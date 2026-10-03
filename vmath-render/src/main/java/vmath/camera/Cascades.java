package vmath.camera;

import java.util.ArrayList;
import java.util.List;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

/**
 * Cascaded shadow map setup for a directional light: how to split the view range, and the
 * light-space orthographic projection that covers each slice.
 *
 * <p>With {@code stabilize = true} each cascade is fitted around the <b>bounding sphere</b> of its
 * slice and its origin is snapped to whole shadow-map texels. The sphere does not change when the
 * camera turns and the snapping removes sub-texel sliding when it moves, so shadow edges do not
 * shimmer. The price is a slightly larger volume than a tight fit ({@code stabilize = false}),
 * which fits the slice's corners exactly.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Cameraf camera = Cameraf.lookingAt(new Vec3f(0f, 5f, 10f), Vec3f.ZERO, Vec3f.UNIT_Y, 1f, 1.5f, 0.1f, 500f, DepthRange.of(ClipSpace.OPENGL));
 * List<Cascades.Cascade> cascades = Cascades.fitAll(camera, 4, 0.75f, 200f, new Vec3f(0.3f, -1f, 0.2f), 2048, true, 100f, DepthRange.of(ClipSpace.OPENGL));
 * Mat4f toShadowMap = cascades.get(0).textureMatrix();                       // world to shadow-map texture coordinates
 * Frustumf casterVolume = cascades.get(0).frustum();
 * }</pre>
 */
public final class Cascades {

    private Cascades() {
    }

    /**
     * One cascade.
     *
     * @param sliceNear      view distance where this cascade's slice starts
     * @param sliceFar       view distance where it ends
     * @param lightView      world to light space (a rotation: the light looks along its local -Z)
     *
     * @param lightProjection orthographic projection covering the slice (and casters toward the
     *     light)
     * @param viewProjection {@code lightProjection * lightView}
     * @param lightBounds    the covered box in light space
     * @param texelSize      light-space size of one shadow-map texel
     * @param depth          the depth convention of {@code lightProjection}
     */
    public record Cascade(float sliceNear, float sliceFar, Mat4f lightView, Mat4f lightProjection, Mat4f viewProjection,
                          Aabbf lightBounds, float texelSize, DepthRange depth) {

        /**
         * Exposes the culling volume of the cascade, for culling casters before they are rendered
         * into the shadow map.
         *
         * @return culling volume of this cascade: everything that can cast a shadow into it
         */
        public Frustumf frustum() {
            return Frustumf.fromViewProjection(viewProjection, depth);
        }

        /**
         * Exposes the matrix that maps world positions to shadow-map texture coordinates and depth,
         * for sampling the cascade in a shader.
         *
         * @return world to shadow-map texture coordinates: u and v in [0, 1] (no y flip; apply one
         *     if your texture origin needs it), and depth in the convention's own range, ready to
         *     compare with the stored depth
         */
        public Mat4f textureMatrix() {
            float zScale = depth == DepthRange.NEGATIVE_ONE_TO_ONE ? 0.5f : 1f;
            float zOffset = depth == DepthRange.NEGATIVE_ONE_TO_ONE ? 0.5f : 0f;
            Mat4f bias = new Mat4f(
                    0.5f, 0f, 0f, 0f,
                    0f, 0.5f, 0f, 0f,
                    0f, 0f, zScale, 0f,
                    0.5f, 0.5f, zOffset, 1f);
            return bias.mul(viewProjection);
        }
    }

    /**
     * Computes the cascade split distances as a blend between logarithmic and uniform spacing, a
     * standard compromise between near and far shadow resolution.
     *
     * <p>{@code lambda} blends uniform (0) and logarithmic (1) spacing; about 0.5 to 0.9 usually
     * balances close-up detail against far coverage (the "practical split scheme").
     *
     * @param near the distance to the near plane
     * @param far the distance to the far plane
     * @param count the number of elements
     * @param lambda the lambda
     * @return distances {@code [near, ..., far]} that split the view range into {@code count}
     *     slices ({@code count + 1} values, the ends exactly {@code near} and {@code far})
     * @throws IllegalArgumentException if {@code count} is below 1, {@code near} and {@code far} do
     *     not satisfy {@code 0 < near < far}, or {@code lambda} is not in {@code [0, 1]}
     */
    public static float[] splitDistances(float near, float far, int count, float lambda) {
        if (count < 1 || !(near > 0f) || !(far > near) || lambda < 0f || lambda > 1f) {
            throw new IllegalArgumentException("need count >= 1, 0 < near < far, 0 <= lambda <= 1");
        }
        float[] d = new float[count + 1];
        d[0] = near;
        for (int i = 1; i < count; i++) {
            float t = (float) i / count;
            float log = near * (float) Math.pow(far / near, t);
            float uniform = near + (far - near) * t;
            d[i] = lambda * log + (1f - lambda) * uniform;
        }
        d[count] = far;
        return d;
    }

    /**
     * Fits {@code count} cascades over {@code [camera.near, shadowDistance]}; see {@link #fit}.
     *
     * @param camera the camera; must not be {@code null}
     * @param count the number of elements
     * @param lambda the lambda
     * @param shadowDistance the shadow distance
     * @param lightDirection the light direction; must not be {@code null}
     * @param shadowMapSize the shadow map size
     * @param stabilize {@code true} to fit each cascade around a bounding sphere and snap it to
     *     shadow-map texels, so that shadow edges do not shimmer when the camera moves, at the
     *     price of a slightly larger volume
     * @param casterDistance the caster distance
     * @param shadowDepth the shadow depth; must not be {@code null}
     * @return the cascades, nearest first, never {@code null}
     */
    public static List<Cascade> fitAll(Cameraf camera, int count, float lambda, float shadowDistance, Vec3f lightDirection,
                                       int shadowMapSize, boolean stabilize, float casterDistance, DepthRange shadowDepth) {
        float far = Math.min(camera.far(), shadowDistance);
        float[] splits = splitDistances(camera.near(), far, count, lambda);
        List<Cascade> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(fit(camera, splits[i], splits[i + 1], lightDirection, shadowMapSize, stabilize, casterDistance, shadowDepth));
        }
        return out;
    }

    /**
     * Fits a light-space orthographic projection around a slice of the view frustum; with
     * stabilisation the extent is rounded so that the shadow does not shimmer as the camera moves.
     *
     * @param camera the camera; must not be {@code null}
     * @param sliceNear the slice near
     * @param sliceFar the slice far
     * @param lightDirection the direction the light travels (from the light toward the scene); need
     *     not be unit length
     * @param shadowMapSize resolution of the shadow map in texels (one side)
     * @param stabilize {@code true} to fit each cascade around a bounding sphere and snap it to
     *     shadow-map texels, so that shadow edges do not shimmer when the camera moves, at the
     *     price of a slightly larger volume
     * @param casterDistance how far toward the light to extend the volume so that casters outside
     *     the view slice, such as tall buildings behind the camera, still land in the map
     * @param shadowDepth    depth convention of the shadow map; the reversed convention is an ordinary reversed ortho
     * @return light-space orthographic projection covering the part of {@code camera}'s view
     *     between {@code sliceNear} and {@code sliceFar}
     * @throws IllegalArgumentException if the slice distances do not satisfy
     *     {@code 0 < sliceNear < sliceFar}, {@code shadowMapSize} is below 1 or
     *     {@code casterDistance} is negative
     */
    public static Cascade fit(Cameraf camera, float sliceNear, float sliceFar, Vec3f lightDirection, int shadowMapSize,
                              boolean stabilize, float casterDistance, DepthRange shadowDepth) {
        if (!(sliceNear > 0f && sliceFar > sliceNear) || shadowMapSize < 1 || casterDistance < 0f) {
            throw new IllegalArgumentException("need 0 < sliceNear < sliceFar, shadowMapSize >= 1, casterDistance >= 0");
        }
        Vec3f dir = lightDirection.normalize();
        Vec3f up = Math.abs(dir.y()) > 0.99f ? Vec3f.UNIT_X : Vec3f.UNIT_Y;
        Mat4f lightView = Mat4f.lookTo(Vec3f.ZERO, dir, up);

        // the eight corners of the slice, in world space
        float t = (float) Math.tan(camera.fovy() * 0.5f);
        Vec3f fwd = camera.forward(), right = camera.right(), upv = camera.up();
        Vec3f[] corners = new Vec3f[8];
        for (int i = 0; i < 8; i++) {
            float d = (i & 4) == 0 ? sliceNear : sliceFar;
            float h = t * d;
            float w = h * camera.aspect();
            corners[i] = camera.position().add(fwd.mul(d))
                    .add(right.mul((i & 1) == 0 ? -w : w)).add(upv.mul((i & 2) == 0 ? -h : h));
        }

        float minX, minY, minZ, maxX, maxY, maxZ;
        if (stabilize) {
            // the centroid of a symmetric frustum slice lies on the view axis, halfway between its planes
            Vec3f center = camera.position().add(fwd.mul((sliceNear + sliceFar) * 0.5f));
            float r2 = 0f;
            for (Vec3f c : corners) {
                r2 = Math.max(r2, c.distanceSquared(center));
            }
            // round the radius up so tiny changes never resize the box
            float radius = (float) Math.ceil(Math.sqrt(r2) * 16.0) / 16f;
            Vec3f ls = lightView.transformPosition(center);
            float texel = 2f * radius / shadowMapSize;
            minX = (float) Math.floor((ls.x() - radius) / texel) * texel;
            minY = (float) Math.floor((ls.y() - radius) / texel) * texel;
            maxX = minX + 2f * radius;
            maxY = minY + 2f * radius;
            minZ = ls.z() - radius;
            maxZ = ls.z() + radius;
        } else {
            minX = Float.POSITIVE_INFINITY;
            minY = minX;
            minZ = minX;
            maxX = Float.NEGATIVE_INFINITY;
            maxY = maxX;
            maxZ = maxX;
            for (Vec3f c : corners) {
                Vec3f p = lightView.transformPosition(c);
                minX = Math.min(minX, p.x());
                minY = Math.min(minY, p.y());
                minZ = Math.min(minZ, p.z());
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
                maxZ = Math.max(maxZ, p.z());
            }
        }

        // light space looks down -Z, so larger z is nearer the light; casters are added on that side
        float near = -(maxZ + casterDistance);
        float far = -minZ;
        Mat4f proj = switch (shadowDepth) {
            case NEGATIVE_ONE_TO_ONE -> Mat4f.ortho(minX, maxX, minY, maxY, near, far, false);
            case ZERO_TO_ONE -> Mat4f.ortho(minX, maxX, minY, maxY, near, far, true);
            // swapping near and far maps the near plane to depth 1 and the far plane to 0
            case REVERSED_ZERO_TO_ONE -> Mat4f.ortho(minX, maxX, minY, maxY, far, near, true);
        };
        return new Cascade(sliceNear, sliceFar, lightView, proj, proj.mul(lightView),
                new Aabbf(minX, minY, minZ, maxX, maxY, maxZ), (maxX - minX) / shadowMapSize, shadowDepth);
    }
}
