package vmath.samples.demos.lights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.camera.Cameraf;
import vmath.camera.ClusterGrid;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.util.Rng;

/**
 * Tests of the light field with the library's cluster assignment: no light that reaches a point is
 * missing from the cluster of that point, and the lights of the same time are the same lights.
 *
 * <p><b>Thread safety.</b> Each test builds its own field; the tests may run in parallel.
 */
class LightFieldTest {

    private static Cameraf camera() {
        return Cameraf.lookingAt(new Vec3f(0f, 3f, 40f), new Vec3f(0f, 2f, 0f), Vec3f.UNIT_Y, 1.0f, 16f / 9f, 0.1f, 300f, DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    @Test
    void everyLightThatReachesAPointIsListedInTheClusterOfThatPoint() {
        Cameraf cam = camera();
        ClusterGrid grid = ClusterGrid.of(cam, 640, 360, 64, 24, 300f, false);
        LightField field = new LightField(300, 60f, 5);
        field.assign(1.5, cam.view(), grid);
        Rng rng = new Rng(9);
        int checked = 0;
        for (int n = 0; n < 4000; n++) {
            float px = (float) rng.nextDouble(0, 640), py = (float) rng.nextDouble(0, 360), depth = (float) rng.nextDouble(1.0, 120.0);
            float x = (px / 640f * 2f - 1f) * depth * grid.tanHalfFovX(), y = (py / 360f * 2f - 1f) * depth * grid.tanHalfFovY();
            float vx = x, vy = y, vz = -depth;
            // the world position of this view-space point
            Vec3f world = cam.view().invert().transformProject(new Vec3f(vx, vy, vz));
            int cluster = grid.clusterOfViewPosition(vx, vy, vz);
            for (int l = 0; l < field.count(); l++) {
                float[] d = field.lightData();
                float dx = d[l * 8] - world.x(), dy = d[l * 8 + 1] - world.y(), dz = d[l * 8 + 2] - world.z();
                if (dx * dx + dy * dy + dz * dz < (d[l * 8 + 3] - 0.01f) * (d[l * 8 + 3] - 0.01f)) {
                    assertTrue(field.assignment().contains(cluster, l), "light " + l + " reaches the point but is not listed for cluster " + cluster);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "the test looked at " + checked + " light and point pairs");
    }

    @Test
    void theSameTimeGivesTheSameLights() {
        Cameraf cam = camera();
        ClusterGrid grid = ClusterGrid.of(cam, 320, 180, 64, 12, 300f, false);
        LightField a = new LightField(50, 30f, 3), b = new LightField(50, 30f, 3);
        a.assign(2.0, cam.view(), grid);
        b.assign(2.0, cam.view(), grid);
        for (int i = 0; i < 50 * 8; i++) {
            assertEquals(a.lightData()[i], b.lightData()[i], 0f);
        }
        assertEquals(a.assignment().totalAssignments(grid), b.assignment().totalAssignments(grid));
    }

    @Test
    void theLightsMoveWithTimeButStayNearTheirHomes() {
        Cameraf cam = camera();
        ClusterGrid grid = ClusterGrid.of(cam, 320, 180, 64, 12, 300f, false);
        LightField f = new LightField(20, 30f, 4);
        f.assign(0.0, cam.view(), grid);
        float[] before = f.lightData().clone();
        f.assign(5.0, cam.view(), grid);
        boolean moved = false;
        for (int i = 0; i < 20; i++) {
            float dx = f.lightData()[i * 8] - before[i * 8], dz = f.lightData()[i * 8 + 2] - before[i * 8 + 2];
            assertTrue(Math.sqrt(dx * dx + dz * dz) <= 10.01f, "a light stays on its circle of radius up to 5");
            moved |= dx != 0f || dz != 0f;
            assertEquals(before[i * 8 + 1], f.lightData()[i * 8 + 1], 0f, "the height does not change");
        }
        assertTrue(moved);
    }
}
