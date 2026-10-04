package vmath.simd;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import vmath.bulk.MatrixKernel;

/**
 * The batch 4x4 product with the Vector API: every column of a matrix is one 128-bit vector, and a
 * column of the result is the sum of the columns of the left matrix scaled by the entries of the
 * matching column of the right matrix, computed with fused multiply-add.
 *
 * <p>The vector transforms use the same scheme: the columns of the matrix are 128-bit vectors and a
 * result is the sum of the columns scaled by the components, with fused multiply-add. The three
 * component forms write four floats per element, one more than the element has, and the next
 * element overwrites the extra one; the components of the next element are read before the store,
 * so that the output may be the input. The quaternions are normalised one per vector.
 *
 * <p>The results can differ from the scalar kernel in the last bit (fused against separately
 * rounded operations, a different order of the sum of squares).
 *
 * <p><b>Thread safety.</b> Stateless: one instance may be shared between threads as long as the
 * calls write to different outputs.
 */
final class SimdMatrixKernel implements MatrixKernel {

    private static final VectorSpecies<Float> S = FloatVector.SPECIES_128;

    @Override
    public String name() {
        return SimdSupport.NAME;
    }

    @Override
    public void multiply(float[] a, int ao, float[] b, int bo, float[] out, int oo, int count) {
        for (int i = 0, k = 0; i < count; i++, k += 16) {
            FloatVector a0 = FloatVector.fromArray(S, a, ao + k);
            FloatVector a1 = FloatVector.fromArray(S, a, ao + k + 4);
            FloatVector a2 = FloatVector.fromArray(S, a, ao + k + 8);
            FloatVector a3 = FloatVector.fromArray(S, a, ao + k + 12);
            int bk = bo + k, ok = oo + k;
            // the right matrix is read column by column and each result column is stored before the next is read, so out may be b
            for (int c = 0; c < 16; c += 4) {
                FloatVector r = a0.mul(b[bk + c]);
                r = a1.fma(FloatVector.broadcast(S, b[bk + c + 1]), r);
                r = a2.fma(FloatVector.broadcast(S, b[bk + c + 2]), r);
                r = a3.fma(FloatVector.broadcast(S, b[bk + c + 3]), r);
                r.intoArray(out, ok + c);
            }
        }
    }

    @Override
    public void transformVec4(float[] m, int mo, float[] src, int so, float[] dst, int d, int count) {
        FloatVector c0 = FloatVector.fromArray(S, m, mo);
        FloatVector c1 = FloatVector.fromArray(S, m, mo + 4);
        FloatVector c2 = FloatVector.fromArray(S, m, mo + 8);
        FloatVector c3 = FloatVector.fromArray(S, m, mo + 12);
        for (int i = 0, s = so, o = d; i < count; i++, s += 4, o += 4) {
            FloatVector r = c0.mul(src[s]);
            r = c1.fma(FloatVector.broadcast(S, src[s + 1]), r);
            r = c2.fma(FloatVector.broadcast(S, src[s + 2]), r);
            r = c3.fma(FloatVector.broadcast(S, src[s + 3]), r);
            r.intoArray(dst, o);
        }
    }

    @Override
    public void transformPositions(float[] m, int mo, float[] src, int so, float[] dst, int d, int count) {
        transform3(m, mo, src, so, dst, d, count, true);
    }

    @Override
    public void transformDirections(float[] m, int mo, float[] src, int so, float[] dst, int d, int count) {
        transform3(m, mo, src, so, dst, d, count, false);
    }

    private static void transform3(float[] m, int mo, float[] src, int so, float[] dst, int d, int count, boolean translate) {
        if (count <= 0) {
            return;
        }
        // 128-bit loads of the columns read the fourth float of each (m03, m13, m23, m33), which the stores below never use
        FloatVector c0 = FloatVector.fromArray(S, m, mo);
        FloatVector c1 = FloatVector.fromArray(S, m, mo + 4);
        FloatVector c2 = FloatVector.fromArray(S, m, mo + 8);
        FloatVector c3 = translate ? FloatVector.fromArray(S, m, mo + 12) : FloatVector.zero(S);
        float x = src[so], y = src[so + 1], z = src[so + 2];
        int last = count - 1;
        for (int i = 0, s = so, o = d; i < last; i++, s += 3, o += 3) {
            float nx = src[s + 3], ny = src[s + 4], nz = src[s + 5]; // before the store below overwrites dst[o + 3], which may be src[s + 3]
            FloatVector r = c0.fma(FloatVector.broadcast(S, x), c3);
            r = c1.fma(FloatVector.broadcast(S, y), r);
            r = c2.fma(FloatVector.broadcast(S, z), r);
            r.intoArray(dst, o);
            x = nx;
            y = ny;
            z = nz;
        }
        // the last element is stored by components: a fourth float would run past the end of the data
        int o = d + last * 3;
        dst[o] = Math.fma(m[mo + 8], z, Math.fma(m[mo + 4], y, Math.fma(m[mo], x, translate ? m[mo + 12] : 0f)));
        dst[o + 1] = Math.fma(m[mo + 9], z, Math.fma(m[mo + 5], y, Math.fma(m[mo + 1], x, translate ? m[mo + 13] : 0f)));
        dst[o + 2] = Math.fma(m[mo + 10], z, Math.fma(m[mo + 6], y, Math.fma(m[mo + 2], x, translate ? m[mo + 14] : 0f)));
    }

    @Override
    public void normalizeQuaternions(float[] q, int off, int count) {
        for (int i = 0, o = off; i < count; i++, o += 4) {
            FloatVector v = FloatVector.fromArray(S, q, o);
            float len = (float) Math.sqrt(v.mul(v).reduceLanes(VectorOperators.ADD));
            if (len > 1e-20f && len < Float.POSITIVE_INFINITY) {
                v.mul(1f / len).intoArray(q, o);
            } else {
                q[o] = 0f;
                q[o + 1] = 0f;
                q[o + 2] = 0f;
                q[o + 3] = 1f;
            }
        }
    }
}
