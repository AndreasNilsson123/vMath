package vmath.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class IkSolverTest {

    final Rnd rnd = Rnd.create();

    /** A chain skeleton: joint 0 has a random parent-less transform with uniform scale; every joint has a random rotation and an offset of random length from its parent. */
    private Skeleton chainSkeleton(int n, boolean uniformScale) {
        int[] parents = new int[n];
        float[] bind = new float[n * 10];
        for (int j = 0; j < n; j++) {
            parents[j] = j - 1;
            double x, y, z, w, len;
            do {
                x = rnd.range(-1, 1);
                y = rnd.range(-1, 1);
                z = rnd.range(-1, 1);
                w = rnd.range(-1, 1);
                len = Math.sqrt(x * x + y * y + z * z + w * w);
            } while (len < 0.2);
            int o = j * 10;
            double ox = rnd.range(-1, 1), oy = rnd.range(-1, 1), oz = rnd.range(-1, 1);
            double ol = Math.max(0.3, Math.sqrt(ox * ox + oy * oy + oz * oz));
            double scale = 1;
            if (uniformScale && j == 0) {
                scale = rnd.range(0.5, 2);
            }
            double norm = Math.max(1e-6, Math.sqrt(ox * ox + oy * oy + oz * oz));
            bind[o] = (float) (ox / norm * ol);
            bind[o + 1] = (float) (oy / norm * ol);
            bind[o + 2] = (float) (oz / norm * ol);
            bind[o + 3] = (float) (x / len);
            bind[o + 4] = (float) (y / len);
            bind[o + 5] = (float) (z / len);
            bind[o + 6] = (float) (w / len);
            bind[o + 7] = bind[o + 8] = bind[o + 9] = (float) scale;
        }
        return new Skeleton(parents, bind);
    }

    private static float[] world(Skeleton skeleton, Pose pose) {
        float[] w = new float[16 * skeleton.jointCount()];
        Skinning.worldMatrices(skeleton, pose, w);
        return w;
    }

    private static double[] position(float[] w, int joint) {
        return new double[] {w[joint * 16 + 12], w[joint * 16 + 13], w[joint * 16 + 14]};
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static double reach(float[] w, int n) {
        double total = 0;
        for (int j = 0; j + 1 < n; j++) {
            total += dist(position(w, j), position(w, j + 1));
        }
        return total;
    }

    private static int[] chain(int n) {
        int[] c = new int[n];
        for (int i = 0; i < n; i++) {
            c[i] = i;
        }
        return c;
    }

    private void assertBonesPreserved(float[] before, float[] after, int n, double tol) {
        for (int j = 0; j + 1 < n; j++) {
            double a = dist(position(before, j), position(before, j + 1)), b = dist(position(after, j), position(after, j + 1));
            assertEquals(a, b, tol * Math.max(1, a), "bone " + j);
        }
        double[] p0 = position(before, 0), q0 = position(after, 0);
        assertTrue(dist(p0, q0) < 1e-6, "the root moved");
    }

    private double[] reachableTarget(float[] w, int n, double fraction) {
        double[] root = position(w, 0);
        double r = reach(w, n) * fraction;
        double x, y, z, l;
        do {
            x = rnd.range(-1, 1);
            y = rnd.range(-1, 1);
            z = rnd.range(-1, 1);
            l = Math.sqrt(x * x + y * y + z * z);
        } while (l < 0.1);
        double s = r * Math.cbrt(rnd.range(0.05, 1)) / l;
        return new double[] {root[0] + x * s, root[1] + y * s, root[2] + z * s};
    }

    @Test
    void twoBoneReachesTheTargetAndKeepsTheBones() {
        for (int t = 0; t < 400; t++) {
            Skeleton skeleton = chainSkeleton(3, true);
            Pose pose = new Pose(skeleton);
            pose.setToBind(skeleton);
            IkSolver ik = new IkSolver(skeleton);
            float[] before = world(skeleton, pose);
            double a = dist(position(before, 0), position(before, 1)), b = dist(position(before, 1), position(before, 2));
            // a reachable target: between the minimum and maximum reach
            double[] root = position(before, 0);
            double lo = Math.abs(a - b) * 1.05 + 1e-3, hi = (a + b) * 0.98;
            double d = rnd.range(lo, hi);
            double x, y, z, l;
            do {
                x = rnd.range(-1, 1);
                y = rnd.range(-1, 1);
                z = rnd.range(-1, 1);
                l = Math.sqrt(x * x + y * y + z * z);
            } while (l < 0.1);
            float tx = (float) (root[0] + x / l * d), ty = (float) (root[1] + y / l * d), tz = (float) (root[2] + z / l * d);
            float left = ik.twoBone(pose, 0, 1, 2, tx, ty, tz, (float) rnd.range(-3, 3), (float) rnd.range(-3, 3), (float) rnd.range(-3, 3));
            float[] after = world(skeleton, pose);
            assertEquals(0.0, dist(position(after, 2), new double[] {tx, ty, tz}), 2e-4 * (a + b), "tip");
            assertEquals(0.0, left, 2e-4 * (a + b), "returned distance");
            assertBonesPreserved(before, after, 3, 1e-4);
        }
    }

    @Test
    void twoBonePoleDecidesTheBendSide() {
        for (int t = 0; t < 200; t++) {
            Skeleton skeleton = chainSkeleton(3, false);
            Pose pose = new Pose(skeleton);
            pose.setToBind(skeleton);
            IkSolver ik = new IkSolver(skeleton);
            float[] before = world(skeleton, pose);
            double a = dist(position(before, 0), position(before, 1)), b = dist(position(before, 1), position(before, 2));
            double[] root = position(before, 0);
            double d = (a + b) * rnd.range(0.5, 0.9);
            d = Math.max(d, Math.abs(a - b) * 1.2 + 1e-2);
            float tx = (float) (root[0] + d), ty = (float) root[1], tz = (float) root[2];
            // a pole off the axis: the middle joint must end on the pole's side of the axis
            double px = root[0] + 1, py = rnd.range(-4, 4), pz = rnd.range(-4, 4);
            if (Math.hypot(py - root[1], pz - root[2]) < 0.5) {
                continue;
            }
            ik.twoBone(pose, 0, 1, 2, tx, ty, tz, (float) px, (float) py, (float) pz);
            double[] mid = position(world(skeleton, pose), 1);
            double my = mid[1] - root[1], mz = mid[2] - root[2], qy = py - root[1], qz = pz - root[2];
            double cos = (my * qy + mz * qz) / (Math.hypot(my, mz) * Math.hypot(qy, qz));
            assertTrue(cos > 0.999, "the knee points at the pole, cos = " + cos);
        }
    }

    @Test
    void unreachableTargetsStretchTheChainTowardsThem() {
        for (int t = 0; t < 100; t++) {
            int n = 2 + (int) rnd.range(1, 6);
            Skeleton skeleton = chainSkeleton(n, true);
            Pose pose = new Pose(skeleton);
            pose.setToBind(skeleton);
            IkSolver ik = new IkSolver(skeleton);
            float[] before = world(skeleton, pose);
            double total = reach(before, n);
            double[] root = position(before, 0);
            float tx = (float) (root[0] + total * 2), ty = (float) (root[1] + total), tz = (float) (root[2] - total);
            double expected = Math.sqrt(Math.pow(total * 2, 2) + total * total * 2) - total;
            float left = n == 3 ? ik.twoBone(pose, 0, 1, 2, tx, ty, tz) : ik.fabrik(pose, chain(n), n, tx, ty, tz, 20, 1e-5f);
            float[] after = world(skeleton, pose);
            assertEquals(expected, left, 1e-3 * total, "remaining distance");
            assertBonesPreserved(before, after, n, 1e-4);
            // straight: the tip is at the full reach from the root, in the direction of the target
            double[] tip = position(after, n - 1);
            assertEquals(total, dist(root, tip), 1e-3 * total);
        }
    }

    @Test
    void fabrikAndCcdReachReachableTargets() {
        int fabrikHits = 0, ccdHits = 0, trials = 300;
        for (int t = 0; t < trials; t++) {
            int n = 3 + (int) rnd.range(0, 6);
            Skeleton skeleton = chainSkeleton(n, true);
            IkSolver ik = new IkSolver(skeleton);
            Pose p1 = new Pose(skeleton);
            p1.setToBind(skeleton);
            float[] before = world(skeleton, p1);
            double[] target = reachableTarget(before, n, 0.9);
            Pose p2 = new Pose(skeleton);
            p2.copyFrom(p1);
            float left = ik.fabrik(p1, chain(n), n, (float) target[0], (float) target[1], (float) target[2], 1000, 1e-4f);
            float[] after = world(skeleton, p1);
            assertEquals(left, dist(position(after, n - 1), target), 1e-4 * reach(before, n) + 1e-5, "fabrik: reported distance is the real one");
            assertBonesPreserved(before, after, n, 1e-4);
            if (left < 1e-3 * reach(before, n)) {
                fabrikHits++;
            }
            left = ik.ccd(p2, chain(n), n, (float) target[0], (float) target[1], (float) target[2], 200, 1e-4f);
            after = world(skeleton, p2);
            assertEquals(left, dist(position(after, n - 1), target), 1e-4 * reach(before, n) + 1e-5, "ccd: reported distance is the real one");
            assertBonesPreserved(before, after, n, 1e-4);
            if (left < 1e-3 * reach(before, n)) {
                ccdHits++;
            }
        }
        // neither method is guaranteed to converge (FABRIK can stall in a folded configuration), so both only have to get there nearly always
        assertTrue(fabrikHits >= trials * 0.98, "fabrik reached " + fabrikHits + " of " + trials);
        assertTrue(ccdHits >= trials * 0.95, "ccd reached " + ccdHits + " of " + trials);
    }

    @Test
    void chainsBelowRotatedAncestorsAreSolvedInTheirOwnSpace() {
        // joints 0 and 1 are ancestors with random rotation; the chain is joints 2..5
        for (int t = 0; t < 100; t++) {
            Skeleton skeleton = chainSkeleton(6, true);
            Pose pose = new Pose(skeleton);
            pose.setToBind(skeleton);
            IkSolver ik = new IkSolver(skeleton);
            int[] c = {2, 3, 4, 5};
            float[] before = world(skeleton, pose);
            double total = dist(position(before, 2), position(before, 3)) + dist(position(before, 3), position(before, 4)) + dist(position(before, 4), position(before, 5));
            double[] root = position(before, 2);
            double[] target = {root[0] + total * 0.3, root[1] - total * 0.2, root[2] + total * 0.4};
            float left = ik.fabrik(pose, c, 4, (float) target[0], (float) target[1], (float) target[2], 1000, 1e-5f);
            float[] after = world(skeleton, pose);
            assertEquals(0.0, left, 2e-4 * total);
            assertEquals(0.0, dist(position(after, 5), target), 2e-4 * total);
            assertTrue(dist(position(after, 2), root) < 1e-5, "chain root fixed");
        }
    }

    @Test
    void lookAtTurnsTheAxisToTheTarget() {
        for (int t = 0; t < 300; t++) {
            Skeleton skeleton = chainSkeleton(3, true);
            Pose pose = new Pose(skeleton);
            pose.setToBind(skeleton);
            IkSolver ik = new IkSolver(skeleton);
            double[] target = {rnd.range(-5, 5), rnd.range(-5, 5), rnd.range(-5, 5)};
            double fx = rnd.range(-1, 1), fy = rnd.range(-1, 1), fz = rnd.range(-1, 1);
            if (Math.sqrt(fx * fx + fy * fy + fz * fz) < 0.1) {
                continue;
            }
            float left = ik.lookAt(pose, 1, (float) fx, (float) fy, (float) fz, (float) target[0], (float) target[1], (float) target[2], 1f);
            assertEquals(0.0, left, 2e-3);
            float[] w = world(skeleton, pose);
            // the world direction of the local axis, normalised, equals the direction to the target
            int m = 16;
            double s = Math.sqrt(w[m] * w[m] + w[m + 1] * w[m + 1] + w[m + 2] * w[m + 2]);
            double ax = (w[m] * fx + w[m + 4] * fy + w[m + 8] * fz) / s, ay = (w[m + 1] * fx + w[m + 5] * fy + w[m + 9] * fz) / s,
                    az = (w[m + 2] * fx + w[m + 6] * fy + w[m + 10] * fz) / s;
            double al = Math.sqrt(ax * ax + ay * ay + az * az);
            double[] p = position(w, 1);
            double dx = target[0] - p[0], dy = target[1] - p[1], dz = target[2] - p[2], dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(1.0, (ax * dx + ay * dy + az * dz) / (al * dl), 1e-5);
        }
    }

    @Test
    void lookAtWithUpKeepsTheUpAxisTowardsTheWorldUp() {
        for (int t = 0; t < 300; t++) {
            Skeleton skeleton = chainSkeleton(2, false);
            Pose pose = new Pose(skeleton);
            pose.setIdentity();
            IkSolver ik = new IkSolver(skeleton);
            // identity pose: joint 1 sits at the bind translation under an identity parent; forward is local +z, up local +y
            double[] target = {rnd.range(-5, 5), rnd.range(-5, 5), rnd.range(-5, 5)};
            float[] w0 = world(skeleton, pose);
            double[] p = position(w0, 1);
            double dx = target[0] - p[0], dy = target[1] - p[1], dz = target[2] - p[2], dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dl < 0.5 || Math.abs(dy / dl) > 0.95) {
                continue;
            }
            ik.lookAt(pose, 1, 0f, 0f, 1f, 0f, 1f, 0f, (float) target[0], (float) target[1], (float) target[2], 0f, 1f, 0f, 1f);
            float[] w = world(skeleton, pose);
            int m = 16;
            double ux = w[m + 4], uy = w[m + 5], uz = w[m + 6];
            double fx = w[m + 8], fy = w[m + 9], fz = w[m + 10];
            // the up axis has no sideways component: it lies in the plane of the forward axis and the world up
            double rx = fy * 0 - fz * 1, ry = fz * 0 - fx * 0, rz = fx * 1 - fy * 0; // forward x worldUp
            assertEquals(0.0, ux * rx + uy * ry + uz * rz, 1e-4 * Math.sqrt(rx * rx + rz * rz) + 1e-6, "no roll sideways");
            assertTrue(uy > 0, "up stays up");
        }
    }

    @Test
    void lookAtWeightBlendsFromTheOriginalPose() {
        Skeleton skeleton = chainSkeleton(2, false);
        Pose pose = new Pose(skeleton);
        pose.setToBind(skeleton);
        IkSolver ik = new IkSolver(skeleton);
        float[] original = pose.data().clone();
        float full = ik.lookAt(pose, 1, 0f, 0f, 1f, 4f, 1f, -3f, 0f);
        for (int i = 0; i < original.length; i++) {
            assertEquals(original[i], pose.data()[i], 1e-6, "weight 0 leaves the pose alone");
        }
        float half = ik.lookAt(pose, 1, 0f, 0f, 1f, 4f, 1f, -3f, 0.5f);
        float one = ik.lookAt(pose, 1, 0f, 0f, 1f, 4f, 1f, -3f, 1f);
        assertTrue(half < full && one < 1e-3f && one <= half, "angle left: " + full + " " + half + " " + one);
    }

    @Test
    void degenerateInputsStayFinite() {
        for (int t = 0; t < 60; t++) {
            Skeleton skeleton = chainSkeleton(3, true);
            Pose pose = new Pose(skeleton);
            pose.setToBind(skeleton);
            IkSolver ik = new IkSolver(skeleton);
            double[] root = position(world(skeleton, pose), 0);
            float rx = (float) root[0], ry = (float) root[1], rz = (float) root[2];
            // the target at the root, a pole on the root-target axis, a pole at the root, the keep-the-bend overload, a target closer than the minimum reach
            ik.twoBone(pose, 0, 1, 2, rx, ry, rz, rx, ry + 1, rz);
            ik.twoBone(pose, 0, 1, 2, rx + 1f, ry, rz, rx + 3f, ry, rz);
            ik.twoBone(pose, 0, 1, 2, rx, ry, rz + 1f, rx, ry, rz);
            ik.twoBone(pose, 0, 1, 2, rx, ry + 1f, rz);
            ik.twoBone(pose, 0, 1, 2, rx, ry, rz + 0.001f, rx, ry, rz + 2f);
            ik.twoBone(pose, 0, 1, 2, rx + 1f, ry, rz, rx, ry + 1f, rz);
            ik.twoBone(pose, 0, 1, 2, rx, ry + 1f, rz, rx, ry, rz + 1f);
            ik.twoBone(pose, 0, 1, 2, rx - 1f, ry, rz, rx, ry, rz);
            ik.fabrik(pose, chain(3), 3, rx, ry, rz, 8, 1e-4f);
            ik.ccd(pose, chain(3), 3, rx, ry, rz, 8, 1e-4f);
            ik.ccd(pose, chain(3), 3, rx + 1000f, ry, rz, 8, 1e-4f);
            ik.fabrik(pose, chain(3), 3, rx + 1000f, ry, rz, 8, 1e-4f);
            ik.lookAt(pose, 1, 0f, 1f, 0f, (float) position(world(skeleton, pose), 1)[0], (float) position(world(skeleton, pose), 1)[1], (float) position(world(skeleton, pose), 1)[2], 1f);
            for (float v : pose.data()) {
                assertTrue(Float.isFinite(v));
            }
        }
    }

    @Test
    void lookAtHandlesOppositeAndParallelUp() {
        Skeleton skeleton = chainSkeleton(2, false);
        Pose pose = new Pose(skeleton);
        pose.setIdentity();
        IkSolver ik = new IkSolver(skeleton);
        double[] p = position(world(skeleton, pose), 1);
        // the target straight behind the axis (a half turn), and straight along the world up with an up axis given
        ik.lookAt(pose, 1, 0f, 0f, 1f, (float) p[0], (float) p[1], (float) p[2] - 3f, 1f);
        ik.lookAt(pose, 1, 1f, 0f, 0f, (float) p[0] - 3f, (float) p[1], (float) p[2], 1f);
        ik.lookAt(pose, 1, 0f, 0f, 1f, 0f, 1f, 0f, (float) p[0], (float) p[1] + 4f, (float) p[2], 0f, 1f, 0f, 1f);
        ik.lookAt(pose, 1, 0f, 0f, 1f, 0f, 1f, 0f, (float) p[0] + 1f, (float) p[1] + 1f, (float) p[2], 0f, 1f, 0f, 1f);
        ik.lookAt(pose, 1, 0f, 0f, 1f, 0f, 1f, 0f, (float) p[0] + 1f, (float) p[1] + 1f, (float) p[2], 0f, 1f, 0f, 1f);
        for (float v : pose.data()) {
            assertTrue(Float.isFinite(v));
        }
    }

    @Test
    void rejectsBadArguments() {
        Skeleton skeleton = chainSkeleton(4, false);
        Pose pose = new Pose(skeleton);
        IkSolver ik = new IkSolver(skeleton);
        assertThrows(IllegalArgumentException.class, () -> ik.fabrik(pose, new int[] {0, 2}, 2, 1f, 1f, 1f, 4, 1e-3f), "2 is not a child of 0");
        assertThrows(IllegalArgumentException.class, () -> ik.fabrik(pose, new int[] {0}, 1, 1f, 1f, 1f, 4, 1e-3f), "a chain needs two joints");
        assertThrows(IllegalArgumentException.class, () -> ik.ccd(pose, new int[] {0, 9}, 2, 1f, 1f, 1f, 4, 1e-3f));
        assertThrows(IllegalArgumentException.class, () -> ik.lookAt(pose, 1, 0f, 0f, 0f, 1f, 1f, 1f, 1f), "zero forward axis");
        assertThrows(IllegalArgumentException.class, () -> ik.lookAt(pose, 7, 0f, 0f, 1f, 1f, 1f, 1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> ik.lookAt(new Pose(2), 1, 0f, 0f, 1f, 1f, 1f, 1f, 1f), "pose and skeleton disagree");
    }
}
