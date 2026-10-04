package vmath.simd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jdk.incubator.vector.FloatVector;
import org.junit.jupiter.api.Test;
import vmath.bulk.MatrixKernels;
import vmath.spatial.FrustumKernels;

/**
 * Which machines the kernels claim to support, and the lane width they run with.
 *
 * <p>The Gradle tasks {@code testVector8} to {@code testVector64} run these tests (and the kernel
 * tests, except for 8) with {@code -XX:MaxVectorSize}, which limits {@link
 * FloatVector#SPECIES_PREFERRED}: that is how the tails of the kernels are checked at every lane
 * width, not only at the width of the machine that runs the build.
 */
class SimdSupportTest {

    @Test
    void vectorsAreAvailableFromFourLanesOn() {
        int lanes = FloatVector.SPECIES_PREFERRED.length();
        assertEquals(lanes >= 4, SimdSupport.vectorsAvailable(), "lanes: " + lanes);
    }

    @Test
    void theLaneCountFollowsTheRequestedVectorSize() {
        String max = System.getProperty("vmath.simd.maxVectorBytes");
        if (max == null) {
            return; // only the extra Gradle tasks say what to expect
        }
        int lanes = FloatVector.SPECIES_PREFERRED.length();
        assertTrue(lanes <= Integer.parseInt(max) / 4, "lanes " + lanes + " with a maximum of " + max + " bytes");
    }

    @Test
    void narrowVectorsMeanNoProviderAndTheScalarKernels() {
        if (!Boolean.getBoolean("vmath.simd.expectUnsupported")) {
            return; // only the narrow-vector Gradle task sets this
        }
        assertFalse(SimdSupport.vectorsAvailable());
        assertFalse(new SimdFrustumKernelProvider().isSupported());
        assertFalse(new SimdMatrixKernelProvider().isSupported());
        assertEquals("scalar", MatrixKernels.best().name());
        assertEquals("scalar", FrustumKernels.best().name());
        assertEquals(java.util.List.of("scalar"), MatrixKernels.available());
        assertEquals(java.util.List.of("scalar"), FrustumKernels.available());
    }
}
