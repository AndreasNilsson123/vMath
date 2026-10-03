package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.spatial.LodSelector;

class MeshLodTest {

    private static Mesh weldedSphere(int subdivisions) {
        Mesh src = Primitives.icoSphere(1f, subdivisions);
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

    @Test
    void theChainGetsCoarserAndItsErrorsGrow() {
        Mesh source = weldedSphere(4);
        int triangles = source.triangleCount();
        float[] firstPositions = source.positions().clone();
        MeshLod.Chain chain = MeshLod.build(source, 5, 0.5f, 20, false);
        assertEquals(5, chain.count());
        assertEquals(triangles, source.triangleCount(), "the source is not changed");
        assertTrue(java.util.Arrays.equals(firstPositions, java.util.Arrays.copyOf(source.positions(), firstPositions.length)));
        assertEquals(triangles, chain.levels()[0].triangleCount(), "level 0 is a copy of the source");
        assertEquals(0f, chain.errors()[0]);
        for (int i = 1; i < chain.count(); i++) {
            assertTrue(chain.levels()[i].triangleCount() < chain.levels()[i - 1].triangleCount() * 0.6, "level " + i + " is about half of the one before");
            assertTrue(chain.errors()[i] > chain.errors()[i - 1], "errors strictly increase");
        }
    }

    @Test
    void theChainStopsWhenItCannotGoOn() {
        Mesh tiny = weldedSphere(1); // 80 triangles
        MeshLod.Chain chain = MeshLod.build(tiny, 10, 0.5f, 30, false);
        assertTrue(chain.count() >= 2 && chain.count() < 10, "levels " + chain.count());
        assertTrue(chain.levels()[chain.count() - 1].triangleCount() >= 20, "never below the floor by much");
        Mesh box = Primitives.box(1f, 1f, 1f); // every corner is a seam: nothing collapses
        assertEquals(1, MeshLod.build(box, 4, 0.5f, 2, false).count());
    }

    @Test
    void pixelErrorMatchesTheFormulaAndFallsWithDistance() {
        MeshLod.Chain chain = MeshLod.build(weldedSphere(3), 3, 0.5f, 20, false);
        float fov = 1.0f, height = 1080f;
        float e = chain.errors()[2];
        assertEquals(e * height / (2f * 10f * (float) Math.tan(fov / 2)), chain.pixelError(2, 10f, fov, height), 1e-4f);
        assertTrue(chain.pixelError(2, 20f, fov, height) < chain.pixelError(2, 10f, fov, height));
        // selection: near gives fine, far gives coarse, and the choice never gets finer with distance
        int previous = 0;
        for (float d = 0.5f; d < 2000f; d *= 1.3f) {
            int level = chain.levelFor(d, fov, height, 1f);
            assertTrue(level >= previous, "level must not get finer as the object moves away: " + d);
            previous = level;
        }
        assertEquals(0, chain.levelFor(0.01f, fov, height, 1f));
        assertEquals(chain.count() - 1, chain.levelFor(1e5f, fov, height, 1f));
    }

    @Test
    void selectorThresholdsAgreeWithTheDirectSelectionAndFeedLodSelector() {
        MeshLod.Chain chain = MeshLod.build(weldedSphere(4), 4, 0.5f, 20, false);
        float radius = 1f, budget = 1.5f, fov = 0.9f, height = 1000f;
        float[] thresholds = chain.selectorThresholds(radius, budget);
        assertEquals(3, thresholds.length);
        LodSelector selector = LodSelector.of(thresholds); // throws unless strictly descending
        assertEquals(4, selector.levels());
        float pixelScale = height / (2f * (float) Math.tan(fov / 2));
        for (float d = 1.5f; d < 3000f; d *= 1.17f) {
            float size = 2f * radius * pixelScale / d; // what LodSelector measures
            assertEquals(chain.levelFor(d, fov, height, budget), selector.levelFor(size), "distance " + d);
        }
    }

    @Test
    void badArgumentsAreRejected() {
        Mesh m = weldedSphere(2);
        assertThrows(IllegalArgumentException.class, () -> MeshLod.build(m, 0, 0.5f, 10, false));
        assertThrows(IllegalArgumentException.class, () -> MeshLod.build(m, 3, 1f, 10, false));
        assertThrows(IllegalArgumentException.class, () -> MeshLod.build(m, 3, 0f, 10, false));
    }

    @Test
    void meshCopyIsIndependent() {
        Mesh a = Primitives.uvSphere(1f, 12, 6);
        Mesh b = a.copy();
        assertEquals(a.vertexCount(), b.vertexCount());
        assertEquals(a.triangleCount(), b.triangleCount());
        assertEquals(a.hasNormals(), b.hasNormals());
        assertEquals(a.hasTangents(), b.hasTangents());
        assertEquals(a.hasUvs(0), b.hasUvs(0));
        b.setPosition(0, 99f, 99f, 99f);
        assertTrue(a.positions()[0] != 99f);
        b.addVertex(1f, 2f, 3f); // and it can grow
        assertEquals(a.vertexCount() + 1, b.vertexCount());
    }
}
