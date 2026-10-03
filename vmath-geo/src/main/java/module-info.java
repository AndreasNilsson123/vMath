/**
 * vmath-geo: shapes, intersection tests, convex geometry, SDFs, vertex packing and the physics math.
 */
module vmath.geo {
    requires static vmath.annotations;
    requires transitive vmath.core;

    exports vmath.geo;
    exports vmath.pack;
    exports vmath.physics;
}
