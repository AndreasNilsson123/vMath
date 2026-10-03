package vmath.spatial;

import vmath.core.Vec3f;
import vmath.geo.Frustumf;

/**
 * Per-frame inputs shared by all culling stages. Build one per view (main camera, each shadow cascade, ...).
 *
 * @param frustum     the view volume
 * @param camera      the eye position, for distance and size tests
 * @param pixelScale  {@code viewportHeightPixels / (2 * tan(fovy / 2))}: a sphere of radius {@code r} at distance
 *                    {@code z} covers about {@code r * pixelScale / z} pixels of screen height. Zero disables
 *                    size-based culling.
 */
public record CullContext(Frustumf frustum, Vec3f camera, float pixelScale) {

    /** Context for a perspective view with the given vertical field of view (radians) and viewport height (pixels). */
    public static CullContext perspective(Frustumf frustum, Vec3f camera, float fovy, int viewportHeight) {
        float scale = viewportHeight / (2f * (float) Math.tan(fovy * 0.5f));
        return new CullContext(frustum, camera, scale);
    }
}
