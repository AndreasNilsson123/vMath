package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.Intersectionf;
import vmath.geo.Trianglef;

class MeshSimplifierTest {

    /** {@code src} with positions and triangles only, duplicates merged by position: a closed mesh has no seams left. */
    private static Mesh weldedPositions(Mesh src) {
        Mesh m = new Mesh();
        float[] p = src.positions();
        for (int i = 0; i < src.vertexCount(); i++) {
            m.addVertex(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
        }
        for (int t = 0; t < src.triangleCount(); t++) {
            m.addTriangle(src.indices()[t * 3], src.indices()[t * 3 + 1], src.indices()[t * 3 + 2]);
        }
        MeshOptimizer.weld(m, 1e-6f, true);
        return m;
    }

    private static Mesh copyOf(Mesh src) {
        Mesh m = new Mesh();
        float[] p = src.positions();
        for (int i = 0; i < src.vertexCount(); i++) {
            m.addVertex(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
        }
        for (int t = 0; t < src.triangleCount(); t++) {
            m.addTriangle(src.indices()[t * 3], src.indices()[t * 3 + 1], src.indices()[t * 3 + 2]);
        }
        return m;
    }

    /** Number of edges (undirected) and whether every directed edge occurs exactly once together with its reverse. */
    private static int[] edgeStats(Mesh m) {
        Map<Long, Integer> directed = new HashMap<>();
        int[] idx = m.indices();
        for (int t = 0; t < m.indexCount(); t += 3) {
            for (int k = 0; k < 3; k++) {
                long key = ((long) idx[t + k] << 32) | (idx[t + (k + 1) % 3] & 0xffffffffL);
                directed.merge(key, 1, Integer::sum);
            }
        }
        boolean closed = true;
        int undirected = 0;
        for (Map.Entry<Long, Integer> e : directed.entrySet()) {
            long rev = (e.getKey() << 32) | (e.getKey() >>> 32);
            if (e.getValue() != 1 || !directed.containsKey(rev)) {
                closed = false;
            }
            if ((e.getKey() >>> 32) < (e.getKey() & 0xffffffffL)) {
                undirected++;
            } else if (!directed.containsKey(rev)) {
                undirected++;
            }
        }
        return new int[] {undirected, closed ? 1 : 0};
    }

    private static int usedVertices(Mesh m) {
        boolean[] used = new boolean[m.vertexCount()];
        int n = 0;
        for (int i = 0; i < m.indexCount(); i++) {
            if (!used[m.indices()[i]]) {
                used[m.indices()[i]] = true;
                n++;
            }
        }
        return n;
    }

    /** Largest distance from a vertex of {@code simple} to the surface of {@code original}. */
    private static double oneSidedDistance(Mesh simple, Mesh original) {
        double worst = 0;
        float[] sp = simple.positions(), op = original.positions();
        int[] oi = original.indices();
        for (int v = 0; v < simple.vertexCount(); v++) {
            Vec3f p = new Vec3f(sp[v * 3], sp[v * 3 + 1], sp[v * 3 + 2]);
            double best = Double.MAX_VALUE;
            for (int t = 0; t < original.indexCount(); t += 3) {
                Trianglef tri = Trianglef.of(new Vec3f(op[oi[t] * 3], op[oi[t] * 3 + 1], op[oi[t] * 3 + 2]),
                        new Vec3f(op[oi[t + 1] * 3], op[oi[t + 1] * 3 + 1], op[oi[t + 1] * 3 + 2]), new Vec3f(op[oi[t + 2] * 3], op[oi[t + 2] * 3 + 1], op[oi[t + 2] * 3 + 2]));
                best = Math.min(best, Intersectionf.pointTriangleDistanceSquared(p, tri));
            }
            worst = Math.max(worst, Math.sqrt(best));
        }
        return worst;
    }

    private static Mesh grid(int n, float size, boolean uvs) {
        Mesh m = new Mesh((n + 1) * (n + 1), 2 * n * n);
        if (uvs) {
            m.enableUvs(0);
        }
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                int v = m.addVertex(i * size / n, 0f, j * size / n);
                if (uvs) {
                    m.setUv(0, v, (float) i / n, (float) j / n);
                }
            }
        }
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = j * (n + 1) + i;
                m.addTriangle(a, a + n + 2, a + 1);
                m.addTriangle(a, a + n + 1, a + n + 2);
            }
        }
        return m;
    }

    @Test
    void aFlatGridCollapsesToAlmostNothingForAlmostNoError() {
        Mesh m = grid(40, 4f, false);
        Aabbf box = m.bounds();
        double area = m.surfaceArea();
        MeshSimplifier.Result r = MeshSimplifier.simplify(m, 10, Float.MAX_VALUE, false);
        assertEquals(3200, r.trianglesBefore());
        assertTrue(r.trianglesAfter() <= 10 && r.trianglesAfter() >= 2, "triangles " + r.trianglesAfter());
        assertEquals(area, m.surfaceArea(), area * 1e-3, "the flat area is preserved");
        assertEquals(box, m.bounds(), "the border keeps the corners");
        assertTrue(r.error() < 1e-3f, "flat collapses cost nothing: " + r.error());
        for (int v = 0; v < m.vertexCount(); v++) {
            assertEquals(0f, m.positions()[v * 3 + 1], 1e-5f);
        }
        assertEquals(r.trianglesAfter(), m.triangleCount());
        assertEquals(m.vertexCount(), usedVertices(m), "no unused vertices remain");
    }

    @Test
    void uvsStayConsistentWithPositionsOnAFlatGrid() {
        Mesh m = grid(30, 3f, true);
        MeshSimplifier.simplify(m, 40, Float.MAX_VALUE, false);
        assertTrue(m.triangleCount() <= 40);
        for (int v = 0; v < m.vertexCount(); v++) {
            assertEquals(m.positions()[v * 3] / 3f, m.uvs(0)[v * 2], 1e-4f, "u follows x");
            assertEquals(m.positions()[v * 3 + 2] / 3f, m.uvs(0)[v * 2 + 1], 1e-4f, "v follows z");
        }
    }

    @Test
    void lockBorderLeavesTheBoundaryAlone() {
        Mesh m = grid(20, 2f, false);
        int border = 0;
        for (int v = 0; v < m.vertexCount(); v++) {
            float x = m.positions()[v * 3], z = m.positions()[v * 3 + 2];
            if (x == 0f || z == 0f || x == 2f || z == 2f) {
                border++;
            }
        }
        MeshSimplifier.simplify(m, 100, Float.MAX_VALUE, true);
        int after = 0;
        for (int v = 0; v < m.vertexCount(); v++) {
            float x = m.positions()[v * 3], z = m.positions()[v * 3 + 2];
            if (x == 0f || z == 0f || x == 2f || z == 2f) {
                after++;
            }
        }
        assertEquals(border, after, "every border vertex is still there");
        assertTrue(m.triangleCount() < 800, "the interior still simplified: " + m.triangleCount());
    }

    @Test
    void closedShapesStayClosedAndKeepTheirTopology() {
        Mesh sphere = weldedPositions(Primitives.icoSphere(1f, 3));
        Mesh torus = weldedPositions(Primitives.torus(1f, 0.35f, 32, 16));
        Mesh[] meshes = {sphere, torus};
        int[] euler = {2, 0};
        for (int s = 0; s < meshes.length; s++) {
            for (double fraction : new double[] {0.5, 0.25, 0.1}) {
                Mesh m = copyOf(meshes[s]);
                double volume = m.signedVolume();
                int target = (int) (m.triangleCount() * fraction);
                MeshSimplifier.Result r = MeshSimplifier.simplify(m, target, Float.MAX_VALUE, false);
                int[] e = edgeStats(m);
                assertEquals(1, e[1], "shape " + s + " at " + fraction + ": still a closed manifold");
                assertEquals(euler[s], usedVertices(m) - e[0] + m.triangleCount(), "shape " + s + " at " + fraction + ": Euler characteristic");
                assertTrue(m.triangleCount() <= target && m.triangleCount() >= target - 4, "shape " + s + " reaches the target: " + m.triangleCount() + " of " + target);
                assertTrue(m.signedVolume() > 0, "not inside out");
                assertTrue(m.signedVolume() > volume * 0.5 && m.signedVolume() < volume * 1.1, "volume " + m.signedVolume() + " vs " + volume);
                assertTrue(r.error() > 0 && Float.isFinite(r.error()));
            }
        }
    }

    @Test
    void theSurfaceStaysNearTheOriginal() {
        Mesh original = weldedPositions(Primitives.icoSphere(1f, 3));
        for (double fraction : new double[] {0.5, 0.25, 0.1}) {
            Mesh m = copyOf(original);
            MeshSimplifier.Result r = MeshSimplifier.simplify(m, (int) (original.triangleCount() * fraction), Float.MAX_VALUE, false);
            double distance = oneSidedDistance(m, original);
            // every vertex lies within a small fraction of the radius of the original surface, and the estimate is the same order as the truth
            assertTrue(distance < 0.03 + 0.15 * (1 - fraction) * (1 - fraction), "fraction " + fraction + ": vertex distance " + distance);
            assertTrue(r.error() > distance * 0.1 && r.error() < distance * 50 + 1e-3, "estimate " + r.error() + " vs measured " + distance);
        }
    }

    @Test
    void maxErrorStopsEarly() {
        Mesh m = weldedPositions(Primitives.icoSphere(1f, 3));
        int before = m.triangleCount();
        MeshSimplifier.Result none = MeshSimplifier.simplify(m, 4, 1e-9f, false);
        assertEquals(0, none.collapses(), "a sphere has no free collapse");
        assertEquals(before, m.triangleCount());
        MeshSimplifier.Result some = MeshSimplifier.simplify(m, 4, 0.05f, false);
        assertTrue(some.trianglesAfter() < before && some.trianglesAfter() > 4, "stopped by the error limit: " + some.trianglesAfter());
        assertTrue(some.error() <= 0.05f + 1e-6f, "error " + some.error());
    }

    @Test
    void seamsAreNeverTouched() {
        Mesh box = Primitives.box(1f, 2f, 3f); // every corner has three different normals: all seams
        float[] pos = box.positions().clone();
        float[] nrm = box.normals().clone();
        int verts = box.vertexCount();
        MeshSimplifier.Result r = MeshSimplifier.simplify(box, 2, Float.MAX_VALUE, false);
        assertEquals(0, r.collapses());
        assertEquals(12, box.triangleCount());
        assertEquals(verts, box.vertexCount());
        for (int i = 0; i < verts * 3; i++) {
            assertEquals(pos[i], box.positions()[i]);
            assertEquals(nrm[i], box.normals()[i]);
        }
    }

    @Test
    void normalsAreInterpolatedAndStayUnit() {
        Mesh m = weldedPositions(Primitives.icoSphere(1f, 2));
        m.enableNormals();
        for (int v = 0; v < m.vertexCount(); v++) {
            Vec3f n = new Vec3f(m.positions()[v * 3], m.positions()[v * 3 + 1], m.positions()[v * 3 + 2]).normalize();
            m.setNormal(v, n.x(), n.y(), n.z());
        }
        MeshSimplifier.simplify(m, 60, Float.MAX_VALUE, false);
        for (int v = 0; v < m.vertexCount(); v++) {
            float x = m.normals()[v * 3], y = m.normals()[v * 3 + 1], z = m.normals()[v * 3 + 2];
            assertEquals(1f, Math.sqrt(x * x + y * y + z * z), 1e-4);
            // still close to the radial direction it started as, because the positions only moved along edges
            Vec3f radial = new Vec3f(m.positions()[v * 3], m.positions()[v * 3 + 1], m.positions()[v * 3 + 2]).normalize();
            assertTrue(radial.x() * x + radial.y() * y + radial.z() * z > 0.8f);
        }
    }

    @Test
    void degenerateAndEmptyInput() {
        Mesh empty = new Mesh();
        MeshSimplifier.Result r = MeshSimplifier.simplify(empty, 0, 1f, false);
        assertEquals(0, r.trianglesAfter());
        Mesh m = grid(4, 1f, false);
        int a = m.addVertex(0.5f, 0f, 0.5f);
        m.addTriangle(a, a, a);
        MeshSimplifier.Result r2 = MeshSimplifier.simplify(m, 100, Float.MAX_VALUE, false);
        assertEquals(32, r2.trianglesBefore(), "the zero-area triangle is not counted");
        assertEquals(32, r2.trianglesAfter());
        for (float f : m.positions()) {
            assertTrue(Float.isFinite(f));
        }
    }
}
