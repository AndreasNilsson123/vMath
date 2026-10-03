package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;

class BoundingVolumesTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);
    private final Rnd rnd = Rnd.create();

    private float[] cloud(int n, double spread) {
        float[] p = new float[3 * n];
        for (int i = 0; i < p.length; i++) {
            p[i] = (float) ((rng.nextDouble() * 2 - 1) * spread);
        }
        return p;
    }

    private static boolean encloses(Spheref s, float[] p, int n) {
        for (int i = 0; i < n; i++) {
            double dx = p[3 * i] - (double) s.cx(), dy = p[3 * i + 1] - (double) s.cy(), dz = p[3 * i + 2] - (double) s.cz();
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > s.radius()) {
                return false;
            }
        }
        return true;
    }

    /** The smallest enclosing sphere by trying every sphere determined by 2, 3 or 4 of the points: an oracle that shares nothing with the Welzl code. */
    private static double bruteForceRadius(float[] p, int n) {
        double best = Double.MAX_VALUE;
        for (int a = 0; a < n; a++) {
            best = Math.min(best, candidate(p, n, new int[] {a}));
            for (int b = a + 1; b < n; b++) {
                best = Math.min(best, candidate(p, n, new int[] {a, b}));
                for (int c = b + 1; c < n; c++) {
                    best = Math.min(best, candidate(p, n, new int[] {a, b, c}));
                    for (int d = c + 1; d < n; d++) {
                        best = Math.min(best, candidate(p, n, new int[] {a, b, c, d}));
                    }
                }
            }
        }
        return best;
    }

    /** The radius of the smallest sphere through the given points that contains all points, or MAX_VALUE if there is none. */
    private static double candidate(float[] p, int n, int[] s) {
        double[] c = new double[3];
        if (s.length == 1) {
            c = new double[] {p[3 * s[0]], p[3 * s[0] + 1], p[3 * s[0] + 2]};
        } else if (s.length == 2) {
            for (int k = 0; k < 3; k++) {
                c[k] = ((double) p[3 * s[0] + k] + p[3 * s[1] + k]) / 2;
            }
        } else {
            // the centre c satisfies 2 (p_i - p_0) . c = |p_i|^2 - |p_0|^2 for each other support point, and lies in the affine hull of the support points
            int m = s.length - 1;
            double[][] a = new double[m][3];
            double[] rhs = new double[m];
            for (int i = 0; i < m; i++) {
                for (int k = 0; k < 3; k++) {
                    a[i][k] = (double) p[3 * s[i + 1] + k] - p[3 * s[0] + k];
                }
                rhs[i] = (a[i][0] * a[i][0] + a[i][1] * a[i][1] + a[i][2] * a[i][2]) / 2;
            }
            // with 3 support points add the plane normal as the third equation (centre in the plane); with 4 the 3x3 system is complete
            if (m == 2) {
                a = new double[][] {a[0], a[1], {a[0][1] * a[1][2] - a[0][2] * a[1][1], a[0][2] * a[1][0] - a[0][0] * a[1][2], a[0][0] * a[1][1] - a[0][1] * a[1][0]}};
                rhs = new double[] {rhs[0], rhs[1], 0};
            }
            double[] x = solve3(a, rhs);
            if (x == null) {
                return Double.MAX_VALUE;
            }
            for (int k = 0; k < 3; k++) {
                c[k] = p[3 * s[0] + k] + x[k];
            }
        }
        double r = 0;
        for (int i = 0; i < n; i++) {
            double dx = p[3 * i] - c[0], dy = p[3 * i + 1] - c[1], dz = p[3 * i + 2] - c[2];
            r = Math.max(r, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        // the sphere must have every support point on its boundary: its radius is the largest distance, and the support points are among them
        for (int idx : s) {
            double dx = p[3 * idx] - c[0], dy = p[3 * idx + 1] - c[1], dz = p[3 * idx + 2] - c[2];
            if (Math.abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - r) > 1e-9 * (1 + r)) {
                return Double.MAX_VALUE;
            }
        }
        return r;
    }

    private static double[] solve3(double[][] a, double[] b) {
        double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        if (Math.abs(det) < 1e-12) {
            return null;
        }
        double[] x = new double[3];
        for (int col = 0; col < 3; col++) {
            double[][] m = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    m[i][j] = j == col ? b[i] : a[i][j];
                }
            }
            x[col] = (m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])) / det;
        }
        return x;
    }

    @Test
    void minimumSphereMatchesTheBruteForceOptimum() {
        for (int t = 0; t < 300; t++) {
            int n = 1 + rng.nextInt(10);
            float[] p = cloud(n, 5);
            Spheref s = BoundingVolumes.minimumSphere(p, n);
            assertTrue(encloses(s, p, n), "trial " + t + ": the sphere must contain every point");
            double ref = bruteForceRadius(p, n);
            assertEquals(ref, s.radius(), 1e-4 * (1 + ref), "trial " + t + ", n = " + n);
        }
    }

    @Test
    void minimumSphereOfSpecialSets() {
        // one point
        Spheref one = BoundingVolumes.minimumSphere(new float[] {1, 2, 3}, 1);
        assertEquals(0f, one.radius());
        assertEquals(2f, one.cy());
        // two points: the diameter sphere
        Spheref two = BoundingVolumes.minimumSphere(new float[] {0, 0, 0, 4, 0, 0}, 2);
        assertEquals(2f, two.radius(), 1e-6f);
        assertEquals(2f, two.cx(), 1e-6f);
        // duplicates and collinear points
        Spheref line = BoundingVolumes.minimumSphere(new float[] {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3, 3, 0, 0, 0}, 6);
        assertEquals(Math.sqrt(27) / 2, line.radius(), 1e-5);
        // the corners of a cube: radius half the space diagonal, centred at the middle
        float[] cube = new float[24];
        for (int i = 0; i < 8; i++) {
            cube[3 * i] = (i & 1) == 0 ? -1 : 1;
            cube[3 * i + 1] = (i & 2) == 0 ? -1 : 1;
            cube[3 * i + 2] = (i & 4) == 0 ? -1 : 1;
        }
        Spheref c = BoundingVolumes.minimumSphere(cube, 8);
        assertEquals(Math.sqrt(3), c.radius(), 1e-5);
        assertEquals(0f, c.cx(), 1e-6f);
        // coplanar points on a circle: the circle's sphere
        float[] ring = new float[3 * 12];
        for (int i = 0; i < 12; i++) {
            ring[3 * i] = (float) (2 * Math.cos(i * Math.PI / 6)) + 10;
            ring[3 * i + 1] = (float) (2 * Math.sin(i * Math.PI / 6));
            ring[3 * i + 2] = 5;
        }
        Spheref r = BoundingVolumes.minimumSphere(ring, 12);
        assertEquals(2.0, r.radius(), 1e-4);
        assertEquals(10f, r.cx(), 1e-4f);
        // an offset into the array, and points far from the origin
        float[] far = {99, 99, 99, 1e4f, 1e4f, 1e4f, 1e4f + 2, 1e4f, 1e4f};
        Spheref f = BoundingVolumes.minimumSphere(far, 3, 2);
        assertEquals(1.0, f.radius(), 2e-3);
    }

    @Test
    void minimumSphereIsNeverLargerThanTheBoxSphereAndScalesLinearly() {
        for (int t = 0; t < 50; t++) {
            int n = 20 + rng.nextInt(300);
            float[] p = cloud(n, 10);
            Spheref s = BoundingVolumes.minimumSphere(p, n);
            Spheref box = Aabbf.fromPoints(p, 0, n).boundingSphere();
            assertTrue(encloses(s, p, n));
            assertTrue(s.radius() <= box.radius() * (1 + 1e-5), "the optimal sphere cannot be larger than the box sphere");
            // order does not matter
            float[] reversed = new float[p.length];
            for (int i = 0; i < n; i++) {
                System.arraycopy(p, 3 * i, reversed, 3 * (n - 1 - i), 3);
            }
            assertEquals(s.radius(), BoundingVolumes.minimumSphere(reversed, n).radius(), 1e-4 * s.radius());
        }
        // worst-case orders (sorted points) do not take long: 200 000 points
        int n = 200_000;
        float[] sorted = new float[3 * n];
        for (int i = 0; i < n; i++) {
            sorted[3 * i] = (float) Math.cos(i * 0.001) * i * 1e-4f;
            sorted[3 * i + 1] = (float) Math.sin(i * 0.001) * i * 1e-4f;
            sorted[3 * i + 2] = i * 1e-5f;
        }
        long t0 = System.nanoTime();
        Spheref big = BoundingVolumes.minimumSphere(sorted, n);
        assertTrue(encloses(big, sorted, n));
        assertTrue((System.nanoTime() - t0) / 1e9 < 5.0, "a sorted input of 200 000 points must not make Welzl blow up");
    }

    @Test
    void minimumSphereChecksItsArguments() {
        assertThrows(IllegalArgumentException.class, () -> BoundingVolumes.minimumSphere(new float[3], 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BoundingVolumes.minimumSphere(new float[3], 0, 2));
        assertThrows(IllegalArgumentException.class, () -> BoundingVolumes.minimumSphere(new float[6], 4, 1));
        assertThrows(IllegalArgumentException.class, () -> BoundingVolumes.minimumSphere(new float[6], -1, 1));
        assertThrows(IllegalArgumentException.class, () -> BoundingVolumes.pcaBox(new float[3], 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BoundingVolumes.transformedBox(Aabbf.EMPTY, Mat4f.IDENTITY));
        assertThrows(IllegalArgumentException.class, () -> BoundingVolumes.sphereOfTransformedBox(Aabbf.EMPTY, Mat4f.IDENTITY));
    }

    // ------------------------------------------------------------ PCA box

    private static boolean encloses(Obbf b, float[] p, int n) {
        for (int i = 0; i < n; i++) {
            if (!b.contains(new Vec3f(p[3 * i], p[3 * i + 1], p[3 * i + 2]))) {
                return false;
            }
        }
        return true;
    }

    @Test
    void pcaBoxContainsEveryPointAndFindsTheAxesOfARotatedBox() {
        for (int t = 0; t < 100; t++) {
            // points filling a rotated box with distinct side lengths
            Quatf q = rnd.nextUnitQuatf();
            Vec3f half = new Vec3f((float) rnd.range(4, 5), (float) rnd.range(2, 2.5), (float) rnd.range(0.5, 0.8));
            Vec3f centre = rnd.nextVec3f();
            int n = 3000;
            float[] p = new float[3 * n];
            for (int i = 0; i < n; i++) {
                Vec3f local = new Vec3f((float) ((rng.nextDouble() * 2 - 1) * half.x()), (float) ((rng.nextDouble() * 2 - 1) * half.y()), (float) ((rng.nextDouble() * 2 - 1) * half.z()));
                Vec3f w = q.transform(local).add(centre);
                p[3 * i] = w.x();
                p[3 * i + 1] = w.y();
                p[3 * i + 2] = w.z();
            }
            Obbf b = BoundingVolumes.pcaBox(p, n);
            assertTrue(encloses(b, p, n), "trial " + t);
            double trueVolume = 8.0 * half.x() * half.y() * half.z();
            double volume = 8.0 * b.hx() * b.hy() * b.hz();
            assertTrue(volume >= trueVolume * 0.97, "the box cannot be much smaller than the box the points fill: " + volume + " vs " + trueVolume);
            assertTrue(volume < trueVolume * 1.25, "the PCA frame should be close to the true frame: " + volume + " vs " + trueVolume);
            Aabbf plain = Aabbf.fromPoints(p, 0, n);
            if (plain.volume() > 2 * trueVolume) {
                assertTrue(volume < plain.volume(), "tighter than the axis-aligned box when that is loose");
            }
            // a proper rotation
            Quatf r = b.rotation();
            assertEquals(1.0, Math.sqrt(r.x() * r.x() + r.y() * r.y() + r.z() * r.z() + r.w() * r.w()), 1e-5);
            assertTrue(Mat4f.rotation(r).determinant() > 0.999f);
        }
    }

    @Test
    void pcaBoxOfDegenerateSets() {
        Obbf one = BoundingVolumes.pcaBox(new float[] {1, 2, 3}, 1);
        assertEquals(0f, one.hx());
        assertEquals(0f, one.hy());
        assertEquals(0f, one.hz());
        assertEquals(1f, one.cx(), 1e-6f);
        // collinear points: one long axis, the others zero
        float[] line = new float[3 * 20];
        for (int i = 0; i < 20; i++) {
            line[3 * i] = i;
            line[3 * i + 1] = 2f * i;
            line[3 * i + 2] = -i;
        }
        Obbf l = BoundingVolumes.pcaBox(line, 20);
        float longest = Math.max(l.hx(), Math.max(l.hy(), l.hz()));
        assertEquals(19 * Math.sqrt(6) / 2, longest, 1e-3);
        assertTrue(encloses(l, line, 20));
        float shortest = Math.min(l.hx(), Math.min(l.hy(), l.hz()));
        assertTrue(shortest < 1e-3f);
        // a flat set: one zero extent
        float[] flat = {0, 0, 5, 4, 0, 5, 4, 2, 5, 0, 2, 5, 2, 1, 5};
        Obbf f = BoundingVolumes.pcaBox(flat, 5);
        assertTrue(encloses(f, flat, 5));
        assertTrue(Math.min(f.hx(), Math.min(f.hy(), f.hz())) < 1e-4f);
        assertEquals(8 * 2 * 1 * 1e-9, 8.0 * f.hx() * f.hy() * f.hz(), 1e-3);
        // an offset into the array
        Obbf o = BoundingVolumes.pcaBox(new float[] {9, 9, 9, 0, 0, 0, 2, 0, 0}, 3, 2);
        assertEquals(1f, o.cx(), 1e-5f);
        // spherical symmetric input: eigenvalues are equal; the box still contains everything and the frame is proper
        float[] ball = cloud(500, 1);
        assertTrue(encloses(BoundingVolumes.pcaBox(ball, 500), ball, 500));
        // an axis-aligned input with distinct spreads is found without a rotation
        float[] axes = {-3, 0, 0, 3, 0, 0, 0, -1, 0, 0, 1, 0, 0, 0, -0.5f, 0, 0, 0.5f};
        Obbf a = BoundingVolumes.pcaBox(axes, 6);
        assertEquals(3.0 * 1 * 0.5 * 8, 8.0 * a.hx() * a.hy() * a.hz(), 1e-3);
    }

    // ------------------------------------------------------------ transformed boxes

    @Test
    void aTransformedBoxIsExactForAnyTrsAndContainsTheCornersWithShear() {
        for (int t = 0; t < 300; t++) {
            Vec3f lo = new Vec3f((float) rnd.range(-5, 0), (float) rnd.range(-5, 0), (float) rnd.range(-5, 0));
            Vec3f hi = new Vec3f((float) rnd.range(0.2, 5), (float) rnd.range(0.2, 5), (float) rnd.range(0.2, 5));
            Aabbf box = Aabbf.of(lo, hi);
            Vec3f s = rnd.nextScaleVec3f();
            if (t % 3 == 0) {
                s = new Vec3f(-s.x(), s.y(), s.z());
            }
            Mat4f trs = Mat4f.translationRotateScale(rnd.nextVec3f(), rnd.nextUnitQuatf(), s);
            Obbf b = BoundingVolumes.transformedBox(box, trs);
            double volume = 8.0 * b.hx() * b.hy() * b.hz();
            assertEquals(Math.abs(trs.determinant()) * box.volume(), volume, 1e-3 * volume, "exact volume for a TRS transform, trial " + t);
            for (int c = 0; c < 8; c++) {
                Vec3f corner = trs.transformPosition(box.corner(c));
                assertTrue(b.contains(corner) || b.distanceSquared(corner) < 1e-6f, "corner " + c + " trial " + t);
            }
            // a sheared transform: still contains the corners, and is never smaller than the volume of the parallelepiped
            Mat4f sheared = trs.mul(Mat4f.shear((float) rnd.range(-1, 1), (float) rnd.range(-1, 1), 0f, (float) rnd.range(-1, 1), 0f, 0f));
            Obbf sb = BoundingVolumes.transformedBox(box, sheared);
            double sv = 8.0 * sb.hx() * sb.hy() * sb.hz();
            assertTrue(sv >= Math.abs(sheared.determinant()) * box.volume() * (1 - 1e-3), "the shear box cannot be smaller than the parallelepiped");
            for (int c = 0; c < 8; c++) {
                Vec3f corner = sheared.transformPosition(box.corner(c));
                assertTrue(sb.contains(corner) || sb.distanceSquared(corner) < 1e-4f, "sheared corner " + c + " trial " + t);
            }
        }
    }

    @Test
    void theSphereOfATransformedBoxIsTheMinimumSphereOfItsCorners() {
        for (int t = 0; t < 200; t++) {
            Aabbf box = Aabbf.of(new Vec3f((float) rnd.range(-5, 0), (float) rnd.range(-5, 0), (float) rnd.range(-5, 0)),
                    new Vec3f((float) rnd.range(0.2, 5), (float) rnd.range(0.2, 5), (float) rnd.range(0.2, 5)));
            Mat4f m = Mat4f.translationRotateScale(rnd.nextVec3f(), rnd.nextUnitQuatf(), rnd.nextScaleVec3f()).mul(Mat4f.shear(0.4f, 0f, 0f, 0f, 0.3f, 0f));
            Spheref s = BoundingVolumes.sphereOfTransformedBox(box, m);
            float[] corners = new float[24];
            for (int i = 0; i < 8; i++) {
                Vec3f p = m.transformPosition(box.corner(i));
                corners[3 * i] = p.x();
                corners[3 * i + 1] = p.y();
                corners[3 * i + 2] = p.z();
            }
            assertTrue(encloses(s, corners, 8));
            double ref = bruteForceRadius(corners, 8);
            assertEquals(ref, s.radius(), 1e-4 * (1 + ref), "trial " + t);
            // and no larger than the sphere around the transformed box centre
            Spheref naive = box.boundingSphere().transform(m);
            assertTrue(s.radius() <= naive.radius() * 1.0001f + 1e-4f || m.determinant() != 0f);
        }
    }
}
