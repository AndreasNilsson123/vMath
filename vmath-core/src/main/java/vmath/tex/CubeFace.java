package vmath.tex;

import vmath.annotations.Experimental;
import vmath.core.Vec3f;

/**
 * The six faces of a cube map in the order OpenGL, Vulkan, D3D and KTX2 all use for array layers, with the standard mapping between a direction and a
 * position on a face (the OpenGL specification's cube map selection table, also what Vulkan specifies).
 *
 * <p>{@code u} and {@code v} run from 0 to 1 across the face, {@code (0, 0)} at the texel image's first texel (top-left in the usual image orientation).
 * These are the conventions of the <em>texture coordinate</em> space; a renderer that stores faces upside down must flip {@code v} itself.
 */
@Experimental("the orientation conventions are checked against the specification table only, not against a GPU")
public enum CubeFace {
    /** The face the +X axis points into. */
    POSITIVE_X,
    /** The face the -X axis points into. */
    NEGATIVE_X,
    /** The face the +Y axis points into. */
    POSITIVE_Y,
    /** The face the -Y axis points into. */
    NEGATIVE_Y,
    /** The face the +Z axis points into. */
    POSITIVE_Z,
    /** The face the -Z axis points into. */
    NEGATIVE_Z;

    /** The face a direction points at (the axis with the largest magnitude; ties go to x, then y, then z). */
    public static CubeFace of(float x, float y, float z) {
        float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        if (ax >= ay && ax >= az) {
            return x >= 0f ? POSITIVE_X : NEGATIVE_X;
        }
        if (ay >= az) {
            return y >= 0f ? POSITIVE_Y : NEGATIVE_Y;
        }
        return z >= 0f ? POSITIVE_Z : NEGATIVE_Z;
    }

    /** The {@code u} coordinate (0 to 1) of direction {@code (x, y, z)} on {@code face}; the direction must point into that face. */
    public float u(float x, float y, float z) {
        float sc, ma;
        switch (this) {
            case POSITIVE_X -> { sc = -z; ma = x; }
            case NEGATIVE_X -> { sc = z; ma = x; }
            case POSITIVE_Y -> { sc = x; ma = y; }
            case NEGATIVE_Y -> { sc = x; ma = y; }
            case POSITIVE_Z -> { sc = x; ma = z; }
            default -> { sc = -x; ma = z; }
        }
        return 0.5f * (sc / Math.abs(ma) + 1f);
    }

    /** The {@code v} coordinate (0 to 1) of direction {@code (x, y, z)} on this face. */
    public float v(float x, float y, float z) {
        float tc, ma;
        switch (this) {
            case POSITIVE_X -> { tc = -y; ma = x; }
            case NEGATIVE_X -> { tc = -y; ma = x; }
            case POSITIVE_Y -> { tc = z; ma = y; }
            case NEGATIVE_Y -> { tc = -z; ma = y; }
            case POSITIVE_Z -> { tc = -y; ma = z; }
            default -> { tc = -y; ma = z; }
        }
        return 0.5f * (tc / Math.abs(ma) + 1f);
    }

    /** A (not normalised) direction through {@code (u, v)} of this face, the inverse of {@link #u} and {@link #v}. */
    public Vec3f direction(float u, float v) {
        float sc = 2f * u - 1f, tc = 2f * v - 1f;
        return switch (this) {
            case POSITIVE_X -> new Vec3f(1f, -tc, -sc);
            case NEGATIVE_X -> new Vec3f(-1f, -tc, sc);
            case POSITIVE_Y -> new Vec3f(sc, 1f, tc);
            case NEGATIVE_Y -> new Vec3f(sc, -1f, -tc);
            case POSITIVE_Z -> new Vec3f(sc, -tc, 1f);
            case NEGATIVE_Z -> new Vec3f(-sc, -tc, -1f);
        };
    }
}
