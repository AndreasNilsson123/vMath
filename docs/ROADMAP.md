# vmath roadmap

Goal: a complete 3D graphics foundation library for Java/LWJGL that is **extremely fast**, **memory-efficient**
and **feature-complete** (math, geometry, culling, spatial structures, GPU interop, mesh/animation support).

Priorities: **P0** blocks everything else, **P1** core of the promise, **P2** completeness, **P3** nice to have.
Sizes: S ≈ hours, M ≈ days, L ≈ 1–2 weeks, XL = multi-week. `→` marks dependencies.

---

## 1. Where we are

The first milestones of the backlog are done; the remaining boxes below are the roadmap. In numbers (October 2026): about 23,000 lines of hand-written main code and 4,300 lines of float templates (the generator emits the double
twins), 15 packages in one JPMS module (`vmath.core`, `geo`, `bulk`, `spatial`, `camera`, `occlusion`, `mesh`, `anim`, `gltf`, `tex`, `pack`, `gl`, `gpucull`, `mem`, `color`), three further modules (`vmath-annotations`, `vmath-codegen`, `vmath-simd`) and the benchmarks;
108 test sources with JOML and brute-force oracles, a zero-allocation contract test, 96.8% of lines and 90.7% of branches covered, and a mutation-tested core (`docs/COVERAGE.md`); CI on Linux and Windows with a nightly random-seed build and a Valhalla job;
an API-compatibility check against the tagged baseline. What is measured is in `docs/PERFORMANCE.md`, `BULK.md`, `MEMORY.md`, `GPU.md`, `COLOR.md`; what is known to be weak is in `docs/technical-debt.md`. The findings of the first review of the code, which this backlog was written
to answer, are in `docs/history.md`.

The demos have their own backlog and structure in [DEMOS.md](DEMOS.md).

**What is not proven yet:** nothing in the GPU-facing layer (shader text, layout validation against drivers, the persistent upload ring) has run against a real graphics API (`docs/technical-debt.md` TD-01).

---

## 2. Architecture and rules

Modules (INF-6, done 2026-10-03: the library is four modules and an aggregate, built from `gradle/vmath-module.gradle.kts`; the tests span the parts and stay in the root project):

| Module (Gradle project) | Packages | Needs |
|---|---|---|
| `vmath-annotations` | `@GenerateDouble`, `@FloatOnly`, `@DoubleOnly`, `@ValueType`, `@Experimental`, ... (build-time markers, `requires static`) | |
| `vmath-codegen` | the generator tool (not published) | |
| `vmath-validator` | the value-type validating annotation processor (not published) | |
| `vmath-core` | `core`, `mem`, `color`, `tex` | |
| `vmath-geo` | `geo`, `pack`, `physics` | core |
| `vmath-scene` | `bulk`, `spatial`, `occlusion`, `anim`, `gl`, `util` | core, geo |
| `vmath-render` | `camera`, `mesh`, `gltf`, `gpucull` | core, geo, scene |
| `vmath` | no packages: the aggregate module, `requires transitive` all four | all |
| `vmath-simd` | optional Vector API kernels (needs `--add-modules jdk.incubator.vector`) | scene |
| `vmath-bench` | JMH suite (not published) | all |

**Performance and memory contract** (write it in `docs/PERFORMANCE.md`, enforce in tests):
1. Single objects → immutable values (JIT escape analysis today, Valhalla later).
2. Anything that scales with entity count (>~64 elements) → `float[]` / `MemorySegment` SoA, zero per-element objects.
3. Hot-path APIs allocate nothing. Every kernel has a benchmark with `-prof gc` showing 0 B/op.
4. Culling and other predicates are **conservative** (never false-negative) and documented with their error bounds.
5. Every public op has a documented NaN/degenerate-input policy.

---

## 3. Task backlog

### Phase A. Annotation framework and generator (P0)

Design in one sentence: replace magic comments with real, compiler-checked annotations, and replace the regex
generator with an AST-based one that treats **all** precision/value-type variants as one pipeline.

Annotations (`vmath.annotations`, `@Retention(SOURCE)`):

| Annotation | Replaces | Meaning |
|---|---|---|
| `@GenerateDouble` on a type | filename regex in `GenDouble` | Emit a `d` twin; supports `rename = {...}` overrides |
| `@FloatOnly` / `@DoubleOnly` on members | `@float-only-begin/end`, `DOUBLE_EXTRAS` | Member exists in one precision only |
| `@Eps(float = 1e-4, double = 1e-11)` on fields | `// @eps-double` | Per-precision constant/tolerance |
| `@ValueType` on records | `/*value*/` comment + Gradle line filter | Emit `value record` in Valhalla profile |
| `@PrecisionSpecific` / `Scalar` helper class | inline `Math.fma`, `Float.MIN_NORMAL` special cases | Explicit per-precision hooks |
| `@Bulk` / `@Kernel` (later, see Phase D) | hand-written SoA loops | Generate SoA container + loops from the scalar op |
| `@GpuStruct` (later, see Phase F) | hand-written `Std140.put*` calls | Generate layout-correct writers |

Tasks:
- [x] **AF-1 (P0, M)** Create `vmath-annotations` with the annotations above, javadoc'd, with a compile-only test.
- [x] **AF-2 (P0, L)** New generator on the JDK compiler Tree API (`com.sun.source`, no third-party deps) or JavaParser.  
      *Done: JDK compiler Tree API, position-based edits.*
      Transform the AST, not lines: retype float→double by resolved symbol, skip comments/strings, fix literals,
      rename types, honor annotations. Emit stable, formatted output. → AF-1
- [x] **AF-3 (P0, M)** Make double-only members real code: move `DOUBLE_EXTRAS` into the source as `@DoubleOnly` members  
      *Done: `@DoubleOnly` members are copied verbatim (decided: option a variant, no sidecars needed).*
      (solve the "must compile in the float file" issue with a sidecar mixin file per type or by putting them in
      the double-side of a `Vec3` template; decide in a spike, see open question 1).
- [x] **AF-4 (P0, S)** Package discovery from annotations instead of a hard-coded directory/regex, so `geo`,
      `bulk`, `spatial` types are picked up automatically.
- [x] **AF-5 (P0, M)** Fold Valhalla rewriting into the same generator (profile = precision × value/plain). Delete the  
      *Done: `--valhalla` profile of the same generator.*
      Gradle `filter` and the `/*value*/` string checks. → AF-2
- [x] **AF-6 (P1, M)** Generate **at build time** into `build/generated/` and stop checking in `*d.java`  
      *Done: generated into `build/generated/sources/vmath`, nothing checked in.*
      (removes stale-check and merge noise). Keep IDE support via generated source set. → AF-2
- [x] **AF-7 (P1, M)** Same pipeline for tests: `*fTest` → `*dTest`, epsilons from `@Eps`. Add a generator self-test  
      *Done: `vmath-codegen` has golden tests for every rule and diagnostic.*
      (golden files for tricky constructs: casts, literals in comments, `Math.fma`, buffers).
- [x] **AF-8 (P1, M)** Generator diagnostics: unknown annotation values, a `@FloatOnly` member referenced from
      non-float-only code (would break the double build), missing `@Eps` on a tolerance constant. Errors point at the
      source line.
- [x] **AF-9 (P2, M)** Optional: annotation processor that *validates* (readiness rules, no `==` on value types,
      no `synchronized`) at compile time, replacing `ValhallaReadinessTest`'s string heuristics.  
      *Done: `vmath-validator` (`ValueTypeProcessor`) runs on the attributed tree in the main compile; the text-based checker is removed. It does not check code that uses the library from a jar (the marker has source retention); `docs/CODEGEN.md`.*
- [x] **AF-10 (P2, S)** Document the framework in `docs/CODEGEN.md` with a "how to add a new type" walkthrough.  
      *Done: `docs/CODEGEN.md`.*

### Phase B. Build, CI and quality infrastructure (P0/P1)

- [x] **INF-1 (P0, M)** JMH module `vmath-bench` with `-prof gc` runs; baseline for mul/invert/transform/normalize.  
      *Done: `vmath-bench`, `CoreBench` and `CullBench`.*
      Verify the "no allocation" claim before building on it.
- [x] **INF-2 (P0, S)** GitHub Actions: JDK matrix (LTS baseline + latest), Windows/Linux, Valhalla EA job (allowed to fail).  
      *Done: `.github/workflows/ci.yml`, incl. nightly seed and Valhalla EA job.*
- [x] **INF-3 (P1, S)** Add allocation regression test (`ThreadMXBean.getThreadAllocatedBytes`) for hot paths in unit tests.  
      *Done: `Alloc` helper and `AllocationContractTest` (scalar frustum kernel, pipeline, BVH/dynamic tree/grid/octree queries and updates, occlusion, LOD/cone/shadow/light culling, transform hierarchy, sampling, blending, skinning). Value-record-returning APIs, offline mesh tools and the executor hand-off are excluded and listed in the test.*
- [x] **INF-4 (P1, S)** Decide baseline JDK (21 vs current LTS). FFM final and useful for `MemorySegment` APIs from 22.  
      *Done: baseline is JDK 25.*
      Multi-release or a bump. (Open question 2.)
- [x] **INF-5 (P1, M)** JPMS `module-info.java` per module, `japicmp` API-compat check, javadoc with `-Xdoclint`, LICENSE.  
      *Partial: JPMS done for `vmath`, `vmath.annotations` and `vmath.codegen` (`vmath` requires the annotations `static`, so
      it needs only `java.base` at run time), verified against the built jar by `ModuleDescriptorTest`. `vmath-bench` stays
      non-modular (JMH's generated code is not). `japicmp` is done (`docs/API-COMPAT.md`, baseline tag `v0.1.0`). Javadoc is linted by `check` (`-Xdoclint:all,-missing` with warnings as errors: broken references, bad HTML, malformed tags); checking for *missing* comments and record `@param` tags is open, and the LICENSE is MIT (added 2026-10, the owner's decision).*
- [x] **INF-6 (P1, M)** Restructure into multi-project Gradle build per the module table. → AF-2  
      *Done 2026-10-03: `vmath-core`, `vmath-geo`, `vmath-scene`, `vmath-render` and the aggregate `vmath` (table in section 2). The code generator runs per module, told where the templates and `@GpuStruct` records of the modules below are (`--family-templates`, `--gpu-register`); the japicmp baseline stays the one jar of `v0.1.0`, compared with the jars of the four parts together; each part is published, and the aggregate publishes a POM that depends on all four. The grouping is the layering measured by `PackageLayeringTest` (which also checks that no package uses a package of a module above it). Choices: the tests moved with the code (done 2026-10-03, nothing is left under the root `src/`): each test or test template lives in the lowest module that has everything it uses, worked out from its imports and the test classes it uses, and sees the test classes of the modules below (the helpers `Rnd`, `Alloc`, `Report` are in `vmath-core`); the tests that look at the whole library (allocation contract, API parity, module descriptors, layering, doc references, cookbook, the glTF tests) are in `vmath-render`, and the test assets are in `vmath-core`, next to `AssetFactory`. The aggregate is the project `vmath-all` published as `vmath`; the root project has no sources and holds the merged coverage, the mutation tests and the compatibility check; `scene` is a wide module of six packages: they form a chain (`bulk` below `spatial`, `anim` and `gl`; `occlusion` above `spatial`; `util` above `anim`), so a finer split is possible, but it gives modules of one or two packages for little gain. No class was renamed and no package was split between modules, so `import` lines in consumer code are unchanged; consumers that wrote `requires vmath` still get everything. Open: nothing in the library is blocked on a finer split of `scene`.*
- [x] **INF-7 (P2, M)** Fuzzing/degenerate suite: zero vectors, denormals, NaN/Inf, huge magnitudes, near-singular
      matrices. Explicit expected behavior per op.  
      *Done for the 14 core types: `DegenerateInputSweepTest` (257 200 reflective calls, fails on unexpected exceptions or hidden NaN), `DegenerateContractfTest` (explicit cases, both precisions), `docs/ROBUSTNESS.md`. Found and fixed `normalize` of huge and tiny vectors. Shapes, meshes and bulk arrays are not covered.*
- [x] **INF-8 (P2, M)** *(done: the BVH, dynamic tree, grids, octree and culling stages are tested against brute-force references in `BvhTest`, `DynamicAabbTreeTest`, `UniformGridTest`, `LooseOctreeTest`, `CullingTest`, the hull, GJK and bounding volumes against independent oracles, and every float template has its generated double twin as a cross-precision check)* Oracle strategy for features JOML lacks (BVH, culling): brute-force reference implementations
      in tests, analytic cases, cross-precision (f vs d) comparison.
- [x] **INF-9 (P2, S)** Code coverage (JaCoCo) and mutation testing (PIT) on `core`.
      *Done: JaCoCo in the build (`jacocoTestReport`, `coverageSummary`, per-package floors in `check`; 96.6% of lines and 90.5% of branches measured), and PIT as `mutationTest` on any classes. PIT on `vmath.core` killed 95.3% of 4,758 production mutants on the first run and 98.4% of 4,845 after the tests it pointed to were added. See `docs/COVERAGE.md`.*
- [x] **INF-10 (P3, S)** Publish to Maven Central/GitHub Packages with sources and javadoc jars.
      *Done as build setup, nothing published: `vmath`, `vmath-simd` and `vmath-annotations` publish jar, sources jar, javadoc jar, POM (licence, developer, SCM) and module metadata to a staging repository that `verifyPublication` checks, and to GitHub Packages with credentials from the environment; signing activates when `SIGNING_KEY` is set. The group id, the credentials and the Maven Central namespace and upload are yours to settle. See `docs/PUBLISHING.md`.*

### Phase C. Core math completeness (P1)

Consistency first, then features. All new features are written once in float and generated.

- [x] **CORE-1 (P1, M)** API parity audit: every applicable op on every type (table in `docs/API.md`), plus a test that  
      *Done: `ApiParityTest` (reflection over required-operation lists for vectors, matrices, quaternions and shapes, plus float/double twin parity). The gaps it found were filled: `Vec4` angle/reflect/refract/splat, `Mat3` row/fromArray, `Mat4x3` rotations and `transform(Vec4)`, `Sphere`/`Ray` closestPoint, `Triangle.transform`, and `toFloat` on the five geometry double types. Intentional exceptions are listed in the test and in `docs/API.md`.*
      fails if a type is missing an op present on its siblings (reflection over a required-ops list).
- [x] **CORE-2 (P1, M)** Scalar/vector toolbox: `clamp, saturate, sign, floor, ceil, fract, mod, step, smoothstep, mix,
      remap, reflect, refract, project, reject, faceForward, orthonormalBasis, min/max component, isFinite, isZero`.
- [x] **CORE-3 (P1, M)** `Quat`: from matrix, from-to (shortest arc), euler (all 12 orders), look rotation, swing-twist,  
      *Done: `fromMat3`, `fromTo`, `fromEuler`/`toEuler` for all 12 orders (`EulerOrder`), `lookRotation`, swing/twist, `log/exp/pow`, `squad`, `integrate`, `axis`.*
      `log/exp/pow`, `squad`, angular-velocity integration, `toEuler`.
- [x] **CORE-4 (P1, M)** *(done: `ortho`, `orthoReversedZ`, `frustum`, `perspectiveInfinite`, `invertProjection`, `lookTo`, `rotationAxis`, `shear`, `decomposeWithShear`, `isAffine`, `isOrthonormal`, `isProjection`; see `docs/API.md`)* `Mat4`: `ortho`, `orthoReversedZ`, `frustum`, `perspectiveInfinite`, inverse projection,  
      *Partial: `frustum`, `perspectiveInfinite`, `lookTo`, `rotationX/Y/Z/Axis`, `decompose`, `isAffine` done; inverse projection and shear open.*
      `lookTo`, `rotationAxis`, `shear`, TRS decomposition (with negative-scale and shear handling), `isAffine`, `isOrthonormal`.
- [x] **CORE-5 (P1, L)** `Transform` (TRS) and `Mat4x3` affine (48 B instead of 64 B, ~25% less bandwidth, cheaper multiply/invert).  
      *Done: `Transformf`/`Transformd` (composition without matrices, non-uniform scale limits documented in its class comment) and `Mat4x3f`/`Mat4x3d` (`mul`, `invert`, `writeTo`).*
      Transform composition without matrices. Non-uniform scale semantics documented.
- [x] **CORE-6 (P1, M)** *(done: `Vec2i`, `Vec3i` and `Vec4i`; long variants were judged unnecessary, `pack()` gives a `long` key)* `Vec2i/3i/4i` (+ long variants if needed): grid coordinates, hashing, min/max, conversions.  
      *Hand-written (not generated like the float and double vectors), tested by `IntVecTest`; `Vec4i` is done, long variants were judged unnecessary.*
- [x] **CORE-7 (P2, M)** `Mat2`, `Mat2x3`/`Mat3x2` (2D transforms), 2D helpers (rotate, perp-dot, winding). *Done: `Mat2f` and `Mat3x2f` (with double twins), `Vec2f.perpDot`, `signedAngle`, `rotateAround`, `orient`; winding is `Polygons.winding`; see `docs/API.md`.*
- [x] **CORE-8 (P2, M)** Dual quaternions, `Pose` (Quat + Vec3, rigid-only, 28 B), rigid inverse/compose fast paths.
      *Done: `DualQuatf` (composition, inverse, normalise, linear blending `nlerp`, screw interpolation `sclerp` and `pow`) and `RigidTransformf` (rotation and translation, 28 bytes, exact inverse and product without a division; named so because `Pose` is the skeleton pose of `vmath.anim`), with double twins, and dual quaternion skinning in `Skinning`. Tested against the matrices and a known screw motion; skinning 2.6 times the cost of linear blending, measured. See `docs/API.md` and `docs/ANIMATION.md`.*
- [x] **CORE-9 (P2, M)** Fast math: polynomial `sin/cos/atan2/acos/exp/log`, fast `invSqrt`, documented max error each,
      with tests against `Math`. Opt-in `FastMath` class, never silently substituted.  
      *Done (experimental): `FastMath` with `sin`, `cos`, `atan`, `atan2`, `acos`, `asin`, `exp`, `log` (documented and tested bounds, 1.25 to 2.5 times faster than `Math`); `invSqrt` was built, measured twice as slow as `1f / (float) Math.sqrt(x)` and removed. See `docs/FASTMATH.md`.*
- [x] **CORE-10 (P2, M)** Robustness: `Predicates` (orient2d/3d, incircle/insphere, adaptive-precision), `DoubleDouble` type,
      stable `normalize` / angle-between / cross-based formulas.  
      *Done (experimental): `Predicates` (exact sign: floating-point filter, then expansion arithmetic; checked against `BigDecimal`), `DoubleDouble` (32 digits), and `Quat.angle()` made stable (`normalize` and the vector angles already were). Two stages rather than Shewchuk's four. See `docs/ROBUSTNESS.md`.*
- [x] **CORE-11 (P2, S)** Consistent `hashCode`/`equals` semantics doc (`-0.0`, NaN) and epsilon-hash helpers for spatial hashing.  
      *Done: `docs/EQUALITY.md` and `EqualityContractTest` (record `equals`/`hashCode` on every type and component, both precisions: -0.0 differs from 0.0, every NaN equals every NaN), and `SpatialHash` (cell indices, hashes, exact packed keys, epsilon-neighbourhood lookup with a tested guarantee, canonical float keys).*
- [ ] **CORE-12 (P3, M)** Optional `ToString`/`fromString` formats, `Vec.parse`, debug formatter for matrices.
- [x] **CORE-13 (P2, M)** Transform-space utilities: frame-tagged transforms, `Geodetic/Ecef/WGS-84` (from the existing "next steps"),
      camera-relative rendering helpers beyond `relativeTo` (rebasing a whole scene, double→float model matrices).
      *Done: `Frame` and `FrameTransformf`/`d` (a transform that knows its source and target frame, refuses composition of the wrong frames); `Geodetic` and `Wgs84` (geodetic, ECEF and local East-North-Up conversions in double, tested for round trips and against the closed form); `relativeTo` on the matrices and transforms, `Rebase` (double world data to camera-relative float arrays, and the shift of relative data) and `FloatingOrigin` (an origin that follows the camera in grid steps). Measured: 1 ns per position and 12 ns per matrix rebased, `toEcef` 62 ns, `toGeodetic` 378 ns. See `docs/LARGE_WORLDS.md`.*

### Phase D. Bulk data and memory efficiency (P1)

- [x] **MEM-1 (P1, L)** `vmath-bulk` SoA containers (`Vec3fArray`, `Vec4fArray`, `QuatArray`, `Mat4fArray`, `TransformArray`)  
      *Done (experimental additions): `Vec4fArray`, `SegmentFloatArray` (off-heap twin with typed accessors and `AutoCloseable` growth), `removeSwap` and `compact(VisibilitySet)` on every container, kernels that write straight into a `MemorySegment`. Containers over `float[]` keep their own classes (`docs/BULK.md`).*
      over `float[]` (heap) and `MemorySegment` (off-heap) with one interface-free API pair. Growth policy, capacity, compaction.
- [x] **MEM-2 (P1, L)** Kernels: batch transform points/normals, matrix multiply, TRS compose, quaternion normalize/slerp,  
      *Done: scalar kernels for transform of positions, directions and four-component vectors, normalize, quaternion multiply, slerp, nlerp, toMatrices, blend, AABB transform, `Mat4fArray.multiply`/`premultiply`. Vector API variants (`vmath-simd`, selected at startup through the `MatrixKernel` SPI) where they won, measured: matrix product 1.8 times faster, premultiply 2.4, position transform 2.1, vec4 transform 2.7, quaternion normalize 1.3. A gather-based AABB transform was slower than scalar (973 us against 825 us) and was dropped; slerp, toMatrices and quaternion multiply were not vectorised. See `docs/BULK.md`.*
      AABB transform. Scalar versions first, then Vector API (incubator) variants selected at startup. → MEM-1, INF-1
- [x] **MEM-3 (P1, M)** Generate the SoA container and scalar loops from the scalar ops via `@Kernel`/`@Bulk`, so
      new ops don't need hand-written loops. → AF-2
      *Done: `@Bulk` (with `name` and `uniform`) on a method of a template record generates `<Type>Bulk` and its double twin with an interleaved and a planar loop per method, from the body of the method, so the results are bit-identical; the vector, quaternion and matrix operations of the templates are marked. The containers over `float[]` stay hand-written (interleaved), so "the SoA container" of the task is the planar form of the loops, not a new container class. Measured: the generated interleaved loop for a matrix times 100 000 points is as fast as the hand-written scalar kernel (182 us against 206 us), the planar form is not faster for add and cross. See `docs/CODEGEN.md`.*
- [x] **MEM-4 (P1, M)** `writeTo`/`readFrom` for `ByteBuffer` (with `ByteOrder`), `MemorySegment`, and strided/interleaved variants.  
      *Done: `FloatBuffer` writers on the containers; `MemorySegment` and `ByteBuffer` strided and byte-order writers and readers (`Strided`, container `writeTo`/`readFrom`); interleaving is a stride and an offset per container, so a vertex or instance buffer is filled attribute by attribute (`docs/BULK.md`); `MeshExport` writes named vertex layouts for meshes.*
      Interleaved vertex writers for common layouts.
- [x] **MEM-5 (P1, M)** Allocators: arena, slab/pool, free-list and ring allocators over `MemorySegment`; persistent-mapped
      buffer ring (N frames in flight) with fence tracking hooks.
      *Done (experimental): `vmath.mem` with `ArenaAllocator`, `SlabAllocator`, `FreeListAllocator` (first and best fit, coalescing), `RingAllocator` and `PersistentBufferRing` with `FenceOps` hooks. Tested with random simulations and a simulated GPU; not run against a real OpenGL or Vulkan binding. See `docs/MEMORY.md`.*
- [x] **MEM-6 (P2, M)** Handle/generation-index registry (sparse set) for entity IDs, with dense array iteration.
      *Done (experimental): `HandleRegistry` (generation handles, swap-remove dense range, `denseIndex`/`handleAt`). See `docs/BULK.md`.*
- [x] **MEM-7 (P2, M)** Dirty-flag/change-tracking bitsets for incremental GPU upload (upload only changed ranges).
      *Done (experimental): `DirtyRanges` (bitset, gap-merged runs, `uploadFloats`/`uploadSegment`) and `FrameDirtyRanges` (one set per frame in flight). 5 to 12 times faster than uploading everything at 1% to 10% dirty. See `docs/BULK.md`.*
- [x] **MEM-8 (P2, M)** Radix sort (float keys, 32/64-bit) and parallel-friendly prefix sums, for draw sorting and BVH build.
      *Done (experimental): `RadixSorter` (int/long/float/double keys, optional payload, stable, descending floats), `PrefixSum` (scans and chunked parallel scan), `LocalityOrder`. 3.5 to 8 times faster than the JDK sorts for float keys from 100 000 elements; not a clear win for 64-bit keys at a million; the parallel scan gains only on multi-million arrays. See `docs/BULK.md`.*
- [x] **MEM-9 (P3, M)** Thread-parallel kernel driver (`ForkJoin`/virtual-thread-free) with chunking and false-sharing avoidance.  
      *Done for frustum culling: `ParallelFrustumKernel` (caller-supplied executor, chunks at multiples of 64, bit-identical, 3x at 8 chunks); other kernels do not exist in bulk form yet.*

### Phase E. Compact and packed formats (P1/P2)

- [x] **FMT-1 (P1, M)** `Half` (float16) conversion, bulk convert, `Vec2h/3h/4h` as packed storage only.  
      *Done: `vmath.pack.Half` over the JDK's `Float.floatToFloat16`, bulk arrays and 2/4-in-a-word packing; exhaustively tested over all 65536 patterns. Dedicated `Vec2h/3h/4h` types were not needed and are not built. See `docs/FORMATS.md`.*
- [x] **FMT-2 (P1, M)** Normalized/packed types: `snorm/unorm 8/16`, `RGB10A2`, `R11G11B10F`, `RGB9E5`, with GL/Vulkan format tokens.  
      *Done: `Norm`, `SmallFloat`, `PackedFormat` (tokens verified against the Khronos headers).*
- [x] **FMT-3 (P1, M)** Octahedral normal/tangent encoding (2×16 or 2×8 bit), tangent-frame-as-quaternion (smallest-three, 32 bit).  
      *Done: `Octahedral` with best-of-four rounding and `QuatPacked`; measured error bounds are asserted in the tests.*
- [x] **FMT-4 (P2, M)** Position quantization with bounds (`unorm16` × AABB), meshopt-style attribute quantization.  
      *Done (experimental additions): `Quantize` (N-bit unorm/snorm, mantissa rounding), `GridQuantizer` (1 to 16 bits, per-axis or cubic cells), `UvQuantizer`, and the `positionUnorm16`/`uvUnorm16` mesh export formats with their dequantization data. The vertex and index buffer compression codecs of meshoptimizer are not built. See `docs/FORMATS.md`.*
- [x] **FMT-5 (P2, S)** Morton/Hilbert codes (2D/3D, 32/64-bit) for locality sorting and BVH/linear-octree construction.  
      *Done (experimental additions): `Hilbert` (2D to 32 bits per axis, 3D to 21, hierarchy and neighbour properties tested), 32-bit `Morton`/`Hilbert` codes. Hilbert encode is 35 ns (6 times Morton) since the table-driven rewrite, from 104 ns. See `docs/BULK.md`.*
- [x] **FMT-6 (P2, S)** Color: sRGB↔linear (exact + fast), HSV/HSL/Oklab, luminance, tone-map curves, premultiplied alpha.
      *Done (experimental): `vmath.color` with `Srgb` (exact and fast transfer functions, 8-bit tables), `ColorSpaces` (HSV, HSL, Oklab, Oklch, luminance, Oklab mixing), `ToneMap` (Reinhard, extended, ACES fit, Hable, exposure, by luminance), `PremultipliedAlpha`. No wide-gamut spaces or gamut mapping. See `docs/COLOR.md`.*

### Phase F. GPU interface (P1)

- [x] **GPU-1 (P1, L)** Layout framework `std140` / `std430` / `scalar` / tight, driven by a `@GpuStruct` annotation:  
      *Done: `GlslType`/`StructLayout`/`GpuWriter` for std140, std430 and scalar, and `@GpuStruct` generated `<Name>Gpu` writers with GLSL text; see `docs/GPU.md`.*
      generate layout computation, size/alignment constants and writers (scalars, vec2/3/4, mat2/3/4, arrays, nested structs). → AF-2
- [x] **GPU-2 (P1, S)** Layout validator that compares the generated layout to reflection data from the shader
      (SPIR-V reflection or GL introspection) in an opt-in test.
      *Done (experimental): `LayoutValidator` compares a layout with reflection data supplied by the caller; checked against hand-derived reflection, not yet against a real driver. See `docs/GPU.md`.*
- [x] **GPU-3 (P1, M)** Indirect draw command structs (`DrawArraysIndirect`, `DrawElementsIndirect`, multi-draw), compute
      dispatch structs, and packed instance-data writers.  
      *Done: `DrawArraysIndirect`, `DrawElementsIndirect`, `DispatchIndirect` (`@GpuStruct`, sizes 16/20/12 checked against the GL and Vulkan specs), `DrawCommandBuffer` (multi-draw runs, optional 16-byte stride, instance-count zeroing for culling), `InstanceWriter` (transform rows plus user data); `docs/GPU.md`.*
- [x] **GPU-4 (P2, M)** Vertex-format descriptions (attribute, stride, offsets) and interleaved-buffer builders that emit the
      matching GL/Vulkan attribute specs.
      *Done (experimental): `VertexFormat`, `VertexBufferLayout` (GL, Vulkan and GLSL descriptions from one layout), `VertexLayout.toBufferLayout()`. See `docs/GPU.md`.*
- [x] **GPU-5 (P2, M)** GLSL/Slang shared-header generator so shader code and Java use one struct definition. → GPU-1
      *Done (experimental): `ShaderHeader` emits GLSL or Slang headers (structs, constants, blocks) from the `LAYOUT` constants; the text is not compiled here. See `docs/GPU.md`.*
- [x] **GPU-6 (P2, M)** Clip-space conventions as a type (`GL`, `Vulkan` (Y-down), `D3D`), applied consistently by
      projection builders. Removes the boolean `zZeroToOne` parameter.  
      *Done for the matrix builders: `ClipSpace` (`OPENGL`, `VULKAN`, `D3D`), `ClipSpace` overloads of `Mat4f.perspective`/`perspectiveInfinite`/`perspectiveReversedZ`/`ortho`/`frustum`, `Mat4f.flipY`, `DepthRange.of`. The boolean overloads stay (japicmp). `Cameraf` and its screen helpers still assume y-up NDC (changing its record components would be an API break); see `docs/CAMERA.md`.*

**GLSL versions (GPU-7 to GPU-12).** The shader text the library produces assumes one GLSL version: `GpuCullGlsl.computeShader` and `clusterShader` start with a fixed `#version 450`, and `ShaderHeader` has no notion of a version at all, so nothing is guarded today. The goal is that every generator can serve any desktop GLSL version from 3.30 up, and says so clearly where a feature does not exist in a version. A wrongly generated shader fails on the user's driver, not in our tests, so these rules apply to every task below:

1. **The default does not change.** Without a version argument every generator emits exactly what it emits now, byte for byte (pinned by golden tests that are written before anything else, GPU-7).
2. **Gating is opt-in and explicit.** A version is a parameter; nothing reads a system property or probes a driver.
3. **A missing feature fails fast, never degrades quietly.** If a version cannot express something, the generator throws `UnsupportedOperationException` with the feature and the lowest version that has it; it never emits text that will not compile, and it never swaps in a different layout (std430 offsets differ from std140, so "falling back to std140" would corrupt data without a message).
4. **One capability table.** The minimum version of every construct lives in one place (GPU-8) and every gate reads it.
5. **Compiler-checked, not remembered.** The table and every generator output are checked with glslang at each version (GPU-12); a version number from memory is a hypothesis until a compiler has accepted or rejected the text.
6. **Desktop core profile only.** GLSL ES (3.00, 3.10, 3.20) and WGSL are separate targets and not part of these tasks.

- [ ] **GPU-7 (P1, S)** Inventory and safety net. List every GLSL construct that each generator emits (`ShaderHeader` structs, constants and blocks; both `GpuCullGlsl` shaders; `ClusterGrid.glslLookup`; `DualParaboloid.glsl`; `VertexBufferLayout.glslInputs`; the types of `GlslType`) with the lowest version that has it, in a table in `docs/GPU.md`, and add golden-text tests that pin the current output of each generator byte for byte. No production code changes. Constructs seen so far that need more than 3.30: `layout(binding = n)` (4.20), compute shaders with `local_size`, storage blocks, `std430` and atomic functions on buffer variables (4.30), double-precision types (4.00); these numbers are from memory and are checked in GPU-12. → GPU-5
- [ ] **GPU-8 (P1, S)** `GlslVersion` and `GlslFeature`: a small value type for the desktop versions 330, 400, 410, 420, 430, 440, 450 and 460 (anything below 330 is refused with `IllegalArgumentException`), a `GlslFeature` enum (uniform block, std140, std430, storage block, explicit binding, compute, memory qualifiers, atomic functions on buffers, double types, explicit attribute location, ...) and `GlslVersion.supports(feature)` with the table of rule 4. Nothing uses it yet. → GPU-7
- [ ] **GPU-9 (P1, M)** `ShaderHeader.Builder.version(GlslVersion)`. Without it the output is unchanged (golden tests). With it: a storage block or `GpuLayout.STD430` below 4.30 throws (rule 3); `layout(binding = n)` is written only from 4.20 up, below that it is left out and the bindings are returned (or written in a comment) so the caller sets them with `glUniformBlockBinding`; memory qualifiers only where the version has them; an optional `#version` line for headers that are not included into a shader that has one. Slang output is not affected. → GPU-8
- [ ] **GPU-10 (P1, S)** `GpuCullGlsl.computeShader` and `clusterShader` with a `GlslVersion` parameter. The constructs in them (compute, std430 storage blocks, buffer atomics, explicit bindings, `texelFetch`) are all 4.30 or older as far as the text read so far shows, so `#version 450` is stricter than needed: the lowest version is settled by GPU-12, the default stays 450 until a deliberate, reviewed change of the golden files, and anything below that version throws with the reason (GLSL 3.30 has no compute shaders; users on it cull on the CPU with `GpuCullReference` and the `vmath.spatial` kernels, which `docs/GPU.md` says). → GPU-9, GPU-12
- [ ] **GPU-11 (P1, S)** The version-neutral generators: check `ClusterGrid.glslLookup`, `DualParaboloid.glsl` and `VertexBufferLayout.glslInputs` from 3.30 up, and add a `GlslVersion` overload only where a construct needs a gate; where none does, record "valid from 3.30" in `docs/GPU.md` and test it. → GPU-12
- [ ] **GPU-12 (P1, M)** Compile matrix. `ShaderCompileTest` compiles the output of every generator at every supported version with glslang, in both directions: the text of a version that has the feature must be accepted, and a combination that the table forbids must throw before any text is produced (so glslang is not asked). Skipped without glslang like today, required on the Linux CI job (which installs it) through `vmath.requireEnvironment`. The table of GPU-8 is corrected wherever the compiler disagrees with it. Prerequisite for turning on any gate. → GPU-8
- [ ] **GPU-13 (P2, M)** Vulkan flavour of the GLSL generators: `ShaderHeader.Builder` with a target of `OPENGL` (today) or `VULKAN`, where Vulkan means `layout(set = s, binding = n)` on every block, `push_constant` blocks (std430), no loose uniforms, and the text is checked by compiling it to SPIR-V with glslang. The rules of GPU-7 to GPU-12 apply (the default does not change, a combination that does not exist throws). `VertexFormat` already carries the `VkFormat` numbers. → GPU-9, GPU-12
- [ ] **GPU-14 (P3, M)** GLSL ES targets (3.00, 3.10, 3.20) for WebGL 2 and mobile, as an extension of the capability table of GPU-8: required precision qualifiers, no double-precision types, storage blocks and compute only from 3.10, explicit bindings only from 3.10, and so on, each checked with glslang like the desktop versions. → GPU-12
- [ ] **GPU-15 (P1, L)** Run the shaders on a real GPU (register TD-01). An opt-in module `vmath-gpu-it` that is not part of `build`: a headless OpenGL 4.5 context through LWJGL (a hidden GLFW window, or a software rasteriser such as Mesa llvmpipe on a CI machine); both culling shaders run on random scenes in all three depth conventions and the instance lists, counters and commands are compared with `GpuCullReference` and `ClusterCullReference`; the layout of every `@GpuStruct` is compared with the program reflection (`glGetProgramResourceiv`) through `LayoutValidator`; the persistent ring is exercised with real fences. Afterwards the version matrix of GPU-12 is linked as real programs on whatever driver is present, and a Vulkan variant (lavapipe) is considered. `docs/SAMPLES.md` already lists what the samples have run on one NVIDIA GPU; this adds the shaders, other vendors and automation. → GPU-12
- [ ] **GPU-16 (P2, S)** Write down the three contracts of the depth pyramid that `docs/technical-debt.md` TD-33 found and no document states: the base of the pyramid must be a power of two on OpenGL (resample the depth image to it taking the farthest value, with a helper in `HiZ` that gives the base size of an image), the texels of a pyramid for `DepthRange.NEGATIVE_ONE_TO_ONE` hold normalised device depth (`2 d - 1`), and the compute passes that build the pyramid need `GL_TEXTURE_FETCH_BARRIER_BIT` before the culling shader and `GL_TEXTURE_UPDATE_BARRIER_BIT` before a read-back. In the Javadoc of `GpuCullGlsl` and in `docs/GPU.md`.

### Phase G. Geometry primitives (P1)

Value records for single shapes; SoA storage in `vmath-bulk` for large sets.

- [x] **GEO-1 (P1, M)** `Aabb`, `Sphere`, `Plane`, `Ray`, `Segment`, `Triangle`, `Capsule`, `Obb`, `Frustum` (six planes, 96 B),  
      *Done: all nine shapes (`Segment`, `Capsule` added with closest point, bounds, transform; capsule radius scales by the largest axis scale).*
      with construct/merge/expand/transform/contain/closest-point.
- [x] **GEO-2 (P1, L)** Intersection matrix, all pairs: ray x {aabb, sphere, plane, tri, obb, capsule}, aabb x {aabb, sphere, plane, tri, obb}, sphere x {sphere, plane, tri}, sweep tests, and distance queries. Each with conservative/exact variants documented.
      *Done: every pair above, plus segment-segment, segment-aabb, segment-triangle, capsule pairs, OBB-OBB (15-axis SAT) and aabb-triangle (13-axis SAT). Sweeps: sphere x {sphere, aabb, obb, triangle, capsule}, capsule x {capsule, aabb, obb, triangle}, aabb x aabb; the sphere, capsule-capsule and box sweeps are exact, the capsule sweeps against boxes and triangles use conservative advancement. See `docs/CULLING.md`.*
- [x] **GEO-3 (P1, M)** Robust ray-triangle (watertight, Woop et al.), slab test with correct NaN/0-direction handling.  
      *Done: watertight ray-triangle.*
- [x] **GEO-4 (P2, M)** *(done: `BoundingVolumes`, `KDop`; see `docs/GEOMETRY.md`)* Bounding-volume fitting: min sphere (Welzl), PCA OBB, k-DOP, bounding-volume from transformed AABB
      (Arvo, exact for affine).
- [x] **GEO-5 (P2, M)** Convex hull (3D quickhull), convex polytope intersection, GJK/EPA distance + penetration, SAT helpers. `ConvexHull`, `ConvexPolytope`, `Sat`, `Gjk`; see `docs/GEOMETRY.md` (with measured speeds).
- [x] **GEO-6 (P2, M)** Curves and interpolation: Bézier, Hermite, Catmull-Rom, B-spline, arc-length parameterization, easing. `Curves`, `ArcLengthTable` (`docs/CURVES.md`); easing is `vmath.util.Easing`. No NURBS.
- [x] **GEO-7 (P2, M)** Polygon utilities: 2D/3D triangulation (ear clipping), polygon clip (Sutherland–Hodgman), winding. `Polygons`; see `docs/GEOMETRY.md`.
- [x] **GEO-8 (P3, L)** Signed distance function primitives and CSG combinators (CPU-side, for picking and mesh generation).  
      *Done: `Sdf`, `Sdfs` (primitives, CSG, smooth blends, transforms, normals, projection, sphere-traced `raycast`), `SurfaceNets` (`docs/GEOMETRY.md`). No sparse grids or sharp-feature meshing.*

### Phase H. Spatial structures and culling framework (P1)

Design: culling produces a **visibility bitset / compact index list**, not per-object callbacks. Cullers are stages that
can be chained and composed, and they run on SoA bounds.

- [x] **CULL-1 (P1, L)** Culling framework core: `CullContext` (camera, frustum, LOD bias), `Cullable` bounds in SoA,  
      *Done: `CullContext`, `CullStage`, `CullPipeline`, `VisibilitySet`, stages: frustum, distance, small-feature.*
      `VisibilitySet` (bitset + compaction to index list), `CullStage` chain (frustum → distance → occlusion → small-feature).
      Zero-allocation per frame. → MEM-1
- [x] **CULL-2 (P1, M)** *(done; the plane-coherency variant was measured and dropped, see `docs/PERFORMANCE.md`)* Frustum extraction (Gribb–Hartmann, both clip conventions, reversed-Z, infinite far), plane normalization,  
      *Partial: extraction for all three depth conventions incl. reversed-Z infinite far, p-vertex tests. Plane coherency was tried in the dynamic-tree traversal and dropped: no gain once nodes are ordered (`docs/PERFORMANCE.md`).*
      AABB/sphere/OBB tests with p-vertex/n-vertex, and the fast "plane-coherency" variant using the previous frame's failing plane.
- [x] **CULL-3 (P1, L)** Batch frustum culling kernel over SoA (scalar + Vector API), with benchmarks at 10k / 100k / 1M objects.  
      *Done: two-pass scalar kernel and a fused Vector API kernel (`vmath-simd`) behind `FrustumKernel`/`FrustumKernels.best()`, bit-identical, 3.4x faster than scalar and 11x faster than the naive loop at 1M objects. Parallel driver still open.*
      Target and record throughput. → MEM-2
- [x] **CULL-4 (P1, L)** Static BVH: SAH binned build, LBVH (Morton) build, refit, traversal (ordered front-to-back),  
      *Done: binned SAH build, refit, depth-first flat layout; LBVH (Morton) build still open.*
      compact node layout (32 B/node, cache-line aware), flattened into a `float[]`/`MemorySegment`.
- [x] **CULL-5 (P1, L)** Dynamic AABB tree (incremental insert/remove/rotate, fat AABBs, ID handles) for moving objects.  
      *Done: `DynamicAabbTree` with stable handles, fat boxes, SAH insertion, rotations, `optimize()` (depth-first renumbering), fuzz-tested; about 4x slower than `StaticBvh` for frustum queries, see `docs/CULLING.md`.*
- [x] **CULL-6 (P1, M)** Uniform grid / spatial hash and loose octree; pick per use case with a comparison benchmark.  
      *Done: `UniformGrid` (hash of cells, oversize list) and `LooseOctree`, fuzz-tested against brute force; guidance and numbers in `docs/CULLING.md`. An octree `optimize()` (node renumbering) is the obvious follow-up.*
- [x] **CULL-7 (P1, L)** Occlusion culling: software Hi-Z rasterizer on the CPU (SIMD depth tile test), plus the data structures
      for GPU Hi-Z (mip-chain sizing, two-phase culling contract).  
      *Done: `vmath.occlusion` with `DepthBuffer` (inner-conservative polygon rasterizer, farthest-depth storage, min pyramid, near-plane clipping, perspective and orthographic views in all three depth conventions), `OcclusionStage`, and `HiZ` (pyramid sizing, two-phase contract); property-tested against a ray-versus-box oracle. A four-objects-per-vector Vector API test was built and dropped: 1.23 times faster (5.25 ms against 6.47 ms for 100k objects), not worth a kernel interface. See `docs/CULLING.md`.*
- [x] **CULL-8 (P2, M)** LOD selection: distance/screen-space-error metrics, hysteresis, cross-fade factors, output to the visibility set.
      *Done: `LodSelector` (bounding-sphere screen size, descending thresholds, hysteresis, cross-fade, cull-below, bias; history in a caller-owned `byte[]`). Per-object thresholds are not built.*
- [x] **CULL-9 (P2, M)** Small-feature / contribution culling, backface cluster cone culling (meshlet cone test).
      *Done: small-feature stage (earlier) and `ConeCull` (cone builder, conservative sphere+cone test, orthographic variant, SoA `Clusters`).*
- [x] **CULL-10 (P2, L)** Portal/sector culling for interiors, PVS import hooks. *Done: `PortalGraph`, `PortalCuller`, `PortalStage`, `SectorVisibility`, `PvsMatrix`; tested against a line-of-sight oracle; see `docs/CULLING.md`. No visibility compiler, no portal cameras.*
- [x] **CULL-11 (P2, M)** Shadow culling: cascade frustum culling, light-space bounds, caster/receiver classification,
      point/spot light volumes, cube-face selection.  
      *Done: `CascadeCasters` (tight light-space caster test per cascade), `LightCull` (point, spot, cube-face masks). Caster/receiver classification bits are not built: the tight caster test covers the same saving.*
- [x] **CULL-12 (P2, M)** Spatial queries: ray cast, k-NN, AABB/sphere/frustum overlap, all against BVH/grid, no allocation  
      *Done: AABB/sphere overlap, frustum and ray queries on the trees; k-NN (`Neighbors`) on the BVH, dynamic tree, grid and octree; overlap on the grid and octree.*
      (caller-provided result buffer).
- [x] **CULL-13 (P3, L)** GPU-driven culling support: compute-shader Hi-Z + frustum culling that writes indirect draw buffers,
      with the CPU-side layouts from GPU-1/GPU-3 and a CPU reference implementation used as the test oracle.
      *Done (experimental): `vmath.gpucull` with layouts, `HiZPyramid`, `GpuCullReference` (single pass and two-phase), `ClusterCullReference` and the shader text.
      The shaders were never run on a GPU. See `docs/GPU.md`.*
- [ ] **CULL-14 (P3, L)** Temporal coherence: per-object visibility history, coherent hierarchical culling with occlusion queries.

### Phase I. Camera and rendering math (P1/P2)

- [x] **CAM-1 (P1, M)** `Camera` type (immutable, view/projection/viewProjection/inverse cached), FPS/orbit/free controllers as  
      *Done: `Cameraf`/`Camerad` (view, projection, frustum, project/unproject, screen, pick ray, camera-relative); controllers are not built. See `docs/CAMERA.md`.*
      separate pure functions over state records.
- [x] **CAM-2 (P1, M)** Unproject / pick ray / screen↔world helpers, viewport type, NDC conventions per `GPU-6`.  
      *Done: project/unproject/toScreen/pickRay for all three depth conventions. See `docs/CAMERA.md`.*
- [x] **CAM-3 (P1, M)** Temporal AA jitter sequences (Halton), jittered projection, previous-frame matrices for motion vectors.  
      *Done: Halton `Jitter`, `jitteredProjection`, `reprojection` for motion vectors. See `docs/CAMERA.md`.*
- [x] **CAM-4 (P1, M)** Cascaded shadow map splits (log/uniform/PSSM blend), stable texel-snapped fitting, light-space projection fit.  
      *Done: `Cascades`: split schemes, sphere-fit with texel snapping, per-cascade frustum and texture matrix. See `docs/CAMERA.md`.*
- [x] **CAM-5 (P2, S)** Cubemap face matrices, omnidirectional shadow/probe setup, dual-paraboloid.  
      *Done (experimental): `CubeFaces` plus `DualParaboloid` (mapping, inverse, hemisphere views and half-spaces, GLSL). See `docs/CAMERA.md`.*
- [x] **CAM-6 (P2, M)** Oblique near-plane clipping (planar reflections), portal camera transforms, stereo/VR projection.
      *Done (experimental): `PlanarViews` (reflection, oblique near plane for all depth conventions, portal views) and `Stereo` (eye views, asymmetric and off-axis projections). A single culling frustum for both eyes is not built. See `docs/CAMERA.md`.*
- [x] **CAM-7 (P2, S)** Physical camera model: exposure (EV100), FOV↔focal length, depth-of-field parameters. *Done: `PhysicalCamera`; see `docs/CAMERA.md`.*
- [x] **CAM-8 (P2, M)** Depth utilities: linearize depth for all conventions, depth reconstruction of view position, Z-slice for clustered lighting.  
      *Done: `linearizeDepth`, `viewPositionFromDepth`, `worldPositionFromDepth`, and the Z-slice of an NDC depth (`ClusterGrid.sliceOfNdcDepth`). See `docs/CAMERA.md`.*
- [x] **CAM-9 (P2, M)** Clustered/tiled light assignment math (froxel bounds, light-vs-cluster tests) with CPU reference.  
      *Done (experimental): `ClusterGrid` (exponential slices, froxel bounds, shader-matching lookup, all depth conventions), `ClusterLights` (point and spot assignment, tiled variant, GPU buffers), `gl.ClusterLight`; tested against an exact oracle (0 missing, 0.5% extra for points). The GLSL is text that is not compiled here. See `docs/CAMERA.md`.*
- [x] **CAM-10 (P3, M)** Sun/sky, atmosphere and time-of-day helpers (solar position). *Done: `SolarPosition`, `Atmosphere`, `PreethamSky`; see `docs/CAMERA.md`.*

**Orthographic cameras (CAM-11 to CAM-14).** There are orthographic projection matrices (`Mat4f.ortho`, `orthoReversedZ`, every `ClipSpace`), `Frustumf.fromViewProjection` works on them, `vmath.occlusion` and `ConeCull` have orthographic variants and `Cascades` builds orthographic light matrices, but `Cameraf` is perspective only (field of view, aspect, near, far) and `docs/CAMERA.md` says "use `Mat4f.ortho`/`frustum` directly". So everything that takes a `Cameraf` (project and unproject, `pickRay`, depth linearisation, `ClusterGrid.of(camera, ...)`) has no orthographic form. No code is planned until the question of CAM-11 is answered.

- [ ] **CAM-11 (P1, M)** Orthographic `Cameraf`/`Camerad`. First decide, in a short spike with the existing tests as the judge, between a projection kind inside `Cameraf` (one type, but `fovy()` and `aspect()` need a defined meaning for an orthographic camera) and a separate type (no ambiguity, but every consumer needs two overloads or a shared interface). Then: size by height or by left, right, bottom and top plus near and far; cached view, projection and inverses; frustum, project, unproject, camera-relative; all three `ClipSpace` values and reversed-z; and a pick ray that has the direction of the view and an origin that moves (the perspective ray is the other way round). Tested against JOML `setOrtho` as the oracle, like the perspective matrices. → GPU-6
- [ ] **CAM-12 (P1, S)** Audit of the code that assumes a perspective camera, each case either made orthographic-aware or made to refuse an orthographic camera with a clear exception (never to give a quietly wrong answer): `Cameraf.linearizeDepth`, `viewPositionFromDepth` and the cluster slice of an NDC depth (depth is linear in an orthographic projection), `LodSelector` and the small-feature stage (the projected size of an object does not depend on its distance), `TileSelector.select(..., fovY, ...)`, the Halton jitter (offsets in world units, not angles), `Cascades`, `PlanarViews` and `Stereo`. The result is a table in `docs/CAMERA.md`. → CAM-11
- [ ] **CAM-13 (P2, M)** Clustered lights for an orthographic camera: slices linear in depth instead of exponential, tiles that do not widen with distance, the matching GLSL lookup, tested against the exact oracle that `ClusterGrid` already has. Optional: if the work is not worth it, `ClusterGrid.of` refuses an orthographic camera (CAM-12) and this item is closed with that decision. → CAM-12
- [ ] **CAM-14 (P2, S)** Documentation: replace the "use `Mat4f.ortho` directly" note in `docs/CAMERA.md`, add a cookbook recipe for 2D, UI and CAD views (an orthographic projection with a pixel-exact size), and fix the sentence there that says the gap is tracked in the roadmap (it was not until now). → CAM-11

### Phase J. Mesh and asset support (P2)

- [x] **MESH-1 (P2, L)** Indexed mesh container over SoA/`MemorySegment` with attribute streams and typed accessors.  
      *Done: `Mesh` (heap `float[]`/`int[]` streams: positions, normals, tangents, four UV sets), `VertexLayout` and `MeshExport` writing interleaved vertices (float, octahedral, half) and 16/32-bit indices into a `MemorySegment`. Off-heap storage of the mesh itself is not built. See `docs/MESH.md`.*
- [x] **MESH-2 (P2, M)** Normals (angle-weighted, smooth groups), tangents (MikkTSpace-compatible), bounds computation.  
      *Done: angle-weighted smooth normals, crease-angle splitting with a remap, UV-derivative tangents with handedness (standard accumulation, not a bit-exact MikkTSpace port), NaN-free on degenerate input, `bounds()`/`surfaceArea()`/`signedVolume()`.*
- [x] **MESH-3 (P2, L)** Meshoptimizer-style pipeline: vertex dedupe, vertex-cache and overdraw optimization, vertex fetch reorder.  
      *Partial: `weld`, Forsyth `optimizeVertexCache` (ACMR 2.99 shuffled to 0.66-0.68), `optimizeVertexFetch`, `acmr`. `Overdraw` (experimental): software overdraw measure and cluster ordering, accepted only when measured overdraw drops within an ACMR budget; numbers in `docs/MESH.md`.*
- [x] **MESH-4 (P2, XL)** Meshlet builder (bounds + normal cone per meshlet), LOD simplification (quadric error metrics) and
      LOD chain / cluster hierarchy generation.  
      *Done (experimental): `Meshlets` (greedy builder, sphere + normal cone, GPU records), `MeshSimplifier` (QEM, seams, borders and explicit locks), `MeshLod` (discrete chain with errors and LodSelector thresholds), `ClusterHierarchy` (Nanite-style cluster DAG with crack-free selection, tested on closed meshes). The hierarchy build takes about 4 s for 328k triangles (was 14 s); see `docs/MESH.md`.*
- [x] **MESH-5 (P2, M)** Procedural primitives: plane, box, UV/ico sphere, capsule, cylinder, cone, torus, with UV/normals/tangents.  
      *Done: `Primitives`, watertight by position, outward-wound, volumes and areas checked against the analytic shapes.*
- [x] **MESH-6 (P2, L)** glTF 2.0 loader (mesh, skin, animation, materials) writing straight into `MemorySegment` streams.  
      *Done (experimental): `vmath.gltf.Gltf` (GLB and glTF, all accessor kinds, `readInto` a segment, meshes, materials, nodes, skins to `Skeleton`, animations to `AnimationClip` with STEP approximated and CUBICSPLINE resampled); hardened and mutation-tested. No morph targets, no compressed geometry, tested with hand-built files only. See `docs/GLTF.md`.*
- [x] **MESH-7 (P3, L)** Texture-side utilities: mip-chain sizing, KTX2 header parse, block-compression formats table, cubemap/array layouts.  
      *Done (experimental): `vmath.tex` with `TextureFormat`, `TextureLayout`, `CubeFace`, `Ktx2` header and level index; no pixel decoding or supercompression. See `docs/TEXTURES.md`.*
- [x] **MESH-8 (P3, M)** UV unwrap/atlas packing (rect packing), lightmap UV helpers.  
      *Done (experimental): `RectPacker` (MaxRects), `UvAtlas` (planar chart unwrap, uniform texel density, padding). Not a conformal/LSCM unwrap; see `docs/MESH.md`.*

### Phase K. Animation, scene and simulation glue (P2/P3)

- [x] **ANIM-1 (P2, L)** Scene-transform hierarchy in SoA: parent indices sorted topologically, local/world `TransformArray`, dirty-flag
      propagation, batch world-matrix update kernel. → MEM-2, CORE-5  
      *Done: `TransformHierarchy` (parents-first order, dirty propagation, single forward pass, subtree removal with remap; `docs/ANIMATION.md`). A standalone bulk `TransformArray` is planned under MEM-1.*
- [x] **ANIM-2 (P2, L)** Skeletal animation: pose sampling (keyframe search, quaternion slerp), skinning matrices, blend/additive/layered
      blending, GPU skinning buffer layout. Dual-quaternion skinning option.  
      *Done: `Skeleton`, `Pose`, `AnimationClip`, `ClipSampler`, `Skinning` (joint matrices, CPU reference, unorm8 weight packing, 64-byte `mat4` stride). Dual-quaternion skinning is not built.*
- [x] **ANIM-3 (P2, M)** Inverse kinematics (two-bone, FABRIK, CCD) and look-at constraints. `IkSolver`; see `docs/ANIMATION.md` (no joint limits yet).
- [x] **ANIM-4 (P3, M)** Morph targets/blend shapes packing, root motion extraction, animation compression (curve fitting, quantized keys).  
      *Done: `MorphTargets`, `RootMotion`, `ClipCompression`, `QuantizedClip` (`docs/ANIMATION.md`). Reduction keeps the clip's linear keys; fitting cubic or spline keys is not built.*
- [x] **ANIM-5 (P3, L)** Physics-adjacent math: rigid-body integrators, inertia tensors from shapes, contact manifold math
      (only the math; the engine would be a separate project).  
      *Done: new package `vmath.physics`: `MassProperties`, `RigidBody`, `OdeIntegrator`, `ManifoldBuilder`, `ContactManifold`, `ContactSolver` (`docs/PHYSICS.md`). No joints, islands, sleeping or continuous collision.*

### Phase L. Utilities (P2/P3)

- [x] **UTIL-1 (P2, M)** *(done except blue-noise tables and scrambled or higher-dimensional Sobol: `Rng` xoshiro256++, `Sequences`; see `docs/UTIL.md`)* Random: PCG/xoshiro (fast, seedable, splittable), sampling on sphere/hemisphere/disk, cosine-weighted,
      Poisson-disk, low-discrepancy (Halton, Sobol, R2), blue-noise tables.
- [x] **UTIL-2 (P2, M)** *(done, simplex only in 2D and 3D: `Noise`, `docs/UTIL.md`)* Noise: value/Perlin/simplex/Worley/curl, fBm, domain warping, 2D/3D/4D, batch fill APIs into `float[]`.
- [x] **UTIL-3 (P2, S)** *(done: `Spring`, `Smoothing`, `Easing`)* Interpolators and smoothing: critically-damped spring, exponential smoothing (frame-rate independent), easing set.
- [ ] **UTIL-4 (P3, M)** Frame-timing/statistics helpers (rolling percentiles) for the bench/diagnostic layer.
- [x] **UTIL-5 (P3, M)** SH (spherical harmonics) L1/L2 projection/evaluation, IBL prefilter math, BRDF LUT generator. *Done: `SphericalHarmonics`, `Ibl`; see `docs/UTIL.md`.*
- [x] **UTIL-6 (P3, M)** Debug draw geometry generators (lines for frustum/AABB/OBB/skeleton) into `float[]`. *Done: `DebugLines`; see `docs/UTIL.md`.*

### Phase M. Documentation and release (P1/P2)

- [x] **DOC-1 (P1, M)** `docs/PERFORMANCE.md` (contract from §2), `docs/CODEGEN.md`, `docs/API.md` parity table.  
      *Done: all three exist; the API table is backed by `ApiParityTest`.*
- [x] **DOC-2 (P2, M)** Cookbook: "camera-relative rendering", "culling 1M instances", "GPU-driven pipeline", "migrating from JOML".
      *Done: `docs/COOKBOOK.md` with four recipes (camera-relative rendering, culling a million instances, a GPU-driven frame, migrating from JOML), generated from `CookbookTest` by `CookbookDocTest`, so every snippet compiles and runs and the file cannot drift.*
- [x] **DOC-3 (P2, S)** ~~JOML adapter module~~ *Dropped on purpose: it would be double bookkeeping; the README migration table is the bridge.*
- [x] **DOC-4 (P2, S)** Changelog + semver policy; mark experimental APIs (`@Experimental` annotation, also in the framework).  
      *Done: `CHANGELOG.md`, `docs/VERSIONING.md`, `vmath.annotations.Experimental` (class retention, excluded from japicmp). The framework side does not exist yet.*
- [x] **DOC-5 (P3, M)** Sample app (LWJGL) that renders and culls 1M instances; doubles as an end-to-end benchmark.  
      *Partial: headless `CullAndDrawSample` (cull 1M, write instance buffer + indirect draw, numbers in `docs/GPU.md`); an LWJGL window that actually draws is open; `FrameBench` compares serial/parallel/BVH frames; `InstanceWriteBench` tuned the instance write (word-wise set walk, about 25% faster; packed centres and staged bulk copy rejected, numbers in `docs/GPU.md`).*
      *Done: `vmath-samples` (a separate module, part of the build only with `-Psamples`) with `MillionInstances`: a city of a million boxes drawn with OpenGL 4.5 through LWJGL 3.3.6, using the frustum culling (SIMD and parallel), `InstanceWriter.writeVisibleBoxes`, the `PersistentBufferRing` with real fences, `DrawCommandBuffer` and the vertex layout; interactive, or a scripted flight with `--frames N` that prints the time of each stage. About 9.4 ms per frame (106 fps) with half the boxes visible on an RTX 3060 Laptop GPU, 14.9 ms with culling off. Run on one NVIDIA GPU only. See `docs/SAMPLES.md`. Since then the sample is the first demo of the demo framework (`docs/DEMOS.md`), whose backlog continues it.*

### Phase N. Measured gaps and follow-ups (P2/P3)

Performance gaps found by the demos (details and numbers in `docs/technical-debt.md`, items TD-29 to TD-32), and follow-ups of the technical-debt work of 2026-10-04 (`TECH_DEBT_REPORT.md`). Nothing here is a known wrong result.

- [ ] **PERF-1 (P2, M)** Occlusion test cost (register TD-29): about 107 ns per box, so occlusion culling does not pay for cheap geometry. A batch entry point `DepthBuffer.cull(BoundsArray, VisibilitySet)` with the corner projection in `float` and in struct-of-arrays form, early outs (the centre and extent first, the nearest corner against the pyramid), and a measurement against the occlusion demo. → CULL-7
- [ ] **PERF-2 (P2, M)** Box-box narrow phase (register TD-30): 10 us per pair and about 9 kB of allocation per body per frame. A `ConvexBox` special case in `ManifoldBuilder` (15-axis test, face clipping), `ConvexPolytope.transformInto` that reuses its arrays for the general case, and warm starting in `ContactSolver`. Verified against the existing general path as the oracle and against the rigid-pile demo.
- [ ] **PERF-3 (P2, S)** The SIMD frustum kernel loses to the scalar one on a few hundred boxes and allocates there (register TD-32). Diagnose with `-XX:+PrintCompilation` and `PrintInlining` why the vector loop is not compiled for short inputs, and make `FrustumKernels.best()` use the scalar kernel below a measured size.
- [ ] **PERF-4 (P3, S)** `SurfaceNets` Javadoc says "a closed, consistently wound triangle mesh" but a cell that holds two sheets of the surface can give edges shared by more than two triangles (register TD-31): say what holds, optionally add a pass that splits such a vertex, behind a flag.
- [ ] **QA-1 (P2, M)** Work through the surviving mutants of the PIT run of 2026-10-04 in the order of their consequence: `Gjk` (196 survivors, 29 uncovered lines), `RigidBody` (166), `ManifoldBuilder` (127, 18 uncovered), `MassProperties` (77), `ContactSolver` (67), `TileSelector` (123), `ClusterLights` (62); record the new figures in `docs/COVERAGE.md`. Also run PIT on `vmath.anim`, `gl`, `occlusion`, `util`, which have never had a run, and on `vmath.mesh` again after the splits of TD-019.
- [ ] **QA-2 (P2, S)** A box with NaN bounds is kept by the scalar frustum kernel and by the GPU reference but not by the static BVH (`CullCopiesAgreeTest`). Decide what a tree does with NaN bounds (reject at insert and build time with a clear exception, or keep them like the kernels) and make the three structures agree; the other structures were not checked.
- [ ] **QA-3 (P3, S)** The long methods that the split of 2026-10-04 left: `ManifoldBuilder.faceContact` (104 lines) and `polytopes` (98), the `ConvexPolytope` constructor (143), `Gjk.epa` (about 100).
- [ ] **QA-4 (P2, S)** `Gltf.parse` copies the BIN chunk of a GLB (a large file is held about twice): read from an offset into the file bytes instead. Make sure the symlink test of `GltfTest` runs somewhere (it skips on Windows without the privilege): on the Linux CI job it must not skip.
- [ ] **QA-5 (P3, S)** The coverage floors of `vmath.lighting` and `vmath.sky` were copied from `vmath.camera` when the package was split; measure them (`./gradlew coverageSummary`) and set them two to three points under the figure like the others.
- [ ] **DET-1 (P2, M)** A deterministic mode: the SIMD matrix kernels use fused multiply-add and a different summation order, so their results differ from the scalar kernels in the last bit (documented), and `MatrixKernels` chooses once per JVM, so the same program can give different results on machines with and without the vector module. For lockstep networking, replays and golden-file tests a switch that makes `best()` return the scalar kernels (`-Dvmath.deterministic=true`, documented next to `-Dvmath.matrixKernel`) and a test that two runs on different kernel sets give identical bytes under it. Also states which other parts of the library are not bit-reproducible across machines (`Math.sin`, `fma` intrinsics, `FastMath`).

### Phase O. Lines, multi-draw and 2D maps (P1/P2)

Goal: thousands of styled lines drawn with as few API calls as the hardware allows, and the pieces of a 2D map view (moving maps, charts, radar-style displays), for anyone who builds an engine on this library, not for one engine. The design rules, which apply to every task of this phase:

1. **Engine-neutral and renderer-neutral.** The library never calls a graphics API. What the hardware can do is passed in by the caller as a value (`GraphicsCapabilities`, CAP-1), and what comes out is data: buffer layouts, writers, command encodings, GLSL text and CPU references. No name in the API is specific to one application; the first users are moving maps for aircraft, and that appears in demos and recipes, not in class names.
2. **Newer and older way, both implemented.** Where a feature has a modern approach (for example one multi-draw-indirect call with the style read through the draw index) and an older one that works down to OpenGL 3.30 core (instanced draws, or vertices expanded on the CPU, or plain `glMultiDrawArrays`), both are implemented, over **one data model**: the strategies differ only in the buffers they write, the commands they emit and the GLSL they need.
3. **Selected on demand.** `choose(capabilities)` returns the best strategy that the capabilities allow; the caller can name a lower one (to test it, to save a feature, or because a driver misbehaves), and a request that the capabilities cannot satisfy is an exception that lists what is missing, never a silent downgrade of the meaning (same philosophy as the rules of GPU-7 to GPU-12). The decision table is generated into the documentation by a test, so that the text cannot drift from the code.
4. **The floor is OpenGL 3.30 core** (and Vulkan 1.0 with its optional features as capabilities). Below that nothing is promised. GLSL ES and WebGL 2 are the same tiers once GPU-14 exists.
5. **Equivalence is tested, not assumed.** Every strategy of a feature is expanded by a CPU reference and compared (a software coverage rasteriser for lines, exact for geometry), every strategy's GLSL is compiled at the versions it claims (GPU-12), and every strategy is run on a real driver once GPU-15 exists.

- [x] **CAP-1 (P1, M)** `GraphicsCapabilities`: a plain value (API, version, and flags: multi-draw indirect, indirect first instance (`baseInstance`), shader draw parameters (`gl_DrawID`), storage buffers, texture buffers, compute, instanced arrays, persistent mapping, ...) with factories `openGl(major, minor, extensions)` that derive the flags from the version and the extension strings (`ARB_multi_draw_indirect`, `ARB_base_instance`, `ARB_shader_draw_parameters`, ...) and `vulkan(...)` from the feature booleans. Pure data, no calls; the version and extension table is checked like the GLSL table (GPU-12: a number from memory is a hypothesis). → GPU-8
      *Done (experimental): `GraphicsCapabilities` (OpenGL from version 3.3 and extension strings, Vulkan from three device features; thirteen features; `without` to take features away, `withGlsl` to lower the version) and the minimal `GlslVersion` it needs (the desktop versions 330 to 460; the feature table of GPU-8 is still open). The table follows the specifications and is tested version by version; it has not been checked against a driver. See `docs/GPU.md`.*
- [x] **CAP-2 (P1, S)** The selection mechanism: an ordered list of strategies, each with the capabilities it needs; `choose(caps)`, `chooseAtMost(caps, ceiling)`, `force(strategy, caps)` with the "missing: ..." exception; the same pattern as `KernelSelector` (which stays as it is for CPU kernels). The decision table of every feature is printed into `docs/` by a test. → CAP-1
      *Done (experimental): `StrategyChooser` (`choose`, `chooseAtMost`, `force`, `supports`, `available`, `markdownTable`); the refusal lists what each option lacks; choosing allocates nothing (`DrawPathAllocationTest`). The tables of `docs/GPU.md` are generated and kept equal to the code by `DecisionTablesDocTest` (`-Dvmath.writeDocs=true`).*
- [x] **ACC-1 (P1, M)** Access modes for arrays of structs in `ShaderHeader`: a storage block (4.30), an array in a uniform block (std140, in OpenGL since 3.1 and so at the 3.30 floor; limited by the maximum block size, which becomes a parameter), a texture buffer (also since 3.1; rows of four floats) and an instanced vertex attribute (attribute divisors, 3.30). The builder emits the declaration and one `fetch_Name(i)` function per mode so that the body of a shader does not change, and the Java side gives the writer and layout of each mode. Chosen by CAP-2, gated and checked like GPU-9. → GPU-9, CAP-2
      *Done (experimental): `StructArrayAccess` with the modes storage block, uniform block, texture buffer (RGBA32UI) and vertex attribute, `StructArrayAccess.choose` for random access or per-instance reads, and `ShaderHeader.Builder.access`. `layout(binding)` only from GLSL 4.20; the texture-buffer and attribute modes refuse matrix, array and nested members by name. The GLSL has been checked against golden text, not compiled: no GLSL compiler was installed on the machine it was written on, and `ShaderCompileTest.everyAccessModeOfAStructArrayCompilesAtEveryVersionItClaims` compiles it at every version where one is (GPU-12 is the full matrix).*
- [x] **DRAW-1 (P1, M)** `DrawList`: an API-neutral list of draws (arrays or elements, first, count, base vertex, instance count, base instance, a user value) with encoders that turn it into what a tier can submit: indirect commands in a buffer (`DrawArraysIndirect`/`DrawElementsIndirect`, the existing `DrawCommandBuffer`), the `first[]`/`count[]` (and `baseVertex[]`) arrays of `glMultiDrawArrays`, `glMultiDrawElements` and `glMultiDrawElementsBaseVertex`, or a plain loop of draws for hardware without `baseInstance` (with the vertex-attribute offsets to rebind per draw). One list, every tier; this is what makes multi-draw "simple" for a user who does not know which of them the target has. → CAP-2, GPU-3
      *Done (experimental): `DrawList` (arrays or elements, base vertex, instance count, base instance, a user value) with `writeIndirect` into a `DrawCommandBuffer`, the client arrays of `glMultiDraw*` and a visitor loop, and `DrawSubmission.choose` by the shape of the list. Nothing allocates in steady state.*
- [x] **CAP-3 (P2, M)** The same mechanism for what exists already: the culling backend (the compute shader of `GpuCullGlsl` where compute exists, the CPU kernels with a command buffer written by the library otherwise, as `CullAndDrawSample` does today), and the submission of the survivors through DRAW-1. `docs/GPU.md` gets the table. → DRAW-1, GPU-10
      *Done (experimental): `CullBackend` (COMPUTE needs compute, storage buffers, indirect draws, a base instance and GLSL 4.50 for now; else CPU) and `SurvivorBatcher`, the CPU output stage that groups a `VisibilitySet` by draw into a `DrawList` and the list of surviving object indices, with an optional capacity per draw; tested against a brute-force grouping. The table is in `docs/GPU.md`. Lowering the 4.50 is GPU-10.*
- [x] **LINE-1 (P1, M)** `LineBatch`, the data model: polylines of any length with a style (colour, width in pixels or in world units, dash pattern, cap, join, layer), double-precision input positions written camera-relative to a chosen origin (the large-world helpers), a bounding box per polyline, and a helper that says when the origin must be moved. Independent of any strategy. → large worlds
      *Done (experimental): `LineBatch` (polylines in double precision, style table without duplicates, bounding boxes, drawing order by layer, the origin with `originTooFar` and `setOrigin`, `relativeViewProjection` in double precision) and `LineStyle` (width in pixels or world units, caps, joins, miter limit, a dash pattern of up to eight world-unit lengths, a layer). Updating and removing polylines is LINE-5.*
- [x] **LINE-2 (P1, M)** CPU reference expansion of a `LineBatch` to triangles (segments, round, miter and bevel joins with a miter limit, butt, square and round caps, dashes, screen-space width), and a software coverage rasteriser that the tests of LINE-3 compare every strategy against. The reference is also the one place that defines what each join and dash looks like.
      *Done (experimental): `LineGeometry` (54 vertices per segment; butt, square and round caps; miter, bevel and round joins with a miter limit; dashes by distance along the polyline), `LineExpander` (the reference), `CoverageRaster`. The geometry is checked against Java 2D's `BasicStroke` for every cap and join, the miter limit, closed polylines, dashes and random polylines (`LineGeometryTest`). Dashes are in world units and constant over a join; there is no anti-aliasing fringe.*
- [x] **LINE-3 (P1, L)** The strategies, each with its buffer layouts, writers, command encoding and GLSL (generated through `ShaderHeader`, gated per GPU-8):
  - `INDIRECT_DRAW_ID` (OpenGL 4.6 or `ARB_shader_draw_parameters`, multi-draw indirect, first instance; Vulkan with `shaderDrawParameters`, `multiDrawIndirect` and `drawIndirectFirstInstance`): one call, instanced quads from a segment buffer, the style of a polyline read through the draw index (ACC-1).
  - `INDIRECT_INSTANCE_STYLE` (OpenGL 4.3 or `ARB_multi_draw_indirect` with `ARB_base_instance`): one call, the style index stored in every segment instance, no draw index needed.
  - `INSTANCED_LOOP` (OpenGL 3.30): instanced quads, one `glDrawArraysInstanced` per run of polylines, the attribute offset moved per draw because `baseInstance` is missing; polylines of equal style are merged into one run to keep the call count down.
  - `EXPANDED_MULTIDRAW` (OpenGL 3.30, and anything older that has `glMultiDrawArrays`): strips expanded on the CPU, each vertex carrying its offset direction and side so that the vertex shader extrudes by the width in pixels and nothing has to be rebuilt when the view zooms; one `glMultiDrawArrays`.
  - `HAIRLINE` (any): one-pixel `GL_LINE_STRIP` through `glMultiDrawArrays`, the last resort and a debugging path.
  A geometry-shader strategy is deliberately not planned: everything from 3.30 has instancing, and geometry shaders are slower on most drivers. → LINE-1, LINE-2, DRAW-1, ACC-1
      *Done (experimental), with one change: all five strategies exist and write their buffers, draws and shader text, but the table puts `EXPANDED_MULTIDRAW` before `INSTANCED_LOOP` (one `glMultiDrawArrays` beats a draw per run). `INDIRECT_DRAW_ID` reads the style by the draw index, `INDIRECT_INSTANCE_STYLE` from the segment, `EXPANDED_MULTIDRAW` finds its segment in a texture buffer from the vertex index (vertex pulling: nothing is expanded on the CPU, as the item first said, which needs 48 bytes per segment instead of the expanded vertices). Every triangle strategy gives the same pixels as the reference (`LineRenderPlanTest`, orthographic and perspective). The GLSL was compiled and run on a real driver afterwards (`LineGpuCheck`, see LINE-8).*
- [x] **LINE-4 (P1, S)** `LineRenderPlan.choose(caps)` and its override: the table "capabilities to strategy" (the first strategy of LINE-3 that the capabilities allow, in the order above), a `force` for tests and compatibility, and a description of the plan (strategy, buffers, GLSL, how to issue the draws) that an engine can follow without knowing the strategies. → CAP-2, LINE-3
      *Done (experimental): `LineStrategy.choose` / `chooseAtMost` / `force`, `LineRenderPlan.choose` / `force` with `describe()`, `submission(draws)`, the decision tables in `docs/LINES.md` (generated and tested).*
- [x] **LINE-5 (P2, M)** `LineSet`: polylines that are added, edited and removed while running, with stable handles, slots from the free-list allocator, dirty ranges for the upload (only what changed), a compaction pass, and the same behaviour in every strategy. Allocation-free in steady state like the other containers. → LINE-3
      *Done (experimental): `LineSet` with handles that are never valid again once removed, slots from `FreeListAllocator`, `update` that writes only the polylines that changed and reports the byte ranges (sorted, merged) as `dirtyOffset`/`dirtyLength`, `compact` (by itself when it lets an addition succeed) and `grow`, runs of equal style next to each other as one draw, and a visibility set that leaves polylines out of the draws. Tests: after random additions, edits, removals, style changes, compactions and growth every strategy, fed to the GPU copy through the dirty ranges alone, draws what the reference draws (`LineSetTest`); steady-state edits and updates allocate nothing (`DrawPathAllocationTest`). A style change allocates, and a style table of a uniform block is limited to 256 entries. Measured: editing 100 of 5000 polylines uploads 2% of the data.*
- [x] **LINE-6 (P2, M)** Line processing: simplification to a pixel tolerance for each zoom (Douglas-Peucker, and a Visvalingam variant for smoother results), segment clipping against a view rectangle or frustum (Liang-Barsky and its 3D form), and culling of whole polylines by their boxes through the existing pipeline (the GPU culling path where compute exists, the CPU kernels elsewhere, CAP-3). → LINE-1, CAP-3
      *Done (experimental), except the compute path: `LineSimplifier` (Douglas-Peucker and Visvalingam-Whyatt as an importance per point, so any zoom is one pass without allocation; Douglas-Peucker checked against the textbook recursion and the distance guarantee, Visvalingam against the quadratic algorithm), `LineClipper` (Liang-Barsky in 2D and 3D, Cyrus-Beck for planes, `frustumPlanes` for every depth range, `clipPolyline` with the distance along the original), and `LineCulling` (boxes of a `LineSet` through `FrustumKernels.best()`, and `update(..., visible)`). The compute path of `vmath.gpucull` is not used: it decides per instance of a draw and the instances of a polyline are its segments, so it does not fit; `LineSet.fillBounds` gives the boxes for a pass of one's own. A clipped piece restarts its dash pattern unless the caller continues it from `startDistance`.*
- [x] **LINE-7 (P3, S)** `TrailBuffer`: a ring buffer of positions per track for histories, age-based fading as a per-vertex value, usable as a polyline of a `LineSet`. → LINE-5
      *Done (experimental), with one change: the fade is in steps, not per vertex. `TrailBuffer` keeps a ring of positions and times per track and writes a trail into a `LineSet` as one polyline per slice of age with a graded alpha, connected at the boundary points; a gradient along every segment needs an alpha at each end of a segment in the record, which the 48 bytes of `LineGpu` do not have (a change of the record and of every shader, left for later).*
- [x] **LINE-8 (P1, M)** Tests of the whole phase: for every strategy the CPU expansion matches the reference rasterisation (exact for the geometry, a coverage tolerance for anti-aliasing), the buffers and commands are well formed for the capabilities that select it, the GLSL of every strategy compiles at every version it claims (GPU-12), and a request that the capabilities cannot satisfy throws. Run on a real driver per strategy with GPU-15. → LINE-3, GPU-12
      *Done (experimental): `LinePhaseTest` runs every capability profile (OpenGL 3.3 to 4.6, with and without the draw-parameters extension, without texture buffers, storage buffers or instancing, and three Vulkan sets) over every strategy: the plan exists with well-formed buffers, draws and commands, and shader text that asks for nothing the profile lacks, or the request throws; with `LineRenderPlanTest` and `LineSetTest` for the pixels. There is no anti-aliasing, so the comparison is exact for the geometry. On a real driver: `LineGpuCheck` (samples module, `:vmath-samples:lineCheck`, and `LineGpuCheckTest`) compiles, links and runs every strategy at every GLSL version from 3.30 to 4.60, with styles in a storage block, a texture buffer and a uniform block, on four scenes: 177 of 177 cases agree on an NVIDIA GeForce RTX 3060, and a sensitivity case shows that the comparison fails for the wrong pixels. GPU-12 (glslang in CI) and GPU-15 (other vendors, automation) stay open.*
- [x] **LINE-9 (P2, S)** `docs/LINES.md`: what a user needs, the decision table (generated), how to issue the draws for each strategy in OpenGL and in Vulkan terms, and what each tier costs (memory, calls, CPU time), with numbers from the demos of `docs/DEMOS.md` (L1 to L3). → LINE-4
      *Done (experimental): `docs/LINES.md` has the pieces, how to use them, the generated decision tables, the set, trails, simplifying, clipping and culling, limits, a table of what each tier costs (memory, calls, CPU and GPU time) measured by `LineGpuCheck --bench`, and each strategy in Vulkan terms. The numbers come from that tool, and the demos L1 to L3 (`line-lab`, `line-styles`, `line-stream`) have measured the same tiers in a window since.*
- [ ] **MAP-1 (P1, L)** Geodesy on the ellipsoid, which does not exist yet: distance, bearing and destination point for geodesics (accurate series, tested against published reference vectors, not against another implementation of the same formulas), the fast spherical forms with their error bound stated, rhumb lines, cross-track and along-track distance for route legs, and the geodesic intersection of a circle or a line with a box for clipping. → `Wgs84`
- [ ] **MAP-2 (P1, L)** `MapProjection` (forward, inverse, scale and convergence at a point) with `WebMercator` adapted to it and these added: azimuthal equidistant about a centre (true range and bearing from the centre, so range rings are circles), polar stereographic, transverse Mercator with UTM zones and the military grid reference formatting, Lambert conformal conic. Double precision throughout, tested for round trips, for the known distortion of each, and against published coordinates. → MAP-1
- [ ] **MAP-3 (P1, M)** `MapView2d`: centre, scale or range, orientation (north up, course up, heading up, any angle), an offset of the centre of the view inside the viewport (own position near the bottom), viewport size, a projection; gives geographic to screen and back in double precision, the geographic bounds of the view, the matrix as a camera-relative float matrix for any `ClipSpace`, and the pixel size on the ground. A view on top of an orthographic `Cameraf`. → CAM-11, MAP-2
- [ ] **MAP-4 (P1, M)** Shapes on the ellipsoid as polylines and polygons, sampled to a tolerance in pixels of the current view: circles (range rings, threat domes), arcs and sectors, route corridors with a width, great-circle and rhumb legs; they feed `LineBatch` and the polygon triangulation and clipping that exist. → MAP-1, LINE-1
- [ ] **MAP-5 (P2, M)** A tile selector for flat maps over any `MapProjection` and the XYZ and TMS schemes: the tiles that cover the view at the pixel density that the view needs, with the hysteresis and the budget of the globe's `TileSelector`, but for a 2D view (the globe's one is built on a perspective camera). → MAP-3, `TileId`
- [ ] **MAP-6 (P2, M)** Symbols: a `@GpuStruct` for billboard symbols (position, heading, size in pixels, atlas rectangle, colour, flags such as "stay upright on a rotated map"), written in every tier of CAP-2 (instanced quads from OpenGL 3.30, expanded quads below that), and an atlas packer on top of `RectPacker`. No symbol set ships with the library: standards such as MIL-STD-2525 and APP-6 have their own licences, and the demos draw generic shapes. → LINE-3's mechanism
- [ ] **MAP-7 (P2, M)** Declutter and label placement as data only: greedy placement by priority of screen-space boxes against the ones already placed (the `UniformGrid` of the library), leader offsets, and stable results from frame to frame so that labels do not flicker. Text rendering stays in the engine (decision 3). → MAP-6
- [ ] **MAP-8 (P2, M)** Filled areas: batching of triangulated polygons (with holes) into index and vertex buffers submitted through DRAW-1 in every tier, outlines through the lines, and hatch and dot patterns as a style that the shader evaluates. → DRAW-1, `Polygons`
- [ ] **MAP-9 (P2, L)** Terrain on a map: hillshade and slope from elevation tiles (`TerrainRgb` and generic float tiles), colour ramps indexed by height or by height relative to a reference altitude with a margin (the way terrain-awareness displays colour terrain as above, near and below the aircraft; a generic "relative to a reference" ramp in the library), the ramp as a texture row, and a CPU viewshed (line of sight over the elevation grid, the shadow of a sensor) with the cost per cell measured. → MAP-5
- [ ] **MAP-10 (P3, S)** Units and readouts: nautical miles, feet, knots, feet per minute and their conversions as exact constants, latitude and longitude formatting (decimal degrees, degrees and minutes, degrees minutes and seconds), and bearing and range text. Independent of the rest.
- [ ] **MAP-11 (P3, S)** Display palettes: day, night and night-vision colour sets as lookup tables applied to a style table (a style table is one buffer, so a palette switch is one upload), and a viewport and scissor helper for a 2D view that is an inset of a larger window. → LINE-1
- [ ] **MAP-12 (P2, S)** `docs/MAPS.md` and cookbook recipes (a moving map with an own-position-centred view, range rings, a route with legs, tracks with trails, terrain colouring relative to altitude); written for any user, with the aircraft display as one of the worked examples. → MAP-3

---

## 4. Suggested order

1. **Phase A + INF-1/2** (framework, benchmarks, CI): everything after is written faster and verified.
2. **CORE-1…6** (uniform API, `Transform`/`Mat4x3`, int vectors).
3. **MEM-1…4, GEO-1…3, CULL-1…4**: the first end-to-end "cull 1M objects" story with numbers.
4. **GPU-1/3, CAM-1…4, FMT-1…3**: what an engine needs to submit frames.
5. Remaining P2 items by need; P3 as time permits.

## 5. Open questions (answered)

> **Decisions taken:** (1) `@DoubleOnly` members are written in final double form and copied verbatim, templates are not compiled
> directly; (2) baseline JDK is 25; (3) renderer-level pieces stay out; (4) Vector API is optional, scalar kernels first;
> (5) generated sources are built at build time. The original questions follow for reference.
>
> 1. **How do `@DoubleOnly` members compile inside a float source?** Options: (a) sidecar `Vec3d.extras` mixin files,
   (b) write the *double* file as the template and derive float, (c) a `Scalar`-generic template with a precision token.
   (a) is the smallest change; (c) is the cleanest long term. A one-day spike in AF-3 decides.
> 2. **Baseline JDK:** stay on 21 (FFM preview) or move to the current LTS (FFM final, and a shorter path to Valhalla)?
> 3. **Scope boundary:** is this library math + geometry + culling + GPU layouts (my assumption), with mesh/animation/scene as
   later modules, or should renderer-level pieces (frame graph, resource management, materials) live here too?
   I'd keep those in the engine and give vmath the math, data layouts and CPU reference algorithms.
> 4. **Vector API:** it's still incubator. Accept `--add-modules jdk.incubator.vector` for the fast path (with scalar fallback),
   or wait?
> 5. **Generated sources checked in or not (AF-6)?** Build-time generation is cleaner but requires the generator to run in every
   consumer's build if this is used as a source dependency.
