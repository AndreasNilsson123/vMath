# Convex geometry and polygons

All classes here are `@Experimental`. They are tested against independent oracles (brute-force geometry, Monte Carlo, exact arithmetic) with seeded random inputs;
speed figures are at the end.

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

## Measured speed

JMH `GeometryBench`, 1 fork of 5 one-second iterations after 3 warm-up iterations, JDK 25, single thread, 2026-10-03. Averages per call; the `±` is the 99.9% interval JMH reports.

| Case | Time per call |
|---|---|
| `ConvexHull.of`, 100 random points in a ball | 877 µs ± 77 µs |
| `ConvexHull.of`, 1 000 points | 3.52 ms ± 0.48 ms |
| `ConvexHull.of`, 10 000 points | 14.2 ms ± 1.3 ms |
| `Polygons.triangulate`, star polygon of 100 vertices | 38.0 µs ± 2.4 µs |
| `Polygons.triangulate`, star polygon of 1 000 vertices | 6.80 ms ± 0.47 ms |
| `Sat.separation`, two 40-point polytopes | 482 µs ± 19 µs |
| `Gjk.distance`, box and capsule | 261 ns ± 21 ns |
| `Gjk.distance`, two 40-point polytopes (apart) | 141 ns ± 15 ns |
| `Gjk.distance`, two spheres | 792 ns ± 54 ns |
| `Gjk.intersects`, two overlapping 40-point polytopes | 461 ns ± 20 ns |
| `Gjk.penetration` (EPA), two overlapping 40-point polytopes | 3.14 µs ± 0.36 µs |
| `Gjk.penetration` (EPA), box and sphere | 2.54 µs ± 3.22 µs (large spread) |

What the numbers say:

- The hull cost follows the size of the **output** more than the input: points in a ball are nearly all extreme, so 100 points cost relatively much (about 8.8 µs per point) while 10 000
  points cost 1.4 µs per point (few of them are vertices). The exact pass that removes points lying on edges and facets compares every vertex with every face. Points on a
  sphere would be the slow case; it was not measured.
- Ear clipping grows faster than the square of the vertex count on this reflex-heavy star: 10 times the vertices cost 180 times as much.
- `Sat` is slow for polytopes with many edges (it tries every pair of edge directions, and each projection scans all vertices, about 5 000 axes here). For anything beyond boxes and
  small polytopes use `Gjk`, which was 460 ns for the same yes/no question.
- GJK and EPA allocate nothing per query (`AllocationContractTest`).

## Bounding volumes: `BoundingVolumes`, `KDop`

- `BoundingVolumes.minimumSphere`: the **smallest** enclosing sphere (Welzl's algorithm in the move-to-front form with at most four support points, run in a fixed pseudo-random order so that
  sorted or adversarial input does not make it slow). The test compares the radius with a brute force over every sphere through 2, 3 or 4 of the points for 300 random sets of up to
  10 points, and checks cubes, rings, collinear and duplicate points, and 200 000 points in sorted order. The result is rounded outward: every input point is inside as float arithmetic sees it.
- `BoundingVolumes.pcaBox`: an oriented box in the frame of the principal axes of the points (the eigenvectors of their covariance, by Jacobi rotations). For 3 000 points filling a rotated box
  with distinct side lengths its volume was within 25% above the true box in every one of 100 trials (the test's limit; it is not the minimum-volume oriented box, and a dense cluster pulls the
  axes towards itself).
- `BoundingVolumes.transformedBox` and `sphereOfTransformedBox`: the box and sphere around an axis-aligned box after an affine transform. For a transform without shear the oriented box is
  exactly the transformed box (volume equal to the determinant times the volume, tested for 300 random transforms including mirrors); with shear it is the tightest box in the rotation's frame.
  `Aabbf.transform` (Arvo's method, exact for the axis-aligned result) already existed.
- `KDop` (6, 14, 18 or 26 directions): slab intervals along the axes and the diagonals, built from points or a box, with `contains`, `overlaps`, `union`, `expand` and `aabb`. Overlap is the
  separating-axis test over those directions: conservative (never misses touching volumes), cheaper than testing the shapes themselves, and tighter than boxes for diagonal shapes (a 14-DOP of
  two diagonal squares separates them where their boxes overlap, a test).

| Call | Time |
|---|---|
| `minimumSphere`, 1 000 points in a ball | 112 µs ± 11 µs |
| `minimumSphere`, 10 000 points | 1.01 ms ± 0.06 ms |
| `pcaBox`, 1 000 points | 18.6 µs ± 0.9 µs |
| `KDop.of(14, ...)`, 1 000 points | 29.6 µs ± 2.5 µs |
| `KDop.overlaps`, 14-DOP against 14-DOP | 13.6 ns ± 1.1 ns |
| `transformedBox` | 108 ns ± 7 ns |

`minimumSphere` grows linearly with the point count (10 times the points took 9 times as long). All measured with `RoadmapBench`, JDK 25, single thread, 2026-10-03.

## Signed distance fields: `Sdf`, `Sdfs`, `SurfaceNets`

An `Sdf` is a function `float distance(x, y, z)`: negative inside a solid, zero on its surface. `Sdfs` makes them and combines them, on the CPU, for picking and for generating meshes (the shader side is not covered).

- **Primitives**: `sphere`, `box`, `roundBox`, `plane`, `capsule`, `cylinder` (axis y, flat caps), `torus` (axis y). All are exact distances.
- **CSG**: `union` (also over any number), `intersection`, `subtract`, the smooth versions with a blend width `k` (`smoothUnion`, `smoothIntersection`, `smoothSubtract`: polynomial smooth minimum, never more than `k / 4`
  from the hard result), and `invert`, `onion` (a shell), `round` (offset). **Moving**: `translate`, `rotate`, uniform `scale`, `repeat` (domain repetition; exact only for solids that fit their cell).
- **Honesty about what a field is.** The union of two exact distances is exact outside both solids and only a *bound* inside them; the same holds for the other operations. What always holds is that the zero set and the sign
  are exact and that every function made here is 1-Lipschitz (never changes faster than the distance between the points), and that is all sphere tracing and meshing need. `SdfsTest` checks the Lipschitz bound on random
  point pairs for every primitive and every operation, the unit gradient of the exact primitives, the sign of each CSG operation against the inside tests of its operands, and the primitives against independent
  references (a clamped-point box distance, a ternary search on the capsule's segment, a dense sample of the torus and cylinder surface).
- **Queries**: `normal` (four-sample tetrahedron gradient, no allocation), `project` (Newton steps onto the surface; one step for an exact distance), and `raycast`, sphere tracing with a reusable `Hit` (point, normal,
  step count, `inside` when the ray starts in the solid). A miss because the step budget ran out is reported as a miss; grazing rays are the usual cause, and the test `raycastLimitsInsideStartsAndAlternativeSolids`
  shows it with a thin plate. Against the analytic ray-sphere intersection (5000 random rays, most of them aimed at the sphere) the hit distance agrees to within `epsilon / cos(angle of incidence)`, which is the
  accuracy the stopping criterion allows.
- **Meshing**: `SurfaceNets` (Naive Surface Nets). The field is sampled on a grid, each cell the surface crosses gets one vertex (the mean of the edge crossings), each crossed edge a quad (split along the shorter
  diagonal). The mesh is closed, 2-manifold and wound counter-clockwise from outside when the solid lies inside the box with a cell to spare: tested for a sphere (Euler characteristic 2, volume within 4% of 4/3 pi at
  32 cells per 3.2 units, checked through `MassProperties.ofMesh`, which rejects inside-out meshes), a torus (Euler 0, volume within 5%), a cube with a cavity and a bore (Euler 0, volume against a random estimate)
  and two smoothly blended spheres (Euler 2). `projection(n)` moves each vertex onto the surface by `n` Newton steps within its cell: on the sphere with 16 cells the largest distance of a vertex from the surface fell
  by more than a factor of five and below 1e-3 with two steps. `normals(true)` gives gradient normals; `isoLevel` meshes an offset surface. Surfaces that leave the box give an open mesh at the border.
  **Sharp edges and corners are rounded off by about a cell**: this is the smoothed average of surface nets, not dual contouring's quadric error minimisation, which is not built.

Measured (`SdfBench`, JMH, JDK 25, one thread; the scene is two smoothly blended spheres united with a box that has a spherical cavity, all behind a rotation):

| Operation | Time | Allocation |
|---|---|---|
| one evaluation, sphere | 3.8 ns | 0 |
| one evaluation, the scene | 28.4 ns | 0 |
| `normal` on the scene (4 evaluations) | 109 ns | 0 |
| `raycast` through the scene (budget 128 steps) | about 320 ns | 0 |
| `SurfaceNets.mesh` of the scene, 32 x 32 x 32 cells | 1.59 ms | 0 after the first call |
| the same, 64 x 64 x 64 cells | 11.5 ms | 0 after the first call |
| mesh with `projection(1)` and normals, 32 / 64 cells | 2.45 ms / 15.4 ms | 0 after the first call |

The mesher costs about 42 ns per grid sample at 64 cells (the evaluation itself is 28 ns of that); doubling the resolution multiplies the number of vertices by about 4, not 8 (a surface, not a volume), and the time by 7.2 because the
sampling of the field is cubic. The allocation contract is enforced in `AnimPhysicsAllocationTest`. Not built: a sparse or adaptive grid (the field is sampled everywhere in the box; a narrow-band or octree version would
cut that), dual contouring or marching cubes variants that keep sharp features, a bounding-volume hierarchy of fields, texture-based fields, and displacement or twist warps (a warp must be scaled by its Lipschitz constant to stay
safe to trace).

