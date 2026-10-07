package vmath.lines;

import vmath.annotations.Experimental;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernels;

/**
 * Culls whole polylines of a {@link LineSet} by their bounding boxes, with the frustum kernels that
 * the rest of the library uses ({@link FrustumKernels#best()}: the vector implementation where the
 * machine has one, the scalar otherwise).
 *
 * <p>The result is a {@link VisibilitySet} indexed by the <b>slot</b> of the polylines ({@link
 * LineSet#slot}), which {@link LineSet#update(java.lang.foreign.MemorySegment,
 * java.lang.foreign.MemorySegment, vmath.gl.DrawList, VisibilitySet)} takes to leave the others out
 * of the draws. The boxes are those of the points, so the {@code margin} must cover half the width
 * of the widest line in world units (for a width in pixels, half the width divided by the
 * pixels per world unit at the nearest point); too small a margin cuts the edge of a thick line
 * that has just left the screen.
 *
 * <p>This is the CPU form. The compute-shader culling of {@code vmath.gpucull} decides per
 * <em>instance of a draw</em> and does not apply to polylines whose segments are the instances;
 * the boxes of {@link LineSet#fillBounds} are what a culling pass of your own would read.
 *
 * <p><b>Allocation.</b> The arrays are reused; each call makes the frustum, a few small objects.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one call at a time per instance.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * LineCulling culling = new LineCulling();
 * VisibilitySet visible = new VisibilitySet(set.slotCount());
 * culling.cull(set, relativeViewProjection, DepthRange.NEGATIVE_ONE_TO_ONE, 2.0f, visible);
 * set.update(data, styles, draws, visible);
 * }</pre>
 */
@Experimental("the culling may change")
public final class LineCulling {

    private final FrustumKernel kernel;
    private final BoundsArray bounds = new BoundsArray(64);

    /**
     * Creates a culling pass with the best kernel of this machine.
     */
    public LineCulling() {
        this(FrustumKernels.best());
    }

    /**
     * Creates a culling pass with a given kernel.
     *
     * @param kernel the kernel; must not be {@code null}
     */
    public LineCulling(FrustumKernel kernel) {
        this.kernel = java.util.Objects.requireNonNull(kernel);
    }

    /**
     * Gives the name of the kernel in use.
     *
     * @return the name, such as {@code "scalar"}
     */
    public String kernelName() {
        return kernel.name();
    }

    /**
     * Finds the polylines that may be inside the frustum.
     *
     * @param set the set; must not be {@code null}
     * @param relativeViewProjection the matrix of {@link LineSet#relativeViewProjection}, 16 values
     * @param depth the depth range of the projection
     * @param margin the distance to add to the boxes, in world units, at least 0
     * @param visible receives one bit per slot, after being cleared and resized; the bits of empty
     *     slots are clear
     * @return the number of visible polylines
     * @throws IllegalArgumentException if the matrix is too short or the margin is negative
     */
    public int cull(LineSet set, float[] relativeViewProjection, DepthRange depth, float margin, VisibilitySet visible) {
        if (relativeViewProjection.length < 16) {
            throw new IllegalArgumentException("a matrix has 16 values");
        }
        int n = set.fillBounds(bounds, margin);
        visible.ensureCapacity(n);
        visible.clearAll();
        visible.setAll(n);
        if (n > 0) {
            kernel.cull(Frustumf.fromViewProjection(Mat4f.fromArray(relativeViewProjection, 0), depth), bounds, 0, n, visible);
        }
        for (int slot = 0; slot < n; slot++) {
            if (!set.isSlotLive(slot)) {
                visible.clear(slot);
            }
        }
        return visible.count();
    }
}
