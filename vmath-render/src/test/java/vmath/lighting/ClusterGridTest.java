package vmath.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.camera.Cameraf;

class ClusterGridTest {

    final Rnd rnd = Rnd.create();

    private static Cameraf camera(DepthRange depth, float far) {
        return new Cameraf(Vec3f.ZERO, Quatf.IDENTITY, 1.0f, 16f / 9f, 0.1f, far, depth);
    }

    @Test
    void sliceBoundariesAreExponentialAndCoverNearToFar() {
        ClusterGrid g = ClusterGrid.of(1f, 1.5f, 0.2f, 500f, 1280, 720, 64, 24, false);
        assertEquals(0.2f, g.sliceBoundary(0));
        assertEquals(500f, g.sliceBoundary(24));
        float ratio = (float) Math.pow(500f / 0.2f, 1.0 / 24);
        for (int k = 0; k < 24; k++) {
            assertEquals(ratio, g.sliceBoundary(k + 1) / g.sliceBoundary(k), ratio * 1e-4f, "every slice is the same factor deeper than the one before");
            assertEquals(k, g.sliceOf(g.sliceBoundary(k) * 1.001f), "just inside the slice");
            assertEquals(k, g.sliceOf(g.sliceBoundary(k + 1) * 0.999f));
        }
        assertEquals(0, g.sliceOf(0.01f), "before near clamps to the first slice");
        assertEquals(23, g.sliceOf(1e6f), "beyond far clamps to the last");
        assertEquals(0, g.sliceOf(Float.NaN));
        // the formula the shader uses
        for (int i = 0; i < 2000; i++) {
            float d = (float) Math.exp(rnd.range(Math.log(0.2), Math.log(500)));
            int shader = (int) Math.floor(Math.log(d) * g.sliceScale() + g.sliceBias());
            assertEquals(Math.max(0, Math.min(23, shader)), g.sliceOf(d), "floor(ln(depth) * sliceScale + sliceBias) is the slice");
        }
    }

    @Test
    void ndcDepthOfEveryConventionFindsTheSameSlice() {
        for (DepthRange depth : DepthRange.values()) {
            for (float far : new float[] {400f, Float.POSITIVE_INFINITY}) {
                Cameraf cam = camera(depth, far);
                ClusterGrid g = ClusterGrid.of(cam, 1280, 720, 64, 24, 300f, false);
                for (int i = 0; i < 500; i++) {
                    float d = (float) Math.exp(rnd.range(Math.log(0.12), Math.log(280)));
                    float ndc = cam.project(new Vec3f(0f, 0f, -d)).z();
                    int expected = g.sliceOf(d);
                    int got = g.sliceOfNdcDepth(cam, ndc);
                    // the round trip through the projection has float error: allow a neighbouring slice only right at a boundary
                    if (got != expected) {
                        float back = cam.linearizeDepth(ndc);
                        assertTrue(Math.abs(back - d) / d < 1e-3f, depth + " far " + far + ": depth " + d + " came back as " + back);
                        assertTrue(Math.abs(got - expected) == 1, "at most the neighbouring slice");
                    }
                }
            }
        }
    }

    @Test
    void aViewPositionLiesInsideTheBoundsOfItsCluster() {
        for (boolean yDown : new boolean[] {false, true}) {
            for (int[] size : new int[][] {{1280, 720}, {1000, 563}, {64, 64}, {1920, 1080}}) {
                for (int tile : new int[] {16, 64, 100}) {
                    ClusterGrid g = ClusterGrid.of(0.9f, size[0] / (float) size[1], 0.1f, 300f, size[0], size[1], tile, 16, yDown);
                    float[] box = new float[6];
                    int found = 0;
                    for (int i = 0; i < 400; i++) {
                        float d = (float) Math.exp(rnd.range(Math.log(0.1), Math.log(300)));
                        float x = (float) rnd.range(-1, 1) * d * g.tanHalfFovX(), y = (float) rnd.range(-1, 1) * d * g.tanHalfFovY();
                        int c = g.clusterOfViewPosition(x, y, -d);
                        assertTrue(c >= 0 && c < g.clusterCount(), "a point inside the frustum has a cluster");
                        g.bounds(c, box, 0);
                        float eps = 2e-4f * d;
                        assertTrue(x >= box[0] - eps && x <= box[3] + eps && y >= box[1] - eps && y <= box[4] + eps && -d >= box[2] - eps && -d <= box[5] + eps,
                                "cluster " + c + " bounds " + java.util.Arrays.toString(box) + " do not contain (" + x + ", " + y + ", " + -d + ") yDown " + yDown + " " + size[0] + "x"
                                        + size[1] + " tile " + tile);
                        found++;
                    }
                    assertEquals(400, found);
                }
            }
        }
    }

    @Test
    void thePixelRouteAgreesWithTheViewPositionRoute() {
        for (boolean yDown : new boolean[] {false, true}) {
            Cameraf cam = camera(DepthRange.ZERO_TO_ONE, 400f);
            ClusterGrid g = ClusterGrid.of(cam, 1280, 720, 64, 24, 300f, yDown);
            for (int i = 0; i < 1000; i++) {
                float d = (float) Math.exp(rnd.range(Math.log(0.2), Math.log(250)));
                Vec3f p = new Vec3f((float) rnd.range(-0.99, 0.99) * d * g.tanHalfFovX(), (float) rnd.range(-0.99, 0.99) * d * g.tanHalfFovY(), -d);
                Vec3f ndc = cam.project(p);
                float px = (ndc.x() * 0.5f + 0.5f) * 1280f;
                float py = yDown ? (0.5f - ndc.y() * 0.5f) * 720f : (ndc.y() * 0.5f + 0.5f) * 720f;
                int viaPixel = g.clusterOf(px, py, d);
                int viaView = g.clusterOfViewPosition(p.x(), p.y(), p.z());
                if (viaPixel != viaView) { // only a hair from a tile edge can differ, through float rounding of the projection
                    int dx = Math.abs(viaPixel % g.tilesX() - viaView % g.tilesX()), dy = Math.abs((viaPixel / g.tilesX()) % g.tilesY() - (viaView / g.tilesX()) % g.tilesY());
                    assertTrue(dx <= 1 && dy <= 1 && viaPixel / (g.tilesX() * g.tilesY()) - viaView / (g.tilesX() * g.tilesY()) <= 1, "pixel and view routes disagree by more than a boundary");
                }
            }
        }
    }

    @Test
    void outsideTheFrustumHasNoCluster() {
        ClusterGrid g = ClusterGrid.of(1f, 1.5f, 0.1f, 100f, 800, 600, 32, 8, false);
        assertEquals(-1, g.clusterOfViewPosition(0f, 0f, 1f), "behind the camera");
        assertEquals(-1, g.clusterOfViewPosition(0f, 0f, 0f), "in the camera plane");
        assertEquals(-1, g.clusterOfViewPosition(100f, 0f, -1f), "far to the side");
        assertEquals(-1, g.clusterOfViewPosition(0f, 100f, -1f), "far above");
        assertEquals(-1, g.clusterOfViewPosition(Float.NaN, 0f, -1f));
        assertTrue(g.clusterOfViewPosition(0f, 0f, -1000f) >= 0, "beyond far clamps to the last slice");
        assertTrue(g.clusterOfViewPosition(0f, 0f, -1e-3f) >= 0, "nearer than near clamps to the first slice");
    }

    @Test
    void tilesCoverTheScreenIncludingPartialOnes() {
        for (boolean yDown : new boolean[] {false, true}) {
            ClusterGrid g = ClusterGrid.of(1f, 1.5f, 0.1f, 100f, 1000, 563, 64, 4, yDown);
            assertEquals(16, g.tilesX());
            assertEquals(9, g.tilesY());
            float[] r = new float[2];
            g.columnRange(0, r);
            assertEquals(-1f, r[0]);
            g.columnRange(15, r);
            assertEquals(1f, r[1], 0f, "the last column ends at the screen edge although it is only 40 pixels wide");
            assertEquals(-1f + 2f * 960f / 1000f, r[0], 1e-6f);
            float top = -2f, bottom = 2f;
            float previous = yDown ? 2f : -2f;
            for (int row = 0; row < 9; row++) {
                g.rowRange(row, r);
                assertTrue(r[0] < r[1]);
                top = Math.max(top, r[1]);
                bottom = Math.min(bottom, r[0]);
                if (yDown) {
                    assertTrue(r[1] <= previous + 1e-6f, "rows run downwards");
                    previous = r[0];
                } else {
                    assertTrue(r[0] >= previous - 1e-6f, "rows run upwards");
                    previous = r[1];
                }
            }
            assertEquals(1f, top, 1e-6f);
            assertEquals(-1f, bottom, 1e-6f);
        }
    }

    @Test
    void indexIsSlicesOutermost() {
        ClusterGrid g = ClusterGrid.of(1f, 1.5f, 0.1f, 100f, 800, 600, 100, 5, false);
        assertEquals(8 * 6 * 5, g.clusterCount());
        assertEquals(0, g.index(0, 0, 0));
        assertEquals(1, g.index(1, 0, 0));
        assertEquals(8, g.index(0, 1, 0));
        assertEquals(48, g.index(0, 0, 1));
        assertEquals(g.clusterCount() - 1, g.index(7, 5, 4));
        float[] all = new float[g.clusterCount() * 6];
        g.fillBounds(all);
        float[] one = new float[6];
        g.bounds(g.index(3, 2, 4), one, 0);
        for (int k = 0; k < 6; k++) {
            assertEquals(one[k], all[g.index(3, 2, 4) * 6 + k]);
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClusterGrid.of(1f, 1.5f, 0f, 100f, 800, 600, 32, 8, false));
        assertThrows(IllegalArgumentException.class, () -> ClusterGrid.of(1f, 1.5f, 1f, 1f, 800, 600, 32, 8, false));
        assertThrows(IllegalArgumentException.class, () -> ClusterGrid.of(1f, 1.5f, 0.1f, Float.POSITIVE_INFINITY, 800, 600, 32, 8, false));
        assertThrows(IllegalArgumentException.class, () -> ClusterGrid.of(1f, 1.5f, 0.1f, 100f, 0, 600, 32, 8, false));
        assertThrows(IllegalArgumentException.class, () -> ClusterGrid.of(1f, 1.5f, 0.1f, 100f, 800, 600, 0, 8, false));
        assertThrows(IllegalArgumentException.class, () -> ClusterGrid.of(1f, 1.5f, 0.1f, 100f, 800, 600, 32, 0, false));
        assertThrows(IllegalArgumentException.class, () -> ClusterGrid.of(3.2f, 1.5f, 0.1f, 100f, 800, 600, 32, 8, false));
        // the camera's own far plane limits the grid
        Cameraf cam = camera(DepthRange.ZERO_TO_ONE, 50f);
        assertEquals(50f, ClusterGrid.of(cam, 800, 600, 32, 8, 500f, false).far());
    }
}
