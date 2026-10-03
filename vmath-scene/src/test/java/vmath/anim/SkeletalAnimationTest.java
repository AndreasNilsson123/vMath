package vmath.anim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import vmath.bulk.Mat4fArray;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Transformf;
import vmath.core.Vec3f;

class SkeletalAnimationTest {

    final Rnd rnd = Rnd.create();

    // ------------------------------------------------------------ helpers

    private float[] randomQuat() {
        double x, y, z, w, len;
        do {
            x = rnd.range(-1, 1);
            y = rnd.range(-1, 1);
            z = rnd.range(-1, 1);
            w = rnd.range(-1, 1);
            len = Math.sqrt(x * x + y * y + z * z + w * w);
        } while (len < 0.2);
        return new float[] {(float) (x / len), (float) (y / len), (float) (z / len), (float) (w / len)};
    }

    private float[] randomTrs() {
        float[] q = randomQuat();
        return new float[] {(float) rnd.range(-2, 2), (float) rnd.range(-2, 2), (float) rnd.range(-2, 2), q[0], q[1], q[2], q[3],
                (float) rnd.range(0.7, 1.3), (float) rnd.range(0.7, 1.3), (float) rnd.range(0.7, 1.3)};
    }

    private Skeleton randomSkeleton(int n) {
        int[] parents = new int[n];
        float[] bind = new float[n * 10];
        for (int j = 0; j < n; j++) {
            parents[j] = j == 0 || rnd.range(0, 1) < 0.05 ? -1 : (int) rnd.range(Math.max(0, j - 4), j);
            System.arraycopy(randomTrs(), 0, bind, j * 10, 10);
        }
        return new Skeleton(parents, bind);
    }

    private static boolean sameRotation(float[] a, int ao, float[] b, int bo, double tol) {
        double dot = a[ao] * b[bo] + a[ao + 1] * b[bo + 1] + a[ao + 2] * b[bo + 2] + a[ao + 3] * b[bo + 3];
        return Math.abs(dot) >= 1 - tol;
    }

    /** World matrices of a pose in double precision, column-major, straight from the definition T * R * S down the chain. */
    private static double[][] oracleWorld(Skeleton s, Pose p) {
        int n = s.jointCount();
        double[][] w = new double[n][];
        for (int j = 0; j < n; j++) {
            double[] l = trsMatrix(p.data(), j * 10);
            w[j] = s.parent(j) < 0 ? l : mul(w[s.parent(j)], l);
        }
        return w;
    }

    private static double[] trsMatrix(float[] t, int o) {
        double x = t[o + 3], y = t[o + 4], z = t[o + 5], w = t[o + 6];
        double len = Math.sqrt(x * x + y * y + z * z + w * w);
        x /= len;
        y /= len;
        z /= len;
        w /= len;
        double sx = t[o + 7], sy = t[o + 8], sz = t[o + 9];
        return new double[] {
                (1 - 2 * (y * y + z * z)) * sx, 2 * (x * y + w * z) * sx, 2 * (x * z - w * y) * sx, 0,
                2 * (x * y - w * z) * sy, (1 - 2 * (x * x + z * z)) * sy, 2 * (y * z + w * x) * sy, 0,
                2 * (x * z + w * y) * sz, 2 * (y * z - w * x) * sz, (1 - 2 * (x * x + y * y)) * sz, 0,
                t[o], t[o + 1], t[o + 2], 1};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + row] * b[c * 4 + k];
                }
                r[c * 4 + row] = s;
            }
        }
        return r;
    }

    private static double[] invertAffine(double[] m) {
        double a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        double[] r = new double[16];
        r[0] = (e * i - f * h) / det;
        r[1] = -(d * i - f * g) / det;
        r[2] = (d * h - e * g) / det;
        r[4] = -(b * i - c * h) / det;
        r[5] = (a * i - c * g) / det;
        r[6] = -(a * h - b * g) / det;
        r[8] = (b * f - c * e) / det;
        r[9] = -(a * f - c * d) / det;
        r[10] = (a * e - b * d) / det;
        r[12] = -(r[0] * m[12] + r[4] * m[13] + r[8] * m[14]);
        r[13] = -(r[1] * m[12] + r[5] * m[13] + r[9] * m[14]);
        r[14] = -(r[2] * m[12] + r[6] * m[13] + r[10] * m[14]);
        r[15] = 1;
        return r;
    }

    private static double[] apply(double[] m, double x, double y, double z) {
        return new double[] {m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14]};
    }

    /** Standard slerp in double along the shortest arc. */
    private static double[] slerpD(double[] a, double[] b, double t) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        double[] bb = b.clone();
        if (dot < 0) {
            for (int k = 0; k < 4; k++) {
                bb[k] = -bb[k];
            }
            dot = -dot;
        }
        double s0, s1;
        if (dot > 0.9999999) {
            s0 = 1 - t;
            s1 = t;
        } else {
            double omega = Math.acos(dot), sin = Math.sin(omega);
            s0 = Math.sin((1 - t) * omega) / sin;
            s1 = Math.sin(t * omega) / sin;
        }
        double[] r = new double[4];
        double len = 0;
        for (int k = 0; k < 4; k++) {
            r[k] = s0 * a[k] + s1 * bb[k];
            len += r[k] * r[k];
        }
        len = Math.sqrt(len);
        for (int k = 0; k < 4; k++) {
            r[k] /= len;
        }
        return r;
    }

    // ------------------------------------------------------------ skeleton

    @Test
    void skeletonValidatesItsInput() {
        float[] id = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(new int[] {1, -1}, concat(id, id)), "parent after child");
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(new int[] {-1, 1}, concat(id, id)), "self parent");
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(new int[] {-1}, new float[5]), "wrong length");
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(new int[] {-1}, id, new String[2]), "wrong names");
        float[] flat = id.clone();
        flat[8] = 0f;
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(new int[] {-1}, flat), "zero scale cannot be inverted");
        float[] nan = id.clone();
        nan[0] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(new int[] {-1}, nan), "non-finite");
        Skeleton s = new Skeleton(new int[] {-1, 0}, concat(id, id), new String[] {"root", "child"});
        assertEquals(2, s.jointCount());
        assertEquals("child", s.name(1));
        assertEquals(1, s.indexOf("child"));
        assertEquals(-1, s.indexOf("missing"));
        assertEquals(0, s.parent(1));
    }

    private static float[] concat(float[] a, float[] b) {
        float[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Test
    void inverseBindMatricesInvertTheBindWorldMatrices() {
        for (int trial = 0; trial < 30; trial++) {
            Skeleton s = randomSkeleton(2 + (int) rnd.range(0, 20));
            Pose bind = new Pose(s);
            double[][] w = oracleWorld(s, bind);
            float[] ib = s.inverseBindMatrices();
            for (int j = 0; j < s.jointCount(); j++) {
                double[] product = new double[16];
                double[] inv = new double[16];
                for (int k = 0; k < 16; k++) {
                    inv[k] = ib[j * 16 + k];
                }
                product = mul(inv, w[j]);
                for (int k = 0; k < 16; k++) {
                    assertEquals(k % 5 == 0 ? 1.0 : 0.0, product[k], 2e-4, "inverseBind * bindWorld must be the identity, joint " + j + " element " + k);
                }
            }
        }
    }

    // ------------------------------------------------------------ skinning

    @Test
    void theBindPoseSkinsEveryVertexToItself() {
        for (int trial = 0; trial < 30; trial++) {
            Skeleton s = randomSkeleton(3 + (int) rnd.range(0, 25));
            Pose pose = new Pose(s);
            Mat4fArray joints = new Mat4fArray(1);
            Skinning.jointMatrices(s, pose, new float[s.jointCount() * 16], joints);
            assertEquals(s.jointCount(), joints.size());
            int vc = 40;
            float[] pos = new float[vc * 3], out = new float[vc * 3];
            int[] ji = new int[vc * 4];
            float[] w = new float[vc * 4];
            for (int v = 0; v < vc; v++) {
                for (int k = 0; k < 3; k++) {
                    pos[v * 3 + k] = (float) rnd.range(-3, 3);
                }
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    ji[v * 4 + k] = (int) rnd.range(0, s.jointCount());
                    w[v * 4 + k] = (float) rnd.range(0, 1);
                    sum += w[v * 4 + k];
                }
                for (int k = 0; k < 4; k++) {
                    w[v * 4 + k] /= sum;
                }
            }
            Skinning.skinPositions(joints.data(), pos, ji, w, vc, out);
            for (int i = 0; i < vc * 3; i++) {
                assertEquals(pos[i], out[i], 1e-3f * Math.max(1f, Math.abs(pos[i])), "rest pose must not move vertex data, index " + i);
            }
        }
    }

    /** Three joints in a chain going up the Y axis: joint 0 at the origin, joint 1 at (0,1,0), joint 2 at (0,2,0). */
    private static Skeleton chain() {
        return new Skeleton(new int[] {-1, 0, 1}, new float[] {
                0, 0, 0, 0, 0, 0, 1, 1, 1, 1,
                0, 1, 0, 0, 0, 0, 1, 1, 1, 1,
                0, 1, 0, 0, 0, 0, 1, 1, 1, 1});
    }

    @Test
    void rotatingAJointMovesExactlyTheVerticesBoundToItAndItsChildren() {
        Skeleton s = chain();
        Pose pose = new Pose(s);
        double theta = 0.9;
        pose.setRotation(1, 0f, 0f, (float) Math.sin(theta / 2), (float) Math.cos(theta / 2));
        Mat4fArray joints = new Mat4fArray(1);
        Skinning.jointMatrices(s, pose, new float[48], joints);
        float[] pos = {
                0.5f, 0.2f, 0.3f,   // v0: bound to joint 0 -> stays
                1.0f, 1.5f, -0.4f,  // v1: bound to joint 1 -> rotates about (0,1,0)
                -0.3f, 2.2f, 0.1f,  // v2: bound to joint 2 (a child of joint 1) -> rotates with it
                0.7f, 0.5f, 0.0f};  // v3: half joint 0, half joint 1
        int[] ji = {0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, 0};
        float[] w = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0.5f, 0.5f, 0, 0};
        float[] out = new float[12];
        Skinning.skinPositions(joints.data(), pos, ji, w, 4, out);
        double c = Math.cos(theta), sn = Math.sin(theta);
        for (int v : new int[] {1, 2}) {
            double dx = pos[v * 3], dy = pos[v * 3 + 1] - 1, dz = pos[v * 3 + 2];
            assertEquals(c * dx - sn * dy, out[v * 3], 1e-5, "x of vertex " + v);
            assertEquals(1 + sn * dx + c * dy, out[v * 3 + 1], 1e-5, "y of vertex " + v);
            assertEquals(dz, out[v * 3 + 2], 1e-5, "z of vertex " + v);
        }
        for (int k = 0; k < 3; k++) {
            assertEquals(pos[k], out[k], 1e-6f, "a vertex bound to the root does not move");
        }
        double dx = pos[9], dy = pos[10] - 1;
        assertEquals(0.5 * pos[9] + 0.5 * (c * dx - sn * dy), out[9], 1e-5, "split weights blend linearly (x)");
        assertEquals(0.5 * pos[10] + 0.5 * (1 + sn * dx + c * dy), out[10], 1e-5, "split weights blend linearly (y)");
    }

    @Test
    void normalsFollowTheRotationAndStayUnit() {
        Skeleton s = chain();
        Pose pose = new Pose(s);
        pose.setRotation(1, 0f, 0f, (float) Math.sin(0.5), (float) Math.cos(0.5)); // 1 radian about Z
        Mat4fArray joints = new Mat4fArray(1);
        Skinning.jointMatrices(s, pose, new float[48], joints);
        float[] out = new float[3];
        Skinning.skinNormals(joints.data(), new float[] {1f, 0f, 0f}, new int[] {1, 0, 0, 0}, new float[] {1, 0, 0, 0}, 1, out);
        assertEquals(Math.cos(1.0), out[0], 1e-5);
        assertEquals(Math.sin(1.0), out[1], 1e-5);
        assertEquals(0.0, out[2], 1e-6);
        Skinning.skinNormals(joints.data(), new float[] {1f, 0f, 0f}, new int[4], new float[4], 1, out); // all-zero weights
        assertEquals(0f, out[0]);
    }

    @Test
    void weightsPackToExactlyOneAndRoundTrip() {
        for (int trial = 0; trial < 500; trial++) {
            float[] w = new float[4];
            for (int k = 0; k < 4; k++) {
                w[k] = rnd.range(0, 1) < 0.3 ? 0f : (float) rnd.range(0, 1);
            }
            float sum = w[0] + w[1] + w[2] + w[3];
            int packed = Skinning.packWeights(w, 0);
            int total = (packed & 0xFF) + ((packed >>> 8) & 0xFF) + ((packed >>> 16) & 0xFF) + (packed >>> 24);
            assertEquals(255, total, "the four bytes always add up to 255");
            float[] back = new float[4];
            Skinning.unpackWeights(packed, back, 0);
            if (sum > 0f) {
                for (int k = 0; k < 4; k++) {
                    assertEquals(w[k] / sum, back[k], 2.5f / 255f, "each weight within a quantisation step");
                }
            }
        }
        assertEquals(255, Skinning.packWeights(new float[] {0, 0, 0, 0}, 0), "all zero: full weight on the first joint");
        assertEquals(255 << 8, Skinning.packWeights(new float[] {Float.NaN, 3f, -1f, 0f}, 0), "NaN and negatives count as zero");
    }

    // ------------------------------------------------------------ slerp and blending

    @Test
    void slerpMatchesQuatfIncludingTheLongWayAroundCase() {
        for (int trial = 0; trial < 2000; trial++) {
            float[] a = randomQuat(), b = randomQuat();
            float t = (float) rnd.range(0, 1);
            Pose pa = new Pose(1), pb = new Pose(1), out = new Pose(1);
            pa.setRotation(0, a[0], a[1], a[2], a[3]);
            pb.setRotation(0, b[0], b[1], b[2], b[3]);
            Pose.lerp(pa, pb, t, out);
            Quatf expected = new Quatf(a[0], a[1], a[2], a[3]).slerp(new Quatf(b[0], b[1], b[2], b[3]), t);
            float[] o = out.data();
            double dot = expected.x() * o[3] + expected.y() * o[4] + expected.z() * o[5] + expected.w() * o[6];
            assertTrue(Math.abs(dot) > 1 - 2e-6, "matches Quatf.slerp up to sign, dot " + dot);
            double len = Math.sqrt(o[3] * o[3] + o[4] * o[4] + o[5] * o[5] + o[6] * o[6]);
            assertEquals(1.0, len, 1e-6, "result is unit length");
        }
    }

    @Test
    void blendingEndpointsMidpointsAndMasks() {
        Skeleton s = randomSkeleton(8);
        Pose a = new Pose(s), b = new Pose(s), out = new Pose(s);
        for (int j = 0; j < 8; j++) {
            float[] t = randomTrs();
            b.setTranslation(j, t[0], t[1], t[2]);
            b.setRotation(j, t[3], t[4], t[5], t[6]);
            b.setScale(j, t[7], t[8], t[9]);
        }
        Pose.lerp(a, b, 0f, out);
        for (int j = 0; j < 8; j++) {
            assertArrayEquals(Arrays.copyOfRange(a.data(), j * 10, j * 10 + 3), Arrays.copyOfRange(out.data(), j * 10, j * 10 + 3), 1e-6f);
            assertTrue(sameRotation(a.data(), j * 10 + 3, out.data(), j * 10 + 3, 1e-6));
        }
        Pose.lerp(a, b, 1f, out);
        for (int j = 0; j < 8; j++) {
            assertArrayEquals(Arrays.copyOfRange(b.data(), j * 10, j * 10 + 3), Arrays.copyOfRange(out.data(), j * 10, j * 10 + 3), 1e-6f);
            assertTrue(sameRotation(b.data(), j * 10 + 3, out.data(), j * 10 + 3, 1e-6));
            assertArrayEquals(Arrays.copyOfRange(b.data(), j * 10 + 7, j * 10 + 10), Arrays.copyOfRange(out.data(), j * 10 + 7, j * 10 + 10), 1e-6f);
        }
        Pose.lerp(a, b, 0.5f, out);
        for (int j = 0; j < 8; j++) {
            for (int k = 0; k < 3; k++) {
                assertEquals(0.5f * (a.data()[j * 10 + k] + b.data()[j * 10 + k]), out.data()[j * 10 + k], 1e-5f);
            }
        }
        // masked: joints 0..3 fully overlaid, joints 4..7 untouched, joint 3 at half strength through the mask
        float[] mask = {1, 1, 1, 0.5f, 0, 0, 0, 0};
        Pose.blendMasked(a, b, mask, 1f, out);
        for (int j = 0; j < 3; j++) {
            assertEquals(b.data()[j * 10], out.data()[j * 10], 1e-6f);
        }
        assertEquals(0.5f * (a.data()[30] + b.data()[30]), out.data()[30], 1e-5f);
        for (int j = 4; j < 8; j++) {
            assertEquals(a.data()[j * 10], out.data()[j * 10], 1e-6f);
            assertTrue(sameRotation(a.data(), j * 10 + 3, out.data(), j * 10 + 3, 1e-6));
        }
        Pose.blendMasked(a, b, mask, 0f, out); // weight 0: the base
        assertEquals(a.data()[0], out.data()[0], 1e-6f);
        assertThrows(IllegalArgumentException.class, () -> Pose.blendMasked(a, b, new float[3], 1f, out));
        assertThrows(IllegalArgumentException.class, () -> Pose.lerp(a, new Pose(3), 0.5f, out));
    }

    @Test
    void additivePosesRoundTripAndScaleWithWeight() {
        for (int trial = 0; trial < 100; trial++) {
            Skeleton s = randomSkeleton(6);
            Pose reference = new Pose(s), source = new Pose(s), additive = new Pose(s), out = new Pose(s);
            for (int j = 0; j < 6; j++) {
                float[] r = randomTrs(), q = randomTrs();
                reference.setTranslation(j, r[0], r[1], r[2]);
                reference.setRotation(j, r[3], r[4], r[5], r[6]);
                reference.setScale(j, r[7], r[8], r[9]);
                source.setTranslation(j, q[0], q[1], q[2]);
                source.setRotation(j, q[3], q[4], q[5], q[6]);
                source.setScale(j, q[7], q[8], q[9]);
            }
            Pose.makeAdditive(reference, source, additive);
            Pose.applyAdditive(reference, additive, 1f, out);
            for (int j = 0; j < 6; j++) {
                for (int k = 0; k < 3; k++) {
                    assertEquals(source.data()[j * 10 + k], out.data()[j * 10 + k], 1e-4f, "translation round trip");
                    assertEquals(source.data()[j * 10 + 7 + k], out.data()[j * 10 + 7 + k], 1e-4f, "scale round trip");
                }
                assertTrue(sameRotation(source.data(), j * 10 + 3, out.data(), j * 10 + 3, 1e-5), "rotation round trip");
            }
            Pose.applyAdditive(reference, additive, 0f, out); // weight 0: nothing added
            for (int j = 0; j < 6; j++) {
                assertEquals(reference.data()[j * 10], out.data()[j * 10], 1e-6f);
                assertTrue(sameRotation(reference.data(), j * 10 + 3, out.data(), j * 10 + 3, 1e-6));
            }
            Pose in = new Pose(s);
            in.copyFrom(reference);
            Pose.applyAdditive(in, additive, 1f, in); // out may be the base pose itself
            for (int j = 0; j < 6; j++) {
                assertTrue(sameRotation(source.data(), j * 10 + 3, in.data(), j * 10 + 3, 1e-5), "aliased application gives the same result");
            }
        }
    }

    // ------------------------------------------------------------ clips

    private AnimationClip randomClip(int joints, int maxKeys, boolean flipSigns) {
        AnimationClip.Builder b = AnimationClip.builder(joints);
        for (int j = 0; j < joints; j++) {
            for (AnimationClip.Channel c : AnimationClip.Channel.values()) {
                if (rnd.range(0, 1) < 0.25) {
                    continue; // not every channel is animated
                }
                int keys = 1 + (int) rnd.range(0, maxKeys);
                float[] times = new float[keys];
                float t = (float) rnd.range(0, 0.3);
                for (int k = 0; k < keys; k++) {
                    times[k] = t;
                    t += (float) rnd.range(0.05, 0.6);
                }
                float[] values = new float[keys * c.components()];
                for (int k = 0; k < keys; k++) {
                    if (c == AnimationClip.Channel.ROTATION) {
                        float[] q = randomQuat();
                        float sign = flipSigns && rnd.range(0, 1) < 0.5 ? -1f : 1f;
                        for (int m = 0; m < 4; m++) {
                            values[k * 4 + m] = q[m] * sign;
                        }
                    } else {
                        for (int m = 0; m < 3; m++) {
                            values[k * 3 + m] = (float) (c == AnimationClip.Channel.SCALE ? rnd.range(0.5, 1.5) : rnd.range(-3, 3));
                        }
                    }
                }
                b.track(j, c, times, values);
            }
        }
        return b.build();
    }

    @Test
    void builderRejectsBadTracks() {
        assertThrows(IllegalArgumentException.class, () -> AnimationClip.builder(0));
        AnimationClip.Builder b = AnimationClip.builder(2);
        assertThrows(IllegalArgumentException.class, () -> b.translation(2, new float[] {0f}, new float[3]), "joint out of range");
        assertThrows(IllegalArgumentException.class, () -> b.translation(0, new float[0], new float[0]), "no keys");
        assertThrows(IllegalArgumentException.class, () -> b.translation(0, new float[] {0f, 1f}, new float[3]), "wrong value count");
        assertThrows(IllegalArgumentException.class, () -> b.translation(0, new float[] {1f, 1f}, new float[6]), "not strictly increasing");
        assertThrows(IllegalArgumentException.class, () -> b.translation(0, new float[] {1f, 0.5f}, new float[6]), "decreasing");
        assertThrows(IllegalArgumentException.class, () -> b.translation(0, new float[] {-1f}, new float[3]), "negative time");
        assertThrows(IllegalArgumentException.class, () -> b.translation(0, new float[] {Float.NaN}, new float[3]), "NaN time");
        assertThrows(IllegalArgumentException.class, () -> b.translation(0, new float[] {0f}, new float[] {0f, Float.POSITIVE_INFINITY, 0f}), "infinite value");
        b.translation(0, new float[] {0f, 1f}, new float[6]);
        assertThrows(IllegalArgumentException.class, () -> b.translation(0, new float[] {0f}, new float[3]), "duplicate track");
        b.duration(3f);
        assertEquals(3f, b.build().duration());
        assertThrows(IllegalArgumentException.class, () -> b.duration(-1f));
    }

    @Test
    void samplingAtKeyTimesReturnsTheKeysAndBetweenKeysMatchesAReference() {
        for (int trial = 0; trial < 60; trial++) {
            int joints = 1 + (int) rnd.range(0, 6);
            AnimationClip clip = randomClip(joints, 6, true);
            ClipSampler sampler = new ClipSampler(clip);
            Pose pose = new Pose(joints);
            int[] tj = clip.trackJoints(), tc = clip.trackChannels(), ts = clip.trackStarts(), tv = clip.trackValueStarts(), tk = clip.trackKeyCounts();
            float[] times = clip.keyTimes(), values = clip.keyValues();
            for (int tr = 0; tr < clip.trackCount(); tr++) {
                int comps = tc[tr] == 1 ? 4 : 3, dst = tj[tr] * 10 + (tc[tr] == 0 ? 0 : tc[tr] == 1 ? 3 : 7);
                for (int k = 0; k < tk[tr]; k++) { // exactly at a key
                    sampler.sample(times[ts[tr] + k], false, pose);
                    for (int m = 0; m < comps; m++) {
                        if (tc[tr] == 1) {
                            assertTrue(sameRotation(values, tv[tr] + k * 4, pose.data(), dst, 1e-5), "key " + k + " of a rotation track");
                            break;
                        }
                        assertEquals(values[tv[tr] + k * comps + m], pose.data()[dst + m], 1e-5f, "key " + k);
                    }
                }
                for (int k = 0; k + 1 < tk[tr]; k++) { // halfway between two keys, against double precision
                    float t0 = times[ts[tr] + k], t1 = times[ts[tr] + k + 1];
                    float f = (float) rnd.range(0.05, 0.95);
                    sampler.sample(t0 + (t1 - t0) * f, false, pose);
                    float fReal = (Math.fma(f, t1 - t0, t0) - t0) / (t1 - t0);
                    int v0 = tv[tr] + k * comps, v1 = v0 + comps;
                    if (tc[tr] == 1) {
                        double[] qa = {values[v0], values[v0 + 1], values[v0 + 2], values[v0 + 3]};
                        double[] qb = {values[v1], values[v1 + 1], values[v1 + 2], values[v1 + 3]};
                        double[] e = slerpD(qa, qb, fReal);
                        double dot = e[0] * pose.data()[dst] + e[1] * pose.data()[dst + 1] + e[2] * pose.data()[dst + 2] + e[3] * pose.data()[dst + 3];
                        assertTrue(Math.abs(dot) > 1 - 1e-5, "interpolated rotation, dot " + dot);
                    } else {
                        for (int m = 0; m < 3; m++) {
                            double e = values[v0 + m] + (values[v1 + m] - values[v0 + m]) * (double) fReal;
                            assertEquals(e, pose.data()[dst + m], 1e-4, "interpolated value");
                        }
                    }
                }
            }
        }
    }

    @Test
    void rotationsTakeTheShortArcAcrossTheQuaternionSignFlip() {
        float[] q = randomQuat();
        AnimationClip clip = AnimationClip.builder(1)
                .rotation(0, new float[] {0f, 1f}, new float[] {q[0], q[1], q[2], q[3], -q[0], -q[1], -q[2], -q[3]}) // the same rotation twice
                .build();
        ClipSampler sampler = new ClipSampler(clip);
        Pose pose = new Pose(1);
        for (float t = 0f; t <= 1f; t += 0.1f) {
            sampler.sample(t, false, pose);
            assertTrue(sameRotation(q, 0, pose.data(), 3, 1e-5), "q and -q are one rotation: the pose must not swing through the identity, t=" + t);
        }
        // and for genuinely different rotations the path never exceeds the shortest angle
        for (int trial = 0; trial < 300; trial++) {
            float[] a = randomQuat(), b = randomQuat();
            AnimationClip c = AnimationClip.builder(1).rotation(0, new float[] {0f, 1f}, new float[] {a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3]}).build();
            ClipSampler sm = new ClipSampler(c);
            double dot = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
            double total = 2 * Math.acos(Math.min(1.0, dot));
            for (float t : new float[] {0.25f, 0.5f, 0.75f}) {
                sm.sample(t, false, pose);
                double d = Math.abs(a[0] * pose.data()[3] + a[1] * pose.data()[4] + a[2] * pose.data()[5] + a[3] * pose.data()[6]);
                double angleFromA = 2 * Math.acos(Math.min(1.0, d));
                assertEquals(t * total, angleFromA, 2e-3, "constant-speed slerp along the short arc");
            }
        }
    }

    @Test
    void wrappingClampingAndRandomAccessAgreeWithSequentialPlayback() {
        AnimationClip clip = randomClip(5, 8, false);
        float d = clip.duration();
        ClipSampler sampler = new ClipSampler(clip);
        assertEquals(0f, sampler.wrap(Float.NaN, true));
        assertEquals(d, sampler.wrap(d * 5f, false));
        assertEquals(0f, sampler.wrap(-3f, false));
        assertEquals(sampler.wrap(0.3f, true), sampler.wrap(0.3f + 4 * d, true), 1e-3f * Math.max(1f, d));
        assertEquals(sampler.wrap(d - 0.1f, true), sampler.wrap(-0.1f, true), 1e-4f * Math.max(1f, d));
        assertTrue(sampler.wrap(-1e-9f, true) < d, "a tiny negative time must not wrap to exactly the duration");
        // a clip of zero duration: everything is time 0
        AnimationClip still = AnimationClip.builder(1).translation(0, new float[] {0f}, new float[] {1f, 2f, 3f}).build();
        assertEquals(0f, new ClipSampler(still).wrap(5f, true));
        Pose a = new Pose(5), b = new Pose(5);
        ClipSampler sequential = new ClipSampler(clip);
        ClipSampler random = new ClipSampler(clip);
        float[] order = new float[400];
        for (int i = 0; i < order.length; i++) {
            order[i] = i * d / 150f - 0.2f; // runs past the end and wraps
        }
        for (float t : order) {
            sequential.sample(t, true, a);
            new ClipSampler(clip).sample(t, true, b); // no history at all
            assertArrayEquals(b.data(), a.data(), "the cursor must never change the answer at t=" + t);
        }
        for (int i = 0; i < 300; i++) { // jumping around, with a reused sampler
            float t = (float) rnd.range(-d, 2 * d);
            random.sample(t, true, a);
            new ClipSampler(clip).sample(t, true, b);
            assertArrayEquals(b.data(), a.data(), "random access");
        }
        assertThrows(IllegalArgumentException.class, () -> sampler.sample(0f, true, new Pose(4)));
    }

    // ------------------------------------------------------------ the whole pipeline

    @Test
    void clipToJointMatricesToSkinningMatchesADoublePrecisionReference() {
        for (int trial = 0; trial < 25; trial++) {
            int n = 3 + (int) rnd.range(0, 20);
            Skeleton s = randomSkeleton(n);
            AnimationClip clip = randomClip(n, 5, true);
            ClipSampler sampler = new ClipSampler(clip);
            Pose pose = new Pose(s);
            Mat4fArray joints = new Mat4fArray(1);
            float[] scratch = new float[n * 16];
            for (float time : new float[] {0f, 0.37f, 0.9f, 1.7f}) {
                pose.setToBind(s);
                sampler.sample(time, true, pose);
                Skinning.jointMatrices(s, pose, scratch, joints);
                double[][] bindWorld = oracleWorld(s, new Pose(s));
                double[][] world = oracleWorld(s, pose);
                int vc = 30;
                float[] pos = new float[vc * 3], out = new float[vc * 3];
                int[] ji = new int[vc * 4];
                float[] w = new float[vc * 4];
                for (int v = 0; v < vc; v++) {
                    for (int k = 0; k < 3; k++) {
                        pos[v * 3 + k] = (float) rnd.range(-2, 2);
                    }
                    float sum = 0;
                    for (int k = 0; k < 4; k++) {
                        ji[v * 4 + k] = (int) rnd.range(0, n);
                        w[v * 4 + k] = (float) rnd.range(0, 1);
                        sum += w[v * 4 + k];
                    }
                    for (int k = 0; k < 4; k++) {
                        w[v * 4 + k] /= sum;
                    }
                }
                Skinning.skinPositions(joints.data(), pos, ji, w, vc, out);
                for (int v = 0; v < vc; v++) {
                    double[] expected = new double[3];
                    for (int k = 0; k < 4; k++) {
                        int j = ji[v * 4 + k];
                        double[] skin = mul(world[j], invertAffine(bindWorld[j]));
                        double[] p = apply(skin, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]);
                        for (int c = 0; c < 3; c++) {
                            expected[c] += w[v * 4 + k] * p[c];
                        }
                    }
                    for (int c = 0; c < 3; c++) {
                        assertEquals(expected[c], out[v * 3 + c], 2e-3 * Math.max(1.0, Math.abs(expected[c])),
                                "vertex " + v + " axis " + c + " at t=" + time + " (trial " + trial + ")");
                    }
                }
            }
        }
    }

    @Test
    void skinningScratchAndPoseSizesAreChecked() {
        Skeleton s = chain();
        Mat4fArray out = new Mat4fArray(1);
        assertThrows(IllegalArgumentException.class, () -> Skinning.jointMatrices(s, new Pose(2), new float[48], out));
        assertThrows(IllegalArgumentException.class, () -> Skinning.jointMatrices(s, new Pose(3), new float[10], out));
        Pose p = new Pose(s);
        assertThrows(IllegalArgumentException.class, () -> p.copyFrom(new Pose(2)));
        assertEquals(64, Skinning.JOINT_MATRIX_BYTES);
        Transformf t = Transformf.IDENTITY;
        assertEquals(Vec3f.ZERO, t.translation());
    }
}
