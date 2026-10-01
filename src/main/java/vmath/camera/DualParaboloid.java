package vmath.camera;

import vmath.annotations.Experimental;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Planef;

/**
 * Dual-paraboloid mapping: two hemisphere images instead of the six faces of a cube map, for omnidirectional shadow maps and cheap probes. A direction
 * {@code d} (unit, in the hemisphere's view space where the front hemisphere looks along -Z) maps to {@code (x, y) / (1 - z)} in the unit disc, and the depth is the
 * distance from the centre, linearly between {@code near} and {@code far}.
 *
 * <p>The mapping is not projective: straight edges become curves, so geometry must be tessellated finely enough (or the vertex shader will cut corners), and the
 * seam between the hemispheres needs a little overlap. Compared with a cube map it costs two renders instead of six and wastes the corners of the images less,
 * and it loses on quality near the seam and on the tessellation requirement.
 */
@Experimental("the helper set may change")
public final class DualParaboloid {

    public static final int FRONT = 0;
    public static final int BACK = 1;

    private DualParaboloid() {
    }

    private static void check(int hemisphere) {
        if (hemisphere != FRONT && hemisphere != BACK) {
            throw new IllegalArgumentException("hemisphere must be FRONT (0) or BACK (1): " + hemisphere);
        }
    }

    /** The view matrix of a hemisphere with the centre at {@code position}: the front one looks along -Z, the back one along +Z (both with +Y up). */
    public static Mat4f view(int hemisphere, Vec3f position) {
        check(hemisphere);
        return Mat4f.lookTo(position, new Vec3f(0f, 0f, hemisphere == FRONT ? -1f : 1f), new Vec3f(0f, 1f, 0f));
    }

    /** The hemisphere a world direction belongs to; the equator ({@code z == 0}) goes to the front. */
    public static int hemisphereOf(Vec3f direction) {
        return direction.z() <= 0f ? FRONT : BACK;
    }

    /**
     * The half-space a hemisphere covers, as a plane with the kept side {@code n . p + d >= 0}. A caster entirely on the negative side cannot appear in that hemisphere's
     * image (add the overlap you render at the seam to the bounds before testing).
     */
    public static Planef halfSpace(int hemisphere, Vec3f position) {
        check(hemisphere);
        return hemisphere == FRONT ? new Planef(0f, 0f, -1f, position.z()) : new Planef(0f, 0f, 1f, -position.z());
    }

    /**
     * Maps a position in the hemisphere's view space to the disc: {@code out[0], out[1]} in the unit disc, {@code out[2]} the depth {@code (distance - near) / (far - near)},
     * so {@code [0, 1]} between the planes.
     *
     * @return {@code false} if the point is at the centre or behind the hemisphere ({@code z > 0}); {@code out} is then not written
     */
    public static boolean project(float x, float y, float z, float near, float far, float[] out) {
        float len = (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (!(len > 0f) || z > 0f) {
            return false;
        }
        float k = 1f / (len - z);
        out[0] = x * k;
        out[1] = y * k;
        out[2] = (len - near) / (far - near);
        return true;
    }

    /**
     * The unit direction (in the hemisphere's view space) that a point of the disc looks along, written to {@code out[0..2]}.
     *
     * @return {@code false} if {@code (u, v)} is outside the unit disc ({@code out} is then not written)
     */
    public static boolean direction(float u, float v, float[] out) {
        float n = u * u + v * v;
        if (!(n <= 1f)) {
            return false;
        }
        float k = 1f / (1f + n);
        out[0] = 2f * u * k;
        out[1] = 2f * v * k;
        out[2] = -(1f - n) * k;
        return true;
    }

    /**
     * A GLSL function that does {@link #project} in a vertex shader: {@code viewPos} is in the hemisphere's view space; the result is the clip position
     * ({@code w} is 1) and {@code side} is positive in front of the hemisphere, to be written to a clip distance. Not compiled by the library's tests.
     */
    public static String glsl() {
        return """
                vec4 dualParaboloid(vec3 viewPos, float near, float far, out float side) {
                    float len = length(viewPos);
                    vec3 d = viewPos / len;
                    side = -d.z;
                    return vec4(d.xy / (1.0 - d.z), (len - near) / (far - near), 1.0);
                }
                """;
    }
}
