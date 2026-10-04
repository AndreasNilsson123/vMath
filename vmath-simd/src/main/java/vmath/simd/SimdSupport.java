package vmath.simd;

import jdk.incubator.vector.FloatVector;

/**
 * What the two kernel providers share: the name and priority under which the SIMD kernels register,
 * and the test for whether the machine can run them.
 */
final class SimdSupport {

    static final String NAME = "simd";
    static final int PRIORITY = 100;

    private SimdSupport() {
    }

    /**
     * Needs at least 128-bit vectors; a two-lane fallback would be slower than the scalar kernels.
     *
     * <p>Also false when the incubator module is not enabled.
     */
    static boolean vectorsAvailable() {
        try {
            return FloatVector.SPECIES_PREFERRED.length() >= 4;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }
}
