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
| `Mat4f.perspective(..., ClipSpace.OPENGL)` (GL default) | `NEGATIVE_ONE_TO_ONE` |
| `Mat4f.perspective(..., ClipSpace.D3D)` or `VULKAN` | `ZERO_TO_ONE` |
| `Mat4f.perspectiveReversedZ` (infinite far) | `REVERSED_ZERO_TO_ONE` |

For an infinite far plane the far plane degenerates to `(0, 0, 0, d > 0)`, which every point satisfies.

All tests are **conservative**: an object that touches the frustum is never reported as outside. The oracle in the tests works
in clip space (a point is visible iff the clip inequalities hold), so it shares no code with the extraction.

## The flat kernels

Two implementations of `FrustumKernel` produce **bit-identical** results (the tests assert it, including NaN bounds and
ranges). `FrustumKernels.best()` picks the SIMD one when the `vmath-simd` module is present and the incubator module is
enabled, and falls back to scalar otherwise; `-Dvmath.frustumKernel=scalar|simd` (older name `-Dvmath.kernel`) forces a choice; a provider that fails to load, or a forced name that does not exist, is logged once through `System.Logger` `vmath.kernel`.

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
light), so feeding it to a frustum stage already keeps casters outside the view. `CascadeCasters(camera, cascade, margin)` (in `vmath.lighting`, next to `Cascades`) is the
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
d.begin(viewProjection, nearDistance);     // clears, sets a perspective camera
// d.beginOrthographic(viewProjection, depthRange);   // or an orthographic one (shadow cascade, top-down view)
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
kernel's per-object cost, so run it after the frustum stage and only when there is real occlusion to exploit.

**Orthographic views.** `beginOrthographic(viewProjection, depthRange)` takes an orthographic view-projection (bottom row `0 0 0 w`, checked) and the depth convention it
was built with (`NEGATIVE_ONE_TO_ONE`, `ZERO_TO_ONE` or `REVERSED_ZERO_TO_ONE`). There is no `1 / w` to store, since `w` is constant, so the buffer stores the
*nearness*, one minus the fraction of the way from the near plane to the far plane, which is linear on the screen like `1 / w` and so goes through the same plane fit,
farthest-in-the-pixel rule and pyramid. The near plane is the one the matrix defines: occluders are clipped against it and an object that reaches it is reported visible;
an occluder beyond the far plane covers nothing. The property test of the perspective buffer (hidden implies every sampled point is blocked, here by parallel rays that
end at the near plane) runs for all three conventions.

**A SIMD occlusion test was tried and dropped.** Four objects per vector (double lanes, the eight corner projections done for four objects at once, the rectangle and the
pyramid reads still scalar per object) gave the same answers as the scalar stage on the benchmark scene and took 5.25 ms against 6.47 ms for the 100k objects (+-0.26 and
+-0.07), 1.23 times faster. That does not pay for a kernel interface and for exposing the buffer's internals to a second module, so the stage stays scalar; the cost is in the
divisions and the rectangle logic per object, not in something wider vectors remove.

**GPU side.** `HiZ` gives the pyramid sizing (`mipCount`, `mipSize`, `levelFor`) and documents the two-phase contract for
GPU-driven occlusion culling (test against last frame's pyramid, draw, rebuild the pyramid, test what failed against the new
one). `DepthBuffer` is the CPU reference to check a GPU implementation against. Temporal coherence is the next section.

## Temporal coherence: `CoherentCulling` and `VisibilityHistory`

Occlusion queries have a latency (the GPU answers a frame or more after it was asked) and cost a draw each, so asking about every node of a tree every frame, or waiting for each answer before going on, is too slow. `CoherentCulling` is the coherent hierarchical culling of Bittner, Wimmer, Piringer and Purgathofer (2004) over a `StaticBvh`, driven by the visibility of the last frame:

- the tree is visited **front to back** with a queue ordered by the distance of each node to the camera;
- a node that was **visible last frame** is not queried: an inner node is just opened; a leaf is **drawn at once**, with a query issued whose answer only decides next frame (and only every `queryInterval` frames, 3 by default, spread by the index of the node, so that the cost is spread over frames);
- a node that was **not visible** gets a query and *waits in a queue of pending queries*: the traversal goes on with other nodes, and when the answer comes in a visible node is opened and its visibility is propagated up to its ancestors (so they are known to be visible next frame) while a hidden one drops its whole subtree;
- the queue of answers is polled as they become ready and drained at the end, so no single answer is waited for while other work remains.

The engine supplies the queries through `OcclusionQueries` (`issue` a box, `isReady`, `visibleSamples`: a `GL_SAMPLES_PASSED` or `GL_ANY_SAMPLES_PASSED` query of the box drawn with colour and depth writes off, or a Vulkan query pool) and a consumer that draws an object. The contract of a GPU query applies: the box is tested against the depth buffer as it is when it is issued, in the order of the commands, so the engine draws the objects the consumer hands out immediately and in order.

`VisibilityHistory` is the per-object half: whether each object was drawn in the last frame, the streak of frames it has been drawn or has not, and the last frame it was drawn. `CoherentCulling` keeps one up to date. It is for what an engine does with the history (draw last frame's visible set first to fill the depth buffer, fade in after several frames, test the long-hidden rarely); the culling itself uses the history of the nodes.

**Correctness.** Nothing that shows is left out: an object is not drawn only if it is outside the frustum or hidden by what had been drawn before the query was issued, and the depth buffer only grows. Objects may be drawn that are hidden (a visible leaf is asked about only every few frames, a query is conservative); that costs time, not the image. `CoherentCullingTest` checks it against an exact software renderer (boxes on whole pixels with distinct depths, a latency of 0, 3 and 40 polls, a camera that pans): after every frame the image of the drawn objects is the image of all of them, object for object, and every object that shows in the full image was drawn. `:vmath-samples:cullCheck` (class `vmath.samples.verify.OcclusionGpuCheck`, also a test that skips without a context) does it on a real driver with `GL_SAMPLES_PASSED` queries: three scenes, 60 frames each, the framebuffer of the culled drawing equal to that of drawing everything in every frame.

**What it saves, measured.** In the software test (3,000 boxes in a 64 by 64 view, a tree of 5,907 nodes, a latency of 2 polls): 5,385 queries in the first frame, when nothing is known, and 1,030 in the steady state, 17 percent of the nodes. On the driver (NVIDIA, OpenGL 4.6; scenes of 600, 1,500 and 3,000 boxes, 60 frames, a panning camera): 9,701 of 36,000, 17,829 of 90,000 and 8,326 of 180,000 objects were drawn (27, 20 and 4.6 percent; the rest were outside the frustum or hidden), with 15,378, 26,410 and 22,264 queries in 60 frames. These are counts of queries and draws on synthetic boxes, **not** the time a real engine saves: a query costs a draw of a box and the draws it saves cost what the objects cost, which is the engine's.

**Does not do.** It does not draw anything or know a graphics API; a tree whose objects move needs `StaticBvh.refit` before the frame (and `reset` after a rebuild or a jump of the camera); a leaf is asked about as a whole (a leaf of at most four objects, drawn together); `setMinimumSamples` above 1 leaves out what shows as a few pixels, which is a decision to lose small things, not a conservative one. The multi-frame reuse of a GPU Hi-Z pyramid is the two-phase scheme of `HiZ`, which this does not replace.

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

## Portal and sector culling (`PortalGraph`, `PortalCuller`, `PortalStage`)

For interiors: rooms, corridors and the doors between them. Frustum culling looks at every object in front of the camera, including the ones in the next room behind a wall; portal
culling never touches an object that cannot be seen through a chain of open doors.

```
PortalGraph (sectors, portals, doors open or shut, which objects are in which sector)
    └─ PortalCuller.traverse   the sectors seen through open portals, each with the screen rectangle it is seen through
       PortalCuller.cullObjects  clears the bits of objects outside those sectors or outside their rectangles
PortalStage   the two as a CullStage; locates the camera (hint: last frame's sector)
```

- **Sectors** are convex volumes: boxes (`Builder.addBox`) or any convex set of inward planes (`addConvex`); they must not overlap except along shared faces. `locate` finds the sector of a
  point through a uniform grid over the sectors' bounding boxes (and, with a hint, in the sector of the previous frame or a neighbour first).
- **Portals** are convex planar polygons of up to 16 vertices on the boundary between two sectors, with a normal that points from A into B. `addPortal` works out the direction from the
  sectors; `autoPortals` finds every shared face between box sectors. A portal can be closed and opened again (`setPortalOpen`): a shut door removes the room behind it.
- **Membership**: an object belongs to every sector its bounds may overlap (`assignAll`), so a box in a doorway belongs to both rooms; `update` re-assigns a moved object in O(1) cells of
  the grid. Objects that belong to no sector are left alone by default (an object the graph does not know is never culled by accident); `cullUnassigned` removes them.

**The traversal.** From the sector of the eye, with the whole screen as the rectangle `[-1, 1]^2`: for each open portal whose front the eye is on, clip the polygon to the half space in
front of the eye, project it, take the bounding rectangle, intersect it with the rectangle of the current sector, and continue in the sector behind it. A sector reached by several routes gets the
bounding rectangle of all of them and is processed again only when that rectangle grows, so the work is bounded (and a budget of `16 * sectors + 64` growths ends a pathological graph by giving
the rest the whole root rectangle). An eye standing in a doorway passes the portal whole. The portal polygon is clipped at the eye plane, **not** the near plane: a doorway closer than the near
plane still shows what is behind it. **The object test** builds, for each reached sector, the six planes of the view volume narrowed to its rectangle (the camera's own near and far planes) and tests
the sector's objects against them; an object in several sectors survives if it passes in any.

**Conservative.** The result never hides what can be seen: rectangles are bounding boxes of the projected portals (enlarged against rounding), NaN bounds are kept, and a portal polygon that is
seen edge-on is passed whole. The test is not exact in the other direction: a rectangle is looser than the true pyramid of the door chain, so an object just outside the view through a doorway
may survive. It is for **perspective** views (the side test of a portal uses the eye position); an orthographic view does not fit.

**Tested against** an independent line-of-sight oracle: grids of rooms with doors of random size and position and random solid walls; for random eyes and view directions, 9 sample points of each
of 1 500 objects are tested by walking the segment from the eye through the rooms and checking that it leaves each room through an open door polygon; every object with a sample point that is in
the view frustum and in line of sight must survive (in all three depth conventions, including the reversed infinite one, and for eyes closer to a door than the near plane). Objects in sectors that
cannot be reached through open portals by plain graph search are always culled (exact), and the grid queries (`locate`, membership) are compared with a brute-force search over boxes, a tetrahedron
and an unbounded sector.

**PVS hooks.** `SectorVisibility` is the interface through which a precomputed potentially visible set narrows the traversal (`PortalCuller.setVisibility`): sectors it says cannot be seen from the
start sector are not entered. It must be conservative. `PvsMatrix` is a bit-matrix implementation with a plain binary format (rows of `ceil(sectors / 8)` bytes, least significant bit first) that a
level tool can write, `fromBytes` / `toBytes`, and `fromConnectivity`, the one set this library can promise without geometry: every sector reachable through portals, open or not (it
removes unconnected parts of a level, nothing else). A tight PVS needs a visibility compiler, which is not part of the library.

**Measured** (`PortalBench`, JDK 25, single thread, 2026-10-03): buildings of 16 x 16 and 32 x 32 rooms of 10 m, a door between neighbours with probability 0.7, 200 000 small objects, a camera in a
central room looking through one door (2 sectors reached). Objects surviving: **46 508** of 200 000 after a plain frustum cull and **352** after the portal stage in the 16 x 16 building (49 610 and
184 in the 32 x 32 one); the portal stage alone gives the same result as frustum and portal together here.

| Call | 16 x 16 rooms | 32 x 32 rooms |
|---|---|---|
| `PortalCuller.traverse` (the sectors and rectangles) | 0.87 us ± 0.05 us | 1.60 us ± 0.07 us |
| `PortalStage.cull` on a full set of 200 000 (includes resetting and counting the set) | 69.2 us ± 1.8 us | 19.2 us ± 1.1 us |
| `CullStages.Frustum` on the same 200 000 | 464 us ± 128 us | 466 us ± 52 us |
| frustum stage then portal stage | 515 us ± 24 us | 496 us ± 36 us |
| `PortalGraph.update` of one moved object | 0.34 us ± 0.07 us | 0.31 us ± 0.02 us |
| `PortalGraph.locate` with a hint | 15 ns ± 4 ns | 14 ns ± 2 ns |

The portal stage costs in proportion to the objects in the sectors it reaches (781 per room at 16 x 16, 195 at 32 x 32, hence its 69 against 19 microseconds), not to the objects in the scene, while the
flat frustum kernel reads all 200 000. Since the portal stage applies a view volume narrowed to each sector, a separate frustum stage adds nothing for objects that belong to a sector; keep it for the
unassigned ones. Updating a moved object was 2.4 and 7.7 microseconds (a search over all 256 or 1 024 sectors) before the sector grid, and is now independent of the number of sectors.

Not built: portals as extra cameras (mirrors, portal views), a visibility compiler that bakes a tight PVS, anti-portals, and exact (pyramid) portal frusta instead of rectangles.

## Not built yet

A kernel interface for the occlusion test (a SIMD version) and a SIMD BVH traversal. They are in `docs/ROADMAP.md`.

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
| sweeps, exact | `sweepSphereCapsule`, `sweepCapsuleSphere`, `sweepSphereAabb`, `sweepSphereObb`, `sweepSphereTriangle`, `sweepCapsuleCapsule`, `sweepAabbAabb` | a ray from the first shape's centre (or, for two capsules, a point) through the Minkowski sum of the two shapes: slab test on the enlarged box, then the edge capsules where the entry lies in a rounded edge or corner zone (Ericson 5.5.7); triangle and capsule-capsule use two offset faces plus edge capsules |
| sweeps, conservative | `sweepCapsuleAabb`, `sweepCapsuleObb`, `sweepCapsuleTriangle` | conservative advancement: step by (gap - radius) / speed until the gap is within `1e-5` times the combined size of the shapes; distance between translating convex shapes is convex in time, so a growing distance means a miss. Never later than the true time; a grazing pass that exhausts the 128 iterations reports the lower bound reached |
| segment-triangle | `segmentTriangleDistanceSquared` | 0 when the segment pierces the triangle, otherwise the end points and the three edges |

Sweeps take both velocities (`va`, `vb`); only `va - vb` matters, and `t` is in units of the velocities. They return the first time in `[0, tMax]`, `0` when the shapes already touch,
`+Infinity` otherwise. The exact sweeps are tested against dense sampling of the gap (2000 steps over `[0, 4]`, with random velocities for both shapes, aimed at the target
and at box corners and edges, and with parallel capsule axes); the conservative ones against the same sampling with the check that they are never late and are within
`5e-3` of the sampled time in all but 2% of the grazing cases. Triangles are two-sided.

All overlap predicates are written as "not separated", so a NaN input reports an overlap (conservative). `obbObb` and `aabbTriangle` are tested against a
vertex-projection SAT with generic axes, skipping pairs within a touching margin where the two may legitimately differ by the epsilon.
`aabbTriangle` allocates small temporary arrays (relies on escape analysis); it is not in the zero-allocation contract test.
