# Culling framework

Culling here means: given many object bounds and a view, produce the set of objects that may be visible, with no
allocation per frame. The pieces:

```
BoundsArray (SoA boxes) ──► CullPipeline ──► VisibilitySet ──► indices for the draw list
                              │  stages, in order, each one only clears bits:
                              ├─ CullStages.Distance      farther than maxDistance
                              ├─ CullStages.Frustum       batch plane-major kernel (FrustumCuller)
                              ├─ BvhStage                 same result through a StaticBvh (alternative to Frustum)
                              └─ CullStages.SmallFeature  covers fewer than N pixels
```

## Data

- **`BoundsArray`**: six parallel `float[]` (`minX ... maxZ`). A kernel reading one component of every object streams one
  contiguous array. There is no object per element.
- **`Mat4fArray`**: 16 floats per matrix in GPU order. `BoundsArray.transformFrom(local, matrices)` turns local bounds into
  world bounds each frame (Arvo's method, exact for affine matrices).
- **`VisibilitySet`**: a bitset of object indices. Stages refine one shared set; `toIndices` compacts survivors;
  `and`/`or`/`andNot` combine sets (per-cascade, per-camera).
- **`IntList`**: reusable result buffer for spatial queries.

## Frustum planes

`Frustumf.fromViewProjection(vp, DepthRange)` extracts six inward-facing, normalized planes (Gribb and Hartmann). The depth
convention must match the projection you built:

| Projection | `DepthRange` |
|---|---|
| `Mat4f.perspective(..., false)` (GL default) | `NEGATIVE_ONE_TO_ONE` |
| `Mat4f.perspective(..., true)`, Vulkan, D3D | `ZERO_TO_ONE` |
| `Mat4f.perspectiveReversedZ` (infinite far) | `REVERSED_ZERO_TO_ONE` |

For an infinite far plane the far plane degenerates to `(0, 0, 0, d > 0)`, which every point satisfies.

All tests are **conservative**: an object that touches the frustum is never reported as outside. The oracle in the tests works
in clip space (a point is visible iff the clip inequalities hold), so it shares no code with the extraction.

## The flat kernels

Two implementations of `FrustumKernel` produce **bit-identical** results (the tests assert it, including NaN bounds and
ranges). `FrustumKernels.best()` picks the SIMD one when the `vmath-simd` module is present and the incubator module is
enabled, and falls back to scalar otherwise; `-Dvmath.kernel=scalar|simd` forces a choice.

**Scalar (`FrustumCuller`)** is plane-major and two-pass. For each of the six planes it passes over a 1024-object chunk, picks
the box corner farthest along the plane normal (chosen once per plane by picking `maxX[]` or `minX[]`, so no per-object
branch) and writes the signed distance to a scratch array; a last pass takes the minimum of the six. It is split this way
because C2 vectorizes these simple loops, but **not** a fused loop over all six planes, and it vectorizes `Math.min`
but not the equivalent ternary (measured in `CullKernelBench`: 500 vs 1270 vs 2600 microseconds per 100k boxes).

**SIMD (`vmath-simd`, `SimdFrustumCuller`)** is fused: each group of lanes is loaded once (18 vector loads), all six distances and
their minimum stay in registers, and the mask goes straight into the bitset word with `VectorMask.toLong()`. The scalar kernel
is bound by memory traffic (it re-reads the same arrays once per plane), so reading every array once is what pays off.
It needs `--add-modules jdk.incubator.vector` (the Vector API is still incubating), which is why it is a separate module.

One `FrustumKernel` per thread. Ranges that start at multiples of 64 write disjoint bitset words, so they can be culled
concurrently.

**Semantics shared by both:** an object is visible iff the minimum p-vertex distance over the six planes is **not negative**
(touching is visible, and a NaN distance is visible: a kernel never culls what it cannot judge).

## The BVH

`StaticBvh.build` is a top-down binned SAH build (16 bins over the widest centroid axis). Layout: nodes in depth-first order in
parallel arrays, so the left child of node `i` is `i + 1` and only the right child is stored; each node also stores the
contiguous range of primitives beneath it. Consequences:

- a frustum query accepts a **whole subtree** with one range walk when the node is entirely inside, and tracks which planes a
  parent already decided so children test only the rest;
- there are no node objects: 24 bytes of bounds and 12 bytes of links per node;
- `refit(bounds)` is an O(n) bottom-up pass for moving objects; rebuild when `sahCost()` has degraded.

`BvhQuery` (one per thread) offers `frustum`, `raycast` (front-to-back with pruning, optional narrow-phase callback),
`raycastBounds`, `overlapAabb` and `overlapSphere`, all writing into caller-supplied buffers.

## Moving objects: `DynamicAabbTree`

`StaticBvh` is rebuilt or refitted in bulk; `DynamicAabbTree` updates incrementally, so a scene of mostly-still objects with a
few movers costs almost nothing per frame.

- **Handles.** `insert` returns a stable handle; `move`, `remove`, `userData` take it. Handles survive rebalancing and
  `optimize()`, and freed handles are recycled.
- **Fat boxes.** Each leaf is padded by a margin and stretched along its motion, so `move` only touches the tree when the object
  leaves its fat box (about 0.1 us when it does not, about 3 us for a reinsert at 100k objects, no allocation). Queries test the
  tight box at the leaf, so results are exact.
- **Structure.** SAH-guided sibling choice on insert, rotations on the way back up, depth capped at `3 * ceil(log2(n+1))`.
  `validate()` checks every invariant and is what the fuzz tests call.
- **Queries** (`newQuery()`, one per thread): `frustum` (into a `VisibilitySet` or an `IntList`), `overlapAabb`, `overlapSphere`,
  `raycast`, all into caller buffers. `DynamicBvhStage` plugs the tree into a `CullPipeline`.
- **`optimize()`** renumbers nodes in depth-first order (same tree, same handles, allocation-free after the first call), which
  halves the frustum query at 100k objects. Call it after loading or bulk edits.

It is still about 4x slower than a static BVH for a frustum query (380 us against 89 us at 100k objects), because a static tree
accepts whole subtrees as one contiguous range. The usual split is a `StaticBvh` for level geometry and a `DynamicAabbTree` for
things that move; the diagnosis is in `docs/PERFORMANCE.md`.

## Grid, octree and nearest neighbours

Two more structures share the tree API shape (stable handle, user data, `insert`/`move`/`remove`, one `Query` per thread, results
into caller buffers) and are checked by `validate()` and brute-force fuzz tests:

- **`UniformGrid`** is a spatial hash: cubic cells of one size, each object registered in every cell its box touches, chained in a
  hash table of plain arrays (no dense world array, so a large sparse world is free). A move that stays in the same cells only
  rewrites the box. Objects that would touch more than `MAX_CELLS` (512) cells go on an oversize list every query checks.
  Choose the cell size near a typical object or query size.
- **`LooseOctree`** stores each object once, in the deepest node whose cell is at least as big as the object (each node's loose box
  is twice its cell, so an object never straddles). Nodes are created on demand and removed when empty. Objects centred outside the
  world stay at a catch-all root. It copes with very different object sizes because one object is one entry.
- **`Neighbors`** is the k-NN result buffer (a bounded max-heap on arrays, sorted ascending at the end, ties broken by the smaller
  index). `BvhQuery.nearest`, `DynamicAabbTree.Query.nearest`, `UniformGrid.Query.nearest` and `LooseOctree.Query.nearest` all
  fill it, by squared distance to the object's box. The tree queries prune with the worst neighbour kept; the grid searches in
  shells of cells and stops when no unseen object can be closer.

All four support box and sphere overlap; frustum and ray queries are on the two trees only.

**Measured** (`SpatialStructBench`, 100k boxes of about 0.2 to 1.2 half-width in a 1000-unit world, grid cell 4, octree depth 8;
JDK 25, short runs with wide error bars, so read the ratios, not the digits; all ~0 B/op). `clustered` = eight dense blobs:

| | BVH (static) | dynamic tree | grid | octree |
|---|---|---|---|---|
| overlap, 8-unit box, uniform | **0.41 us** | 4.5 us | **0.44 us** | 8.3 us |
| overlap, 8-unit box, clustered | **7.1 us** | 16.9 us | 17.9 us | 18.7 us |
| 8 nearest, uniform | **4.3 us** | 27 us | 139 us | 149 us |
| 8 nearest, clustered | **3.9 us** | 24 us | 32 us | 86 us |
| move one object by up to 1.5 (uniform / clustered) | refit all: 3.3 / 3.9 ms | 2.4 / 2.5 us | 0.31 / 0.94 us | 0.72 / 0.20 us |

How to read it, and what to pick:

- **Nothing moves (or everything moves and you rebuild): `StaticBvh`.** It wins every query. Its only update path is a whole-tree
  refit (about 33 ns per object).
- **Many objects that move a little every frame, similar size: `UniformGrid`.** Overlap matches the BVH on evenly spread data and a
  move is the cheapest of all. It degrades on dense clusters (many objects per cell) and its k-NN is only good when the nearest
  neighbours are within a few cells: with cell 4 and neighbours about 20 units apart it visits thousands of cells. Match the cell
  size to the neighbour spacing if you use k-NN.
- **Mixed sizes that move: `DynamicAabbTree`** (adapts to density; also does frustum and rays) or `LooseOctree` (one entry per
  object, cheap moves). In these runs the octree's overlap was the slowest on the uniform scene: the tree's nodes are numbered in
  insertion order, so a query jumps around memory, the same locality problem that `DynamicAabbTree.optimize()` fixes for the
  dynamic tree. An `optimize()` for the octree would be the next step if it matters for you.
- **Static geometry plus movers:** a `StaticBvh` for the level and one of the dynamic structures for the rest.

## Level of detail and cluster cones

**`LodSelector`** picks a level per visible object from its on-screen size: the diameter, in pixels of screen height, of the
bounding sphere (`2 * radius * pixelScale / distance`, times an optional bias). `L - 1` descending thresholds give `L` levels;
objects under `cullBelow` pixels are removed from the `VisibilitySet`. Previous levels live in a caller-owned `byte[]`
(initialise with `LodSelector.NO_LEVEL`), so nothing allocates.

- **Hysteresis** `h`: an object drops a level only below `threshold * (1 - h)` and climbs back only above `threshold * (1 + h)`,
  so a camera hovering at a threshold does not flicker (tested: a +-4% wobble flips the level 100+ times in 200 frames without
  hysteresis and never with 10%).
- **Cross-fade**: `fade` runs from 0 (well inside a level) to 1 as the size falls from `threshold * (1 + fadeBand)` to `threshold`.
  Draw the chosen level with weight `1 - fade` and the next with `fade`; the blend is continuous across the threshold because just
  above it the object is already fully the lower level.

**`ConeCull`** skips whole clusters (meshlets) of back-facing triangles; the cone computation and the single-cluster test are `vmath.geo.NormalCone` (plain geometry, so `mesh` does not need `spatial` for them) and `ConeCull` forwards to it and adds `Clusters`. `computeCone` turns a cluster's triangle normals into an
axis and a `cutoff` (the sine of the cone's half-angle); a cluster is culled when
`dot(center - eye, axis) >= cutoff * |center - eye| + radius`. The sphere term makes it conservative: the tests check against real
triangle geometry that a cluster with any front-facing triangle is never culled (400 random patches x 40 eyes, plus an
orthographic variant). Clusters whose normals spread more than about 84 degrees get `cutoff = 1` and are never culled. `Clusters`
is the SoA container with `cull(eye, visible)` and `cullOrthographic(dir, visible)`.

## Shadows and local lights

**Cascades.** `Cascade.frustum()` is the volume the shadow map covers (a box around the slice, pushed `casterDistance` toward the
light), so feeding it to a frustum stage already keeps casters outside the view. `CascadeCasters(camera, cascade, margin)` (in `vmath.camera`, next to `Cascades`) is the
tighter second stage: in light space an object can only shadow the slice if its footprint overlaps the slice's footprint and it is
not entirely beyond the slice's far side. A stabilised cascade is fitted around the slice's bounding sphere, so its box is far
bigger than the slice: in a test scene the tight stage kept 499 objects where the cascade's own volume held 1186. It is
conservative (tested by sampling shadow rays from points in the slice back toward the light: none is ever removed); `margin`
widens the footprint by the shadow filter radius. A second test compares every keep and cull decision against an independent double-precision light-space computation, in both directions (boxes that overlap the footprint by more than the rounding tolerance are kept, boxes that miss it by more are culled). Typical use, per cascade:
`ctx = new CullContext(cascade.frustum(), ...)`, then `CullPipeline.of(new CullStages.Frustum(), new CascadeCasters(camera, cascade, margin))`.

**Point and spot lights.** `LightCull.pointLight` (exact box-to-light distance) and `LightCull.spotLight` (bounding-sphere against
a cone and its range; may keep an object that misses the cone edge by a hair, never removes one inside) clear the objects a light
cannot reach.

**Cube faces.** `CubeFaces` gives the six faces of a cube map in GL/Vulkan orientation (direction, up, view, 90 degree projection,
frustum), and `LightCull.cubeFaces` records for each surviving object a 6-bit mask of the faces it touches, clearing those outside
the light's range. An object straddling a face boundary has several bits set and is drawn into each. The mask comes from a box
against the four side planes of each face's pyramid: it never misses a face the real face frustum touches (tested against
`CubeFaces.frustum`), and the test bounds the average at under 2.2 faces per random box, so it is not just "all six".

## Occlusion culling

`vmath.occlusion` is a small software Hi-Z: rasterize a handful of big, truly opaque occluders (walls, buildings, terrain chunks),
then ask whether an object's box is hidden behind them. `DepthBuffer` does the work, `OcclusionStage` plugs it into a
`CullPipeline` after the cheap stages.

```
DepthBuffer d = new DepthBuffer(256, 128);
d.begin(viewProjection, nearDistance);     // clears, sets the camera (perspective only)
d.addBox(building); d.addPolygon(...);     // occluders, world space, two-sided
d.finish();                                // builds the min pyramid (read-only after this: queries are thread-safe)
CullPipeline.of(new CullStages.Frustum(), new OcclusionStage(d)).run(ctx, bounds, visible);
```

**Why it never hides something visible.** Two deliberate differences from a normal rasterizer:

- **Coverage is inner-conservative**: a pixel counts only if the occluder covers the *whole* pixel square, tested at the corner of the
  square that is worst for each polygon edge. Occluders are rasterized as whole convex polygons: a box face is one quad, because
  two triangles would leave a seam of uncovered pixels along their shared edge (the first version did exactly that, and the
  property test showed it could never hide anything behind a wall face; see `DepthBuffer`).
- **Depth is the occluder's farthest point in the pixel.** The buffer stores `1 / w` (larger is nearer) and for a covered pixel the
  smallest value the occluder has anywhere inside it. An object is hidden only if every pixel of its screen rectangle (rounded
  outwards) holds an occluder at least as near as the object's *nearest* corner. A min-reduced pyramid makes that a handful of
  reads. Anything reaching the near plane, off screen, or with a non-finite corner is reported visible.

**Tested** against a brute-force oracle: for random occluder boxes and candidate boxes, whenever the buffer says hidden, every sampled
point of the candidate that lands on screen must be blocked by a real ray-versus-box test (10 seeds, more than 100 hidden
candidates checked each; a tuned scene also has to hide something, so the test cannot pass by hiding nothing).

**Measured** (`OcclusionBench`, 100k small objects among 400 buildings, camera at street height: a deliberately occlusion-heavy
scene, where the buildings cover 99% of the screen, so read the cost figures rather than the cull rate):

| | 256 x 128 | 512 x 256 |
|---|---|---|
| rasterize 400 buildings + build pyramid | 1.9 ms | 6.3 ms |
| occlusion test of all 100k objects | 7.3 ms | 7.1 ms |
| frustum then occlusion (occlusion sees the 26k survivors) | 3.2 ms | 3.6 ms |
| objects removed among those in the frustum | 99.6% | 99.6% |

The test costs about 70 ns per object (eight double-precision corner projections and a few reads), several times the frustum
kernel's per-object cost, so run it after the frustum stage and only when there is real occlusion to exploit. A SIMD tile test
behind a kernel interface of its own (like `FrustumKernel`) is not built; it is the obvious speed-up.

**GPU side.** `HiZ` gives the pyramid sizing (`mipCount`, `mipSize`, `levelFor`) and documents the two-phase contract for
GPU-driven occlusion culling (test against last frame's pyramid, draw, rebuild the pyramid, test what failed against the new
one). `DepthBuffer` is the CPU reference to check a GPU implementation against. Temporal coherence (CULL-14) and portals are
not built.

## Using several threads

`ParallelFrustumKernel` wraps any `FrustumKernel`: it cuts the range at multiples of 64 (disjoint bitset words), runs the chunks on
a caller-supplied `Executor`, and gives **bit-identical** results to the serial kernel. Ranges under `2 * MIN_CHUNK` objects run
serially. At 1M objects it takes 2.25 ms serial, 0.99 ms with 4 chunks and 0.76 ms with 8; the kernel is memory-bound, so it
flattens well before the core count.

## Choosing flat versus BVH

The BVH wins when most objects are outside the view (whole subtrees rejected); the flat kernel wins when most are visible
or when bounds change every frame (no refit). Both are exposed as `CullStage`s so you can measure on your data. See
`CullBench` and `docs/PERFORMANCE.md`.

## Measured (JDK 25, Ryzen 5 5600H, 1 fork, short run: indicative only)

Frustum culling of N boxes scattered in a 1000-unit cube, about a fifth of the volume in view (`CullBench`, one frame,
microseconds; includes resetting and counting the visibility set):

| N | naive per-object `Aabbf` loop | scalar kernel | SIMD kernel | BVH (prebuilt) |
|---|---|---|---|---|
| 10 000 | 195 | 57 | **17** | 30 |
| 100 000 | 2 426 | 673 | **208** | 107 |
| 1 000 000 | 24 407 | 7 405 | **2 187** | 1 342 |

At 1M objects that is about 41 M objects/s naive, 135 M/s scalar and **457 M/s SIMD**: the SIMD kernel is about 3.4x faster
than scalar and 11x faster than the naive loop. The scalar kernel improved from 10.3 ms to 7.4 ms at 1M by the two-pass rewrite.
None of the kernels allocates (the sub-100 B/op in the profile is benchmark setup noise). Caveats: 4-iteration runs have wide
error bars (the BVH row especially); the BVH win depends on how much of the scene is outside the view, and its numbers exclude
build and refit.

## Not built yet

Portal and sector culling, temporal coherence for occlusion queries, a kernel interface for the occlusion test (a SIMD version) and a SIMD BVH traversal.
They are in `docs/ROADMAP.md`.

## Segments, capsules and the remaining overlap tests

`Segmentf` and `Capsulef` (and their `d` twins) are plain records with `closestPoint`, `aabb`, `transform`. A capsule transforms exactly under uniform scale and
conservatively otherwise (radius times the largest axis scale). `Intersectionf` gained:

| Query | Method | Notes |
| --- | --- | --- |
| segment-segment | `segmentSegmentDistanceSquared`, `segmentSegmentClosestParameters` | parallel and degenerate (point) segments handled |
| segment-AABB | `segmentAabbDistanceSquared` | exact: convex piecewise quadratic in the segment parameter, minimised per slab piece |
| sphere-capsule, capsule-capsule, capsule-AABB | `sphereCapsule`, `capsuleCapsule`, `capsuleAabb` | distance to the axis against the radius sum |
| ray-capsule | `rayCapsule` | end spheres plus cylinder wall; origin inside gives `t = 0` |
| OBB-OBB | `obbObb` | 15-axis SAT with an epsilon on the edge-cross terms |
| AABB-triangle | `aabbTriangle` | 13-axis SAT |
| ray-OBB, sphere-OBB | `rayObb`, `sphereObb` | ray is moved into the box frame (rigid, so `t` is unchanged) and uses the slab test |
| plane-OBB, plane-triangle | `planeObb`, `planeTriangle` | same `Containment` result as `planeAabb` (INSIDE = in front) |
| sphere sweep | `sweepSphereSphere` | earliest time of contact of two moving spheres, `0` when already overlapping, `+Infinity` for a miss |

All overlap predicates are written as "not separated", so a NaN input reports an overlap (conservative). `obbObb` and `aabbTriangle` are tested against a
vertex-projection SAT with generic axes, skipping pairs within a touching margin where the two may legitimately differ by the epsilon.
`aabbTriangle` allocates small temporary arrays (relies on escape analysis); it is not in the zero-allocation contract test.
