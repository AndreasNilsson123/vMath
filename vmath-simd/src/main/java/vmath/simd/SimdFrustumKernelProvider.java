package vmath.simd;

import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernelProvider;

/** Registers {@link SimdFrustumCuller} with {@code FrustumKernels.best()}. */
public final class SimdFrustumKernelProvider implements FrustumKernelProvider {

    /** Public no-argument constructor required by {@link java.util.ServiceLoader}. */
    public SimdFrustumKernelProvider() {
    }

    @Override
    public String name() {
        return SimdSupport.NAME;
    }

    @Override
    public int priority() {
        return SimdSupport.PRIORITY;
    }

    @Override
    public boolean isSupported() {
        return SimdSupport.vectorsAvailable();
    }

    @Override
    public FrustumKernel create() {
        return new SimdFrustumCuller();
    }
}
