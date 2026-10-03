package vmath.spatial;

/**
 * The per-node tests that the static BVH ({@link BvhQuery}) and the dynamic tree
 * ({@link DynamicAabbTree}) share, so that the two cannot drift apart: both are called in the
 * innermost loop of a traversal, take plain arrays and allocate nothing.
 */
final class NodeTests {

    private NodeTests() {
    }

    /**
     * Tests the node box at {@code b[o .. o+5]} ({@code minX, minY, minZ, maxX, maxY, maxZ})
     * against the frustum {@code planes} (six planes of {@code nx, ny, nz, d}, a point is inside
     * when {@code n.p + d >= 0}) that are still undecided in {@code mask}.
     *
     * <p>Returns -1 when the box is entirely outside one of them, otherwise the mask of the planes
     * the box is not entirely inside (0: the whole subtree is visible, no further plane tests
     * needed).
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

    /**
     * Reciprocal of a ray direction component for {@link #entry}: {@code +Infinity} for a component
     * that is zero (or so small that its reciprocal overflows), which {@code entry} reads as "the
     * ray is parallel to this slab".
     *
     * <p>Never used in a product, so no NaN can arise from {@code 0 * Infinity}.
     */
    static float inverse(float d) {
        float inv = 1f / d;
        return Float.isInfinite(inv) ? Float.POSITIVE_INFINITY : inv;
    }

    /**
     * Slab test against node bounds; returns the entry distance, or +Infinity for a miss or an
     * entry beyond tMax.
     *
     * <p>An axis the ray is parallel to ({@code i == +Infinity}) only checks that the origin lies
     * between the two faces, <em>inclusive</em>: a ray that runs exactly along a face touches the
     * box, as {@code Intersectionf.rayAabb} says.
     */
    static float entry(float[] b, int o, float ox, float oy, float oz, float ix, float iy, float iz, float tMax) {
        return entry(b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5], ox, oy, oz, ix, iy, iz, tMax);
    }

    /**
     * {@link #entry(float[], int, float, float, float, float, float, float, float)} for a box given
     * by its six bounds.
     */
    static float entry(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                       float ox, float oy, float oz, float ix, float iy, float iz, float tMax) {
        float tNear = 0f, tFar = tMax;
        if (ix == Float.POSITIVE_INFINITY) {
            if (ox < minX || ox > maxX) {
                return Float.POSITIVE_INFINITY;
            }
        } else {
            float t1 = (minX - ox) * ix, t2 = (maxX - ox) * ix;
            tNear = Math.max(tNear, Math.min(t1, t2));
            tFar = Math.min(tFar, Math.max(t1, t2));
        }
        if (iy == Float.POSITIVE_INFINITY) {
            if (oy < minY || oy > maxY) {
                return Float.POSITIVE_INFINITY;
            }
        } else {
            float t1 = (minY - oy) * iy, t2 = (maxY - oy) * iy;
            tNear = Math.max(tNear, Math.min(t1, t2));
            tFar = Math.min(tFar, Math.max(t1, t2));
        }
        if (iz == Float.POSITIVE_INFINITY) {
            if (oz < minZ || oz > maxZ) {
                return Float.POSITIVE_INFINITY;
            }
        } else {
            float t1 = (minZ - oz) * iz, t2 = (maxZ - oz) * iz;
            tNear = Math.max(tNear, Math.min(t1, t2));
            tFar = Math.min(tFar, Math.max(t1, t2));
        }
        return tNear <= tFar ? tNear : Float.POSITIVE_INFINITY;
    }

    /**
     * Squared distance from the point to the box at {@code b[o .. o+5]}; 0 when the point is
     * inside.
     */
    static float boxDistanceSquared(float[] b, int o, float px, float py, float pz) {
        float dx = Math.max(Math.max(b[o] - px, 0f), px - b[o + 3]);
        float dy = Math.max(Math.max(b[o + 1] - py, 0f), py - b[o + 4]);
        float dz = Math.max(Math.max(b[o + 2] - pz, 0f), pz - b[o + 5]);
        return dx * dx + dy * dy + dz * dz;
    }
}
