package vmath.simd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.bulk.Mat4fArray;
import vmath.bulk.MatrixKernel;
import vmath.bulk.MatrixKernels;
import vmath.bulk.QuatArray;
import vmath.bulk.Vec3fArray;
import vmath.bulk.Vec4fArray;
import vmath.core.Mat4f;
import vmath.core.Quatf;

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

    private static float[] randomFloats(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = f();
        }
        return a;
    }

    private static void assertClose(float[] expected, float[] actual, String what) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-5f * Math.max(1f, Math.abs(expected[i])), what + " float " + i);
        }
    }

    /** The three vector transforms against the scalar kernel for several counts and offsets, with guard floats around the output. */
    @Test
    void vectorTransformsAgreeWithTheScalarKernel() {
        MatrixKernel simd = new SimdMatrixKernelProvider().create();
        MatrixKernel scalar = MatrixKernels.scalar();
        for (int count : new int[] {0, 1, 2, 3, 4, 5, 17, 1000}) {
            for (int comps : new int[] {3, 4}) {
                float[] m = randomFloats(16 + 5);
                int mo = 5, so = 3, d = 7;
                float[] src = randomFloats(so + count * comps + 4);
                for (int kind = 0; kind < (comps == 4 ? 1 : 2); kind++) {
                    float[] expected = new float[d + count * comps + 6];
                    java.util.Arrays.fill(expected, 42f);
                    float[] actual = expected.clone();
                    if (comps == 4) {
                        scalar.transformVec4(m, mo, src, so, expected, d, count);
                        simd.transformVec4(m, mo, src, so, actual, d, count);
                    } else if (kind == 0) {
                        scalar.transformPositions(m, mo, src, so, expected, d, count);
                        simd.transformPositions(m, mo, src, so, actual, d, count);
                    } else {
                        scalar.transformDirections(m, mo, src, so, expected, d, count);
                        simd.transformDirections(m, mo, src, so, actual, d, count);
                    }
                    assertClose(expected, actual, "count " + count + " components " + comps + " kind " + kind);
                    // in place
                    float[] inPlace = new float[so + count * comps + 6];
                    float[] copy = src.clone();
                    System.arraycopy(src, 0, inPlace, 0, Math.min(src.length, inPlace.length));
                    float[] viaScalar = new float[inPlace.length];
                    System.arraycopy(inPlace, 0, viaScalar, 0, inPlace.length);
                    if (comps == 4) {
                        scalar.transformVec4(m, mo, inPlace.clone(), so, viaScalar, so, count);
                        simd.transformVec4(m, mo, inPlace, so, inPlace, so, count);
                    } else if (kind == 0) {
                        scalar.transformPositions(m, mo, inPlace.clone(), so, viaScalar, so, count);
                        simd.transformPositions(m, mo, inPlace, so, inPlace, so, count);
                    } else {
                        scalar.transformDirections(m, mo, inPlace.clone(), so, viaScalar, so, count);
                        simd.transformDirections(m, mo, inPlace, so, inPlace, so, count);
                    }
                    assertClose(viaScalar, inPlace, "in place, count " + count + " components " + comps + " kind " + kind);
                    assertEquals(copy.length, src.length);
                }
            }
        }
    }

    @Test
    void containersAgreeWithTheScalarKernel() {
        MatrixKernel simd = new SimdMatrixKernelProvider().create();
        MatrixKernel scalar = MatrixKernels.scalar();
        Mat4f m = new Mat4f(f(), f(), f(), 0f, f(), f(), f(), 0f, f(), f(), f(), 0f, f(), f(), f(), 1f);
        int n = 513;
        Vec3fArray v3 = new Vec3fArray(n);
        Vec4fArray v4 = new Vec4fArray(n);
        QuatArray q = new QuatArray(n);
        Mat4fArray mats = random(n);
        for (int i = 0; i < n; i++) {
            v3.add(f(), f(), f());
            v4.add(f(), f(), f(), f());
            q.add(f(), f(), f(), f());
        }
        Vec3fArray e3 = new Vec3fArray(1), a3 = new Vec3fArray(1);
        v3.transformPositions(m, e3, scalar);
        v3.transformPositions(m, a3, simd);
        assertClose(java.util.Arrays.copyOf(e3.data(), n * 3), java.util.Arrays.copyOf(a3.data(), n * 3), "positions");
        v3.transformDirections(m, e3, scalar);
        v3.transformDirections(m, a3, simd);
        assertClose(java.util.Arrays.copyOf(e3.data(), n * 3), java.util.Arrays.copyOf(a3.data(), n * 3), "directions");
        Vec4fArray e4 = new Vec4fArray(1), a4 = new Vec4fArray(1);
        v4.transform(m, e4, scalar);
        v4.transform(m, a4, simd);
        assertClose(java.util.Arrays.copyOf(e4.data(), n * 4), java.util.Arrays.copyOf(a4.data(), n * 4), "vec4");
        Mat4fArray em = new Mat4fArray(1), am = new Mat4fArray(1);
        mats.premultiply(m, em, scalar);
        mats.premultiply(m, am, simd);
        assertClose(java.util.Arrays.copyOf(em.data(), n * 16), java.util.Arrays.copyOf(am.data(), n * 16), "premultiply");
        for (int i = 0; i < n; i += 61) {
            assertTrue(m.mul(mats.get(i)).approxEquals(am.get(i), 1e-4f), "premultiply element " + i);
        }
        QuatArray q1 = new QuatArray(n), q2 = new QuatArray(n);
        for (int i = 0; i < n; i++) {
            q1.add(q.get(i));
            q2.add(q.get(i));
        }
        q1.normalizeAll(scalar);
        q2.normalizeAll(simd);
        assertClose(java.util.Arrays.copyOf(q1.data(), n * 4), java.util.Arrays.copyOf(q2.data(), n * 4), "normalize");
    }

    @Test
    void normalizeHandlesZeroAndNonFiniteQuaternions() {
        MatrixKernel simd = new SimdMatrixKernelProvider().create();
        float[] q = {0f, 0f, 0f, 0f, Float.NaN, 1f, 2f, 3f, Float.POSITIVE_INFINITY, 0f, 0f, 1f, 3f, 0f, 4f, 0f, 1e-30f, 0f, 0f, 0f};
        float[] expected = q.clone();
        MatrixKernels.scalar().normalizeQuaternions(expected, 0, 5);
        simd.normalizeQuaternions(q, 0, 5);
        for (int i = 0; i < q.length; i++) {
            assertEquals(expected[i], q[i], 1e-6f, "float " + i);
        }
        assertEquals(1f, q[3], 0f);
        assertEquals(0.6f, q[12], 1e-6f);
    }
}
