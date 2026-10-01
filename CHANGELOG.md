# Changelog

All notable changes to vmath. Format: [Keep a Changelog](https://keepachangelog.com); versioning policy: `docs/VERSIONING.md`.
Nothing has been released yet; the baseline for the compatibility check is the tag `v0.1.0`.

## Unreleased

### Added
- Bulk and memory (experimental): `Vec4fArray`, `SegmentFloatArray` (off-heap), `removeSwap`/`compact` on every container, `Mat4fArray.multiply`/`premultiply`, `QuatArray.nlerp`, `MatrixKernel` SPI with a Vector API kernel in `vmath-simd`, `DirtyRanges`, `FrameDirtyRanges`, `HandleRegistry`.
- New package `vmath.mem` (experimental): `ArenaAllocator`, `SlabAllocator`, `FreeListAllocator`, `RingAllocator`, `PersistentBufferRing`.
- Sorting (experimental): `RadixSorter`, `PrefixSum`, `LocalityOrder`; `Hilbert` codes and 32-bit `Morton`/`Hilbert` codes.
- Camera (experimental): `PlanarViews` (reflections, oblique near-plane clipping, portal views), `Stereo`, `DualParaboloid`.
- GPU (experimental): `VertexFormat`, `VertexBufferLayout`, `VertexLayout.toBufferLayout()`, `ShaderHeader` (GLSL/Slang headers from layouts), `LayoutValidator`.
- Clustered lighting (experimental): `ClusterGrid`, `ClusterLights` (CPU reference of light assignment, tiled variant), `ClusterLight` struct.
- GPU-driven culling (experimental, new package `vmath.gpucull`): layouts, `HiZPyramid`, `GpuCullReference`, `ClusterCullReference`, `GpuCullGlsl`; `DrawCommandBuffer.baseInstance`.
- MIT `LICENSE`.
- Core: `ClipSpace` (OpenGL, Vulkan, D3D) and `Mat4` projection overloads for it, `flipY`; `Vec4`, `Mat3`, `Mat4x3` operations found missing by the parity test;
  `toFloat` on the shapes.
- Geometry: `Segment`, `Capsule`; segment-segment, segment-box, sphere/capsule/box-capsule, ray-capsule, OBB-OBB, box-triangle, ray-OBB, sphere-OBB, plane-OBB,
  plane-triangle and sphere-sweep intersections.
- GPU: `DrawArraysIndirect`, `DrawElementsIndirect`, `DispatchIndirect`, `DrawCommandBuffer`, `InstanceWriter` (including `writeVisibleTranslations`).
- Bulk: `Vec3fArray`, `QuatArray`, `TransformArray`; `Strided` and strided/byte-order `writeTo`/`readFrom` on the containers.
- `MeshSimplifier` can take per-vertex attributes (skin weights) and locked vertices; generated exporter-style test assets (`src/test/resources/assets`) and the tests that read them.
- `docs/ROBUSTNESS.md`, the degenerate-input sweep and contract tests.
- `@Experimental` marker and the versioning policy.
- Meshes (all experimental): `Overdraw` (software overdraw measure and cluster reordering), `MeshSimplifier` (quadric error metrics), `Meshlets`, `MeshLod`
  (discrete LOD chain), `ClusterHierarchy` (continuous LOD cluster DAG), `RectPacker` and `UvAtlas` (planar chart unwrap with atlas packing), `Mesh.copy()`.
- Textures (`vmath.tex`, experimental): `TextureFormat`, `TextureLayout`, `CubeFace`, `Ktx2` header and level index.
- glTF (`vmath.gltf`, experimental): `Gltf` loader for `.gltf` and `.glb` (meshes, materials, nodes, skins to `Skeleton`, animations to `AnimationClip`).
- Tests: allocation contract (`AllocationContractTest`), API parity (`ApiParityTest`); Javadoc lint in `check`.
- Benchmarks: `BulkBench`, `FrameBench`, `InstanceWriteBench`; `CullAndDrawSample` (`./gradlew :vmath-bench:sample`).

### Changed
- `ClusterHierarchy.build` is about 3.6 times faster on large meshes (14.3 s to 4.0 s for 328k triangles): no more quadratic array copying, primitive hash maps (`FastMaps`, internal).
- `normalize()` of Vec2/3/4 and Quat, and `normalizeOrZero()` of the vectors, now keep the direction of huge and subnormal vectors (before: zeros or infinities).
- `Gltf.SkinData` has `rootTransform`; `MeshSimplifier.Result` has `remap` and `attributes` (experimental APIs).
- `TransformMath.slerp` delegates to `QuatArray.slerp` (same result).
