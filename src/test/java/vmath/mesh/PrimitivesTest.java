package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import vmath.geo.Aabbf;

class PrimitivesTest {

    private record Key(int x, int y, int z) {
    }

    /** Vertices with bit-identical positions count as one: seams are duplicated, never gaps. */
    private static int[] canonicalIds(Mesh m) {
        Map<Key, Integer> ids = new HashMap<>();
        int[] out = new int[m.vertexCount()];
        float[] p = m.positions();
        for (int v = 0; v < m.vertexCount(); v++) {
            // -0.0 and 0.0 are the same position
            Key k = new Key(Float.floatToIntBits(p[v * 3] + 0f), Float.floatToIntBits(p[v * 3 + 1] + 0f), Float.floatToIntBits(p[v * 3 + 2] + 0f));
            out[v] = ids.computeIfAbsent(k, x -> ids.size());
        }
        return out;
    }

    /** Every directed edge of a closed mesh appears exactly once and so does its reverse. */
    private static void assertWatertight(Mesh m, String name) {
        int[] id = canonicalIds(m);
        Map<Long, Integer> edges = new HashMap<>();
        int[] idx = m.indices();
        for (int t = 0; t < m.indexCount(); t += 3) {
            for (int k = 0; k < 3; k++) {
                long a = id[idx[t + k]], b = id[idx[t + (k + 1) % 3]];
                assertTrue(a != b, name + ": triangle with a collapsed edge");
                edges.merge((a << 32) | b, 1, Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> e : edges.entrySet()) {
            long a = e.getKey() >>> 32, b = e.getKey() & 0xFFFFFFFFL;
            assertEquals(1, e.getValue(), name + ": directed edge used more than once");
            assertEquals(1, edges.getOrDefault((b << 32) | a, 0), name + ": edge without its opposite (a hole or a flipped triangle)");
        }
    }

    private static void assertSound(Mesh m, String name) {
        int[] idx = m.indices();
        float[] p = m.positions();
        float[] n = m.normals();
        assertTrue(m.hasNormals() && m.hasTangents() && m.hasUvs(0), name + ": streams");
        for (int i = 0; i < m.indexCount(); i++) {
            assertTrue(idx[i] >= 0 && idx[i] < m.vertexCount(), name + ": index in range");
        }
        for (int v = 0; v < m.vertexCount(); v++) {
            double len = Math.sqrt(n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2]);
            assertEquals(1.0, len, 1e-4, name + ": unit normal at " + v);
            float[] t = m.tangents();
            double tl = Math.sqrt(t[v * 4] * t[v * 4] + t[v * 4 + 1] * t[v * 4 + 1] + t[v * 4 + 2] * t[v * 4 + 2]);
            assertEquals(1.0, tl, 1e-4, name + ": unit tangent at " + v);
            assertEquals(0.0, t[v * 4] * n[v * 3] + t[v * 4 + 1] * n[v * 3 + 1] + t[v * 4 + 2] * n[v * 3 + 2], 2e-4, name + ": tangent is perpendicular to the normal");
            assertTrue(Math.abs(t[v * 4 + 3]) == 1f, name + ": handedness is +-1");
            for (int k = 0; k < 3; k++) {
                assertTrue(Float.isFinite(p[v * 3 + k]) && Float.isFinite(n[v * 3 + k]), name + ": finite");
            }
        }
        // vertex normals agree in direction with the face they belong to, and no triangle has zero area
        for (int t = 0; t < m.indexCount(); t += 3) {
            double[] fn = faceNormal(p, idx[t], idx[t + 1], idx[t + 2]);
            double len = Math.sqrt(fn[0] * fn[0] + fn[1] * fn[1] + fn[2] * fn[2]);
            assertTrue(len > 1e-12, name + ": zero-area triangle " + t / 3);
            for (int k = 0; k < 3; k++) {
                int v = idx[t + k];
                double dot = (fn[0] * n[v * 3] + fn[1] * n[v * 3 + 1] + fn[2] * n[v * 3 + 2]) / len;
                assertTrue(dot > 0.2, name + ": normal disagrees with its face (dot " + dot + ") in triangle " + t / 3);
            }
        }
    }

    private static double[] faceNormal(float[] p, int a, int b, int c) {
        double ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
        double vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
        return new double[] {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
    }

    private static void assertClosedAndOutward(Mesh m, String name) {
        assertSound(m, name);
        assertWatertight(m, name);
        assertTrue(m.signedVolume() > 0, name + ": inside out (volume " + m.signedVolume() + ")");
    }

    @Test
    void everyClosedShapeIsWatertightOutwardAndSound() {
        Map<String, Supplier<Mesh>> shapes = new java.util.LinkedHashMap<>();
        shapes.put("box", () -> Primitives.box(1f, 2f, 3f));
        shapes.put("uvSphere 16x8", () -> Primitives.uvSphere(1.5f, 16, 8));
        shapes.put("uvSphere 3x2", () -> Primitives.uvSphere(1f, 3, 2));
        shapes.put("icoSphere 0", () -> Primitives.icoSphere(1f, 0));
        shapes.put("icoSphere 3", () -> Primitives.icoSphere(2f, 3));
        shapes.put("capsule", () -> Primitives.capsule(0.5f, 2f, 16, 4));
        shapes.put("capsule flat", () -> Primitives.capsule(0.5f, 0f, 12, 3));
        shapes.put("cylinder", () -> Primitives.cylinder(1f, 2f, 20));
        shapes.put("cone", () -> Primitives.cone(1f, 2f, 20));
        shapes.put("torus", () -> Primitives.torus(2f, 0.5f, 24, 12));
        for (var e : shapes.entrySet()) {
            assertClosedAndOutward(e.getValue().get(), e.getKey());
        }
    }

    @Test
    void volumesAndAreasMatchTheAnalyticShapes() {
        double pi = Math.PI;
        Mesh sphere = Primitives.uvSphere(2f, 64, 32);
        assertRatio(sphere.signedVolume(), 4.0 / 3 * pi * 8, 0.985, 1.0, "sphere volume");
        assertRatio(sphere.surfaceArea(), 4 * pi * 4, 0.985, 1.0, "sphere area");
        Mesh ico = Primitives.icoSphere(2f, 3);
        assertRatio(ico.signedVolume(), 4.0 / 3 * pi * 8, 0.97, 1.0, "icosphere volume");
        Mesh cyl = Primitives.cylinder(1.5f, 4f, 64);
        assertRatio(cyl.signedVolume(), pi * 2.25 * 4, 0.995, 1.0, "cylinder volume");
        assertRatio(cyl.surfaceArea(), 2 * pi * 1.5 * 4 + 2 * pi * 2.25, 0.995, 1.0, "cylinder area");
        Mesh cone = Primitives.cone(1.5f, 3f, 64);
        assertRatio(cone.signedVolume(), pi * 2.25 * 3 / 3, 0.995, 1.0, "cone volume");
        Mesh cap = Primitives.capsule(1f, 2f, 64, 16);
        assertRatio(cap.signedVolume(), pi * 1 * 2 + 4.0 / 3 * pi, 0.99, 1.0, "capsule volume");
        Mesh torus = Primitives.torus(3f, 0.75f, 64, 32);
        assertRatio(torus.signedVolume(), 2 * pi * pi * 3 * 0.75 * 0.75, 0.99, 1.0, "torus volume");
        assertRatio(torus.surfaceArea(), 4 * pi * pi * 3 * 0.75, 0.99, 1.0, "torus area");
        Mesh box = Primitives.box(1f, 2f, 3f);
        assertEquals(88.0, box.surfaceArea(), 1e-9, "box area: 2 * (2*4 + 2*6 + 4*6)");
        assertEquals(48.0, box.signedVolume(), 1e-9, "box volume: 2 * 4 * 6");
    }

    private static void assertRatio(double actual, double analytic, double lo, double hi, String what) {
        double r = actual / analytic;
        assertTrue(r >= lo && r <= hi, what + ": ratio " + r + " not in [" + lo + ", " + hi + "]");
    }

    @Test
    void boundsMatchTheShapes() {
        assertEquals(new Aabbf(-1f, -2f, -3f, 1f, 2f, 3f), Primitives.box(1f, 2f, 3f).bounds());
        Aabbf s = Primitives.uvSphere(2f, 32, 16).bounds();
        assertEquals(-2f, s.minY());
        assertEquals(2f, s.maxY());
        assertEquals(2f, s.maxX(), 1e-5f);
        Aabbf c = Primitives.capsule(0.5f, 2f, 16, 4).bounds();
        assertEquals(-1.5f, c.minY(), 1e-6f);
        assertEquals(1.5f, c.maxY(), 1e-6f);
        Aabbf t = Primitives.torus(2f, 0.5f, 32, 16).bounds();
        assertEquals(-0.5f, t.minY(), 1e-6f);
        assertEquals(2.5f, t.maxX(), 1e-5f);
        Aabbf cone = Primitives.cone(1f, 2f, 16).bounds();
        assertEquals(-1f, cone.minY(), 1e-6f);
        assertEquals(1f, cone.maxY(), 1e-6f);
    }

    @Test
    void planeFacesUpAndItsTangentFollowsU() {
        Mesh p = Primitives.plane(4f, 2f, 4, 2);
        assertEquals(15, p.vertexCount());
        assertEquals(16, p.triangleCount());
        assertSound(p, "plane");
        assertEquals(8.0, p.surfaceArea(), 1e-9);
        for (int v = 0; v < p.vertexCount(); v++) {
            assertEquals(1f, p.normals()[v * 3 + 1], 1e-6f);
            assertEquals(1f, p.tangents()[v * 4], 1e-5f, "u grows toward +X");
            assertEquals(1f, p.tangents()[v * 4 + 3], "cross(n, t) points the way v grows (-Z)");
        }
        int[] idx = p.indices();
        for (int t = 0; t < p.indexCount(); t += 3) {
            double[] fn = faceNormal(p.positions(), idx[t], idx[t + 1], idx[t + 2]);
            assertTrue(fn[1] > 0, "counter-clockwise seen from +Y");
        }
    }

    @Test
    void boxFacesAreFlatAndSeamsAreDuplicatedVertices() {
        Mesh b = Primitives.box(1f, 1f, 1f);
        assertEquals(24, b.vertexCount());
        assertEquals(12, b.triangleCount());
        int distinct = (int) java.util.Arrays.stream(canonicalIds(b)).distinct().count();
        assertEquals(8, distinct, "24 vertices sit on 8 distinct positions");
        Mesh s = Primitives.uvSphere(1f, 16, 8);
        int seamDistinct = (int) java.util.Arrays.stream(canonicalIds(s)).distinct().count();
        assertTrue(seamDistinct < s.vertexCount(), "the seam column and the poles are duplicated");
        assertEquals(16 * 7 + 2, seamDistinct, "16 columns x 7 inner rings plus the two poles");
    }

    @Test
    void segmentCountsAreClamped() {
        assertSound(Primitives.uvSphere(1f, 0, 0), "clamped sphere");
        assertSound(Primitives.cylinder(1f, 1f, -5), "clamped cylinder");
        assertSound(Primitives.torus(1f, 0.2f, 1, 1), "clamped torus");
        assertSound(Primitives.icoSphere(1f, -3), "clamped icosphere");
        assertTrue(Primitives.plane(1f, 1f, 0, 0).triangleCount() == 2);
    }
}
