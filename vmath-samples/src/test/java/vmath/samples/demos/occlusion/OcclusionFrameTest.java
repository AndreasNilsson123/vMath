package vmath.samples.demos.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.samples.framework.Scenes;

/**
 * Tests of the CPU side of the occlusion demo on a small city, without a window: the culling
 * removes boxes, never one that a ray from the camera can reach, and the threaded test gives the
 * same result as the library's stage.
 *
 * <p><b>Thread safety.</b> Each test builds its own frame state and scene; the tests may run in
 * parallel.
 */
class OcclusionFrameTest {

    private static final int BLOCKS = 16;

    private static Cameraf streetCamera(float z, float yaw) {
        float half = BLOCKS * Scenes.BLOCK_PITCH * 0.5f;
        float x = (BLOCKS / 2 + 1) * Scenes.BLOCK_PITCH - half;
        Vec3f eye = new Vec3f(x, 3f, z);
        Vec3f target = new Vec3f(x + (float) Math.sin(yaw) * 50f, 2f, z - (float) Math.cos(yaw) * 50f);
        return Cameraf.lookingAt(eye, target, Vec3f.UNIT_Y, 1.0f, 16f / 9f, 0.3f, 1500f, DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    @Test
    void theOcclusionCullingRemovesMostPropsAndNoRayFindsARemovedBoxVisible() {
        Scenes.Blocks city = Scenes.blocks(BLOCKS, 30);
        OcclusionFrame frame = new OcclusionFrame(city, 256, null, 1);
        long removed = 0;
        for (int i = 0; i < 12; i++) {
            float z = -150f + i * 22f;
            Cameraf cam = streetCamera(z, (float) Math.PI + 0.5f * (float) Math.sin(i));
            frame.cull(cam, 900, true);
            removed += frame.frustumCount() - frame.finalCount();
            frame.verify(cam);
            assertTrue(frame.finalCount() <= frame.frustumCount());
            assertTrue(frame.occluders().size() > 0, "a street has buildings next to it");
        }
        assertTrue(removed > 1000, "the buildings hide many of the props: " + removed + " boxes removed over 12 views");
        assertTrue(frame.checked() > 100, "the check looked at removed boxes: " + frame.checked());
        assertEquals(0, frame.violations());
    }

    @Test
    void withoutOcclusionTheResultIsTheFrustums() {
        Scenes.Blocks city = Scenes.blocks(BLOCKS, 10);
        OcclusionFrame frame = new OcclusionFrame(city, 128, null, 1);
        frame.cull(streetCamera(-100f, (float) Math.PI), 900, false);
        assertEquals(frame.frustumCount(), frame.finalCount());
    }

    @Test
    void severalThreadsRemoveExactlyTheBoxesThatOneThreadRemoves() {
        Scenes.Blocks city = Scenes.blocks(BLOCKS, 40);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            OcclusionFrame single = new OcclusionFrame(city, 256, null, 1);
            OcclusionFrame threaded = new OcclusionFrame(city, 256, pool, 3);
            for (int i = 0; i < 6; i++) {
                Cameraf cam = streetCamera(-120f + i * 40f, (float) Math.PI + 0.3f * i);
                single.cull(cam, 900, true);
                threaded.cull(cam, 900, true);
                assertEquals(single.finalCount(), threaded.finalCount(), "view " + i);
                VisibilitySet a = single.visible(), b = threaded.visible();
                for (int k = a.nextSetBit(0); k >= 0; k = a.nextSetBit(k + 1)) {
                    assertTrue(b.get(k), "view " + i + ": box " + k + " is removed by the threaded test only");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCameraInsideTheOpenSkyKeepsEverythingInTheFrustumThatNothingCovers() {
        Scenes.Blocks city = Scenes.blocks(BLOCKS, 5);
        OcclusionFrame frame = new OcclusionFrame(city, 128, null, 1);
        Cameraf above = Cameraf.lookingAt(new Vec3f(0f, 500f, 0f), new Vec3f(0f, 0f, 1f), Vec3f.UNIT_Y, 1.0f, 16f / 9f, 0.3f, 1500f, DepthRange.NEGATIVE_ONE_TO_ONE);
        frame.cull(above, 900, true);
        frame.verify(above);
        assertEquals(0, frame.violations());
    }
}
