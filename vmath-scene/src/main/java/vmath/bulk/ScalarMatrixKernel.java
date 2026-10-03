package vmath.bulk;

import vmath.annotations.Experimental;

/**
 * The portable {@link MatrixKernel}: plain loops over the {@code float[]} layouts, the reference
 * that the faster kernels are compared with.
 *
 * <p>Internal: reached through {@link MatrixKernels#scalar()}; the default methods of the
 * interface also delegate to it.
 *
 * <p><b>Thread safety.</b> Stateless: one instance may be shared between threads as long as the
 * calls write to different outputs.
 */
@Experimental("internal: the reference implementation of the experimental kernel SPI")
final class ScalarMatrixKernel implements MatrixKernel {

    static final ScalarMatrixKernel INSTANCE = new ScalarMatrixKernel();

    private ScalarMatrixKernel() {
    }

    @Override
    public String name() {
        return "scalar";
    }

    @Override
    public void multiply(float[] a, int ao, float[] b, int bo, float[] out, int oo, int count) {
        for (int i = 0, k = 0; i < count; i++, k += Mat4fArray.STRIDE) {
            Mat4fArray.multiply(a, ao + k, b, bo + k, out, oo + k);
        }
    }

    @Override
    public void transformVec4(float[] m, int mo, float[] src, int so, float[] dst, int d, int count) {
        float m00 = m[mo], m01 = m[mo + 1], m02 = m[mo + 2], m03 = m[mo + 3];
        float m10 = m[mo + 4], m11 = m[mo + 5], m12 = m[mo + 6], m13 = m[mo + 7];
        float m20 = m[mo + 8], m21 = m[mo + 9], m22 = m[mo + 10], m23 = m[mo + 11];
        float m30 = m[mo + 12], m31 = m[mo + 13], m32 = m[mo + 14], m33 = m[mo + 15];
        for (int i = 0, s = so, o = d; i < count; i++, s += 4, o += 4) {
            float x = src[s], y = src[s + 1], z = src[s + 2], w = src[s + 3];
            dst[o] = m00 * x + m10 * y + m20 * z + m30 * w;
            dst[o + 1] = m01 * x + m11 * y + m21 * z + m31 * w;
            dst[o + 2] = m02 * x + m12 * y + m22 * z + m32 * w;
            dst[o + 3] = m03 * x + m13 * y + m23 * z + m33 * w;
        }
    }

    @Override
    public void transformPositions(float[] m, int mo, float[] src, int so, float[] dst, int d, int count) {
        transform3(m, mo, src, so, dst, d, count, m[mo + 12], m[mo + 13], m[mo + 14]);
    }

    @Override
    public void transformDirections(float[] m, int mo, float[] src, int so, float[] dst, int d, int count) {
        transform3(m, mo, src, so, dst, d, count, 0f, 0f, 0f);
    }

    private static void transform3(float[] m, int mo, float[] src, int so, float[] dst, int d, int count, float tx, float ty, float tz) {
        float m00 = m[mo], m01 = m[mo + 1], m02 = m[mo + 2];
        float m10 = m[mo + 4], m11 = m[mo + 5], m12 = m[mo + 6];
        float m20 = m[mo + 8], m21 = m[mo + 9], m22 = m[mo + 10];
        for (int i = 0, s = so, o = d; i < count; i++, s += 3, o += 3) {
            float x = src[s], y = src[s + 1], z = src[s + 2];
            dst[o] = m00 * x + m10 * y + m20 * z + tx;
            dst[o + 1] = m01 * x + m11 * y + m21 * z + ty;
            dst[o + 2] = m02 * x + m12 * y + m22 * z + tz;
        }
    }

    @Override
    public void normalizeQuaternions(float[] q, int off, int count) {
        for (int i = 0, o = off; i < count; i++, o += 4) {
            float x = q[o], y = q[o + 1], z = q[o + 2], w = q[o + 3];
            float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
            if (len > 1e-20f && len < Float.POSITIVE_INFINITY) {
                float inv = 1f / len;
                q[o] = x * inv;
                q[o + 1] = y * inv;
                q[o + 2] = z * inv;
                q[o + 3] = w * inv;
            } else {
                q[o] = 0f;
                q[o + 1] = 0f;
                q[o + 2] = 0f;
                q[o + 3] = 1f;
            }
        }
    }
}
