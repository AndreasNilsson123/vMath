package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.geo.Frustumf;

/**
 * A batch frustum-culling implementation over {@link BoundsArray}. The scalar {@link FrustumCuller} is always available;
 * an optional SIMD implementation is provided by the {@code vmath-simd} module and found through
 * {@link FrustumKernels#best()}.
 *
 * <p><b>Contract shared by all kernels</b> (the tests run every kernel against the same oracle):
 * <ul>
 *   <li>An object is visible iff the minimum, over the six planes, of the signed distance of its box corner farthest
 *       along the plane normal is <b>not negative</b>. Touching counts as visible; a NaN distance counts as visible, so a
 *       kernel never culls something it cannot judge.</li>
 *   <li>Only bits of objects <b>outside</b> are cleared; the bits of visible objects, and all bits outside
 *       {@code [from, to)}, are left as they were.</li>
 *   <li>{@code from} must be a multiple of 64, so concurrent calls on disjoint ranges write disjoint bitset words.</li>
 *   <li>No allocation per call.</li>
 * </ul>
 *
 * <p>An instance owns scratch memory and is <b>not thread-safe</b>; use one per thread.
 */
public interface FrustumKernel {

    /** Culls objects {@code [from, to)} of {@code bounds}, clearing the bits of those outside {@code frustum}. */
    void cull(Frustumf frustum, BoundsArray bounds, int from, int to, VisibilitySet visible);

    /** Culls every object. */
    default void cull(Frustumf frustum, BoundsArray bounds, VisibilitySet visible) {
        cull(frustum, bounds, 0, bounds.size(), visible);
    }

    /** Short identifier, e.g. {@code "scalar"} or {@code "simd"}. */
    String name();
}
