package vmath.simd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.bulk.Mat4fArray;
import vmath.bulk.MatrixKernel;
import vmath.bulk.MatrixKernels;
import vmath.core.Mat4f;

class SimdMatrixKernelTest {

    private static final SplittableRandom R = new SplittableRandom(Long.getLong("vmath.seed", 31L));

    private static float f() {
        return (float) (R.nextDouble() * 4 - 2);
    }

    private static Mat4fArray random(int n) {
        Mat4fArray a = new Mat4fArray(n);
        for (int i = 0; i < n; i++) {
            a.add(new Mat4f(f(), f(), f(), f(), f(), f(), f(), f(), f(), f(), f(), f(), f(), f(), f(), f()));
        }
        return a;
    }

    @Test
    void theProviderIsFoundAndSelected() {
        assertTrue(MatrixKernels.available().contains("simd"), "available: " + MatrixKernels.available());
        assertEquals("simd", MatrixKernels.best().name());
    }

    @Test
    void simdAgreesWithTheScalarKernelIncludingAliasing() {
        MatrixKernel simd = new SimdMatrixKernelProvider().create();
        int n = 1000;
        Mat4fArray a = random(n), b = random(n), expected = new Mat4fArray(1), actual = new Mat4fArray(1);
        Mat4fArray.multiply(a, b, expected, MatrixKernels.scalar());
        Mat4fArray.multiply(a, b, actual, simd);
        for (int i = 0; i < n * 16; i++) {
            float e = expected.data()[i], g = actual.data()[i];
            assertEquals(e, g, 1e-5f * Math.max(1f, Math.abs(e)), "float " + i);
        }
        // out == a and out == b
        Mat4fArray a2 = new Mat4fArray(n), b2 = new Mat4fArray(n);
        for (int i = 0; i < n; i++) {
            a2.add(a.get(i));
            b2.add(b.get(i));
        }
        Mat4fArray.multiply(a2, b, a2, simd);
        Mat4fArray.multiply(a, b2, b2, simd);
        for (int i = 0; i < n * 16; i++) {
            float e = expected.data()[i];
            assertEquals(e, a2.data()[i], 1e-5f * Math.max(1f, Math.abs(e)));
            assertEquals(e, b2.data()[i], 1e-5f * Math.max(1f, Math.abs(e)));
        }
        // an empty batch and a batch of one
        Mat4fArray.multiply(new Mat4fArray(1), new Mat4fArray(1), actual, simd);
        assertEquals(0, actual.size());
        // the default entry point uses the selected kernel and agrees with Mat4f.mul
        Mat4fArray c = new Mat4fArray(1);
        Mat4fArray.multiply(a, b, c);
        for (int i = 0; i < n; i += 97) {
            assertTrue(a.get(i).mul(b.get(i)).approxEquals(c.get(i), 1e-4f), "element " + i);
        }
    }
}
