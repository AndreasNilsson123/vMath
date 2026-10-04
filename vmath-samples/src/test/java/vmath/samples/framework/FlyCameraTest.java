package vmath.samples.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;

/**
 * Tests of the fly camera and of the scripted flight that makes the benchmark repeatable.
 *
 * <p><b>Thread safety.</b> Each test builds its own camera; the tests may run in parallel.
 */
class FlyCameraTest {

    private static FlyCamera camera() {
        return new FlyCamera(new Vec3f(1f, 2f, 3f), 0.5f, -0.1f, 1f, 0.5f, 1000f, 10f, 50f);
    }

    @Test
    void theScriptedFlightDependsOnTheFrameNumberOnly() {
        FlyCamera a = camera(), b = camera();
        a.move(100f, 50f, 7f);
        a.look(300, 200);
        a.scripted(250, 100f, 30f);
        b.scripted(250, 100f, 30f);
        Cameraf ca = a.camera(1.5f), cb = b.camera(1.5f);
        assertEquals(cb.position(), ca.position());
        assertEquals(cb.viewProjection(), ca.viewProjection());
    }

    @Test
    void theScriptedFlightMovesWithTheFrame() {
        FlyCamera c = camera();
        c.scripted(0, 100f, 30f);
        Vec3f first = c.camera(1f).position();
        c.scripted(100, 100f, 30f);
        assertNotEquals(first, c.camera(1f).position());
    }

    @Test
    void movingForwardFollowsTheHeading() {
        FlyCamera c = new FlyCamera(Vec3f.ZERO, 0f, 0f, 1f, 0.5f, 100f, 1f, 1f);
        c.move(10f, 0f, 0f);
        Vec3f p = c.camera(1f).position();
        assertEquals(0f, p.x(), 1e-5f);
        assertEquals(-10f, p.z(), 1e-5f, "yaw 0 looks along -z");
        c.move(0f, 5f, 2f);
        p = c.camera(1f).position();
        assertEquals(5f, p.x(), 1e-5f);
        assertEquals(2f, p.y(), 1e-5f);
    }

    @Test
    void resetPutsTheCameraBack() {
        FlyCamera c = camera();
        Vec3f start = c.camera(1f).position();
        c.move(5f, 5f, 5f);
        c.look(100, 100);
        c.reset();
        assertEquals(start, c.camera(1f).position());
    }

    @Test
    void walkingForwardStaysLevelEvenWhenLookingUp() {
        FlyCamera c = camera();
        c.look(0, -1_000_000);
        c.move(10f, 0f, 0f);
        assertEquals(2f, c.camera(1f).position().y(), 1e-5f);
    }
}
