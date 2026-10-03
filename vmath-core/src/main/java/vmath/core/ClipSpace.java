package vmath.core;

/**
 * The clip-space conventions of the graphics APIs, which differ in two ways that a projection
 * matrix has to get right: the range of NDC depth and the direction of NDC {@code y}.
 *
 * <table> <caption>Conventions</caption>
 *   <tr><th>Space</th><th>NDC depth (near to far)</th><th>NDC y</th></tr>
 *   <tr><td>{@link #OPENGL}</td><td>-1 to 1</td><td>up (+1 is the top of the image)</td></tr>
 *   <tr><td>{@link #VULKAN}</td><td>0 to 1</td><td>down (+1 is the bottom of the image)</td></tr>
 *   <tr><td>{@link #D3D}</td><td>0 to 1</td><td>up</td></tr>
 * </table>
 *
 * <p>Pass one to the projection builders of {@link Mat4f} (for example
 * {@code Mat4f.perspective(fovy, aspect, near, far, ClipSpace.VULKAN)}) instead of the boolean
 * {@code zZeroToOne} overloads, which cannot express the Vulkan Y flip. For culling,
 * {@code DepthRange.of(space)} gives the matching depth convention for
 * {@code Frustumf.fromViewProjection}; a Y flip swaps the top and bottom planes, so the set of six
 * planes is the same.
 *
 * <p><b>Thread safety.</b> Immutable: the constants can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mat4f vulkan = Mat4f.perspective(1f, 16f / 9f, 0.1f, 100f, ClipSpace.VULKAN);   // depth 0 to 1, y down
 * Mat4f opengl = Mat4f.perspective(1f, 16f / 9f, 0.1f, 100f, ClipSpace.OPENGL);   // depth -1 to 1
 * boolean flip = ClipSpace.VULKAN.yDown();                                          // true
 * }</pre>
 */
public enum ClipSpace {
    /**
     * OpenGL's default: depth in [-1, 1], y up.
     *
     * <p>(GL with {@code glClipControl(..., GL_ZERO_TO_ONE)} is {@link #D3D} here.)
     */
    OPENGL(false, false),
    /**
     * Vulkan: depth in [0, 1], y down.
     */
    VULKAN(true, true),
    /**
     * Direct3D (and Metal): depth in [0, 1], y up.
     */
    D3D(true, false);

    private final boolean zeroToOne;
    private final boolean yDown;

    ClipSpace(boolean zeroToOne, boolean yDown) {
        this.zeroToOne = zeroToOne;
        this.yDown = yDown;
    }

    /**
     * Reports the depth convention of this clip space: whether device depth is 0 to 1 or -1 to 1.
     *
     * @return {@code true} if NDC depth runs from 0 at the near plane to 1 at the far plane, false
     *     for -1 to 1
     */
    public boolean zeroToOne() {
        return zeroToOne;
    }

    /**
     * Reports the vertical convention of this clip space: whether screen-space y grows downwards.
     *
     * @return {@code true} if positive NDC {@code y} points down the image (Vulkan)
     */
    public boolean yDown() {
        return yDown;
    }
}
