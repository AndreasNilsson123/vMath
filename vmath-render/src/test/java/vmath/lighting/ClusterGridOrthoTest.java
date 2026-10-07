package vmath.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.camera.OrthoCameraf;
import vmath.core.ClipSpace;
import vmath.core.Quatf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;

/**
 * The cluster grid of an orthographic camera (CAM-13): tiles that are fixed rectangles, slices
 * linear in depth, the lookups against the arithmetic of the box, the light assignment against the
 * exact oracle (a cluster is a box, so a sphere touches it exactly when its distance to the box is
 * at most the radius), and the GLSL text.
 */
class ClusterGridOrthoTest {

    private final SplittableRandom rnd = new SplittableRandom(5);

    private float r(double lo, double hi) {
        return (float) (lo + (hi - lo) * rnd.nextDouble());
    }

    private static OrthoCameraf camera(DepthRange depth, float near, float far) {
        return new OrthoCameraf(Vec3f.ZERO, Quatf.IDENTITY, -16f, 16f, -9f, 9f, near, far, depth);
    }

    @Test
    void theTilesAreFixedRectanglesAndTheSlicesAreLinear() {
        OrthoCameraf cam = camera(DepthRange.ZERO_TO_ONE, 0.5f, 100.5f);
        ClusterGrid g = ClusterGrid.of(cam, 1920, 1080, 64, 20, 1000f, false);
        assertTrue(g.orthographic());
        assertEquals(30, g.tilesX());
        assertEquals(17, g.tilesY());
        assertEquals(100.5f, g.far(), "the far plane is the camera's when the given one is farther");
        for (int k = 0; k <= 20; k++) {
            assertEquals(0.5f + 5f * k, g.sliceBoundary(k), 1e-4f, "boundary " + k + " is linear");
        }
        float[] edges = new float[4], b = new float[6];
        for (int col = 0; col < g.tilesX(); col++) {
            for (int row = 0; row < g.tilesY(); row++) {
                g.viewEdges(col, row, edges, 0);
                float x0 = -16f + 32f * Math.min(col * 64, 1920) / 1920f, x1 = -16f + 32f * Math.min((col + 1) * 64, 1920) / 1920f;
                float y0 = -9f + 18f * Math.min(row * 64, 1080) / 1080f, y1 = -9f + 18f * Math.min((row + 1) * 64, 1080) / 1080f;
                assertEquals(x0, edges[0], 1e-4f, "left edge of column " + col);
                assertEquals(x1, edges[1], 1e-4f, "right edge of column " + col);
                assertEquals(y0, edges[2], 1e-4f, "bottom edge of row " + row);
                assertEquals(y1, edges[3], 1e-4f, "top edge of row " + row);
                // every slice: the same box in x and y, only the depth moves
                for (int s : new int[] {0, 7, 19}) {
                    g.bounds(g.index(col, row, s), b, 0);
                    assertEquals(x0, b[0], 1e-4f);
                    assertEquals(y0, b[1], 1e-4f);
                    assertEquals(-g.sliceBoundary(s + 1), b[2], 1e-4f);
                    assertEquals(x1, b[3], 1e-4f);
                    assertEquals(y1, b[4], 1e-4f);
                    assertEquals(-g.sliceBoundary(s), b[5], 1e-4f);
                }
            }
        }
    }

    @Test
    void theLookupsAgreeWithTheBoxInEveryConvention() {
        for (DepthRange depth : DepthRange.values()) {
            for (boolean yDown : new boolean[] {false, true}) {
                OrthoCameraf cam = camera(depth, r(-1, 1), r(50, 200));
                ClusterGrid g = ClusterGrid.of(cam, 1280, 720, 32, 16, 1000f, yDown);
                for (int i = 0; i < 500; i++) {
                    float x = r(-16, 16), y = r(-9, 9), d = r(cam.near(), cam.far());
                    Vec3f ndc = cam.project(new Vec3f(x, y, -d));
                    float px = (ndc.x() * 0.5f + 0.5f) * 1280f, py = yDown ? (0.5f - ndc.y() * 0.5f) * 720f : (ndc.y() * 0.5f + 0.5f) * 720f;
                    int fromView = g.clusterOfViewPosition(x, y, -d);
                    int fromPixel = g.clusterOf(px, py, d);
                    assertEquals(fromPixel, fromView, depth + " yDown " + yDown + ": the view position and the pixel with its depth find the same cluster");
                    int fromDepthBuffer = g.sliceOfNdcDepth(cam, ndc.z());
                    assertEquals(g.sliceOf(d), fromDepthBuffer, depth + ": the slice of the stored depth");
                    // the cluster's box contains the position
                    float[] b = new float[6];
                    g.bounds(fromView, b, 0);
                    assertTrue(x >= b[0] - 1e-3f && x <= b[3] + 1e-3f && y >= b[1] - 1e-3f && y <= b[4] + 1e-3f && -d >= b[2] - 1e-3f && -d <= b[5] + 1e-3f, "inside its box");
                }
                assertEquals(-1, g.clusterOfViewPosition(17f, 0f, -5f), "outside the box in x");
                assertEquals(-1, g.clusterOfViewPosition(0f, -10f, -5f), "outside the box in y");
                assertTrue(g.clusterOfViewPosition(0f, 0f, cam.far() + 50f) >= 0, "depth beyond the far plane clamps to the last slice");
                assertTrue(g.clusterOfViewPosition(0f, 0f, 3f) >= 0, "a position behind the camera plane is a position like any other");
            }
        }
    }

    // a sphere against a box, exactly
    private static boolean touches(float[] b, float cx, float cy, float cz, float rad) {
        float ex = Math.max(Math.max(b[0] - cx, cx - b[3]), 0f), ey = Math.max(Math.max(b[1] - cy, cy - b[4]), 0f), ez = Math.max(Math.max(b[2] - cz, cz - b[5]), 0f);
        return ex * ex + ey * ey + ez * ez <= rad * rad;
    }

    @Test
    void theAssignmentIsExactBecauseAClusterIsABox() {
        long exact = 0, listed = 0, missing = 0, extra = 0;
        for (boolean yDown : new boolean[] {false, true}) {
            OrthoCameraf cam = camera(DepthRange.NEGATIVE_ONE_TO_ONE, 0.5f, 120f);
            ClusterGrid g = ClusterGrid.of(cam, 1600, 900, 64, 12, 1000f, yDown);
            ClusterLights lights = new ClusterLights();
            int n = 150;
            float[] x = new float[n], y = new float[n], z = new float[n], rad = new float[n];
            for (int i = 0; i < n; i++) {
                x[i] = r(-20, 20);
                y[i] = r(-12, 12);
                z[i] = -r(-5, 130);
                rad[i] = r(0.2, 8);
                lights.addPoint(x[i], y[i], z[i], rad[i]);
            }
            lights.assign(g);
            float[] b = new float[6];
            for (int c = 0; c < g.clusterCount(); c++) {
                g.bounds(c, b, 0);
                for (int l = 0; l < n; l++) {
                    boolean really = touches(b, x[l], y[l], z[l], rad[l]);
                    boolean in = lights.contains(c, l);
                    exact += really ? 1 : 0;
                    listed += in ? 1 : 0;
                    if (really && !in) {
                        missing++;
                    }
                    if (!really && in) {
                        extra++;
                    }
                }
            }
        }
        assertTrue(exact > 500, "the scene has pairs to find: " + exact);
        assertEquals(0, missing, "no light that touches a cluster is missing");
        assertEquals(0, extra, "and none is listed that does not (" + listed + " listed for " + exact + " exact)");
    }

    @Test
    void theTiledAssignmentWorksWithOneSlice() {
        OrthoCameraf cam = camera(DepthRange.ZERO_TO_ONE, 0.5f, 60f);
        ClusterGrid g = ClusterGrid.of(cam, 640, 360, 32, 1, 1000f, false);
        ClusterLights lights = new ClusterLights();
        lights.addPoint(0f, 0f, -10f, 3f);
        lights.addPoint(10f, 5f, -50f, 3f);
        int tiles = g.tilesX() * g.tilesY();
        float[] near = new float[tiles], far = new float[tiles];
        java.util.Arrays.fill(near, 5f);
        java.util.Arrays.fill(far, 20f);
        lights.assignTiled(g, near, far);
        int middle = g.index(g.columnOfPixel(320f), g.rowOfPixel(180f), 0);
        assertTrue(lights.contains(middle, 0), "the light at depth 10 reaches the tile whose depths are 5 to 20");
        int total = 0;
        for (int c = 0; c < g.clusterCount(); c++) {
            total += lights.count(c);
            assertFalse(lights.contains(c, 1), "the light at depth 50 is beyond every tile's depth range");
        }
        assertTrue(total > 0);
    }

    @Test
    void theGlslLookupHasNoLogarithmAndTheSameNumbers() {
        OrthoCameraf cam = camera(DepthRange.ZERO_TO_ONE, 1f, 101f);
        ClusterGrid g = ClusterGrid.of(cam, 1920, 1080, 64, 25, 1000f, ClipSpace.VULKAN);
        String glsl = g.glslLookup();
        assertFalse(glsl.contains("log("), "linear slices: " + glsl);
        assertTrue(glsl.contains("CLUSTER_SLICE_SCALE = " + g.sliceScale()), "the same scale as the CPU");
        assertTrue(glsl.contains("viewDepth * CLUSTER_SLICE_SCALE + CLUSTER_SLICE_BIAS"));
        // the CPU formula is the GLSL formula
        for (int i = 0; i < 100; i++) {
            float d = r(1, 101);
            int s = Math.min(24, Math.max(0, (int) Math.floor(d * g.sliceScale() + g.sliceBias())));
            assertEquals(s, g.sliceOf(d), 1.0, "depth " + d);
        }
    }

    @Test
    void thePerspectiveOnlyAccessorsRefuseAnOrthographicGridAndTheOtherWayRound() {
        ClusterGrid o = ClusterGrid.of(camera(DepthRange.ZERO_TO_ONE, 0f, 10f), 100, 100, 10, 4, 10f, false);
        assertThrows(IllegalStateException.class, o::tanHalfFovX);
        assertThrows(IllegalStateException.class, o::tanHalfFovY);
        assertThrows(IllegalStateException.class, () -> o.slopes(0, 0, new float[4], 0));
        ClusterGrid p = ClusterGrid.of(1f, 1.5f, 0.1f, 100f, 100, 100, 10, 4, false);
        assertFalse(p.orthographic());
        assertThrows(IllegalStateException.class, () -> p.viewEdges(0, 0, new float[4], 0));
        assertThrows(IllegalStateException.class, () -> p.viewBox(new float[4]));
        float[] box = new float[4];
        o.viewBox(box);
        assertEquals(-16f, box[0]);
        assertEquals(9f, box[3]);
        assertThrows(IllegalArgumentException.class, () -> ClusterGrid.of(camera(DepthRange.ZERO_TO_ONE, 0f, 10f), 0, 100, 10, 4, 10f, false), "an empty viewport");
        assertThrows(IllegalArgumentException.class, () -> ClusterGrid.of(camera(DepthRange.ZERO_TO_ONE, 5f, 10f), 100, 100, 10, 4, 5f, false), "a far plane at the near plane");
    }
}
