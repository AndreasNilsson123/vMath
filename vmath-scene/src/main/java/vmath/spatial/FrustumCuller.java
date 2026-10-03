package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.geo.Frustumf;

/**
 * The portable scalar {@link FrustumKernel}: batch frustum culling over {@link BoundsArray} in plain Java.
 *
 * <p>The kernel is <b>plane-major and two-pass</b>. For each of the six planes it makes one branch-free pass over a chunk
 * of objects, taking the box corner farthest along the plane normal (the "p-vertex", chosen once per plane by picking
 * {@code maxX[]} or {@code minX[]}, so there is no per-object branch) and writing the signed distance to its own scratch
 * array. A final pass takes the minimum of the six. Splitting it this way is deliberate: measured (see
 * {@code docs/PERFORMANCE.md}) C2 vectorizes these simple loops, but not a fused single loop over all six planes, and it
 * vectorizes {@code Math.min} but not the equivalent ternary. The fused form is what the {@code vmath-simd} kernel does
 * with the Vector API, because it reads every input array once instead of once per plane.
 *
 * <p>An instance owns scratch memory, so use one per thread. Culling allocates nothing.
 */
public final class FrustumCuller implements FrustumKernel {

    /** Objects per chunk: a multiple of 64 so each chunk fills whole bitset words. */
    static final int CHUNK = 1024;

    private final float[] slack = new float[CHUNK];
    private final float[][] perPlane = new float[6][CHUNK];
    private final float[] planes = new float[24];

    /** A scalar frustum kernel with its own scratch memory; use one per thread. */
    public FrustumCuller() {
    }

    @Override
    public String name() {
        return "scalar";
    }

    @Override
    public void cull(Frustumf frustum, BoundsArray bounds, int from, int to, VisibilitySet visible) {
        if ((from & 63) != 0) {
            throw new IllegalArgumentException("from must be a multiple of 64: " + from);
        }
        frustum.writeTo(planes, 0);
        long[] words = visible.words();
        for (int start = from; start < to; start += CHUNK) {
            int n = Math.min(CHUNK, to - start);
            computeSlack(bounds, start, n);
            packInto(words, start, n);
        }
    }

    /** Minimum over the six planes of the p-vertex signed distance, per object of the chunk. */
    private void computeSlack(BoundsArray b, int start, int n) {
        for (int p = 0; p < 6; p++) {
            float nx = planes[p * 4], ny = planes[p * 4 + 1], nz = planes[p * 4 + 2], d = planes[p * 4 + 3];
            float[] px = nx >= 0f ? b.maxXs() : b.minXs();
            float[] py = ny >= 0f ? b.maxYs() : b.minYs();
            float[] pz = nz >= 0f ? b.maxZs() : b.minZs();
            float[] out = perPlane[p];
            for (int i = 0; i < n; i++) {
                out[i] = d + nx * px[start + i] + ny * py[start + i] + nz * pz[start + i];
            }
        }
        float[] a = perPlane[0], b1 = perPlane[1], c = perPlane[2], d = perPlane[3], e = perPlane[4], f = perPlane[5];
        float[] s = slack;
        for (int i = 0; i < n; i++) {
            s[i] = Math.min(Math.min(Math.min(a[i], b1[i]), Math.min(c[i], d[i])), Math.min(e[i], f[i]));
        }
    }

    /** ANDs the visibility of the chunk (visible unless slack {@code < 0}) into the bitset words. */
    private void packInto(long[] words, int start, int n) {
        int full = n >>> 6;
        for (int w = 0; w < full; w++) {
            long mask = 0L;
            int base = w << 6;
            for (int j = 0; j < 64; j++) {
                mask |= (slack[base + j] < 0f ? 0L : 1L) << j;
            }
            words[(start >>> 6) + w] &= mask;
        }
        int rest = n & 63;
        if (rest != 0) {
            long mask = 0L;
            int base = full << 6;
            for (int j = 0; j < rest; j++) {
                mask |= (slack[base + j] < 0f ? 0L : 1L) << j;
            }
            // bits beyond the last object are not ours to touch
            words[(start >>> 6) + full] &= mask | (-1L << rest);
        }
    }
}
