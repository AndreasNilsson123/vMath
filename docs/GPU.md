# GPU data layout

Getting Java values into a GLSL uniform or storage block means matching its memory layout exactly. vmath does this in two
layers: a **layout engine** that knows the rules, and a **generator** that turns a record into a ready-made writer.

## The three layouts (`GpuLayout`)

| | `STD140` (uniform blocks) | `STD430` (storage blocks) | `SCALAR` (Vulkan / `GL_EXT_scalar_block_layout`) |
|---|---|---|---|
| `vec2` / `vec3` / `vec4` alignment | 8 / 16 / 16 | 8 / 16 / 16 | 4 / 4 / 4 |
| `float[N]` stride | 16 | 4 | 4 |
| struct alignment | widest member, rounded up to 16 | widest member | widest member |
| `mat3` (3 columns of vec3) | 48 bytes, 16 per column | 48 bytes | 36 bytes |
| `mat2` | 32 bytes | 16 bytes | 16 bytes |

A `vec3` is only 12 bytes even where it aligns to 16, so a `float` right after it shares its slot
(`vec3 v; float f;` is 16 bytes in std140, whereas `float f; vec3 v;` is 32).

## Layout engine (`vmath.gl`)

```java
Struct light = new GlslType.Struct("Light", List.of(
        new Member("position", GlslType.VEC3),
        new Member("radius", GlslType.FLOAT),
        new Member("cascades", GlslType.array(GlslType.VEC4, 4))));
StructLayout l = light.layout(GpuLayout.STD140);
l.offsetOf("cascades");   // 16
l.size();                 // 80  (stride when used in an array)
l.paddingBytes();         // wasted bytes: reorder members to reduce it
light.glslDeclaration();  // "struct Light { ... };"
light.glslBlock(GpuLayout.STD430, "buffer", "lights");
```

`GlslType` covers `float`/`int`/`uint`, vectors, `matCxR`, fixed arrays and nested structs. The rules live in one place (`GlslType`,
`StructLayout`) and are checked against hand-worked examples from the GL specification plus invariants over random structs
(`GlslLayoutTest`).

## Writers (`GpuWriter`)

Static, allocation-free writes into a `MemorySegment` at explicit byte offsets: `putFloat`, `putInt`, `putVec2/3/4`,
`putQuat`, `putIVec2/3`, `putMat4`, `putMat3(columnStride)`, `putMat4x3(columnStride)`. They use native byte order and any
alignment. Padding is **never written**: allocate zeroed memory (`Arena.allocate`) if padding must be deterministic.
`GpuWriter.of(ByteBuffer)` wraps a heap or direct buffer as a segment.

## Generated writers (`@GpuStruct`)

```java
@GpuStruct(layout = GpuStruct.Layout.STD140)
record Light(Vec3f position, float radius, Vec4f color, @GpuArray(4) Vec4f[] cascades, Quatf orientation) {}
```

The build generates `LightGpu` next to it:

```java
LightGpu.SIZE                 // 112
LightGpu.OFFSET_CASCADES      // 32
LightGpu.STRIDE_CASCADES      // 16
LightGpu.GLSL                 // the matching "struct Light { ... };" text
LightGpu.write(light, segment, base);      // or write(light, byteBuffer, base)
```

Supported components: `float`, `int` (`@GpuUint` for `uint`), `Vec2f`, `Vec3f`, `Vec4f`, `Quatf` (as `vec4`, order `x y z w`), `Vec2i`,
`Vec3i`, `Mat3f`, `Mat4f`, `Mat4x3f`, other `@GpuStruct` records, and arrays of those with `@GpuArray(n)`. The generator reports
errors with file and message for: double-precision or 64-bit types, unknown types, `@GpuArray` misuse, and a nested struct whose
layout differs from its parent's (mixing rules would silently give wrong offsets).

How it works: `vmath-codegen` reads only component **names and types** from the source. The generated class builds its
`StructLayout` when the class loads (from the layout engine above), so the layout rules are not duplicated in the generator and
`vmath` still needs nothing beyond `java.base`. The offsets are `static final`, which the JIT treats as constants.

## Indirect draws, dispatches and instance data

Three `@GpuStruct` records are part of the library, so their generated `...Gpu` classes carry the layout constants and the GLSL text:

| Record | GL | Vulkan | Bytes |
|---|---|---|---|
| `DrawArraysIndirect(count, instanceCount, first, baseInstance)` | `DrawArraysIndirectCommand` | `VkDrawIndirectCommand` | 16 |
| `DrawElementsIndirect(count, instanceCount, firstIndex, baseVertex, baseInstance)` | `DrawElementsIndirectCommand` | `VkDrawIndexedIndirectCommand` | 20 |
| `DispatchIndirect(x, y, z)` | `DispatchIndirectCommand` | `VkDispatchIndirectCommand` | 12 |

Every member is a 4-byte scalar, so std430 and scalar layouts agree and there is no padding (tested). `baseVertex` is the only signed member.

`DrawCommandBuffer` writes a run of one kind of command into a `MemorySegment` at a fixed stride (optionally rounded up to 16 bytes, with the padding never
touched) and keeps the draw count for `glMultiDraw*Indirect` / `vkCmdDrawIndirect`. `instanceCount(i)` and `setInstanceCount(i, n)` read and zero the
instance count of one command, which is how a culling pass hides a draw without changing the draw count. `InstanceWriter` writes 64 bytes per instance:
an affine transform as three `vec4` rows (48 bytes instead of the 64 of a padded `mat4x3`; the shader computes
`vec3(dot(row0, p), dot(row1, p), dot(row2, p))` with `p = vec4(position, 1)`) and a `uint` of user data. Nothing in these writers allocates
(`AllocationContractTest`).

## Vertex buffers, shader headers and layout validation (experimental)

**Vertex formats** (`VertexFormat`, `VertexBufferLayout`). `VertexFormat` names the attribute formats a renderer meets (`FLOAT32X3`, `FLOAT16X2`, `SNORM16X2`, `UNORM8X4`,
`UINT8X4`, `UINT32`, ...) with the OpenGL component count, type and normalization, the Vulkan `VkFormat` value and the GLSL type; the numbers are the registry values and
the test pins them. `VertexBufferLayout` places named attributes (location, format, offset aligned to the component size, stride rounded up to 4, `perInstance`) and turns
the one description into `glFormats()` (the arguments of `glVertexAttribFormat`/`glVertexAttribIFormat`), `vkAttributes(binding)` and `vkBinding(binding)` (the Vulkan
input structs' values) and `glslInputs()` (the `layout(location = n) in ...` lines), so the three cannot disagree. `VertexLayout.toBufferLayout()` converts the mesh export
layouts (`position`, `normal`, `tangent`, `uv0`, ...). It describes; it calls no graphics API.

**Shared shader headers** (`ShaderHeader`). Generates the include file for a set of `StructLayout`s (the `LAYOUT` constant of every generated `XxxGpu`): guard, structs
with nested structs first and each once, `uint`/`int`/`float` constants for flag bits and sizes, optional GLSL interface blocks, and a comment per struct with its size and
member offsets so a layout change shows in a diff. GLSL and Slang output (`float3`, `float4x4`, ...); the output is deterministic, so a build can regenerate and compare.
The header text is checked by the library's tests for structure, ordering, deduplication and name conflicts. Matrices are column-major in the data, and Slang
needs the matching layout option (see the class comment).

**Layout validation** (`LayoutValidator`). Compares a Java `StructLayout` with reflection data (GL program introspection or SPIR-V decorations) that the caller reduces to
`Reflected(name, offset, arrayStride, matrixStride)`: offsets, array strides, matrix strides, unexpected members, optionally missing members (compilers drop unused ones) and the
block size, as a list of plain-text differences. `expected(layout)` gives the list the Java side implies. Tested against reflection of the `CullView` block worked out by hand
from the std140 rules (agrees), against nested-struct layouts worked out by hand, and against six deliberately wrong inputs (each reported). It has not been run against a real
driver or compiler; that is the opt-in test the roadmap item describes, and it needs a GPU or a SPIR-V toolchain.

## Not covered yet

Running the validator against a real compiler (it takes reflection data as input), compiling the generated headers, and Slang-specific buffer declarations. The older
`Std140` class (`vec3`/`mat3`/`mat4` into a `FloatBuffer`) still works but is deprecated for removal: the layout engine and writers above supersede it.

## End-to-end sample

The version that draws is the demo `city` in the `vmath-samples` module (OpenGL through LWJGL, `docs/SAMPLES.md`); this is the headless one that needs no driver.

`vmath-bench/.../sample/CullAndDrawSample.java` (run with `./gradlew :vmath-bench:sample`) is the CPU half of a GPU-driven frame, headless: 1 000 000 instances
are frustum culled with the best available kernel (Vulkan clip space), the survivors are written into an instance buffer with
`InstanceWriter.writeTranslation`, and one `DrawElementsIndirect` command is added to a `DrawCommandBuffer`. A renderer uploads the two segments and issues
the indirect draw. Measured on one machine, JDK 25, a moving camera with about 95 000 of the 1M instances visible, averaged over 300 frames after warm-up:

| Stage | Time per frame | Allocation per frame |
|---|---|---|
| frustum cull, 1M boxes | about 2.0 ms | 0 B |
| write about 95k instances (64 B each) and the draw command | about 1.7 ms | 0 B |

The first version of the sample built a `Mat4x3f.translation` per instance and allocated about 276 KB per frame (about 3 B per instance: escape analysis
removed most of the objects but not all), which is why `writeTranslation` exists. A sample with rotation or scale would use `InstanceWriter.write` and should
be measured again. The cull here is a single thread with no BVH or occlusion stage; `ParallelFrustumKernel` and `BvhStage` are the next levers.

### Frame cost by culling strategy (`FrameBench`)

`FrameBench` runs the same scene as the sample through JMH (`./gradlew :vmath-bench:jmh "-Pjmh.args=-prof gc FrameBench"`), one machine, JDK 25, 5 iterations
of 1 s, so read the ratios and mind the error bars. "Whole frame" is cull, then writing about 95 000 instances and one draw command, with
`InstanceWriter.writeVisibleTranslations`:

| Cull strategy | Cull only | Whole frame | Allocation per frame |
|---|---|---|---|
| serial (best single-thread kernel) | 2.06 ms ± 0.11 | 3.42 ms ± 0.16 | about 26 B (JMH noise floor) |
| parallel, 4 chunks | 0.73 ms ± 0.05 | 2.28 ms ± 0.19 | about 230 B (executor hand-off) |
| static BVH | 0.85 ms ± 0.18 | 2.92 ms ± 2.56 (very noisy) | about 23 B |

The instance write is now as large as the cull, so a faster cull stops paying off quickly: the BVH cull is about 2.4x faster than serial but saves well
under half of that in the frame. The 4-chunk parallel kernel allocates a few hundred bytes per call (task hand-off), the documented exception to the
zero-allocation rule.

### Tuning the instance write (`InstanceWriteBench`)

A fixed, random visibility set (9.5% of 1M, the worst case for locality), the step "write one instance per visible object":

| Variant | Time | Kept? |
|---|---|---|
| `nextSetBit` loop + six-array gather + write (first version) | 1.9 to 2.0 ms | replaced |
| walk the set a word at a time (`w &= w - 1`), same gather and write | 1.42 ms | **yes**, now `writeVisibleTranslations` |
| word loop over one packed centre array instead of six bounds arrays | 1.31 ms ± 0.97 | no: not clearly better within the noise, and it needs a second array kept in step |
| fill a heap `float[]`, then one bulk copy to the segment | 2.0 to 2.8 ms | no: slower (an extra pass over 6 MB) |

Split of the cost on the same set: bit scan with `nextSetBit` 0.41 ms, word scan 0.20 ms, six-array gather about 0.8 to 0.9 ms, the 64 B writes alone
0.16 to 0.19 ms. The gather (cache misses across six arrays) is the largest part and is what a packed layout would attack; it was not convincing here, so
the bounds stay in the planar layout the SIMD cull needs.

## Choosing by capability (`vmath.gl`, experimental)

Some things have a newer way and an older way to do them. The library implements both and picks by what the context can do, so that the same program runs on OpenGL 3.30 and on 4.6, and
on Vulkan, without the caller knowing which way is behind it. The library never calls a graphics API: **you** describe the context with a `GraphicsCapabilities` (from the version and the
extension strings you queried, or from the Vulkan device features) and the library answers with a strategy, a layout and, where there is shader text, the GLSL.

```java
GraphicsCapabilities caps = GraphicsCapabilities.openGl(major, minor, extensionNames);   // or GraphicsCapabilities.vulkan(multiDrawIndirect, drawIndirectFirstInstance, shaderDrawParameters)
GraphicsCapabilities older = caps.without(GraphicsCapabilities.Feature.MULTI_DRAW_INDIRECT);   // to test a lower way, or to work around a driver
```

The floor is OpenGL 3.30 core; below it nothing is promised and `openGl` refuses. A strategy that the capabilities cannot satisfy is an `UnsupportedOperationException` that lists what is
missing; the library never quietly does something else. Every table below is produced by `StrategyChooser.markdownTable()`; a test (`DecisionTablesDocTest`) keeps this page equal to the code
(`-Dvmath.writeDocs=true` rewrites it).

The features that each context has (derived from the version and the extensions in `GraphicsCapabilities.openGl`, which follows the specifications; it has not been checked against a driver):

<!-- decision-table:capabilities -->
| Feature | OpenGL 3.3 | OpenGL 4.2 | OpenGL 4.3 | OpenGL 4.5 | OpenGL 4.6 | Vulkan, no optional features | Vulkan, all three |
|---|---|---|---|---|---|---|---|
| GLSL | 330 | 420 | 430 | 450 | 460 | 450 | 450 |
| `MULTI_DRAW` | yes | yes | yes | yes | yes | no | no |
| `BASE_VERTEX` | yes | yes | yes | yes | yes | yes | yes |
| `INSTANCED_DRAWS` | yes | yes | yes | yes | yes | yes | yes |
| `INSTANCED_ARRAYS` | yes | yes | yes | yes | yes | yes | yes |
| `UNIFORM_BLOCKS` | yes | yes | yes | yes | yes | yes | yes |
| `TEXTURE_BUFFERS` | yes | yes | yes | yes | yes | yes | yes |
| `DRAW_INDIRECT` | no | yes | yes | yes | yes | yes | yes |
| `MULTI_DRAW_INDIRECT` | no | no | yes | yes | yes | no | yes |
| `BASE_INSTANCE` | no | yes | yes | yes | yes | no | yes |
| `SHADER_DRAW_PARAMETERS` | no | no | no | no | yes | no | yes |
| `STORAGE_BUFFERS` | no | no | yes | yes | yes | yes | yes |
| `COMPUTE_SHADERS` | no | no | yes | yes | yes | yes | yes |
| `PERSISTENT_MAPPING` | no | no | no | yes | yes | yes | yes |
<!-- /decision-table -->

### Submitting draws (`DrawList`, `DrawSubmission`)

Put the draws in a `DrawList` (a count, a first vertex or index, a base vertex, an instance count, a base instance and a value of yours per draw), ask `DrawSubmission.choose(caps, list)`, and
write the list in the form it names:

| `DrawSubmission` | What you call | What the list gives you |
|---|---|---|
| `MULTI_DRAW_INDIRECT` | `glMultiDrawArraysIndirect` / `glMultiDrawElementsIndirect` (Vulkan: `vkCmdDrawIndirect` / `vkCmdDrawIndexedIndirect`) over a buffer | `list.writeIndirect(DrawCommandBuffer)`, which keeps zero-instance draws so that command index = draw index |
| `MULTI_DRAW_CLIENT` | `glMultiDrawArrays` / `glMultiDrawElements` (`glMultiDrawElementsBaseVertex` if a base vertex is used) | `copyCounts`, `copyFirsts`, `copyIndexOffsets`, `copyBaseVertices`; draws that draw nothing are left out; no instancing |
| `DRAW_LOOP` | one `glDrawArraysInstanced` / `glDrawElementsInstancedBaseVertex` per draw (with the base-instance form of the call where `BASE_INSTANCE` exists) | `list.forEach(visitor)`; without `BASE_INSTANCE` move the per-instance vertex attributes by `baseInstance` instances yourself |

The choice depends on the shape of the list, because a client multi-draw cannot instance and an indirect draw with a base instance needs `BASE_INSTANCE`:

Draws without instancing:

<!-- decision-table:draw-plain -->
| # | Strategy | Needs | What it does |
|---|---|---|---|
| 1 | `MULTI_DRAW_INDIRECT` | `MULTI_DRAW_INDIRECT` | one indirect call, the commands in a buffer |
| 2 | `MULTI_DRAW_CLIENT` | `MULTI_DRAW` | one call with client arrays |
| 3 | `DRAW_LOOP` | nothing | a draw call per draw |
<!-- /decision-table -->

Instanced draws:

<!-- decision-table:draw-instanced -->
| # | Strategy | Needs | What it does |
|---|---|---|---|
| 1 | `MULTI_DRAW_INDIRECT` | `MULTI_DRAW_INDIRECT` | one indirect call, the commands in a buffer |
| 2 | `DRAW_LOOP` | `INSTANCED_DRAWS` | an instanced draw call per draw |
<!-- /decision-table -->

Instanced draws with a base instance:

<!-- decision-table:draw-base-instance -->
| # | Strategy | Needs | What it does |
|---|---|---|---|
| 1 | `MULTI_DRAW_INDIRECT` | `MULTI_DRAW_INDIRECT`, `BASE_INSTANCE` | one indirect call, the commands in a buffer |
| 2 | `DRAW_LOOP` | `INSTANCED_DRAWS` | an instanced draw call per draw; without a base instance the per-instance attributes are moved by hand |
<!-- /decision-table -->

### Arrays of structs in a shader (`StructArrayAccess`)

A table of styles, a list of instances: the shader code calls `fetch_<name>(i)` and gets a struct, and the buffer behind it is one of four kinds. `StructArrayAccess.of(struct, mode, name, count,
slot, caps)` returns the GLSL (also through `ShaderHeader.Builder.access`), the layout and stride to write the elements with, and, for vertex attributes, the `VertexBufferLayout`.

| `StructArrayAccess.Mode` | Buffer | Write the elements | Bind it with |
|---|---|---|---|
| `STORAGE_BLOCK` | shader storage buffer | the writers of the struct in `std430` | `glBindBufferBase(GL_SHADER_STORAGE_BUFFER, slot, ...)` |
| `UNIFORM_BLOCK` | uniform buffer, at most 16,384 bytes guaranteed | `std140` | `glBindBufferBase(GL_UNIFORM_BUFFER, slot, ...)` |
| `TEXTURE_BUFFER` | texture buffer, format `RGBA32UI` | `std430`, every element padded to a multiple of 16 bytes (`elementStride()`) | `glTexBuffer` and a `usamplerBuffer` on a texture unit |
| `VERTEX_ATTRIBUTE` | vertex buffer, divisor 1 | `std430`, at `elementStride()`; the attributes are in `vertexLayout()` | the vertex array, one attribute per member |

`layout(binding = ...)` is only written from GLSL 4.20 on; below that the text has none and you bind through the API. The texture-buffer and attribute modes read scalars and vectors of `float`, `int` and
`uint`; a matrix, array or nested struct member is refused by name rather than laid out some other way. Which mode the library picks:

Read by any index:

<!-- decision-table:access-random -->
| # | Strategy | Needs | What it does |
|---|---|---|---|
| 1 | `STORAGE_BLOCK` | `STORAGE_BUFFERS` | a storage block, any length |
| 2 | `TEXTURE_BUFFER` | `TEXTURE_BUFFERS` | a texture buffer of RGBA32UI texels, any length |
| 3 | `UNIFORM_BLOCK` | `UNIFORM_BLOCKS` | an array in a uniform block, as long as the block size allows |
<!-- /decision-table -->

Read for the instance that is being drawn:

<!-- decision-table:access-instance -->
| # | Strategy | Needs | What it does |
|---|---|---|---|
| 1 | `VERTEX_ATTRIBUTE` | `INSTANCED_ARRAYS` | per-instance vertex attributes, nothing to fetch |
| 2 | `STORAGE_BLOCK` | `STORAGE_BUFFERS` | a storage block read by the instance index |
| 3 | `TEXTURE_BUFFER` | `TEXTURE_BUFFERS` | a texture buffer read by the instance index |
| 4 | `UNIFORM_BLOCK` | `UNIFORM_BLOCKS` | an array in a uniform block read by the instance index |
<!-- /decision-table -->

The text has been checked against the layouts by tests, and compiled with a GLSL compiler wherever one is installed (`ShaderCompileTest`; none was installed on the machine it was written on, so it has
not been compiled there).

### Where the culling runs (`CullBackend`, `SurvivorBatcher`)

The compute shaders of the next section are one way to cull. The other is the CPU: the kernels of `vmath.spatial` produce a `VisibilitySet`, `SurvivorBatcher.batch` groups the survivors by draw into a
`DrawList` (instance count and base instance per draw) and the list of surviving object indices (`survivors()`) that the vertex shader reads per instance, and `DrawSubmission` hands the list to the
driver in the best way available. Both paths give the same grouping.

<!-- decision-table:cull-backend -->
| # | Strategy | Needs | What it does |
|---|---|---|---|
| 1 | `COMPUTE` | `DRAW_INDIRECT`, `BASE_INSTANCE`, `STORAGE_BUFFERS`, `COMPUTE_SHADERS`, GLSL 4.50 | the compute shaders of GpuCullGlsl write the indirect commands |
| 2 | `CPU` | nothing | SIMD, parallel or tree kernels, then SurvivorBatcher and DrawList |
<!-- /decision-table -->

The compute path needs GLSL 4.50 for now (the text of `GpuCullGlsl` starts with `#version 450`); the roadmap lowers that once a compiler has confirmed what the shaders need.

## GPU-driven culling (`vmath.gpucull`, experimental)

CULL-13: the CPU-side layouts and a **CPU reference** of what a culling compute shader does, so that the shader has an exact oracle. Nothing here talks to a graphics API, and
**the shaders in `GpuCullGlsl` have never run on a GPU**: they are text written from the Java references step by step. The tests check the text for completeness and for consistency with the generated layouts, and `ShaderCompileTest` compiles them (object and cluster shaders at several work group sizes, `ClusterGrid.glslLookup`, `DualParaboloid.glsl`) with a real front end when `glslangValidator` or `glslc` is installed: on 2026-10-02 all four test methods passed with glslang 16.6.0, so the text is syntactically and type-correct GLSL 450; that says nothing about whether it computes the right thing. The first run on a GPU should be compared with the reference (as sets per draw: GPU atomics give no order).

**Layouts** (generated writers and GLSL declarations): `CullObject` (32 bytes std430: box min, draw index, box max, flags), `CullView` (192 bytes std140: six planes, view-projection
matrix, object count, pyramid size and level count, `nearW`, depth convention, flags), `ClusterCullObject` (80 bytes: geometry sphere, normal cone, level-of-detail sphere and error of the
cluster and of its parent, index range) and `ClusterCullView` (208 bytes: as `CullView` plus eye, pixel scale and pixel budget). Draws are `DrawElementsIndirect` commands in a
`DrawCommandBuffer` (which gained `baseInstance(index)`); surviving objects are a `uint` list.

**`HiZPyramid`** models the Hi-Z texture: a depth image in any of the three conventions (-1..1, 0..1, reversed-Z) is converted to one "farness" order (larger is farther) and max-reduced
into mip levels of any size (ceil sizes, edge texels cover the farthest of what they cover). `isHidden(rectangle, nearest)` picks the level where the rectangle is at most one texel wide,
reads at most 2 x 2 texels and compares strictly. `yDown` means row 0 is at NDC y = +1 (the D3D convention; false for OpenGL, and for Vulkan when the projection is not flipped).
Measured in `HiZPyramidTest`: of 31 259 random rectangle cases that a per-pixel test says are hidden, the pyramid reports 49.5% hidden, and **0 cases that are visible**.
The 40% in the test is a regression guard, not a promise.

**`GpuCullReference`** (one invocation per object): frustum test of the box (positive vertex; NaN never rejects), then, unless the object has `OBJECT_NO_OCCLUSION`, the Hi-Z test of the
projected box (a box with a corner at clip `w <= nearW` crosses the camera plane and is kept), then the append to the draw's instance list; slots beyond the capacity reserved for the draw
are dropped and counted. Entry points: `cullSinglePass`, and the two phases of the usual two-pass scheme: `cullPhase1` draws the objects that were visible last frame (frustum test only), the
renderer draws them and builds the pyramid, `cullPhase2` tests every object against the new pyramid, draws those that pass and were not drawn in phase 1, and records the new visibility.
Tested: frustum results agree with `Frustumf` in every clip-space convention; each draw lists exactly its own visible objects; overflow is counted; **in every convention and with reversed-Z the
reference never hides an object that an independent per-pixel test says is visible** (it hid 5 172 of 10 160 objects that are exactly hidden, 0 wrong); flagged objects and objects crossing
the camera plane are kept; the two phases never draw an object twice and together equal the single-pass result. Seeds 1 to 4 pass.

**`ClusterCullReference`** (one invocation per cluster of a `ClusterHierarchy`): select the cluster when its projected error is within the pixel budget and its parent's is not, test its
sphere against the frustum, back-face test it with its cone (`NormalCone.backfacing`), Hi-Z test the box around its sphere, and append one indirect command (`firstIndex`, `indexCount`,
one instance, `baseInstance` = cluster index) for a multi-draw-indirect-count call. Tested: over 120 random views the selected set equals the one computed from the hierarchy's own accessors,
`Frustumf` and `ConeCull` (8 485 expected clusters, **0 differences**); with frustum, cone and occlusion out of the way, the commands draw a closed, crack-free surface at budgets from 0 to
unlimited; a wall in front hides everything; a short command buffer drops and counts the rest.

**Allocation**: zero bytes per call for all passes and `HiZPyramid.isHidden` (`AllocationContractTest`). An early version allocated 91 KB per pass because `DepthRange.values()` clones its
array on every call.

**Measured** (`GpuCullBench`, JDK 25, one machine; boxes scattered in front of the camera, about a third in the frustum; Hi-Z against 40 random occluder rectangles at 480 x 270;
the pass reads the same bytes a shader would):

| Objects | frustum only | frustum and Hi-Z |
|---|---|---|
| 100 000 | 3.44 ms (about 35 ns per object) | 15.0 ms (about 150 ns per object) |
| 1 000 000 | 36.8 ms | 158.1 ms |

Loading the matrix and depth convention once per pass instead of once per object made the Hi-Z pass faster (179 ms to 158 ms at 1M objects). The references are oracles, not production
paths; this work belongs on the GPU.

**Not covered**: building the pyramid and the depth pre-pass (the renderer's job); hardware runs of the shaders; skinned meshes (`ClusterHierarchy` has no attribute support); oriented boxes
and spheres in the object pass; the per-cluster Hi-Z box is the box around the bounding sphere, which is loose. Instance order inside a draw is ascending in the reference and atomic order on a GPU.
