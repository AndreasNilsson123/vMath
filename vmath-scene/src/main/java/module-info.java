/**
 * vmath-scene: bulk containers and kernels, spatial structures and culling, occlusion, animation,
 * GPU data layouts and utilities.
 */
module vmath.scene {
    requires static vmath.annotations;
    requires transitive vmath.core;
    requires transitive vmath.geo;

    exports vmath.bulk;
    exports vmath.spatial;
    exports vmath.occlusion;
    exports vmath.anim;
    exports vmath.gl;
    exports vmath.util;

    uses vmath.spatial.FrustumKernelProvider;
    uses vmath.bulk.MatrixKernelProvider;
}
