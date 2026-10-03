package vmath.spatial;

import vmath.bulk.BoundsArray;

/**
 * Slab test of a ray against one box of a {@link BoundsArray}, with a precomputed reciprocal
 * direction.
 */
final class Intersections {

    private Intersections() {
    }

    /**
     * Entry distance of the ray into box {@code p} within {@code [0, tMax]}, or +Infinity.
     */
    static float rayBox(float ox, float oy, float oz, float ix, float iy, float iz, BoundsArray b, int p, float tMax) {
        return NodeTests.entry(b.minX(p), b.minY(p), b.minZ(p), b.maxX(p), b.maxY(p), b.maxZ(p), ox, oy, oz, ix, iy, iz, tMax);
    }
}
