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

## Not covered yet

Checking a layout against a shader's reflection data (GPU-2), indirect-draw and dispatch structs (GPU-3), vertex-format builders
(GPU-4), and generating a shared GLSL header file from the records (the `GLSL` string is available per struct today).
The older `Std140` class (`vec3`/`mat3`/`mat4` into a `FloatBuffer`) still works, but the layout engine and writers above supersede it.
