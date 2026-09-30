package vmath.geo;

/**
 * Clip-space depth convention of a projection matrix. Frustum-plane extraction needs it because the near and far
 * planes come from different matrix rows in each convention.
 */
public enum DepthRange {
    /** OpenGL default: NDC z in [-1, 1], near maps to -1. */
    NEGATIVE_ONE_TO_ONE,
    /** Vulkan, D3D and GL with {@code glClipControl(..., GL_ZERO_TO_ONE)}: NDC z in [0, 1], near maps to 0. */
    ZERO_TO_ONE,
    /** Reversed-Z: NDC z in [0, 1] with near mapping to 1 and far (or infinity) to 0. */
    REVERSED_ZERO_TO_ONE;

    /** The depth convention of a conventional (not reversed) projection built for {@code space}. */
    public static DepthRange of(vmath.core.ClipSpace space) {
        return space.zeroToOne() ? ZERO_TO_ONE : NEGATIVE_ONE_TO_ONE;
    }
}
