package vmath.simd;

import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernelProvider;

/**
 * Registers the SIMD frustum kernel with {@code FrustumKernels.best()}: {@link SimdFrustumCuller}
 * once {@link SimdWarmUp} has seen it beat the scalar kernel, the scalar kernel until then (the
 * results are bit-identical).
 *
 * <p><b>Thread safety.</b> Not specified: the library does not define the threading behavior of
 * implementations of this interface; see the methods for what they promise.
 */
public final class SimdFrustumKernelProvider extends SimdProvider implements FrustumKernelProvider {

    /**
     * Creates the provider; the constructor is public and takes no arguments, as
     * {@link java.util.ServiceLoader} requires.
     */
    public SimdFrustumKernelProvider() {
    }

    @Override
    public FrustumKernel create() {
        return new WarmedSimdFrustumKernel();
    }
}
