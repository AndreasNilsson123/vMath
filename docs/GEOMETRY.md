# Convex geometry and polygons

All classes here are `@Experimental`. They are tested against independent oracles (brute-force geometry, Monte Carlo, exact arithmetic) with seeded random inputs;
**no benchmark has been run for them yet, so this page gives no speed figures.**

## `Polygons`: polygons in 2D and 3D

Arrays of `x, y` pairs (or `x, y, z` triples for the 3D variants) with a vertex count, no objects.

- `signedArea`, `winding`, `contains`, `isSimple`.
- `triangulate`: ear clipping on a linked ring, for a simple polygon, a polygon with holes (Eberly bridging, `ringEnds` marks where each ring ends) and `triangulate3` for a planar
  polygon in 3D (the Newell normal picks the projection). Output is a list of vertex-index triples.
- `clipHalfPlane`, `clipConvex` and `clipPlane3`: Sutherland-Hodgman against a half-plane, a convex polygon, or a plane in 3D. A vertex exactly on the clip line is not
  duplicated.

Ear clipping is O(n²) in the worst case; it is meant for the polygons of tools and level geometry, not for terrain-sized input.

## `ConvexHull`, `ConvexPolytope`

`ConvexHull.of(points, count)` is a quickhull whose visibility decisions use the exact `Predicates.orient3d`, followed by an exact pass that removes points lying on an edge or in a
facet, so the vertices are the true extreme points (a lattice of points gives its corners only). Degenerate input is handled by dimension: `dimension()` is 0 to 3 and the result
for lower dimensions is the point, the segment or the polygon. `volume()` and `surfaceArea()` are available.

`ConvexPolytope.of(points, count)` (or `of(Aabbf)`) wraps a solid hull as a `ConvexShape` with an exact `contains`, and the distinct face and edge directions for `Sat`. Faces in the
same plane share one direction, so a box has 3 face directions however it is triangulated.

## `Sat` and `Gjk`

- `Sat.separation(a, b, axis)`: the largest signed separation over the face normals and the edge cross products. Negative values are the exact penetration depth with the axis to
  move along; positive values are a lower bound of the distance (use `Gjk` for the distance). Meant for polytopes of a few dozen faces.
- `Gjk` (one instance owns its scratch memory, no allocation per query): `intersects`, `distance` (closest points on both shapes) and `penetration` (EPA: depth, normal and contact
  points). Any `ConvexShape` works: spheres, boxes, oriented boxes, capsules, polytopes, transformed, translated and inflated shapes (`ConvexShapes`). The shapes only need a
  support function.

Known limit, measured against analytic answers in the tests: for two nearly concentric smooth shapes (two spheres whose centres almost coincide) the EPA depth and normal converge to
about 1e-3 of the radius, because every direction is nearly equally close. The tests compare the other combinations (boxes, capsules, polytopes) with independent oracles at much tighter tolerances.
