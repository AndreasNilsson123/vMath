# Textures

`vmath.tex` is the texture-side bookkeeping a renderer needs before it touches a graphics API: what a format is made of, how big every mip level and
array layer is and where it starts, how a cube map is addressed, and what is inside a KTX2 header. It decodes no pixels and depends on no graphics
library. Everything here is `@Experimental` (see `VERSIONING.md`).

## `TextureFormat`

An enum of the common formats with the Vulkan `VkFormat` value (what KTX2 stores), the block size in texels, bytes per block and an sRGB flag: the usual
uncompressed 8/16/32-bit formats, BC1 to BC7, ETC2 and EAC, and all ASTC LDR block sizes from 4x4 to 12x12. An uncompressed format is a 1 x 1 block, so
one formula covers both: `imageBytes(w, h) = ceil(w / bw) * ceil(h / bh) * bytesPerBlock` (a 1 x 1 BC1 image takes a whole 8-byte block).
`fromVkFormat` returns `null` for a format not in the table; the table is not every format.
The test checks that values are unique, round-trip, that every ASTC block is 16 bytes with the block size in its name, and a few values the Vulkan
specification fixes; it cannot check the table against the specification itself, so a wrong entry is possible.

## `TextureLayout`

A record of format, width, height, depth, mip levels, array layers and faces (1, or 6 for a cube map). The data order is the order of the contents of a KTX2
level and of a Vulkan buffer-to-image copy: **level, then layer, then face, then depth slice**. `maxLevels` is `floor(log2(max dimension)) + 1`, level sizes are
`max(1, size >> level)`, and `levelOffset`, `imageOffset(level, layer, face)` and `totalBytes` are computed from those, compressed formats rounded up to
whole blocks. A cube array has `layers * 6` images per level. Invalid combinations (more levels than the size allows, faces other than 1 or 6) throw.

## `CubeFace`

The six faces in the array-layer order `+X, -X, +Y, -Y, +Z, -Z`, and the mapping between a direction and `(face, u, v)` from the cube-map selection table
of the OpenGL specification (Vulkan specifies the same). `CubeFace.of(dir)` picks the face, `u`/`v` give the position, `direction(u, v)` is the inverse.
Tested by round trips on random directions and by the face centres. It is a texture-coordinate convention; renderers that store faces upside down flip `v`
themselves. Not checked against a GPU.

## `Ktx2`

`Ktx2.parse(ByteBuffer)` reads the 80-byte header and the level index of a KTX 2.0 file (absolute reads, any buffer byte order) and validates what it can:
identifier, width at least 1, faces 1 or 6, square cube faces, level count within what the size allows, and every level range inside the data. The result
has the raw fields, the level index (offset, length, uncompressed length; level 0 is the largest and is stored last in the file), `format()`, and `layout()`
for a known format. `levelCount == 0` is reported as stored (the loader is meant to generate the chain) while the index still has the base level.
It does **not** decompress: supercompressed files (Basis LZ, Zstandard, ZLIB) are reported through `supercompressionScheme` and the raw level ranges, and
the key/value data and the data format descriptor are given as byte ranges, not parsed. `writeHeader` writes a header and level index, which is how the
tests build their files; the tests use hand-built files, not files from a real encoder.
