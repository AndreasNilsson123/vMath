package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class MeshOptimizerTest {

    final Rnd rnd = Rnd.create();

    /** An n x n grid of quads as triangles (2 n^2 triangles), positions in the XZ plane. */
    private static Mesh grid(int n) {
        Mesh m = new Mesh((n + 1) * (n + 1), 2 * n * n);
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                m.addVertex(i, 0f, j);
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = j * (n + 1) + i;
                m.addTriangle(a, a + 1, a + n + 2);
                m.addTriangle(a, a + n + 2, a + n + 1);
            }
        }
        return m;
    }

    private void shuffleTriangles(Mesh m) {
        int[] idx = m.indices();
        int t = m.triangleCount();
        for (int i = t - 1; i > 0; i--) {
            int j = (int) rnd.range(0, i + 1);
            for (int k = 0; k < 3; k++) {
                int tmp = idx[i * 3 + k];
                idx[i * 3 + k] = idx[j * 3 + k];
                idx[j * 3 + k] = tmp;
            }
        }
    }

    /** The triangles as an unordered collection of (a, b, c) tuples, in a form that can be compared. */
    private static List<String> triangleTuples(Mesh m) {
        List<String> out = new ArrayList<>();
        int[] idx = m.indices();
        for (int t = 0; t < m.indexCount(); t += 3) {
            out.add(idx[t] + "," + idx[t + 1] + "," + idx[t + 2]);
        }
        out.sort(null);
        return out;
    }

    /** The triangles as position triples with orientation kept (rotated so that the smallest corner comes first). */
    private static List<String> positionTriangles(Mesh m) {
        List<String> out = new ArrayList<>();
        float[] p = m.positions();
        int[] idx = m.indices();
        for (int t = 0; t < m.indexCount(); t += 3) {
            String[] c = new String[3];
            for (int k = 0; k < 3; k++) {
                int v = idx[t + k];
                // "+ 0f" turns -0.0 into 0.0: the same point must give the same text
                c[k] = (p[v * 3] + 0f) + "," + (p[v * 3 + 1] + 0f) + "," + (p[v * 3 + 2] + 0f);
            }
            int first = 0;
            for (int k = 1; k < 3; k++) {
                if (c[k].compareTo(c[first]) < 0) {
                    first = k;
                }
            }
            out.add(c[first] + "|" + c[(first + 1) % 3] + "|" + c[(first + 2) % 3]);
        }
        out.sort(null);
        return out;
    }

    /** Turns an indexed mesh into a triangle soup (three private vertices per triangle). */
    private static Mesh soupOf(Mesh m) {
        Mesh s = new Mesh();
        float[] p = m.positions();
        int[] idx = m.indices();
        for (int t = 0; t < m.indexCount(); t += 3) {
            int a = s.addVertex(p[idx[t] * 3], p[idx[t] * 3 + 1], p[idx[t] * 3 + 2]);
            int b = s.addVertex(p[idx[t + 1] * 3], p[idx[t + 1] * 3 + 1], p[idx[t + 1] * 3 + 2]);
            int c = s.addVertex(p[idx[t + 2] * 3], p[idx[t + 2] * 3 + 1], p[idx[t + 2] * 3 + 2]);
            s.addTriangle(a, b, c);
        }
        return s;
    }

    // ------------------------------------------------------------ weld

    @Test
    void weldingATriangleSoupRebuildsTheSharedMesh() {
        Mesh cube = MeshTest.sharedCube();
        Mesh soup = soupOf(cube);
        assertEquals(36, soup.vertexCount());
        List<String> before = positionTriangles(soup);
        int[] remap = MeshOptimizer.weld(soup, 1e-6f, false);
        assertEquals(36, remap.length);
        assertEquals(8, soup.vertexCount());
        assertEquals(12, soup.triangleCount());
        assertEquals(before, positionTriangles(soup), "welding must not change the surface");
        assertEquals(1.0, Math.abs(soup.signedVolume()), 1e-9);
    }

    @Test
    void attributesKeepSeamsUnlessWeldingByPositionOnly() {
        Mesh box = Primitives.box(1f, 1f, 1f);
        List<String> before = positionTriangles(box);
        Mesh keep = Primitives.box(1f, 1f, 1f);
        MeshOptimizer.weld(keep, 1e-5f, false);
        assertEquals(24, keep.vertexCount(), "corners differ in normal and uv, so they stay separate");
        int[] remap = MeshOptimizer.weld(box, 1e-5f, true);
        assertEquals(8, box.vertexCount(), "position-only welding merges all three copies of each corner");
        assertEquals(24, remap.length);
        assertEquals(12, box.triangleCount());
        assertEquals(before, positionTriangles(box));
        assertTrue(box.hasNormals() && box.hasUvs(0) && box.hasTangents(), "streams survive and follow the vertices");
    }

    @Test
    void weldMergesJitteredDuplicatesAndOnlyThose() {
        for (int trial = 0; trial < 100; trial++) {
            float eps = (float) rnd.range(0.001, 0.05);
            int sites = 5 + (int) rnd.range(0, 60);
            Mesh m = new Mesh();
            int[] siteOf = new int[0];
            List<Integer> owners = new ArrayList<>();
            for (int s = 0; s < sites; s++) {
                // sites sit on a coarse lattice, at least 20 eps apart, with random offsets inside their own cell
                float bx = (s % 7) * 20f * eps, by = ((s / 7) % 7) * 20f * eps, bz = (s / 49) * 20f * eps;
                int copies = 1 + (int) rnd.range(0, 3);
                for (int c = 0; c < copies; c++) {
                    m.addVertex(bx + (float) rnd.range(-1, 1) * eps * 0.2f, by + (float) rnd.range(-1, 1) * eps * 0.2f,
                            bz + (float) rnd.range(-1, 1) * eps * 0.2f);
                    owners.add(s);
                }
            }
            int n = m.vertexCount();
            for (int t = 0; t < n; t++) {
                m.addTriangle((int) rnd.range(0, n), (int) rnd.range(0, n), (int) rnd.range(0, n));
            }
            float[] original = m.positions().clone();
            int oldTriangles = m.triangleCount();
            int[] oldIdx = Arrays.copyOf(m.indices(), m.indexCount());
            int[] remap = MeshOptimizer.weld(m, eps, false);
            assertEquals(sites, m.vertexCount(), "one vertex per site (trial " + trial + ")");
            int survivors = 0;
            for (int t = 0; t < oldTriangles; t++) {
                int a = remap[oldIdx[t * 3]], b = remap[oldIdx[t * 3 + 1]], c = remap[oldIdx[t * 3 + 2]];
                if (a != b && b != c && a != c) {
                    survivors++;
                }
            }
            assertEquals(survivors, m.triangleCount(), "collapsed triangles are dropped, all others kept");
            for (int v = 0; v < n; v++) {
                assertEquals(owners.get(v), owners.get(firstOldWith(remap, remap[v])), "vertices merge only within their site");
                for (int k = 0; k < 3; k++) {
                    assertEquals(original[v * 3 + k], m.positions()[remap[v] * 3 + k], eps, "the kept vertex is within eps of the merged one");
                }
            }
            assertTrue(siteOf.length == 0);
        }
    }

    private static int firstOldWith(int[] remap, int target) {
        for (int v = 0; v < remap.length; v++) {
            if (remap[v] == target) {
                return v;
            }
        }
        throw new IllegalStateException();
    }

    @Test
    void weldRejectsBadEps() {
        assertThrows(IllegalArgumentException.class, () -> MeshOptimizer.weld(new Mesh(), -1f, false));
        assertThrows(IllegalArgumentException.class, () -> MeshOptimizer.weld(new Mesh(), Float.NaN, false));
        Mesh empty = new Mesh();
        assertEquals(0, MeshOptimizer.weld(empty, 0.1f, false).length);
    }

    // ------------------------------------------------------------ vertex cache

    @Test
    void cacheOptimisationKeepsTheTrianglesAndCutsTheMissRatio() {
        Mesh m = grid(40);
        shuffleTriangles(m);
        float before = MeshOptimizer.acmr(m, 32);
        List<String> tuples = triangleTuples(m);
        MeshOptimizer.optimizeVertexCache(m, 32);
        float after = MeshOptimizer.acmr(m, 32);
        assertEquals(tuples, triangleTuples(m), "the same triangles, each with its own vertex order, only reordered");
        assertTrue(before > 1.8f, "a shuffled grid should be bad, was " + before);
        assertTrue(after < 0.9f, "the optimised grid should approach 0.5, was " + after);
        assertTrue(after < before * 0.5f, "at least halved: " + before + " -> " + after);
    }

    @Test
    void cacheOptimisationHelpsCurvedMeshesToo() {
        for (Mesh m : new Mesh[] {Primitives.uvSphere(1f, 64, 32), Primitives.torus(2f, 0.5f, 48, 24), Primitives.icoSphere(1f, 4)}) {
            shuffleTriangles(m);
            float before = MeshOptimizer.acmr(m, 32);
            List<String> tuples = triangleTuples(m);
            MeshOptimizer.optimizeVertexCache(m, 32);
            float after = MeshOptimizer.acmr(m, 32);
            assertEquals(tuples, triangleTuples(m));
            assertTrue(after < before * 0.5f, "shuffled " + before + " -> optimised " + after);
            assertTrue(after < 1.0f, "optimised ratio " + after);
        }
    }

    @Test
    void cacheOptimisationHandlesEdgeCases() {
        Mesh empty = new Mesh();
        MeshOptimizer.optimizeVertexCache(empty, 32);
        Mesh one = new Mesh();
        one.addVertex(0f, 0f, 0f);
        one.addVertex(1f, 0f, 0f);
        one.addVertex(0f, 1f, 0f);
        one.addTriangle(0, 1, 2);
        MeshOptimizer.optimizeVertexCache(one, 32);
        assertArrayEquals(new int[] {0, 1, 2}, Arrays.copyOf(one.indices(), 3));
        assertThrows(IllegalArgumentException.class, () -> MeshOptimizer.optimizeVertexCache(new int[] {0, 1, 2, 3}, 4, 4, 32));
        Mesh g = grid(6);
        shuffleTriangles(g);
        List<String> tuples = triangleTuples(g);
        MeshOptimizer.optimizeVertexCache(g, 1); // absurdly small: clamped, must still be a valid reordering
        assertEquals(tuples, triangleTuples(g));
        Mesh degenerate = new Mesh();
        for (int i = 0; i < 4; i++) {
            degenerate.addVertex(i, 0f, 0f);
        }
        degenerate.addTriangle(0, 0, 1);
        degenerate.addTriangle(1, 2, 2);
        degenerate.addTriangle(3, 3, 3);
        degenerate.addTriangle(0, 1, 2);
        List<String> dt = triangleTuples(degenerate);
        MeshOptimizer.optimizeVertexCache(degenerate, 32);
        assertEquals(dt, triangleTuples(degenerate), "repeated corner indices are tolerated");
    }

    @Test
    void acmrOfKnownOrders() {
        assertEquals(0f, MeshOptimizer.acmr(new int[0], 0, 32));
        assertEquals(3f, MeshOptimizer.acmr(new int[] {0, 1, 2, 3, 4, 5}, 6, 32), 1e-6f, "no vertex is ever reused");
        assertEquals(1.5f, MeshOptimizer.acmr(new int[] {0, 1, 2, 0, 2, 1}, 6, 32), 1e-6f, "second triangle is all hits");
    }

    // ------------------------------------------------------------ vertex fetch

    @Test
    void fetchOptimisationIsAPermutationThatKeepsTheSurface() {
        Mesh m = Primitives.uvSphere(1f, 32, 16);
        shuffleTriangles(m);
        MeshOptimizer.optimizeVertexCache(m, 32);
        List<String> surface = positionTriangles(m);
        float[] oldUv = m.uvs(0).clone();
        float[] oldNormals = m.normals().clone();
        int n = m.vertexCount();
        int[] remap = MeshOptimizer.optimizeVertexFetch(m);
        assertEquals(n, m.vertexCount());
        boolean[] seen = new boolean[n];
        for (int r : remap) {
            assertTrue(r >= 0 && r < n && !seen[r], "the remap must be a permutation");
            seen[r] = true;
        }
        assertEquals(surface, positionTriangles(m), "same triangles in the same order");
        for (int v = 0; v < n; v++) { // every stream followed its vertex
            assertEquals(oldUv[v * 2], m.uvs(0)[remap[v] * 2]);
            assertEquals(oldUv[v * 2 + 1], m.uvs(0)[remap[v] * 2 + 1]);
            assertEquals(oldNormals[v * 3 + 2], m.normals()[remap[v] * 3 + 2]);
        }
        // by construction, each vertex index appears for the first time exactly when it equals the count of distinct ones so far
        int next = 0;
        int[] idx = m.indices();
        for (int i = 0; i < m.indexCount(); i++) {
            assertTrue(idx[i] <= next, "indices only ever reach one past the highest seen so far");
            if (idx[i] == next) {
                next++;
            }
        }
    }

    @Test
    void unusedVerticesMoveToTheEnd() {
        Mesh m = new Mesh();
        for (int i = 0; i < 6; i++) {
            m.addVertex(i, 0f, 0f);
        }
        m.addTriangle(5, 3, 1);
        int[] remap = MeshOptimizer.optimizeVertexFetch(m);
        assertEquals(0, remap[5]);
        assertEquals(1, remap[3]);
        assertEquals(2, remap[1]);
        assertEquals(3, remap[0]);
        assertEquals(4, remap[2]);
        assertEquals(5, remap[4]);
        assertEquals(5f, m.positions()[0]);
        assertEquals(0f, m.positions()[3 * 3], "unused vertices keep their relative order at the end: old 0, 2, 4");
        assertEquals(2f, m.positions()[4 * 3]);
        assertEquals(4f, m.positions()[5 * 3]);
        Set<Integer> ids = new HashSet<>();
        for (int r : remap) {
            ids.add(r);
        }
        assertEquals(6, ids.size());
    }

    @Test
    void theWholePipelineOnARandomMeshKeepsTheSurface() {
        for (int trial = 0; trial < 30; trial++) {
            Mesh base = Primitives.uvSphere(1f, 8 + (int) rnd.range(0, 24), 4 + (int) rnd.range(0, 12));
            Mesh soup = soupOf(base);
            List<String> surface = positionTriangles(soup);
            MeshOptimizer.weld(soup, 1e-6f, false);
            MeshOptimizer.optimizeVertexCache(soup, 16 + (int) rnd.range(0, 17));
            MeshOptimizer.optimizeVertexFetch(soup);
            assertEquals(surface, positionTriangles(soup), "weld, cache and fetch together must not change the surface (trial " + trial + ")");
            assertTrue(soup.vertexCount() <= base.vertexCount());
        }
    }
}
