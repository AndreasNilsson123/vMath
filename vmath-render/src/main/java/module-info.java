/**
 * vmath-render: cameras, mesh processing, the glTF loader and the GPU culling data.
 */
module vmath.render {
    requires static vmath.annotations;
    requires transitive vmath.core;
    requires transitive vmath.geo;
    requires transitive vmath.scene;

    exports vmath.camera;
    exports vmath.lighting;
    exports vmath.sky;
    exports vmath.mesh;
    exports vmath.gltf;
    exports vmath.gpucull;
    exports vmath.map;
}
