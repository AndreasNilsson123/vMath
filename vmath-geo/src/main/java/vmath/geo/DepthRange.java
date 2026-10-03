package vmath.geo;

/**
 * Clip-space depth convention of a projection matrix.
 *
 * <p>Frustum-plane extraction needs it because the near and far planes come from different matrix
 * rows in each convention.
 *
 * <p><b>Thread safety.</b> Immutable: the constants can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DepthRange range = DepthRange.of(ClipSpace.VULKAN);            // ZERO_TO_ONE
 * Frustumf frustum = Frustumf.fromViewProjection(Mat4f.perspective(1f, 1f, 0.1f, 10f, ClipSpace.VULKAN), range);
 * }</pre>
 */
public enum DepthRange {
    /**
     * OpenGL default: NDC z in [-1, 1], near maps to -1.
     */
    NEGATIVE_ONE_TO_ONE,
    /**
     * Vulkan, D3D and GL with {@code glClipControl(..., GL_ZERO_TO_ONE)}: NDC z in [0, 1], near
     * maps to 0.
     */
    ZERO_TO_ONE,
    /**
     * Reversed-Z: NDC z in [0, 1] with near mapping to 1 and far (or infinity) to 0.
     */
    REVERSED_ZERO_TO_ONE;

    /**
     * Describes the depth conventions of a projection built for a clip space: whether depth is zero
     * to one or minus one to one.
     *
     * @param space the space; must not be {@code null}
     * @return the depth convention of a conventional (not reversed) projection built for
     *     {@code space}
     */
    public static DepthRange of(vmath.core.ClipSpace space) {
        return space.zeroToOne() ? ZERO_TO_ONE : NEGATIVE_ONE_TO_ONE;
    }
}
