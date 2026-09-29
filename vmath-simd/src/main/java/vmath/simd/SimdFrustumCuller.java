package vmath.simd;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.geo.Frustumf;
import vmath.spatial.FrustumKernel;

/**
 * Frustum culling with the Vector API. Where the scalar kernel makes six passes over its inputs (one per plane), this
 * one is <b>fused</b>: each group of {@code L} objects is loaded once (18 vector loads: three p-vertex coordinates for each
 * of six planes), all six signed distances and their minimum stay in registers, and the visibility mask goes straight into
 * the bitset word with {@link VectorMask#toLong()}. The scalar kernel is limited by memory traffic, so reading every array
 * once is what makes this faster, and the JIT will not fuse that loop on its own.
 *
 * <p><b>Bit-identical to the scalar kernel.</b> The arithmetic is done in the same order ({@code (d + nx*px) + ny*py + nz*pz}),
 * with separate multiplies and adds and no fused multiply-add, and NaN handling matches (a NaN distance stays visible), so
 * both kernels clear exactly the same bits. Tests assert that.
 *
 * <p>Not thread-safe; use one per thread.
 */
public final class SimdFrustumCuller implements FrustumKernel {

    private static final VectorSpecies<Float> S = FloatVector.SPECIES_PREFERRED;

    private final float[] planes = new float[24];

    public SimdFrustumCuller() {
    }

    @Override
    public String name() {
        return "simd";
    }

    @Override
    public void cull(Frustumf frustum, BoundsArray b, int from, int to, VisibilitySet visible) {
        if ((from & 63) != 0) {
            throw new IllegalArgumentException("from must be a multiple of 64: " + from);
        }
        frustum.writeTo(planes, 0);
        float[] x0 = b.minXs(), y0 = b.minYs(), z0 = b.minZs(), x1 = b.maxXs(), y1 = b.maxYs(), z1 = b.maxZs();
        // per plane, the p-vertex array for each axis is fixed by the sign of the plane normal
        float[] ax0 = planes[0] >= 0f ? x1 : x0, ay0 = planes[1] >= 0f ? y1 : y0, az0 = planes[2] >= 0f ? z1 : z0;
        float[] ax1 = planes[4] >= 0f ? x1 : x0, ay1 = planes[5] >= 0f ? y1 : y0, az1 = planes[6] >= 0f ? z1 : z0;
        float[] ax2 = planes[8] >= 0f ? x1 : x0, ay2 = planes[9] >= 0f ? y1 : y0, az2 = planes[10] >= 0f ? z1 : z0;
        float[] ax3 = planes[12] >= 0f ? x1 : x0, ay3 = planes[13] >= 0f ? y1 : y0, az3 = planes[14] >= 0f ? z1 : z0;
        float[] ax4 = planes[16] >= 0f ? x1 : x0, ay4 = planes[17] >= 0f ? y1 : y0, az4 = planes[18] >= 0f ? z1 : z0;
        float[] ax5 = planes[20] >= 0f ? x1 : x0, ay5 = planes[21] >= 0f ? y1 : y0, az5 = planes[22] >= 0f ? z1 : z0;
        float n0x = planes[0], n0y = planes[1], n0z = planes[2], d0 = planes[3];
        float n1x = planes[4], n1y = planes[5], n1z = planes[6], d1 = planes[7];
        float n2x = planes[8], n2y = planes[9], n2z = planes[10], d2 = planes[11];
        float n3x = planes[12], n3y = planes[13], n3z = planes[14], d3 = planes[15];
        float n4x = planes[16], n4y = planes[17], n4z = planes[18], d4 = planes[19];
        float n5x = planes[20], n5y = planes[21], n5z = planes[22], d5 = planes[23];

        long[] words = visible.words();
        int lanes = S.length();
        // lanes is a power of two no larger than 64, so a group never straddles two bitset words when `from` is aligned
        long laneBits = lanes == 64 ? -1L : (1L << lanes) - 1L;
        int end = from + ((to - from) / lanes) * lanes;
        int k = from;
        for (; k < end; k += lanes) {
            FloatVector m = FloatVector.fromArray(S, ax0, k).mul(n0x).add(d0)
                    .add(FloatVector.fromArray(S, ay0, k).mul(n0y))
                    .add(FloatVector.fromArray(S, az0, k).mul(n0z));
            m = m.min(FloatVector.fromArray(S, ax1, k).mul(n1x).add(d1)
                    .add(FloatVector.fromArray(S, ay1, k).mul(n1y))
                    .add(FloatVector.fromArray(S, az1, k).mul(n1z)));
            m = m.min(FloatVector.fromArray(S, ax2, k).mul(n2x).add(d2)
                    .add(FloatVector.fromArray(S, ay2, k).mul(n2y))
                    .add(FloatVector.fromArray(S, az2, k).mul(n2z)));
            m = m.min(FloatVector.fromArray(S, ax3, k).mul(n3x).add(d3)
                    .add(FloatVector.fromArray(S, ay3, k).mul(n3y))
                    .add(FloatVector.fromArray(S, az3, k).mul(n3z)));
            m = m.min(FloatVector.fromArray(S, ax4, k).mul(n4x).add(d4)
                    .add(FloatVector.fromArray(S, ay4, k).mul(n4y))
                    .add(FloatVector.fromArray(S, az4, k).mul(n4z)));
            m = m.min(FloatVector.fromArray(S, ax5, k).mul(n5x).add(d5)
                    .add(FloatVector.fromArray(S, ay5, k).mul(n5y))
                    .add(FloatVector.fromArray(S, az5, k).mul(n5z)));
            // visible unless the minimum distance is negative; lt() is false for NaN, so NaN stays visible
            VectorMask<Float> visibleMask = m.lt(0f).not();
            int shift = k & 63;
            words[k >>> 6] &= (visibleMask.toLong() << shift) | ~(laneBits << shift);
        }
        // scalar tail, same arithmetic order as the vector body and as the scalar kernel
        for (; k < to; k++) {
            float m = d0 + n0x * ax0[k] + n0y * ay0[k] + n0z * az0[k];
            m = Math.min(m, d1 + n1x * ax1[k] + n1y * ay1[k] + n1z * az1[k]);
            m = Math.min(m, d2 + n2x * ax2[k] + n2y * ay2[k] + n2z * az2[k]);
            m = Math.min(m, d3 + n3x * ax3[k] + n3y * ay3[k] + n3z * az3[k]);
            m = Math.min(m, d4 + n4x * ax4[k] + n4y * ay4[k] + n4z * az4[k]);
            m = Math.min(m, d5 + n5x * ax5[k] + n5y * ay5[k] + n5z * az5[k]);
            if (m < 0f) {
                words[k >>> 6] &= ~(1L << k);
            }
        }
    }
}
