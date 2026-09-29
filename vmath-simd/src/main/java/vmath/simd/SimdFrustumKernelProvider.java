package vmath.simd;

import jdk.incubator.vector.FloatVector;
import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernelProvider;

/** Registers {@link SimdFrustumCuller} with {@code FrustumKernels.best()}. */
public final class SimdFrustumKernelProvider implements FrustumKernelProvider {

    /** Public no-argument constructor required by {@link java.util.ServiceLoader}. */
    public SimdFrustumKernelProvider() {
    }

    @Override
    public String name() {
        return "simd";
    }

    @Override
    public int priority() {
        return 100;
    }

    /** Needs at least 128-bit vectors; a two-lane fallback would be slower than the scalar kernel. */
    @Override
    public boolean isSupported() {
        try {
            return FloatVector.SPECIES_PREFERRED.length() >= 4;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public FrustumKernel create() {
        return new SimdFrustumCuller();
    }
}
