# Samples

`vmath-samples` is a separate Gradle module with demos that use vmath against a real graphics API. It needs LWJGL (GLFW and OpenGL bindings), which Gradle downloads, and a driver
to run, so it is part of the build **only when asked for**: the library build (`./gradlew build`) never resolves LWJGL. The demos share one framework (a launcher with a menu, the window, the
camera, a heads-up display, statistics, screenshots and a smoke check); how it is built, the rules every demo follows and the backlog of the next demos are in [DEMOS.md](DEMOS.md).

```
./gradlew -Psamples :vmath-samples:run                                          # the launcher, with a menu
./gradlew -Psamples :vmath-samples:run --args="--demo city"                     # the million-instance city, interactive
./gradlew -Psamples :vmath-samples:run --args="--demo city --frames 600"        # a scripted flight, the timings, then exit
./gradlew -Psamples :vmath-samples:run --args="--list"                          # the demos
./gradlew -Psamples :vmath-samples:run --args="--help"
./gradlew -Psamples :vmath-samples:smoke                                        # every demo for a few frames, checked
./gradlew -Psamples :vmath-samples:test                                         # the tests that need no window
```

The module is added to the build by `settings.gradle.kts` when the property `samples` is set, so the same flag is needed for every task of the module (IDEs: set it as a Gradle
property of the project; IntelliJ needs nothing, see [IDE.md](IDE.md)). LWJGL 3.3.6 comes from Maven Central, with the natives of the machine that builds it (`natives-windows`, `-linux`, `-macos`, and
the `arm64` variants); the version is in `gradle/libs.versions.toml`. The run task passes `--add-modules=jdk.incubator.vector` so that the SIMD frustum kernel is found, and
`--enable-native-access=ALL-UNNAMED`. Gradle runs the program in the module's directory, so a relative path for `--screenshot` or `--report` is relative to `vmath-samples`.

## The demos

| id | what it shows | card |
|---|---|---|
| `city` | a million boxes culled and drawn with one indirect call | below, and [DEMOS.md](DEMOS.md) |
| `culling-lab` | seven ways to cull the same boxes, timed and checked against each other | [DEMOS.md](DEMOS.md) |
| `interior-portals` | portal culling in a building of 400 rooms with doors that open and shut | [DEMOS.md](DEMOS.md) |
| `occlusion` | software occlusion culling in a dense city, with a ray check that nothing visible is removed | [DEMOS.md](DEMOS.md) |

## The demo `city`

A city of a million boxes whose heights follow fractal noise (`vmath.util.Noise`), drawn with OpenGL 4.5. It needs a driver with OpenGL 4.5 (so not macOS, which stops at 4.1); it
creates a core profile context and says so if it cannot. What it uses from the library:

| Step | vmath |
|---|---|
| the scene: a million boxes and a ground box | `City`, `BoundsArray`, `Noise.fbm2`, `Rng` |
| the box mesh, optimised for the vertex cache and exported with a vertex layout | `Primitives.box`, `MeshOptimizer`, `VertexLayout`, `MeshExport` |
| the vertex array object and the shader inputs, from the same layout | `VertexBufferLayout.glFormats()` and `glslInputs()` |
| the camera, its frustum and the OpenGL depth convention | `Cameraf`, `Frustumf`, `DepthRange` |
| frustum culling of the million boxes, on the render thread or on several, with the SIMD kernel | `CullPipeline`, `CullStages.Frustum`, `FrustumKernels`, `ParallelFrustumKernel` |
| the survivors as 64-byte instance records written straight into mapped GPU memory | `InstanceWriter.writeVisibleBoxes` |
| a buffer that the CPU and the GPU share for several frames in flight, guarded by fences | `PersistentBufferRing` with `FenceOps` over `glFenceSync` |
| one indirect draw command for all the survivors | `DrawCommandBuffer`, `glDrawElementsIndirect` |

Each box is an instance of one unit cube: the instance record is a scale and a translation (three rows of a `vec4` and a word of user data, the layout `InstanceWriter` documents), the
vertex shader reads it from a shader storage buffer by `gl_InstanceID`, and the colour is a hash of the box's index, which is the user data. The instance buffer is mapped once
(`glNamedBufferStorage` with the persistent and coherent bits) and split into one region per frame in flight; each frame binds its own region with `glBindBufferRange`.

**Interactive.** Hold the left mouse button and move to look, `W A S D` to fly, `Space` and `Left Control` for up and down, `Left Shift` for speed, `C` to toggle the frustum culling (off
draws all million boxes), `X` to freeze the camera that the culling uses and draw its frustum (fly away and see what was removed), `R` to reset the camera. The keys of every demo are `Tab` (menu),
`PageUp` and `PageDown` (switch demo), `V` (vsync), `P` (screenshot), `F1` (hide the text) and `Escape`. The text in the corner shows the visible instances and the time of each stage.

**Benchmark.** `--frames N` flies a fixed circle around the city (the same on every run, at a fixed time step), leaves out the first `--warmup` frames (60), and prints the average time of each
stage, the number of visible instances, the allocation on the render thread per frame and how often the ring had to wait for the GPU, then exits. `--screenshot FILE` writes the last frame as a
PNG, `--report FILE` appends the numbers as a markdown table, `--width`, `--height`, `--vsync`, `--no-vsync`, `--no-hud` and `--check-gl` do what they say. The demo's own options are
`--threads N` (cull on N threads), `--no-cull` (draw everything), `--instances N` and `--frames-in-flight N`.

**Measured** with the first version of the sample, before the framework (JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, OpenGL 4.5, 1600 x 900, vsync off, 400 frames after 100 of warm-up, 3 frames in flight):

| | 1 000 000 boxes, culling on (about 500 000 visible) | 4 threads | culling off (1 000 001 drawn) | 250 000 boxes |
|---|---|---|---|---|
| wait for the ring | 0.12 ms | | 0.72 ms | |
| frustum cull | 2.19 ms | 1.71 ms | 0.02 ms | 0.59 ms |
| write instances | 6.86 ms (32 MB) | 6.77 ms | 13.83 ms (64 MB) | 1.61 ms (7.8 MB) |
| submit (uniforms, command, draw, fence) | 0.08 ms | 0.08 ms | 0.12 ms | 0.04 ms |
| GPU time of the draw | 7.5 ms | 7.5 ms | 14.9 ms | 1.8 ms |
| frame | 9.4 ms (106 fps) | 8.7 ms (114 fps) | 14.9 ms (67 fps) | 2.4 ms (425 fps) |

Culling halves the work of every stage that scales with the instance count: the GPU draws half the boxes and the CPU writes half the bytes. The frame is bound by the instance
write (a memory-bound walk over the visible set that writes 64 bytes per box) and by the GPU, which the three frames in flight overlap: with culling on the ring never waited for the
fence (0 stalls); drawing everything is bound by the GPU, and the ring had to wait for the fence 143 times in the 260 frames of that run, which is what it is for. Four threads cut the cull from 2.2 to 1.7 ms, not by four. The render thread allocates about 2 kB per frame (the camera, its frustum and the cull context, which are records;
the instance write, the command and the ring allocate nothing).

## What it checks

Everything in the library that touches a graphics API had so far been checked against Java references and simulations only (`docs/technical-debt.md` TD-01). Running this sample on
a real driver is the first check against hardware, and it covers: the 64-byte instance layout read as `vec4`s in std430, the 20-byte `DrawElementsIndirect` command, the vertex
formats and locations that `VertexBufferLayout` produces, the shader input text from `glslInputs()`, persistent mapping with the ring's fences (no corruption, and the stalls counted
when the GPU is the limit), and the OpenGL clip-space conventions of `Cameraf` (`DepthRange.NEGATIVE_ONE_TO_ONE`). It was run on one NVIDIA GPU under Windows; the
GPU-driven culling shaders (`vmath.gpucull`), Vulkan, other vendors and other operating systems are still unchecked, and the parts of the framework that need no window have unit tests, and `:vmath-samples:smoke` runs every demo on a machine that has a display and a driver (it is not part of any other task).

The older headless `vmath-bench/.../sample/CullAndDrawSample` stays as the CPU-only version for machines without a graphics driver (`./gradlew :vmath-bench:sample`, `docs/GPU.md`).

## Layout of the module

`vmath-samples/src/main/java/vmath/samples`: `Launcher` (the entry point), `Demos` (the registry), `framework/` (the shared pieces, listed in [DEMOS.md](DEMOS.md)) and `demos/city/`, `demos/culling/`, `demos/portals/` and `demos/occlusion/` (one package per demo: the `...Demo` class, its options and what only it needs, such as `CullMethod` or `Building`). To add a demo, follow "Adding a demo" in [DEMOS.md](DEMOS.md).
The measurements of the demos are in their cards there; the table above is the first version's and is kept because the framework changed what a frame includes (the HUD, the per-frame timing).
