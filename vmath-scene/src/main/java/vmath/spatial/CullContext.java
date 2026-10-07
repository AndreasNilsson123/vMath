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
 * @param pixelScale for a perspective view {@code viewportHeightPixels / (2 * tan(fovy / 2))}: a
 *     sphere of radius {@code r} at distance {@code z} covers about {@code r * pixelScale / z}
 *     pixels of screen height. For an orthographic view ({@code orthographic} is {@code true}) the
 *     pixels per world unit, {@code viewportHeightPixels / height}: the sphere covers about
 *     {@code r * pixelScale} pixels at any distance. Zero disables size-based culling.
 * @param orthographic whether the view is orthographic, in which case the projected size of an
 *     object does not depend on its distance from {@code camera}
 */
public record CullContext(Frustumf frustum, Vec3f camera, float pixelScale, boolean orthographic) {

    /**
     * Creates the context of a perspective view (or one that does no size-based culling).
     *
     * @param frustum the frustum; must not be {@code null}
     * @param camera the eye position; must not be {@code null}
     * @param pixelScale the pixel scale of a perspective view, or 0 for none
     */
    public CullContext(Frustumf frustum, Vec3f camera, float pixelScale) {
        this(frustum, camera, pixelScale, false);
    }

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

    /**
     * Creates the context of an orthographic view: objects are as large on the screen at any
     * distance, so size-based culling and level-of-detail selection use the pixels per world unit
     * and ignore the distance from the camera.
     *
     * @param frustum the frustum (the box of the view); must not be {@code null}
     * @param camera the camera position; must not be {@code null}
     * @param viewportHeight the viewport height in pixels, positive
     * @param viewHeight the height of the view in world units, positive ({@code OrthoCameraf.height()})
     * @return the context, whose pixel scale is {@code viewportHeight / viewHeight}
     * @throws IllegalArgumentException if a size is not positive
     */
    public static CullContext orthographic(Frustumf frustum, Vec3f camera, int viewportHeight, float viewHeight) {
        if (viewportHeight < 1 || !(viewHeight > 0f)) {
            throw new IllegalArgumentException("the viewport height and the view height must be positive: " + viewportHeight + ", " + viewHeight);
        }
        return new CullContext(frustum, camera, viewportHeight / viewHeight, true);
    }
}
