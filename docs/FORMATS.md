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

The angular figures are the worst cases measured over 2 million random unit vectors or rotations, and the tests assert bounds just above
them (so a regression that loses accuracy fails). A `Vec3f` normal is 12 bytes and a `Quatf` is 16, so these are 3 to 6 times smaller.

## Rules that apply throughout

- **Range.** Inputs outside a format's range are clamped, never wrapped. NaN packs to 0 in the normalized formats and stays NaN in the
  floating-point ones.
- **Rounding** is to nearest. Normalized formats round ties up; half floats and the small floats round ties to even.
- **Zero is exact** in every format (snorm reserves its most negative code so the range is symmetric).
- **Bit layouts follow the graphics APIs** so a packed `int` can be uploaded as it is: first component in the lowest bits (GLSL
  `packUnorm4x8` and friends), red in bits 0 to 9 for `RGB10A2`, red in bits 0 to 10 for `R11G11B10F`, and so on.
- `PackedFormat` lists the matching OpenGL internal format and `VkFormat` for each one, plus its size. The numbers were checked against the
  Khronos `glcorearb.h` and `vulkan_core.h` headers.

## Choosing

- **Normals and tangents:** octahedral 2x16 is accurate to 0.0025 degrees for 4 bytes, and 2x8 (2 bytes) is fine for lighting-only use
  where 0.6 degrees of error is invisible. Both use best-of-four rounding: the encoder tries the four neighbouring quantized values and keeps
  the closest, which cuts the 8-bit worst case by about a third compared with plain rounding.
- **Orientations:** `QuatPacked` for animation streams and tangent frames (0.24 degrees).
- **HDR render targets and probes:** `R11G11B10F` when the channels have similar brightness, `RGB9E5` when you want more mantissa bits and can
  accept that a dim channel next to a bright one loses precision. Neither stores negative values or alpha.
- **Positions:** `Quantizer` when the mesh has a known bounding box. Fold `dequantizationMatrix()` into the model matrix and let the GPU
  read the three shorts as normalized integers.

## Not covered yet

Hilbert curves (only Morton codes exist, in `vmath.core.Morton`), a full attribute-quantization pipeline like meshoptimizer's (this belongs
with mesh processing), and unsigned 8-bit encodings of normals (2x8 octahedral covers that need). See `docs/ROADMAP.md`.
