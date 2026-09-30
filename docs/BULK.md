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

## Writing into interleaved buffers

`Vec3fArray`, `QuatArray`, `Mat4fArray` and `TransformArray` have `writeTo(MemorySegment, offset, strideBytes, order)` and `readFrom(...)`. The stride is the
distance in bytes between elements in the destination; when it is larger than one element the bytes in between are left alone, so one container fills one
attribute of an interleaved vertex or instance buffer and the next container fills the next. `Strided` is the underlying copy and also has `ByteBuffer`
variants (absolute positions, the buffer's own byte order). A tightly packed write in native byte order is a single bulk copy; every other case is an
element loop. No timing is recorded for either yet.
