package vmath.simd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernels;

/**
 * PERF-3 / TD-32: the {@code "simd"} frustum kernel must not pay for the JIT warm-up of the Vector API on the
 * render thread. It culls with the scalar kernel (bit-identical, allocation-free) until the vector kernel has
 * been seen to be faster.
 */
class SimdWarmUpTest {

    @BeforeEach
    void cold() {
        SimdWarmUp.resetForTest();
    }

    @AfterEach
    void clear() {
        System.clearProperty(SimdWarmUp.PROPERTY);
        SimdWarmUp.resetForTest();
    }

    private static BoundsArray boxes(int n, SplittableRandom r) {
        BoundsArray b = new BoundsArray(n);
        for (int i = 0; i < n; i++) {
            float x = (float) (r.nextDouble() * 60 - 30), y = (float) (r.nextDouble() * 60 - 30), z = (float) (-r.nextDouble() * 120);
            b.add(new Aabbf(x, y, z, x + 1 + (float) r.nextDouble() * 3, y + 1 + (float) r.nextDouble() * 3, z + 1 + (float) r.nextDouble() * 3));
        }
        return b;
    }

    private static Frustumf frustum(SplittableRandom r) {
        Mat4f vp = Mat4f.perspective(0.5f + (float) r.nextDouble(), 1.2f, 0.1f, 100f, ClipSpace.OPENGL)
                .mul(Mat4f.lookAt(new Vec3f((float) r.nextDouble() * 4, 0, 5), new Vec3f(0, 0, -50), Vec3f.UNIT_Y));
        return Frustumf.fromViewProjection(vp, DepthRange.of(ClipSpace.OPENGL));
    }

    @Test
    void theKernelStaysBitIdenticalToTheScalarOneBeforeAndAfterTheSwitch() {
        SplittableRandom r = new SplittableRandom(7);
        FrustumKernel kernel = FrustumKernels.best();
        assertEquals("simd", kernel.name());
        FrustumKernel scalar = FrustumKernels.scalar();
        BoundsArray b = boxes(300, r);
        for (int i = 0; i < 400; i++) {
            Frustumf f = frustum(r);
            VisibilitySet expected = new VisibilitySet(300), actual = new VisibilitySet(300);
            expected.setAll(1);
            actual.setAll(1);
            scalar.cull(f, b, expected);
            kernel.cull(f, b, actual);
            assertArrayEquals(expected.words(), actual.words(), "call " + i + (SimdWarmUp.isFrustumKernelReady() ? " (vector)" : " (scalar)"));
        }
    }

    @Test
    void theFirstCallsDoNotAllocateOnTheCallingThread() {
        SplittableRandom r = new SplittableRandom(8);
        BoundsArray b = boxes(256, r);
        Frustumf f = frustum(r);
        VisibilitySet v = new VisibilitySet(256);
        FrustumKernel kernel = FrustumKernels.best();
        ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        for (int i = 0; i < 50; i++) { // the scalar kernel's own first calls
            v.setAll(1);
            kernel.cull(f, b, v);
        }
        long before = mx.getThreadAllocatedBytes(id);
        int calls = 1000;
        for (int i = 0; i < calls; i++) {
            v.setAll(1);
            kernel.cull(f, b, v);
        }
        long perCall = (mx.getThreadAllocatedBytes(id) - before) / calls;
        // the vector kernel allocates 100 to 200 kB per call while it is cold; the guard is far below that and far above any bookkeeping
        assertTrue(perCall < 256, "allocated " + perCall + " B per call on the calling thread in the first 1000 calls");
    }

    @Test
    void theWarmUpOffUsesTheVectorKernelFromTheStart() {
        System.setProperty(SimdWarmUp.PROPERTY, "off");
        assertFalse(SimdWarmUp.isFrustumKernelReady());
        FrustumKernels.best();
        assertTrue(SimdWarmUp.isFrustumKernelReady());
    }

    @Test
    void theSynchronousWarmUpHasDecidedWhenTheKernelIsReturned() {
        System.setProperty(SimdWarmUp.PROPERTY, "sync");
        FrustumKernels.best();
        // on a machine where the vector kernel is faster once compiled, it is ready now; if it is not, the scalar kernel stays; both are right, so only the result is checked
        SplittableRandom r = new SplittableRandom(9);
        BoundsArray b = boxes(100, r);
        Frustumf f = frustum(r);
        VisibilitySet expected = new VisibilitySet(100), actual = new VisibilitySet(100);
        expected.setAll(1);
        actual.setAll(1);
        FrustumKernels.scalar().cull(f, b, expected);
        FrustumKernels.best().cull(f, b, actual);
        assertArrayEquals(expected.words(), actual.words());
    }

    @Test
    void warmUpReportsWhatItKnowsAndDoesNotRunWithoutTime() {
        assertFalse(SimdWarmUp.warmUp(0), "nothing is known yet and no time was given");
        boolean ready = SimdWarmUp.warmUp(10_000);
        assertEquals(ready, SimdWarmUp.isFrustumKernelReady() && SimdWarmUp.isMatrixKernelReady());
        assertEquals(ready, SimdWarmUp.warmUp(0), "asking again does not run anything");
    }
}
