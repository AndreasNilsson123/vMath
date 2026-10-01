package vmath.simd;

import jdk.incubator.vector.FloatVector;
import vmath.bulk.MatrixKernel;
import vmath.bulk.MatrixKernelProvider;

/** Registers {@link SimdMatrixKernel} with {@code MatrixKernels.best()}. */
public final class SimdMatrixKernelProvider implements MatrixKernelProvider {

    /** Public no-argument constructor required by {@link java.util.ServiceLoader}. */
    public SimdMatrixKernelProvider() {
    }

    @Override
    public String name() {
        return "simd";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean isSupported() {
        try {
            return FloatVector.SPECIES_PREFERRED.length() >= 4;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public MatrixKernel create() {
        return new SimdMatrixKernel();
    }
}
