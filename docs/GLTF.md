# glTF 2.0 loader (`vmath.gltf`)

`Gltf.load(Path)` and `Gltf.parse(byte[], UriResolver)` read a `.gltf` (JSON, with embedded base64 or external buffers) or a `.glb` (binary container, recognised by
its magic) into vmath's own types. There are no dependencies and no pixel decoding. The whole package is `@Experimental` (see `VERSIONING.md`).

## What you get

- **Accessors.** `readFloats`, `readInts` and `readInto` decode every accessor kind in the specification: byte, short, int and float components, normalized or
  not (byte and short clamp to -1 as the specification says), a `byteStride` in the buffer view, matrix columns padded to 4 bytes (a MAT2 of bytes is 8 bytes, a MAT3
  of shorts 24), sparse accessors, and accessors without a buffer view (zeros). `readInto(accessor, segment, offset, strideBytes, order)` writes the converted floats
  straight into a `MemorySegment` with a chosen stride and byte order (through `vmath.bulk.Strided`), so one accessor fills one attribute of an interleaved vertex buffer.
- **Meshes.** `toMesh(mesh, primitive)` builds a `vmath.mesh.Mesh` (positions, normals, tangents, up to four UV sets) from a triangle, strip or fan primitive;
  strips keep the winding by flipping every second triangle. Non-indexed primitives get sequential indices. UVs keep glTF's convention, origin at the top left.
  `readSkinning` returns `JOINTS_0` and `WEIGHTS_0` (four per vertex).
- **Materials, textures, images, samplers** as records with the specification's defaults; `imageBytes` returns the encoded bytes (from a data URI, a resolver or a buffer
  view) and decodes nothing.
- **Nodes and scenes.** `localMatrix`, `worldMatrices` (computed without recursion, so a 20 000-deep chain is fine), `sceneNodes`, `defaultScene`. A node graph with two
  parents or a cycle is rejected.
- **Skins** become a `vmath.anim.Skeleton`. glTF lists joints in any order and a skeleton needs parents first, so `skin(i)` reorders them and returns
  `skinToSkeleton` (a vertex's `JOINTS_0` value to a skeleton joint) and `jointNodes`. Bind poses come from the joint nodes' local transforms; the file's inverse bind
  matrices are returned (in skeleton order) for comparison, while the skeleton derives its own. A non-joint node between two joints is an error, because its transform
  would vanish from the skeleton.
- **Animations** become an `AnimationClip` for a skeleton (`clip(animation, skin)`): translation, rotation and scale channels of the skin's joints; other nodes and
  `weights` channels are ignored. `AnimationClip` interpolates linearly (slerp for rotations) only, so **`STEP` is approximated** by a second key just before the next one
  (so it holds until 0.1% of the step before the next key), and **`CUBICSPLINE` is resampled** at 30 Hz by default (`clip(animation, skin, rate)`), at every key and
  between them by the Hermite formula of the specification. Both are lossy and documented as such.

## Safety

The file is treated as untrusted input:

- every buffer view and accessor is checked with overflow-safe arithmetic before any read, and **an accessor's count is only trusted after that validation** (an
  early version allocated `count * components` floats first; the mutation test below found it: a negative count crashed, a huge one would have run out of memory).
  Accessors without a buffer view are capped at 16 M values;
- JSON nesting is limited to 200 levels (100 000 open brackets raise an error, not a stack overflow), and the node-cycle check is linear;
- a path-based load refuses relative buffer URIs that leave the directory of the file, including percent-encoded `..`;
- every failure is a `GltfException`. **Mutation test:** 300 000 files made by flipping, replacing or truncating bytes of a valid `.gltf` and `.glb` (mesh, skin,
  animation, image), each then fully used (every accessor read, every primitive converted, every skin and clip built), produced only `GltfException` or a valid
  result (run once at that size; the test in the build runs 40 000).

Files that require an extension other than `KHR_mesh_quantization` are rejected (`KHR_draco_mesh_compression`, `EXT_meshopt_compression`).

## What is not there

Morph targets, cameras, lights, `KHR_texture_transform` and other extensions, image decoding, and writing glTF. The tests use files built in the test code (triangle,
interleaved, sparse, normalized, strips, skin, three interpolation modes, GLB and on-disk with an external buffer), **not the Khronos sample assets**, so behaviour on
exporter-specific quirks is untested.
