/**
 * vmath: immutable, Valhalla-ready 3D math, geometry, bulk containers and culling.
 *
 * <p>The annotations are source-retention build-time markers, so the dependency is {@code static}: it is needed to
 * compile but never at run time, and the module resolves without it.
 */
module vmath {
    requires static vmath.annotations;

    exports vmath.core;
    exports vmath.geo;
    exports vmath.bulk;
    exports vmath.spatial;
    exports vmath.gl;
    exports vmath.camera;
    exports vmath.pack;
    exports vmath.occlusion;
    exports vmath.mesh;
    exports vmath.anim;
    exports vmath.tex;
    exports vmath.gltf;
    exports vmath.gpucull;

    uses vmath.spatial.FrustumKernelProvider;
}
