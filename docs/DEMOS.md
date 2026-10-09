# vmath demos

A roadmap of demos that show what the library is good for, and the structure they are built on. It follows the conventions of [ROADMAP.md](ROADMAP.md): priorities **P0** blocks the rest,
**P1** shows a core strength, **P2** completeness, **P3** nice to have; sizes S ≈ hours, M ≈ days, L ≈ 1-2 weeks; `→` marks dependencies; IDs are stable.

The demos live in the module `vmath-samples` (built only with `-Psamples`, or by IntelliJ; [SAMPLES.md](SAMPLES.md), [IDE.md](IDE.md)). They need OpenGL 4.5 and a display; they are not part of the
library build.

---

## 1. Where we are

The framework (phase F) and twenty-two demos are done: `city`, `culling-lab`, `interior-portals`, `occlusion`, `dq-vs-lbs`, `rigid-pile`, `sdf-sculpt`, `terrain`, `sky-sun`, `cluster-lod`, `gpu-culling`, `clustered-lights`, `globe`, `cascaded-shadows`, `streaming-ring`, `line-lab`, `line-styles`, `line-stream`, `map-view`, `map-projections`, `map-symbols` and `map-terrain`. Everything else in section 4 is the backlog. A card has numbers only for a demo that has run.

| | |
|---|---|
| Demos | 22 of 32 (`city`, `culling-lab`, `interior-portals`, `occlusion`, `dq-vs-lbs`, `rigid-pile`, `sdf-sculpt`, `terrain`, `sky-sun`, `cluster-lod`, `gpu-culling`, `clustered-lights`, `globe`, `cascaded-shadows`, `streaming-ring`, `line-lab`, `line-styles`, `line-stream`, `map-view`, `map-projections`, `map-symbols`, `map-terrain`) |
| Framework | launcher with a menu, runner, HUD, camera, instance stream, box renderer, debug lines, depth-buffer inset, shared scenes, warm-up, statistics and report, screenshot, smoke check |
| Verified on | NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, OpenGL 4.5, Windows 11, JDK 25 (one machine; other vendors and systems are unchecked) |

---

## 2. Rules

Every demo, no exceptions:

1. **One strength.** A demo shows one thing the library does well and says which classes do the work. The demo's own code stays thin: it builds a scene, calls the library, and draws the result. A
   demo that needs a hundred lines of its own algorithm to look good is showing the demo, not the library.
2. **Scripted and measured.** `--frames N` runs the demo along a fixed path with a fixed time step (1/60 s) and prints averages after the warm-up. Only numbers printed by such a run go into a card
   (and into the README); say the machine, the JDK, the size of the window and the number of frames next to them. A demo that cannot be measured is not finished.
3. **Allocation budget.** The `DemoInfo` of a demo declares how many bytes the render thread may allocate per frame in `update` and `render` (the library's hot paths allocate nothing; a demo shows
   that). The smoke run fails above the budget. The budget is a measured number plus headroom, written in the card.
4. **Procedural or from the repository.** Content is generated in the demo or comes from the generated test assets in `vmath-core/src/test/resources/assets` (glTF, KTX2). No downloads, no
   third-party models, so that every demo runs from a checkout.
5. **A gap is a fix, not a workaround.** A bug or a missing feature that a demo runs into becomes a fix in the library or an entry in [technical-debt.md](technical-debt.md); the demo does not hide it.
6. **A card.** Every demo has a card in section 5 (claim, what it uses, controls, what it measures, what it does not prove), a run configuration (`.run/Demo_<id>.run.xml`) and a place in `Demos.java`. A
   test (`DemosRegistryTest`) fails when one of them is missing.
7. **OpenGL 4.5, one context.** The demos use direct state access, persistent mapping, indirect draws and compute shaders, but nothing that needs more than 4.5 (so not mesh shaders), and no
   second window. Vulkan is not a goal of the demos.
   The demos of phase L and M (lines and maps) are the exception that proves the tiers: they run in a 4.5 context but force each lower strategy of the library (and its `#version`, down to
   330) through the capabilities they pass in, so that every tier is drawn and compared; what they cannot show is that a real driver of that version accepts it (`ROADMAP.md` GPU-15).
8. **Honest scope.** A card says what the demo does not prove: one GPU, a synthetic scene, a CPU measurement that leaves the driver out.

---

## 3. Structure

```
vmath-samples/src/main/java/vmath/samples
  Launcher.java            main: --list, --demo, --sequence, --smoke, or the menu
  Demos.java               the registry: one DemoEntry (DemoInfo + factory) per demo
  framework/               shared pieces; no demo code
  demos/<id>/              one package per demo: <Name>Demo, its options, its scene, its GLSL
vmath-samples/src/test/java    tests of the framework and the registry (no window needed)
vmath-samples/tools/make_run_configs.py   writes .run/Demo_<id>*.run.xml
```

| Piece (`vmath.samples.framework`) | What it does | Library pieces it shows |
|---|---|---|
| `Demo`, `DemoInfo`, `DemoEntry` | the contract: `create`, `update` (CPU), `render` (GL), `hud`, `report`, `dispose`; the description and the factory | |
| `DemoRunner`, `RunOptions` | window and OpenGL 4.5 context, main loop, menu, live switching, scripted runs, smoke run | |
| `FrameInfo`, `Input` | time, size, keys, mouse; a fixed step in scripted runs | |
| `DemoContext` | arena, statistics, GPU timer, debug renderer, the demo's arguments, the clear colour | |
| `Gl`, `GpuMesh` | program and compute program build with numbered errors, matrix uniforms, `glGetError` checks; a mesh exported with its vertex layout | `MeshExport`, `VertexLayout`, `VertexBufferLayout.glFormats()` and `glslInputs()` |
| `InstanceStream` | persistently mapped instance buffer in regions with fences, the indirect command; instances from the visible set or written one by one with a rotation | `PersistentBufferRing`, `InstanceWriter`, `DrawCommandBuffer` |
| `GpuTimer` | `GL_TIME_ELAPSED` read a few frames late; a demo may make more than one | |
| `SceneTarget` | an offscreen frame with colour and a sampleable, readable depth texture, blitted to the window | |
| `ReadbackRing` | reads a GPU counter back two or three frames late through a fenced copy, without stalling | |
| `GlFences` | the fence operations of `PersistentBufferRing` for OpenGL (`glFenceSync`, `glClientWaitSync`, `glDeleteSync`) | |
| `BoxRenderer` | the unit cube, its program, and the instanced draw of an `InstanceStream` | `Primitives.box`, `MeshOptimizer`, `VertexLayout` |
| `Scenes` | the shared procedural scenes: `city` (noise heights) and `blocks` (buildings with props in the streets) | `BoundsArray`, `Noise`, `Rng` |
| `OrbitCamera` | orbit around a point with the left mouse button, zoom with the wheel, a scripted turn | `Cameraf` |
| `FlyCamera` | free flight, a scripted circle, and `place` for a demo's own scripted path | `Cameraf`, `DepthRange` |
| `Sliders` | mouse-driven sliders, checkboxes and button rows in the HUD, in the immediate-mode style; tells the demo when the pointer is over a control | |
| `Hud`, `FontAtlas` | text, backdrops and value bars in window pixels, one draw call; the font is baked with Java 2D | |
| `DebugRenderer` | draws a `DebugLines` buffer (boxes, spheres, frusta, skeletons, ...) | `DebugLines` |
| `DepthInset` | one level of a depth buffer drawn as a picture in the window | `DepthBuffer.invDepth` |
| `Warmup` | runs a piece of work until the JIT has compiled it and it stops allocating | |
| `Stats`, `Report`, `Screenshot` | named series, averages after the warm-up, text and markdown reports; the last frame as a PNG | |

**Lifecycle.** The runner polls the input, calls `update`, clears, calls `render`, draws the HUD, optionally captures the frame, and swaps. The allocation of the render thread is measured over
`update` and `render`. A demo is disposed and created again when the user switches (`Tab`, `PageUp`, `PageDown`), which is also how the lifecycle is tested (`--sequence a,b`).

**Keys of the framework** (a demo does not use them): `Escape` quits, `Tab` opens the menu, `PageUp` and `PageDown` switch demo, `V` toggles vsync, `P` saves a screenshot, `F1` hides the text.

**Running a demo.**

```
./gradlew -Psamples :vmath-samples:run                                           # the menu
./gradlew -Psamples :vmath-samples:run --args="--demo city"                      # one demo
./gradlew -Psamples :vmath-samples:run --args="--demo city --frames 600"         # scripted: the numbers, then on to the next demo
./gradlew -Psamples :vmath-samples:run --args="--sequence city,city --frames 100 --screenshot build/shots/s.png --report build/shots/report.md"
./gradlew -Psamples :vmath-samples:smoke                                         # every demo, a few frames, checked
```

**Smoke.** `smoke` runs each demo with the arguments of its `DemoInfo.smokeArgs` for 300 warm-up and 30 measured frames with `glGetError` checks on, and fails on a GL error, on allocation above the
budget, or on a last frame with fewer than 16 distinct colours in a regular sample. The warm-up is long on purpose: the SIMD kernels allocate a vector object per operation until the JIT has compiled
them (9.5 MB per frame over the first 100 frames of the culling lab's 250,000 boxes, 1.2 MB per frame for hundreds of frames on a scene of 3,000 boxes), so a short warm-up measures the interpreter.
`Warmup` runs the culling before the first frame until it stops allocating, and the long smoke warm-up covers what it cannot (the compiler throws code away when the data takes a branch that the
warm-up did not).

**Adding a demo.**

1. `vmath.samples.demos.<id>.<Name>Demo implements Demo` with a public `static final DemoInfo INFO` and a constructor that takes the demo's arguments (`List<String>`) and rejects what it does not know.
2. An entry in `Demos.all()`.
3. A card in section 5 of this file, headed `### <id>: <title>`, and the checkbox of the backlog item ticked with a *Done* note.
4. `python vmath-samples/tools/make_run_configs.py` for the run configurations.
5. `./gradlew -Psamples :vmath-samples:test :vmath-samples:smoke`, and a scripted run whose numbers go into the card.

**The card template** (section 5):

```
### <id>: <title>
Claim: one sentence.
Uses: the library classes that do the work.
Controls: keys and mouse.
Options: the demo's own arguments.
Measured: machine, JDK, window, frames; a table of the numbers of the scripted run.
Does not prove: what the demo cannot tell you.
```

---

## 4. Backlog

### Phase F: framework (done)

- [x] **F1 (P0, M)** The `Demo` contract, the registry, the runner and the launcher with its menu and live switching.
- [x] **F2 (P0, M)** `Gl`, `GpuMesh` and `InstanceStream`, extracted from the first sample.
- [x] **F3 (P0, S)** `FlyCamera` and `Input`.
- [x] **F4 (P0, M)** The HUD (`Hud`, `FontAtlas`). *Done:* the font is baked with Java 2D into a one-channel atlas (the plan said a hand-written 5×7 bitmap font); one draw call per frame.
- [x] **F5 (P0, S)** `DebugRenderer` for `DebugLines`. *Done:* `city` uses it to draw the frozen culling camera (key `X`).
- [x] **F6 (P0, M)** `Stats`, `Report`, the screenshot (read before the swap) and `glGetError` checks.
- [x] **F7 (P0, S)** `:vmath-samples:smoke`.
- [x] **F8 (P0, S)** `MillionInstances` ported to the demo `city`.
- [x] **F9 (P0, S)** Run configurations from `tools/make_run_configs.py`, the card template and `DemosRegistryTest`.
- [x] **F10 (P1, S)** `Scenes`: reusable generators. *Done:* `city` (moved out of the first demo) and `blocks`, used by `culling-lab` and `occlusion`; a terrain grid waits for P4.
- [x] **F14 (P1, M)** Pieces the animation, physics and geometry demos needed. *Done:* `OrbitCamera`, `Hud.bar`, `Gl.computeProgram`, `InstanceStream.writeInstance` for rotated instances.
- [x] **F13 (P1, M)** Pieces the first culling demos needed. *Done:* `BoxRenderer` (the cube and its draw), `DepthInset` (a depth buffer as a picture), `Warmup`, `FlyCamera.place`.
- [x] **F11 (P2, M)** Mouse-driven sliders and toggles in the HUD. *Done: `Sliders` (slider, checkbox, button row; hit tests and value mapping are static and unit-tested) and `Input.mousePressed`; first used by R1. A2 keeps its arrow keys.* → F4
- [x] **F12 (P2, M)** Compute-shader and texture helpers. *Done: `Gl.computeProgram`, `SceneTarget` (colour and depth textures, readback, blit), `ReadbackRing`, `GpuTimer` usable several times; the pyramid builder is the gpu-culling demo's own (`HizBuilder`).*

### Phase C: culling and scale

- [x] **C1 (P1, S)** `city`: a million boxes, frustum culling, instance ring, indirect draw.
- [x] **C2 (P1, M)** `culling-lab`: the same scene with a key to switch between the brute-force scalar kernel, the SIMD kernel, the parallel kernel, `StaticBvh`, `DynamicAabbTree`, `LooseOctree` and `UniformGrid`; the time and the visible count of each live, the nodes of the BVH drawn with the debug renderer, a sweep of how many boxes are visible against how long each structure takes. Shows: three batch kernels and four spatial structures behind one interface, and where each wins. → F10
      *Done: seven methods (the first plan said eight), each timed per frame and each checked against the scalar kernel with `--verify` (a miss fails the run; a box within a centimetre of a plane is counted as marginal), the cost of keeping each structure current while a fiftieth of the boxes move (`--animate`), the build times, the BVH levels drawn, a table of every method on the current view (`T`). Card and numbers below.*
- [x] **C3 (P1, M)** `interior-portals`: a generated building of rooms and doors; `PortalGraph`, `PortalCuller` and `SectorVisibility`; doors open and close with a key; visible sectors and the portal rectangles drawn; the instance count against frustum culling alone. Shows: indoor visibility without a hand-made PVS.
      *Done: a maze of 400 rooms and 508 doors (a random spanning tree plus a quarter of the other walls) with 150 pieces of furniture per room, the portals found by `autoPortals` (two per door), a slab that fills a shut door. `SectorVisibility` and `PvsMatrix` are not used: for a graph without a baked PVS the connectivity matrix lets everything connected see everything connected, so it filters nothing here.*
- [x] **C4 (P1, M)** `occlusion`: a dense city with tall occluders; `DepthBuffer`, `OcclusionStage`; the depth buffer and its Hi-Z levels shown as an inset; a ray-test check running beside the culling that proves nothing visible is removed (conservative). Shows: software occlusion that never culls what is seen.
      *Done: 10,000 buildings and 400,000 props, the nearest 143 buildings rasterised, a ray check of up to 200 removed boxes per frame at nine points each, a multi-threaded test (`--threads`). It also found that the test is too slow to pay for itself on cheap boxes: TD-29 in `technical-debt.md`.*
- [x] **C5 (P2, L)** `cluster-lod`: a dense generated mesh with `Meshlets`, `ClusterHierarchy`, `ConeCull` and `MeshSimplifier`; the cut of the hierarchy chosen by screen-space error; clusters coloured; triangle count against pixel error. Shows: Nanite-style continuous LOD with the library's data structures and a CPU cut.
      *Done: a noise-displaced icosphere (81,920 triangles by default, up to 1.3 million), `ClusterHierarchy.build`, the cut on the CPU by `select` or on the GPU by `GpuCullGlsl.clusterShader`, one multi-draw, clusters coloured by id or by level. `--verify` runs the shader against `ClusterCullReference`: the same clusters at four distances. `Meshlets`, `ConeCull` and `MeshSimplifier` are used through the hierarchy (and the shader's cone test).*
- [x] **C6 (P2, L)** `gpu-culling`: the compute culling shader of `vmath.gpucull` with a `HiZPyramid`, indirect commands built on the GPU, the result compared with `GpuCullReference` every frame. Shows: GPU-driven culling, and closes the shader-text part of TD-01.
      *Done: 410,001 boxes, `GpuCullGlsl.computeShader`, a pyramid built on the GPU from the depth of the previous frame, `glDrawElementsIndirect` with the survivors read through an instanced attribute, the survivors compared with `GpuCullReference` and the pyramid with `HiZPyramid` (`--verify`). It found TD-33.*
- [x] **C7 (P2, L)** `clustered-lights`: ten thousand point lights assigned with `ClusterGrid` and `ClusterLights`, shaded forward-plus, the grid drawn on demand. Shows: the light-culling math, and checks `glslLookup` against a driver.
      *Done: 4,096 lights by default (the card measures up to 10,000), the assignment redone every frame on the CPU with `ClusterLights`, the lookup of `glslLookup` in the fragment shader, a loop over every light and a heat map as alternatives, and a check that the clustered image equals the loop's (`--verify`).*

### Phase A: animation and characters

- [ ] **A1 (P1, L)** `crowd`: thousands of skinned characters from the generated glTF assets (or procedural tubes with a skeleton); `Gltf.load`, `ClipSampler`, `Pose` blending, joint matrices in a buffer, LOD by distance; microseconds per character per frame. Shows: animation at crowd scale with no per-frame allocation. → F10
- [x] **A2 (P1, M)** `dq-vs-lbs`: an arm twisted by a slider, linear blend skinning on the left and dual-quaternion skinning on the right (both in GPU shaders), the candy-wrapper collapse visible; the CPU `Skinning` as the oracle for both. Shows: `DualQuatf`, `Skinning.skinPositionsDualQuat`.
      *Done: a tube skinned to two joints, the twist turned with the arrow keys or automatically, both methods in the vertex shader, the GLSL of both checked against the library's CPU reference in a compute shader at ten angles (`--verify`: largest error 3.6e-7), the radius of the blended ring measured on the CPU for both. The tube is not a character: the glTF assets are for A1.*
- [ ] **A3 (P2, M)** `ik-playground`: two-bone leg IK on uneven ground, a look-at head, a FABRIK tentacle that follows the mouse; the remaining distance shown. Shows: `IkSolver`.
- [ ] **A4 (P3, M)** `morph-faces`: `MorphTargets` with weights from sliders; the dense and the sparse form side by side with their byte sizes. Shows: sparse blend shapes. → F11

### Phase P: physics and geometry

- [x] **P1 (P1, L)** `rigid-pile`: thousands of boxes and spheres dropped into a pit; `DynamicAabbTree` as the broadphase, `ManifoldBuilder`, `ContactSolver`, `RigidBody`; contact points drawn; step time per body. Shows: the physics building blocks, honestly a toy engine, not a competitor to one.
      *Done: up to 3,000 boxes and spheres in a pit, the step broken into broad phase, narrow phase, solver and integration, contacts drawn, `--verify` for bodies that leave the pit. The cost of the narrow phase and its allocation are TD-30.*
- [x] **P2 (P1, L)** `sdf-sculpt`: signed distance fields with smooth CSG, re-meshed with `SurfaceNets` every frame, a brush that adds and subtracts; vertices and triangles per second. Shows: SDF modelling and meshing.
      *Done: a starting shape made with `Sdfs` CSG, a grid field that the brush edits with `smoothUnion` and `smoothSubtract`, `Sdfs.raycast` for the pointer, `SurfaceNets` after every stroke, `--verify` for open meshes. It found that the mesh can have edges shared by more than two triangles: TD-31.*
- [ ] **P3 (P2, L)** `character-controller`: a capsule walks a triangle-soup level using the sweeps (`sweepCapsuleTriangle`, `sweepSphereAabb`) and a `StaticBvh`; slides along walls, steps up. Shows: the exact and conservative-advancement sweeps.
- [x] **P4 (P2, M)** `terrain`: fractal terrain (`Noise.fbm2`) with `MeshLod` chains and `LodSelector`; a camera on a `Curves` spline rail with arc-length speed. Shows: procedural geometry and LOD selection.
      *Done: 256 chunks of 256 m, a `MeshLod` chain per chunk with the border locked, `LodSelector` with hysteresis and thresholds from a pixel budget, one `glMultiDrawElementsIndirect` for all visible chunks, a closed Catmull-Rom rail with `ArcLengthTable`, camera speed measured. It found TD-32 (the SIMD frustum kernel on a few hundred boxes).*
- [ ] **P5 (P3, M)** `convex-lab`: two convex shapes dragged with the mouse; `Gjk` distance, `Epa` penetration depth, closest points and the contact normal drawn. Shows: the narrow phase.

### Phase R: rendering math and large worlds

- [x] **R1 (P1, M)** `sky-sun`: `PreethamSky` and `SolarPosition` with a time-of-day slider, `PhysicalCamera` exposure and `ToneMap` operators switched live. Shows: the lighting and camera math.
      *Done: sliders for the hour, the day, the latitude, the turbidity, the focal length, the aperture, the sensitivity and the shutter time or exposure compensation, an automatic shutter, six curves; the sun, sky, atmosphere and exposure numbers are on the screen, and the shader's curves are checked against `ToneMap` and `Srgb` (`--verify`).*
- [x] **R2 (P1, L)** `globe`: the whole Earth as a WGS-84 ellipsoid (`Wgs84`, `Geodetic`), a flight from orbit to street level with a `FloatingOrigin`; a key turns camera-relative rendering off and shows the jitter that it removes (`Rebase`, `FrameTransformf`). Shows: large worlds in single-precision rendering. Measures the vertex error with and without rebasing.
      *Done, and extended with map tiles: a Web Mercator pyramid of height and image tiles on the ellipsoid, chosen by the screen size of their meshes, streamed in on worker threads and drawn camera-relative, from 20,000 km to 2 m. It needed new library classes (`WebMercator`, `TileId`, `TileBounds`, `HorizonCuller`, `Ellipsoids`, `TerrainRgb`, `TileSelector`; `docs/LARGE_WORLDS.md`). The tiles are procedural, because the demos download nothing.*
- [x] **R3 (P2, L)** `cascaded-shadows`: `Cascades` fit and `CascadeCasters` culling per slice, four shadow maps, the splits and the casters drawn. Shows: shadow-cascade math and caster culling.
      *Done: four cascades of 2,048 by 2,048 texels fitted by `Cascades.fitAll`, the casters of each by `Cascade.frustum()` and `CascadeCasters`, one layer of a depth array texture per cascade read through a shadow sampler, `Cascade.textureMatrix()` used by the shader as it is; `--verify` checks the culling and the lookup against brute force. It found TD-34.*
- [ ] **R4 (P2, M)** `ibl-spheres`: a grid of material spheres lit by `SphericalHarmonics` irradiance and `Ibl` GGX prefiltering of the procedural sky. Shows: image-based lighting. → F12, R1
- [ ] **R5 (P3, M)** `mirrors-stereo`: a planar reflection and a portal view (`PlanarViews`) and a side-by-side stereo pair (`Stereo`). Shows: the view-matrix constructions. → F12
- [ ] **R6 (P3, S)** `color-lab`: colour spaces and tone maps side by side (`ColorSpaces`, `Srgb`, `ToneMap`). Shows: the colour math.

### Phase L: lines and multi-draw

The demos of `ROADMAP.md` phase O. Each runs every strategy of the line renderer that its context allows (the 4.5 context runs all of them by forcing the capabilities down), switchable live with the keys 1 to 5, and its `--verify` compares each strategy's image with the CPU reference of the library (LINE-2).

- [x] **L1 (P1, L)** `line-lab`: five thousand polylines of sixty-four points each (a procedural river-and-road network and random walks), drawn by every strategy with the same LineBatch; per strategy the draw calls, the bytes uploaded, the CPU time to write and the GPU time, and the largest coverage difference from the reference. Shows: LineBatch, LineRenderPlan.choose, DrawList and what each tier costs. → LINE-4
      *Done: `line-lab` draws one network of 5,000 polylines of 64 points (315,000 segments, 13 styles) in six tiers (INDIRECT_DRAW_ID on OpenGL 4.5 with the draw-parameters extension, INDIRECT_INSTANCE_STYLE on 4.3, INSTANCED_LOOP on 4.2 and on 3.3, EXPANDED_MULTIDRAW on 3.3, HAIRLINE on 3.3), switchable live, with the calls, bytes, CPU time to write, CPU time to issue, GPU time and the pixels that differ from the reference. The probe compares a 256 by 256 square of 1,500 other polylines, drawn offscreen by each tier, with `LineExpander`.*
- [x] **L2 (P2, M)** `line-styles`: widths from hairline to thirty pixels, dashes, caps, joins and the miter limit on sharp turns, anti-aliasing against a pixel-exact reference, zoom without rebuilding, in world and in pixel width. Shows: the style model and the screen-space expansion. → L1
      *Done: `line-styles` shows the widths, the nine cap and join combinations, two miter limits, dash patterns and a world-unit against a pixel width, in every tier. The roadmap's anti-aliasing is not built (the geometry has no coverage fringe, `docs/LINES.md`), so the card compares the pixels with the reference instead.*
- [x] **L3 (P2, M)** `line-stream`: a few thousand polylines added, edited and removed every frame and a hundred tracks with trails; the bytes uploaded with dirty ranges against a full upload, the compaction, the handles. Shows: LineSet, TrailBuffer. → LINE-5, LINE-7
      *Done: `line-stream` edits, replaces and moves 3,000 polylines and 100 trails every frame in one `LineSet`, with the two upload modes, handle checks and compaction.*

### Phase M: 2D maps

- [x] **M1 (P1, L)** `map-view`: a moving map with own position moving along a route: a MapView2d that centres on it or sits it near the bottom, north up, course up and heading up on a key, range rings and a route with legs from GeoShapes, tiles from the flat tile selector (the generated procedural tiles of the globe demo, so no downloads), a scale bar from the pixel size on the ground, and a cursor that shows latitude and longitude. Shows: the 2D view, geodesy and the shapes, all drawn through the line renderer. → MAP-3, MAP-4, MAP-5, L1
      *Done: `map-view` flies an own position along a route of great-circle legs over the procedural planet in a `MapView2d` (north up, course up, heading up, own position centred or low), with range rings, the route, a hatched corridor, a trail, tiles from `FlatTileSelector` generated on the fly, a scale bar, and a cursor read-out in navigation units; the lines go through the line renderer, the corridor through the area renderer and the own position through the symbol renderer. See the card.*
- [x] **M2 (P2, M)** `map-projections`: the same data (coastlines of the procedural world, a geodesic and a rhumb line between two cities, circles of equal ground radius) in Web Mercator, azimuthal equidistant, polar stereographic and UTM, switched live, with distortion ellipses so that the difference can be seen; the round-trip error and the distance error of each printed. Shows: MapProjection and Geodesic. → MAP-1, MAP-2
      *Done: `map-projections` draws the coasts, a geodesic and a rhumb line, circles of equal ground radius and a grid of 450 km circles (distortion ellipses) in Web Mercator, azimuthal equidistant, polar stereographic and UTM, switched live, with the round-trip error and the distance error of each. See the card.*
- [x] **M3 (P2, M)** `map-symbols`: ten thousand moving symbols with headings, trails, priority declutter of labels and a pointer that selects the nearest one; the symbols that stay upright on a rotated map; the draw calls and the CPU time per frame in every tier. Shows: the symbol writer, Declutter, TrailBuffer, picking through `screenToWorld`. → MAP-6, MAP-7, L3
      *Done: `map-symbols` moves 10,000 symbols with headings over a rotating map in each of the three symbol tiers, with trails of 300 of them, priority-declutter labels, a pointer pick through `screenToProjected`, and 1,500 cities that stay upright. See the card.*
- [x] **M4 (P2, L)** `map-terrain`: hillshaded elevation tiles with a ramp indexed by the height relative to a reference altitude that the pointer or a key changes, and a viewshed from the own position shown as a mask, its cost per cell. Shows: the terrain shading and the CPU viewshed. → MAP-9, M1
      *Done: `map-terrain` colours a 385 by 385 tile by height relative to a reference altitude (a key or the pointer changes it), hillshaded, on the CPU or the GPU, with a viewshed from the moving own position computed on a worker thread and shown as a mask. See the card.*

### Phase T: tour and capture

- [x] **T1 (P2, M)** `streaming-ring`: `PersistentBufferRing` and the allocators under a synthetic streaming load, region occupancy and stalls shown. Shows: the memory layer.
      *Done: chunks of points streamed through a persistently mapped ring into a pool that `FreeListAllocator` (first fit or best fit) or `SlabAllocator` manages, 200,000 particles written into the ring every frame and drawn from it, teleports, a GPU load that the keys set; the pool is read back and compared with the chunks (`--verify`).*
- [ ] **T2 (P2, M)** `tour`: runs a chosen sequence of demos on their scripted paths and writes the screenshots and the combined markdown report (the material for a README gallery and release notes). Mostly `--sequence` with `--screenshot` and `--report`, which exist; this is the script and the page.
- [ ] **T3 (P3, S)** `valhalla-allocation`: the HUD allocation counter of a demo on the plain build against the `-Pvalhalla` build (needs JDK 28).

### Order

1. Done: F1-F14, C1-C7, A2, P1, P2, P4, R1, R2, R3, T1: the five that show the widest range, the terrain, the GPU demos, the globe, the shadows and the memory layer.
3. **A1** `crowd`, the other one that looks like a product.
4. The rest by interest.

---

## 5. Cards

### city: A city of a million boxes

**Claim.** A million boxes culled on the CPU every frame and drawn with one indirect call from a persistently mapped, fenced instance buffer, with about 2 kB of allocation per frame.

**Uses.** `City` (generated here with `Noise.fbm2` and `Rng`), `BoundsArray`, `Primitives.box`, `MeshOptimizer`, `VertexLayout`, `MeshExport` and `VertexBufferLayout` (through `GpuMesh`), `Cameraf`, `Frustumf`,
`CullPipeline`, `CullStages.Frustum`, `FrustumKernels` (SIMD where the incubator module is enabled), `ParallelFrustumKernel`, `InstanceWriter.writeVisibleBoxes`, `PersistentBufferRing` with fences (through
`InstanceStream`), `DrawCommandBuffer`, `DebugLines` for the frozen frustum.

**Controls.** Left mouse button and move to look, `W A S D` to fly, `Space` and `Left Control` up and down, `Left Shift` fast, `C` toggles the culling (off draws every box), `X` freezes the camera that the
culling uses and draws its frustum (fly away to see what the culling removes), `R` resets the camera.

**Options.** `--instances N` (1 000 000), `--threads N` (1), `--frames-in-flight N` (3), `--no-cull`.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, OpenGL 4.5, window 1600 × 900, vsync off, 400 frames after 100 of warm-up, one culling thread, the SIMD kernel, three frames in flight,
the HUD drawn (`--demo city --frames 400 --warmup 100`):

| | average |
|---|---|
| visible instances (of 1 000 001) | 499 606 |
| wait for the ring | 0.125 ms |
| frustum cull | 2.255 ms |
| write instances (32.0 MB per frame) | 6.334 ms |
| submit (uniforms, command, draw, fence) | 0.074 ms |
| GPU time of the draw | 7.473 ms |
| frame | 9.217 ms |
| allocated on the render thread | 2,425 B per frame |
| stalls waiting for the GPU | 0 |

The first version of the sample, before the framework, measured 9.4 ms ([SAMPLES.md](SAMPLES.md)); the numbers above are from the framework's first run (10.0 ms) and from the run after the box renderer and the shared scene were
extracted from the demo (9.2 ms), both with the HUD drawn: the difference between the two is run-to-run variation, not a change in the work. The 50 000-box smoke configuration passes with 2,424 B per frame against a budget of 4,096 B.

**Does not prove.** One GPU of one vendor; a synthetic scene of equal boxes; the frame is bound by the instance write and by the GPU, not by the library's culling, so it says little about scenes with
fewer, heavier objects. Culling quality (the culling is conservative; how much it culls) is tested in the library, not here.

### culling-lab: Culling lab: seven ways to cull

**Claim.** The same quarter of a million boxes culled by three batch kernels and four spatial structures behind one interface, each timed on the same view and each checked against the scalar kernel, so that the
cost of a structure (build, update, query) can be read next to what it saves.

**Uses.** `CullPipeline` and `CullStages.Frustum` with `FrustumKernels.scalar()`, `FrustumKernels.best()` (SIMD) and `ParallelFrustumKernel`; `StaticBvh` with `BvhQuery.frustum` and `refit`; `DynamicAabbTree` with its
`Query.frustum` and `move`; `LooseOctree` and `UniformGrid` with `overlapAabb` (they have no frustum query: the demo asks for the candidates in the box around the frustum and tests the six planes, which is also
what a user of them would do); `VisibilitySet`, `BoundsArray`, `DebugLines` for the BVH boxes and the frozen frustum, `InstanceWriter` through `InstanceStream` for the drawing.

**Controls.** Left mouse button and move to look, `W A S D` to fly, `M` and `N` or `1` to `7` to choose the method, `U` to move a fiftieth of the boxes every frame, `B` to draw the boxes of one BVH level (`[` and `]`
change the level), `T` to run every method on the current view and show the table with the build times, `X` to freeze the culling camera.

**Options.** `--instances N` (250 000), `--method NAME` (a scripted run then stays on that method), `--threads N` (4, for the parallel kernel), `--segment N` (120 frames per method in a scripted run), `--verify`,
`--animate`, `--show-bvh`. A scripted run of all seven methods is `--frames 840 --warmup 0`; the first 20 frames of each segment are left out of that method's numbers.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, 250 001 boxes, far plane 1500 m, a scripted flight in a circle (the same view for every method at the same frame), 120 frames
per method, `--demo culling-lab --frames 840 --warmup 0`; about 123 000 boxes (half) are in the frustum:

| method | cull | build | with `--animate`: update of the structure |
|---|---|---|---|
| scalar kernel | 1.559 ms | | |
| SIMD kernel | 0.619 ms | | |
| parallel kernel, 4 threads | 0.416 ms | | |
| static BVH | 0.336 ms | 392 ms | 7.790 ms (a full refit) |
| dynamic AABB tree | 4.735 ms | 204 ms | 5.377 ms (5 000 moves) |
| loose octree | 11.818 ms | 69 ms | 0.430 ms (5 000 moves) |
| uniform grid | 3.900 ms | 68 ms | 0.953 ms (5 000 moves) |

The render thread allocated 7,462 B per frame over the whole run (the smoke budget is 16,384 B), the frame was 5.6 ms with the GPU at 1.8 ms (it draws the visible half), and no stall. With `--verify` over the same
run (720 frames of the six methods other than the scalar kernel): no missed box, one box within a centimetre of a frustum plane that one method missed (counted as marginal, see below), no extra box.

What it shows: with half of the boxes in the frustum the BVH wins the query (0.34 ms, 4.6 times faster than the scalar kernel) by accepting whole subtrees, the SIMD and parallel kernels are close behind with
nothing to build, and the structures that cannot answer a frustum query directly lose: the octree and the grid have to enumerate the candidates of a box that covers most of the city (12 and 4 ms, slower than looking at
every box), and the dynamic tree, built for moving objects, queries about 14 times slower than the static one. What it costs to keep them current is the other half: a refit of the whole BVH is 7.8 ms for 2% of the
boxes moving, more than the 5,000 moves of the tree, the octree and the grid, so a mostly static scene belongs in the BVH and a scene of moving objects in the others.

**Findings.** (1) The SIMD kernel allocates about 9.5 MB per frame over its first 100 frames on 250 000 boxes while the JIT compiles it (a run of the SIMD method alone with no warm-up, 100 frames); the demo runs every method until it stops allocating before the first frame (`Warmup`), and `docs/DEMOS.md` section 3 explains why the smoke run is long. (2) `--verify` found that the uniform grid's result
(through the candidate test of the demo) differed from the scalar kernel's by one box in 720 frames, a box that lay on the right frustum plane to within the float rounding: the scalar kernel kept it and
`Frustumf.intersects` and the candidate test rejected it, which is the rounding of two correct tests, not a defect. The check therefore treats a box within 1 cm of a plane as marginal and counts it. (3) `StaticBvh.depth()` walks the whole tree and allocates 1.5 MB per call, which is fine for a diagnostic and not
for a per-frame call; the demo reads it once.

**Does not prove.** One scene (boxes of the same size on a grid, so the structures are not stressed by size variation or clustering), one frustum shape (half the scene visible), one machine, a scalar test of the
candidates for the octree and the grid, and no multi-threaded queries of the structures (the library supports them; the demo does not use them).

### interior-portals: Interior with doors: portal culling

**Claim.** A building of 400 rooms and 508 doors where every door can be opened and shut, culled first by the frustum and then by portals: the objects the camera sees through the open doors are 1.1% of the building, and
a shut door removes the room behind it.

**Uses.** `PortalGraph` with its `Builder` (`addBox`, `autoPortals`, `setPortalOpen`, `assignAll`, `locate`), `PortalStage` and `PortalCuller` (`traverse`, `visibleSector`, `sectorRect`, `cullObjects`), `CullPipeline`
with `CullStages.Frustum`, `VisibilitySet`, `DebugLines` for the sectors and the portals, `InstanceStream` and `BoxRenderer` for the drawing.

**Controls.** Left mouse button and move to look, `W A S D` to walk, `Space` and `Left Control` up and down, `Left Shift` fast, `E` shuts or opens the nearest door, `O` opens every door, `K` shuts every door, `G` draws the
visible sectors (green, the camera's room white), their portals (yellow open, red shut) and the screen rectangle through which each sector is seen, `C` takes the ceilings away, `X` freezes the culling camera (fly up in the
cutaway to see exactly what was drawn).

**Options.** `--rooms N` (20), `--props N` (150), `--overlay`.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, a scripted walk through a column of rooms (the maze is forced to have doors there) with the view swinging from side to side, 1000
frames after 100 of warm-up, `--demo interior-portals --frames 1000 --warmup 100`; the building has 400 rooms, 508 doors, 1,016 portals, 908 sectors and 63,605 objects, and takes 123 ms to build:

| | average |
|---|---|
| objects in the frustum (of 63 605) | 20,672 |
| objects after the portals | 718 |
| sectors reached by the traversal (of 908) | 24.7 |
| frustum kernel | 0.147 ms |
| locate the camera, traverse the doors, cull the objects | 0.074 ms |
| frame | 0.533 ms |
| GPU time of the draw | 0.086 ms |
| allocated on the render thread | 3,031 B per frame |

The frustum alone keeps a third of the building, most of it behind walls; the portals keep 3.5% of that, and they cost half as long as the frustum test. The test `BuildingTest` checks the same thing on a small building:
with every door shut the camera sees only its own room.

**Does not prove.** A synthetic building of identical rooms with doors in a regular pattern; no stairs, no windows, no portals that are not boxes, and no moving objects (the library re-assigns them with `PortalGraph.update`;
the demo does not exercise it). The sectors of this building are boxes, so the traversal's work per portal is the simplest case.

### occlusion: Occlusion culling in a dense city

**Claim.** The buildings near the camera rasterised into a 256 × 128 depth buffer hide 98% of the boxes in the frustum, and a ray check finds no visible box among the 172,855 it samples from the ones that were
removed; on this scene the test costs more than the drawing it saves (TD-29).

**Uses.** `DepthBuffer` (`begin`, `addBox`, `finish`, `isHidden`, `invDepth`), `OcclusionStage`, `CullPipeline` and `CullStages.Frustum`, `Scenes.blocks` (`BoundsArray`, `Noise`, `Rng`), `StaticBvh` and `BvhQuery.raycastBounds`
with `Rayf.through` for the check, `DepthInset` for the picture, `InstanceStream` and `BoxRenderer`.

**Controls.** Left mouse button and move to look, `W A S D` to fly, `Space` and `Left Control` up and down, `Left Shift` fast, `O` occlusion culling on or off, `I` the depth buffer as a picture (`[` and `]` show a coarser
level of the Hi-Z pyramid), `Y` the ray check on or off, `X` freezes the culling camera.

**Options.** `--blocks N` (100), `--props N` (40), `--depth W` (256), `--verify`, `--no-occlusion`, `--threads N` (1).

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, a scripted walk along a street, 800 frames after 100 of warm-up, 10,000 buildings and 400,000 props (410,001 boxes), 143
buildings rasterised per frame:

| | occlusion on, 1 thread | occlusion on, 4 threads | occlusion off |
|---|---|---|---|
| boxes in the frustum | 142,945 | 142,945 | 142,945 |
| boxes drawn | 2,925 | 2,925 | 142,945 |
| frustum kernel | 0.970 ms | 0.921 ms | 0.963 ms |
| choose occluders | 0.026 ms | 0.023 ms | |
| rasterise | 2.466 ms | 2.278 ms | |
| occlusion test | 15.291 ms | 4.804 ms | |
| GPU time of the draw | 0.525 ms | 0.293 ms | 2.157 ms |
| frame | 19.776 ms | 8.940 ms | 3.246 ms |
| allocated on the render thread | 2,746 B | 3,151 B | 2,424 B |

With `--verify` (800 frames, 1 thread) the demo shot rays at nine points of 172,855 removed boxes that were on screen and found no free ray; the check is the conservative-never-wrong proof, run against the demo's own
rasterised occluders. The same CPU code (`OcclusionFrame`) runs in `OcclusionFrameTest` on a small city without a window, and the smoke run does not use `--verify` because the check allocates (a BVH of
the occluders and a ray per sample) and the smoke run enforces the allocation budget. The cheap cubes are the reason the culling loses: the GPU draws 143,000 of them in 2.2 ms, so removing 98% of them saves about 1.6 ms of GPU time and costs 15 ms of CPU time (about 107 ns per box); with
four threads, which the library's documented thread safety of a finished buffer allows, 4.8 ms. It would win for objects that are expensive to draw; making it win for cheap ones is TD-29.

**Findings.** (1) TD-29, above. (2) The first version of the check failed on boxes that stand partly off screen: the depth buffer decides only what is on the screen, so the demo uses only sample points inside the view
(with a half metre margin) as evidence. (3) The demo's first street was inside a building; the check failed at once, because the camera was inside an occluder, which the ray test and the depth buffer both treat as
undefined, and the street's position was corrected.

**Does not prove.** A synthetic city of boxes, one machine, and only the occluders the demo chooses (buildings within 300 m, at most 3,000): which occluders to rasterise, and how to rasterise them front to back, is
where a real engine spends its effort. The depth buffer is low resolution on purpose; a higher one (`--depth`) culls more and costs more in the rasterisation.

### dq-vs-lbs: Dual quaternion against linear blend skinning

**Claim.** An arm twisted by one joint, skinned in the vertex shader by linear blending on the left and by dual quaternions on the right: at half a turn the linear blend pinches the blended ring to a point (0.003 m
against a bind radius of 0.500 m) and the dual quaternion keeps it at 0.500 m, and the shaders' skinning is the same as the library's CPU reference to 3.6e-7.

**Uses.** `Skeleton`, `Pose`, `Skinning.jointMatrices`, `Skinning.jointDualQuaternions` for the joints; `Skinning.skinPositions`, `skinNormals`, `skinPositionsDualQuat` and `skinNormalsDualQuat` as the reference and for the
waist radius; `Mat4fArray`; `Cameraf.toScreen` for the labels. The GLSL (`SkinningShaders`) is a port of the two dual quaternion methods.

**Controls.** Left mouse button and move to orbit, wheel to zoom, `Left` and `Right` to turn the joint from 0 to 360 degrees, `Space` for the automatic twist back and forth, `L` for wireframe, `R` to reset the camera.

**Options.** `--verify`: at ten angles (0, 45, 90, 135, 170, 180, 190, 270, 359 and 360 degrees) the same GLSL function runs in a compute shader over all 1,312 vertices, the skinned positions and normals are read back, and the run
fails if any differs from the CPU reference by more than 2e-4 (positions) or 2e-3 (normals).

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, 720 frames after 60 of warm-up, the twist sweeping from 0 to 200 degrees and back every 240 frames, `--demo dq-vs-lbs --verify --frames 720 --warmup 60`:

| | linear blend | dual quaternion |
|---|---|---|
| radius of the ring in the middle, average over the run (bind pose 0.500 m) | 0.287 m | 0.500 m |
| smallest radius over the run | 0.003 m | 0.500 m |

The largest difference between the shaders and the CPU reference at the ten angles was 3.58e-07. The frame took 0.42 ms with 0.019 ms on the GPU, and the render thread allocated 1,249 B per frame (the smoke budget is 4,096 B).
`TwistRigTest` checks the same numbers on the CPU: a full turn is the bind pose for both methods, the waist stays within 0.005 of 0.500 at every tenth degree for dual quaternions and collapses below 0.02 for linear blending.

**Does not prove.** A tube, not a character: two joints, one twist axis, weights that blend over a band of 1.6 m, and no scale (dual quaternions are for rigid joints only, as the library says). It does not compare the cost of the two methods on a real mesh (the frame time of 1,312 vertices says nothing), and it checks the
shader on one GPU of one vendor.

### rigid-pile: A pile of boxes and spheres

**Claim.** Up to 3,000 boxes and spheres dropped into a pit and simulated with the library's rigid-body pieces, none of which leaves the pit, at a cost that the card measures per part of the step.

**Uses.** `RigidBody`, `MassProperties` (`box`, `sphere`), `DynamicAabbTree` (`insert`, `move`, `Query.overlapAabb`), `ConvexPolytope` (`of`, `transformed`), `ManifoldBuilder` (`polytopes` for box pairs, `shapes` for anything with a sphere, through GJK), `ContactManifold`,
`ContactSolver` (`Params`, `solveAll`), `Rng`; `InstanceStream.writeInstance` and a shader that reads rotated instances for the drawing, `DebugLines` for the contacts.

**Controls.** Left mouse button and move to orbit, wheel to zoom, `C` to draw the contact points (red) and normals (yellow), `N` to add a thousand bodies, `R` to reset the camera.

**Options.** `--bodies N` (1,000), `--per-frame N` (4 bodies added per frame), `--contacts`, `--verify` (every 30 frames, fail if a body is below the floor, outside the walls or not a number).

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, one thread, a pit of 32 × 32 m with 8 m walls, bodies of half extents 0.3 to 0.7 m dropped from 8 to 22 m, 20 bodies per frame until all are in, 300 frames after 200 of warm-up,
`--demo rigid-pile --bodies N --per-frame 20 --verify --frames 300 --warmup 200`:

| bodies | pairs | contact points | broad phase | narrow phase | solver | step | per body | allocated per frame |
|---|---|---|---|---|---|---|---|---|
| 250 | 406 | 819 | 0.31 ms | 1.0 ms | 1.6 ms | 3.0 ms | 12 us | 11 kB |
| 1 000 | 3 548 | 3 888 | 2.8 ms | 12.9 ms | 8.2 ms | 24.1 ms | 24 us | 45 kB |
| 2 000 | 9 751 | 7 880 | 7.9 ms | 36.6 ms | 19.8 ms | 64.6 ms | 32 us | 91 kB |
| 3 000 | 16 018 | 11 246 | 15.6 ms | 60.3 ms | 29.2 ms | 105.4 ms | 35 us | 141 kB |

(The first measurement, before PERF-2, was 54.3 ms for 1 000 bodies with 41.3 ms of narrow phase and 8.9 MB allocated per frame, and 228.9 ms for 3 000 bodies; the boxes now go through `ManifoldBuilder.boxes` and the spheres through GJK, which is what the narrow phase spends its time on now. The pile is not the same pile: the pair counts differ because the bodies settle differently with warm starting on.)

No body left the pit at any check, and the kinetic energy at the end was 7.3, 16.3, 42.7 and 111.9 J (the pile never comes fully to rest: there is no sleeping; warm starting is on since PERF-2 and lowered the energy a little). The smoke configuration (150 bodies) allocated 1.4 MB per frame against a budget of 4 MB before PERF-2.
Before PERF-2 the narrow phase was 71% of the step at 1,000 bodies: about 11 us per box pair, and the polytope that each box needs per step was where the allocation came from (TD-30). Now it is 54%: a box pair costs 0.5 us (`ManifoldBuilder.boxes`), and what is left is the pairs with a sphere, which go through GJK and EPA at several microseconds each. `PileSimulationTest` runs 120 bodies for 1,200 steps without a window and checks the same properties.

**Findings.** (1) TD-30. (2) With drops from up to 40 m, 1 m thick walls and no speed limit, a box was thrown out of the pit at frame 240 of a 3,000-body run (the engine has no continuous collision detection); the demo drops from at most 22 m, clamps the speed to 25 m/s and has
4 m thick walls. (3) A pit that is too small overflows: 3,000 bodies filled a 24 × 24 m pit to the top of 7 m walls and bodies sat on the wall; the pit is 32 × 32 m.

**Does not prove.** A toy engine: boxes and spheres only, no islands, no sleeping, no joints, no warm starting, no continuous collision detection, and a fixed step of 1/60 s with one solver pass. It shows the building blocks working together and what they cost, and it is not a measure of what a physics engine does.

### sdf-sculpt: Sculpting with signed distance fields

**Claim.** A shape built with the library's CSG is sampled onto a grid, a brush adds and carves with smooth operations where a sphere-traced ray from the pointer hits the field, and the surface is re-meshed with surface nets after every stroke: 27,000 triangles in 10 ms on a 64³ grid.

**Uses.** `Sdfs` (`sphere`, `cylinder`, `torus`, `smoothUnion`, `subtract`, `union`, `smoothSubtract`, `raycast`), `Sdf`, `SurfaceNets` (`mesh`, `normals`, `projection`), `Cameraf.pickRay`, `DebugLines` for the brush.

**Controls.** Hold the right mouse button to add material, with `Left Shift` to carve; the left mouse button orbits, the wheel zooms, `[` and `]` change the brush size, `T` draws the wireframe, `Z` goes back to the starting shape, `R` resets the camera.

**Options.** `--grid N` (64; from 16 to 192), `--no-normals` (flat shading), `--projection N` (Newton steps per vertex), `--verify` (every 10 frames: fail if an edge of the mesh has no partner; count the frames with edges that more than two triangles share).

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, one thread, a scripted brush path on the screen (adding for 150 frames, carving for the next 150), the ray cast and the stamp and the meshing and the upload in every frame in which the ray hits the shape (406 of 900 frames at 64³).
The mesh and upload rows are averages over the frames that re-meshed:

| grid | vertices | triangles | pick | stamp | mesh | meshing rate | upload |
|---|---|---|---|---|---|---|---|
| 64³ (`--verify`, 900 frames after 100) | 13,613 | 27,350 | 0.006 ms | 0.18 ms | 10.5 ms | 2.6 Mtri/s | 0.21 ms |
| 96³ (600 frames after 100) | 29,708 | 59,527 | 0.008 ms | 0.46 ms | 34.8 ms | 1.7 Mtri/s | 0.42 ms |
| 128³, flat shading (600 frames after 100) | 54,232 | 108,593 | 0.010 ms | 0.91 ms | 72.0 ms | 1.5 Mtri/s | 0.61 ms |

The meshing of the whole grid is the cost (it samples every corner of every cell, with a trilinear read of the stored field), the stroke itself is a fifth of a millisecond, and the GPU draw is 0.05 ms. The 64³ run allocated 18,374 B per frame (the composed `Sdf` of each stamp and the new mesh arrays; the smoke budget is 32,768 B).
With `--verify` the mesh had no edge without a partner at any of the 43 checks.

**Findings.** (1) TD-31: 42 of the 43 checks found a few edges (at most 128 of about 54,000) that more than two triangles share; the starting shape has none, and one added sphere that is wider than a hole in the shape gives eight. (2) A torus that touched the body along a thin lens did the same before it was moved away from it. (3) Since PERF-4, `--manifold` gives each sheet of a cell its own vertex: 15 of 21 checks (at most 6 edges) instead of 20 of 21 (at most 128); the mesh time and the allocation per frame are the same within noise (10.7 against 11.0 ms).

**Does not prove.** A whole-grid re-mesh of 64³ to 128³: a tool with a larger field would mesh only the chunks that the brush touched, which `SurfaceNets` does not offer (it meshes a box). The field is a grid of samples, so the brush is as sharp as a cell; the library's `Sdf` composition is used only for the stamp.

### terrain: Terrain with levels of detail on a camera rail

**Claim.** A terrain of 256 chunks, each with a chain of levels of detail, drawn with the levels that `LodSelector` picks from the size of each chunk on the screen, in one indirect draw call, while the camera flies along a spline at a constant speed that an arc-length table
keeps constant to within 2.4% (68.4 to 71.6 m/s for 70).

**Uses.** `Noise.fbm2` (the heights, in `Terrain`), `Mesh`, `MeshLod.build` (a chain per chunk with the border locked) and `Chain.selectorThresholds`, `LodSelector` (with hysteresis), `CullPipeline` with `CullStages.Frustum`, `BoundsArray`, `VisibilitySet`, `DrawCommandBuffer` with
`glMultiDrawElementsIndirect`, `Curves.catmullRom` and `ArcLengthTable` (`Rail`), `Cameraf`.

**Controls.** `F` leaves the rail for a free camera (`W A S D`, `Space`, `Left Control`, `Left Shift`, left mouse button to look), `[` and `]` change the error budget, `L` tints the chunks by their level, `N` draws everything in view at full detail, `U` moves along the rail by equal
steps of the spline parameter, `T` draws wireframes.

**Options.** `--chunks N` (16), `--cells N` (48), `--budget PIXELS` (2), `--no-lod`, `--free`, `--parameter-speed`.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, 16 × 16 chunks of 256 m (a 4 km square) with 48 × 48 cells (4,608 triangles) at full detail, 1,200 frames after 100 of warm-up along the 7,403 m rail (12 control points) at 70 m/s, about 46 of the 256 chunks
in view. The chains were built in 5 to 7 seconds on the common pool (27 ms of one thread per chunk) and have 4 levels (the simplifier was asked for 5 and stopped at 4 on every chunk; with the border locked a chunk cannot go below its 192 border vertices, so the coarsest level has about 192 triangles); over
all chunks the levels have 1,179,648, 353,792, 105,984 and 49,180 triangles; 943,630 vertices and 5,065,812 indices were uploaded.

| error budget | triangles drawn | of the triangles at full detail | GPU time of the draw |
|---|---|---|---|
| 0.5 px | 214,245 | 100% | 0.080 ms |
| 2 px (default) | 114,115 | 53% | 0.069 ms |
| 8 px | 55,213 | 26% | 0.059 ms |
| 32 px | 22,545 | 10.5% | 0.055 ms |

The frustum cull of the chunks takes 0.010 ms, the level selection 0.003 ms and the 46 indirect commands 0.003 ms; the frame is 0.5 to 0.6 ms in all of the runs, which is the runner's own work, and the render thread allocates 2,401 B per frame.
The camera speed, measured between frames: 68.4 to 71.6 m/s with equal steps of arc length, 36.5 to 109.4 m/s with equal steps of the spline parameter (`--parameter-speed`). `TerrainTest` checks the same properties on a small terrain: neighbouring chunks share their edge vertices, the chains have fewer triangles at every level and
keep every border vertex of the chunk, and steps of arc length are equal to within 3% while steps of the parameter vary by more than 30%.

**Findings.** (1) TD-32: the demo culls its 256 chunks with the scalar kernel because the SIMD one that `best()` picks took 0.096 ms against 0.010 ms and allocated 156 kB per frame. (2) The levels of detail save triangles but hardly time here: 47% fewer triangles at the default budget save 0.011 ms of GPU time, because 214,000
triangles of flat-shaded terrain cost the GPU 0.08 ms; the saving is real for a heavier shader or a larger world, and the card does not claim more. (3) A chunk of 48 cells cannot be simplified below its border (192 triangles), which is the price of cracks-free neighbours with a locked border.

**Does not prove.** One terrain function, one GPU, a camera that stays 90 m above the ground, flat shading with derivative normals (so the shape of the levels shows), no texturing, no streaming (all chains are built at the start and live on the GPU), and no cross-fade between levels (the selector can output a fade, the demo does not draw it).

### sky-sun: Sky, sun, camera exposure and tone mapping

**Claim.** A day of sunlight from the library's models, on a lawn with four spheres: the sun is 54.1 degrees up at noon at the solstice at 59.3 degrees north (the geometry says 54.1), it lights a surface with 101,584 lux and the sky with 31,178 lux, and the camera's automatic exposure lands at EV100 15.7, which is the photographers'
"sunny 16" rule; every number is on the screen and every control is a slider.

**Uses.** `SolarPosition` (`julianDay`, `position`, `direction`), `PreethamSky` (`rgb`, `zenithLuminance`), `Atmosphere` (`sunTransmittanceRgb`, `TYPICAL_ANGSTROM_ALPHA`), `PhysicalCamera` (`fullFrame`, `fNumberOfStops`, `ev100ForLuminance`, `shutterTimeFor`, `ev100`, `exposure`, `verticalFov`), `ToneMap` and `Srgb` (as the reference for the shader's
port), `Cameraf` for the view directions. The tables and the light of a moment are in `SkyModel`; the GLSL is in `SkyShaders`.

**Controls.** Drag the sliders with the left mouse button: the hour (UT, at longitude 0), the day of 2026, the latitude, the turbidity, the focal length, the aperture (in stops from f/1), the sensitivity (ISO 100 to 6400) and the shutter time or, with the automatic shutter, an exposure compensation of ±4 EV. The checkbox switches the
automatic shutter, the buttons choose the curve (clamp, Reinhard, extended Reinhard, ACES, Hable, 1-exp), the left mouse button elsewhere looks around, `Space` runs the clock, `R` resets the view.

**Options.** `--verify`: the shader's curves and sRGB encoding are evaluated in a compute shader for 2,048 values from 0.001 to 1,000 (and 0) for the five curves of the library, read back and compared with `ToneMap.apply` and `Srgb.fromLinear`; a difference above 2e-5 fails the run. The largest difference was 9.5e-7.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, a scripted day of 1,200 frames (24 hours) at the solstice at 59.3 degrees north, turbidity 3, automatic shutter, ACES, 24 mm, f/11.3, ISO 100; 1,200 frames after 60 of warm-up, `--demo sky-sun --verify --frames 1200 --warmup 60`:

| | |
|---|---|
| the sky table (256 × 64 texels, one `PreethamSky.rgb` each) and the light of the moment on the CPU | 2.98 ms |
| the upload of the table | 0.05 ms |
| the GPU time of the full-screen pass | 0.18 ms |
| the frame | 3.38 ms |
| allocated on the render thread | 1,072 B per frame (budget 4,096 B) |

Two moments of the same day, from the screen at frames 599 and 879 (hours 12.0 and 17.6):

| | noon | evening |
|---|---|---|
| sun elevation | 54.1° | 23.4° |
| direct sunlight on a surface facing the sun | 101,584 lux | 80,359 lux |
| sky on an upward surface | 31,178 lux | 21,367 lux |
| luminance of the zenith | 9,042 cd/m² | 4,329 cd/m² |
| luminance of a mid-grey card lying flat | 6,511 cd/m² | 3,074 cd/m² |
| automatic exposure | EV100 15.7: f/11.3, 1/407 s | EV100 14.6: f/11.3, 1/192 s |

`SkyModelTest` checks the model without a window: the sun is nearly overhead at the equator at the equinox, the direct sun is 80,000 to 127,500 lux and nearly white at noon, a low sun is redder and dimmer, haze dims the direct sun, the night sky is below 0.01 cd/m², and the automatic exposure of noon is between EV 13 and 17.5 and that of
night more than 10 stops lower.

**Findings.** (1) `PreethamSky` is not defined for a sun below the horizon, so the demo clamps the sun to the horizon for the sky and fades the day sky out over six degrees of civil twilight into a constant night sky (0.0006 to 0.0014 cd/m²): the fade and the night floor are the demo's, not the library's. (2) The library has no mapping from the turbidity of
the sky model to the haze of the transmittance; the demo uses `0.02 (T - 1)` for the optical depth at 1 µm, a rough choice. (3) The library's sun, sky and exposure models agree with the photographers' rule without any tuning: the automatic exposure at the solstice noon is EV100 15.7, and the sun's elevation is the geometric 54.1 degrees.

**Does not prove.** A clear-sky model with a sun disc and no clouds, single scattering, a flat lawn and four diffuse spheres lit by the sun and the sky irradiance only (no light between the objects), per-channel curves, one display (sRGB), and a sky that is a 256 × 64 table sampled bilinearly (the horizon band is 1.4 degrees per row). The sliders' hit tests and value mapping are unit-tested
(`SlidersTest`), but the dragging itself was not tried with a mouse in this check; only the scripted run and its screenshots were.

### cluster-lod: Cluster hierarchy with continuous level of detail

**Claim.** A dense rock is cut into clusters of 64 vertices and 124 triangles that are grouped and simplified level by level, and the clusters whose error is just small enough on the screen are drawn, chosen on the CPU by `ClusterHierarchy.select` or by the library's compute shader, which chose the same
clusters as the library's CPU reference of it at four distances (16,084 clusters compared, none differing): the first run of that shader on a GPU.

**Uses.** `Primitives.icoSphere`, `Noise.fbm3`, `ClusterHierarchy` (`build`, `select`, `level`, `indices`, `lodError` and the bounds that `ClusterCullObject.of` reads), `ClusterCullObject`, `ClusterCullView` and their `Gpu` writers, `GpuCullGlsl.clusterShader` (the level test, the frustum test, the normal-cone test), `ClusterCullReference`,
`DrawCommandBuffer`, `glMultiDrawElementsIndirect`, `OrbitCamera`, `ReadbackRing`.

**Controls.** Left mouse button and move to orbit, wheel to zoom, `G` switches between the CPU cut and the GPU cut, `[` and `]` change the error budget, `L` colours the clusters by level, `X` freezes the camera that the cut and the culling use (fly away to see what was chosen), `T` draws wireframes.

**Options.** `--detail N` (subdivisions of the icosphere, 6 by default: 81,920 triangles; 2 to 8), `--budget PIXELS` (1), `--gpu`, `--verify`.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, a scripted flight from 1.6 to 29.6 radii away (a quadratic approach and retreat every 240 frames) while orbiting, 480 frames after 60 of warm-up. The rock has 81,920 triangles in 4,021 clusters in 20 levels (58,427 pool vertices);
`ClusterHierarchy.build` took 1.4 to 1.8 s (it is a load-time step). With `--detail 7`: 327,680 triangles, 16,256 clusters, 231,593 vertices, 5.0 s.

| error budget | clusters drawn | triangles drawn | of the rock |
|---|---|---|---|
| 1 px (default) | 981 | 67,521 | 82% |
| 4 px | 831 | 49,927 | 61% |
| 16 px | 624 | 28,115 | 34% |

The cut: 0.095 ms on the CPU (`select` over 4,021 clusters; 0.44 ms over the 16,256 of `--detail 7`) against 0.021 ms for the shader, which also tests the frustum and the normal cone (it drew 915 clusters at 1 px against the CPU's 981). The GPU time of the multi-draw is 0.05 to 0.11 ms, the frame 0.5 to 0.8 ms, and the render thread
allocates 1,250 B per frame with the CPU cut and 3,721 B with the GPU cut (the smoke budget is 16,384 B). `ClusterSceneTest` checks the same on the CPU without a window: a larger budget chooses fewer clusters and triangles, and the library's reference of the shader chooses the clusters that `select` chooses among the ones in view.

**Verification.** At 1.5, 3, 6 and 20 radii the shader chose 962, 1,006, 1,015 and 770 clusters, the CPU reference the same sets: the level test selects 1,074, 1,074, 1,074 and 831 of 4,021, the frustum keeps 1,035, 1,074, 1,074 and 831 of them, and the normal cone removes up to 10% more (back-facing).

**Findings.** (1) `glMultiDrawElementsIndirectCount` is OpenGL 4.6, so under 4.5 the commands that the shader does not write have to be zero (the buffer is cleared every frame) and the multi-draw is issued with every slot: 4,021 mostly empty commands cost about 0.03 ms, which the GPU time above includes. (2) With a 1-pixel budget the
rock at 82,000 triangles only drops 18% of its triangles on this flight, because the simplifier's error estimate is conservative (documented in `MeshLod` as 3 to 12 times the measured distance); the savings begin at larger budgets or larger meshes.

**Does not prove.** One object (no instancing: the shader's eye is the camera in the space of the cluster data), no depth pyramid (a one-texel pyramid that hides nothing; occlusion is the next demo), flat shading with derivative normals, one GPU, and `glMultiDrawElementsIndirect` instead of the count variant of 4.6.

### gpu-culling: GPU-driven culling with a Hi-Z pyramid

**Claim.** The library's compute shader culls 410,001 boxes against the frustum and a depth pyramid built on the GPU in 0.08 ms, an indirect draw follows without the CPU looking at an object, 1,716 of the 142,945 boxes in the frustum are drawn, and the survivors equal those of the library's CPU model of the shader on every checked frame.

**Uses.** `GpuCullGlsl.computeShader`, `CullObject`, `CullView` and their `Gpu` writers, `GpuCullReference.cullSinglePass` and `HiZPyramid` (as the reference), `HiZ.mipSize`, `DrawCommandBuffer`, `Scenes.blocks`, `CullPipeline` with the SIMD frustum kernel (the comparison column), `SceneTarget`, `ReadbackRing`,
`GpuTimer`.

**Controls.** Left mouse button and move to look, `W A S D` to fly, `Space` and `Left Control` up and down, `Left Shift` fast, `O` switches the depth pyramid off (frustum culling only), `X` freezes the camera of the culling.

**Options.** `--blocks N` (100), `--props N` (40), `--no-occlusion`, `--verify` (every 20 frames: the depth is read back, the library's pyramid is built from it and compared with every level of the GPU's; the shader is run again with that pyramid and the matrix that made it and its survivors are compared with `cullSinglePass` on the same bytes; a difference fails the run).

**How it works.** The pyramid is built from the depth of the previous frame: a compute shader resamples the depth image to a base that is a power of two (2048 × 1024 for 1600 × 900) as normalised device depth, taking the farthest pixel under each texel, and a second one reduces each level into the next with `max`. The cull of a frame uses
its own frustum planes and the view projection of the frame that made the pyramid, appends survivors to one indirect command with `atomicAdd`, and the vertex shader reads the object index of each instance from the survivor list through an instanced attribute. The draw goes to an offscreen target so that its depth can be sampled.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, a scripted walk along a street, 800 frames after 100 of warm-up, 10,000 buildings and 400,000 props:

| | pyramid on | pyramid off (`--no-occlusion`) |
|---|---|---|
| boxes in the frustum (CPU count) | 142,945 | 142,945 |
| boxes drawn | 1,716 | 142,938 |
| GPU: culling shader | 0.077 ms | 0.059 ms |
| GPU: the draw | 0.067 ms | 0.924 ms |
| GPU: building the pyramid | 0.176 ms | 0.182 ms |
| CPU: the library's frustum kernel over all boxes, for comparison (no depth test) | 0.904 ms | 0.872 ms |
| frame | 1.431 ms | 1.747 ms |
| allocated on the render thread | 4,849 B per frame (smoke budget 16,384 B) | 4,848 B |

The culling with a depth test costs 0.25 ms of GPU time in all (shader and pyramid) and saves 0.86 ms of drawing; the CPU's frustum culling alone costs 0.9 ms and removes nothing that is hidden. This is the opposite of the CPU occlusion test of the `occlusion` demo (TD-29), which costs more than the draw it saves.

**Verification.** `--verify --blocks 40 --props 30`, 200 frames after 60, 49,601 objects: 13 frames checked, the GPU pyramid identical to `HiZPyramid` at every one of 36,350,639 texels, 6,696 survivors compared with `GpuCullReference.cullSinglePass`, 0 only on the GPU and 0 only on the CPU. `CullDataTest` checks the data and the reference
on the CPU: the layout of the records, that an empty pyramid keeps what the frustum kernel keeps, that a wall in the depth image hides the box behind it and a huge `nearW` switches the test off.

**Findings.** TD-33: the library's shader and `HiZPyramid` halve level sizes rounding up and OpenGL rounds down, so the pyramid needs a power-of-two base on OpenGL (`glTextureStorage2D` with the library's 12 levels failed with `GL_INVALID_OPERATION` at 1600 × 900); the shader wants normalised device depth, so OpenGL's window depth is converted `2 d - 1`; and the pyramid needs
`GL_TEXTURE_FETCH_BARRIER_BIT` and `GL_TEXTURE_UPDATE_BARRIER_BIT` after the compute passes. The first run on hardware otherwise matched the CPU model exactly.

**Does not prove.** One pass with last frame's depth (the known risk: an object uncovered by a fast turn can be wrongly removed for a frame; the two-phase scheme of the library's Javadoc is not built), one draw (one mesh for all objects), boxes drawn as boxes, one GPU of one vendor, and the verification rendering the same frame's pyramid (it compares the shader and the model on identical inputs, not last-frame reuse).

### clustered-lights: Clustered forward lighting with thousands of lights

**Claim.** Thousands of point lights are assigned to the clusters of a frustum grid by the library and the fragment shader looks up its own cluster with the text of `ClusterGrid.glslLookup`: at 4,096 lights the lighting costs 0.9 ms of GPU time against 61.7 ms for a loop over every light, and the image is the same as the loop's (largest difference 0 of 255 on three views).

**Uses.** `ClusterGrid` (`of`, `glslLookup`, `clusterCount`, `tanHalfFovX`), `ClusterLights` (`addPoint`, `assign`, `count`, `offset`, `contains`, `totalAssignments`, `writeRanges`, `writeIndices`), `Mat4f` (the view matrix), `Scenes.blocks`, `CullPipeline` (scalar kernel), `InstanceStream`, `SceneTarget`.

**Controls.** Left mouse button and move to look, `W A S D` to fly, `Space` and `Left Control` up and down, `Left Shift` fast, `M` cycles the clusters, the loop over every light and the heat map of the lights per cluster, `[` and `]` halve and double the number of lights.

**Options.** `--lights N` (4,096), `--blocks N` (16), `--brute`, `--heat`, `--verify` (at the start, three views at 640 × 360 are rendered both ways and read back; a colour value that differs by more than 2 fails the run).

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 × 900, vsync off, a scripted walk down a street of a city of 256 buildings and 5,120 props (5,377 boxes), 64-pixel tiles and 24 depth slices (9,000 clusters), lights of range 5 to 11 m moving on circles, 400 frames after 100 of warm-up:

| lights | assignments | per cluster (mean, worst) | assignment on the CPU | GPU time of the draw | frame |
|---|---|---|---|---|---|
| 1,024 | 16,252 | 1.8, 33 | 2.90 ms | 0.12 ms | 3.4 ms |
| 4,096 | 50,929 | 5.7, 124 | 12.1 ms | 0.89 ms | 12.9 ms |
| 10,000 | 140,017 | 15.6, 291 | 28.3 ms | 3.27 ms | 29.2 ms |
| 1,024, loop over every light | | | 3.18 ms | | 13.0 ms |
| 4,096, loop over every light | | | 12.1 ms | 61.7 ms | 52.5 ms |

The CPU assignment is the cost, as the library says of it (a reference and an oracle, not a production path): at 10,000 lights it is 28 ms of the 29 ms frame, while the GPU needs 3.3 ms. The upload is 0.05 to 0.25 ms. The render thread allocated 6,319 B per frame at 2,048 lights (the smoke budget is 16,384 B). `LightFieldTest` checks without a window that every light that reaches a
random point is listed in that point's cluster (4,000 points, 300 lights).

**Verification.** At 2,048 lights, three views at 640 × 360: 1,382,400 colour values per view compared, none differing by more than 0 (the clustered lists are in ascending light order, so the sums are made in the same order as the loop's).

**Findings.** (1) `ClusterGrid.glslLookup` ran on a GPU for the first time and indexes the right cluster (a wrong index would show as a different image). (2) The cluster of a fragment is found from `gl_FragCoord` with the grid built for the window's origin (`yDown = false` for OpenGL); a window resize makes the demo rebuild the grid and recompile the program, since the grid's constants are baked into the text.

**Does not prove.** Point lights only (spot lights are in `ClusterLights`, not used), no shadows, a CPU assignment (a compute-shader assignment would remove the cost that the table shows and is not in the library), diffuse shading only, one GPU, and a scene of boxes whose heavy overdraw is not what a real scene looks like.

### globe: The whole Earth as map tiles

**Claim.** The Earth is drawn from a Web Mercator pyramid of height tiles and image tiles placed on the WGS-84 ellipsoid, from 20,000 km up to 2 m above the ground, with about 320 tiles on the screen at a time chosen by `TileSelector`: 0.21 ms of selection and 0.8 ms for the frame, and the vertex positions of the floating-origin pipeline are within a millimetre of the exact ones from 100 m to 10 km above the ground, where the naive float pipeline is off by 0.05 to 0.27 m.

**Uses.** `Wgs84` and `Geodetic` (every vertex, the local frames of the camera and of the normals), `WebMercator` and `TileId` (the pyramid, the tile edges), `TileBounds` (the boxes), `TileSelector` with `HorizonCuller` (the walk, the culling, the balance), `Frustumd` and `Mat4d` (the culling in `double` in ECEF), `TerrainRgb` (the Terrarium decode, the grid normals), `Ellipsoids.rayWgs84` (the point that the camera looks at), `FloatingOrigin` (the origin of the camera-relative positions), `Noise.fbm3` (the planet), `DrawCommandBuffer` with `glMultiDrawElementsIndirect`, `GpuTimer`, `Stats`.

**Controls.** `G` switches between the scripted descent and the free camera (mouse to look, `W A S D` to move along the view, `Space` and `Ctrl` up and down, `Shift` four times faster, the wheel scales the speed; the speed is half the height above the ground per second), `J` switches between the floating origin and the naive pipeline, `H` the horizon culling, `F` freezes the camera that the selection uses (fly away to see what was chosen), `C` colours the tiles by zoom level, `T` draws wireframes, `[` and `]` change the allowed distance between vertices.

**Options.** `--max-zoom N` (17), `--min-zoom N` (2, always resident), `--cells N` (32 quads along a tile), `--image N` (128 pixels per tile image), `--pixels P` (6), `--resident N` (3072 tiles in the GPU buffers, at most 4095), `--flight SECONDS` (12), `--threads N`, `--naive`, `--free`, `--no-horizon`, `--verify`.

**The data.** Procedural, because no demo downloads anything: `Planet` makes the height and the colour of every point from noise on the unit sphere (seamless across the antimeridian and the poles, the same at every zoom), and `ProceduralTileSource` delivers it the way a tile set would: heights as Terrarium bytes on a node-registered grid, images as RGBA. The geometry is exact (the ellipsoid, the EPSG:3857 tile grid, the Terrarium encoding); the continents are not the real ones. A source of real tiles implements `TileSource`.

**How it works.** The selector walks from the 16 tiles of zoom 2. A tile that is missing is requested from the worker threads (coarsest missing ancestor first), and until it arrives the nearest resident ancestor is drawn instead, once and only until all the tiles that replace it are there. Every resident tile owns a block of one vertex buffer and a layer of an image array (four arrays of 1,024 layers, because the driver limits an array texture to 2,048); the least recently used tile that has not been used for two frames is evicted. A tile mesh is 33 by 33 nodes with a skirt along each edge, positions relative to the point of the ellipsoid under the middle of the tile, narrowed to `float` after the subtraction in `double`. The vertex shader forms `position + (tileOffset - cameraLocal)` with the tile offset and the camera relative to a `FloatingOrigin` (or, with `J`, the ECEF positions narrowed to `float`). The caps beyond the 85.05 degrees of the projection are two fans.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 x 900, vsync off, no HUD, the scripted descent of 12 s (720 frames; 700 measured after 60 of warm-up). The tiles of the whole flight are loaded before the first frame so that the numbers do not depend on how fast the workers are: 2,510 tiles in 15.2 s on 10 threads, 0 evicted, and 0 tiles were loaded while measuring.

| | 6 px (default) | 12 px |
|---|---|---|
| tiles selected (mean over the flight) | 321 | 134 |
| tiles drawn (with substitutes and the two caps) | 323 | 136 |
| nodes tested | 634 | 327 |
| outside the frustum, mean per frame | 156.8 | |
| behind the horizon, mean per frame | 1.63 | |
| split by the balance, mean per frame | 59.3 | |
| selection | 0.210 ms | 0.111 ms |
| GPU, the draw | 0.245 ms | 0.135 ms |
| frame | 0.81 ms | 0.66 ms |
| tiles needed over the flight | 2,510 | 928 |
| allocated on the render thread | 6,778 B per frame | 6,780 B |

With the horizon test off the selection was 322 tiles and 637 nodes; with the naive pipeline the frame took the same time (0.76 ms against 0.81 ms in the runs made). A run with `--pixels 3` needs 8,490 tiles over the flight, more than the 3,072 slots, so it reloads tiles all the time and is not a measurement of drawing (52 ms per frame, almost all of it generating tiles).

Vertex position error (`Jitter`, 400 vertices on the ground within three times the height, at most 3 km, of the point below the camera; the shader's float arithmetic against the exact difference in `double`):

| height of the camera | naive | floating origin |
|---|---|---|
| 100 m | 0.219 m | 0.00003 m |
| 1 km | 0.049 m | 0.0002 m |
| 10 km | 0.266 m | 0.0006 m |
| 1,000 km | 0.322 m | 0.057 m |

Far from the surface the gain disappears (the HUD showed 1.17 m for the naive pipeline and 1.51 m for the floating origin at 19,154 km), which does not matter there: a metre is a small fraction of a pixel at that distance.

**Verification.** `--verify` runs at seven views from 20,000 km to 5 m (the default zoom range): 22,175 pixels of the view were each traced to the ground with `Ellipsoids.rayWgs84` and every one lies in a selected tile (0 holes); neighbouring selected tiles differ by at most one level at every view; and ten vertices of resident tiles at each view were read back from the GPU and compared with `Wgs84.toEcef` of the height in the tile's data: the largest error was 0.075 m in the tiles of zoom 2 to 4 (2,500 to 10,000 km wide; 3.4e-8 of the distance from their reference point, which is the resolution of a `float`) and 0.015 m or less in every other view. `GlobeTest` and the library's tests check the same pieces without a window.

**Findings.** (1) With the selection by screen size the horizon test removes almost nothing (1.6 nodes per frame): the tiles behind the horizon are far away and so are coarse, the frustum has already removed most of the rest, and the few big tiles that straddle the horizon cannot be culled by a test that has to be conservative. It is cheap (40 ns a node) and correct, but on a descent like this one it saves one tile in 320. (2) The balance splits 59 tiles per frame on average, and tiles one level apart do occur next to each other (the library's test sees them), so the skirts have work to do. (3) Camera-relative rendering takes the position error near the camera from 0.05 to 0.27 m down to under a millimetre; far from the surface it gains nothing, and does not need to. (4) A descent from orbit needs 2,500 tiles of this size and a free flight much more, so the cache and its eviction are part of the demo, not an extra: `--pixels 3` shows what happens when the working set exceeds the slots. (5) The image arrays are limited to 2,048 layers by the driver, which is why there are four of 1,024.

**Does not prove.** That the maths is right for the real Earth's data (the tiles are noise on an exact geometry), a download or a decode path for real tiles (the codecs are exercised, a network and a PNG decoder are not), geoid heights (the heights are above the ellipsoid), an atmosphere (a haze and a rim glow only), depth precision from orbit to the ground in one depth buffer (the near and far planes follow the height, but there is no logarithmic depth), shadows, water, and anything about other GPUs or the speed of the workers while flying (a scripted run waits for the tiles).

### cascaded-shadows: Cascaded shadow maps with caster culling

**Claim.** A moving sun lights a city of 49,601 boxes through four cascaded shadow maps that the library fits, each drawn from only the boxes that can shadow its slice; the culling never removed a box that shadows a point of a slice (0 of 11,127 shadowing boxes), the maps drawn from the casters are texel for texel the maps drawn from every box, and a lookup with `Cascade.textureMatrix()` agrees with a ray to the sun at all but 16 of 8,930 surface points, none of which a ray within two texels contradicts.

**Uses.** `Cascades.fitAll` (the splits, the stabilised and texel-snapped light-space projections), `Cascade.frustum()` and `CascadeCasters` (the two culling stages of each map), `Cascade.textureMatrix()` and `texelSize()` (the shader's lookup), `CullPipeline` with the SIMD frustum kernel, `Cameraf`, `Intersectionf.rayAabb` (the brute-force ground truth), `InstanceWriter.writeBox`, `DebugLines.frustum`, `GpuTimer`.

**Controls.** `G` free camera (`W A S D`, `Space`, `Shift`, mouse), `C` tints the scene by cascade, `L` switches the footprint test of `CascadeCasters` off, `X` freezes the camera that the cascades are fitted to and draws the volumes of the cascades and of that camera's frustum (fly away with `G`), `P` stops the sun.

**Options.** `--blocks N` (40: 1,600 buildings), `--props N` (30), `--cascades N` (4, up to 8), `--map N` (2048), `--lambda L` (0.8), `--distance D` (400 m), `--no-stabilize`, `--no-footprint`, `--tint`, `--show`, `--verify`.

**How it works.** Each frame the camera's range is split and a cascade is fitted to each slice. The boxes of the city sit in one storage buffer; each pass reads its own list of box indices (the view, and one list per cascade) and draws the cubes with one instanced call. A shadow pass renders the casters of a cascade into one layer of a depth array texture (polygon offset, no culling); the scene pass selects the cascade by the clip-space `w`, moves the position along the normal by one and a half texels, transforms it by the cascade's `textureMatrix()` and reads 3 by 3 taps of a hardware-comparing sampler.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 x 900, vsync off, no HUD, 800 frames after 100 of warm-up, a slow circle above the roofs of 49,601 boxes (a sun that turns once in 120 s), four cascades of 2,048 x 2,048 texels, shadow distance 400 m, the splits 0.5, 22.2, 51.4, 120.2 and 400 m with texels of 0.026, 0.058, 0.135 and 0.456 m.

| | footprint test on (default) | `--no-footprint` |
|---|---|---|
| boxes in the view frustum | 21,446 | 21,446 |
| casters in all four maps | 30,290 (2, 72, 2,044 and 28,171) | 49,393 (4, 376, 4,702 and 44,311) |
| of them outside the view | 10,667 | 26,378 |
| fit and cull on the CPU | 2.206 ms | 0.891 ms |
| GPU: the four shadow passes | 0.732 ms | 0.865 ms |
| GPU: the scene | 0.343 ms | 0.300 ms |
| frame | 3.456 ms | 2.160 ms |
| allocated on the render thread | 8,036 B per frame (smoke budget 16,384 B) | 7,325 B |

With a 1,024-texel map the shadow passes took 0.427 ms and the texels doubled (0.053, 0.115, 0.270 and 0.912 m). With one cascade the fit and cull was 1.573 ms, the shadow pass 0.408 ms and the frame 2.512 ms; with eight, 3.268 ms, 1.084 ms and 4.185 ms (the four-cascade default: 2.206, 0.732 and 3.456).

**Verification.** `--verify` runs at three views (frames 0, 400 and 900 of the flight, with the sun at three places): (1) points of each slice (3,000 per cascade and view) are traced to the sun against all 49,601 boxes, and every box that shadows one of them (11,127 points were in shadow) must be a caster: 0 were culled; (2) the four maps from the casters are compared with the maps from every box, texel by texel inside the slice's footprint in the map up to the depth of its farthest corner (what lies deeper can never shadow it): 0 of 1.8 to 2.1 million texels per cascade differ; outside the footprint, in the extra that the fitted sphere adds, 0.2 to 1.6 million texels per cascade do differ, which is what `CascadeCasters` promises (it keeps the boxes that can shadow the slice, not everything that the map could see); (3) 6,000 rays through random pixels per view find the surface point, and the verdict of a nearest-texel read of the map with the library's texture matrix is compared with a ray from the point to the sun: 8,930 points, 16 disagreeing (0.18%), 0 of which no ray within two texels agrees with (the mismatches are on shadow edges). `ShadowSceneTest` repeats (1) on the CPU for six cameras and sun positions.

**Findings.** (1) The first run of `Cascade.textureMatrix()` and its depth convention on a GPU: with `DepthRange.NEGATIVE_ONE_TO_ONE` the matrix gives the depth that OpenGL stores with the default depth range, and the shader uses it unchanged. (2) The footprint test costs more than it saves here: 1.3 ms of CPU time to remove 19,100 draws that cost 0.13 ms of GPU time (TD-34). (3) 35% of the casters (10,667 of 30,290) are outside the view frustum: a culling of the casters by the view alone would leave their shadows out. The far cascade carries almost all the casters (28,171 of 30,290) because its slice is 280 m deep. (4) A `CullStages.Frustum` owns the scratch memory of its kernel (about 29 KB), so creating one for each pass of a frame allocated 124 KB per frame; one stage for all the passes brought it to 8 KB, the documented use ("give each thread its own stage"). (5) The first run of `--verify` failed on the maps because it ran in `create()`, before the runner has set the state of a frame: with the depth test off nothing is written to a depth buffer; the demo now sets what it needs.

**Does not prove.** A scene of more than boxes (no alpha casters, no skinned or instanced meshes), blending between cascades (the edge of a cascade is visible when tinted), filtering beyond hardware 3 by 3 percentage-closer, a bias that is right for other scenes (the normal offset and the depth bias were chosen for this one), and other GPUs.

### streaming-ring: Streaming through a ring and a pool

**Claim.** Chunks of points stream through a persistently mapped ring into a pool in GPU memory under teleports and a GPU that lags the CPU, and the numbers say what the ring's regions and the pool's allocator cost: one region halves the frame rate of this load (15.4 ms against 8.6 ms), four never waited, a first-fit pool that is 15% larger than its working set ends up 61% fragmented, and every chunk that was read back from the pool equals its content (5,790 chunks).

**Uses.** `PersistentBufferRing` (`beginFrame`, `allocate`, `endFrame`, `stalls`, `highWaterMark`, `used`, `drain`; `GlFences` supplies the fences), `FreeListAllocator` (first fit and best fit, `largestFree`, `freeBytes`, `blockCount`, `allocatedBytes`, `validate`), `SlabAllocator`, `DrawCommandBuffer` (`addArrays`), `glMultiDrawArraysIndirect`, `GpuTimer`.

**Controls.** `F` changes the number of regions of the ring (1 to 4), `P` changes the allocator of the pool (and empties it), `[` and `]` halve and double the GPU load, `T` jumps to a new place now, `B` stops the camera. The display shows the regions with their use, the pool with its reserved share, the numbers of the frame and a map of the neighbourhood (green resident, red wanted and missing, grey resident and no longer wanted).

**Options.** `--world N` (160 cells of 32 m), `--radius N` (14: a neighbourhood of 665 cells), `--pool-mb N` (30), `--pool first-fit|best-fit|slab`, `--region-mb N` (16), `--budget-mb N` (8: the most uploaded in one frame), `--frames-in-flight N` (3), `--particles N` (200,000), `--gpu-load N` (2), `--speed S` (25 cells per second), `--teleport SECONDS` (3), `--verify`.

**How it works.** The camera flies a loop over the world and jumps to a new place every three seconds, which makes the whole neighbourhood missing at once. Each frame the chunks beyond the neighbourhood and a margin of two cells are freed, then the missing cells are walked nearest first: a chunk (400 to 6,000 points of 16 bytes, unevenly distributed so that small chunks dominate: 36 KB on average) is written into the ring's region, copied into the pool at the offset that the allocator gave it with `glCopyNamedBufferSubData` and put in the draw list, until the byte budget of the frame or the region is used up. When the pool has no room the farthest chunk that is outside the wanted disc is freed and the allocation tried again. The 200,000 particles of the frame go into the same region and are drawn straight from the ring. A filler pass whose cost the GPU load sets makes the GPU the slower side.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 x 900, vsync off, no HUD, 900 frames after 100 of warm-up, the defaults above unless a row says otherwise. A frame uploads 3.77 MB: 3.2 MB of particles and 0.57 MB of chunks (15.9 chunks); the high-water mark of the region is 11.04 MB of 16 MB, which is the budget of 8 MB (a teleport frame) plus the particles.

| regions | waits of the ring (of 999 frames) | time waiting, mean per frame | frame | GPU |
|---|---|---|---|---|
| 1 | 998 | 7.65 ms | 15.41 ms | 7.68 ms |
| 2 | 634 | 1.18 ms | 8.90 ms | 7.99 ms |
| 3 | 415 | 0.77 ms | 8.64 ms | 8.05 ms |
| 4 | 0 | 0.04 ms | 8.44 ms | 7.83 ms |

With no filler (`--gpu-load 0`) one region took 11.38 ms against 7.75 ms for three (no waits; the frame is then limited by the CPU, which writes the particles and the chunks), and with a filler of 8 (a GPU time of 29.3 ms) three regions waited 21.5 ms per frame in 985 of 999 frames: when the GPU is the slower side, more regions do not help. A region of 6 MB with a budget of 2 MB held the same frame time (8.64 ms) but 35.6 chunks were missing on average against 19.6, and 67 uploads were deferred against 13.

The pool, with three regions and the filler of 2 (chunks resident are the mean of the run; 665 are wanted, 719 are held with the margin; the working set is about 26 MB):

| pool | resident | missing | fragmentation at the end | largest free block / free bytes | failed attempts |
|---|---|---|---|---|---|
| first fit, 30 MB | 719 | 19.6 | 60.6% | 1.31 / 4.4 MB | 0 |
| best fit, 30 MB | 719 | 19.6 | 37.8% | 2.62 / 4.4 MB | 0 |
| first fit, 26 MB | 693 | 26.0 | 91.8% | 0.04 / 2.3 MB | 13,355 |
| best fit, 26 MB | 710 | 22.1 | 88.0% | 0.07 / 2.0 MB | 4,744 |
| first fit, 24 MB | 656 | 35.6 | 94.5% | 0.05 / 2.1 MB | 27,499 |
| best fit, 24 MB | 671 | 31.1 | 92.5% | 0.05 / 1.6 MB | 22,022 |
| slab, 30 MB (blocks of 96,000 B) | 326 | 346.9 | 0.6% | none free | 342,356 |
| slab, 60 MB | 651 | 30.2 | 1.2% | none free | 22,837 |

(A failed attempt is a chunk that did not fit and is tried again the next frame; the free list's blocks numbered 937 to 1,060 at the end, the slab's 327 and 655.) The render thread allocated 1,226 to 1,273 B per frame in every run (smoke 691 B, budget 16,384 B).

**Verification.** `--verify` reads every resident chunk back from the pool every 120 frames and compares it byte for byte with the content that the plan generates for its cell, checks `FreeListAllocator.validate()` and that the allocator's books equal the sum of the resident chunks: 5,723 chunks in 8 rounds were right with two regions, a best-fit pool of 26 MB (4,744 failed attempts, 15,442 evictions) and waits on 768 frames, and 5,790 chunks in 8 rounds with one region of 10 MB and a budget of 6 MB (high water 9.05 MB, a wait on 998 of 999 frames); none was wrong. `StreamingTest` checks the pools against overlap and the books, the plan and the options without a window.

**Findings.** (1) With one region the CPU and the GPU take turns: 15.4 ms is the CPU's own 7.7 ms (the frame with three regions and no filler) plus a wait of 7.65 ms for the GPU's 7.7 ms. Two regions recover almost all of it (8.9 ms), three or four the rest; beyond what the GPU is busy for, extra regions only add latency. (2) A region has to hold the byte budget plus whatever else the frame writes: the high water of 11.04 MB is 8 MB of chunks plus 3.2 MB of particles, and a region smaller than that makes the streamer defer uploads (the 6 MB region with a 2 MB budget had 35.6 chunks missing on average). (3) `PersistentBufferRing.stalls()` counts the waits but not how long they were; the demo times `beginFrame` itself. (4) A free list fragments quickly at the load that a streamer has: at 15% over the working set first fit left a largest free block of 1.31 MB in 4.4 MB free (60.6%), best fit 2.62 MB (37.8%), and at the working set itself both are above 88% and allocations fail in the thousands, so the demo has to evict chunks that it still wants. (5) A slab never fragments (at most 1.2%) but gives every chunk the block of the largest one: with chunks of 36 KB on average in blocks of 96 KB, 30 MB holds 326 of the 665 chunks that are wanted, and 60 MB is needed for 651. (6) The allocators and the ring needed no change; the first version of the demo had no way to tell the waiting from the working, which is why the wait is timed.

**Does not prove.** The bandwidth of a real transfer (the copy is inside one GPU and 3.6 GB over 15 s is a small rate for it), a separate transfer queue or loader thread (the chunks are generated on the render thread, which is part of the CPU time of a frame), other drivers (this driver let the CPU run about three frames ahead, which is why four regions never waited), or what real asset sizes do to the pool (the sizes here are generated).

### line-lab: Line lab: one network, six ways to draw it

**Claim.** The same 5,000 polylines are drawn by every tier of the line renderer, from an indirect multi-draw on OpenGL 4.5 down to line strips on 3.3, and the numbers say what each costs in calls, bytes and time on one GPU: the draw-index strategy that sits first in the decision table was the slowest on the GPU here, and the vertex-pulling tier of 3.3 the fastest of the thick ones.

**Uses.** `LineBatch` and `LineStyle` (the data), `LineRenderPlan` (`force`, `write`, the shaders, `submission`), `DrawList` and `DrawSubmission` (an indirect multi-draw, `glMultiDrawArrays`, or a loop that moves the attributes by hand), `LineExpander` and `CoverageRaster` (the reference of the probe); in the samples `LineRenderer` (binds each strategy's arrays and issues the draws), `LineTier` (the capabilities of each tier), `PanZoom2d` and `LineGpuCheck.probe`.

**Controls.** `1` to `6` or `N` choose the tier, `A` cycles through them, the left mouse button pans and the wheel zooms about the cursor.

**Options.** `--polylines N` (5000), `--points N` (64), `--tier-frames N` (120: frames per tier in a scripted run and in the cycle), `--tier N` (1), `--seed N`.

**How it works.** The network is procedural (`NetworkScene`): 4% rivers with a width in world units, 10% roads drawn twice (a dark casing, a bright fill), 50% minor roads, 5% dashed railways and the rest thin random walks in eight styles, over 8,000 by 4,500 units. At the start the demo writes the buffers of each tier for the same batch (the CPU time of `write` is the best of four), uploads them, and draws a probe of 1,500 other polylines into a 256 by 256 target to count the pixels where the GPU and the reference disagree. A frame sets the matrix (`relativeViewProjection`, in double precision) and draws the chosen tier; zooming rebuilds nothing. In a scripted run the tiers follow each other every `--tier-frames` measured frames along a fixed pan and zoom path.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, window 1600 x 900, vsync off, 720 frames after 120 of warm-up, 100 frames per tier, defaults above. 315,000 segments, 13 styles, 932 runs of equal style:

| tier | strategy | submission | draw calls | data | styles | write (CPU) | issue (CPU) | GPU | pixels off in the probe |
|---|---|---|---|---|---|---|---|---|---|
| 4.5 draw id | INDIRECT_DRAW_ID | indirect | 1 | 14.42 MB | 58.3 KB | 11.1 ms | 0.028 ms | 2.99 ms | 1 missing, 0 extra |
| 4.3 indirect | INDIRECT_INSTANCE_STYLE | indirect | 1 | 14.42 MB | 0.8 KB | 12.2 ms | 0.058 ms | 2.93 ms | 1 missing, 0 extra |
| 4.2 loop | INSTANCED_LOOP | loop with a base instance | 932 | 14.42 MB | 0.8 KB | 13.8 ms | 0.210 ms | 2.97 ms | 1 missing, 0 extra |
| 3.3 pulling | EXPANDED_MULTIDRAW | glMultiDrawArrays | 1 | 14.42 MB | 0.8 KB | 9.8 ms | 0.044 ms | 2.02 ms | 1 missing, 0 extra |
| 3.3 loop | INSTANCED_LOOP | loop, attributes by hand | 932 | 14.42 MB | 0.8 KB | 10.8 ms | 0.715 ms | 3.08 ms | 1 missing, 0 extra |
| 3.3 hairline | HAIRLINE | glMultiDrawArrays | 1 (5,000 strips) | 4.88 MB | 0 | 9.9 ms | 0.030 ms | 0.09 ms | no triangles |

The wall time of a frame was 2.54 ms, and the render thread allocated 292 B per frame (budget 4,096 B; smoke runs the demo with 400 polylines of 24 points). The write times vary by a few milliseconds from run to run (an earlier run of the same code gave 9.3 to 15.0 ms). The one missing pixel is the same in every tier, so it is an edge pixel that the reference and the rasteriser decide differently, not a difference between tiers.

**Verification.** The probe is a check that the GLSL of every tier draws what the reference draws on this driver, with a view of 256 by 256 pixels; `LineGpuCheck` (`:vmath-samples:lineCheck`) does it for four scenes and every GLSL version from 3.30 (177 cases, `docs/LINES.md`).

**Findings.** (1) Every thick tier moves the same 14.4 MB (48 bytes a segment, 54 vertices of work for each); what changes is how the style is found and how the draws are issued. (2) The loop costs the CPU 0.21 ms (with a base instance) to 0.72 ms (without one: the attributes are bound again for each of the 932 draws) against 0.03 to 0.06 ms for a multi-draw, and the same GPU time; a frame of 2.5 ms is not limited by that here, a scene with ten times the draws would be. (3) Reading the style by the draw index (INDIRECT_DRAW_ID) cost 1 ms of GPU time more than reading it from the instance, and vertex pulling from a texture buffer cost 1 ms less than instanced attributes; both are one GPU's answer and the decision table (best first by what the features allow) does not follow them. (4) Hairlines are 30 times cheaper and one pixel wide.

**Does not prove.** Anything about another vendor, a driver of the older versions (the older tiers run in a 4.5 context with the text and the calls of their version), a scene that is not a synthetic network, or the cost of the CPU side of a real engine around it. The GPU time is the time of the lines alone, at 1600 by 900 with the scripted view, which covers part of the world.

### line-styles: Line styles: widths, caps, joins and dashes

**Claim.** Widths from a pixel to thirty, nine cap and join combinations, miter limits, dashes and widths in world units are drawn by the same shaders in every tier, zoom changes only a matrix, and a comparison with the reference expansion finds no pixel of difference but edge pixels.

**Uses.** `LineStyle` (`pixels`, `world`, caps, joins, `withMiterLimit`, `withDash`, `dashedInPixels`), `LineBatch`, `LineRenderPlan.write` (the style table is written again for dashes that keep their length in pixels), `LineExpander` and `CoverageRaster`; in the samples `LineRenderer`, `LineTier`, `PanZoom2d` and `LineGpuCheck.probe`.

**Controls.** `1` to `6` or `N` choose the tier, `Z` makes the dashes keep their length in pixels, `C` compares the middle 512 by 512 pixels of the view with the reference, the left mouse button pans and the wheel zooms.

**Options.** `--tier-frames N` (90: frames per tier in a scripted run), `--tier N` (1), `--dashes N` (0: in a scripted run alternate dashes in world units and in pixels, 1 pixels, 2 world units).

**How it works.** `StyleScene` fills a `LineSet` per tier with the panel (one unit is one pixel at scale 1): eight widths, three caps by three joins on a zigzag, seven angles of a sharp turn with miter limit 8 and 1.5, four dash patterns and a dashed closed circle, and two fans of lines with the same width, 10, in world units on the left and in pixels on the right. Dashes are lengths in world units, so they grow with the zoom; with `Z` the five dashed polylines get a new style with `dashedInPixels(pixelSize)` through `LineSet.setStyle` whenever the zoom has changed by 0.2%, and `update` writes only those polylines again, which takes 0.07 ms. Every change of tier, and `C`, draws a 512 by 512 square of the current view offscreen with that tier's shaders and counts the pixels that differ from the reference.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 x 900, vsync off, 600 frames after 60 of warm-up, 50 frames per tier, the pixel dashes on for every second block of 100 frames. The probe covered about 47,000 pixels, and at every comparison (at the start and at each change of tier except into the hairline tier, which has no triangles) 0 fully covered pixels were missing and 0 were extra. Writing the changed polylines again took 0.072 ms (240 times); the GPU time of the lines was 0.027 ms and the frame 1.26 ms. The render thread allocated 32 B per frame with the dashes in world units and 10,271 B with the dashes in pixels (a new style and a new draw list for each change of zoom; budget 16,384 B; smoke runs `--dashes 1` without tier changes). Each comparison allocates a few megabytes for the 512 by 512 raster of the reference, which is why the run above, with 12 of them, allocated 92,855 B per frame on average.

**Does not prove.** Anti-aliasing (the geometry has none: an edge is as sharp as the pixels, and a coverage fringe in the shader is not built), the look at a distance (an orthographic view only), or any driver but the one of the machine. The comparison counts edge pixels leniently (a pixel the reference covers less than 5% or more than 95% is judged), as `LineGpuCheck` does.

### line-stream: Line stream: edits, trails and dirty ranges

**Claim.** In one `LineSet`, 3,000 polylines are edited in place, replaced and moved every frame and 100 tracks leave fading trails, and the update uploads 351 KB a frame as dirty ranges where writing and uploading every record would take 1.2 MB, with handles that stay valid and a compaction that lowers the number of draws.

**Uses.** `LineSet` (`add`, `set`, `remove`, `contains`, `update`, `dirtyRangeCount`, `dirtyOffset`, `dirtyLength`, `invalidate`, `compact`, `largestFreeUnits`; the slots come from `FreeListAllocator`), `TrailBuffer` (`push`, `updateLines`), `LineRenderPlan` and `DrawList`; in the samples `LineRenderer` (the CPU mirrors, `uploadData` for a range), `LineTier` and `PanZoom2d`.

**Controls.** `U` switches between uploading the dirty ranges and rewriting and uploading every record of every frame, `K` compacts now, `1` to `6` or `N` choose the tier (the set is built again), the left mouse button pans and the wheel zooms.

**Options.** `--polylines N` (3000), `--edit-percent N` (5), `--churn-percent N` (1), `--tracks N` (100), `--trail-points N` (64), `--mode-frames N` (150), `--compact-every N` (200, scripted runs only), `--tier N` (2), `--seed N`.

**How it works.** Each frame 150 polylines (5%) are rewritten with the same number of points, which `LineSet.set` does in place, 30 (1%) are removed and replaced by new ones of another length (4 to 11 points), which frees and takes slots of the allocator, and each of the 100 tracks moves, pushes its position into the `TrailBuffer` and has its eight slices of age written into the set again. `update` writes the records that changed into the mirror of the renderer and the demo uploads the dirty ranges; the draws and the style table are rebuilt (they are a few kilobytes). In the other mode `invalidate` makes every record dirty first, so the update rewrites all of them and the ranges are all the bytes in use. A scripted run alternates the two modes every `--mode-frames` frames and compacts every 200 frames; the demo checks that a removed handle is never valid again and that every handle of a new polyline is.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, window 1600 x 900, vsync off, 600 frames after 120 of warm-up, tier 2 (INDIRECT_INSTANCE_STYLE, OpenGL 4.3), 150 frames per mode, 25,679 records (segments) in use of a capacity of 60,396:

| mode | uploaded per frame | update and upload on the CPU | ranges |
|---|---|---|---|
| dirty ranges | 351.4 KB | 0.433 ms | 221 |
| everything | 1,201.5 KB | 1.445 ms | 1 or a few |

The dirty ranges are 29% of the bytes and 30% of the CPU time; most of what remains are the trails, which change completely every frame (100 tracks times 63 records of 48 bytes, 302 KB), where the edited polylines are 150 times 7 records (50 KB) and the churn a few KB. The set had 3,442 draws on average (the styles of the 3,000 polylines are dealt out at random, so most are a run of one) and a fragmentation of 1.5%; the three compactions took 3,792, 3,773 and 3,778 draws to 2,310, 2,324 and 2,375 and rewrote 1.2 MB each (every polyline moves). 108,000 edits, 21,600 removals and additions and 43,200 handle checks gave 0 failures. The wall time of a frame was 1.86 ms, the GPU time of the lines 0.29 ms, and the render thread allocated 4,165 B per frame (budget 16,384 B; smoke runs 400 polylines and 12 tracks).

**Does not prove.** The cost of a real transfer (the upload here is `glBufferSubData` into a buffer that the GPU has finished with, not a persistently mapped ring with fences), a scene whose edits are not spread over the whole buffer, or the other tiers (the keys run them, the numbers above are tier 2). The fade of a trail is in steps and not a gradient along each segment.

### map-view: Moving map: own position, route, rings and tiles

**Claim.** A 2D map follows an own position along a route of great-circle legs, north up, course up or heading up, with range rings, a corridor, a trail and generated tiles, and the numbers of the view are read off in navigation units, with the lines, the areas and the symbol on the library's renderers in their best tier on the context.

**Uses.** `MapView2d` (centre, scale, orientation, offset of the centre, `toScreen`, `toGeographic`, `scaleBar`, `viewProjection`), `MapShapes` (`rangeRings`, `route`, `corridorAreas`), `FlatTileSelector` and `TileGrid`, `LineBatch` and `LineRenderPlan` through the line renderer, `AreaBatch` and `AreaRenderPlan`, `SymbolBatch` and `SymbolRenderPlan`, `Geodesy`, `GeoFormat` and `Units`.

**Controls.** `O` cycles north up, course up and heading up, `C` centres the own position or puts it low, `R` shows the rings, `Space` pauses, the wheel zooms, the mouse position is read out as latitude, longitude and bearing and range from the own position.

**Options.** `--speed N` (450 m/s), `--scale N` (150 m of ground per pixel), `--tier N` (the line tier, 1).

**How it works.** The route (`MapKit.Route`) joins five waypoints by geodesics and is followed by distance with `Geodesy.direct`. Each frame the view is made again (it is immutable) at the own position; the selector chooses the tiles, at most three missing ones are generated per frame from the planet of the globe demo (a 128-pixel image each, nearest first) and a missing tile is covered by the nearest cached ancestor. The rings, the route and the corridor are sampled for the view by `MapShapes` into a `LineBatch` and an `AreaBatch`, written relative to the centre, and drawn with the matrix of `MapView2d.viewProjection`. The own position is a symbol with `ROTATE_WITH_MAP`, so it points along its heading on a turned map.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, window 1600 x 900, vsync off, 720 frames after 60 of warm-up, defaults above:

| what | value |
|---|---|
| sampling the rings and the route and writing the lines | 2.98 ms per frame (the corridor is triangulated only when the scale has changed by 5 percent) |
| wall time of a frame | 4.68 ms; the tiles (128 pixels each) are generated on three worker threads and become textures on the render thread, six a frame at most (282 generated and cached). Generating them on the render thread, as the first version did, made a frame 17 ms and a zoom drop to 30 frames a second |
| render-thread allocation | 245,769 B per frame (budget 1,048,576 B; smoke runs the demo at 600 m/s) |
| line draw calls | 1 (tier 4.5 draw id) |

**Verification.** The shaders it uses are those that `MapGpuCheck` compares with their CPU models on this driver (`docs/MAPS.md`); the tile images are not checked against anything, they are a procedural planet.

**Findings.** (1) The corridor does not depend on the own position, only on the scale, so it is built again only when the scale changes; the rings and the route follow the own position and are sampled every frame. (2) The tiles that are not yet generated are covered by their parent, so the map never shows a hole, only a blur. (3) Course up and heading up differ only by the 9 degree wobble that the demo adds to the heading; the symbol turns by it while the map turns by the course.

**Does not prove.** Any real tile source (fetching, decoding and caching are not here), a real route or data, a window other than this one, or another driver.

### map-projections: Map projections: the same data in four of them

**Claim.** Coasts, a geodesic and a rhumb line between two cities, circles of equal ground radius and distortion ellipses are drawn in Web Mercator, azimuthal equidistant about a city, polar stereographic and UTM, switched live, and the round-trip error and the distance error of each are printed.

**Uses.** `MapProjection` and its four implementations (`WebMercatorProjection`, `AzimuthalEquidistant`, `PolarStereographic`, `TransverseMercator` through `Utm`), `MapShapes` (`circle`, `geodesicLeg`, `rhumbLeg`), `Geodesy`, `MapView2d`, `LineBatch`.

**Controls.** `1` to `4` or `A` (cycle) choose the projection, `T` shows the distortion ellipses, `C` the coasts, the left mouse button pans and the wheel zooms.

**Options.** `--projection-frames N` (120 frames per projection in a scripted run and in the cycle), `--projection N` (1).

**How it works.** The coasts are the zero level of the planet's height found by marching squares on a 1 degree grid (8,072 segments). For each projection the segments whose ends are inside the part of the world that the projection is used for are projected; the 450 km circles on the ground are sampled by `MapShapes.circle` and become the distortion ellipses (Tissot's indicatrices). The round-trip error is the worst geodesic distance between a point and the inverse of its projection over 3,000 random points of the domain; the distance error compares the straight line between the cities on the plane (in projected metres) with the geodesic length.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, window 1600 x 900, vsync off, 720 frames after 60 of warm-up:

| projection | round trip, worst of 3,000 points | straight line A to B against the geodesic (6,270 km) | scale at A |
|---|---|---|---|
| Web Mercator | 3.5e-09 m | +52.30 percent | 1.62296 |
| azimuthal equidistant about A | 1.6e-08 m | 0.00 percent (true range from the centre, by construction) | 1.00000 |
| polar stereographic (north) | 3.2e-09 m | +11.37 percent | 1.11168 |
| UTM zone of A | 4.9e-07 m | city B is outside the domain used | 0.99960 |

The wall time of a frame was 2.2 ms and the render thread allocated 32,615 B per frame (budget 262,144 B). The coasts are built when the projection changes (8.6 ms for UTM; 437 ms, read off the HUD in an earlier run, for the azimuthal equidistant, which takes a geodesic inverse for each end of each segment to test the domain), and the circles and lines once the scale has stopped changing for ten frames (or at once when the view has come three times closer than they were sampled for): 21 to 42 ms for UTM and Web Mercator, 117 ms for the azimuthal equidistant, whose every projected point is a geodesic inverse. A pan or a zoom in progress builds nothing, which is why they stay at a few hundred frames a second; in an earlier version every wheel notch rebuilt them and the azimuthal equidistant dropped to a few frames a second. The coasts of the azimuthal equidistant take 174 ms (432 ms before the domain test used the spherical distance).

**Verification.** The round-trip and distance figures are computed by the demo from the library, with `Geodesy` as the reference; the projections themselves are tested against published examples (`docs/MAPS.md`).

**Findings.** (1) The only projection in which the straight line to B is the true distance is the one centred on A, as designed; Web Mercator overstates it by half at 52 degrees north. (2) The distortion ellipses show what each projection does: circles that grow toward the pole (Mercator, conformal), ellipses that stretch with distance from the centre (azimuthal equidistant), circles again but larger away from the pole (stereographic), and almost none inside the zone (UTM). (3) UTM series are used only within 55 degrees of the central meridian here, where the code is checked; beyond that the demo does not draw.

**Does not prove.** That a projection is right outside the domain used here, or the cost of drawing a real coastline data set.

### map-symbols: Map symbols: ten thousand movers, trails and labels

**Claim.** Ten thousand moving symbols with headings and trails, priority-declutter labels that do not flicker, a pointer that picks the nearest symbol, and symbols that stay upright on a rotating map are drawn in each of the three tiers of the symbol renderer, with the CPU time to write the records and the GPU time of each.

**Uses.** `SymbolBatch`, `SymbolAtlas`, `SymbolStrategy` and `SymbolRenderPlan` (all three strategies on a 3.3 context), `Declutter`, `TrailBuffer` and `LineSet`, `MapView2d` (`projectedToScreen`, `screenToProjected`, a rotation of the map), `GeoFormat` and `Units` for the read-out.

**Controls.** `1` to `3` or `N` choose the tier, `A` cycles them, `R` stops or starts the rotation of the map, `L` shows the labels, the left mouse button pans, the wheel zooms, the pointer picks.

**Options.** `--symbols N` (10,000), `--trails N` (300), `--cities N` (1,500), `--tier-frames N` (120), `--label-share N` (30 percent of the symbols have a label), `--seed N`.

**How it works.** The movers fly in a box 5,000 km wide and bounce off its edges (the time is scaled by 250 so that they are seen to move); the cities are fixed with flags 0 and stay upright, the movers have `ROTATE_WITH_MAP`. Every frame the positions are set in the `SymbolBatch`, `SymbolRenderPlan.write` writes the records for the tier and they are uploaded. The labels are boxes of six characters placed by `Declutter` with nine candidates each (in place and eight around it) and a stickiness of 40; only the 30 percent of the symbols with the highest priority are candidates, because placing all of them costs three times as much. The trails are a `TrailBuffer` feeding one `LineSet` (each track is updated every third frame). The pointer is converted with `screenToProjected` and the nearest mover within 24 pixels is picked and recoloured.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, window 1600 x 900, vsync off, 720 frames after 60 of warm-up, defaults above (11,500 symbols, 300 trails):

| tier | strategy | write (CPU) | GPU | data | draw calls |
|---|---|---|---|---|---|
| 1 | INSTANCED | 0.147 ms | 0.284 ms | 0.53 MB | 1 |
| 2 | TEXTURE_FETCH | 0.163 ms | 0.306 ms | 0.53 MB | 1 |
| 3 | EXPANDED | 1.104 ms | 0.504 ms | 3.16 MB | 1 |

Declutter solved 2,030 candidates in 3.9 ms and placed about 300, and does it every third frame (the labels follow their symbols in between, and stickiness keeps them from jumping); the trails cost 0.37 ms a frame; the wall time of a frame was 3.13 ms and the render thread allocated 48,189 B per frame (budget 1,048,576 B; smoke runs 1,500 symbols). The GPU times vary from run to run (an earlier run of the same code gave 0.66, 0.88 and 0.86 ms for the three tiers).

**Verification.** The three shaders are compared with `SymbolShaderModel` pixel by pixel on this driver at every GLSL version by `MapGpuCheck`; the test that the labels do not flicker is `DeclutterTest` (a half-pixel pan changes under 1 percent of the decisions).

**Findings.** (1) Once the records are written the three tiers cost the GPU about the same; what the floor costs is the CPU and the memory, six times the data (3.16 MB against 0.53 MB). (2) Declutter is the expensive part of the frame, not the drawing: 4 ms for two thousand candidates with nine candidate places each, more than the whole rest of the frame; a real display would limit the labels by priority as this one does. (3) Ten thousand symbols in a window of 1600 by 900 are a dense field, which is the point of the declutter and of the pick.

**Does not prove.** Anything about another vendor or a driver of the older versions (the tiers run in a 4.5 context with the text of 3.30), real tracks, or label text drawn with a real font: the labels are drawn by the heads-up display's font.

### map-terrain: Map terrain: height relative to an altitude, and a viewshed

**Claim.** Hillshaded elevation coloured by height relative to a reference altitude that a key or the pointer changes, on the CPU or the GPU from the same heights, and a viewshed from the moving own position computed off the render thread and shown as a mask, with the cost per cell.

**Uses.** `TerrainGrid`, `Hillshade` (inside the shading), `ColorRamp` (`relativeSteps`, `bake`), `TerrainShading` (the CPU path), `TerrainShader` through the GPU renderer, `Viewshed`, `MapView2d`, `Units` and `GeoFormat`.

**Controls.** `Up` and `Down` change the reference altitude, `H` sets it to 150 m above the terrain under the pointer, `T` colours on the CPU or the GPU, `M` shows the mask, the left mouse button pans and the wheel zooms.

**Options.** `--grid N` (385 nodes a side), `--range N` (40,000 m), `--size N` (240 km).

**How it works.** The demo looks for the part of the planet where the land is most rugged and dry and samples a 385 by 385 grid of the planet's heights in Web Mercator there (cells of 625 m on the ground). The ramp has steps at 600 m and 300 m below the reference, at the reference and 300 m above it, and is baked into 512 texels; whenever the reference changes the tile is coloured again, by `TerrainShading.render` into a texture, or by the GLSL of `TerrainShader` into a texture of the grid's size, and drawn as a quad. The own position circles over the tile and every time it has moved two cells a viewshed (eye 30 m above the ground, curvature and refraction included) is computed on one worker thread; when it is done its result becomes a mask that darkens what is not seen.

**Measured.** JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, window 1600 x 900, vsync off, 720 frames after 60 of warm-up, defaults above:

| what | value |
|---|---|
| colouring the whole tile on the CPU, with the upload | 8.10 ms |
| colouring it on the GPU | 0.060 ms (a timer query, few samples) |
| the viewshed, 88 runs, warmed up | 1.88 ms for about 12,900 cells in range, 146 ns per cell, 4 percent seen at the end |
| wall time of a frame | 5.77 ms; the render thread allocated 22,977 B per frame (budget 262,144 B; smoke runs a 129 node grid) |

In a fresh JVM the first call of `Viewshed.compute` costs far more per cell (3.3 microseconds a cell in the unit test, which runs cold): the figure above is after warm-up.

**Verification.** The GPU shading is compared with the CPU shading pixel by pixel (within 3 of 255, a few in a thousand more) at every GLSL version by `MapGpuCheck`; the viewshed is compared with the exact test of `Viewshed.isVisible` in `TerrainTest` (98 percent agree).

**Findings.** (1) Moving the colouring to the GPU is a factor of 130 on this tile, and it matters because the ramp changes every frame as the reference does. (2) The viewshed is cheap enough to follow a moving position, and on a worker thread it never stalls a frame; the mask is a few frames old. (3) A step ramp relative to an altitude makes the areas that matter (a terrain at the aircraft's level) a band, which the hillshade does not obscure at a strength of 0.65.

**Does not prove.** That the colour scheme means anything for a real display (the colours are the demo's), accuracy of the viewshed beyond the 98 percent agreement with the exact test, or anything about real elevation data: the planet is synthetic.
