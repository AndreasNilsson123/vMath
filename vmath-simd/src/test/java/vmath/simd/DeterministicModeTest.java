package vmath.simd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vmath.bulk.KernelSelector;
import vmath.bulk.MatrixKernel;
import vmath.bulk.MatrixKernels;
import vmath.spatial.FrustumKernels;

/**
 * {@code -Dvmath.deterministic=true} (DET-1): with the SIMD provider present, every selector gives the
 * scalar kernel, and the products are then identical to the bit with the scalar kernel and with a
 * class path that has no vector module at all.
 */
class DeterministicModeTest {

    @AfterEach
    void clear() {
        System.clearProperty(KernelSelector.DETERMINISTIC);
        System.clearProperty("vmath.matrixKernel");
        System.clearProperty("vmath.frustumKernel");
    }

    private static float[] products(MatrixKernel kernel, int n) {
        SplittableRandom r = new SplittableRandom(99);
        float[] a = new float[n * 16], b = new float[n * 16], out = new float[n * 16];
        for (int i = 0; i < a.length; i++) {
            a[i] = (float) (r.nextDouble() * 4 - 2);
            b[i] = (float) (r.nextDouble() * 4 - 2);
        }
        kernel.multiply(a, 0, b, 0, out, 0, n);
        return out;
    }

    @Test
    void theSimdKernelIsTheDefaultAndThePropertyTurnsItOff() {
        assertEquals("simd", MatrixKernels.best().name());
        assertEquals("simd", FrustumKernels.best().name());
        System.setProperty(KernelSelector.DETERMINISTIC, "true");
        assertTrue(KernelSelector.isDeterministic());
        assertEquals("scalar", MatrixKernels.best().name());
        assertEquals("scalar", FrustumKernels.best().name());
    }

    @Test
    void theModeWinsOverANamedKernelAndOnlyTrueTurnsItOn() {
        System.setProperty(KernelSelector.DETERMINISTIC, "true");
        System.setProperty("vmath.matrixKernel", "simd");
        System.setProperty("vmath.frustumKernel", "simd");
        assertEquals("scalar", MatrixKernels.best().name());
        assertEquals("scalar", FrustumKernels.best().name());
        for (String off : new String[] {"false", "", "yes", "0"}) {
            System.setProperty(KernelSelector.DETERMINISTIC, off);
            assertFalse(KernelSelector.isDeterministic(), off);
        }
        System.setProperty(KernelSelector.DETERMINISTIC, "TRUE");
        assertTrue(KernelSelector.isDeterministic());
    }

    @Test
    void underTheModeTheProductsAreTheBitsOfTheScalarKernel() {
        float[] reference = products(MatrixKernels.scalar(), 2000);
        System.setProperty(KernelSelector.DETERMINISTIC, "true");
        float[] deterministic = products(MatrixKernels.best(), 2000);
        assertEquals(Arrays.toString(reference), Arrays.toString(deterministic));
        assertArrayEquals(reference, deterministic);
        // without the mode the vector kernel is allowed to differ in the last bit; it is not required to, so that is not asserted
    }
}
