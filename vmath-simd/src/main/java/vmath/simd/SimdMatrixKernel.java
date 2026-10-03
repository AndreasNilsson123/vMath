package vmath.simd;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import vmath.bulk.MatrixKernel;

/**
 * The batch 4x4 product with the Vector API: every column of a matrix is one 128-bit vector, and a
 * column of the result is the sum of the columns of the left matrix scaled by the entries of the
 * matching column of the right matrix, computed with fused multiply-add.
 *
 * <p>The result can differ from the scalar kernel in the last bit (fused against separately rounded
 * operations).
 */
final class SimdMatrixKernel implements MatrixKernel {

    private static final VectorSpecies<Float> S = FloatVector.SPECIES_128;

    @Override
    public String name() {
        return "simd";
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
}
