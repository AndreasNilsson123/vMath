package vmath.core;

/**
 * The clip-space conventions of the graphics APIs, which differ in two ways that a projection matrix has to get right:
 * the range of NDC depth and the direction of NDC {@code y}.
 *
 * <table>
 *   <caption>Conventions</caption>
 *   <tr><th>Space</th><th>NDC depth (near to far)</th><th>NDC y</th></tr>
 *   <tr><td>{@link #OPENGL}</td><td>-1 to 1</td><td>up (+1 is the top of the image)</td></tr>
 *   <tr><td>{@link #VULKAN}</td><td>0 to 1</td><td>down (+1 is the bottom of the image)</td></tr>
 *   <tr><td>{@link #D3D}</td><td>0 to 1</td><td>up</td></tr>
 * </table>
 *
 * <p>Pass one to the projection builders of {@link Mat4f} (for example {@code Mat4f.perspective(fovy, aspect, near, far, ClipSpace.VULKAN)}) instead of the
 * boolean {@code zZeroToOne} overloads, which cannot express the Vulkan Y flip. For culling, {@code DepthRange.of(space)} gives the matching
 * depth convention for {@code Frustumf.fromViewProjection}; a Y flip swaps the top and bottom planes, so the set of six planes is the same.
 */
public enum ClipSpace {
    /** OpenGL's default: depth in [-1, 1], y up. (GL with {@code glClipControl(..., GL_ZERO_TO_ONE)} is {@link #D3D} here.) */
    OPENGL(false, false),
    /** Vulkan: depth in [0, 1], y down. */
    VULKAN(true, true),
    /** Direct3D (and Metal): depth in [0, 1], y up. */
    D3D(true, false);

    private final boolean zeroToOne;
    private final boolean yDown;

    ClipSpace(boolean zeroToOne, boolean yDown) {
        this.zeroToOne = zeroToOne;
        this.yDown = yDown;
    }

    /** True if NDC depth runs from 0 at the near plane to 1 at the far plane, false for -1 to 1. */
    public boolean zeroToOne() {
        return zeroToOne;
    }

    /** True if positive NDC {@code y} points down the image (Vulkan). */
    public boolean yDown() {
        return yDown;
    }
}
