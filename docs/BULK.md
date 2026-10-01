# Bulk arrays

Anything that scales with the number of objects lives in plain `float[]` arrays, never in arrays of value objects: one contiguous read per element,
no per-element allocation, and the arrays are already in the layout a GPU buffer wants. `vmath.bulk` has the containers and the batch kernels.

| Container | Floats per element | Holds | Also see |
|---|---|---|---|
| `BoundsArray` | 6 arrays of 1 | axis-aligned boxes, structure-of-arrays | `docs/CULLING.md` |
| `Mat4fArray` | 16 | 4x4 matrices, column-major (GPU order) | `Skinning.jointMatrices` writes one |
| `Vec3fArray` | 3 | positions, directions, normals | |
| `QuatArray` | 4 | unit quaternions `x, y, z, w` | |
| `TransformArray` | 10 | translation, unit quaternion, scale: the layout of `Pose` and `TransformHierarchy` | `docs/ANIMATION.md` |
| `VisibilitySet`, `IntList` | bits, ints | culling results | `docs/CULLING.md` |

All of them have the same shape: `add`, `set`, `get`, `size`, `capacity`, `ensureCapacity`, `setSize` (after writing into `data()` directly), `clear`, a live `data()`
array that is replaced when the container grows (fetch it again after adding), and `writeTo(FloatBuffer, index)`.

## Kernels

They read and write the arrays directly, allocate nothing (`AllocationContractTest`), and accept the same array as input and output where that makes sense.

| Kernel | What it does |
|---|---|
| `Vec3fArray.transformPositions(Mat4f, out)` / `transformDirections` | apply an affine matrix to every element (as a point, or as a direction without translation) |
| `Vec3fArray.normalizeAll()`, `bounds()` | unit-length in place (zero stays zero); the box around all points |
| `QuatArray.normalizeAll()` | unit-length in place (zero or non-finite becomes the identity) |
| `QuatArray.multiply(a, b, out)`, `slerp(a, b, t, out)` | Hamilton product; shortest-arc slerp, renormalised |
| `QuatArray.toMatrices(out)` | rotation matrices |
| `TransformArray.toMatrices(out)` | model matrices `T * R * S`, the matrices to upload |
| `TransformArray.blend(a, b, t, out)` | translation and scale linear, rotation slerp |
| `BoundsArray.transformFrom(local, matrices)` | world bounds from local bounds and per-object matrices |

Each kernel is tested against the value-type classes (`Mat4f.transformPosition`, `Quatf.slerp`, `Transformf.toMat4`, ...), including the in-place calls and
the `q` / `-q` shortest-arc case. `TransformArray` and `QuatArray.slerp` are the same code `vmath.anim` uses (`Pose.lerp`, `ClipSampler`), so there is one
implementation of each.

## Measured

`BulkBench`, 100 000 elements, JDK 25, a single short run on one machine, so read the ratios rather than the digits (all about 0 B/op):

| Kernel | Time for 100k | Per element |
|---|---|---|
| `Vec3fArray.transformPositions` | 197 us | 2.0 ns |
| `Vec3fArray.normalizeAll` | 294 us | 2.9 ns |
| `QuatArray.multiply` | 302 us | 3.0 ns |
| `TransformArray.toMatrices` | 663 us (wide error bar) | 6.6 ns |
| `TransformArray.blend` | 1 638 us | 16 ns |
| `QuatArray.slerp` | 7 246 us | 72 ns |

`slerp` is the expensive one by a wide margin: it calls `sin` twice and `atan2` once per element. When the two rotations are close (a blend between nearby
animation frames) a normalised linear interpolation is a good substitute at a fraction of the cost, and a batch `nlerp` would be the obvious addition;
it is not built. None of these kernels uses the Vector API yet.

## Sorting, scans and locality order (experimental)

**`RadixSorter`.** A stable least-significant-digit radix sort (11-bit digits, so 3 passes for 32-bit and 6 for 64-bit keys, and a pass is skipped when all keys share the
digit) of `int`, `long`, `float` and `double` keys with an optional `int` payload. `sort`/`sortUnsigned` permute keys and payload together in place; `order` writes the permutation and
leaves a float or double key array untouched; floats and doubles can be sorted descending with ties still in input order. Float keys follow the IEEE total order (a NaN with the
sign bit set sorts first, which `Arrays.sort` does not do). An instance owns its scratch buffers (`reserve(n)` preallocates) and allocates nothing in steady state
(`AllocationContractTest`); use one per thread. Below 48 elements it falls back to an insertion sort. Tests compare against a stable sort of the indices by `Float.compare`,
`Long.compareUnsigned` and so on, for sizes from 0 to 100 000 and seven key distributions (random, few distinct values, all equal, sorted, reversed, narrow range, one constant digit).

**`PrefixSum`.** In-place and out-of-place exclusive and inclusive scans (`int`, `long`, and an `int` to `long` widening scan), and the two primitives of a chunked scan
(`chunkSum`, `scanChunkExclusive`); `exclusiveParallel` runs the chunks on the common pool and is tested equal to the sequential scan for chunk counts from 1 to 5 000.

**`LocalityOrder`.** Orders points (packed `xyz`) along a `Morton` or `Hilbert` curve at 21 bits per axis over their bounding box, with the radix sort; flat axes and huge ranges
are handled, non-finite coordinates are rejected.

**Measured** (`SortBench`, JDK 25, one machine; the JDK baseline sorts the order-preserving key bits and the index packed into one `long` with `Arrays.sort`, the best way to sort
pairs without objects):

| Task | 1 000 | 100 000 | 1 000 000 |
|---|---|---|---|
| order by float key, radix | 13.4 us | 1.67 ms | 21.7 ms |
| order by float key, JDK packed `long` sort | 14.8 us | 6.27 ms | 75.5 ms |
| float keys only, radix | 12.7 us | 0.93 ms | 13.4 ms |
| float keys only, `Arrays.sort(float[])` | 15.8 us | 7.79 ms | 93.9 ms |
| 64-bit keys with index, radix | 18.3 us | 2.48 ms | 72 ms (error +-37) |
| 64-bit keys only, `Arrays.sort(long[])` | 15.0 us | 6.20 ms | 79.7 ms |

So the radix sort is 3.5 to 8 times faster for float keys from 100 000 elements up and about equal at 1 000. For 64-bit keys with a payload it is 2.5 times faster at 100 000 but **not a clear
win at a million** (six passes over 8-byte keys are memory-bound, and the run-to-run spread is large); a smaller key range (Morton codes of fewer bits) skips passes automatically.

Prefix sum of 1 000 000 `int`s: 465 us sequential, 330 us with `exclusiveParallel(…, 8)`; at 100 000 elements the parallel version is slower (84 us against 43 us). A scan is memory-bound,
so only multi-million-element arrays gain, and only a little.

Locality order of 1 000 000 random points: Morton 75 ms, Hilbert 186 ms (+-27); the difference is the encode, 6 ns per Morton code against 104 ns per Hilbert code. The path through 50 000 random
points in the unit cube (`SortingTest`): input order 33 114, Morton 1 787, Hilbert 1 427, so the Hilbert path is 20% shorter. A branch-free rewrite of the Hilbert encode loop (mask arithmetic
instead of `if`) made it 11% faster (117 to 104 ns); a table-driven version that handles several levels per lookup would be the next step and has not been tried.

## Space-filling curve codes

`Morton` (Z-order) and `Hilbert` codes: 2D with up to 32 bits per axis (64-bit unsigned code) and 3D with up to 21 bits per axis (63-bit code), and 32-bit codes (`encode2Int`: 16 bits
per axis, unsigned; `encode3Int`: 10 bits per axis, non-negative). `Hilbert` takes the number of bits and rejects coordinates that do not fit. Tested properties of the Hilbert curve:
every cell is visited exactly once and each step moves to a neighbouring cell (checked exhaustively up to 128 x 128 and 16 x 16 x 16, and by sampling up to 32 and 21 bits), encode and decode are
inverse, the curve starts at the origin, and a code prefix is the code of the coarser cell (hierarchy).

## Writing into interleaved buffers

`Vec3fArray`, `QuatArray`, `Mat4fArray` and `TransformArray` have `writeTo(MemorySegment, offset, strideBytes, order)` and `readFrom(...)`. The stride is the
distance in bytes between elements in the destination; when it is larger than one element the bytes in between are left alone, so one container fills one
attribute of an interleaved vertex or instance buffer and the next container fills the next. `Strided` is the underlying copy and also has `ByteBuffer`
variants (absolute positions, the buffer's own byte order). A tightly packed write in native byte order is a single bulk copy; every other case is an
element loop. No timing is recorded for either yet.
