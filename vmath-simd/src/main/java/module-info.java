/**
 * Optional SIMD kernels for vmath, built on the incubating Vector API.
 *
 * <p>Resolving this module needs {@code --add-modules jdk.incubator.vector}; without it vmath falls
 * back to its scalar kernels.
 */
module vmath.simd {
    requires transitive vmath.scene;
    requires jdk.incubator.vector;

    exports vmath.simd;

    provides vmath.spatial.FrustumKernelProvider with vmath.simd.SimdFrustumKernelProvider;
    provides vmath.bulk.MatrixKernelProvider with vmath.simd.SimdMatrixKernelProvider;
}
