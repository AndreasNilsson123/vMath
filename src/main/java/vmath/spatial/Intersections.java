package vmath.spatial;

import vmath.bulk.BoundsArray;

/** Slab test of a ray against one box of a {@link BoundsArray}, with a precomputed reciprocal direction. */
final class Intersections {

    private Intersections() {
    }

    /** Entry distance of the ray into box {@code p} within {@code [0, tMax]}, or +Infinity. */
    static float rayBox(float ox, float oy, float oz, float ix, float iy, float iz, BoundsArray b, int p, float tMax) {
        float t1 = (b.minX(p) - ox) * ix, t2 = (b.maxX(p) - ox) * ix;
        float tNear = Math.min(t1, t2), tFar = Math.max(t1, t2);
        t1 = (b.minY(p) - oy) * iy;
        t2 = (b.maxY(p) - oy) * iy;
        tNear = Math.max(tNear, Math.min(t1, t2));
        tFar = Math.min(tFar, Math.max(t1, t2));
        t1 = (b.minZ(p) - oz) * iz;
        t2 = (b.maxZ(p) - oz) * iz;
        tNear = Math.max(tNear, Math.min(t1, t2));
        tFar = Math.min(tFar, Math.max(t1, t2));
        tNear = Math.max(tNear, 0f);
        tFar = Math.min(tFar, tMax);
        return tNear <= tFar ? tNear : Float.POSITIVE_INFINITY;
    }
}
