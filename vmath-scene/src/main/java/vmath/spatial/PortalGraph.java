package vmath.spatial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import vmath.bulk.BoundsArray;
import vmath.geo.Aabbf;

/**
 * The structure of an interior for portal culling: <b>sectors</b> (rooms, cells: convex volumes),
 * <b>portals</b> (convex polygons in the openings between two sectors: doors, windows, archways)
 * and the <b>membership</b> of objects in sectors.
 *
 * <p>Portal culling starts in the sector that holds the camera and only looks into a neighbouring
 * sector through a portal that is visible, so everything behind walls is never touched, however
 * many objects there are; {@link PortalCuller} does the traversal and the object test.
 *
 * <p><b>Sectors.</b> A sector is a convex volume given by inward-facing planes {@code (a, b, c, d)}
 * with {@code a x + b y + c z + d >= 0} inside ({@link Builder#addConvex}), or an axis-aligned box
 * ({@link Builder#addBox}). Sectors must not overlap except along shared faces; their union need
 * not be convex. {@link #locate} finds the sector that holds a point, using the sector of the
 * previous frame as a hint.
 *
 * <p><b>Portals.</b> A portal joins sector {@code a} to sector {@code b}: a planar convex polygon
 * of 3 to {@value #MAX_PORTAL_VERTICES} vertices on their shared boundary, whose normal points from
 * {@code a} into {@code b}. {@link Builder#addPortal} works out the direction from the sectors;
 * {@link Builder#autoPortals} finds all the openings between box sectors that touch. A portal can
 * be <b>closed</b> and opened again at run time ({@link #setPortalOpen}): a shut door hides the
 * room behind it from the traversal.
 *
 * <p><b>Membership.</b> An object belongs to the sectors its bounds overlap ({@link #assignAll},
 * {@link #assign}); a box straddling a doorway belongs to both rooms. Moving objects are
 * re-assigned with {@link #update}, in time proportional to the sectors involved. Objects that
 * belong to no sector are <em>unassigned</em>: {@link PortalCuller#cullObjects} leaves them alone
 * unless told otherwise (an object that the graph does not know is never culled by accident).
 *
 * <p>The topology (sectors and portals) is fixed once built; the open flags and the membership
 * change between frames. <b>Thread safety.</b> Not thread-safe for changes; any number of
 * {@link PortalCuller}s may read it at once while nobody changes it.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * PortalGraph.Builder builder = PortalGraph.builder();
 * int room = builder.addBox(Aabbf.of(new Vec3f(0f, 0f, 0f), new Vec3f(10f, 3f, 10f)));
 * int hall = builder.addBox(Aabbf.of(new Vec3f(10f, 0f, 0f), new Vec3f(20f, 3f, 10f)));
 * builder.autoPortals(0.01f);
 * PortalGraph graph = builder.build();
 * int where = graph.locate(5f, 1f, 5f);                                      // the sector containing the point
 * graph.assign(0, hall);                                                     // object 0 belongs to the hall
 * }</pre>
 */
public final class PortalGraph {

    /**
     * The largest number of vertices of a portal polygon.
     */
    public static final int MAX_PORTAL_VERTICES = 16;

    private static final float INSIDE_EPS = 1e-5f;

    private final int sectorCount;
    private final float[] planes; // 4 floats per plane, unit normals pointing inward
    private final int[] planeStart; // sector s owns the planes planeStart[s] .. planeStart[s + 1] - 1
    private final float[] boxes; // 6 floats per sector for box sectors, NaN otherwise
    private final float[] sectorBounds; // 6 floats per sector: the bounding box, enlarged by a tolerance; infinite for an unbounded sector
    private final int[] unbounded; // the sectors without a finite bounding box, tested for every query
    private final float[] gridMin = new float[3], gridCell = new float[3];
    private final int[] gridDims = new int[3];
    private int[] cellStart = new int[1], cellSectors = new int[0]; // the sectors whose bounding box touches each cell, in ascending order
    private final int[] stamp;
    private int stampValue;

    private final int portalCount;
    private final int[] portalA, portalB;
    private final int[] vertexStart; // portal p owns the vertices vertexStart[p] .. vertexStart[p + 1] - 1
    private final float[] vertices; // xyz triples
    private final float[] portalPlane; // 4 floats per portal: unit normal from a to b, and d, so that n . x + d = 0 on the portal
    private final boolean[] open;

    private final int[] adjacencyStart; // sector s touches the portals adjacency[adjacencyStart[s] .. adjacencyStart[s + 1] - 1]
    private final int[] adjacency;

    // membership: entries in two linked lists each, one per sector and one per object
    private int[] entryObject = new int[64], entrySector = new int[64], entryNextInSector = new int[64], entryPrevInSector = new int[64], entryNextOfObject = new int[64];
    private int entryCount, freeEntry = -1;
    private final int[] sectorHead;
    private int[] objectHead = new int[64];
    private final int[] sectorMembers;
    private long[] assigned = new long[1];

    private PortalGraph(Builder b) {
        sectorCount = b.sectorPlanes.size();
        int totalPlanes = 0;
        for (float[] p : b.sectorPlanes) {
            totalPlanes += p.length / 4;
        }
        planes = new float[4 * totalPlanes];
        planeStart = new int[sectorCount + 1];
        boxes = new float[6 * sectorCount];
        int at = 0;
        for (int s = 0; s < sectorCount; s++) {
            planeStart[s] = at / 4;
            float[] p = b.sectorPlanes.get(s);
            System.arraycopy(p, 0, planes, at, p.length);
            at += p.length;
            Aabbf box = b.sectorBoxes.get(s);
            for (int k = 0; k < 6; k++) {
                boxes[6 * s + k] = box == null ? Float.NaN : k == 0 ? box.minX() : k == 1 ? box.minY() : k == 2 ? box.minZ() : k == 3 ? box.maxX() : k == 4 ? box.maxY() : box.maxZ();
            }
        }
        planeStart[sectorCount] = totalPlanes;
        sectorBounds = new float[6 * sectorCount];
        stamp = new int[sectorCount];
        java.util.ArrayList<Integer> unboundedList = new java.util.ArrayList<>();
        for (int s = 0; s < sectorCount; s++) {
            if (!computeBounds(s)) {
                unboundedList.add(s);
            }
        }
        unbounded = new int[unboundedList.size()];
        for (int i = 0; i < unbounded.length; i++) {
            unbounded[i] = unboundedList.get(i);
        }
        buildGrid();
        portalCount = b.portalA.size();
        portalA = new int[portalCount];
        portalB = new int[portalCount];
        vertexStart = new int[portalCount + 1];
        portalPlane = new float[4 * portalCount];
        open = new boolean[portalCount];
        int totalVertices = 0;
        for (float[] v : b.portalVertices) {
            totalVertices += v.length / 3;
        }
        vertices = new float[3 * totalVertices];
        int vat = 0;
        for (int p = 0; p < portalCount; p++) {
            portalA[p] = b.portalA.get(p);
            portalB[p] = b.portalB.get(p);
            vertexStart[p] = vat / 3;
            float[] v = b.portalVertices.get(p);
            System.arraycopy(v, 0, vertices, vat, v.length);
            vat += v.length;
            System.arraycopy(b.portalPlanes.get(p), 0, portalPlane, 4 * p, 4);
            open[p] = true;
        }
        vertexStart[portalCount] = totalVertices;
        adjacencyStart = new int[sectorCount + 1];
        for (int p = 0; p < portalCount; p++) {
            adjacencyStart[portalA[p] + 1]++;
            adjacencyStart[portalB[p] + 1]++;
        }
        for (int s = 0; s < sectorCount; s++) {
            adjacencyStart[s + 1] += adjacencyStart[s];
        }
        adjacency = new int[2 * portalCount];
        int[] fill = Arrays.copyOf(adjacencyStart, sectorCount);
        for (int p = 0; p < portalCount; p++) {
            adjacency[fill[portalA[p]]++] = p;
            adjacency[fill[portalB[p]]++] = p;
        }
        sectorHead = new int[sectorCount];
        Arrays.fill(sectorHead, -1);
        sectorMembers = new int[sectorCount];
        Arrays.fill(objectHead, -1);
    }

    /**
     * Starts a builder for a portal graph.
     *
     * @return a builder for a new graph
     */
    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------ the grid over the sectors

    /**
     * The bounding box of sector {@code s} into {@link #sectorBounds}: the box itself for a box
     * sector, otherwise the extent of the vertices of the convex volume (the intersection points of
     * every three planes that lie inside all of them).
     *
     * <p>Returns false when the sector has no finite box (unbounded or degenerate).
     */
    private boolean computeBounds(int s) {
        int first = planeStart[s], last = planeStart[s + 1];
        double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        if (!Float.isNaN(boxes[6 * s])) {
            for (int k = 0; k < 3; k++) {
                lo[k] = boxes[6 * s + k];
                hi[k] = boxes[6 * s + 3 + k];
            }
        } else if (isBounded(first, last)) {
            for (int i = first; i < last; i++) {
                for (int j = i + 1; j < last; j++) {
                    for (int k = j + 1; k < last; k++) {
                        double[] v = intersect(i, j, k);
                        if (v == null) {
                            continue;
                        }
                        boolean inside = true;
                        for (int q = first; q < last && inside; q++) {
                            inside = planes[4 * q] * v[0] + planes[4 * q + 1] * v[1] + planes[4 * q + 2] * v[2] + planes[4 * q + 3] >= -1e-4;
                        }
                        if (inside) {
                            for (int c = 0; c < 3; c++) {
                                lo[c] = Math.min(lo[c], v[c]);
                                hi[c] = Math.max(hi[c], v[c]);
                            }
                        }
                    }
                }
            }
        }
        boolean finite = true;
        for (int c = 0; c < 3; c++) {
            finite &= lo[c] <= hi[c] && Double.isFinite(lo[c]) && Double.isFinite(hi[c]);
        }
        for (int c = 0; c < 3; c++) {
            sectorBounds[6 * s + c] = finite ? (float) lo[c] - 1e-3f : Float.NEGATIVE_INFINITY;
            sectorBounds[6 * s + 3 + c] = finite ? (float) hi[c] + 1e-3f : Float.POSITIVE_INFINITY;
        }
        return finite;
    }

    /**
     * Whether the volume of the planes {@code first .. last - 1} is bounded: it is exactly when no
     * non-zero direction {@code d} has {@code n . d >= 0} for every normal {@code n}.
     *
     * <p>If there is one, there is one of the form (a normal crossed with another), so only those
     * are tried; planes whose normals are all parallel never bound a volume.
     */
    private boolean isBounded(int first, int last) {
        boolean anyPair = false;
        for (int i = first; i < last; i++) {
            for (int j = i + 1; j < last; j++) {
                double dx = planes[4 * i + 1] * planes[4 * j + 2] - planes[4 * i + 2] * planes[4 * j + 1];
                double dy = planes[4 * i + 2] * planes[4 * j] - planes[4 * i] * planes[4 * j + 2];
                double dz = planes[4 * i] * planes[4 * j + 1] - planes[4 * i + 1] * planes[4 * j];
                if (dx * dx + dy * dy + dz * dz < 1e-12) {
                    continue;
                }
                anyPair = true;
                for (int sign = -1; sign <= 1; sign += 2) {
                    boolean all = true;
                    for (int q = first; q < last && all; q++) {
                        all = sign * (planes[4 * q] * dx + planes[4 * q + 1] * dy + planes[4 * q + 2] * dz) >= -1e-9;
                    }
                    if (all) {
                        return false;
                    }
                }
            }
        }
        return anyPair;
    }

    /**
     * The point where the planes {@code i}, {@code j} and {@code k} meet, or null when they do not
     * meet in a single point.
     */
    private double[] intersect(int i, int j, int k) {
        double a1 = planes[4 * i], b1 = planes[4 * i + 1], c1 = planes[4 * i + 2], d1 = -planes[4 * i + 3];
        double a2 = planes[4 * j], b2 = planes[4 * j + 1], c2 = planes[4 * j + 2], d2 = -planes[4 * j + 3];
        double a3 = planes[4 * k], b3 = planes[4 * k + 1], c3 = planes[4 * k + 2], d3 = -planes[4 * k + 3];
        double det = a1 * (b2 * c3 - b3 * c2) - b1 * (a2 * c3 - a3 * c2) + c1 * (a2 * b3 - a3 * b2);
        if (Math.abs(det) < 1e-9) {
            return null;
        }
        return new double[] {
                (d1 * (b2 * c3 - b3 * c2) - b1 * (d2 * c3 - d3 * c2) + c1 * (d2 * b3 - d3 * b2)) / det,
                (a1 * (d2 * c3 - d3 * c2) - d1 * (a2 * c3 - a3 * c2) + c1 * (a2 * d3 - a3 * d2)) / det,
                (a1 * (b2 * d3 - b3 * d2) - b1 * (a2 * d3 - a3 * d2) + d1 * (a2 * b3 - a3 * b2)) / det};
    }

    /**
     * A uniform grid over the bounded sectors, with cells about as large as a typical sector,
     * listing for each cell the sectors whose box touches it.
     */
    private void buildGrid() {
        float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY}, hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        double[] mean = new double[3];
        int bounded = 0;
        for (int s = 0; s < sectorCount; s++) {
            if (Float.isInfinite(sectorBounds[6 * s])) {
                continue;
            }
            bounded++;
            for (int c = 0; c < 3; c++) {
                lo[c] = Math.min(lo[c], sectorBounds[6 * s + c]);
                hi[c] = Math.max(hi[c], sectorBounds[6 * s + 3 + c]);
                mean[c] += sectorBounds[6 * s + 3 + c] - sectorBounds[6 * s + c];
            }
        }
        if (bounded == 0) {
            gridDims[0] = gridDims[1] = gridDims[2] = 0;
            return;
        }
        for (int c = 0; c < 3; c++) {
            float extent = Math.max(hi[c] - lo[c], 1e-3f);
            float cell = (float) Math.max(mean[c] / bounded, extent / 64.0);
            gridMin[c] = lo[c];
            gridCell[c] = cell;
            gridDims[c] = Math.max(1, (int) Math.ceil(extent / cell));
        }
        int cells = gridDims[0] * gridDims[1] * gridDims[2];
        int[] counts = new int[cells + 1];
        int[] fill = null;
        for (int pass = 0; pass < 2; pass++) {
            for (int s = 0; s < sectorCount; s++) {
                if (Float.isInfinite(sectorBounds[6 * s])) {
                    continue;
                }
                int x0 = cellOf(0, sectorBounds[6 * s]), y0 = cellOf(1, sectorBounds[6 * s + 1]), z0 = cellOf(2, sectorBounds[6 * s + 2]);
                int x1 = cellOf(0, sectorBounds[6 * s + 3]), y1 = cellOf(1, sectorBounds[6 * s + 4]), z1 = cellOf(2, sectorBounds[6 * s + 5]);
                for (int z = z0; z <= z1; z++) {
                    for (int y = y0; y <= y1; y++) {
                        for (int x = x0; x <= x1; x++) {
                            int cell = (z * gridDims[1] + y) * gridDims[0] + x;
                            if (pass == 0) {
                                counts[cell + 1]++;
                            } else {
                                cellSectors[fill[cell]++] = s;
                            }
                        }
                    }
                }
            }
            if (pass == 0) {
                for (int i = 0; i < cells; i++) {
                    counts[i + 1] += counts[i];
                }
                cellStart = counts;
                cellSectors = new int[counts[cells]];
                fill = Arrays.copyOf(counts, cells);
            }
        }
    }

    private int cellOf(int axis, float v) {
        int c = (int) Math.floor((v - gridMin[axis]) / gridCell[axis]);
        return Math.max(0, Math.min(gridDims[axis] - 1, c));
    }

    // ------------------------------------------------------------ topology

    /**
     * Counts the sectors of the graph.
     *
     * @return the number of sectors
     */
    public int sectorCount() {
        return sectorCount;
    }

    /**
     * Counts the portals of the graph.
     *
     * @return the number of portals
     */
    public int portalCount() {
        return portalCount;
    }

    /**
     * Reads the sector behind a portal, which traversal can only enter from the front.
     *
     * @param p the portal index
     * @return the sector on the back of portal {@code p}: its normal points away from this one, and
     *     traversal from it needs the eye on this side
     */
    public int portalSectorA(int p) {
        return portalA[p];
    }

    /**
     * Reads the sector in front of a portal, which is the side its normal points into.
     *
     * @param p the portal index
     * @return the sector in front of portal {@code p}: the one its normal points into
     */
    public int portalSectorB(int p) {
        return portalB[p];
    }

    /**
     * Counts the vertices of a portal polygon.
     *
     * @param p the portal index
     * @return the number of vertices of portal {@code p}
     */
    public int portalVertexCount(int p) {
        return vertexStart[p + 1] - vertexStart[p];
    }

    /**
     * Copies the vertices of portal {@code p} ({@code x, y, z} triples) to {@code out}, which needs
     * room for {@code 3 * portalVertexCount(p)} floats.
     *
     * @param p the portal index
     * @param out receives the result
     */
    public void portalVertices(int p, float[] out) {
        System.arraycopy(vertices, 3 * vertexStart[p], out, 0, 3 * portalVertexCount(p));
    }

    /**
     * Writes the plane {@code (nx, ny, nz, d)} of portal {@code p} to {@code out[0 .. 4)}: a unit
     * normal from sector A to sector B, and {@code n . x + d = 0} on the portal.
     *
     * @param p the portal index
     * @param out receives the result
     */
    public void portalPlane(int p, float[] out) {
        System.arraycopy(portalPlane, 4 * p, out, 0, 4);
    }

    /**
     * Counts the portals that touch a sector.
     *
     * @param s the sector index
     * @return the number of portals that touch sector {@code s}
     */
    public int portalsOf(int s) {
        return adjacencyStart[s + 1] - adjacencyStart[s];
    }

    /**
     * Reads a portal of a sector by position in its portal list.
     *
     * @param s the sector index
     * @param k the index of the portal among those of the sector
     * @return the {@code k}-th portal of sector {@code s}, {@code k} below {@link #portalsOf}
     */
    public int portalOf(int s, int k) {
        return adjacency[adjacencyStart[s] + k];
    }

    /**
     * Maps a portal and one of its sectors to the sector on the other side.
     *
     * @param p the portal index
     * @param s the sector index
     * @return the sector on the other side of portal {@code p} from sector {@code s}
     */
    public int otherSector(int p, int s) {
        return portalA[p] == s ? portalB[p] : portalA[p];
    }

    /**
     * Returns whether portal {@code p} is open (the default).
     *
     * @param p the portal index
     * @return {@code true} if portal {@code p} is open (the default)
     */
    public boolean isPortalOpen(int p) {
        return open[p];
    }

    /**
     * Opens or closes portal {@code p}: a closed portal blocks the traversal, as a shut door does.
     *
     * @param p the portal index
     * @param isOpen whether is open
     */
    public void setPortalOpen(int p, boolean isOpen) {
        open[p] = isOpen;
    }

    // ------------------------------------------------------------ locating points

    /**
     * Returns whether the sector contains the point: it is on the inner side of every plane of the
     * sector, up to a tolerance of 1e-5 (points on a shared face belong to both sectors).
     *
     * @param s the sector index
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return {@code true} if the sector contains the point: it is on the inner side of every plane
     *     of the sector, up to a tolerance of 1e-5 (points on a shared face belong to both sectors)
     */
    public boolean contains(int s, float x, float y, float z) {
        for (int k = planeStart[s]; k < planeStart[s + 1]; k++) {
            if (planes[4 * k] * x + planes[4 * k + 1] * y + planes[4 * k + 2] * z + planes[4 * k + 3] < -INSIDE_EPS) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the sector that contains a point by testing the sectors in order; the cost grows with
     * the number of sectors, so prefer the overload with a hint when the previous position is
     * known.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return the first sector (lowest index) that contains the point, or -1 when it is in none
     *     (outside the level)
     */
    public int locate(float x, float y, float z) {
        int best = Integer.MAX_VALUE;
        for (int s : unbounded) {
            if (s < best && contains(s, x, y, z)) {
                best = s;
            }
        }
        if (gridDims[0] > 0 && x >= gridMin[0] - 1e-3f && y >= gridMin[1] - 1e-3f && z >= gridMin[2] - 1e-3f) {
            int cx = cellOf(0, x), cy = cellOf(1, y), cz = cellOf(2, z);
            int cell = (cz * gridDims[1] + cy) * gridDims[0] + cx;
            for (int k = cellStart[cell]; k < cellStart[cell + 1]; k++) {
                int s = cellSectors[k];
                if (s >= best) {
                    break; // the cell lists ascend
                }
                if (contains(s, x, y, z)) {
                    best = s;
                    break;
                }
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /**
     * Finds the sector that contains a point, trying the previous sector and its neighbours first,
     * which is nearly constant time when the camera moves continuously.
     *
     * <p>A negative or out-of-range hint is ignored.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param hint the hint
     * @return {@link #locate(float, float, float)} that tries the sector {@code hint} (the one of
     *     the previous frame) first, then its neighbours through portals, and only then all
     *     sectors: for a camera that moves a little each frame, the answer is found in the first
     *     step
     */
    public int locate(float x, float y, float z, int hint) {
        if (hint >= 0 && hint < sectorCount) {
            if (contains(hint, x, y, z)) {
                return hint;
            }
            for (int k = adjacencyStart[hint]; k < adjacencyStart[hint + 1]; k++) {
                int n = otherSector(adjacency[k], hint);
                if (contains(n, x, y, z)) {
                    return n;
                }
            }
        }
        return locate(x, y, z);
    }

    /**
     * Returns whether the box may overlap the sector: it overlaps the bounding box of the sector
     * and is not entirely outside any plane of it (conservative: a box near a corner may be
     * reported although it misses the sector).
     *
     * @param s the sector index
     * @param minX the smallest x coordinate
     * @param minY the smallest y coordinate
     * @param minZ the smallest z coordinate
     * @param maxX the largest x coordinate
     * @param maxY the largest y coordinate
     * @param maxZ the largest z coordinate
     * @return {@code true} if the box may overlap the sector: it overlaps the bounding box of the
     *     sector and is not entirely outside any plane of it (conservative: a box near a corner may
     *     be reported although it misses the sector)
     */
    public boolean mayOverlap(int s, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int sb = 6 * s;
        if (minX > sectorBounds[sb + 3] || maxX < sectorBounds[sb] || minY > sectorBounds[sb + 4] || maxY < sectorBounds[sb + 1] || minZ > sectorBounds[sb + 5] || maxZ < sectorBounds[sb + 2]) {
            return false; // beyond the bounding box of the sector (infinite for an unbounded one)
        }
        for (int k = planeStart[s]; k < planeStart[s + 1]; k++) {
            float a = planes[4 * k], b = planes[4 * k + 1], c = planes[4 * k + 2], d = planes[4 * k + 3];
            float v = a * (a >= 0 ? maxX : minX) + b * (b >= 0 ? maxY : minY) + c * (c >= 0 ? maxZ : minZ) + d;
            if (v < -INSIDE_EPS) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ plane access for the culler

    int sectorPlaneCount(int s) {
        return planeStart[s + 1] - planeStart[s];
    }

    float[] portalVertexArray() {
        return vertices;
    }

    int portalVertexStart(int p) {
        return vertexStart[p];
    }

    float[] portalPlaneArray() {
        return portalPlane;
    }

    // ------------------------------------------------------------ membership

    /**
     * Adds {@code object} to {@code sector}.
     *
     * <p>An object may belong to several sectors; adding it twice to the same one lists it twice
     * (harmless, but wasteful).
     *
     * @param object the object
     * @param sector the sector
     * @throws IllegalArgumentException if {@code object} or {@code sector} is out of range
     */
    public void assign(int object, int sector) {
        if (object < 0 || sector < 0 || sector >= sectorCount) {
            throw new IllegalArgumentException("object " + object + ", sector " + sector + " (the graph has " + sectorCount + " sectors)");
        }
        ensureObject(object);
        int e;
        if (freeEntry >= 0) {
            e = freeEntry;
            freeEntry = entryNextInSector[e];
        } else {
            if (entryCount == entryObject.length) {
                int n = entryCount * 2;
                entryObject = Arrays.copyOf(entryObject, n);
                entrySector = Arrays.copyOf(entrySector, n);
                entryNextInSector = Arrays.copyOf(entryNextInSector, n);
                entryPrevInSector = Arrays.copyOf(entryPrevInSector, n);
                entryNextOfObject = Arrays.copyOf(entryNextOfObject, n);
            }
            e = entryCount++;
        }
        entryObject[e] = object;
        entrySector[e] = sector;
        entryNextInSector[e] = sectorHead[sector];
        entryPrevInSector[e] = -1;
        if (sectorHead[sector] >= 0) {
            entryPrevInSector[sectorHead[sector]] = e;
        }
        sectorHead[sector] = e;
        entryNextOfObject[e] = objectHead[object];
        objectHead[object] = e;
        sectorMembers[sector]++;
        assigned[object >>> 6] |= 1L << object;
    }

    private void ensureObject(int object) {
        if (object >= objectHead.length) {
            int old = objectHead.length;
            int n = Math.max(object + 1, old * 2);
            objectHead = Arrays.copyOf(objectHead, n);
            Arrays.fill(objectHead, old, n, -1);
        }
        int words = (object >>> 6) + 1;
        if (words > assigned.length) {
            assigned = Arrays.copyOf(assigned, Math.max(words, assigned.length * 2));
        }
    }

    /**
     * Removes {@code object} from every sector.
     *
     * <p>An object that belongs to none is left as it is.
     *
     * @param object the object
     */
    public void remove(int object) {
        if (object < 0 || object >= objectHead.length) {
            return;
        }
        for (int e = objectHead[object]; e >= 0; ) {
            int next = entryNextOfObject[e];
            int s = entrySector[e];
            // unlink from the sector's list
            if (entryPrevInSector[e] >= 0) {
                entryNextInSector[entryPrevInSector[e]] = entryNextInSector[e];
            } else {
                sectorHead[s] = entryNextInSector[e];
            }
            if (entryNextInSector[e] >= 0) {
                entryPrevInSector[entryNextInSector[e]] = entryPrevInSector[e];
            }
            sectorMembers[s]--;
            entryNextInSector[e] = freeEntry;
            freeEntry = e;
            e = next;
        }
        objectHead[object] = -1;
        assigned[object >>> 6] &= ~(1L << object);
    }

    /**
     * Removes every object from every sector.
     */
    public void clearObjects() {
        Arrays.fill(sectorHead, -1);
        Arrays.fill(sectorMembers, 0);
        Arrays.fill(objectHead, -1);
        Arrays.fill(assigned, 0L);
        entryCount = 0;
        freeEntry = -1;
    }

    /**
     * Adds {@code object} with the given bounds to every sector it may overlap
     * ({@link #mayOverlap}) and returns the number of sectors.
     *
     * <p>When there is none, the object stays unassigned.
     *
     * @param object the object
     * @param minX the smallest x coordinate
     * @param minY the smallest y coordinate
     * @param minZ the smallest z coordinate
     * @param maxX the largest x coordinate
     * @param maxY the largest y coordinate
     * @param maxZ the largest z coordinate
     * @return the number of sectors
     */
    public int assignByBounds(int object, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int n = 0;
        if (++stampValue == Integer.MAX_VALUE) {
            Arrays.fill(stamp, 0);
            stampValue = 1;
        }
        for (int s : unbounded) {
            stamp[s] = stampValue;
            if (mayOverlap(s, minX, minY, minZ, maxX, maxY, maxZ)) {
                assign(object, s);
                n++;
            }
        }
        if (gridDims[0] > 0 && maxX >= gridMin[0] - 1e-3f && maxY >= gridMin[1] - 1e-3f && maxZ >= gridMin[2] - 1e-3f) {
            int x0 = cellOf(0, minX), y0 = cellOf(1, minY), z0 = cellOf(2, minZ), x1 = cellOf(0, maxX), y1 = cellOf(1, maxY), z1 = cellOf(2, maxZ);
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        int cell = (z * gridDims[1] + y) * gridDims[0] + x;
                        for (int k = cellStart[cell]; k < cellStart[cell + 1]; k++) {
                            int s = cellSectors[k];
                            if (stamp[s] != stampValue) {
                                stamp[s] = stampValue;
                                if (mayOverlap(s, minX, minY, minZ, maxX, maxY, maxZ)) {
                                    assign(object, s);
                                    n++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return n;
    }

    /**
     * Assigns every object to the sectors it overlaps, replacing earlier assignments.
     *
     * <p>Returns the number of objects that belong to no sector.
     *
     * @param bounds the bounds; must not be {@code null}
     * @return {@link #assignByBounds} for every object of {@code bounds}, after clearing all
     *     earlier membership
     */
    public int assignAll(BoundsArray bounds) {
        clearObjects();
        int unassigned = 0;
        for (int i = 0; i < bounds.size(); i++) {
            if (assignByBounds(i, bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i)) == 0) {
                unassigned++;
            }
        }
        return unassigned;
    }

    /**
     * Re-assigns {@code object} after it moved: removes it everywhere and adds it to the sectors
     * its new bounds overlap.
     *
     * <p>Returns the number of sectors.
     *
     * @param object the object
     * @param minX the smallest x coordinate
     * @param minY the smallest y coordinate
     * @param minZ the smallest z coordinate
     * @param maxX the largest x coordinate
     * @param maxY the largest y coordinate
     * @param maxZ the largest z coordinate
     * @return the number of sectors
     */
    public int update(int object, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        remove(object);
        return assignByBounds(object, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Counts the entries of a sector; an object that overlaps several sectors is counted once per
     * sector.
     *
     * @param s the sector index
     * @return the number of entries in sector {@code s}: objects that overlap several sectors are
     *     counted once per sector
     */
    public int memberCount(int s) {
        return sectorMembers[s];
    }

    /**
     * Returns whether {@code object} belongs to at least one sector.
     *
     * @param object the object
     * @return {@code true} if {@code object} belongs to at least one sector
     */
    public boolean isAssigned(int object) {
        return object >= 0 && (object >>> 6) < assigned.length && (assigned[object >>> 6] & (1L << object)) != 0;
    }

    int firstMember(int s) {
        return sectorHead[s];
    }

    int nextMember(int entry) {
        return entryNextInSector[entry];
    }

    int memberObject(int entry) {
        return entryObject[entry];
    }

    long[] assignedWords() {
        return assigned;
    }

    // ------------------------------------------------------------ the builder

    /**
     * Collects sectors and portals; {@link #build()} makes the graph.
     */
    public static final class Builder {

        private final List<float[]> sectorPlanes = new ArrayList<>();
        private final List<Aabbf> sectorBoxes = new ArrayList<>();
        private final List<Integer> portalA = new ArrayList<>(), portalB = new ArrayList<>();
        private final List<float[]> portalVertices = new ArrayList<>(), portalPlanes = new ArrayList<>();

        private Builder() {
        }

        /**
         * Adds an axis-aligned box sector and returns its index.
         *
         * <p>An empty or flat box is rejected.
         *
         * @param box the box; must not be {@code null}
         * @return its index
         * @throws IllegalArgumentException if the box does not have a positive extent along every
         *     axis
         */
        public int addBox(Aabbf box) {
            if (box.isEmpty() || !(box.maxX() > box.minX()) || !(box.maxY() > box.minY()) || !(box.maxZ() > box.minZ())) {
                throw new IllegalArgumentException("a sector box needs a positive extent along every axis: " + box);
            }
            float[] p = {
                    1, 0, 0, -box.minX(), -1, 0, 0, box.maxX(),
                    0, 1, 0, -box.minY(), 0, -1, 0, box.maxY(),
                    0, 0, 1, -box.minZ(), 0, 0, -1, box.maxZ()};
            sectorPlanes.add(p);
            sectorBoxes.add(box);
            return sectorPlanes.size() - 1;
        }

        /**
         * Adds a convex sector bounded by {@code count} planes ({@code a, b, c, d} quadruples with
         * {@code a x + b y + c z + d >= 0} inside; the normals need not be unit) and returns its
         * index.
         *
         * <p>At least four planes are needed to enclose a volume.
         *
         * @param planes the planes
         * @param count the number of elements
         * @return its index
         * @throws IllegalArgumentException if there are fewer than 4 planes, the array is too short
         *     or a plane has no normal
         */
        public int addConvex(float[] planes, int count) {
            if (count < 4 || planes.length < 4 * count) {
                throw new IllegalArgumentException("a convex sector needs at least 4 planes in an array of 4 floats each: " + count);
            }
            float[] p = new float[4 * count];
            for (int k = 0; k < count; k++) {
                float a = planes[4 * k], b = planes[4 * k + 1], c = planes[4 * k + 2], d = planes[4 * k + 3];
                float len = (float) Math.sqrt(a * a + b * b + c * c);
                if (!(len > 0f)) {
                    throw new IllegalArgumentException("plane " + k + " has no normal");
                }
                p[4 * k] = a / len;
                p[4 * k + 1] = b / len;
                p[4 * k + 2] = c / len;
                p[4 * k + 3] = d / len;
            }
            sectorPlanes.add(p);
            sectorBoxes.add(null);
            return sectorPlanes.size() - 1;
        }

        /**
         * Adds a portal between sectors {@code a} and {@code b}: the convex planar polygon of
         * {@code count} vertices ({@code x, y, z} triples, either winding) on their shared
         * boundary.
         *
         * <p>The normal's direction, from {@code a} into {@code b}, is found by testing which side
         * of the polygon each sector lies on; {@link IllegalArgumentException} when that cannot be
         * decided (use the overload with the normal). Returns the portal's index.
         *
         * @param a the index of the first sector
         * @param b the index of the second sector
         * @param polygon the polygon
         * @param count the number of elements
         * @return the portal's index
         * @throws IllegalArgumentException if the side of the portal that sector {@code a} lies on
         *     cannot be told: give the normal explicitly
         */
        public int addPortal(int a, int b, float[] polygon, int count) {
            double[] n = newell(polygon, count);
            double cx = 0, cy = 0, cz = 0, size = 0;
            for (int i = 0; i < count; i++) {
                cx += polygon[3 * i];
                cy += polygon[3 * i + 1];
                cz += polygon[3 * i + 2];
            }
            cx /= count;
            cy /= count;
            cz /= count;
            for (int i = 0; i < count; i++) {
                size = Math.max(size, Math.abs(polygon[3 * i] - cx) + Math.abs(polygon[3 * i + 1] - cy) + Math.abs(polygon[3 * i + 2] - cz));
            }
            double delta = Math.max(1e-3 * size, 1e-4);
            boolean forward = insideSector(a, cx - n[0] * delta, cy - n[1] * delta, cz - n[2] * delta) && insideSector(b, cx + n[0] * delta, cy + n[1] * delta, cz + n[2] * delta);
            boolean backward = insideSector(a, cx + n[0] * delta, cy + n[1] * delta, cz + n[2] * delta) && insideSector(b, cx - n[0] * delta, cy - n[1] * delta, cz - n[2] * delta);
            if (forward == backward) {
                throw new IllegalArgumentException("cannot tell which side of the portal sector " + a + " lies on: give the normal explicitly");
            }
            double s = forward ? 1 : -1;
            return addPortal(a, b, polygon, count, (float) (s * n[0]), (float) (s * n[1]), (float) (s * n[2]));
        }

        /**
         * Adds a portal with an explicit normal, which fixes which side counts as front; the normal
         * is normalised.
         *
         * @param a the index of the first sector
         * @param b the index of the second sector
         * @param polygon the polygon
         * @param count the number of elements
         * @param nx the x component of the normal
         * @param ny the y component of the normal
         * @param nz the z component of the normal
         * @return {@link #addPortal(int, int, float[], int)} with the normal given: it must point
         *     from sector {@code a} into sector {@code b}; it is normalised
         * @throws IllegalArgumentException if the sectors are not two different existing sectors,
         *     or the normal is zero or not perpendicular to the polygon
         */
        public int addPortal(int a, int b, float[] polygon, int count, float nx, float ny, float nz) {
            int sectors = sectorPlanes.size();
            if (a < 0 || b < 0 || a >= sectors || b >= sectors || a == b) {
                throw new IllegalArgumentException("a portal joins two different existing sectors: " + a + ", " + b);
            }
            double[] n = newell(polygon, count);
            double len = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
            if (!(len > 0)) {
                throw new IllegalArgumentException("the portal normal is zero");
            }
            double ux = nx / len, uy = ny / len, uz = nz / len;
            if (ux * n[0] + uy * n[1] + uz * n[2] < 0) {
                n[0] = -n[0];
                n[1] = -n[1];
                n[2] = -n[2];
            }
            // the given direction decides: use it as the unit normal (the polygon's own normal only checks that the polygon is planar enough)
            if (Math.abs(ux * n[0] + uy * n[1] + uz * n[2]) < 0.99) {
                throw new IllegalArgumentException("the normal is not perpendicular to the polygon");
            }
            double cx = 0, cy = 0, cz = 0;
            for (int i = 0; i < count; i++) {
                cx += polygon[3 * i];
                cy += polygon[3 * i + 1];
                cz += polygon[3 * i + 2];
            }
            cx /= count;
            cy /= count;
            cz /= count;
            portalA.add(a);
            portalB.add(b);
            portalVertices.add(Arrays.copyOf(polygon, 3 * count));
            portalPlanes.add(new float[] {(float) ux, (float) uy, (float) uz, (float) -(ux * cx + uy * cy + uz * cz)});
            return portalA.size() - 1;
        }

        /**
         * Adds a portal for every pair of box sectors that share part of a face: the overlap of the
         * two faces, a rectangle whose area exceeds {@code tolerance} squared, with the faces
         * counted as shared when their planes are within {@code tolerance}.
         *
         * <p>Only sectors made by {@link #addBox} take part. Returns the number of portals added.
         * Call it once, after the boxes: it does not look at portals that were added by hand.
         *
         * @param tolerance the tolerance
         * @return the number of portals added
         */
        public int autoPortals(float tolerance) {
            int added = 0;
            int n = sectorBoxes.size();
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    Aabbf p = sectorBoxes.get(i), q = sectorBoxes.get(j);
                    if (p == null || q == null) {
                        continue;
                    }
                    float[] lo = {Math.max(p.minX(), q.minX()), Math.max(p.minY(), q.minY()), Math.max(p.minZ(), q.minZ())};
                    float[] hi = {Math.min(p.maxX(), q.maxX()), Math.min(p.maxY(), q.maxY()), Math.min(p.maxZ(), q.maxZ())};
                    for (int axis = 0; axis < 3; axis++) {
                        float pMin = axis == 0 ? p.minX() : axis == 1 ? p.minY() : p.minZ(), pMax = axis == 0 ? p.maxX() : axis == 1 ? p.maxY() : p.maxZ();
                        float qMin = axis == 0 ? q.minX() : axis == 1 ? q.minY() : q.minZ(), qMax = axis == 0 ? q.maxX() : axis == 1 ? q.maxY() : q.maxZ();
                        boolean pBelow = Math.abs(pMax - qMin) <= tolerance, qBelow = Math.abs(qMax - pMin) <= tolerance;
                        if (!pBelow && !qBelow) {
                            continue;
                        }
                        int u = (axis + 1) % 3, v = (axis + 2) % 3;
                        if (hi[u] - lo[u] <= tolerance || hi[v] - lo[v] <= tolerance) {
                            continue;
                        }
                        float plane = pBelow ? (pMax + qMin) / 2 : (qMax + pMin) / 2;
                        float[] poly = new float[12];
                        float[][] corners = {{lo[u], lo[v]}, {hi[u], lo[v]}, {hi[u], hi[v]}, {lo[u], hi[v]}};
                        for (int k = 0; k < 4; k++) {
                            poly[3 * k + axis] = plane;
                            poly[3 * k + u] = corners[k][0];
                            poly[3 * k + v] = corners[k][1];
                        }
                        float[] normal = new float[3];
                        normal[axis] = pBelow ? 1f : -1f; // from the lower box into the upper one; sector i is "a" and j is "b"
                        addPortal(i, j, poly, 4, normal[0], normal[1], normal[2]);
                        added++;
                        break;
                    }
                }
            }
            return added;
        }

        private boolean insideSector(int s, double x, double y, double z) {
            if (s < 0 || s >= sectorPlanes.size()) {
                throw new IllegalArgumentException("no sector " + s);
            }
            float[] p = sectorPlanes.get(s);
            for (int k = 0; k < p.length / 4; k++) {
                if (p[4 * k] * x + p[4 * k + 1] * y + p[4 * k + 2] * z + p[4 * k + 3] < -INSIDE_EPS) {
                    return false;
                }
            }
            return true;
        }

        private static double[] newell(float[] poly, int count) {
            if (count < 3 || count > MAX_PORTAL_VERTICES || poly.length < 3 * count) {
                throw new IllegalArgumentException("a portal has 3 to " + MAX_PORTAL_VERTICES + " vertices, got " + count);
            }
            double nx = 0, ny = 0, nz = 0;
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;
                double xi = poly[3 * i], yi = poly[3 * i + 1], zi = poly[3 * i + 2], xj = poly[3 * j], yj = poly[3 * j + 1], zj = poly[3 * j + 2];
                nx += (yi - yj) * (zi + zj);
                ny += (zi - zj) * (xi + xj);
                nz += (xi - xj) * (yi + yj);
            }
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(len > 1e-12)) {
                throw new IllegalArgumentException("the portal polygon has no area");
            }
            return new double[] {nx / len, ny / len, nz / len};
        }

        /**
         * Makes the graph: all portals open, no objects assigned.
         *
         * <p>At least one sector is required.
         *
         * @return the graph, never {@code null}
         * @throws IllegalStateException if no sector was added
         */
        public PortalGraph build() {
            if (sectorPlanes.isEmpty()) {
                throw new IllegalStateException("a portal graph needs at least one sector");
            }
            return new PortalGraph(this);
        }
    }
}
