# Changelog

All notable changes to vmath. Format: [Keep a Changelog](https://keepachangelog.com); versioning policy: `docs/VERSIONING.md`.
Nothing has been released yet; the baseline for the compatibility check is the tag `v0.1.0`.

## Unreleased

### Added
- Quality tooling: JaCoCo coverage with per-package floors in `check`, PIT mutation testing (`mutationTest`), `docs/COVERAGE.md`.
- Publishing setup for `vmath`, `vmath-simd` and `vmath-annotations` (sources and javadoc jars, POM metadata, staging repository check, GitHub Packages), `docs/PUBLISHING.md`.
- `SpatialHash` (cell indices, hashes, packed keys, epsilon-neighbourhood lookup, canonical float keys), `docs/EQUALITY.md`.
- `docs/COOKBOOK.md`: four recipes generated from tests.
- Tests found by mutation testing: `CoverageGapsfTest`/`CoverageGapsdTest`, `ConversionAndIntVecGapsTest`, more Hilbert argument checks.
- Quantization (experimental): `Quantize` (N-bit unorm/snorm, mantissa rounding), `GridQuantizer`, `UvQuantizer`; `VertexLayout` formats `positionUnorm16` and `uvUnorm16` for `MeshExport`.
- New package `vmath.color` (experimental): `Srgb`, `ColorSpaces` (HSV, HSL, Oklab, Oklch), `ToneMap`, `PremultipliedAlpha`.
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
- Tests for the debt register (docs/technical-debt.md): `PublicDocsTest` (every public declaration has a doc comment), `ValueTypeChecker` (the JEP 401 identity rules on the syntax tree), `ShaderCompileTest` (compiles the generated GLSL when glslang is installed), `FormatNumbersTest`, seeded mutation fuzzers for the glTF and KTX2 readers (`LoaderFuzzTest`, `Ktx2FuzzTest`), `FloatElementsTest`, `FastMapsTest`, and an independent light-space oracle for `CascadeCasters`.
- Performance guard: `.github/workflows/perf.yml` (nightly JMH subset) and `scripts/perf_guard.py` (ratios within a run, absolute change against the previous night).
- JaCoCo coverage and floors for `vmath-simd` and `vmath-codegen`.

### Changed
- Maven coordinates are `io.github.andreasnilsson123:vmath` (was `vmath:vmath`) and the version is `0.2.0-SNAPSHOT`; the JPMS module name is unchanged. The japicmp baseline tag is pinned in `gradle/baseline-commits.txt`.
- Build: JUnit 6.1.3, japicmp 0.26.2, JOML 1.10.9 (test oracle), JaCoCo 0.8.15, Gradle 9.8.0; versions live in `gradle/libs.versions.toml`; Dependabot proposes updates; the library and its tests compile with `-Werror`.
- `GpuCullReference.Counters` and `ClusterCullReference.Counters` have explicit public constructors.
- `ClusterHierarchy.build` is about 3.6 times faster on large meshes (14.3 s to 4.0 s for 328k triangles): no more quadratic array copying, primitive hash maps (`FastMaps`, internal).
- `normalize()` of Vec2/3/4 and Quat, and `normalizeOrZero()` of the vectors, now keep the direction of huge and subnormal vectors (before: zeros or infinities).
- `Gltf.SkinData` has `rootTransform`; `MeshSimplifier.Result` has `remap` and `attributes` (experimental APIs).
- `TransformMath.slerp` delegates to `QuatArray.slerp` (same result).
- `Ktx2.parse` refuses a header whose dimensions describe a texture that cannot be addressed (`FormatException`), `TextureLayout` refuses one whose total size exceeds a `long` (`IllegalArgumentException`), and `TextureFormat.blocksWide/blocksHigh` no longer overflow near `Integer.MAX_VALUE` (found by the new loader fuzz tests; before: negative sizes).

- `CascadeCasters` moved from `vmath.spatial` to `vmath.camera` (next to `Cascades`; `spatial` no longer imports `camera`), and the cone computation and single-cluster back-face test are in the new `vmath.geo.NormalCone` (`ConeCull` forwards to it; `mesh` uses it directly). Neither class was in a release.
- `FloatElements` (public, cannot be extended outside the package) is the base class of `Vec3fArray`, `Vec4fArray`, `QuatArray`, `Mat4fArray` and `TransformArray`: the shared methods are inherited instead of repeated. Growth no longer overflows `int` silently for absurd sizes (`OutOfMemoryError` with a message).
- `Gltf` keeps its JSON tree in `JsonObject` and `JsonArray` (package-private), so it needs no unchecked cast, and its accessor-format, JSON-access and clip-resampling helpers are the package-private classes `AccessorFormat`, `JsonAccess` and `ClipResampling`. The JDK numbers of the build are in `gradle.properties`. `BvhQuery` and `DynamicAabbTree` share their node tests (`NodeTests`), and the two SIMD providers share `SimdSupport`.
- `Hilbert.encode2` and `encode3` are table-driven (an automaton over Morton digits): 35 ns per 3D code instead of 104 ns, the same codes; the locality order of 1M points with Hilbert went from 186 ms to 97.6 ms.

### Deprecated
- `vmath.gl.Std140`, for removal: use `GlslType`, `StructLayout`, `GpuWriter` or a generated `@GpuStruct` writer.
