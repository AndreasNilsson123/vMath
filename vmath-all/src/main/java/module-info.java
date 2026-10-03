/**
 * vmath: immutable, Valhalla-ready 3D math, geometry, bulk containers and culling. This module has no packages of its own: it is the one name for the four parts
 * ({@code vmath.core}, {@code vmath.geo}, {@code vmath.scene}, {@code vmath.render}), so that a consumer that wants everything still writes {@code requires vmath}.
 */
module vmath {
    requires transitive vmath.core;
    requires transitive vmath.geo;
    requires transitive vmath.scene;
    requires transitive vmath.render;
}
