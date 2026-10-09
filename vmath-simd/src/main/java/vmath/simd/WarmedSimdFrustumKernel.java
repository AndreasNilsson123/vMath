package vmath.simd;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.geo.Frustumf;
import vmath.spatial.FrustumCuller;
import vmath.spatial.FrustumKernel;

/**
 * The {@code "simd"} frustum kernel that {@code FrustumKernels.best()} returns: it culls with the
 * scalar kernel until {@link SimdWarmUp} has seen the vector kernel beat it, then with the vector
 * kernel. The two give bit-identical results, so the switch cannot be seen except in the time and
 * in the allocation (the vector kernel allocates a lot until the JIT has compiled it).
 *
 * <p>Internal: reached through the provider.
 *
 * <p><b>Thread safety.</b> Not thread-safe: use one instance per thread.
 */
final class WarmedSimdFrustumKernel implements FrustumKernel {

    private final SimdFrustumCuller vector = new SimdFrustumCuller();
    private final FrustumCuller scalar = new FrustumCuller();

    WarmedSimdFrustumKernel() {
        SimdWarmUp.startFrustumWarmUp();
    }

    @Override
    public String name() {
        return SimdSupport.NAME;
    }

    @Override
    public void cull(Frustumf frustum, BoundsArray bounds, int from, int to, VisibilitySet visible) {
        if (SimdWarmUp.isFrustumKernelReady()) {
            vector.cull(frustum, bounds, from, to, visible);
        } else {
            scalar.cull(frustum, bounds, from, to, visible);
        }
    }
}
