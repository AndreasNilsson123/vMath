# Compact formats (`vmath.pack`)

Everything here trades precision for memory and bandwidth. All of it is precision-independent (bit twiddling on ints and shorts), so it is
hand-written rather than generated, and it allocates nothing beyond the small vector records some methods return.

| Format | Size | Class | Range and precision |
|---|---|---|---|
| half float | 2 B | `Half` | finite up to 65504, 11 significant bits; the JDK's own conversion (ties to even) |
| unorm8 / snorm8 | 1 B | `Norm` | `[0,1]` in steps of 1/255; `[-1,1]` in steps of 1/127, 0 exact |
| unorm16 / snorm16 | 2 B | `Norm` | steps of 1/65535 and 1/32767 |
| RGB10A2 (unorm, snorm) | 4 B | `Norm` | 10-bit color channels, 2-bit alpha |
| R11G11B10F | 4 B | `SmallFloat` | HDR, non-negative, up to 65024 (red, green) and 64512 (blue); about 2^-7 and 2^-6 relative error |
| RGB9E5 | 4 B | `SmallFloat` | HDR, non-negative, shared exponent, up to 65408; error within half a step of the largest channel |
| octahedral unit vector, 2x16 | 4 B | `Octahedral` | worst angular error 4.3e-5 rad (0.0025 degrees) |
| octahedral unit vector, 2x8 | 2 B | `Octahedral` | worst angular error 1.1e-2 rad (0.63 degrees) |
| smallest-three quaternion | 4 B | `QuatPacked` | worst rotation error 4.1e-3 rad (0.24 degrees) |
| position in a box, 3x unorm16 | 6 B | `Quantizer` | half a step: `size / 131070` per axis |
| position or uv on a grid of 1 to 16 bits | any | `GridQuantizer`, `UvQuantizer` | half a step: `extent / (2 * (2^bits - 1))` |

The angular figures are the worst cases measured over 2 million random unit vectors or rotations, and the tests assert bounds just above
them (so a regression that loses accuracy fails). A `Vec3f` normal is 12 bytes and a `Quatf` is 16, so these are 3 to 6 times smaller.

## Rules that apply throughout

- **Range.** Inputs outside a format's range are clamped, never wrapped. NaN packs to 0 in the normalized formats and stays NaN in the
  floating-point ones.
- **Rounding** is to nearest. Normalized formats round ties up; half floats and the small floats round ties to even.
- **Zero is exact** in every format (snorm reserves its most negative code so the range is symmetric).
- **Bit layouts follow the graphics APIs** so a packed `int` can be uploaded as it is: first component in the lowest bits (GLSL
  `packUnorm4x8` and friends), red in bits 0 to 9 for `RGB10A2`, red in bits 0 to 10 for `R11G11B10F`, and so on.
- `PackedFormat` lists the matching OpenGL internal format and `VkFormat` for each one, plus its size. The numbers were entered by hand from the registries and compared
  in tests with `TextureFormat`'s Vulkan numbers where the formats are shared; no test reads the Khronos `glcorearb.h` and `vulkan_core.h` headers yet (technical-debt TD-01).

## Choosing

- **Normals and tangents:** octahedral 2x16 is accurate to 0.0025 degrees for 4 bytes, and 2x8 (2 bytes) is fine for lighting-only use
  where 0.6 degrees of error is invisible. Both use best-of-four rounding: the encoder tries the four neighbouring quantized values and keeps
  the closest, which cuts the 8-bit worst case by about a third compared with plain rounding.
- **Orientations:** `QuatPacked` for animation streams and tangent frames (0.24 degrees).
- **HDR render targets and probes:** `R11G11B10F` when the channels have similar brightness, `RGB9E5` when you want more mantissa bits and can
  accept that a dim channel next to a bright one loses precision. Neither stores negative values or alpha.
- **Positions:** `Quantizer` when the mesh has a known bounding box. Fold `dequantizationMatrix()` into the model matrix and let the GPU
  read the three shorts as normalized integers.

## Quantization of any width and mesh attribute formats (experimental)

`Quantize` does normalized integers of 1 to 24 bits (`unorm`, `snorm` with exact zero and a symmetric range, `fromUnorm`, `fromSnorm`) and `mantissa(f, bits)`, which rounds a float's mantissa to fewer bits (relative
error at most `2^-(bits+1)`, the low bits become zero) so that data keeps its type but compresses far better. The conventions are those of `Norm`; against its float arithmetic the 8-, 10- and 16-bit codes agree to within one
code at values that sit on a rounding tie.

`GridQuantizer` quantizes positions to 1 to 16 bits inside a box: `of(bounds, bits)` scales each axis to its own extent, `uniform(bounds, bits)` uses the largest extent for all axes so the cells are cubes and the error is
the same in every direction (the meshoptimizer convention). The worst error per axis is half a step, `extent / (2 * (2^bits - 1))`: 6.1e-5 units for 14 bits across 2 units, 9.8e-4 for 10 bits. `dequantizationMatrix()` folds
into the model matrix. Tested: the error bound at 1, 5, 10, 14 and 16 bits in both modes over 3 000 random points each, the matrix against `unpack`, cubic cells, clamping, flat axes, and agreement with `Quantizer` at 16 bits.
`UvQuantizer` does the same for texture coordinates in a rectangle (`fit` finds the rectangle of a set, which may lie outside [0, 1]).

`VertexLayout` gained two formats that use them through `MeshExport`: `positionUnorm16()` (an unorm16x4: three coordinates inside the mesh bounding box and a 1 in the fourth, 8 bytes) and `uvUnorm16(set)` (4 bytes, over
the rectangle around the set). `MeshExport.positionQuantizer(mesh)` and `uvQuantizer(mesh, set)` return the quantizers the writer used, for the matrix and the rectangle the shader needs; `toBufferLayout()` describes them as
`UNORM16X4` and `UNORM16X2`. A layout of position, octahedral normal and uv takes 16 bytes per vertex in this form, against 24 with a float position (12), the same normal (4) and a float uv pair (8): a third smaller.
Tested on a sphere whose uvs were stretched outside the unit square: every position and uv comes back within half a step through the matrix and the rectangle.

What is **not** built from meshoptimizer: the vertex and index buffer *compression* codecs (`encodeVertexBuffer`, `encodeIndexBuffer`), which need a byte-level entropy format and decoders on the target side.

## Not covered yet

The vertex and index buffer compression codecs of meshoptimizer (the quantization and the mesh formats above are built, the entropy-coded streams are not), and unsigned 8-bit encodings of normals (2x8 octahedral covers that need). Colour is in `docs/COLOR.md`. See `docs/ROADMAP.md`.
