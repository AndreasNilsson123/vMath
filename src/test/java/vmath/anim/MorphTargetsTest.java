package vmath.anim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class MorphTargetsTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    /** Dense random deltas in which about {@code sparsity} of the vertices do not move. */
    private float[] deltas(int vertices, double sparsity, double scale) {
        float[] d = new float[3 * vertices];
        for (int v = 0; v < vertices; v++) {
            if (rng.nextDouble() < sparsity) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                d[3 * v + k] = (float) ((rng.nextDouble() * 2 - 1) * scale);
            }
        }
        return d;
    }

    private float[] randomArray(int n, double scale) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = (float) ((rng.nextDouble() * 2 - 1) * scale);
        }
        return a;
    }

    private float[] unitNormals(int vertices) {
        float[] n = new float[3 * vertices];
        for (int v = 0; v < vertices; v++) {
            double x = rng.nextGaussian(), y = rng.nextGaussian(), z = rng.nextGaussian(), l = Math.sqrt(x * x + y * y + z * z);
            n[3 * v] = (float) (x / l);
            n[3 * v + 1] = (float) (y / l);
            n[3 * v + 2] = (float) (z / l);
        }
        return n;
    }

    @Test
    void sparseStorageAppliesLikeTheDenseSum() {
        for (int trial = 0; trial < 50; trial++) {
            int vertices = 1 + rng.nextInt(200), targets = 1 + rng.nextInt(8);
            MorphTargets.Builder b = MorphTargets.builder(vertices);
            float[][] dense = new float[targets][];
            for (int t = 0; t < targets; t++) {
                dense[t] = deltas(vertices, 0.7, 2.0);
                b.target("t" + t, dense[t]);
            }
            MorphTargets m = b.build();
            assertEquals(vertices, m.vertexCount());
            assertEquals(targets, m.targetCount());
            assertFalse(m.hasNormals() || m.hasTangents());
            float[] base = randomArray(3 * vertices, 10), weights = randomArray(targets, 1.5);
            weights[rng.nextInt(targets)] = 0f;
            float[] out = new float[3 * vertices];
            m.apply(base, weights, out);
            for (int v = 0; v < vertices; v++) {
                for (int k = 0; k < 3; k++) {
                    double expected = base[3 * v + k];
                    for (int t = 0; t < targets; t++) {
                        expected += (double) weights[t] * dense[t][3 * v + k];
                    }
                    assertEquals(expected, out[3 * v + k], 2e-5 * (1 + Math.abs(expected)), "vertex " + v);
                }
            }
            // the entry counts are the vertices that move
            int total = 0;
            for (int t = 0; t < targets; t++) {
                int moving = 0;
                for (int v = 0; v < vertices; v++) {
                    if (dense[t][3 * v] != 0 || dense[t][3 * v + 1] != 0 || dense[t][3 * v + 2] != 0) {
                        moving++;
                    }
                }
                assertEquals(moving, m.entryCount(t));
                total += moving;
                assertEquals("t" + t, m.name(t));
            }
            assertEquals(total, m.entryCount());
            // in place gives the same
            float[] inPlace = base.clone();
            m.apply(inPlace, weights, inPlace);
            assertArrayEquals(out, inPlace, 0f);
        }
    }

    @Test
    void normalsAndTangentsMorphToo() {
        int vertices = 120, targets = 4;
        MorphTargets.Builder b = MorphTargets.builder(vertices);
        float[][] pos = new float[targets][], nor = new float[targets][], tan = new float[targets][];
        for (int t = 0; t < targets; t++) {
            pos[t] = deltas(vertices, 0.5, 1.0);
            nor[t] = deltas(vertices, 0.5, 0.6);
            tan[t] = deltas(vertices, 0.5, 0.3);
            b.target(null, pos[t], nor[t], tan[t]);
        }
        MorphTargets m = b.build();
        assertTrue(m.hasNormals() && m.hasTangents());
        assertEquals("", m.name(0));
        float[] baseN = unitNormals(vertices), baseT = unitNormals(vertices), w = {0.7f, -0.3f, 0f, 1.1f};
        float[] out = new float[3 * vertices];
        m.applyNormals(baseN, w, out, false);
        for (int v = 0; v < vertices; v++) {
            for (int k = 0; k < 3; k++) {
                double e = baseN[3 * v + k];
                for (int t = 0; t < targets; t++) {
                    e += w[t] * nor[t][3 * v + k];
                }
                assertEquals(e, out[3 * v + k], 2e-5);
            }
        }
        float[] unit = new float[3 * vertices];
        m.applyNormals(baseN, w, unit, true);
        for (int v = 0; v < vertices; v++) {
            boolean touched = false;
            for (int t = 0; t < targets; t++) {
                touched |= w[t] != 0 && (nor[t][3 * v] != 0 || nor[t][3 * v + 1] != 0 || nor[t][3 * v + 2] != 0 || pos[t][3 * v] != 0 || pos[t][3 * v + 1] != 0 || pos[t][3 * v + 2] != 0
                        || tan[t][3 * v] != 0 || tan[t][3 * v + 1] != 0 || tan[t][3 * v + 2] != 0);
            }
            double len = Math.sqrt(unit[3 * v] * unit[3 * v] + unit[3 * v + 1] * unit[3 * v + 1] + unit[3 * v + 2] * unit[3 * v + 2]);
            if (touched) {
                assertEquals(1.0, len, 1e-5, "a touched normal is unit length again");
            } else {
                assertEquals(baseN[3 * v], unit[3 * v], 0f);
            }
        }
        float[] tout = new float[3 * vertices];
        m.applyTangents(baseT, w, tout);
        for (int v = 0; v < vertices; v++) {
            double e = baseT[3 * v] + w[0] * tan[0][3 * v] + w[1] * tan[1][3 * v] + w[3] * tan[3][3 * v];
            assertEquals(e, tout[3 * v], 2e-5);
        }
        // a normal that a target cancels exactly keeps the base normal when renormalising
        float[] base = {0, 0, 1};
        MorphTargets cancel = MorphTargets.builder(1).target("c", new float[] {0, 0, 1}, new float[] {0, 0, -1}, new float[] {0, 0, 0}).build();
        float[] res = new float[3];
        cancel.applyNormals(base, new float[] {1f}, res, true);
        assertArrayEquals(base, res, 0f);
        // targets without normals reject normal calls
        MorphTargets plain = MorphTargets.builder(2).target("p", new float[6]).build();
        assertThrows(IllegalStateException.class, () -> plain.applyNormals(new float[6], new float[1], new float[6], true));
        assertThrows(IllegalStateException.class, () -> plain.applyTangents(new float[6], new float[1], new float[6]));
    }

    @Test
    void theToleranceDropsTinyDeltasWithABoundedChange() {
        int vertices = 300;
        float[] d = deltas(vertices, 0.2, 1.0);
        for (int v = 0; v < vertices; v++) {
            if (v % 3 == 0) {
                d[3 * v] = 1e-5f;
                d[3 * v + 1] = -1e-5f;
                d[3 * v + 2] = 0f;
            }
        }
        MorphTargets exact = MorphTargets.builder(vertices).target("e", d).build();
        MorphTargets loose = MorphTargets.builder(vertices).tolerance(1e-4f).target("e", d).build();
        assertTrue(loose.entryCount() < exact.entryCount());
        float[] base = new float[3 * vertices], a = new float[3 * vertices], b = new float[3 * vertices];
        float[] w = {0.9f};
        exact.apply(base, w, a);
        loose.apply(base, w, b);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], 1e-4 * 0.9 + 1e-7);
        }
        assertThrows(IllegalArgumentException.class, () -> MorphTargets.builder(3).tolerance(-1f));
    }

    @Test
    void activeTargetsMatchASortedReference() {
        for (int trial = 0; trial < 300; trial++) {
            int targets = 1 + rng.nextInt(20);
            MorphTargets.Builder b = MorphTargets.builder(2);
            for (int t = 0; t < targets; t++) {
                b.target("t", new float[] {1, 0, 0, 0, 0, 0});
            }
            MorphTargets m = b.build();
            float[] w = new float[targets];
            for (int t = 0; t < targets; t++) {
                w[t] = rng.nextInt(4) == 0 ? 0f : (float) (rng.nextInt(7) - 3) / 2f; // plenty of ties and zeros
            }
            float threshold = rng.nextInt(3) * 0.4f;
            int k = 1 + rng.nextInt(6);
            List<Integer> ref = new ArrayList<>();
            for (int t = 0; t < targets; t++) {
                if (Math.abs(w[t]) > threshold) {
                    ref.add(t);
                }
            }
            ref.sort(Comparator.<Integer>comparingDouble(t -> -Math.abs(w[t])).thenComparingInt(t -> t));
            int[] idx = new int[k];
            float[] ws = new float[k];
            int n = m.activeTargets(w, threshold, k, idx, ws);
            assertEquals(Math.min(k, ref.size()), n, "trial " + trial);
            for (int i = 0; i < n; i++) {
                assertEquals((int) ref.get(i), idx[i], "trial " + trial + " rank " + i);
                assertEquals(w[ref.get(i)], ws[i]);
            }
        }
        MorphTargets m = MorphTargets.builder(1).target("a", new float[3]).build();
        assertThrows(IllegalArgumentException.class, () -> m.activeTargets(new float[1], 0f, 0, new int[1], new float[1]));
        assertThrows(IllegalArgumentException.class, () -> m.activeTargets(new float[1], 0f, 2, new int[1], new float[2]));
        assertThrows(IllegalArgumentException.class, () -> m.activeTargets(new float[0], 0f, 1, new int[1], new float[1]));
    }

    @Test
    void theBoundsExpansionBoundsEveryDisplacement() {
        for (int trial = 0; trial < 100; trial++) {
            int vertices = 50, targets = 5;
            MorphTargets.Builder b = MorphTargets.builder(vertices);
            for (int t = 0; t < targets; t++) {
                b.target("t", deltas(vertices, 0.4, 1.0 + t));
            }
            MorphTargets m = b.build();
            float[] w = randomArray(targets, 1.2);
            float expansion = m.boundsExpansion(w);
            float[] base = new float[3 * vertices], out = new float[3 * vertices];
            m.apply(base, w, out);
            for (int v = 0; v < vertices; v++) {
                double d = Math.sqrt(out[3 * v] * out[3 * v] + out[3 * v + 1] * out[3 * v + 1] + out[3 * v + 2] * out[3 * v + 2]);
                assertTrue(d <= expansion * (1 + 1e-5) + 1e-6, "vertex " + v + " moved " + d + " but the bound is " + expansion);
            }
        }
        // one target: the bound is reached by the vertex that moves the most
        float[] d = {3, 4, 0, 0, 0, 0, 1, 1, 1};
        MorphTargets m = MorphTargets.builder(3).target("t", d).build();
        assertEquals(5f, m.maxDisplacement(0), 1e-6f);
        assertEquals(2.5f, m.boundsExpansion(new float[] {-0.5f}), 1e-6f);
    }

    @Test
    void thePackedFormsHoldTheSameData() {
        int vertices = 40, targets = 3;
        MorphTargets.Builder b = MorphTargets.builder(vertices);
        float[][] dense = new float[targets][];
        for (int t = 0; t < targets; t++) {
            dense[t] = deltas(vertices, t == 1 ? 1.0 : 0.6, 3.0); // target 1 does not move anything
            b.target("t" + t, dense[t]);
        }
        MorphTargets m = b.build();
        assertEquals(0, m.entryCount(1));
        for (int stride = 3; stride <= 4; stride++) {
            float[] out = new float[targets * vertices * stride];
            java.util.Arrays.fill(out, 99f);
            m.packDense(out, stride);
            for (int t = 0; t < targets; t++) {
                for (int v = 0; v < vertices; v++) {
                    for (int k = 0; k < 3; k++) {
                        assertEquals(dense[t][3 * v + k], out[(t * vertices + v) * stride + k], 0f);
                    }
                    if (stride == 4) {
                        assertEquals(0f, out[(t * vertices + v) * 4 + 3], 0f);
                    }
                }
            }
            int[] offsets = new int[targets + 1], ids = new int[m.entryCount()];
            float[] sparse = new float[m.entryCount() * stride];
            m.packSparse(offsets, ids, sparse, stride);
            for (int t = 0; t < targets; t++) {
                assertEquals(m.targetOffset(t), offsets[t]);
                for (int e = offsets[t]; e < offsets[t + 1]; e++) {
                    assertEquals(m.entryVertex(e), ids[e]);
                    for (int k = 0; k < 3; k++) {
                        assertEquals(dense[t][3 * ids[e] + k], sparse[e * stride + k], 0f);
                    }
                    if (stride == 4) {
                        assertEquals(0f, sparse[e * 4 + 3], 0f);
                    }
                }
                for (int e = offsets[t] + 1; e < offsets[t + 1]; e++) {
                    assertTrue(ids[e] > ids[e - 1], "the vertices of a target ascend");
                }
            }
            assertEquals(m.entryCount(), offsets[targets]);
        }
        float[] scales = new float[targets];
        short[] q = new short[3 * m.entryCount()];
        float[] back = new float[3 * m.entryCount()];
        m.packSparseSnorm16(scales, q);
        m.unpackSparseSnorm16(scales, q, back);
        for (int t = 0; t < targets; t++) {
            float max = 0;
            for (float v : dense[t]) {
                max = Math.max(max, Math.abs(v));
            }
            assertEquals(max, scales[t], 0f);
            for (int e = m.targetOffset(t); e < m.targetOffset(t + 1); e++) {
                for (int k = 0; k < 3; k++) {
                    float exact = dense[t][3 * m.entryVertex(e) + k];
                    assertEquals(exact, back[3 * e + k], scales[t] / 65534f * 1.0001f + 1e-7f, "the error of a quantised component is at most half a step");
                }
            }
        }
        assertEquals(0f, scales[1], 0f);
        assertThrows(IllegalArgumentException.class, () -> m.packDense(new float[targets * vertices * 3], 5));
        assertThrows(IllegalArgumentException.class, () -> m.packDense(new float[10], 3));
        assertThrows(IllegalArgumentException.class, () -> m.packSparse(new int[1], new int[1], new float[1], 3));
        assertThrows(IllegalArgumentException.class, () -> m.packSparse(new int[targets + 1], new int[m.entryCount()], new float[3 * m.entryCount()], 2));
        assertThrows(IllegalArgumentException.class, () -> m.packSparseSnorm16(new float[1], new short[1]));
        assertThrows(IllegalArgumentException.class, () -> m.unpackSparseSnorm16(new float[1], new short[1], new float[1]));
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MorphTargets.builder(0));
        MorphTargets.Builder b = MorphTargets.builder(2);
        assertThrows(IllegalStateException.class, b::build);
        assertThrows(IllegalArgumentException.class, () -> b.target("x", new float[5]));
        assertThrows(IllegalArgumentException.class, () -> b.target("x", new float[6], new float[3], null));
        assertThrows(IllegalArgumentException.class, () -> b.target("x", new float[] {1, 2, Float.NaN, 0, 0, 0}));
        b.target("ok", new float[6]);
        assertThrows(IllegalArgumentException.class, () -> b.target("normals later", new float[6], new float[6], null));
        MorphTargets m = b.build();
        assertEquals(0, m.entryCount(), "a target that does not move anything has no entries");
        assertThrows(IllegalArgumentException.class, () -> m.apply(new float[3], new float[1], new float[6]));
        assertThrows(IllegalArgumentException.class, () -> m.apply(new float[6], new float[0], new float[6]));
        assertThrows(IllegalArgumentException.class, () -> m.apply(new float[6], new float[1], new float[3]));
        assertThrows(IllegalArgumentException.class, () -> m.boundsExpansion(new float[0]));
    }
}
