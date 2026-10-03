# Samples

`vmath-samples` is a separate Gradle module with programs that use vmath against a real graphics API. It needs LWJGL (GLFW and OpenGL bindings), which Gradle downloads, and a driver
to run, so it is part of the build **only when asked for**: the library build (`./gradlew build`) never resolves LWJGL.

```
./gradlew -Psamples :vmath-samples:run                              # the million-instance city, interactive
./gradlew -Psamples :vmath-samples:run --args="--frames 600"        # a scripted flight, the timings, then exit
./gradlew -Psamples :vmath-samples:run --args="--help"
```

The module is added to the build by `settings.gradle.kts` when the property `samples` is set, so the same flag is needed for every task of the module (IDEs: set it as a Gradle
property of the project). LWJGL 3.3.6 comes from Maven Central, with the natives of the machine that builds it (`natives-windows`, `-linux`, `-macos`, and the `arm64` variants); the
version is in `gradle/libs.versions.toml`. The run task passes `--add-modules=jdk.incubator.vector` so that the SIMD frustum kernel is found, and `--enable-native-access=ALL-UNNAMED`.

## `MillionInstances`

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
draws all million boxes), `V` to toggle vsync, `R` to reset the camera, `Escape` to quit. The title bar shows the visible instances, the frame rate and the time of each stage.

**Benchmark.** `--frames N` flies a fixed circle around the city (the same on every run), leaves out the first `--warmup` frames (60), and prints the average time of each stage, the
number of visible instances, the allocation on the render thread per frame and how often the ring had to wait for the GPU, then exits. `--screenshot FILE` writes the last frame as a
PNG, `--threads N` culls on N threads, `--no-cull` draws everything, `--instances N`, `--width`, `--height`, `--frames-in-flight N`, `--vsync` and `--no-vsync` do what they say.

**Measured** (JDK 25, NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, OpenGL 4.5, 1600 x 900, vsync off, 400 frames after 100 of warm-up, 3 frames in flight):

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
GPU-driven culling shaders (`vmath.gpucull`), Vulkan, other vendors and other operating systems are still unchecked, and there is no automated test of the sample (it needs a display and a
driver; `--frames N` with `--screenshot` is the way to run it by hand or on a machine that has them).

The older headless `vmath-bench/.../sample/CullAndDrawSample` stays as the CPU-only version for machines without a graphics driver (`./gradlew :vmath-bench:sample`, `docs/GPU.md`).

## Layout of the module

`vmath-samples/src/main/java/vmath/samples`: `MillionInstances` (the window, the GPU objects, the frame loop and the report), `Options` (the command line), `City` (the scene),
`FlyCamera` (free flight and the scripted flight) and `Shaders` (the GLSL, with the vertex inputs generated from the vertex layout). To add a sample, add a class with a `main` to the module
and point `application.mainClass` in `vmath-samples/build.gradle.kts` at it.
