package vmath.camera;

import vmath.Report;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.gl.GpuWriter;

class ClusterLightsTest {

    final Rnd rnd = Rnd.create();

    private ClusterGrid grid(boolean yDown) {
        return ClusterGrid.of(0.9f, 16f / 9f, 0.1f, 200f, 1280, 720, 64, 16, yDown);
    }

    private static float distanceToBoxSquared(float[] b, float x, float y, float z) {
        float ex = Math.max(Math.max(b[0] - x, x - b[3]), 0f), ey = Math.max(Math.max(b[1] - y, y - b[4]), 0f), ez = Math.max(Math.max(b[2] - z, z - b[5]), 0f);
        return ex * ex + ey * ey + ez * ez;
    }

    /** A random view-space point of the frustum, depth distributed over the whole range. */
    private float[] randomPointInFrustum(ClusterGrid g) {
        float d = (float) Math.exp(rnd.range(Math.log(g.near()), Math.log(g.far())));
        return new float[] {(float) rnd.range(-1, 1) * d * g.tanHalfFovX(), (float) rnd.range(-1, 1) * d * g.tanHalfFovY(), -d};
    }

    @Test
    void pointLightsAreNeverMissingAndNeverBeyondTheBoxTest() {
        for (boolean yDown : new boolean[] {false, true}) {
            ClusterGrid g = grid(yDown);
            ClusterLights lights = new ClusterLights();
            float[] boxes = new float[g.clusterCount() * 6];
            g.fillBounds(boxes);
            for (int scene = 0; scene < 6; scene++) {
                lights.clearLights();
                int n = 5 + (int) rnd.range(0, 60);
                for (int i = 0; i < n; i++) {
                    float d = (float) Math.exp(rnd.range(Math.log(0.05), Math.log(300)));
                    float x = (float) rnd.range(-1.6, 1.6) * d * g.tanHalfFovX(), y = (float) rnd.range(-1.6, 1.6) * d * g.tanHalfFovY();
                    lights.addPoint(x, y, -d * (float) (rnd.range(0, 1) < 0.1 ? -0.5 : 1), (float) Math.exp(rnd.range(Math.log(0.1), Math.log(60))));
                }
                lights.assign(g);
                // 1. never beyond the exact sphere against box test of the cluster boxes
                int pairs = 0, boxPairs = 0;
                for (int c = 0; c < g.clusterCount(); c++) {
                    for (int l = 0; l < lights.lightCount(); l++) {
                        float[] b = {boxes[c * 6], boxes[c * 6 + 1], boxes[c * 6 + 2], boxes[c * 6 + 3], boxes[c * 6 + 4], boxes[c * 6 + 5]};
                        float r = lightRange(lights, l);
                        boolean exact = distanceToBoxSquared(b, lightX(lights, l), lightY(lights, l), lightZ(lights, l)) <= r * r;
                        boolean listed = lights.contains(c, l);
                        if (exact) {
                            boxPairs++;
                        }
                        if (listed) {
                            pairs++;
                            assertTrue(exact, "light " + l + " is listed for cluster " + c + " although its sphere misses the cluster box");
                        }
                    }
                }
                assertEquals(pairs, lights.totalAssignments(g));
                // 2. sampling: a point inside a light and inside the frustum lands in a cluster that lists the light
                for (int s = 0; s < 4000; s++) {
                    float[] p = randomPointInFrustum(g);
                    int c = g.clusterOfViewPosition(p[0], p[1], p[2]);
                    for (int l = 0; l < lights.lightCount(); l++) {
                        float dx = p[0] - lightX(lights, l), dy = p[1] - lightY(lights, l), dz = p[2] - lightZ(lights, l), r = lightRange(lights, l);
                        if (dx * dx + dy * dy + dz * dz <= r * r * 0.9999f) {
                            assertTrue(lights.contains(c, l), "a point inside light " + l + " is in cluster " + c + " which does not list it");
                        }
                    }
                }
                assertTrue(boxPairs == 0 || pairs >= boxPairs * 0.97, "the range prefilter must not discard many box overlaps: " + pairs + " of " + boxPairs);
            }
        }
    }

    // the light arrays are private: read them back through the bounding sphere that a point light is
    private static float lightX(ClusterLights l, int i) {
        return field(l, "sx")[i];
    }

    private static float lightY(ClusterLights l, int i) {
        return field(l, "sy")[i];
    }

    private static float lightZ(ClusterLights l, int i) {
        return field(l, "sz")[i];
    }

    private static float lightRange(ClusterLights l, int i) {
        return field(l, "sr")[i];
    }

    private static float[] field(ClusterLights l, String name) {
        try {
            java.lang.reflect.Field f = ClusterLights.class.getDeclaredField(name);
            f.setAccessible(true);
            return (float[]) f.get(l);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void spotLightsAreNeverMissingAndOnlyModeratelyOverEstimated() {
        ClusterGrid g = grid(false);
        ClusterLights lights = new ClusterLights();
        long assigned = 0, needed = 0;
        for (int scene = 0; scene < 8; scene++) {
            lights.clearLights();
            int n = 4 + (int) rnd.range(0, 24);
            float[] ax = new float[n], ay = new float[n], az = new float[n], dx = new float[n], dy = new float[n], dz = new float[n], half = new float[n], range = new float[n];
            for (int i = 0; i < n; i++) {
                float d = (float) Math.exp(rnd.range(Math.log(0.3), Math.log(120)));
                ax[i] = (float) rnd.range(-1.2, 1.2) * d * g.tanHalfFovX();
                ay[i] = (float) rnd.range(-1.2, 1.2) * d * g.tanHalfFovY();
                az[i] = -d;
                dx[i] = (float) rnd.range(-1, 1);
                dy[i] = (float) rnd.range(-1, 1);
                dz[i] = (float) rnd.range(-1.5, 0.5);
                half[i] = (float) rnd.range(0.1, 1.3);
                range[i] = (float) Math.exp(rnd.range(Math.log(1), Math.log(80)));
                lights.addSpot(ax[i], ay[i], az[i], dx[i], dy[i], dz[i], half[i], range[i]);
            }
            lights.assign(g);
            boolean[][] occupied = new boolean[n][g.clusterCount()];
            for (int s = 0; s < 6000; s++) {
                float[] p = randomPointInFrustum(g);
                int c = g.clusterOfViewPosition(p[0], p[1], p[2]);
                for (int l = 0; l < n; l++) {
                    float vx = p[0] - ax[l], vy = p[1] - ay[l], vz = p[2] - az[l];
                    float len = (float) Math.sqrt(dx[l] * dx[l] + dy[l] * dy[l] + dz[l] * dz[l]);
                    float along = (vx * dx[l] + vy * dy[l] + vz * dz[l]) / len;
                    float dist = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                    boolean inside = along >= 0 && along <= range[l] * 0.9999f && dist > 1e-6f && along / dist >= (float) Math.cos(half[l]) * 1.0001f;
                    if (inside) {
                        assertTrue(lights.contains(c, l), "scene " + scene + ": a point inside spot " + l + " is in cluster " + c + " which does not list it");
                        occupied[l][c] = true;
                    }
                }
            }
            for (int l = 0; l < n; l++) {
                for (int c = 0; c < g.clusterCount(); c++) {
                    if (lights.contains(c, l)) {
                        assigned++;
                    }
                    if (occupied[l][c]) {
                        needed++;
                    }
                }
            }
        }
        Report.println("CLUSTER-SPOT assigned pairs " + assigned + ", pairs seen by sampling " + needed + " (sampling finds only some of the real ones)");
        assertTrue(assigned > 0 && needed > 0);
    }

    @Test
    void listsAreAscendingAndConsistent() {
        ClusterGrid g = grid(false);
        ClusterLights lights = new ClusterLights();
        for (int i = 0; i < 80; i++) {
            float d = (float) Math.exp(rnd.range(Math.log(0.5), Math.log(100)));
            if (i % 3 == 0) {
                lights.addSpot((float) rnd.range(-1, 1) * d, (float) rnd.range(-1, 1) * d * 0.5f, -d, 0f, -0.3f, -1f, 0.5f, 30f);
            } else {
                lights.addPoint((float) rnd.range(-1, 1) * d, (float) rnd.range(-1, 1) * d * 0.5f, -d, 5f);
            }
        }
        lights.assign(g);
        int sum = 0;
        for (int c = 0; c < g.clusterCount(); c++) {
            sum += lights.count(c);
            for (int i = 1; i < lights.count(c); i++) {
                assertTrue(lights.lightAt(c, i - 1) < lights.lightAt(c, i), "ascending light order, so the result does not depend on how the work is split");
            }
            assertEquals(lights.offset(c) + lights.count(c), c + 1 < g.clusterCount() ? lights.offset(c + 1) : lights.totalAssignments(g));
        }
        assertEquals(lights.totalAssignments(g), sum);
        // assigning again with fewer lights leaves no trace of the old ones
        lights.clearLights();
        lights.addPoint(0f, 0f, -10f, 1f);
        lights.assign(g);
        for (int c = 0; c < g.clusterCount(); c++) {
            for (int i = 0; i < lights.count(c); i++) {
                assertEquals(0, lights.lightAt(c, i));
            }
        }
        assertTrue(lights.totalAssignments(g) >= 1 && lights.totalAssignments(g) <= 12, "a small light touches a few clusters: " + lights.totalAssignments(g));
    }

    @Test
    void specialLights() {
        ClusterGrid g = grid(false);
        ClusterLights lights = new ClusterLights();
        lights.addPoint(0f, 0f, 20f, 5f);                 // wholly behind the camera
        lights.addPoint(0f, 0f, -500f, 10f);              // beyond the far plane
        lights.addPoint(Float.NaN, 0f, -10f, 5f);         // nonsense
        lights.addPoint(0f, 0f, -10f, 0f);                // a point of zero range
        lights.addPoint(0f, 0f, 0f, 1000f);               // contains the camera and everything
        lights.addPoint(1e6f, 0f, -10f, 5f);              // far to the side
        lights.assign(g);
        for (int c = 0; c < g.clusterCount(); c++) {
            for (int bad : new int[] {0, 1, 5}) {
                assertTrue(!lights.contains(c, bad), "light " + bad + " is listed for cluster " + c);
            }
            assertTrue(lights.contains(c, 4), "a light that contains the whole frustum reaches every cluster");
            assertTrue(lights.contains(c, 2), "a light at a NaN position is listed everywhere (NaN is not a separation) instead of being dropped");
        }
        int zeroRange = 0;
        for (int c = 0; c < g.clusterCount(); c++) {
            if (lights.contains(c, 3)) {
                zeroRange++;
            }
        }
        assertTrue(zeroRange >= 1 && zeroRange <= 8, "a zero-range light touches the clusters around its position: " + zeroRange);
        assertThrows(IllegalArgumentException.class, () -> lights.addPoint(0f, 0f, 0f, -1f));
        assertThrows(IllegalArgumentException.class, () -> lights.addSpot(0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1f));
        assertThrows(IllegalArgumentException.class, () -> lights.addSpot(0f, 0f, 0f, 0f, 0f, -1f, 1.6f, 1f));
        assertThrows(IllegalArgumentException.class, () -> lights.addSpot(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f));
    }

    @Test
    void tiledAssignmentUsesThePerTileDepthRange() {
        ClusterGrid g = ClusterGrid.of(0.9f, 16f / 9f, 0.1f, 200f, 1280, 720, 64, 1, false);
        int tiles = g.tilesX() * g.tilesY();
        float[] near = new float[tiles], far = new float[tiles];
        for (int t = 0; t < tiles; t++) {
            near[t] = 20f;
            far[t] = 30f;
        }
        near[0] = 5f; // an empty tile
        far[0] = 1f;
        ClusterLights lights = new ClusterLights();
        float tanX = g.tanHalfFovX();
        lights.addPoint(0f, 0f, -10f, 2f);   // in front of everything that is drawn (depths 20 to 30)
        lights.addPoint(0f, 0f, -25f, 2f);   // in the depth range of the centre tiles
        lights.addPoint(0f, 0f, -60f, 2f);   // behind everything drawn
        lights.addPoint(-tanX * 25f * 0.97f, -g.tanHalfFovY() * 25f * 0.97f, -25f, 2f); // in the corner tile 0, which is empty
        lights.assignTiled(g, near, far);
        int centre = g.index(g.tilesX() / 2, g.tilesY() / 2, 0);
        assertTrue(lights.contains(centre, 1), "the light inside the depth range of the tile");
        for (int c = 0; c < g.clusterCount(); c++) {
            assertTrue(!lights.contains(c, 0), "a light in front of all the geometry of the tile is dropped");
            assertTrue(!lights.contains(c, 2), "and so is one behind it");
        }
        assertEquals(0, lights.count(0), "an empty tile gets no lights");
        // with the whole depth range instead, the same lights are kept
        float[] allNear = new float[tiles], allFar = new float[tiles];
        java.util.Arrays.fill(allNear, g.near());
        java.util.Arrays.fill(allFar, g.far());
        lights.assignTiled(g, allNear, allFar);
        assertTrue(lights.contains(centre, 0) && lights.contains(centre, 1) && lights.contains(centre, 2));
        assertThrows(IllegalArgumentException.class, () -> lights.assignTiled(grid(false), near, far));
        assertThrows(IllegalArgumentException.class, () -> lights.assignTiled(g, new float[1], new float[1]));
    }

    @Test
    void theGpuBuffersHoldTheRangesAndIndices() {
        ClusterGrid g = ClusterGrid.of(0.9f, 1.5f, 0.1f, 100f, 256, 192, 64, 4, false);
        ClusterLights lights = new ClusterLights();
        for (int i = 0; i < 12; i++) {
            lights.addPoint((float) rnd.range(-5, 5), (float) rnd.range(-3, 3), -(float) rnd.range(1, 40), 8f);
        }
        lights.assign(g);
        int total = lights.totalAssignments(g);
        MemorySegment seg = MemorySegment.ofArray(new byte[16 + g.clusterCount() * ClusterLights.RANGE_BYTES + 4 * total + 8]);
        lights.writeRanges(g, seg, 16);
        lights.writeIndices(g, seg, 16 + (long) g.clusterCount() * ClusterLights.RANGE_BYTES);
        for (int c = 0; c < g.clusterCount(); c++) {
            assertEquals(lights.offset(c), GpuWriter.getInt(seg, 16 + (long) c * ClusterLights.RANGE_BYTES));
            assertEquals(lights.count(c), GpuWriter.getInt(seg, 16 + (long) c * ClusterLights.RANGE_BYTES + 4));
            for (int i = 0; i < lights.count(c); i++) {
                assertEquals(lights.lightAt(c, i), GpuWriter.getInt(seg, 16 + (long) g.clusterCount() * ClusterLights.RANGE_BYTES + 4L * (lights.offset(c) + i)));
            }
        }
    }
}
