# vmath demos

A roadmap of demos that show what the library is good for, and the structure they are built on. It follows the conventions of [ROADMAP.md](ROADMAP.md): priorities **P0** blocks the rest,
**P1** shows a core strength, **P2** completeness, **P3** nice to have; sizes S ≈ hours, M ≈ days, L ≈ 1-2 weeks; `→` marks dependencies; IDs are stable.

The demos live in the module `vmath-samples` (built only with `-Psamples`, or by IntelliJ; [SAMPLES.md](SAMPLES.md), [IDE.md](IDE.md)). They need OpenGL 4.5 and a display; they are not part of the
library build.

---

## 1. Where we are

The framework (phase F) and seven demos are done: `city`, `culling-lab`, `interior-portals`, `occlusion`, `dq-vs-lbs`, `rigid-pile` and `sdf-sculpt`. Everything else in section 4 is the backlog. A card has numbers only for a demo that has run.

| | |
|---|---|
| Demos | 7 of 25 (`city`, `culling-lab`, `interior-portals`, `occlusion`, `dq-vs-lbs`, `rigid-pile`, `sdf-sculpt`) |
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
| `GpuTimer` | `GL_TIME_ELAPSED` read a few frames late | |
| `BoxRenderer` | the unit cube, its program, and the instanced draw of an `InstanceStream` | `Primitives.box`, `MeshOptimizer`, `VertexLayout` |
| `Scenes` | the shared procedural scenes: `city` (noise heights) and `blocks` (buildings with props in the streets) | `BoundsArray`, `Noise`, `Rng` |
| `OrbitCamera` | orbit around a point with the left mouse button, zoom with the wheel, a scripted turn | `Cameraf` |
| `FlyCamera` | free flight, a scripted circle, and `place` for a demo's own scripted path | `Cameraf`, `DepthRange` |
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
- [ ] **F11 (P2, M)** Mouse-driven sliders and toggles in the HUD (picking on the bars) for the demos that want a parameter slider (R1). A2 did not need them: it turns its parameter with the arrow keys and shows it with `Hud.bar`. → F4
- [ ] **F12 (P2, M)** Compute-shader and texture helpers in `Gl` (compute program, image textures, a framebuffer object for inset views). → needed by C6, R3 (C4 needed only the texture inset, `DepthInset`)

### Phase C: culling and scale

- [x] **C1 (P1, S)** `city`: a million boxes, frustum culling, instance ring, indirect draw.
- [x] **C2 (P1, M)** `culling-lab`: the same scene with a key to switch between the brute-force scalar kernel, the SIMD kernel, the parallel kernel, `StaticBvh`, `DynamicAabbTree`, `LooseOctree` and `UniformGrid`; the time and the visible count of each live, the nodes of the BVH drawn with the debug renderer, a sweep of how many boxes are visible against how long each structure takes. Shows: three batch kernels and four spatial structures behind one interface, and where each wins. → F10
      *Done: seven methods (the first plan said eight), each timed per frame and each checked against the scalar kernel with `--verify` (a miss fails the run; a box within a centimetre of a plane is counted as marginal), the cost of keeping each structure current while a fiftieth of the boxes move (`--animate`), the build times, the BVH levels drawn, a table of every method on the current view (`T`). Card and numbers below.*
- [x] **C3 (P1, M)** `interior-portals`: a generated building of rooms and doors; `PortalGraph`, `PortalCuller` and `SectorVisibility`; doors open and close with a key; visible sectors and the portal rectangles drawn; the instance count against frustum culling alone. Shows: indoor visibility without a hand-made PVS.
      *Done: a maze of 400 rooms and 508 doors (a random spanning tree plus a quarter of the other walls) with 150 pieces of furniture per room, the portals found by `autoPortals` (two per door), a slab that fills a shut door. `SectorVisibility` and `PvsMatrix` are not used: for a graph without a baked PVS the connectivity matrix lets everything connected see everything connected, so it filters nothing here.*
- [x] **C4 (P1, M)** `occlusion`: a dense city with tall occluders; `DepthBuffer`, `OcclusionStage`; the depth buffer and its Hi-Z levels shown as an inset; a ray-test check running beside the culling that proves nothing visible is removed (conservative). Shows: software occlusion that never culls what is seen.
      *Done: 10,000 buildings and 400,000 props, the nearest 143 buildings rasterised, a ray check of up to 200 removed boxes per frame at nine points each, a multi-threaded test (`--threads`). It also found that the test is too slow to pay for itself on cheap boxes: TD-29 in `technical-debt.md`.*
- [ ] **C5 (P2, L)** `cluster-lod`: a dense generated mesh with `Meshlets`, `ClusterHierarchy`, `ConeCull` and `MeshSimplifier`; the cut of the hierarchy chosen by screen-space error; clusters coloured; triangle count against pixel error. Shows: Nanite-style continuous LOD with the library's data structures and a CPU cut. → F12
- [ ] **C6 (P2, L)** `gpu-culling`: the compute culling shader of `vmath.gpucull` with a `HiZPyramid`, indirect commands built on the GPU, the result compared with `GpuCullReference` every frame. Shows: GPU-driven culling, and closes the shader-text part of TD-01. → F12
- [ ] **C7 (P2, L)** `clustered-lights`: ten thousand point lights assigned with `ClusterGrid` and `ClusterLights`, shaded forward-plus, the grid drawn on demand. Shows: the light-culling math, and checks `glslLookup` against a driver. → F12

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
- [ ] **P4 (P2, M)** `terrain`: fractal terrain (`Noise.fbm2`) with `MeshLod` chains and `LodSelector`; a camera on a `Curves` spline rail with arc-length speed. Shows: procedural geometry and LOD selection.
- [ ] **P5 (P3, M)** `convex-lab`: two convex shapes dragged with the mouse; `Gjk` distance, `Epa` penetration depth, closest points and the contact normal drawn. Shows: the narrow phase.

### Phase R: rendering math and large worlds

- [ ] **R1 (P1, M)** `sky-sun`: `PreethamSky` and `SolarPosition` with a time-of-day slider, `PhysicalCamera` exposure and `ToneMap` operators switched live. Shows: the lighting and camera math. → F11
- [ ] **R2 (P1, L)** `globe`: the whole Earth as a WGS-84 ellipsoid (`Wgs84`, `Geodetic`), a flight from orbit to street level with a `FloatingOrigin`; a key turns camera-relative rendering off and shows the jitter that it removes (`Rebase`, `FrameTransformf`). Shows: large worlds in single-precision rendering. Measures the vertex error with and without rebasing.
- [ ] **R3 (P2, L)** `cascaded-shadows`: `Cascades` fit and `CascadeCasters` culling per slice, four shadow maps, the splits and the casters drawn. Shows: shadow-cascade math and caster culling. → F12
- [ ] **R4 (P2, M)** `ibl-spheres`: a grid of material spheres lit by `SphericalHarmonics` irradiance and `Ibl` GGX prefiltering of the procedural sky. Shows: image-based lighting. → F12, R1
- [ ] **R5 (P3, M)** `mirrors-stereo`: a planar reflection and a portal view (`PlanarViews`) and a side-by-side stereo pair (`Stereo`). Shows: the view-matrix constructions. → F12
- [ ] **R6 (P3, S)** `color-lab`: colour spaces and tone maps side by side (`ColorSpaces`, `Srgb`, `ToneMap`). Shows: the colour math.

### Phase T: tour and capture

- [ ] **T1 (P2, M)** `streaming-ring`: `PersistentBufferRing` and the allocators under a synthetic streaming load, region occupancy and stalls shown. Shows: the memory layer.
- [ ] **T2 (P2, M)** `tour`: runs a chosen sequence of demos on their scripted paths and writes the screenshots and the combined markdown report (the material for a README gallery and release notes). Mostly `--sequence` with `--screenshot` and `--report`, which exist; this is the script and the page.
- [ ] **T3 (P3, S)** `valhalla-allocation`: the HUD allocation counter of a demo on the plain build against the `-Pvalhalla` build (needs JDK 28).

### Order

1. Done: F1-F10, F13, F14, C1-C4, A2, P1, P2.
2. The last of the five that show the widest range: **R1** `sky-sun` (wants sliders, F11).
3. **R2** `globe` and **A1** `crowd`: the two that look most like a product.
4. The GPU demos **C6**, **C7**, **C5**, **R3** (with F12): they also close the open validation items of TD-01 (shader text and layouts against a driver).
5. The rest by interest.

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
| 250 | 426 | 803 | 0.37 ms | 5.5 ms | 1.9 ms | 7.8 ms | 31 us | 2.2 MB |
| 1 000 | 3 609 | 4 179 | 2.8 ms | 41.3 ms | 10.1 ms | 54.3 ms | 54 us | 8.9 MB |
| 2 000 | 10 203 | 9 508 | 10.4 ms | 105.8 ms | 27.4 ms | 143.8 ms | 72 us | 18.1 MB |
| 3 000 | 17 280 | 14 978 | 18.9 ms | 163.2 ms | 46.5 ms | 228.9 ms | 76 us | 27.0 MB |

No body left the pit at any check, and the kinetic energy at the end was 7.3, 16.3, 42.7 and 111.9 J (the pile never comes fully to rest: there is no sleeping and no warm starting). The smoke configuration (150 bodies) allocates 1.4 MB per frame against a budget of 4 MB.
The narrow phase is 71% of the step at 1,000 bodies: about 11 us per box pair, and the polytope that each box needs per step is where the allocation comes from (TD-30). `PileSimulationTest` runs 120 bodies for 1,200 steps without a window and checks the same properties.

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

**Findings.** (1) TD-31: 42 of the 43 checks found a few edges (at most 128 of about 54,000) that more than two triangles share; the starting shape has none, and one added sphere that is wider than a hole in the shape gives eight. (2) A torus that touched the body along a thin lens did the same before it was moved away from it.

**Does not prove.** A whole-grid re-mesh of 64³ to 128³: a tool with a larger field would mesh only the chunks that the brush touched, which `SurfaceNets` does not offer (it meshes a box). The field is a grid of samples, so the brush is as sharp as a cell; the library's `Sdf` composition is used only for the stamp.
