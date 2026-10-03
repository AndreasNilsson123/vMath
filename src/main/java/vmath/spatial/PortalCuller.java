package vmath.spatial;

import java.util.Arrays;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4f;
import vmath.geo.DepthRange;

/**
 * Portal culling: finds the sectors of a {@link PortalGraph} that can be seen from a viewpoint through a chain of open portals, and clears the visibility bit of every object that is
 * in none of them or outside the part of the screen through which its sectors are seen.
 *
 * <p><b>The traversal</b> starts in the sector of the eye with the whole screen, the rectangle {@code [-1, 1]^2} of normalised device coordinates. For each open portal of a sector whose
 * front the eye is on, the portal polygon is clipped to the half space in front of the eye, projected, and its bounding rectangle intersected with the rectangle through which the sector is seen; if
 * anything is left, the sector behind the portal is seen through that rectangle. A sector reached by several routes gets the bounding rectangle of all of them, and is processed again
 * only when that rectangle grows, which bounds the work (and a budget of {@code 16 * sectors + 64} growths, after which a sector gets the whole root rectangle, guarantees the end).
 * Nothing is allocated: all memory belongs to the culler and is sized by the graph.
 *
 * <p><b>The object test</b> ({@link #cullObjects}) builds, for each visible sector, the six planes of the view volume narrowed to that sector's rectangle (the near and far planes are the
 * camera's own), and tests the bounds of the sector's objects against them; an object in several sectors survives if it passes in any one.
 *
 * <p><b>Conservative.</b> The result never hides something that can be seen: the rectangles are bounding boxes of the projected polygons (slightly enlarged against rounding), a portal
 * the eye lies in is passed whole, an object whose bounds hold NaN is kept, and an eye outside every sector is the caller's decision ({@link PortalStage} culls nothing then). A
 * rectangle is looser than the exact pyramid of the portal chain, so objects just outside the true view through a doorway may survive. {@link SectorVisibility} can only remove sectors, and
 * is as conservative as what is passed in.
 *
 * <p>One culler per thread; the graph can be shared while nobody changes it. <b>Thread safety.</b> Not thread-safe.
 */
public final class PortalCuller {

    private static final double SIDE_EPS = 1e-4;
    private static final double RECT_EPS = 1e-6;
    private static final double W_MIN = 1e-6;

    private final PortalGraph graph;
    private final double[] rect; // x0, y0, x1, y1 per sector; empty (x0 > x1) until the sector is reached
    private int[] stackSector = new int[64];
    private double[] stackRect = new double[256];
    private int stackSize;
    private final int[] visibleSectors;
    private int visibleCount;
    private final double[] m = new double[16];
    private DepthRange depth = DepthRange.ZERO_TO_ONE;
    private boolean viewSet;
    private SectorVisibility visibility;
    private int start = -1;
    private int visits;
    private boolean budgetExhausted;
    private long budgetOverride = -1;
    private final double[] polyA = new double[4 * (PortalGraph.MAX_PORTAL_VERTICES + 2)], polyB = new double[4 * (PortalGraph.MAX_PORTAL_VERTICES + 2)];
    private final double[] planes = new double[24];
    private final double[] root = new double[4];
    private VisibilitySet kept = new VisibilitySet(64);

    /** A culler for the sectors and portals of {@code graph}. */
    public PortalCuller(PortalGraph graph) {
        this.graph = graph;
        int n = graph.sectorCount();
        rect = new double[4 * n];
        visibleSectors = new int[n];
    }

    /** Narrows the traversal with a precomputed visibility set, or removes the narrowing when {@code null}: sectors that {@code visibility} says cannot be seen from the start sector are not entered. */
    public void setVisibility(SectorVisibility visibility) {
        this.visibility = visibility;
    }

    /**
     * Sets the number of times the traversal may grow the rectangle of a sector before it stops refining (the default is {@code 16 * sectors + 64}, never reached by ordinary levels);
     * a negative value restores the default. A lower value makes a pathological graph finish sooner at the price of looser rectangles.
     */
    public void setGrowthBudget(long growths) {
        this.budgetOverride = growths;
    }

    // ------------------------------------------------------------ the traversal

    /**
     * Sets the view of the next traversals: the view-projection matrix and its depth convention. Calling it once per frame and then {@link #traverse(float, float, float, int)} keeps the
     * matrix out of the per-frame calls.
     */
    public void setView(Mat4f viewProjection, DepthRange depthRange) {
        load(viewProjection);
        depth = depthRange;
        viewSet = true;
    }

    /**
     * Finds the visible sectors for the view-projection matrix {@code viewProjection} (with the depth convention {@code depthRange}) and the eye at {@code (ex, ey, ez)} in the sector
     * {@code startSector}, with the whole screen as the root rectangle. Returns the number of visible sectors, the start sector included.
     */
    public int traverse(Mat4f viewProjection, DepthRange depthRange, float ex, float ey, float ez, int startSector) {
        setView(viewProjection, depthRange);
        return traverse(ex, ey, ez, startSector, -1.0, -1.0, 1.0, 1.0);
    }

    /**
     * {@link #traverse(Mat4f, DepthRange, float, float, float, int)} with a root rectangle in normalised device coordinates, for a view that does not use the whole screen (a portal
     * view, a split-screen half, a scissored viewport). The rectangle is clamped to {@code [-1, 1]^2}.
     */
    public int traverse(Mat4f viewProjection, DepthRange depthRange, float ex, float ey, float ez, int startSector, double x0, double y0, double x1, double y1) {
        setView(viewProjection, depthRange);
        return traverse(ex, ey, ez, startSector, x0, y0, x1, y1);
    }

    /** {@link #traverse(Mat4f, DepthRange, float, float, float, int)} with the view set by {@link #setView}. */
    public int traverse(float ex, float ey, float ez, int startSector) {
        return traverse(ex, ey, ez, startSector, -1.0, -1.0, 1.0, 1.0);
    }

    /** {@link #traverse(Mat4f, DepthRange, float, float, float, int, double, double, double, double)} with the view set by {@link #setView}. */
    public int traverse(float ex, float ey, float ez, int startSector, double x0, double y0, double x1, double y1) {
        if (!viewSet) {
            throw new IllegalStateException("call setView first");
        }
        if (startSector < 0 || startSector >= graph.sectorCount()) {
            throw new IllegalArgumentException("the start sector " + startSector + " is outside [0, " + graph.sectorCount() + ")");
        }
        start = startSector;
        root[0] = Math.max(-1.0, x0);
        root[1] = Math.max(-1.0, y0);
        root[2] = Math.min(1.0, x1);
        root[3] = Math.min(1.0, y1);
        for (int s = 0; s < graph.sectorCount(); s++) {
            rect[4 * s] = Double.POSITIVE_INFINITY;
            rect[4 * s + 1] = Double.POSITIVE_INFINITY;
            rect[4 * s + 2] = Double.NEGATIVE_INFINITY;
            rect[4 * s + 3] = Double.NEGATIVE_INFINITY;
        }
        visibleCount = 0;
        visits = 0;
        budgetExhausted = false;
        stackSize = 0;
        if (!(root[0] <= root[2] && root[1] <= root[3])) {
            return 0;
        }
        push(startSector, root[0], root[1], root[2], root[3]);
        long budget = budgetOverride >= 0 ? budgetOverride : 16L * graph.sectorCount() + 64;
        while (stackSize > 0) {
            stackSize--;
            int s = stackSector[stackSize];
            double ax0 = stackRect[4 * stackSize], ay0 = stackRect[4 * stackSize + 1], ax1 = stackRect[4 * stackSize + 2], ay1 = stackRect[4 * stackSize + 3];
            int r = 4 * s;
            if (rect[r] <= rect[r + 2]) {
                // already reached: only a rectangle that is not inside the one seen so far changes anything
                if (ax0 >= rect[r] && ay0 >= rect[r + 1] && ax1 <= rect[r + 2] && ay1 <= rect[r + 3]) {
                    continue;
                }
                rect[r] = Math.min(rect[r], ax0);
                rect[r + 1] = Math.min(rect[r + 1], ay0);
                rect[r + 2] = Math.max(rect[r + 2], ax1);
                rect[r + 3] = Math.max(rect[r + 3], ay1);
            } else {
                rect[r] = ax0;
                rect[r + 1] = ay0;
                rect[r + 2] = ax1;
                rect[r + 3] = ay1;
                visibleSectors[visibleCount++] = s;
            }
            if (++visits > budget) {
                // a pathological graph: let this sector see the whole root rectangle, which ends the growth, and stays conservative
                budgetExhausted = true;
                rect[r] = root[0];
                rect[r + 1] = root[1];
                rect[r + 2] = root[2];
                rect[r + 3] = root[3];
            }
            expand(s, rect[r], rect[r + 1], rect[r + 2], rect[r + 3], ex, ey, ez);
        }
        return visibleCount;
    }

    private void expand(int s, double x0, double y0, double x1, double y1, float ex, float ey, float ez) {
        float[] plane = graph.portalPlaneArray();
        for (int k = 0; k < graph.portalsOf(s); k++) {
            int p = graph.portalOf(s, k);
            if (!graph.isPortalOpen(p)) {
                continue;
            }
            int other = graph.otherSector(p, s);
            if (visibility != null && other != start && !visibility.isVisible(start, other)) {
                continue;
            }
            double dist = (double) plane[4 * p] * ex + (double) plane[4 * p + 1] * ey + (double) plane[4 * p + 2] * ez + plane[4 * p + 3];
            boolean fromBack = graph.portalSectorA(p) == s; // the normal points from the back sector into the front one
            if (fromBack ? dist > SIDE_EPS : dist < -SIDE_EPS) {
                continue; // the eye is on the wrong side: the portal is seen from behind
            }
            if (Math.abs(dist) <= SIDE_EPS) {
                push(other, x0, y0, x1, y1); // the eye is in the doorway: the other sector is seen whole
                continue;
            }
            if (project(p, x0, y0, x1, y1)) {
                push(other, outX0, outY0, outX1, outY1);
            }
        }
    }

    private double outX0, outY0, outX1, outY1;

    /** Projects portal {@code p}, clipped to the half space in front of the eye, and intersects its bounding rectangle with the given one into the {@code out} fields; false when nothing is left. */
    private boolean project(int p, double x0, double y0, double x1, double y1) {
        float[] v = graph.portalVertexArray();
        int first = graph.portalVertexStart(p), n = graph.portalVertexCount(p);
        double[] in = polyA, out = polyB;
        for (int i = 0; i < n; i++) {
            double x = v[3 * (first + i)], y = v[3 * (first + i) + 1], z = v[3 * (first + i) + 2];
            in[4 * i] = m[0] * x + m[4] * y + m[8] * z + m[12];
            in[4 * i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
            in[4 * i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
            in[4 * i + 3] = m[3] * x + m[7] * y + m[11] * z + m[15];
        }
        // Sutherland-Hodgman against the plane through the eye parallel to the image plane (w = W_MIN), not against the near plane: a ray through a part of the portal that is
        // closer than the near plane still reaches the sector behind it beyond the near plane, so that part must count (the object test applies the real near plane)
        int count = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double di = in[4 * i + 3] - W_MIN, dj = in[4 * j + 3] - W_MIN;
            if (di >= 0) {
                System.arraycopy(in, 4 * i, out, 4 * count++, 4);
            }
            if ((di >= 0) != (dj >= 0)) {
                double t = di / (di - dj);
                for (int c = 0; c < 4; c++) {
                    out[4 * count + c] = in[4 * i + c] + t * (in[4 * j + c] - in[4 * i + c]);
                }
                count++;
            }
        }
        if (count < 3) {
            return false;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double w = out[4 * i + 3];
            if (!(w > 1e-12)) {
                // a vertex at or behind the eye plane that the near clip did not remove (an unusual projection): do not narrow
                outX0 = x0;
                outY0 = y0;
                outX1 = x1;
                outY1 = y1;
                return true;
            }
            double x = out[4 * i] / w, y = out[4 * i + 1] / w;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        outX0 = Math.max(x0, minX - RECT_EPS);
        outY0 = Math.max(y0, minY - RECT_EPS);
        outX1 = Math.min(x1, maxX + RECT_EPS);
        outY1 = Math.min(y1, maxY + RECT_EPS);
        return outX0 <= outX1 && outY0 <= outY1;
    }

    private void load(Mat4f a) {
        m[0] = a.m00();
        m[1] = a.m01();
        m[2] = a.m02();
        m[3] = a.m03();
        m[4] = a.m10();
        m[5] = a.m11();
        m[6] = a.m12();
        m[7] = a.m13();
        m[8] = a.m20();
        m[9] = a.m21();
        m[10] = a.m22();
        m[11] = a.m23();
        m[12] = a.m30();
        m[13] = a.m31();
        m[14] = a.m32();
        m[15] = a.m33();
    }

    private void push(int sector, double x0, double y0, double x1, double y1) {
        if (stackSize == stackSector.length) {
            stackSector = Arrays.copyOf(stackSector, stackSize * 2);
            stackRect = Arrays.copyOf(stackRect, stackSize * 8);
        }
        stackSector[stackSize] = sector;
        stackRect[4 * stackSize] = x0;
        stackRect[4 * stackSize + 1] = y0;
        stackRect[4 * stackSize + 2] = x1;
        stackRect[4 * stackSize + 3] = y1;
        stackSize++;
    }

    // ------------------------------------------------------------ results of the traversal

    /** The number of sectors that the last traversal reached. */
    public int visibleSectorCount() {
        return visibleCount;
    }

    /** The {@code i}-th visible sector of the last traversal, in the order they were first reached, {@code i} below {@link #visibleSectorCount()}. */
    public int visibleSector(int i) {
        return visibleSectors[i];
    }

    /** Whether the last traversal reached sector {@code s}. */
    public boolean isSectorVisible(int s) {
        return rect[4 * s] <= rect[4 * s + 2];
    }

    /**
     * Writes the rectangle in normalised device coordinates through which the last traversal saw sector {@code s} to {@code out[0 .. 4)} as {@code x0, y0, x1, y1}; the sector must be visible.
     */
    public void sectorRect(int s, double[] out) {
        System.arraycopy(rect, 4 * s, out, 0, 4);
    }

    /** Whether the last traversal stopped refining because it ran out of its budget (every sector reached afterwards got the whole root rectangle). It never happens for ordinary levels. */
    public boolean budgetExhausted() {
        return budgetExhausted;
    }

    // ------------------------------------------------------------ the object test

    /**
     * Clears the bit of every object in {@code visible} that is not seen: objects of sectors the last traversal did not reach, and objects outside the narrowed view volume of
     * every sector they belong to. Objects that belong to no sector are kept, or cleared when {@code cullUnassigned} is true. Objects with an index beyond {@code bounds.size()} and the
     * bits that are already clear are not touched.
     */
    public void cullObjects(BoundsArray bounds, VisibilitySet visible, boolean cullUnassigned) {
        int n = Math.min(bounds.size(), visible.capacity());
        if (kept.capacity() < n) {
            kept = new VisibilitySet(Math.max(n, kept.capacity() * 2));
        }
        kept.clearAll();
        long[] keptWords = kept.words();
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs(), x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        for (int i = 0; i < visibleCount; i++) {
            int s = visibleSectors[i];
            narrowedPlanes(s);
            for (int e = graph.firstMember(s); e >= 0; e = graph.nextMember(e)) {
                int o = graph.memberObject(e);
                if (o >= n || !visible.get(o)) {
                    continue;
                }
                if (inside(x0[o], y0[o], z0[o], x1[o], y1[o], z1[o])) {
                    keptWords[o >>> 6] |= 1L << o;
                }
            }
        }
        long[] words = visible.words();
        long[] assigned = graph.assignedWords();
        int wordCount = (n + 63) >>> 6;
        for (int w = 0; w < wordCount; w++) {
            long a = w < assigned.length ? assigned[w] : 0L;
            long keep = cullUnassigned ? keptWords[w] : (keptWords[w] | ~a);
            long mask = (w == wordCount - 1 && (n & 63) != 0) ? (1L << (n & 63)) - 1 : -1L; // the bits of objects beyond n stay as they are
            words[w] &= keep | ~mask;
        }
    }

    /** The six planes of the view volume narrowed to the rectangle of sector {@code s}, unit normals pointing inward, into {@link #planes}. */
    private void narrowedPlanes(int s) {
        int r = 4 * s;
        double rx0 = rect[r], ry0 = rect[r + 1], rx1 = rect[r + 2], ry1 = rect[r + 3];
        // rows of the matrix: row i has the entries m[i], m[4 + i], m[8 + i], m[12 + i]
        for (int c = 0; c < 4; c++) {
            double r0 = m[4 * c], r1 = m[4 * c + 1], r2 = m[4 * c + 2], r3 = m[4 * c + 3];
            planes[c] = r0 - rx0 * r3; // clip X >= x0 W
            planes[4 + c] = rx1 * r3 - r0; // X <= x1 W
            planes[8 + c] = r1 - ry0 * r3;
            planes[12 + c] = ry1 * r3 - r1;
            switch (depth) {
                case NEGATIVE_ONE_TO_ONE:
                    planes[16 + c] = r3 + r2;
                    planes[20 + c] = r3 - r2;
                    break;
                case ZERO_TO_ONE:
                    planes[16 + c] = r2;
                    planes[20 + c] = r3 - r2;
                    break;
                default:
                    planes[16 + c] = r3 - r2;
                    planes[20 + c] = r2;
                    break;
            }
        }
        for (int k = 0; k < 6; k++) {
            double len = Math.sqrt(planes[4 * k] * planes[4 * k] + planes[4 * k + 1] * planes[4 * k + 1] + planes[4 * k + 2] * planes[4 * k + 2]);
            if (len > 1e-12) {
                for (int c = 0; c < 4; c++) {
                    planes[4 * k + c] /= len;
                }
            } else {
                // a degenerate plane (the far plane of an infinite projection): everything is inside
                planes[4 * k] = planes[4 * k + 1] = planes[4 * k + 2] = 0;
                planes[4 * k + 3] = 1;
            }
        }
    }

    private boolean inside(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        for (int k = 0; k < 6; k++) {
            double a = planes[4 * k], b = planes[4 * k + 1], c = planes[4 * k + 2], d = planes[4 * k + 3];
            double v = a * (a >= 0 ? maxX : minX) + b * (b >= 0 ? maxY : minY) + c * (c >= 0 ? maxZ : minZ) + d;
            // a NaN compares false: an object that cannot be judged stays
            if (v < -1e-6 * (1.0 + Math.abs(d) + Math.abs(maxX) + Math.abs(maxY) + Math.abs(maxZ))) {
                return false;
            }
        }
        return true;
    }
}
