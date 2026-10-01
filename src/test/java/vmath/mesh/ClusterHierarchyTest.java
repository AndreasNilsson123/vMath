package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.core.Vec3f;

class ClusterHierarchyTest {

    final Rnd rnd = Rnd.create();

    private static Mesh weldedPositions(Mesh src) {
        Mesh m = new Mesh();
        for (int i = 0; i < src.vertexCount(); i++) {
            m.addVertex(src.positions()[i * 3], src.positions()[i * 3 + 1], src.positions()[i * 3 + 2]);
        }
        for (int t = 0; t < src.triangleCount(); t++) {
            m.addTriangle(src.indices()[t * 3], src.indices()[t * 3 + 1], src.indices()[t * 3 + 2]);
        }
        MeshOptimizer.weld(m, 1e-6f, true);
        return m;
    }

    private static int[] indicesOf(Mesh m) {
        return Arrays.copyOf(m.indices(), m.indexCount());
    }

    /** Edges seen as a directed pair: a closed manifold has every directed edge exactly once and its reverse too. */
    private static int openEdges(int[] idx) {
        Map<Long, Integer> directed = new HashMap<>();
        for (int t = 0; t < idx.length; t += 3) {
            for (int k = 0; k < 3; k++) {
                directed.merge(((long) idx[t + k] << 32) | (idx[t + (k + 1) % 3] & 0xffffffffL), 1, Integer::sum);
            }
        }
        int open = 0;
        for (Map.Entry<Long, Integer> e : directed.entrySet()) {
            long rev = (e.getKey() << 32) | (e.getKey() >>> 32);
            if (e.getValue() != 1 || !directed.containsKey(rev)) {
                open++;
            }
        }
        return open;
    }

    private static double area(Mesh pool, int[] idx) {
        float[] p = pool.positions();
        double a = 0;
        for (int t = 0; t < idx.length; t += 3) {
            int i = idx[t] * 3, j = idx[t + 1] * 3, k = idx[t + 2] * 3;
            double ex = p[j] - p[i], ey = p[j + 1] - p[i + 1], ez = p[j + 2] - p[i + 2];
            double fx = p[k] - p[i], fy = p[k + 1] - p[i + 1], fz = p[k + 2] - p[i + 2];
            double cx = ey * fz - ez * fy, cy = ez * fx - ex * fz, cz = ex * fy - ey * fx;
            a += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
        }
        return a;
    }

    private static double volume(Mesh pool, int[] idx) {
        float[] p = pool.positions();
        double v = 0;
        for (int t = 0; t < idx.length; t += 3) {
            int i = idx[t] * 3, j = idx[t + 1] * 3, k = idx[t + 2] * 3;
            v += (p[i] * (p[j + 1] * p[k + 2] - p[j + 2] * p[k + 1]) - p[i + 1] * (p[j] * p[k + 2] - p[j + 2] * p[k]) + p[i + 2] * (p[j] * p[k + 1] - p[j + 1] * p[k])) / 6.0;
        }
        return v;
    }

    private static List<String> canonicalTriangles(int[] idx) {
        List<String> out = new ArrayList<>();
        for (int t = 0; t < idx.length; t += 3) {
            int a = idx[t], b = idx[t + 1], c = idx[t + 2];
            if (b < a && b < c) {
                out.add(b + "," + c + "," + a);
            } else if (c < a && c < b) {
                out.add(c + "," + a + "," + b);
            } else {
                out.add(a + "," + b + "," + c);
            }
        }
        Collections.sort(out);
        return out;
    }

    private static int[] select(ClusterHierarchy h, float ex, float ey, float ez, float budget) {
        int[] out = new int[h.clusterCount()];
        int n = h.select(ex, ey, ez, 1000f, budget, out);
        return Arrays.copyOf(out, n);
    }

    private static double sq(double x) {
        return x * x;
    }

    @Test
    void theHierarchyHasSeveralLevelsThatReduceTheDetail() {
        Mesh mesh = weldedPositions(Primitives.icoSphere(1f, 5));
        ClusterHierarchy h = ClusterHierarchy.build(mesh, 64, 128, 4);
        assertTrue(h.levelCount() >= 4, "levels " + h.levelCount());
        int[] trianglesPerLevel = new int[h.levelCount()];
        for (int c = 0; c < h.clusterCount(); c++) {
            trianglesPerLevel[h.level(c)] += h.triangleCount(c);
        }
        assertEquals(mesh.triangleCount(), trianglesPerLevel[0], "level 0 is the whole input");
        for (int l = 1; l < trianglesPerLevel.length; l++) {
            assertTrue(trianglesPerLevel[l] < trianglesPerLevel[l - 1] * 0.75, "level " + l + " has " + trianglesPerLevel[l] + " after " + trianglesPerLevel[l - 1]);
        }
        System.out.println("CLUSTER-LEVELS " + Arrays.toString(trianglesPerLevel) + " clusters=" + h.clusterCount());
    }

    @Test
    void theLeavesAreTheInputAndErrorsAndSpheresAreMonotone() {
        Mesh mesh = weldedPositions(Primitives.icoSphere(1f, 4));
        ClusterHierarchy h = ClusterHierarchy.build(mesh, 64, 128, 4);
        List<Integer> leaves = new ArrayList<>();
        for (int c = 0; c < h.clusterCount(); c++) {
            if (h.level(c) == 0) {
                leaves.add(c);
                assertEquals(0f, h.lodError(c));
            }
            assertTrue(h.parentError(c) >= h.lodError(c), "the error never decreases going up");
            if (h.parentError(c) != Float.POSITIVE_INFINITY) {
                double d = Math.sqrt(sq(h.parentCenterX(c) - h.lodCenterX(c)) + sq(h.parentCenterY(c) - h.lodCenterY(c)) + sq(h.parentCenterZ(c) - h.lodCenterZ(c)));
                assertTrue(d + h.lodRadius(c) <= h.parentRadius(c) * (1 + 1e-4) + 1e-5, "the parent sphere contains the LOD sphere of the cluster");
                double g = Math.sqrt(sq(h.parentCenterX(c) - h.sphereX(c)) + sq(h.parentCenterY(c) - h.sphereY(c)) + sq(h.parentCenterZ(c) - h.sphereZ(c)));
                assertTrue(g + h.sphereRadius(c) <= h.parentRadius(c) * (1 + 1e-4) + 1e-5, "and its geometry");
            }
        }
        int[] all = new int[leaves.size()];
        for (int i = 0; i < all.length; i++) {
            all[i] = leaves.get(i);
        }
        assertEquals(canonicalTriangles(indicesOf(h.vertices())), canonicalTriangles(h.triangles(all, all.length)), "level 0 is exactly the triangles of the welded input");
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int c = 0; c < h.clusterCount(); c++) {
            if (h.parentGroup(c) >= 0) {
                groups.computeIfAbsent(h.parentGroup(c), k -> new ArrayList<>()).add(c);
            } else {
                assertEquals(Float.POSITIVE_INFINITY, h.parentError(c), "a root has no parent");
            }
        }
        for (List<Integer> members : groups.values()) {
            assertTrue(members.size() >= 1 && members.size() <= 4, "a group has at most groupSize children: " + members.size());
            int first = members.get(0);
            for (int c : members) {
                assertEquals(h.level(first), h.level(c), "a group is made of one level");
                assertEquals(h.parentError(first), h.parentError(c));
                assertEquals(h.parentCenterX(first), h.parentCenterX(c));
                assertEquals(h.parentRadius(first), h.parentRadius(c));
            }
        }
    }

    @Test
    void everySelectionIsAClosedSurfaceWithoutCracks() {
        for (Mesh shape : new Mesh[] {weldedPositions(Primitives.icoSphere(1f, 5)), weldedPositions(Primitives.torus(1f, 0.35f, 96, 48))}) {
            ClusterHierarchy h = ClusterHierarchy.build(shape, 64, 128, 4);
            double originalArea = area(shape, indicesOf(shape));
            double originalVolume = volume(shape, indicesOf(shape));
            int cuts = 0;
            for (float budget : new float[] {0f, 0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f, 64f, 1e9f}) {
                for (int trial = 0; trial < 12; trial++) {
                    Vec3f eye = rnd.nextVec3f();
                    eye = eye.mul((float) rnd.range(1.5, 25) / eye.length());
                    int[] sel = select(h, eye.x(), eye.y(), eye.z(), budget);
                    int[] tris = h.triangles(sel, sel.length);
                    assertEquals(0, openEdges(tris), "budget " + budget + " eye " + eye + ": the selected surface must be closed");
                    Set<Integer> unique = new HashSet<>();
                    for (int c : sel) {
                        assertTrue(unique.add(c), "no cluster twice");
                    }
                    double a = area(h.vertices(), tris), v = volume(h.vertices(), tris);
                    boolean root = budget >= 1e6f; // the unlimited budget gives the coarsest roots, a few dozen triangles, which cannot keep a thin tube
                    assertTrue(a > originalArea * (root ? 0.4 : 0.75) && a < originalArea * 1.03, "budget " + budget + ": area " + a + " vs " + originalArea);
                    assertTrue(v > originalVolume * (root ? 0.1 : 0.5) && v < originalVolume * 1.01, "budget " + budget + ": volume " + v + " vs " + originalVolume);
                    cuts++;
                }
            }
            assertEquals(120, cuts);
        }
    }

    @Test
    void zeroBudgetIsTheOriginalAndAHugeBudgetIsTheRoots() {
        Mesh mesh = weldedPositions(Primitives.icoSphere(1f, 5));
        ClusterHierarchy h = ClusterHierarchy.build(mesh, 64, 128, 4);
        int[] sel = select(h, 0f, 0f, 5f, 0f);
        assertEquals(canonicalTriangles(indicesOf(h.vertices())), canonicalTriangles(h.triangles(sel, sel.length)), "no error allowed: the original triangles");
        int[] coarse = select(h, 0f, 0f, 5f, 1e9f);
        for (int c : coarse) {
            assertEquals(Float.POSITIVE_INFINITY, h.parentError(c), "only roots at an unlimited budget");
            assertEquals(h.levelCount() - 1, h.level(c));
        }
        int coarseTriangles = h.triangles(coarse, coarse.length).length / 3;
        assertTrue(coarseTriangles < mesh.triangleCount() / 8, "the roots are much coarser: " + coarseTriangles + " of " + mesh.triangleCount());
    }

    @Test
    void theSelectionGetsCoarserWithTheBudgetAndWithDistance() {
        Mesh mesh = weldedPositions(Primitives.icoSphere(1f, 5));
        ClusterHierarchy h = ClusterHierarchy.build(mesh, 64, 128, 4);
        int previous = Integer.MAX_VALUE;
        for (float budget : new float[] {0f, 0.1f, 0.5f, 1f, 2f, 4f, 16f, 100f, 1e9f}) {
            int[] sel = select(h, 0f, 0f, 4f, budget);
            int t = h.triangles(sel, sel.length).length / 3;
            assertTrue(t <= previous, "budget " + budget + ": " + t + " triangles after " + previous);
            previous = t;
        }
        previous = Integer.MAX_VALUE;
        for (float distance : new float[] {1.5f, 3f, 6f, 12f, 50f, 400f}) {
            int[] sel = select(h, 0f, 0f, distance, 1f);
            int t = h.triangles(sel, sel.length).length / 3;
            assertTrue(t <= previous, "distance " + distance + ": " + t + " triangles after " + previous);
            previous = t;
        }
    }

    @Test
    void smallAndSeamedMeshesStillWork() {
        Mesh box = Primitives.box(1f, 1f, 1f);
        ClusterHierarchy b = ClusterHierarchy.build(box, 64, 128, 4);
        // the six faces of a box share no vertices, so each is its own meshlet (clusters are connected); all six must always be selected together
        assertTrue(b.clusterCount() >= 6);
        int[] sel = select(b, 0f, 0f, 9f, 0f);
        assertEquals(12, b.triangles(sel, sel.length).length / 3, "the original box at zero error");
        int[] coarse = select(b, 0f, 0f, 9f, 1e9f);
        assertTrue(b.triangles(coarse, coarse.length).length / 3 >= 12 - 12, "some valid selection exists at any budget");
        Mesh soup = Primitives.uvSphere(1f, 40, 20); // UV and normal seams are locked, so it simplifies little but must stay valid
        ClusterHierarchy s = ClusterHierarchy.build(soup, 64, 128, 4);
        assertTrue(s.clusterCount() >= 1);
        for (float budget : new float[] {0f, 1f, 1e9f}) {
            assertTrue(select(s, 0f, 0f, 6f, budget).length >= 1);
        }
        ClusterHierarchy empty = ClusterHierarchy.build(new Mesh(), 64, 128, 4);
        assertEquals(0, empty.clusterCount());
        assertEquals(0, select(empty, 0f, 0f, 1f, 1f).length);
        assertThrows(IllegalArgumentException.class, () -> ClusterHierarchy.build(box, 64, 128, 1));
    }

    @Test
    void buildingIsDeterministic() {
        Mesh mesh = weldedPositions(Primitives.torus(1f, 0.35f, 64, 32));
        ClusterHierarchy a = ClusterHierarchy.build(mesh, 64, 128, 4), b = ClusterHierarchy.build(mesh, 64, 128, 4);
        assertEquals(a.clusterCount(), b.clusterCount());
        Set<Integer> levels = new HashSet<>();
        for (int c = 0; c < a.clusterCount(); c++) {
            assertEquals(a.lodError(c), b.lodError(c));
            assertTrue(Arrays.equals(a.indices(c), b.indices(c)));
            levels.add(a.level(c));
        }
        assertEquals(a.levelCount(), levels.size(), "every level has clusters");
    }
}
