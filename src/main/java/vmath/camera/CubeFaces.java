package vmath.camera;

import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

/**
 * The six faces of a cube map, for point-light shadows and reflection probes: which way each face looks, and its view,
 * projection and culling frustum. Every face is a 90 degree perspective view with aspect 1, so the six frusta tile the sphere
 * around the light with no gaps.
 *
 * <p>The orientation is the one OpenGL and Vulkan use for cube map faces (the {@code up} vectors below), so a rendered face can
 * be uploaded to the layer with the same index and sampled with an ordinary direction vector. Face order is the layer order:
 * {@code +X, -X, +Y, -Y, +Z, -Z}.
 *
 * <p>For {@link DepthRange#REVERSED_ZERO_TO_ONE} the projection has an infinite far plane (there is no finite reversed builder),
 * so {@code far} is ignored there.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class CubeFaces {

    /** The face looking along +X. */
    public static final int POSITIVE_X = 0;
    /** The face looking along -X. */
    public static final int NEGATIVE_X = 1;
    /** The face looking along +Y. */
    public static final int POSITIVE_Y = 2;
    /** The face looking along -Y. */
    public static final int NEGATIVE_Y = 3;
    /** The face looking along +Z. */
    public static final int POSITIVE_Z = 4;
    /** The face looking along -Z. */
    public static final int NEGATIVE_Z = 5;
    /** The number of faces, 6. */
    public static final int COUNT = 6;

    private static final Vec3f[] DIRECTION = {
            new Vec3f(1f, 0f, 0f), new Vec3f(-1f, 0f, 0f),
            new Vec3f(0f, 1f, 0f), new Vec3f(0f, -1f, 0f),
            new Vec3f(0f, 0f, 1f), new Vec3f(0f, 0f, -1f)};
    private static final Vec3f[] UP = {
            new Vec3f(0f, -1f, 0f), new Vec3f(0f, -1f, 0f),
            new Vec3f(0f, 0f, 1f), new Vec3f(0f, 0f, -1f),
            new Vec3f(0f, -1f, 0f), new Vec3f(0f, -1f, 0f)};

    private CubeFaces() {
    }

    private static void check(int face) {
        if (face < 0 || face >= COUNT) {
            throw new IllegalArgumentException("face must be in [0, 5]: " + face);
        }
    }

    /** The direction the face looks along (a unit axis). */
    public static Vec3f direction(int face) {
        check(face);
        return DIRECTION[face];
    }

    /** The face's up vector (the direction of increasing texture {@code v} flipped as the GL/Vulkan convention has it). */
    public static Vec3f up(int face) {
        check(face);
        return UP[face];
    }

    /** The face whose axis is closest to {@code direction} (the one a cube map lookup with that vector reads). */
    public static int faceOf(Vec3f direction) {
        float ax = Math.abs(direction.x()), ay = Math.abs(direction.y()), az = Math.abs(direction.z());
        if (ax >= ay && ax >= az) {
            return direction.x() >= 0f ? POSITIVE_X : NEGATIVE_X;
        }
        if (ay >= az) {
            return direction.y() >= 0f ? POSITIVE_Y : NEGATIVE_Y;
        }
        return direction.z() >= 0f ? POSITIVE_Z : NEGATIVE_Z;
    }

    /** World to view for a face, with the eye at {@code position}. */
    public static Mat4f view(int face, Vec3f position) {
        check(face);
        return Mat4f.lookTo(position, DIRECTION[face], UP[face]);
    }

    /** The shared 90 degree, aspect 1 projection in the given depth convention. */
    public static Mat4f projection(float near, float far, DepthRange depth) {
        float fovy = (float) (Math.PI / 2.0);
        return switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> Mat4f.perspective(fovy, 1f, near, far, false);
            case ZERO_TO_ONE -> Mat4f.perspective(fovy, 1f, near, far, true);
            case REVERSED_ZERO_TO_ONE -> Mat4f.perspectiveReversedZ(fovy, 1f, near);
        };
    }

    /** {@code projection * view} for a face. */
    public static Mat4f viewProjection(int face, Vec3f position, float near, float far, DepthRange depth) {
        return projection(near, far, depth).mul(view(face, position));
    }

    /** Culling frustum of a face. */
    public static Frustumf frustum(int face, Vec3f position, float near, float far, DepthRange depth) {
        return Frustumf.fromViewProjection(viewProjection(face, position, near, far, depth), depth);
    }
}
