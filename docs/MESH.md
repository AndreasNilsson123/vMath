# Meshes

`vmath.mesh` is the content layer under everything else: an indexed triangle mesh stored as plain arrays, procedural shapes,
normal and tangent generation, load-time optimisation, and export into GPU buffers. It follows the library's rules: no per-vertex
objects, one array per attribute, allocation only where a tool works on a whole mesh at load time.

```
Primitives ─┐
your loader ─┴─► Mesh ─► MeshTools (normals, tangents) ─► MeshOptimizer (weld, cache, fetch) ─► MeshExport ─► GPU buffers
                  │                                                                              (VertexLayout)
                  └─► bounds, area, volume ─► BoundsArray / StaticBvh / culling
```

## `Mesh`

- Always: `positions` (3 floats per vertex) and a triangle index list. Optional streams, switched on with `enable...` and always
  one entry per vertex: `normals` (3), `tangents` (4: direction and a handedness sign, so `bitangent = cross(normal, tangent) * w`)
  and up to four UV sets (2 each).
- Counter-clockwise triangles seen from outside, like the rest of the library. `positions()`, `indices()` etc. return the live
  arrays, which are longer than the used part and may be replaced when the mesh grows: fetch them again after adding.
- Queries: `bounds()`, `surfaceArea()` and `signedVolume()` (positive for a closed, outward-wound mesh: the cheapest check that a
  mesh is not inside out). `copyVertex` appends a copy of a vertex across every stream, which is what vertex splitting needs.

## `Primitives`

Plane, box, UV sphere, icosphere, capsule, cylinder, cone and torus, each with normals, UV set 0 and tangents. Where UVs or normals
must differ across an edge (a sphere's seam, a cylinder's cap rim, a box's corners) the vertices are duplicated with
**bit-identical positions**, so every closed shape is watertight by position: the tests check that each directed edge occurs exactly
once together with its opposite, that the signed volume is positive, that normals are unit length and agree with their faces, and that
volume and area match the analytic shape (for example a 64 x 32 sphere reaches at least 98.5% of the true volume from below, a
64-segment cylinder 99.5%). Zero-area triangles are left out. The icosphere's UVs are spherical and stretch at the seam; use the UV
sphere when texturing matters.

## `MeshTools`

- `computeSmoothNormals`: angle-weighted, so a triangle contributes to each corner in proportion to the angle it has there and the
  result does not depend on how a surface is split into triangles (tested: cutting one wedge in two leaves the centre normal unchanged).
- `computeNormalsWithCrease(mesh, angle)`: splits vertices where the faces around them meet at more than the crease angle (a cube
  keeps 24 vertices with flat faces, a fine sphere keeps its vertex count), copies all other streams to the new vertices, and returns
  a `remap` (new vertex to original vertex) so your own per-vertex data can follow.
- `computeTangents(mesh, uvSet)`: per triangle the direction in which `u` grows and in which `v` grows, accumulated at the corners with
  angle weights, made perpendicular to the normal, handedness in `w`. It is the standard accumulation, **not a bit-exact MikkTSpace
  port**. Tested against random rotated, sheared and mirrored UV mappings of a plane (200 trials): the tangent points along `dP/du`
  and `w` is right in every case.
- **Degenerate input never gives NaN.** Zero-area triangles and UV-degenerate triangles are skipped; a vertex left with nothing gets
  normal `(0, 1, 0)` or a tangent perpendicular to its normal. A corpus of random meshes with coincident vertices, collapsed triangles,
  huge and tiny coordinates and identical UVs checks this.

## `MeshOptimizer`

Run in this order: weld, cache, fetch. None of them changes the surface (tested by comparing the set of position triples, orientation
included, before and after the whole pipeline on random meshes).

- `weld(mesh, eps, positionsOnly)`: merges duplicate vertices through a grid of cell size `eps`. Comparing every stream keeps UV seams
  and hard edges; `positionsOnly` closes them (use it to rebuild connectivity). Returns the old-to-new remap; triangles that collapse
  are dropped. A triangle soup of a cube welds back to 8 vertices.
- `optimizeVertexCache(mesh, cacheSize)`: Tom Forsyth's scoring algorithm. Only the order of the triangles changes.
- `optimizeVertexFetch(mesh)`: reorders vertices by first use, so the vertex buffer is read almost sequentially; returns the remap
  (a permutation), unused vertices go last.
- `acmr(mesh, cacheSize)`: average cache miss ratio for a FIFO cache: vertex shader runs per triangle (3 is worst, about 0.5 is the best
  a regular grid can do).

**Measured** (FIFO cache of 32, triangle order shuffled, then optimised; a single run on this machine, JDK 25):

| Mesh | triangles | as generated | shuffled | optimised | time |
|---|---|---|---|---|---|
| grid 100 x 100 | 20 000 | 1.010 | 2.993 | **0.664** | 22 ms |
| UV sphere 128 x 64 | 16 128 | 1.016 | 2.991 | **0.667** | 19 ms |
| icosphere, 6 subdivisions | 81 920 | 0.781 | 2.998 | **0.683** | 128 ms |
| torus 128 x 64 | 16 384 | 1.016 | 2.992 | **0.672** | 21 ms |

The optimiser recovers about 77% of the vertex shader work lost to a random order and beats the natural generation order of grid-like
meshes (1.01). It costs about 1.3 to 2 microseconds per triangle, fine for load time and not for every frame. Overdraw ordering, simplification, meshlets, LOD chains and cluster hierarchies are described below.

## `Overdraw` (experimental)

`Overdraw.measure(mesh, resolution)` is a small software rasteriser: the triangles in submission order, back faces culled, early depth test, six axis views,
result `shaded pixels / covered pixels` (1.0 is no overdraw). It is a model for comparing two orders of one mesh, not a GPU counter.
`Overdraw.optimize(mesh, resolution, cacheThreshold)` splits the cache-optimised triangle list into clusters (a cluster ends when a triangle jumps to a new
region of the mesh, or its box exceeds half the mesh, at least 128 triangles each), orders the clusters by `dot(centroid - mesh centroid, normal)` in both
directions, and **keeps an order only if the measured overdraw drops and the vertex-cache miss ratio grows by at most `cacheThreshold`** (1.05 is 5%). It
returns a `Result` with both figures. Run it between `optimizeVertexCache` and `optimizeVertexFetch`.

Measured (two nested UV spheres, inner one drawn first, the textbook bad case; FIFO 16 for the miss ratio, 96 x 96 grid):

| Segments | triangles | overdraw before | after | ACMR before | after |
|---|---|---|---|---|---|
| 32 | 1 920 | 1.250 | 1.000 | 0.698 | 0.725 |
| 64 | 7 936 | 1.251 | 1.000 | 0.687 | 0.709 |
| 128 | 32 256 | 1.250 | 1.000 | 0.677 | 0.695 |

What this does not show: a single convex shape, a box, a torus or an icosphere has almost nothing to gain (the tests check only that the result is never
worse and that an unhelpful order is not applied). The first version cut clusters at about 16 to 20 triangles; it found the same overdraw win but raised the
miss ratio by 8% to 32%, so most runs were rejected by the threshold; larger clusters fixed that. Clusters here are chosen by a heuristic, not by the
meshoptimizer algorithm, and the model has only six axis views. Timing was not measured.

## `RectPacker` and `UvAtlas` (experimental)

`RectPacker.pack(w, h, binW, binH, allowRotation, outX, outY, outRotated)` places integer rectangles into a bin with MaxRects (best short side fit,
largest first); `packSmallestPowerOfTwo` finds the smallest power-of-two bin up to a limit, trying smaller areas first. The tests check, on random sets and
with and without rotation, that a successful result is inside the bin and overlap-free, and that an exact fit (strips plus squares filling a 4 x 4 bin) is
found. It is a heuristic: it can fail on a set that some packing fits. On 200 random rectangles (8 to 64 texels a side) the smallest power-of-two bin was
more than half full (asserted); no better figure is claimed.

`UvAtlas.generate(mesh, uvSet, maxAngleDegrees, resolution, paddingTexels)` is a **planar chart unwrap**: triangles are grown into charts by flood fill over
the adjacency of welded positions (a triangle joins while its normal is within the angle limit of the chart's running average normal), each chart is projected onto
the plane of that normal, rotated to the smallest of 18 candidate boxes, and all charts are packed into one square at the **single largest texel density
that fits** (bisection on the scale). The same vertex used by two charts is copied; the result reports the old vertex of every new one. It is not a
conformal or distortion-minimising parameterisation (LSCM, ABF++): stretch is only bounded by the angle limit. Tested: for every shape and for angle limits of
20, 35 and 60 degrees, all UVs lie in [0, 1] and are finite, chart boxes are separated by at least twice the padding, the positions of every triangle are
unchanged, a box gives exactly 6 charts whose triangles all have UV-to-world area ratio equal to the density squared, and for curved shapes that ratio is at
most 1 (a projection cannot enlarge) and, for limits below 45 degrees, positive (winding kept) and at least cos(2 x angle). Smaller angles give more charts;
the result is deterministic. A chart that folds over itself when projected (a helical ramp) is not detected.

Measured (resolution 1024, padding 2, one run each on this machine, JDK 25):

| Shape | triangles | angle 30: charts, fill, vertices, time | angle 60: charts, fill, vertices |
|---|---|---|---|
| box | 12 | 6, 0.65, 24 to 24, under 10 ms | 6, 0.65, 24 to 24 |
| UV sphere 32 x 16 | 960 | 21, 0.84, 561 to 796, 27 ms | 7, 0.83, 561 to 681 |
| icosphere, 3 subdivisions | 1 280 | 30, 0.87, 642 to 916, 18 ms | 6, 0.77, 642 to 758 |
| torus 48 x 24 | 2 304 | 36, 0.86, 1 225 to 1 796, 31 ms | 9, 0.71, 1 225 to 1 467 |
| capsule | 1 024 | 21, 0.87, 594 to 836, 7 ms | 5, 0.86, 594 to 714 |
| cylinder, 32 segments | 128 | 9, 0.76, 198 to 212 | 6, 0.79, 198 to 206 |

"Fill" is the fraction of the atlas covered by chart rectangles without padding. The first timings include JIT warm-up; this is a load-time tool.

## `MeshSimplifier` (experimental)

Edge-collapse simplification with quadric error metrics (Garland and Heckbert 1997): `MeshSimplifier.simplify(mesh, targetTriangles, maxError, lockBorder)`
reduces the mesh in place and returns a `Result` (triangles before and after, vertices after, error estimate, collapses). The cheapest collapse goes first from
a priority queue; the new position is the quadric minimiser (or an end point or the midpoint when that is ill-posed).

- **Seams are never touched.** A position shared by several vertices with different normals, tangents or UVs is locked, so attribute discontinuities survive
  exactly. A flat-shaded soup or a box (every corner is a seam) therefore barely simplifies: weld first, or drop the attributes you do not need. The tests check that a box
  with its 24 vertices is returned unchanged.
- **Attributes follow the collapse.** With normals, tangents or UVs present the new position is restricted to the collapsed edge and the survivor's attributes are
  interpolated to match (normals and tangent directions renormalised). Tested: on a UV grid every surviving vertex still has `u = x / size`, `v = z / size`.
  Normals are not recomputed.
- **Borders** get a penalty plane so open boundaries keep their shape (a flat grid keeps its corner box and its area); `lockBorder` forbids touching them.
- **Legality**: a collapse that breaks the link condition or flips a remaining triangle by more than about 78 degrees is refused. Tested on a sphere and a torus
  at 50%, 25% and 10%: still a closed manifold (every directed edge once, with its reverse), Euler characteristic 2 and 0 preserved, volume positive and close.
- Zero-area triangles are dropped first; unused vertices are removed at the end.

Measured (welded meshes, JDK 25, one run each; distances are in units of the sphere radius 1 / the torus tube radius 0.35, brute-force point-to-triangle in both
directions):

| Mesh | target | triangles | estimate | simplified to original | original to simplified | time |
|---|---|---|---|---|---|---|
| icosphere 5 120 tris | 50% | 2 560 | 0.0067 | 0.0022 | 0.0018 | 68 ms |
| | 25% | 1 280 | 0.0142 | 0.0034 | 0.0044 | 40 ms |
| | 10% | 512 | 0.0556 | 0.0085 | 0.0127 | 40 ms |
| | 3% | 152 | 0.2983 | 0.0248 | 0.0398 | 41 ms |
| torus 9 216 tris | 50% | 4 608 | 0.0049 | 0.0014 | 0.0014 | 41 ms |
| | 25% | 2 304 | 0.0130 | 0.0029 | 0.0038 | 69 ms |
| | 10% | 920 | 0.0440 | 0.0062 | 0.0107 | 75 ms |
| | 3% | 276 | 0.2348 | 0.0196 | 0.0311 | 101 ms |

The **estimate** (the root of the largest quadric cost of any collapse, which sums squared distances to the planes around the vertex) is 3 to 12 times
larger than the measured distance in every row, so as an error bound for LOD selection it errs toward more detail. It is an estimate, not a Hausdorff distance,
and these are two closed smooth shapes; a sharp-featured model may differ. The volume of the sphere stays within 3% even at 3% of the triangles.

### Skin weights and other attributes

A geometry-only simplifier ruins a skinned mesh: a cylinder collapses along its length for free, so vertices with very different joint weights get merged and the
mesh deforms wrongly. `simplify(mesh, target, maxError, lockBorder, locked, attributes, stride, attributeWeight)` takes per-vertex attributes (for skin weights, one
float per joint, dense) and adds to the cost of a collapse Ward's clustering term, `attributeWeight * n_a n_b / (n_a + n_b) * |mean_a - mean_b|^2`, where `n` counts the
vertices already merged into each side; it works on running means, so a chain of small steps cannot hide a large drift. The merged means come back in `Result.attributes()`
(skin weights stay normalised). `Result.remap()` always maps each output vertex to the input vertex it came from.

Measured on the generated skinned tube (768 triangles, 4 bones, bent 40 degrees at every joint; weight 0.0625; the distance is from the simplified skinned vertices to the
skinned original surface, tube radius 0.25 and length 1.5):

| Target | skin-blind deviation | skin-aware deviation |
|---|---|---|
| 384 triangles (50%) | 0.263 | 0.025 |
| 192 triangles (25%) | 0.897 | 0.029 |

A mesh that is skinned should always be simplified with its weights. `ClusterHierarchy` does not take attributes yet, so it is not suitable for skinned meshes.

## `Meshlets` (experimental)

`Meshlets.build(mesh, maxVertices, maxTriangles)` (default 64 and 124) cuts a mesh into small clusters for mesh shaders and cluster culling. The layout is
meshoptimizer's: a concatenated vertex list (source-mesh indices), concatenated triangles as three byte-sized local indices, and per meshlet an offset and a count
for each. Each meshlet has a bounding sphere (centre of its vertex box, radius to the farthest vertex) and a normal cone (`ConeCull.computeCone`), so
`ConeCull.backfacing` and `ConeCull.Clusters` (`addTo`) work on them directly. `writeDescriptors` and `writeBounds` fill 16-byte and 32-byte GPU records.

The builder is a greedy grower (seed, then the adjacent triangle with the fewest new vertices, nearest the centre), with the seed chosen among the next 32 unused
triangles as the one with the fewest unused neighbours. Tested on six shapes and six limit pairs from 3/1 to 255/512: every triangle lands in exactly one meshlet,
orientation included, the limits hold, local indices are in range, every sphere contains its vertices, every normal is inside its cone, and **no meshlet that the
cone test culls has a front-facing triangle** (40 random eyes per shape). Measured on this machine, JDK 25, after warm-up:

| Mesh | limits | triangles | meshlets | triangles per meshlet | vertices per meshlet | vertex duplication | time |
|---|---|---|---|---|---|---|---|
| icosphere 5 | 64 / 124 | 20 480 | 276 | 74.2 | 50.2 | 1.35 | 23 ms |
| | 128 / 256 | | 144 | 142.2 | 87.8 | 1.23 | 28 ms |
| UV sphere 160 x 80 | 64 / 124 | 25 280 | 343 | 73.7 | 52.9 | 1.39 | 27 ms |
| torus 128 x 64 | 64 / 124 | 16 384 | 213 | 76.9 | 52.8 | 1.34 | 19 ms |
| plane 100 x 100 | 64 / 124 | 20 000 | 255 | 78.4 | 53.3 | 1.33 | 21 ms |

So meshlets are filled to roughly 60% of the triangle limit and 80% of the vertex limit. The vertex limit is what binds: a square patch of a regular grid with 8 x 8 = 64
vertices has 98 triangles, so about 100 is the practical ceiling for 64 vertices, not 124, and the builder reaches about three quarters of that. A post-pass that merges a small
meshlet into the neighbour it shares most vertices with, whenever the union keeps within both limits (up to four rounds), was built and measured on the four meshes above and
**changed nothing** (not one merge fitted, identical meshlet counts), so it was removed.
Seen from 6 radii away, 52% of the meshlets of an icosphere are back-face culled by the cone test. The first seed rule (next unused triangle in index order) gave 60.6
triangles per meshlet on the icosphere; the edge-first seed gave 74.2 and is what remains.

## `MeshLod` (experimental)

`MeshLod.build(mesh, levels, ratio, minTriangles, lockBorder)` returns a `Chain`: level 0 is a copy of the source and every next level is `MeshSimplifier` applied to
the one before, with the running sum of the error estimates as its world-space error. The chain stops early when a step cannot remove 5% of the triangles (a
seamed mesh). `Chain.pixelError(level, distance, fovY, viewportHeight)` is `error * viewportHeight / (2 * distance * tan(fovY / 2))`, `levelFor` picks the coarsest level
within a pixel budget, and `selectorThresholds(boundingRadius, budget)` returns `2 * r * budget / error` per step, the descending thresholds `LodSelector.of` expects
(tested: the selector and the direct selection agree at 60 distances, and the choice never gets finer as an object moves away).

## `ClusterHierarchy` (experimental)

`ClusterHierarchy.build(mesh, maxVertices, maxTriangles, groupSize)` builds the cluster DAG that continuous level of detail needs, in the manner of Nanite:
the mesh is cut into meshlets; neighbouring clusters are grouped (greedily, by shared edges, `groupSize` 4 is usual); each group is merged and simplified to
about half its triangles **with the edges it shares with other groups locked**; the result is cut into new meshlets; and the process repeats on the new
clusters until one group is left or a level removes fewer than 10% of the triangles.

Every cluster has the error of its own simplification (`lodError`, 0 at level 0) and a bounding sphere for it, and the same two values for the group that replaces
it (`parentError`, `parentRadius`, ...; `+Infinity` for a root). All clusters of a group share these (`parentGroup` is the group id), the error never decreases going up
(checked for every cluster) and a parent sphere contains its children's. `select(eye, pixelScale, pixelBudget, out)` draws a cluster when its projected error
`error * pixelScale / distance` is within the budget and its parent's is not. Because the decision only depends on the group, a group is drawn whole or not at all,
and because group borders are locked while simplifying, neighbouring groups drawn at different levels have **identical border vertices**: no cracks.

Tested on a 20 480-triangle icosphere and a 9 216-triangle torus, with 120 selections each (ten budgets from 0 to unlimited, twelve random eyes at 1.5 to 25 radii):
**every selection is a closed manifold surface** (each directed edge once, with its reverse), its area is within 15% below and 3% above the original (the quadric
places vertices slightly outside a convex surface, so the area can exceed it) and its volume at least half of it (the coarsest torus is about 65%), no cluster is
selected twice; a zero budget gives exactly the original triangles; an unlimited budget gives roots only; the number of selected triangles never grows with the budget or
with distance; level 0 is exactly the welded input; construction is deterministic.

Measured (JDK 25, one machine, positions only, 64 vertices and 128 triangles per cluster, groups of 4, pixel scale 1000 and a budget of 1 pixel, eye on the axis; build times
are of a second build in the same JVM, after warm-up):

| Mesh | triangles | levels | clusters | build | triangles per level |
|---|---|---|---|---|---|
| icosphere 5 | 20 480 | 12 | 822 | 426 ms | 20 480, 10 204, 5 116, 2 766, 1 600, 1 022, 658, 452, 250, 132, 68, 34 |
| torus 128 x 64 | 16 384 | 11 | 692 | 325 ms | 16 384, 8 156, 4 132, 2 250, 1 446, 910, 598, 454, 258, 136, 68 |
| icosphere 7 | 327 680 | 27 | 14 227 | **3.98 s** | halves for the first levels, then 30 to 40% per level |

| Eye distance (icosphere 5) | 1.5 | 3 | 6 | 12 | 25 | 50 | 100 | 400 |
|---|---|---|---|---|---|---|---|---|
| selected clusters | 275 | 212 | 178 | 148 | 124 | 106 | 90 | 57 |
| selected triangles | 20 130 | 13 476 | 10 204 | 6 634 | 5 238 | 4 292 | 3 364 | 1 810 |

**Build speed.** The first version took 14.3 s for the 328 000-triangle sphere. Two things were responsible: copying the whole `posIds` array for every new vertex
(quadratic; the 82 000-triangle sphere spent 0.59 of its 2.5 s there) and boxed `HashMap<Long, ...>`/`HashMap<Integer, ...>` structures in the grouping, the border detection and
the per-group local meshes, now replaced by primitive open-addressing maps (`FastMaps`) and arrays. That gives 3.98 s (a cold first build is a little slower), and the remaining time is
spread over the simplifier (a few milliseconds per group), grouping, border detection, local mesh building and meshlets, none of it dominant. Small meshes did not get faster
(0.3 to 0.4 s). The seed search in the grouping is still quadratic in the number of clusters per level, which only matters for meshes of several million triangles.
The build is meant for load or bake time. `select` is a linear scan over the clusters; a real renderer would run the same test per cluster on the GPU or walk the DAG.
Limits: the upper levels shrink slowly (locked borders), attribute seams of the input never simplify, the error is the conservative estimate of `MeshSimplifier`, not a Hausdorff
distance, and skinned meshes are not supported (no attribute-aware cost here). The pool of vertices (`vertices()`) carries attributes; nothing here draws or uploads.

## `MeshExport` and `VertexLayout`

`VertexLayout.builder().position().normalOct16().tangent().uvHalf(0).build()` describes an interleaved vertex: position as 3 floats,
normal as float3 or 2 x 16-bit octahedral (about 4e-5 radians of error), tangent as 4 floats, UVs as 2 floats or 2 halves. Offsets and
stride are computed as you add attributes. `MeshExport.writeVertices` writes straight from the mesh arrays into a `MemorySegment`
(`GpuWriter.of(byteBuffer)` wraps a `ByteBuffer`), `writeIndices32` and `writeIndices16` write the indices (the 16-bit one refuses a mesh
with more than 65 536 vertices). A layout that asks for a stream the mesh does not have is an error, not silently zero.
