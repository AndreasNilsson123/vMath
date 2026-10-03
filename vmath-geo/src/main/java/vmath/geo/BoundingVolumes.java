package vmath.geo;

import vmath.core.Mat3f;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec3f;

/**
 * Fitting bounding volumes to point sets and to transformed boxes.
 *
 * <ul>
 *   <li>{@link #minimumSphere}: the <b>smallest</b> sphere that contains all the points (the
 *       algorithm of Welzl, in the move-to-front form with at most four support points). Unlike the
 *       cheap "centre of the box" sphere ({@link Aabbf#boundingSphere()}) it is optimal; for random
 *       points in a cube it is about 15% smaller in radius.</li>
 *   <li>{@link #pcaBox}: an oriented box whose axes are the principal axes of the point cloud (the
 *       eigenvectors of its covariance matrix). Fast and usually much tighter than the axis-aligned
 *       box for elongated, rotated shapes; not guaranteed to be the smallest oriented box.</li>
 *   <li>{@link #transformedBox}: the tightest box, oriented like the transform's rotation, around
 *       an axis-aligned box after a general affine transform (translation, rotation, non-uniform
 *       scale and shear); for a transform without shear it is exact.</li>
 *   <li>{@link #sphereOfTransformedBox}: the smallest sphere around an axis-aligned box after an
 *       affine transform.</li>
 * </ul>
 *
 * <p>The results are rounded to {@code float} in the safe direction: every input point is inside
 * the returned volume as {@code float} arithmetic sees it (the sphere radius and the box half
 * extents are rounded up). The axis-aligned box of a transformed box is
 * {@link Aabbf#transform(Mat4f)}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float[] points = {0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
 * Spheref sphere = BoundingVolumes.minimumSphere(points, 4);        // the smallest sphere around them
 * Obbf box = BoundingVolumes.pcaBox(points, 4);                      // an oriented box along the principal axes
 * Spheref moved = BoundingVolumes.sphereOfTransformedBox(Aabbf.of(Vec3f.ZERO, Vec3f.ONE), Mat4f.rotationY(0.5f));
 * }</pre>
 */
public final class BoundingVolumes {

    private static final double INSIDE = 1e-12;

    private BoundingVolumes() {
    }

    // ------------------------------------------------------------ minimum sphere

    /**
     * Computes the smallest enclosing sphere of a point set with an iterative algorithm that gives
     * the exact minimum; cost is linear in expectation, and the points are read from a flat
     * coordinate array.
     *
     * <p>The points are visited in a fixed pseudo-random order (the input is copied, not modified),
     * which makes the expected time linear in the number of points for any input order. At least
     * one point is needed; {@link IllegalArgumentException} otherwise.
     *
     * @param xyz the three components
     * @param offset the index of the first element to read or write
     * @param vertexCount the number of vertices
     * @return the smallest sphere that contains the {@code vertexCount} points ({@code x, y, z}
     *     triples) of {@code xyz} starting at float index {@code offset}
     */
    public static Spheref minimumSphere(float[] xyz, int offset, int vertexCount) {
        checkPoints(xyz, offset, vertexCount);
        int n = vertexCount;
        double[] p = new double[3 * n];
        // a deterministic shuffle (an xorshift generator) so that adversarial orders do not make the nested loops quadratic or worse
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        long state = 0x9E3779B97F4A7C15L;
        for (int i = n - 1; i > 0; i--) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            int j = (int) Long.remainderUnsigned(state, i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        for (int i = 0; i < n; i++) {
            int s = offset + 3 * order[i];
            p[3 * i] = xyz[s];
            p[3 * i + 1] = xyz[s + 1];
            p[3 * i + 2] = xyz[s + 2];
        }
        double[] b = new double[4]; // centre x, y, z and the squared radius
        double[] t = new double[4];
        b[0] = p[0];
        b[1] = p[1];
        b[2] = p[2];
        b[3] = 0;
        for (int i = 1; i < n; i++) {
            if (!inside(b, p, i)) {
                ball1(p, i, b, t);
            }
        }
        return enclose(b, xyz, offset, vertexCount);
    }

    /**
     * Computes the smallest enclosing sphere of a point set from the start of the array; see the
     * overload with an offset.
     *
     * @param xyz the three components
     * @param vertexCount the number of vertices
     * @return {@link #minimumSphere(float[], int, int)} for points starting at index 0
     */
    public static Spheref minimumSphere(float[] xyz, int vertexCount) {
        return minimumSphere(xyz, 0, vertexCount);
    }

    private static boolean inside(double[] b, double[] p, int i) {
        double dx = p[3 * i] - b[0], dy = p[3 * i + 1] - b[1], dz = p[3 * i + 2] - b[2];
        return dx * dx + dy * dy + dz * dz <= b[3] * (1 + INSIDE) + 1e-300;
    }

    /**
     * The smallest sphere of the points {@code 0 .. i - 1} with point {@code i} on its boundary,
     * into {@code b}.
     */
    private static void ball1(double[] p, int i, double[] b, double[] t) {
        b[0] = p[3 * i];
        b[1] = p[3 * i + 1];
        b[2] = p[3 * i + 2];
        b[3] = 0;
        for (int j = 0; j < i; j++) {
            if (!inside(b, p, j)) {
                ball2(p, j, i, b, t);
            }
        }
    }

    /**
     * The smallest sphere of the points {@code 0 .. j - 1} with points {@code j} and {@code i} on
     * its boundary.
     */
    private static void ball2(double[] p, int j, int i, double[] b, double[] t) {
        sphereOf2(p, i, j, b);
        for (int k = 0; k < j; k++) {
            if (!inside(b, p, k)) {
                ball3(p, k, j, i, b, t);
            }
        }
    }

    /**
     * The smallest sphere of the points {@code 0 .. k - 1} with points {@code k}, {@code j} and
     * {@code i} on its boundary.
     */
    private static void ball3(double[] p, int k, int j, int i, double[] b, double[] t) {
        sphereOf3(p, i, j, k, b);
        for (int l = 0; l < k; l++) {
            if (!inside(b, p, l)) {
                sphereOf4(p, i, j, k, l, b, t);
            }
        }
    }

    private static void sphereOf2(double[] p, int a, int c, double[] out) {
        out[0] = (p[3 * a] + p[3 * c]) * 0.5;
        out[1] = (p[3 * a + 1] + p[3 * c + 1]) * 0.5;
        out[2] = (p[3 * a + 2] + p[3 * c + 2]) * 0.5;
        double dx = p[3 * a] - out[0], dy = p[3 * a + 1] - out[1], dz = p[3 * a + 2] - out[2];
        out[3] = dx * dx + dy * dy + dz * dz;
    }

    /**
     * The smallest sphere through three points: centred at the circumcentre of the triangle (or the
     * diameter sphere of the two farthest points when they are collinear).
     */
    private static void sphereOf3(double[] p, int ia, int ib, int ic, double[] out) {
        double ax = p[3 * ia], ay = p[3 * ia + 1], az = p[3 * ia + 2];
        double ux = p[3 * ib] - ax, uy = p[3 * ib + 1] - ay, uz = p[3 * ib + 2] - az;
        double vx = p[3 * ic] - ax, vy = p[3 * ic + 1] - ay, vz = p[3 * ic + 2] - az;
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double n2 = nx * nx + ny * ny + nz * nz;
        double u2 = ux * ux + uy * uy + uz * uz, v2 = vx * vx + vy * vy + vz * vz;
        if (n2 <= 1e-24 * u2 * v2) {
            // collinear (or coincident) points: the diameter sphere of the farthest pair
            double wx = vx - ux, wy = vy - uy, wz = vz - uz;
            double w2 = wx * wx + wy * wy + wz * wz;
            if (u2 >= v2 && u2 >= w2) {
                sphereOf2(p, ia, ib, out);
            } else if (v2 >= w2) {
                sphereOf2(p, ia, ic, out);
            } else {
                sphereOf2(p, ib, ic, out);
            }
            return;
        }
        // the circumcentre a + ((|u|^2 v - |v|^2 u) x n) / (2 |n|^2), with n = u x v
        double cux = uy * nz - uz * ny, cuy = uz * nx - ux * nz, cuz = ux * ny - uy * nx; // u x n
        double cvx = vy * nz - vz * ny, cvy = vz * nx - vx * nz, cvz = vx * ny - vy * nx; // v x n
        double s = 1.0 / (2 * n2);
        double ox = (u2 * cvx - v2 * cux) * s, oy = (u2 * cvy - v2 * cuy) * s, oz = (u2 * cvz - v2 * cuz) * s;
        out[0] = ax + ox;
        out[1] = ay + oy;
        out[2] = az + oz;
        out[3] = ox * ox + oy * oy + oz * oz;
    }

    /**
     * The sphere through four points; when they are (nearly) coplanar the smallest sphere through
     * the three that matter is kept.
     */
    private static void sphereOf4(double[] p, int ia, int ib, int ic, int id, double[] b, double[] t) {
        double ax = p[3 * ia], ay = p[3 * ia + 1], az = p[3 * ia + 2];
        double ux = p[3 * ib] - ax, uy = p[3 * ib + 1] - ay, uz = p[3 * ib + 2] - az;
        double vx = p[3 * ic] - ax, vy = p[3 * ic + 1] - ay, vz = p[3 * ic + 2] - az;
        double wx = p[3 * id] - ax, wy = p[3 * id + 1] - ay, wz = p[3 * id + 2] - az;
        double det = ux * (vy * wz - vz * wy) - uy * (vx * wz - vz * wx) + uz * (vx * wy - vy * wx);
        double scale = Math.sqrt((ux * ux + uy * uy + uz * uz) * (vx * vx + vy * vy + vz * vz) * (wx * wx + wy * wy + wz * wz));
        if (Math.abs(det) <= 1e-12 * scale) {
            return; // coplanar: no sphere through all four is smaller than the current one that already holds the others; keep it
        }
        double u2 = ux * ux + uy * uy + uz * uz, v2 = vx * vx + vy * vy + vz * vz, w2 = wx * wx + wy * wy + wz * wz;
        // the centre c solves 2 c . u = |u|^2, 2 c . v = |v|^2, 2 c . w = |w|^2 (Cramer's rule)
        double cx = (u2 * (vy * wz - vz * wy) - v2 * (uy * wz - uz * wy) + w2 * (uy * vz - uz * vy)) / (2 * det);
        double cy = (-u2 * (vx * wz - vz * wx) + v2 * (ux * wz - uz * wx) - w2 * (ux * vz - uz * vx)) / (2 * det);
        double cz = (u2 * (vx * wy - vy * wx) - v2 * (ux * wy - uy * wx) + w2 * (ux * vy - uy * vx)) / (2 * det);
        b[0] = ax + cx;
        b[1] = ay + cy;
        b[2] = az + cz;
        b[3] = cx * cx + cy * cy + cz * cz;
    }

    /**
     * The float sphere around the float centre nearest to {@code b} that contains every point, its
     * radius rounded up.
     */
    private static Spheref enclose(double[] b, float[] xyz, int offset, int count) {
        float cx = (float) b[0], cy = (float) b[1], cz = (float) b[2];
        double r2 = 0;
        for (int i = 0; i < count; i++) {
            double dx = xyz[offset + 3 * i] - (double) cx, dy = xyz[offset + 3 * i + 1] - (double) cy, dz = xyz[offset + 3 * i + 2] - (double) cz;
            r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);
        }
        return new Spheref(cx, cy, cz, roundUp(Math.sqrt(r2)));
    }

    /**
     * The smallest float that is at least {@code v}.
     */
    private static float roundUp(double v) {
        float f = (float) v;
        return f < v ? Math.nextUp(f) : f;
    }

    // ------------------------------------------------------------ PCA box

    /**
     * Fits an oriented bounding box by principal component analysis: the axes are the eigenvectors
     * of the covariance matrix, which gives a good but not minimal box; works from a flat
     * coordinate array.
     *
     * <p>The axes form a right-handed frame (a proper rotation). For one point, or a set with no
     * spread, the box has zero size. At least one point is needed.
     *
     * <p>The covariance weighs every <em>point</em>, so a dense cluster pulls the axes towards
     * itself; for a surface mesh, points sampled evenly over the surface (or the vertices of its
     * {@link ConvexHull}) give a better frame than a vertex list with uneven density.
     *
     * @param xyz the three components
     * @param offset the index of the first element to read or write
     * @param vertexCount the number of vertices
     * @return an oriented box around the {@code vertexCount} points of {@code xyz} (from float
     *     index {@code offset}) whose axes are the eigenvectors of the covariance matrix of the
     *     points, found by Jacobi rotations; the box is the tightest one in that frame
     */
    public static Obbf pcaBox(float[] xyz, int offset, int vertexCount) {
        checkPoints(xyz, offset, vertexCount);
        int n = vertexCount;
        double mx = 0, my = 0, mz = 0;
        for (int i = 0; i < n; i++) {
            mx += xyz[offset + 3 * i];
            my += xyz[offset + 3 * i + 1];
            mz += xyz[offset + 3 * i + 2];
        }
        mx /= n;
        my /= n;
        mz /= n;
        double[] a = new double[9]; // the covariance, symmetric, row-major
        for (int i = 0; i < n; i++) {
            double x = xyz[offset + 3 * i] - mx, y = xyz[offset + 3 * i + 1] - my, z = xyz[offset + 3 * i + 2] - mz;
            a[0] += x * x;
            a[1] += x * y;
            a[2] += x * z;
            a[4] += y * y;
            a[5] += y * z;
            a[8] += z * z;
        }
        a[3] = a[1];
        a[6] = a[2];
        a[7] = a[5];
        double[] v = new double[9]; // the eigenvectors as columns, row-major
        jacobi(a, v);
        // a right-handed frame
        double det = v[0] * (v[4] * v[8] - v[5] * v[7]) - v[1] * (v[3] * v[8] - v[5] * v[6]) + v[2] * (v[3] * v[7] - v[4] * v[6]);
        if (det < 0) {
            v[2] = -v[2];
            v[5] = -v[5];
            v[8] = -v[8];
        }
        return boxInFrame(xyz, offset, n, v);
    }

    /**
     * Fits an oriented bounding box by principal component analysis to a point set from the start
     * of the array; see the overload with an offset.
     *
     * @param xyz the three components
     * @param vertexCount the number of vertices
     * @return {@link #pcaBox(float[], int, int)} for points starting at index 0
     */
    public static Obbf pcaBox(float[] xyz, int vertexCount) {
        return pcaBox(xyz, 0, vertexCount);
    }

    /**
     * The tightest box around the points in the frame whose axes are the columns of {@code v}
     * (row-major 3x3), rounded outward to float.
     */
    private static Obbf boxInFrame(float[] xyz, int offset, int n, double[] v) {
        double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < n; i++) {
            double x = xyz[offset + 3 * i], y = xyz[offset + 3 * i + 1], z = xyz[offset + 3 * i + 2];
            for (int c = 0; c < 3; c++) {
                double d = x * v[c] + y * v[3 + c] + z * v[6 + c];
                lo[c] = Math.min(lo[c], d);
                hi[c] = Math.max(hi[c], d);
            }
        }
        double[] mid = new double[3], half = new double[3];
        for (int c = 0; c < 3; c++) {
            mid[c] = (lo[c] + hi[c]) * 0.5;
            half[c] = (hi[c] - lo[c]) * 0.5;
        }
        // the centre in world space: sum of mid[c] * axis c
        float cx = (float) (mid[0] * v[0] + mid[1] * v[1] + mid[2] * v[2]);
        float cy = (float) (mid[0] * v[3] + mid[1] * v[4] + mid[2] * v[5]);
        float cz = (float) (mid[0] * v[6] + mid[1] * v[7] + mid[2] * v[8]);
        Mat3f axes = new Mat3f((float) v[0], (float) v[3], (float) v[6], (float) v[1], (float) v[4], (float) v[7], (float) v[2], (float) v[5], (float) v[8]);
        Quatf q = Quatf.fromMat3(axes).normalize();
        Obbf rough = new Obbf(cx, cy, cz, (float) half[0], (float) half[1], (float) half[2], q.x(), q.y(), q.z(), q.w());
        // make it safe in float arithmetic: grow the half extents until every point is inside as the box's own test sees it
        float hx = rough.hx(), hy = rough.hy(), hz = rough.hz();
        for (int i = 0; i < n; i++) {
            Vec3f l = rough.toLocal(new Vec3f(xyz[offset + 3 * i], xyz[offset + 3 * i + 1], xyz[offset + 3 * i + 2]));
            hx = Math.max(hx, Math.abs(l.x()));
            hy = Math.max(hy, Math.abs(l.y()));
            hz = Math.max(hz, Math.abs(l.z()));
        }
        return new Obbf(cx, cy, cz, hx, hy, hz, q.x(), q.y(), q.z(), q.w());
    }

    /**
     * The eigen decomposition of the symmetric 3x3 matrix {@code a} (row-major, destroyed) by
     * cyclic Jacobi rotations; the eigenvectors go to the columns of {@code v}.
     */
    private static void jacobi(double[] a, double[] v) {
        v[0] = v[4] = v[8] = 1;
        v[1] = v[2] = v[3] = v[5] = v[6] = v[7] = 0;
        for (int sweep = 0; sweep < 60; sweep++) {
            double off = a[1] * a[1] + a[2] * a[2] + a[5] * a[5];
            double diag = a[0] * a[0] + a[4] * a[4] + a[8] * a[8];
            if (off <= 1e-30 * diag || off == 0) {
                return;
            }
            rotate(a, v, 0, 1);
            rotate(a, v, 0, 2);
            rotate(a, v, 1, 2);
        }
    }

    private static void rotate(double[] a, double[] v, int p, int q) {
        double apq = a[3 * p + q];
        if (apq == 0) {
            return;
        }
        double theta = (a[3 * q + q] - a[3 * p + p]) / (2 * apq);
        double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
        if (theta == 0) {
            t = 1;
        }
        double c = 1 / Math.sqrt(t * t + 1), s = t * c;
        for (int k = 0; k < 3; k++) { // A' = J^T A J, columns first
            double akp = a[3 * k + p], akq = a[3 * k + q];
            a[3 * k + p] = c * akp - s * akq;
            a[3 * k + q] = s * akp + c * akq;
        }
        for (int k = 0; k < 3; k++) { // then rows
            double apk = a[3 * p + k], aqk = a[3 * q + k];
            a[3 * p + k] = c * apk - s * aqk;
            a[3 * q + k] = s * apk + c * aqk;
        }
        for (int k = 0; k < 3; k++) {
            double vkp = v[3 * k + p], vkq = v[3 * k + q];
            v[3 * k + p] = c * vkp - s * vkq;
            v[3 * k + q] = s * vkp + c * vkq;
        }
    }

    // ------------------------------------------------------------ transformed boxes

    /**
     * Fits an oriented box around a transformed box, factoring the matrix into rotation, shear and
     * scale so that the result is tight; the projective row of the matrix is ignored.
     *
     * <p>For a transform without shear, that is a translation, rotation and scale (also
     * non-uniform, also with a reflection), the result is <b>exactly</b> the transformed box; with
     * shear it is the tightest box in the rotation's frame, which is larger. An empty box gives
     * {@link IllegalArgumentException}.
     *
     * @param box the box; must not be {@code null}
     * @param m the matrix; must not be {@code null}
     * @return an oriented box around {@code box} after the affine transform {@code m} (the
     *     projection row is ignored): the matrix is factored as {@code T R Sh S}
     *     ({@link Mat4f#decomposeWithShear()}), and the box is oriented like {@code R}, with the
     *     half extents of the tightest box in that frame (Arvo's method applied to {@code Sh S})
     * @throws IllegalArgumentException if the box is empty
     */
    public static Obbf transformedBox(Aabbf box, Mat4f m) {
        if (box.isEmpty()) {
            throw new IllegalArgumentException("the box is empty");
        }
        Mat4f.ShearDecomposition d = m.decomposeWithShear();
        // Sh * S as a matrix: column 0 = (sx, 0, 0), column 1 = (xy sy, sy, 0), column 2 = (xz sz, yz sz, sz)
        Mat4f shs = new Mat4f(d.scale().x(), 0f, 0f, 0f,
                d.shear().x() * d.scale().y(), d.scale().y(), 0f, 0f,
                d.shear().y() * d.scale().z(), d.shear().z() * d.scale().z(), d.scale().z(), 0f,
                0f, 0f, 0f, 1f);
        Aabbf local = box.transform(shs);
        Vec3f c = d.rotation().transform(local.center()).add(d.translation());
        Vec3f h = local.halfSize();
        return Obbf.of(c, h, d.rotation());
    }

    /**
     * Fits a sphere around a transformed box from the eight transformed corners; exact for the
     * affine image of a box, and cheaper to test against than an oriented box.
     *
     * <p>The projection row of {@code m} is ignored.
     *
     * @param box the box; must not be {@code null}
     * @param m the matrix; must not be {@code null}
     * @return the smallest sphere around {@code box} after the affine transform {@code m}: the
     *     minimum sphere of the eight transformed corners (the image of a box under an affine map
     *     is a parallelepiped, whose smallest enclosing sphere is determined by its vertices)
     * @throws IllegalArgumentException if the box is empty
     */
    public static Spheref sphereOfTransformedBox(Aabbf box, Mat4f m) {
        if (box.isEmpty()) {
            throw new IllegalArgumentException("the box is empty");
        }
        float[] corners = new float[24];
        for (int i = 0; i < 8; i++) {
            Vec3f p = m.transformPosition(box.corner(i));
            corners[3 * i] = p.x();
            corners[3 * i + 1] = p.y();
            corners[3 * i + 2] = p.z();
        }
        return minimumSphere(corners, 0, 8);
    }

    private static void checkPoints(float[] xyz, int offset, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("at least one point is needed, got " + count);
        }
        if (offset < 0 || (long) offset + 3L * count > xyz.length) {
            throw new IllegalArgumentException(count + " points at offset " + offset + " do not fit in an array of " + xyz.length);
        }
    }
}
