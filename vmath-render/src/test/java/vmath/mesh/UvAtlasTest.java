package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UvAtlasTest {

    private static double worldArea(Mesh m, int t) {
        float[] p = m.positions();
        int[] i = m.indices();
        int a = i[t * 3] * 3, b = i[t * 3 + 1] * 3, c = i[t * 3 + 2] * 3;
        double ex = p[b] - p[a], ey = p[b + 1] - p[a + 1], ez = p[b + 2] - p[a + 2];
        double fx = p[c] - p[a], fy = p[c + 1] - p[a + 1], fz = p[c + 2] - p[a + 2];
        double cx = ey * fz - ez * fy, cy = ez * fx - ex * fz, cz = ex * fy - ey * fx;
        return 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** Signed area in UV space (positive = counter-clockwise). */
    private static double uvArea(Mesh m, int set, int t) {
        float[] uv = m.uvs(set);
        int[] i = m.indices();
        int a = i[t * 3] * 2, b = i[t * 3 + 1] * 2, c = i[t * 3 + 2] * 2;
        return 0.5 * ((uv[b] - uv[a]) * (double) (uv[c + 1] - uv[a + 1]) - (uv[b + 1] - uv[a + 1]) * (double) (uv[c] - uv[a]));
    }

    /** The positions of every triangle as nine floats, in order. */
    private static float[] trianglePositions(Mesh m) {
        float[] out = new float[m.triangleCount() * 9];
        float[] p = m.positions();
        int[] idx = m.indices();
        for (int t = 0; t < m.triangleCount(); t++) {
            for (int k = 0; k < 3; k++) {
                System.arraycopy(p, idx[t * 3 + k] * 3, out, t * 9 + k * 3, 3);
            }
        }
        return out;
    }

    /** UV box of every chart: {minU, minV, maxU, maxV}. */
    private static double[][] chartBoxes(Mesh m, int set, UvAtlas.Result r) {
        double[][] box = new double[r.charts()][];
        float[] uv = m.uvs(set);
        int[] idx = m.indices();
        for (int t = 0; t < m.triangleCount(); t++) {
            int c = r.triangleChart()[t];
            if (box[c] == null) {
                box[c] = new double[] {2, 2, -1, -1};
            }
            for (int k = 0; k < 3; k++) {
                double u = uv[idx[t * 3 + k] * 2], v = uv[idx[t * 3 + k] * 2 + 1];
                box[c][0] = Math.min(box[c][0], u);
                box[c][1] = Math.min(box[c][1], v);
                box[c][2] = Math.max(box[c][2], u);
                box[c][3] = Math.max(box[c][3], v);
            }
        }
        return box;
    }

    private static void checkAtlas(Mesh m, int set, UvAtlas.Result r, int padding, String what) {
        float[] uv = m.uvs(set);
        for (int v = 0; v < m.vertexCount(); v++) {
            assertTrue(uv[v * 2] >= -1e-6f && uv[v * 2] <= 1 + 1e-6f && uv[v * 2 + 1] >= -1e-6f && uv[v * 2 + 1] <= 1 + 1e-6f, what + ": uv inside [0,1]");
            assertTrue(Float.isFinite(uv[v * 2]) && Float.isFinite(uv[v * 2 + 1]), what + ": finite uv");
        }
        double[][] box = chartBoxes(m, set, r);
        double gap = 2.0 * padding / r.resolution() - 1e-6;
        for (int a = 0; a < box.length; a++) {
            for (int b = a + 1; b < box.length; b++) {
                boolean apart = box[a][2] + gap <= box[b][0] || box[b][2] + gap <= box[a][0] || box[a][3] + gap <= box[b][1] || box[b][3] + gap <= box[a][1];
                assertTrue(apart, what + ": charts " + a + " and " + b + " are closer than the padding");
            }
        }
    }

    @Test
    void aBoxGivesSixFlatChartsWithUniformDensity() {
        Mesh m = Primitives.box(1f, 2f, 3f);
        float[] before = trianglePositions(m);
        UvAtlas.Result r = UvAtlas.generate(m, 1, 30f, 512, 2);
        assertEquals(6, r.charts());
        checkAtlas(m, 1, r, 2, "box");
        assertArrayEquals(before, trianglePositions(m), "the surface is untouched");
        double density = (double) r.texelsPerUnit() / r.resolution();
        for (int t = 0; t < m.triangleCount(); t++) {
            double ratio = uvArea(m, 1, t) / worldArea(m, t);
            assertEquals(density * density, ratio, density * density * 2e-3, "a flat chart keeps areas in proportion, triangle " + t);
        }
        assertTrue(r.efficiency() > 0.3, "efficiency " + r.efficiency());
    }

    @Test
    void curvedShapesStayDisjointBoundedAndOriented() {
        Mesh[] shapes = {Primitives.uvSphere(1f, 24, 12), Primitives.icoSphere(1f, 2), Primitives.torus(1f, 0.35f, 32, 16), Primitives.capsule(0.5f, 1.5f, 24, 6),
                Primitives.cylinder(0.7f, 2f, 24), Primitives.cone(1f, 2f, 24)};
        for (float angle : new float[] {20f, 35f, 60f}) {
            for (int s = 0; s < shapes.length; s++) {
                Mesh m = copyOf(shapes[s]);
                float[] before = trianglePositions(m);
                int verticesBefore = m.vertexCount();
                UvAtlas.Result r = UvAtlas.generate(m, 1, angle, 1024, 3);
                String what = "shape " + s + " angle " + angle;
                checkAtlas(m, 1, r, 3, what);
                assertArrayEquals(before, trianglePositions(m), what + ": the surface is untouched");
                assertEquals(verticesBefore, r.verticesBefore());
                assertEquals(m.vertexCount(), r.verticesAfter());
                assertEquals(m.vertexCount(), r.remap().length);
                float[] p = m.positions();
                for (int v = 0; v < m.vertexCount(); v++) {
                    int o = r.remap()[v];
                    assertTrue(o >= 0 && o < verticesBefore);
                    assertEquals(p[o * 3], p[v * 3]);
                    assertEquals(p[o * 3 + 1], p[v * 3 + 1]);
                    assertEquals(p[o * 3 + 2], p[v * 3 + 2]);
                }
                double density = (double) r.texelsPerUnit() / r.resolution();
                double lowest = Math.cos(Math.toRadians(2 * angle));
                for (int t = 0; t < m.triangleCount(); t++) {
                    double wa = worldArea(m, t);
                    if (wa < 1e-9) {
                        continue;
                    }
                    double ratio = uvArea(m, 1, t) / wa / (density * density);
                    assertTrue(ratio <= 1.0 + 2e-3, what + ": a projection cannot grow a triangle: " + ratio);
                    if (angle < 45f) {
                        assertTrue(ratio >= lowest - 2e-3 && ratio > 0, what + ": ratio " + ratio + " must be positive and at least cos(2 * angle) = " + lowest);
                    }
                }
            }
        }
    }

    @Test
    void smallerAnglesGiveMoreCharts() {
        int previous = 0;
        for (float angle : new float[] {80f, 50f, 25f}) {
            Mesh m = Primitives.uvSphere(1f, 32, 16);
            int charts = UvAtlas.generate(m, 1, angle, 2048, 1).charts();
            assertTrue(charts > previous, "angle " + angle + " gives " + charts + " charts, previous " + previous);
            previous = charts;
        }
    }

    @Test
    void unwrapIsDeterministic() {
        Mesh a = Primitives.torus(1f, 0.3f, 24, 12), b = Primitives.torus(1f, 0.3f, 24, 12);
        UvAtlas.generate(a, 1, 40f, 512, 2);
        UvAtlas.generate(b, 1, 40f, 512, 2);
        assertArrayEquals(a.uvs(1), b.uvs(1));
        assertArrayEquals(a.indices(), b.indices());
    }

    @Test
    void degenerateTrianglesDoNotPoisonTheUvs() {
        Mesh m = Primitives.box(1f, 1f, 1f);
        int a = m.addVertex(0.25f, 0.25f, 1f);
        m.addTriangle(a, a, a); // a point
        int b = m.addVertex(0.5f, 0.5f, 1f);
        m.addTriangle(a, b, a); // a line
        UvAtlas.Result r = UvAtlas.generate(m, 0, 30f, 256, 1);
        checkAtlas(m, 0, r, 1, "degenerate");
        assertEquals(m.triangleCount(), r.triangleChart().length);
    }

    @Test
    void impossibleRequestsAreReported() {
        Mesh m = Primitives.uvSphere(1f, 32, 16);
        assertThrows(IllegalArgumentException.class, () -> UvAtlas.generate(m, 1, 20f, 8, 4), "hundreds of charts cannot fit 8 x 8 texels");
        assertThrows(IllegalArgumentException.class, () -> UvAtlas.generate(Primitives.box(1f, 1f, 1f), 1, 0f, 64, 1));
        assertThrows(IllegalArgumentException.class, () -> UvAtlas.generate(Primitives.box(1f, 1f, 1f), 1, 45f, 1, 1));
    }

    @Test
    void anEmptyMeshHasNothingToDo() {
        UvAtlas.Result r = UvAtlas.generate(new Mesh(), 1, 45f, 64, 1);
        assertEquals(0, r.charts());
    }

    private static Mesh copyOf(Mesh src) {
        Mesh m = new Mesh();
        if (src.hasNormals()) {
            m.enableNormals();
        }
        float[] p = src.positions();
        for (int i = 0; i < src.vertexCount(); i++) {
            m.addVertex(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
        }
        for (int t = 0; t < src.triangleCount(); t++) {
            m.addTriangle(src.indices()[t * 3], src.indices()[t * 3 + 1], src.indices()[t * 3 + 2]);
        }
        return m;
    }
}
