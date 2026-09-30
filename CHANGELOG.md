# Changelog

All notable changes to vmath. Format: [Keep a Changelog](https://keepachangelog.com); versioning policy: `docs/VERSIONING.md`.
Nothing has been released yet; the baseline for the compatibility check is the tag `v0.1.0`.

## Unreleased

### Added
- Core: `ClipSpace` (OpenGL, Vulkan, D3D) and `Mat4` projection overloads for it, `flipY`; `Vec4`, `Mat3`, `Mat4x3` operations found missing by the parity test;
  `toFloat` on the shapes.
- Geometry: `Segment`, `Capsule`; segment-segment, segment-box, sphere/capsule/box-capsule, ray-capsule, OBB-OBB, box-triangle, ray-OBB, sphere-OBB, plane-OBB,
  plane-triangle and sphere-sweep intersections.
- GPU: `DrawArraysIndirect`, `DrawElementsIndirect`, `DispatchIndirect`, `DrawCommandBuffer`, `InstanceWriter` (including `writeVisibleTranslations`).
- Bulk: `Vec3fArray`, `QuatArray`, `TransformArray`; `Strided` and strided/byte-order `writeTo`/`readFrom` on the containers.
- `@Experimental` marker and the versioning policy.
- Meshes (all experimental): `Overdraw` (software overdraw measure and cluster reordering), `MeshSimplifier` (quadric error metrics), `Meshlets`, `MeshLod`
  (discrete LOD chain), `ClusterHierarchy` (continuous LOD cluster DAG), `RectPacker` and `UvAtlas` (planar chart unwrap with atlas packing), `Mesh.copy()`.
- Textures (`vmath.tex`, experimental): `TextureFormat`, `TextureLayout`, `CubeFace`, `Ktx2` header and level index.
- glTF (`vmath.gltf`, experimental): `Gltf` loader for `.gltf` and `.glb` (meshes, materials, nodes, skins to `Skeleton`, animations to `AnimationClip`).
- Tests: allocation contract (`AllocationContractTest`), API parity (`ApiParityTest`); Javadoc lint in `check`.
- Benchmarks: `BulkBench`, `FrameBench`, `InstanceWriteBench`; `CullAndDrawSample` (`./gradlew :vmath-bench:sample`).

### Changed
- `TransformMath.slerp` delegates to `QuatArray.slerp` (same result).
