package vmath.samples.demos.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.mesh.Mesh;

/**
 * Tests of the terrain without a window: the height function, the chunk meshes and their chains of
 * levels of detail (the border is kept, so neighbours at different levels meet), and the camera
 * rail whose arc-length table gives a constant speed.
 *
 * <p><b>Thread safety.</b> Each test builds its own terrain; the tests may run in parallel.
 */
class TerrainTest {

    private static String key(float x, float y, float z) {
        return x + "," + y + "," + z;
    }

    @Test
    void theHeightIsAPureFunctionOfThePositionAndStaysInRange() {
        for (int i = 0; i < 200; i++) {
            double x = -2000 + i * 20.3, z = 1500 - i * 17.1;
            float h = Terrain.height(x, z);
            assertEquals(h, Terrain.height(x, z), 0f);
            assertTrue(h >= 0f && h < 400f, "height " + h);
        }
    }

    @Test
    void neighbouringChunksShareTheirEdge() {
        Terrain t = new Terrain(3, 8, 100f);
        Mesh a = t.buildChunk(0), b = t.buildChunk(1);
        assertEquals(81, a.vertexCount());
        assertEquals(128, a.triangleCount());
        assertEquals("", a.validate() == null ? "" : a.validate());
        for (int j = 0; j <= 8; j++) {
            int va = j * 9 + 8, vb = j * 9;
            assertEquals(a.positions()[va * 3], b.positions()[vb * 3], 0f);
            assertEquals(a.positions()[va * 3 + 1], b.positions()[vb * 3 + 1], 0f);
            assertEquals(a.positions()[va * 3 + 2], b.positions()[vb * 3 + 2], 0f);
        }
    }

    @Test
    void theBoundsHoldEveryVertexOfTheirChunk() {
        Terrain t = new Terrain(2, 12, 200f);
        for (int c = 0; c < t.chunkCount(); c++) {
            Mesh m = t.buildChunk(c);
            for (int v = 0; v < m.vertexCount(); v++) {
                assertTrue(m.positions()[v * 3] >= t.bounds().minX(c) - 1e-3f && m.positions()[v * 3] <= t.bounds().maxX(c) + 1e-3f);
                assertTrue(m.positions()[v * 3 + 1] >= t.bounds().minY(c) - 1e-3f && m.positions()[v * 3 + 1] <= t.bounds().maxY(c) + 1e-3f);
                assertTrue(m.positions()[v * 3 + 2] >= t.bounds().minZ(c) - 1e-3f && m.positions()[v * 3 + 2] <= t.bounds().maxZ(c) + 1e-3f);
            }
        }
    }

    @Test
    void theChainsHaveFewerTrianglesAtEveryLevelAndKeepTheBorderOfTheChunk() {
        Terrain t = new Terrain(2, 32, 200f);
        t.buildChains(4, 0.3f, 16);
        assertTrue(t.levels() >= 2);
        for (int c = 0; c < t.chunkCount(); c++) {
            var chain = t.chain(c);
            for (int l = 1; l < t.levels(); l++) {
                assertTrue(chain.levels()[l].triangleCount() < chain.levels()[l - 1].triangleCount(), "chunk " + c + " level " + l);
                assertTrue(chain.errors()[l] >= chain.errors()[l - 1]);
            }
            Mesh full = chain.levels()[0], coarsest = chain.levels()[t.levels() - 1];
            Set<String> kept = new HashSet<>();
            for (int v = 0; v < coarsest.vertexCount(); v++) {
                kept.add(key(coarsest.positions()[v * 3], coarsest.positions()[v * 3 + 1], coarsest.positions()[v * 3 + 2]));
            }
            float x0 = t.bounds().minX(c), z0 = t.bounds().minZ(c), x1 = t.bounds().maxX(c), z1 = t.bounds().maxZ(c);
            for (int v = 0; v < full.vertexCount(); v++) {
                float x = full.positions()[v * 3], y = full.positions()[v * 3 + 1], z = full.positions()[v * 3 + 2];
                boolean border = x == x0 || x == x1 || z == z0 || z == z1;
                if (border) {
                    assertTrue(kept.contains(key(x, y, z)), "the border vertex " + x + ", " + z + " of chunk " + c + " is gone at the coarsest level");
                }
            }
        }
        float[] mean = t.meanErrors();
        assertEquals(0f, mean[0], 0f);
        assertTrue(mean[mean.length - 1] > 0f);
    }

    @Test
    void movingByArcLengthGivesAConstantSpeedAndMovingByParameterDoesNot() {
        Rail rail = new Rail(10, 1500f, 80f);
        float length = rail.length();
        assertTrue(length > 5000f && length < 12000f, "length " + length);
        float[] a = new float[3], b = new float[3];
        double step = 25.0;
        float lowest = Float.MAX_VALUE, highest = 0f;
        for (int i = 0; i < 300; i++) {
            rail.positionAtDistance(i * step, a);
            rail.positionAtDistance((i + 1) * step, b);
            float d = (float) Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));
            lowest = Math.min(lowest, d);
            highest = Math.max(highest, d);
        }
        assertTrue(highest / lowest < 1.03f, "equal steps of distance are equal steps in space: " + lowest + " to " + highest);
        double du = rail.segments() / (double) (length / step);
        float slowest = Float.MAX_VALUE, fastest = 0f;
        for (int i = 0; i < 300; i++) {
            rail.positionAtParameter(i * du, a);
            rail.positionAtParameter((i + 1) * du, b);
            float d = (float) Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));
            slowest = Math.min(slowest, d);
            fastest = Math.max(fastest, d);
        }
        assertTrue(fastest / slowest > 1.3f, "equal steps of the parameter are not: " + slowest + " to " + fastest);
    }

    @Test
    void theRailIsClosed() {
        Rail rail = new Rail(8, 1000f, 50f);
        float[] a = new float[3], b = new float[3];
        rail.positionAtDistance(0, a);
        rail.positionAtDistance(rail.length(), b);
        assertEquals(a[0], b[0], 1f);
        assertEquals(a[2], b[2], 1f);
    }

    @Test
    void badSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Terrain(0, 8, 10f));
        assertThrows(IllegalArgumentException.class, () -> new Terrain(2, 2, 10f));
        assertThrows(IllegalArgumentException.class, () -> new Rail(3, 10f, 1f));
        assertTrue(TerrainOptions.parse(List.of()).chunks() > 0);
    }
}
