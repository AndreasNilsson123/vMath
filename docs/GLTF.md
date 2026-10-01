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

## Skinned meshes from real exporters: the armature

Exporters (Blender, for one) put an "Armature" node above the root joint, usually with a rotation. The file's vertices and inverse bind matrices include it, while a
`Skeleton` and the animation clips are local to the joints. `SkinData.rootTransform` is the world matrix of the nodes above the skeleton (identity when there are
none; all root joints must hang from the same one). To skin the vertices of the file: take `Skinning.worldMatrices(skeleton, pose)`, multiply each by the **file's**
inverse bind matrix (`SkinData.inverseBindMatrices`, in skeleton order, not the skeleton's own), skin the positions with those, and transform the result by
`rootTransform`. The test `skinningTheFileMatchesTheIndependentOracleAtRestAndBent` does exactly this and compares with a double-precision implementation that
shares no code with the library: the rest pose reproduces every vertex (within 2e-5) and the bent pose at four times stays within 3e-4 of the oracle. The file's inverse bind
matrix equals the skeleton's own times the inverse of `rootTransform` (also tested), which is why the two must not be mixed.

## Real-looking test assets

`src/test/resources/assets` holds files generated by `src/test/java/vmath/assets/AssetFactory` (deterministically; a test fails if the committed files differ from
the generator, and `-Dvmath.writeAssets=src/test/resources/assets` regenerates them). They are laid out the way exporters do it, to find what hand-made minimal files
cannot: `skinned_tube.glb` (an armature node rotated -90 degrees about x above a four-bone chain listed in scrambled order, interleaved position and normal with a stride of 24,
a tangent stream, a second UV set as normalized unsigned shorts, byte joints, float weights, vertex colours, a PNG in a buffer view, an unknown optional extension, `extras`,
and two animations with linear, step and cubic-spline curves), `quantized_sphere.glb` (`KHR_mesh_quantization` as a required extension: normalized shorts for positions with a
node scale, normalized bytes for normals with 3 bytes per element at a stride of 4, normalized unsigned shorts for UVs, 32-bit indices) and `scene/` (an external `.bin`
and `.png`, a data-URI image, a triangle strip, a fan without indices, a sparse accessor, a morph target and mesh weights, a camera and a light that must be ignored, two scenes,
a node given as a matrix). The PNGs come from a small deterministic encoder that the tests check against the JDK's decoder.

They are written by the same author as the loader, so they test the loader against the specification as understood, not against other people's exporters; opening them in
the Khronos glTF validator or any viewer is an independent check that has not been done here (no network). The Khronos sample assets were still not used.

## What is not there

Morph targets, cameras, lights, `KHR_texture_transform` and other extensions, image decoding, and writing glTF. The tests use files built in the test code (triangle,
interleaved, sparse, normalized, strips, skin, three interpolation modes, GLB and on-disk with an external buffer), **not the Khronos sample assets**, so behaviour on
exporter-specific quirks is untested.
