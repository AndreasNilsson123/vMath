package vmath.spatial;

/**
 * The per-node tests that the static BVH ({@link BvhQuery}) and the dynamic tree ({@link DynamicAabbTree}) share, so that the two cannot drift apart: both are
 * called in the innermost loop of a traversal, take plain arrays and allocate nothing.
 */
final class NodeTests {

    private NodeTests() {
    }

    /**
     * Tests the node box at {@code b[o .. o+5]} ({@code minX, minY, minZ, maxX, maxY, maxZ}) against the frustum {@code planes} (six planes of {@code nx, ny, nz, d}, a
     * point is inside when {@code n.p + d >= 0}) that are still undecided in {@code mask}. Returns -1 when the box is entirely outside one of them, otherwise the
     * mask of the planes the box is not entirely inside (0: the whole subtree is visible, no further plane tests needed).
     */
    static int frustumMask(float[] planes, float[] b, int o, int mask) {
        float cx = (b[o] + b[o + 3]) * 0.5f, cy = (b[o + 1] + b[o + 4]) * 0.5f, cz = (b[o + 2] + b[o + 5]) * 0.5f;
        float hx = (b[o + 3] - b[o]) * 0.5f, hy = (b[o + 4] - b[o + 1]) * 0.5f, hz = (b[o + 5] - b[o + 2]) * 0.5f;
        int m = mask;
        for (int p = 0; p < 6; p++) {
            if ((mask & (1 << p)) == 0) {
                continue;
            }
            float nx = planes[p * 4], ny = planes[p * 4 + 1], nz = planes[p * 4 + 2], d = planes[p * 4 + 3];
            float s = nx * cx + ny * cy + nz * cz + d;
            float r = hx * Math.abs(nx) + hy * Math.abs(ny) + hz * Math.abs(nz);
            if (s + r < 0f) {
                return -1;
            }
            if (s - r >= 0f) {
                m &= ~(1 << p);
            }
        }
        return m;
    }

    /** Reciprocal that never yields NaN downstream: a zero component becomes a huge finite value instead of infinity. */
    static float inverse(float d) {
        return 1f / (d == 0f ? Float.MIN_NORMAL : d);
    }

    /** Slab test against node bounds; returns the entry distance, or +Infinity for a miss or an entry beyond tMax. */
    static float entry(float[] b, int o, float ox, float oy, float oz, float ix, float iy, float iz, float tMax) {
        float t1 = (b[o] - ox) * ix, t2 = (b[o + 3] - ox) * ix;
        float tNear = Math.min(t1, t2), tFar = Math.max(t1, t2);
        t1 = (b[o + 1] - oy) * iy;
        t2 = (b[o + 4] - oy) * iy;
        tNear = Math.max(tNear, Math.min(t1, t2));
        tFar = Math.min(tFar, Math.max(t1, t2));
        t1 = (b[o + 2] - oz) * iz;
        t2 = (b[o + 5] - oz) * iz;
        tNear = Math.max(tNear, Math.min(t1, t2));
        tFar = Math.min(tFar, Math.max(t1, t2));
        tNear = Math.max(tNear, 0f);
        tFar = Math.min(tFar, tMax);
        return tNear <= tFar ? tNear : Float.POSITIVE_INFINITY;
    }
}
