package vmath.spatial;

import vmath.core.Vec3f;
import vmath.geo.Frustumf;

/**
 * Per-frame inputs shared by all culling stages.
 *
 * <p>Build one per view (main camera, each shadow cascade, ...).
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Frustumf frustum = Frustumf.fromViewProjection(Mat4f.IDENTITY, DepthRange.of(ClipSpace.OPENGL));
 * CullContext ctx = CullContext.perspective(frustum, new Vec3f(0f, 0f, 5f), 1f, 1080);   // vertical field of view and viewport height
 * }</pre>
 *
 * @param frustum     the view volume
 * @param camera      the eye position, for distance and size tests
 *
 * @param pixelScale {@code viewportHeightPixels / (2 * tan(fovy / 2))}: a sphere of radius
 *     {@code r} at distance {@code z} covers about {@code r * pixelScale / z} pixels of screen
 *     height. Zero disables size-based culling.
 */
public record CullContext(Frustumf frustum, Vec3f camera, float pixelScale) {

    /**
     * Creates the context of a perspective view, which holds the frustum, the eye position and the
     * data that level-of-detail selection needs.
     *
     * @param frustum the frustum; must not be {@code null}
     * @param camera the camera; must not be {@code null}
     * @param fovy the vertical field of view in radians
     * @param viewportHeight the viewport height
     * @return context for a perspective view with the given vertical field of view (radians) and
     *     viewport height (pixels)
     */
    public static CullContext perspective(Frustumf frustum, Vec3f camera, float fovy, int viewportHeight) {
        float scale = viewportHeight / (2f * (float) Math.tan(fovy * 0.5f));
        return new CullContext(frustum, camera, scale);
    }
}
