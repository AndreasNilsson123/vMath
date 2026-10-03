package vmath.occlusion;

import java.util.Arrays;
import vmath.core.Mat4f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;

/**
 * A small software depth buffer for occlusion culling: rasterize a few big occluders, then ask
 * whether an object's box is hidden behind them.
 *
 * <p><b>Conservative in both directions that matter.</b> The buffer must never report an object
 * hidden that could be seen, so two things are done differently from a normal renderer:
 * <ul>
 *   <li><b>Coverage is inner-conservative.</b> A pixel counts as covered only if the occluder
 *       covers the <em>whole</em> pixel square (tested at the corner of the square that is worst
 *       for each polygon edge), never merely its centre. Occluders are rasterized as whole convex
 *       polygons (a box face is one quad, not two triangles), because two triangles would leave a
 *       seam of uncovered pixels along their shared edge.</li>
 *   <li><b>Depth is the occluder's farthest point in the pixel.</b> The buffer stores {@code 1 / w}
 *       (larger is nearer; {@code w} is the distance along the view axis) and, for a covered pixel,
 *       the smallest value the occluder has anywhere inside it, so a box behind that value is
 *       behind the occluder wherever in the pixel it lies. Where several occluders cover a pixel
 *       the nearest one is kept.</li>
 * </ul> A small margin is added on top of both (a thousandth of a pixel, and a relative 1e-5 in
 * depth) to cover rounding.
 *
 * <p><b>Testing an object</b> ({@link #isHidden}): its eight corners give a screen rectangle
 * (rounded outwards) and its nearest depth; the object is hidden only if every pixel of the
 * rectangle holds an occluder at least that near. A min-reduced pyramid (Hi-Z) lets the test read a
 * handful of texels instead of the whole rectangle. Anything that reaches the near plane, or has a
 * non-finite corner, is reported visible.
 *
 * <p><b>Projections.</b> {@link #begin} is for perspective projections: the {@code w} used for
 * depth is the distance along the view axis, and near clipping is done against a plane
 * {@code w >= nearW}. {@link #beginOrthographic} is for orthographic ones (a shadow cascade, a
 * top-down or isometric view): there the depth is the position between the near and the far plane
 * that the matrix itself defines, and the buffer stores one minus that fraction, which is also
 * linear on the screen.
 *
 * <p><b>Limits.</b> Occluders are treated as opaque and two-sided; boxes must be solid. Use an
 * occluder only if it really is opaque (a wall, a terrain chunk, a building), never a tree or a
 * window. Anything beyond the far plane of an orthographic view covers nothing (it could not hide
 * what is inside the view).
 *
 * <p><b>Threads.</b> Building (begin, add..., finish) is single-threaded. After {@link #finish()}
 * the buffer is read-only and {@link #isHidden} can be called from any number of threads. All work
 * is done in {@code double}, into preallocated arrays.
 *
 * <p><b>Thread safety.</b> Building a frame ({@code begin}, the {@code add} methods and
 * {@code finish}) must happen on one thread. After {@link #finish()} the buffer is read-only and
 * {@link #isHidden} can be called from any number of threads. Nothing blocks.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DepthBuffer depth = new DepthBuffer(256, 128);                        // small: it is only for culling
 * depth.begin(Mat4f.perspective(1f, 2f, 0.1f, 100f, ClipSpace.OPENGL).mul(Mat4f.lookAt(new Vec3f(0f, 0f, 5f), Vec3f.ZERO, Vec3f.UNIT_Y)), 0.1f);
 * depth.addBox(Aabbf.of(new Vec3f(-2f, -2f, -1f), new Vec3f(2f, 2f, 0f)));      // a big occluder
 * depth.finish();
 * boolean hidden = depth.isHidden(Aabbf.of(new Vec3f(-0.5f, -0.5f, -3f), new Vec3f(0.5f, 0.5f, -2f)));
 * }</pre>
 */
public final class DepthBuffer {

    private static final double COVER_MARGIN_PIXELS = 1e-3;
    private static final double DEPTH_SAFETY = 1e-5;
    private static final double QUERY_SAFETY = 1e-4;

    /**
     * Most vertices of a polygon passed to {@link #addPolygon} (clipping against the near plane
     * adds one more).
     */
    public static final int MAX_POLYGON_VERTICES = 7;

    private final int width;
    private final int height;
    private final float[][] level;
    private final int[] levelWidth;
    private final int[] levelHeight;

    // rows of the view-projection matrix for clip x, y and w: value = r[0] * x + r[1] * y + r[2] * z + r[3]
    private final double[] rowX = new double[4];
    private final double[] rowY = new double[4];
    private final double[] rowW = new double[4];
    private final double[] rowZ = new double[4];
    private double nearW;
    // orthographic views: nearness = depthScale * ndcZ + depthBias, 1 at the near plane and 0 at the far plane
    private boolean ortho;
    private double depthScale;
    private double depthBias;
    private boolean begun;
    private boolean mipsValid;

    // scratch (one more vertex than a polygon may have, for the near-plane clip): clip-space x, y, w and the near-plane coordinate s per vertex
    // (s is w for a perspective view, where the plane is w = nearW, and the fraction of the way to the far plane for an orthographic one, where it is s = 0)
    private static final int SCRATCH = MAX_POLYGON_VERTICES + 1;
    private static final int VERTEX = 4;
    private final double[] clipIn = new double[VERTEX * SCRATCH];
    private final double[] clipOut = new double[VERTEX * SCRATCH];
    // screen-space vertices (x, y, 1/w) and per-edge setup
    private final double[] sx = new double[SCRATCH];
    private final double[] sy = new double[SCRATCH];
    private final double[] siw = new double[SCRATCH];
    private final double[] edgeA = new double[SCRATCH];
    private final double[] edgeB = new double[SCRATCH];
    private final double[] edgeC = new double[SCRATCH];
    private final double[] edgeMargin = new double[SCRATCH];
    private final double[] edgeOx = new double[SCRATCH];
    private final double[] edgeOy = new double[SCRATCH];
    private final float[] boxCorners = new float[24];
    private final float[] quad = new float[12];

    /**
     * Creates a buffer of {@code width x height} pixels; something like 256 x 128 is usually plenty
     * for culling.
     *
     * @param width the width
     * @param height the height
     * @throws IllegalArgumentException if {@code width} or {@code height} is not positive
     */
    public DepthBuffer(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("size must be positive: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        int levels = HiZ.mipCount(width, height);
        level = new float[levels][];
        levelWidth = new int[levels];
        levelHeight = new int[levels];
        for (int l = 0; l < levels; l++) {
            levelWidth[l] = HiZ.mipSize(width, l);
            levelHeight[l] = HiZ.mipSize(height, l);
            level[l] = new float[levelWidth[l] * levelHeight[l]];
        }
    }

    /**
     * Exposes the width of the finest level in pixels.
     *
     * @return the width of the finest level, in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Exposes the height of the finest level in pixels.
     *
     * @return the height of the finest level, in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Counts the levels of the depth pyramid.
     *
     * @return number of pyramid levels (level 0 is the full-resolution buffer)
     */
    public int levels() {
        return level.length;
    }

    /**
     * Starts a frame: empties the buffer and sets the camera.
     *
     * <p>{@code nearW} is the smallest view distance kept when clipping occluders and the distance
     * below which an object counts as reaching the near plane; use the camera's near distance.
     *
     * @param viewProjection the view projection; must not be {@code null}
     * @param nearW the near w
     * @throws IllegalArgumentException if {@code nearW} is not positive
     */
    public void begin(Mat4f viewProjection, float nearW) {
        if (!(nearW > 0f)) {
            throw new IllegalArgumentException("nearW must be positive: " + nearW);
        }
        Mat4f m = viewProjection;
        row(rowX, m.m00(), m.m10(), m.m20(), m.m30());
        row(rowY, m.m01(), m.m11(), m.m21(), m.m31());
        row(rowW, m.m03(), m.m13(), m.m23(), m.m33());
        this.nearW = nearW;
        ortho = false;
        Arrays.fill(level[0], 0f);
        begun = true;
        mipsValid = false;
    }

    /**
     * Starts a frame for an orthographic view: empties the buffer and sets the camera.
     *
     * <p>The matrix must be a view-projection of an orthographic projection (its bottom row is
     * {@code 0 0 0 w} with {@code w > 0}, as {@link Mat4f#ortho} builds it) in the given depth
     * convention. The near plane is the one the matrix defines; occluders are clipped against it,
     * and an object that reaches it is reported visible.
     *
     * @param viewProjection the view projection; must not be {@code null}
     * @param depth how the matrix maps depth; must not be {@code null}
     * @throws IllegalArgumentException if the bottom row of the matrix is not {@code 0 0 0 w} with
     *     {@code w > 0}
     */
    public void beginOrthographic(Mat4f viewProjection, DepthRange depth) {
        Mat4f m = viewProjection;
        if (!(m.m03() == 0f && m.m13() == 0f && m.m23() == 0f && m.m33() > 0f)) {
            throw new IllegalArgumentException("not an orthographic view-projection (the bottom row must be 0 0 0 w with w > 0)");
        }
        row(rowX, m.m00(), m.m10(), m.m20(), m.m30());
        row(rowY, m.m01(), m.m11(), m.m21(), m.m31());
        row(rowW, m.m03(), m.m13(), m.m23(), m.m33());
        row(rowZ, m.m02(), m.m12(), m.m22(), m.m32());
        switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> {
                depthScale = -0.5;
                depthBias = 0.5;
            }
            case ZERO_TO_ONE -> {
                depthScale = -1.0;
                depthBias = 1.0;
            }
            case REVERSED_ZERO_TO_ONE -> {
                depthScale = 1.0;
                depthBias = 0.0;
            }
        }
        ortho = true;
        nearW = 0.0;
        Arrays.fill(level[0], 0f);
        begun = true;
        mipsValid = false;
    }

    /**
     * Computes how near a point is in an orthographic view from the depth that the matrix gives
     * it.
     *
     * <p>Internal helper of the orthographic mode.
     *
     * @param x the x coordinate of the point
     * @param y the y coordinate of the point
     * @param z the z coordinate of the point
     * @param w the clip-space w of the point, which is constant for an orthographic projection
     * @return the nearness: 1 at the near plane and 0 at the far plane, outside that range for
     *     points beyond them
     */
    private double nearness(float x, float y, float z, double w) {
        double ndcZ = (rowZ[0] * x + rowZ[1] * y + rowZ[2] * z + rowZ[3]) / w;
        return depthScale * ndcZ + depthBias;
    }

    private static void row(double[] r, float a, float b, float c, float d) {
        r[0] = a;
        r[1] = b;
        r[2] = c;
        r[3] = d;
    }

    // ---------------------------------------------------------------- occluders

    /**
     * Adds a solid box as an occluder.
     *
     * @param b the second box; must not be {@code null}
     */
    public void addBox(Aabbf b) {
        addBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    /**
     * Adds a solid box as an occluder (six quads, so there is no seam inside a face).
     *
     * @param x0 the x coordinate of the first corner
     * @param y0 the y coordinate of the first corner
     * @param z0 the z coordinate of the first corner
     * @param x1 the x coordinate of the second corner
     * @param y1 the y coordinate of the second corner
     * @param z1 the z coordinate of the second corner
     */
    public void addBox(float x0, float y0, float z0, float x1, float y1, float z1) {
        float[] c = boxCorners;
        for (int i = 0; i < 8; i++) { // corner bits: 1 = x, 2 = y, 4 = z
            c[i * 3] = (i & 1) == 0 ? x0 : x1;
            c[i * 3 + 1] = (i & 2) == 0 ? y0 : y1;
            c[i * 3 + 2] = (i & 4) == 0 ? z0 : z1;
        }
        face(0, 2, 3, 1); // -z
        face(4, 5, 7, 6); // +z
        face(0, 1, 5, 4); // -y
        face(2, 6, 7, 3); // +y
        face(0, 4, 6, 2); // -x
        face(1, 3, 7, 5); // +x
    }

    private void face(int a, int b, int c, int d) {
        System.arraycopy(boxCorners, a * 3, quad, 0, 3);
        System.arraycopy(boxCorners, b * 3, quad, 3, 3);
        System.arraycopy(boxCorners, c * 3, quad, 6, 3);
        System.arraycopy(boxCorners, d * 3, quad, 9, 3);
        addPolygon(quad, 4);
    }

    /**
     * Adds {@code triangleCount} triangles stored as nine floats each ({@code x, y, z} per vertex)
     * from {@code offset}.
     *
     * @param positions the positions
     * @param offset the index of the first element to read or write
     * @param triangleCount the triangle count
     */
    public void addTriangles(float[] positions, int offset, int triangleCount) {
        for (int t = 0; t < triangleCount; t++) {
            int o = offset + t * 9;
            addTriangle(positions[o], positions[o + 1], positions[o + 2], positions[o + 3], positions[o + 4], positions[o + 5],
                    positions[o + 6], positions[o + 7], positions[o + 8]);
        }
    }

    /**
     * Adds one opaque, two-sided triangle in world space.
     *
     * @param x0 the x coordinate of the first vertex
     * @param y0 the y coordinate of the first vertex
     * @param z0 the z coordinate of the first vertex
     * @param x1 the x coordinate of the second vertex
     * @param y1 the y coordinate of the second vertex
     * @param z1 the z coordinate of the second vertex
     * @param x2 the x coordinate of the third vertex
     * @param y2 the y coordinate of the third vertex
     * @param z2 the z coordinate of the third vertex
     */
    public void addTriangle(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2) {
        quad[0] = x0;
        quad[1] = y0;
        quad[2] = z0;
        quad[3] = x1;
        quad[4] = y1;
        quad[5] = z1;
        quad[6] = x2;
        quad[7] = y2;
        quad[8] = z2;
        addPolygon(quad, 3);
    }

    /**
     * Adds one opaque, two-sided <b>convex, planar</b> polygon of {@code count} vertices given as
     * {@code x, y, z} triples in world space, in either winding order.
     *
     * <p>Two polygons that share an edge do not cover the pixels along it: build a bigger polygon
     * when a flat surface is made of several pieces.
     *
     * @param positions the positions
     * @param count the number of elements
     * @throws IllegalStateException if {@link #begin} has not been called
     * @throws IllegalArgumentException if {@code count} is not in {@code [3, MAX_POLYGON_VERTICES]}
     */
    public void addPolygon(float[] positions, int count) {
        if (!begun) {
            throw new IllegalStateException("call begin() first");
        }
        if (count < 3 || count > MAX_POLYGON_VERTICES || positions.length < count * 3) {
            throw new IllegalArgumentException("a polygon needs 3 to " + MAX_POLYGON_VERTICES + " vertices: " + count);
        }
        mipsValid = false;
        double[] in = clipIn;
        for (int i = 0; i < count; i++) {
            float x = positions[i * 3], y = positions[i * 3 + 1], z = positions[i * 3 + 2];
            double w = rowW[0] * x + rowW[1] * y + rowW[2] * z + rowW[3];
            in[i * VERTEX] = rowX[0] * x + rowX[1] * y + rowX[2] * z + rowX[3];
            in[i * VERTEX + 1] = rowY[0] * x + rowY[1] * y + rowY[2] * z + rowY[3];
            in[i * VERTEX + 2] = w;
            in[i * VERTEX + 3] = ortho ? 1.0 - nearness(x, y, z, w) : w;
        }
        // clip against s >= nearW (Sutherland-Hodgman with one plane): s is w, or the fraction of the way to the far plane
        double[] out = clipOut;
        int n = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double si = in[i * VERTEX + 3], sj = in[j * VERTEX + 3];
            boolean insideI = si >= nearW, insideJ = sj >= nearW;
            if (insideI) {
                System.arraycopy(in, i * VERTEX, out, n * VERTEX, VERTEX);
                n++;
            }
            if (insideI != insideJ) {
                double t = (nearW - si) / (sj - si);
                out[n * VERTEX] = in[i * VERTEX] + t * (in[j * VERTEX] - in[i * VERTEX]);
                out[n * VERTEX + 1] = in[i * VERTEX + 1] + t * (in[j * VERTEX + 1] - in[i * VERTEX + 1]);
                out[n * VERTEX + 2] = ortho ? in[i * VERTEX + 2] + t * (in[j * VERTEX + 2] - in[i * VERTEX + 2]) : nearW;
                out[n * VERTEX + 3] = nearW;
                n++;
            }
        }
        if (n >= 3) {
            rasterize(out, n);
        }
    }

    /**
     * Rasterizes a convex polygon of {@code m} vertices (each: clip x, clip y, clip w and the
     * near-plane coordinate).
     */
    private void rasterize(double[] v, int m) {
        for (int i = 0; i < m; i++) {
            double w = v[i * VERTEX + 2];
            sx[i] = (v[i * VERTEX] / w * 0.5 + 0.5) * width;
            sy[i] = (v[i * VERTEX + 1] / w * 0.5 + 0.5) * height;
            siw[i] = ortho ? 1.0 - v[i * VERTEX + 3] : 1.0 / w; // the stored quantity: 1 / w, or the nearness
            if (!(Double.isFinite(sx[i]) && Double.isFinite(sy[i]) && Double.isFinite(siw[i]))) {
                return;
            }
        }
        double area = 0;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            area += sx[i] * sy[j] - sx[j] * sy[i];
            minX = Math.min(minX, sx[i]);
            maxX = Math.max(maxX, sx[i]);
            minY = Math.min(minY, sy[i]);
            maxY = Math.max(maxY, sy[i]);
        }
        if (!(Math.abs(area) > 1e-9)) {
            return; // degenerate: covers no whole pixel
        }
        int x0 = (int) Math.max(0, Math.floor(minX)), x1 = (int) Math.min(width, Math.ceil(maxX));
        int y0 = (int) Math.max(0, Math.floor(minY)), y1 = (int) Math.min(height, Math.ceil(maxY));
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        // edge functions E(px, py) = A * px + B * py + C, >= 0 inside, with the sign chosen so that either winding works
        double sign = area > 0 ? 1.0 : -1.0;
        for (int e = 0; e < m; e++) {
            int j = (e + 1) % m;
            double a = -(sy[j] - sy[e]) * sign, b = (sx[j] - sx[e]) * sign;
            edgeA[e] = a;
            edgeB[e] = b;
            edgeC[e] = -(a * sx[e] + b * sy[e]);
            edgeMargin[e] = COVER_MARGIN_PIXELS * Math.hypot(a, b);
            edgeOx[e] = a < 0 ? 1 : 0; // the pixel-square corner where this edge function is smallest
            edgeOy[e] = b < 0 ? 1 : 0;
        }
        // 1/w is linear in screen space: fit the plane through the three vertices spanning the largest triangle
        int bi = 0, bj = 1, bk = 2;
        double best = -1;
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                for (int k = j + 1; k < m; k++) {
                    double t = Math.abs((sx[j] - sx[i]) * (sy[k] - sy[i]) - (sx[k] - sx[i]) * (sy[j] - sy[i]));
                    if (t > best) {
                        best = t;
                        bi = i;
                        bj = j;
                        bk = k;
                    }
                }
            }
        }
        double ax = sx[bj] - sx[bi], ay = sy[bj] - sy[bi], az = siw[bj] - siw[bi];
        double bx = sx[bk] - sx[bi], by = sy[bk] - sy[bi], bz = siw[bk] - siw[bi];
        double det = ax * by - bx * ay;
        double gx = (az * by - bz * ay) / det;
        double gy = (bz * ax - az * bx) / det;
        double g0 = siw[bi] - gx * sx[bi] - gy * sy[bi];
        double dox = gx < 0 ? 1 : 0, doy = gy < 0 ? 1 : 0; // the corner where 1/w is smallest
        float[] base = level[0];
        for (int y = y0; y < y1; y++) {
            int rowStart = y * width;
            for (int x = x0; x < x1; x++) {
                boolean inside = true;
                for (int e = 0; e < m; e++) {
                    if (edgeA[e] * (x + edgeOx[e]) + edgeB[e] * (y + edgeOy[e]) + edgeC[e] < edgeMargin[e]) {
                        inside = false;
                        break;
                    }
                }
                if (!inside) {
                    continue;
                }
                double farthest = gx * (x + dox) + gy * (y + doy) + g0; // smallest 1/w inside the pixel square
                float stored = (float) (farthest * (1.0 - DEPTH_SAFETY));
                if (stored > base[rowStart + x]) {
                    base[rowStart + x] = stored;
                }
            }
        }
    }

    // ---------------------------------------------------------------- pyramid

    /**
     * Builds the min-reduced pyramid.
     *
     * <p>Called automatically by {@link #isHidden} if needed; call it yourself before querying from
     * several threads.
     */
    public void finish() {
        if (mipsValid) {
            return;
        }
        for (int l = 1; l < level.length; l++) {
            float[] src = level[l - 1], dst = level[l];
            int sw = levelWidth[l - 1], sh = levelHeight[l - 1], dw = levelWidth[l], dh = levelHeight[l];
            for (int y = 0; y < dh; y++) {
                for (int x = 0; x < dw; x++) {
                    int px = x * 2, py = y * 2;
                    float m = src[py * sw + px];
                    if (px + 1 < sw) {
                        m = Math.min(m, src[py * sw + px + 1]);
                    }
                    if (py + 1 < sh) {
                        m = Math.min(m, src[(py + 1) * sw + px]);
                        if (px + 1 < sw) {
                            m = Math.min(m, src[(py + 1) * sw + px + 1]);
                        }
                    }
                    dst[y * dw + x] = m;
                }
            }
        }
        mipsValid = true;
    }

    /**
     * Reads the stored depth of a pixel at a level of the pyramid; the buffer stores the reciprocal
     * of the depth, so zero means that nothing was drawn.
     *
     * @param x the x component
     * @param y the y component
     * @param lvl the lvl
     * @return the stored occluder depth {@code 1 / w} of pixel {@code (x, y)} at pyramid
     *     {@code level}: 0 means nothing covers it
     */
    public float invDepth(int x, int y, int lvl) {
        finish();
        return level[lvl][y * levelWidth[lvl] + x];
    }

    /**
     * Reads the stored depth of a pixel at the finest level; the buffer stores the reciprocal of
     * the depth.
     *
     * @param x the x component
     * @param y the y component
     * @return full-resolution stored depth ({@code 1 / w}) of pixel {@code (x, y)}; see
     *     {@link #invDepth(int, int, int)}
     */
    public float invDepth(int x, int y) {
        return level[0][y * width + x];
    }

    /**
     * Counts the pixels at full resolution that an occluder covers.
     *
     * @return number of full-resolution pixels currently covered by some occluder
     */
    public int coveredPixels() {
        int n = 0;
        for (float v : level[0]) {
            if (v > 0f) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- queries

    /**
     * Returns {@link #isHidden(float, float, float, float, float, float)} for an {@link Aabbf}.
     *
     * @param b the second box; must not be {@code null}
     * @return {@code true} if the box is certainly hidden by the content of the buffer;
     *     {@code false} if it may be visible
     */
    public boolean isHidden(Aabbf b) {
        return isHidden(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    /**
     * Tests a box against the occluders that were rasterised since the last begin; conservative, so
     * only a box that is certainly hidden is reported as hidden.
     *
     * <p>False means "possibly visible": the box may be visible, reach the near plane, be off
     * screen, or have non-finite bounds.
     *
     * @param x0 the x coordinate of the first corner
     * @param y0 the y coordinate of the first corner
     * @param z0 the z coordinate of the first corner
     * @param x1 the x coordinate of the second corner
     * @param y1 the y coordinate of the second corner
     * @param z1 the z coordinate of the second corner
     * @return {@code true} only if the box is certainly hidden behind the occluders added since
     *     {@link #begin}
     */
    public boolean isHidden(float x0, float y0, float z0, float x1, float y1, float z1) {
        if (!begun) {
            return false;
        }
        finish();
        double minSx = Double.POSITIVE_INFINITY, minSy = minSx;
        double maxSx = Double.NEGATIVE_INFINITY, maxSy = maxSx;
        double nearest = 0.0; // the largest 1 / w over the corners: the box's nearest point
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? x0 : x1, y = (i & 2) == 0 ? y0 : y1, z = (i & 4) == 0 ? z0 : z1;
            double w = rowW[0] * x + rowW[1] * y + rowW[2] * z + rowW[3];
            double key; // the stored quantity of the corner: 1 / w, or the nearness
            if (ortho) {
                key = nearness(x, y, z, w);
                if (!(key <= 1.0)) {
                    return false; // reaches the near plane (or NaN)
                }
            } else {
                if (!(w >= nearW)) {
                    return false; // reaches the near plane (or NaN)
                }
                key = 1.0 / w;
            }
            double cx = rowX[0] * x + rowX[1] * y + rowX[2] * z + rowX[3];
            double cy = rowY[0] * x + rowY[1] * y + rowY[2] * z + rowY[3];
            double px = (cx / w * 0.5 + 0.5) * width, py = (cy / w * 0.5 + 0.5) * height;
            minSx = Math.min(minSx, px);
            maxSx = Math.max(maxSx, px);
            minSy = Math.min(minSy, py);
            maxSy = Math.max(maxSy, py);
            nearest = Math.max(nearest, key);
        }
        double needed = nearest * (1.0 + QUERY_SAFETY);
        if (!(needed > 0.0) || !Double.isFinite(needed) || !Double.isFinite(minSx) || !Double.isFinite(maxSx)
                || !Double.isFinite(minSy) || !Double.isFinite(maxSy)) {
            return false;
        }
        int px0 = (int) Math.max(0, Math.floor(minSx)), px1 = (int) Math.min(width, Math.ceil(maxSx));
        int py0 = (int) Math.max(0, Math.floor(minSy)), py1 = (int) Math.min(height, Math.ceil(maxSy));
        if (px0 >= px1 || py0 >= py1) {
            return false; // off screen: not this stage's business
        }
        // the coarsest level at which the rectangle still spans at most four texels either way
        int l = 0;
        while (l + 1 < level.length
                && (((px1 - 1) >> l) - (px0 >> l) >= 4 || ((py1 - 1) >> l) - (py0 >> l) >= 4)) {
            l++;
        }
        float[] lv = level[l];
        int lw = levelWidth[l];
        for (int ty = py0 >> l; ty <= (py1 - 1) >> l; ty++) {
            for (int tx = px0 >> l; tx <= (px1 - 1) >> l; tx++) {
                if (lv[ty * lw + tx] < needed) {
                    return false;
                }
            }
        }
        return true;
    }
}
