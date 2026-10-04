# vmath demos

A roadmap of demos that show what the library is good for, and the structure they are built on. It follows the conventions of [ROADMAP.md](ROADMAP.md): priorities **P0** blocks the rest,
**P1** shows a core strength, **P2** completeness, **P3** nice to have; sizes S ≈ hours, M ≈ days, L ≈ 1-2 weeks; `→` marks dependencies; IDs are stable.

The demos live in the module `vmath-samples` (built only with `-Psamples`, or by IntelliJ; [SAMPLES.md](SAMPLES.md), [IDE.md](IDE.md)). They need OpenGL 4.5 and a display; they are not part of the
library build.

---

## 1. Where we are

The framework (phase F) and the first demo, `city`, are done. Everything else in section 4 is the backlog. The `city` card below is the only one with numbers, because it is the only demo that has run.

| | |
|---|---|
| Demos | 1 of 25 (`city`) |
| Framework | launcher with a menu, runner, HUD, camera, instance stream, debug lines, statistics and report, screenshot, smoke check |
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
| `Gl`, `GpuMesh` | program build with numbered errors, matrix uniforms, `glGetError` checks; a mesh exported with its vertex layout | `MeshExport`, `VertexLayout`, `VertexBufferLayout.glFormats()` and `glslInputs()` |
| `InstanceStream` | persistently mapped instance buffer in regions with fences, the indirect command | `PersistentBufferRing`, `InstanceWriter`, `DrawCommandBuffer` |
| `GpuTimer` | `GL_TIME_ELAPSED` read a few frames late | |
| `FlyCamera` | free flight and a scripted circle | `Cameraf`, `DepthRange` |
| `Hud`, `FontAtlas` | text and backdrops in window pixels, one draw call; the font is baked with Java 2D | |
| `DebugRenderer` | draws a `DebugLines` buffer (boxes, spheres, frusta, skeletons, ...) | `DebugLines` |
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

**Smoke.** `smoke` runs each demo with the arguments of its `DemoInfo.smokeArgs` for 60 warm-up and 30 measured frames with `glGetError` checks on, and fails on a GL error, on allocation above the
budget, or on a last frame with fewer than 16 distinct colours in a regular sample. The warm-up is long on purpose: the Vector API allocates until its code is compiled, so a short warm-up
measures the interpreter.

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
- [ ] **F10 (P1, S)** `Scenes`: reusable generators (city, scatter, terrain grid) for the second demo that needs them. Not extracted before a second user exists.
- [ ] **F11 (P2, M)** A UI for sliders and toggles in the HUD (mouse picking on the text backdrops) for the demos that want a parameter slider (A2, R1). → F4
- [ ] **F12 (P2, M)** Compute-shader and texture helpers in `Gl` (compute program, image textures, a framebuffer object for inset views). → needed by C4, C6, R3

### Phase C: culling and scale

- [x] **C1 (P1, S)** `city`: a million boxes, frustum culling, instance ring, indirect draw.
- [ ] **C2 (P1, M)** `culling-lab`: the same scene with a key to switch between the brute-force scalar kernel, the SIMD kernel, the parallel kernel, `StaticBvh`, `DynamicAabbTree`, `LooseOctree` and `UniformGrid`; the time and the visible count of each live, the nodes of the BVH drawn with the debug renderer, a sweep of how many boxes are visible against how long each structure takes. Shows: five spatial structures behind one pipeline, and where each wins. → F10
- [ ] **C3 (P1, M)** `interior-portals`: a generated building of rooms and doors; `PortalGraph`, `PortalCuller` and `SectorVisibility`; doors open and close with a key; visible sectors and the portal rectangles drawn; the instance count against frustum culling alone. Shows: indoor visibility without a hand-made PVS.
- [ ] **C4 (P1, M)** `occlusion`: a dense city with tall occluders; `DepthBuffer`, `OcclusionStage`; the depth buffer and its Hi-Z levels shown as an inset; a ray-test check running beside the culling that proves nothing visible is removed (conservative). Shows: software occlusion that never culls what is seen. → F12
- [ ] **C5 (P2, L)** `cluster-lod`: a dense generated mesh with `Meshlets`, `ClusterHierarchy`, `ConeCull` and `MeshSimplifier`; the cut of the hierarchy chosen by screen-space error; clusters coloured; triangle count against pixel error. Shows: Nanite-style continuous LOD with the library's data structures and a CPU cut. → F12
- [ ] **C6 (P2, L)** `gpu-culling`: the compute culling shader of `vmath.gpucull` with a `HiZPyramid`, indirect commands built on the GPU, the result compared with `GpuCullReference` every frame. Shows: GPU-driven culling, and closes the shader-text part of TD-01. → F12
- [ ] **C7 (P2, L)** `clustered-lights`: ten thousand point lights assigned with `ClusterGrid` and `ClusterLights`, shaded forward-plus, the grid drawn on demand. Shows: the light-culling math, and checks `glslLookup` against a driver. → F12

### Phase A: animation and characters

- [ ] **A1 (P1, L)** `crowd`: thousands of skinned characters from the generated glTF assets (or procedural tubes with a skeleton); `Gltf.load`, `ClipSampler`, `Pose` blending, joint matrices in a buffer, LOD by distance; microseconds per character per frame. Shows: animation at crowd scale with no per-frame allocation. → F10
- [ ] **A2 (P1, M)** `dq-vs-lbs`: an arm twisted by a slider, linear blend skinning on the left and dual-quaternion skinning on the right (both in GPU shaders), the candy-wrapper collapse visible; the CPU `Skinning` as the oracle for both. Shows: `DualQuatf`, `Skinning.skinPositionsDualQuat`. → F11
- [ ] **A3 (P2, M)** `ik-playground`: two-bone leg IK on uneven ground, a look-at head, a FABRIK tentacle that follows the mouse; the remaining distance shown. Shows: `IkSolver`.
- [ ] **A4 (P3, M)** `morph-faces`: `MorphTargets` with weights from sliders; the dense and the sparse form side by side with their byte sizes. Shows: sparse blend shapes. → F11

### Phase P: physics and geometry

- [ ] **P1 (P1, L)** `rigid-pile`: thousands of boxes and spheres dropped into a pit; `DynamicAabbTree` as the broadphase, `ManifoldBuilder`, `ContactSolver`, `RigidBody`; contact points drawn; step time per body. Shows: the physics building blocks, honestly a toy engine, not a competitor to one.
- [ ] **P2 (P1, L)** `sdf-sculpt`: signed distance fields with smooth CSG, re-meshed with `SurfaceNets` every frame, a brush that adds and subtracts; vertices and triangles per second. Shows: SDF modelling and meshing.
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

1. Done: F1-F9, C1.
2. The five that show the widest range, in this order: **C2** `culling-lab`, **A2** `dq-vs-lbs` (with F11), **R1** `sky-sun`, **P1** `rigid-pile`, **P2** `sdf-sculpt`. Two of them need nothing but what exists (C2, P1, P2); A2 and R1 want
   sliders (F11).
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
| wait for the ring | 0.091 ms |
| frustum cull | 2.493 ms |
| write instances (32.0 MB per frame) | 6.753 ms |
| submit (uniforms, command, draw, fence) | 0.076 ms |
| GPU time of the draw | 7.685 ms |
| frame | 10.030 ms |
| allocated on the render thread | 2,425 B per frame |
| stalls waiting for the GPU | 0 |

The frame is about half a millisecond slower than in the first version of the sample (9.4 ms, [SAMPLES.md](SAMPLES.md)): it now draws the HUD and the runner measures each frame. The 50 000-box smoke
configuration passes with 2,424 B per frame against a budget of 4,096 B.

**Does not prove.** One GPU of one vendor; a synthetic scene of equal boxes; the frame is bound by the instance write and by the GPU, not by the library's culling, so it says little about scenes with
fewer, heavier objects. Culling quality (the culling is conservative; how much it culls) is tested in the library, not here.
