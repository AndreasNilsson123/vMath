package vmath.simd;

import vmath.bulk.MatrixKernel;
import vmath.bulk.MatrixKernelProvider;

/** Registers {@link SimdMatrixKernel} with {@code MatrixKernels.best()}. */
public final class SimdMatrixKernelProvider implements MatrixKernelProvider {

    /** Public no-argument constructor required by {@link java.util.ServiceLoader}. */
    public SimdMatrixKernelProvider() {
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
    public MatrixKernel create() {
        return new SimdMatrixKernel();
    }
}
