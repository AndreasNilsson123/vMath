package vmath.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.core.Mat4d;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Transformf;
import vmath.core.Vec3f;

class TransformHierarchyTest {

    final Rnd rnd = Rnd.create();

    /** A random local transform with a rotation, a non-uniform scale and a translation. */
    private Transformf randomLocal() {
        double x = rnd.range(-1, 1), y = rnd.range(-1, 1), z = rnd.range(-1, 1), w = rnd.range(-1, 1);
        double len = Math.sqrt(x * x + y * y + z * z + w * w);
        if (len < 0.1) {
            x = 0;
            y = 0;
            z = 0;
            w = 1;
            len = 1;
        }
        return new Transformf(new Vec3f((float) rnd.range(-3, 3), (float) rnd.range(-3, 3), (float) rnd.range(-3, 3)),
                new Quatf((float) (x / len), (float) (y / len), (float) (z / len), (float) (w / len)),
                new Vec3f((float) rnd.range(0.7, 1.3), (float) rnd.range(0.7, 1.3), (float) rnd.range(0.7, 1.3)));
    }

    /** The hierarchy's parents and locals, mirrored so that a double-precision oracle can be computed from the stored values. */
    private static double[] oracleWorld(TransformHierarchy h, int node, double[][] cache) {
        if (cache[node] != null) {
            return cache[node];
        }
        Mat4d local = h.local(node).toDouble().toMat4();
        double[] l = flatten(local);
        int p = h.parent(node);
        double[] r = p < 0 ? l : multiply(oracleWorld(h, p, cache), l);
        cache[node] = r;
        return r;
    }

    private static double[] flatten(Mat4d m) {
        return new double[] {m.m00(), m.m01(), m.m02(), m.m03(), m.m10(), m.m11(), m.m12(), m.m13(),
                m.m20(), m.m21(), m.m22(), m.m23(), m.m30(), m.m31(), m.m32(), m.m33()};
    }

    /** a * b for matrices stored column-major (index col * 4 + row). */
    private static double[] multiply(double[] a, double[] b) {
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

    private void assertWorldMatchesOracle(TransformHierarchy h, String when) {
        double[][] cache = new double[h.size()][];
        float[] w = h.worldMatrices().data();
        for (int i = 0; i < h.size(); i++) {
            double[] expected = oracleWorld(h, i, cache);
            for (int k = 0; k < 16; k++) {
                double tol = 2e-4 * Math.max(1.0, Math.abs(expected[k]));
                assertEquals(expected[k], w[i * 16 + k], tol, when + ": node " + i + " element " + k);
            }
        }
    }

    private TransformHierarchy randomTree(int n, int maxDepthWindow) {
        TransformHierarchy h = new TransformHierarchy(2);
        for (int i = 0; i < n; i++) {
            int parent = i == 0 || rnd.range(0, 1) < 0.05 ? -1 : Math.max(0, i - 1 - (int) rnd.range(0, maxDepthWindow));
            int node = h.add(parent);
            h.setLocal(node, randomLocal());
        }
        return h;
    }

    // ------------------------------------------------------------ correctness

    @Test
    void worldMatricesMatchTheExactMatrixProductOnRandomTrees() {
        for (int trial = 0; trial < 40; trial++) {
            TransformHierarchy h = randomTree(30 + (int) rnd.range(0, 200), 1 + (int) rnd.range(0, 3));
            assertEquals(h.size(), h.update(), "the first update computes every node");
            assertWorldMatchesOracle(h, "trial " + trial);
        }
    }

    @Test
    void deepChainsStayAccurate() {
        TransformHierarchy h = new TransformHierarchy();
        int prev = -1;
        for (int i = 0; i < 40; i++) {
            prev = h.add(prev);
            h.setLocal(prev, randomLocal());
        }
        h.update();
        assertWorldMatchesOracle(h, "chain of 40");
    }

    @Test
    void updatesRecomputeExactlyTheDirtySubtrees() {
        for (int trial = 0; trial < 40; trial++) {
            TransformHierarchy h = randomTree(200, 3);
            h.update();
            float[] before = h.worldMatrices().data().clone();
            int edits = 1 + (int) rnd.range(0, 8);
            boolean[] edited = new boolean[h.size()];
            for (int e = 0; e < edits; e++) {
                int node = (int) rnd.range(0, h.size());
                h.setLocal(node, randomLocal());
                edited[node] = true;
            }
            // expected: every edited node and everything below one
            boolean[] affected = new boolean[h.size()];
            int expected = 0;
            for (int i = 0; i < h.size(); i++) {
                affected[i] = edited[i] || (h.parent(i) >= 0 && affected[h.parent(i)]);
                if (affected[i]) {
                    expected++;
                }
            }
            for (int i = 0; i < h.size(); i++) {
                assertEquals(affected[i], h.isDirty(i), "isDirty of node " + i);
            }
            assertEquals(expected, h.update(), "exactly the dirty subtrees are recomputed (trial " + trial + ")");
            assertEquals(expected, h.lastUpdateCount());
            float[] after = h.worldMatrices().data();
            for (int i = 0; i < h.size(); i++) {
                if (!affected[i]) {
                    for (int k = 0; k < 16; k++) {
                        assertEquals(Float.floatToIntBits(before[i * 16 + k]), Float.floatToIntBits(after[i * 16 + k]),
                                "an untouched node must keep bit-identical data: node " + i);
                    }
                }
            }
            assertWorldMatchesOracle(h, "after edits, trial " + trial);
            assertEquals(0, h.update(), "nothing dirty: nothing to do");
        }
    }

    @Test
    void addingNodesAfterAnUpdateComputesJustTheNewOnes() {
        TransformHierarchy h = randomTree(50, 2);
        h.update();
        int a = h.add(10);
        h.setLocal(a, randomLocal());
        int b = h.add(a);
        h.setLocal(b, randomLocal());
        assertEquals(2, h.update());
        assertWorldMatchesOracle(h, "after adding");
    }

    @Test
    void individualSettersComposeAsTranslateRotateScale() {
        TransformHierarchy h = new TransformHierarchy();
        int root = h.add(-1);
        int child = h.add(root);
        h.setTranslation(root, 1f, 2f, 3f);
        h.setRotation(root, 0f, 0f, 2f, 2f); // not unit length: 90 degrees about Z after normalising
        h.setScale(root, 2f, 2f, 2f);
        h.setTranslation(child, 1f, 0f, 0f);
        h.update();
        Vec3f p = h.worldMatrix(child).transformPosition(Vec3f.ZERO);
        // child origin: (1,0,0) scaled by 2 -> (2,0,0), rotated 90 degrees about Z -> (0,2,0), plus (1,2,3)
        assertEquals(1f, p.x(), 1e-5f);
        assertEquals(4f, p.y(), 1e-5f);
        assertEquals(3f, p.z(), 1e-5f);
        h.setRotation(root, 0f, 0f, 0f, 0f); // a zero quaternion falls back to the identity
        h.update();
        Vec3f q = h.worldMatrix(child).transformPosition(Vec3f.ZERO);
        assertEquals(3f, q.x(), 1e-5f);
        assertEquals(2f, q.y(), 1e-5f);
    }

    // ------------------------------------------------------------ structure edits

    @Test
    void removingASubtreeKeepsTheSurvivorsAndTheOrderInvariant() {
        for (int trial = 0; trial < 60; trial++) {
            TransformHierarchy h = randomTree(60 + (int) rnd.range(0, 80), 1 + (int) rnd.range(0, 3));
            h.update();
            int oldSize = h.size();
            int victim = (int) rnd.range(0, oldSize);
            // model: which old nodes go
            boolean[] gone = new boolean[oldSize];
            List<Transformf> locals = new ArrayList<>();
            int[] oldParent = new int[oldSize];
            for (int i = 0; i < oldSize; i++) {
                oldParent[i] = h.parent(i);
                locals.add(h.local(i));
            }
            gone[victim] = true;
            for (int i = victim + 1; i < oldSize; i++) {
                gone[i] = oldParent[i] >= 0 && gone[oldParent[i]];
            }
            int[] remap = h.remove(victim);
            assertEquals(oldSize, remap.length);
            int survivors = 0;
            for (int i = 0; i < oldSize; i++) {
                if (gone[i]) {
                    assertEquals(-1, remap[i]);
                } else {
                    assertEquals(survivors++, remap[i], "survivors keep their relative order");
                }
            }
            assertEquals(survivors, h.size());
            for (int i = 0; i < h.size(); i++) {
                assertTrue(h.parent(i) < i, "parent index below child index at " + i);
            }
            for (int i = 0; i < oldSize; i++) {
                if (!gone[i]) {
                    int expectedParent = oldParent[i] < 0 ? -1 : remap[oldParent[i]];
                    assertEquals(expectedParent, h.parent(remap[i]));
                    assertEquals(locals.get(i), h.local(remap[i]), "local transform moves with its node");
                }
            }
            assertWorldMatchesOracle(h, "after removal, without an update (trial " + trial + ")");
        }
    }

    @Test
    void dirtyFlagsSurviveCompaction() {
        TransformHierarchy h = new TransformHierarchy();
        int a = h.add(-1), b = h.add(a), c = h.add(-1), d = h.add(c);
        h.update();
        h.setTranslation(d, 5f, 0f, 0f);
        int[] remap = h.remove(b);
        assertEquals(-1, remap[b]);
        assertTrue(h.isDirty(remap[d]));
        assertFalse(h.isDirty(remap[a]));
        assertEquals(1, h.update());
        assertEquals(5f, h.worldMatrix(remap[d]).transformPosition(Vec3f.ZERO).x(), 1e-6f);
    }

    @Test
    void clearAndArgumentChecks() {
        TransformHierarchy h = new TransformHierarchy(2);
        assertThrows(IllegalArgumentException.class, () -> h.add(0));
        assertThrows(IllegalArgumentException.class, () -> h.add(-2));
        int a = h.add(-1);
        assertThrows(IndexOutOfBoundsException.class, () -> h.setTranslation(3, 0f, 0f, 0f));
        assertThrows(IndexOutOfBoundsException.class, () -> h.parent(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> h.remove(1));
        assertEquals(a, 0);
        for (int i = 0; i < 300; i++) { // growth from a tiny capacity
            h.add(i);
        }
        assertEquals(301, h.size());
        h.update();
        assertEquals(301, h.worldMatrices().size());
        h.clear();
        assertEquals(0, h.size());
        assertEquals(0, h.update());
        int again = h.add(-1);
        assertEquals(0, again);
        assertEquals(1, h.update());
        float[] w = h.worldMatrices().data();
        assertEquals(1f, w[0]);
        assertEquals(1f, w[15]);
        assertEquals(0f, Arrays.copyOf(w, 16)[12]);
    }
}
