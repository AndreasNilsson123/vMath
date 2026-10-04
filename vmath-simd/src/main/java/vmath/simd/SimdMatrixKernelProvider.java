package vmath.simd;

import vmath.bulk.MatrixKernel;
import vmath.bulk.MatrixKernelProvider;

/**
 * Registers {@link SimdMatrixKernel} with {@code MatrixKernels.best()}.
 *
 * <p><b>Thread safety.</b> Not specified: the library does not define the threading behavior of
 * implementations of this interface; see the methods for what they promise.
 */
public final class SimdMatrixKernelProvider extends SimdProvider implements MatrixKernelProvider {

    /**
     * Creates the provider; the constructor is public and takes no arguments, as
     * {@link java.util.ServiceLoader} requires.
     */
    public SimdMatrixKernelProvider() {
    }

    @Override
    public MatrixKernel create() {
        return new SimdMatrixKernel();
    }
}
