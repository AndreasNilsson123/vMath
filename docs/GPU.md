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

## Not covered yet

Checking a layout against a shader's reflection data (GPU-2), vertex-format builders
(GPU-4), and generating a shared GLSL header file from the records (the `GLSL` string is available per struct today).
The older `Std140` class (`vec3`/`mat3`/`mat4` into a `FloatBuffer`) still works, but the layout engine and writers above supersede it.

## End-to-end sample

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
