# Bulk arrays

Anything that scales with the number of objects lives in plain `float[]` arrays, never in arrays of value objects: one contiguous read per element,
no per-element allocation, and the arrays are already in the layout a GPU buffer wants. `vmath.bulk` has the containers and the batch kernels.

| Container | Floats per element | Holds | Also see |
|---|---|---|---|
| `BoundsArray` | 6 arrays of 1 | axis-aligned boxes, structure-of-arrays | `docs/CULLING.md` |
| `Mat4fArray` | 16 | 4x4 matrices, column-major (GPU order) | `Skinning.jointMatrices` writes one |
| `Vec3fArray` | 3 | positions, directions, normals | |
| `Vec4fArray` | 4 | homogeneous positions, colours, planes | |
| `QuatArray` | 4 | unit quaternions `x, y, z, w` | |
| `TransformArray` | 10 | translation, unit quaternion, scale: the layout of `Pose` and `TransformHierarchy` | `docs/ANIMATION.md` |
| `SegmentFloatArray` | any | the same, off-heap in a `MemorySegment` (below) | |
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
| `Mat4fArray.multiply(a, b, out)`, `premultiply(m, out)` | element-wise matrix product; a common matrix on the left of every element |
| `QuatArray.nlerp(a, b, t, out)` | shortest-arc normalized lerp |
| `Vec4fArray.transform(m, out)`, `divideByW(out)` | full 4x4 product; perspective divide |
| `TransformArray.toMatrices(segment, offset, stride)` | model matrices straight into an off-heap buffer |

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
animation frames) `QuatArray.nlerp` is a good substitute at a fraction of the cost (measured below). The Vector API is used for the matrix product only.

## More containers, compaction and off-heap storage (experimental)

**`Vec4fArray`** has the shape of `Vec3fArray` (homogeneous positions, colours, plane equations): `transform(Mat4f, out)` is the full 4x4 product, so a perspective matrix gives clip
positions, and `divideByW(Vec3fArray)` takes them to NDC (tested against `Mat4f.transformProject`).

**Compaction.** Every container (`Vec3fArray`, `Vec4fArray`, `QuatArray`, `Mat4fArray`, `TransformArray`, `BoundsArray`) has `removeSwap(i)`, which moves the last element into the gap in O(1) and
returns the index that element came from (or -1 if `i` was last, so parallel arrays can mirror it), and `compact(VisibilitySet keep)`, which keeps the marked elements in their original order and
returns the new size. Together with `HandleRegistry` below this is the machinery for dense per-entity data.

**Off-heap: `SegmentFloatArray`.** The `MemorySegment` twin of the containers: a growable array of fixed-size elements (`floatsPerElement`, with `ofVec3`, `ofVec4`, `ofMat4`, `ofTransform` and
typed accessors such as `addMat4`/`getMat4`) in a shared `Arena`, with the same `add`, `set`, `get`, `size`, `capacity`, `ensureCapacity`, `removeSwap`, `compact` and the live `segment()` (replaced on growth,
the old memory freed). `copyFrom(float[], count)` and `copyTo` move whole runs to and from a heap container. It is `AutoCloseable`. Rather than a second copy of every kernel, the kernels that produce
GPU data can write straight into a segment: `TransformArray.toMatrices(MemorySegment, offset, strideBytes)` computes the model matrices into a mapped instance buffer (a stride above 64 leaves per-instance
data alone). Measured, 100 000 transforms: through a heap `Mat4fArray` and a copy 1 519 us, direct 825 us (+-214), about 1.8 times faster.

**Matrix and quaternion kernels.** `Mat4fArray.multiply(a, b, out)` (element-wise product, `out` may alias an input) and `premultiply(Mat4f, out)` (a parent matrix on every element); `QuatArray.nlerp`
(shortest-arc normalized lerp). Tested against `Mat4f.mul` and `Quatf.nlerp`, including aliasing. Measured for 100 000 elements: `nlerp` 596 us against `slerp` 7 799 us (13 times faster; use it when the
rotations are close). `multiply` runs through a `MatrixKernel` chosen at startup by `MatrixKernels.best()`: the scalar one, or, when the `vmath-simd` module and `--add-modules jdk.incubator.vector` are present,
a Vector API kernel (one 128-bit vector per matrix column, fused multiply-add; results can differ from the scalar kernel in the last bit). Measured (JMH, 2 forks of 10 iterations): scalar 2 008 us
(+-58), SIMD 914 us (+-117) for 100 000 products, so 2.2 times faster (20 ns against 9 ns per matrix). `-Dvmath.matrixKernel=scalar` forces the scalar one. The other kernels (transform of positions, slerp, ...) have no Vector
API variant: they work on interleaved data where the gain has not been shown, and none was written.

## Incremental GPU upload: `DirtyRanges`

`DirtyRanges` is a bitset of changed elements. `mark(i)` and `markRange(from, to)` as you write; `ranges(maxGap, out)` or `forEachRange(maxGap, visitor)` give the changed runs, with runs at most
`maxGap` clean elements apart merged (one slightly larger copy beats two calls); `uploadFloats(float[], floatsPerElement, count, MemorySegment dst, dstOffset, maxGap)` and `uploadSegment(...)`
copy just those runs into a mapped buffer and clear the set. With frames in flight each buffer copy needs the changes since *that* copy was written, which `FrameDirtyRanges` provides: one set per
slot, `mark` marks all of them, `forSlot(frame % n)` is the set to upload from. Tested against a boolean model (all operations, run merging, search), and end to end: with 3 buffers and random
changes each frame, every buffer equals the CPU array after its upload while copying a small fraction of the bytes. Measured, 100 000 elements of 16 bytes (1.6 MB): copying everything 38.1 us;
1% of the elements dirty (runs of 10, marked in the same call) 3.0 us; 10% dirty (runs of 100) 7.3 us, so 12 and 5 times faster.

## Entity handles: `HandleRegistry`

A sparse set with generation counters. `create()` returns a `long` handle (`generation << 32 | slot`); `isAlive(handle)` is false after `destroy`, even when the slot is reused, because the generation differs, so a stale
handle can never reach a new entity. Alive entities fill the dense indices `0 .. size() - 1` without holes, so component arrays indexed by dense index can be iterated and uploaded as they are. `destroy`
swaps the last entity into the gap and returns the freed dense index `d`; if `d < size()` afterwards, do `removeSwap(d)` on every parallel array. `denseIndex(handle)` and `handleAt(dense)` map between the two sides.
Tested with 20 000 random operations against a map and a mirrored component array (every alive entity once in the dense range with its own component, every dead handle stale). A create, look-up and destroy
costs about 3 ns each (9.4 us for 1 000 of each). A slot's generation wraps after 2^32 - 1 reuses of that one slot.

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
