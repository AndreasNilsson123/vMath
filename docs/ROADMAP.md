# vmath roadmap

Goal: a complete 3D graphics foundation library for Java/LWJGL that is **extremely fast**, **memory-efficient**
and **feature-complete** (math, geometry, culling, spatial structures, GPU interop, mesh/animation support).

Priorities: **P0** blocks everything else, **P1** core of the promise, **P2** completeness, **P3** nice to have.
Sizes: S ≈ hours, M ≈ days, L ≈ 1–2 weeks, XL = multi-week. `→` marks dependencies.

---

## 1. Where we are

Solid base: 12 immutable `/*value*/ record`s (Vec2/3/4, Quat, Mat3/4 × f/d), JOML-oracle property tests,
Valhalla readiness test, std140 writers, and a float→double generator. ~2.4k lines of main code.

Findings from reading the code:

**Generation is text-based and comment-driven** (`tools/GenDouble.java`)
- Markers are magic comments (`// @float-only-begin/end`, `// @eps-double`, `/*value*/`). Nothing checks them
  (a typo silently changes output) and IDEs don't know about them.
- Conversion is per-line regex (`float`→`double`, `(float)` stripping, literal rewriting). It also rewrites
  comments and strings, and can't express precision-specific logic (`Math.fma`, `Float.MIN_NORMAL`,
  bit tricks, `FloatBuffer` vs `DoubleBuffer`, `float[]` bulk paths).
- Double-only members live as text blocks inside the generator (`DOUBLE_EXTRAS`), so they are uncompiled,
  untested until generated, and invisible to the IDE.
- Only `vmath/core` and the hard-coded filename regex `(Vec[234]|Quat|Mat[34])f` are handled. Every new
  package (`geo`, `bulk`, …) would need generator edits.
- The Valhalla switch is a *second* text hack (a Gradle `filter` replacing `/*value*/ record`), and
  `ValhallaReadinessTest` string-matches source for it.
- Generated `*d.java` are checked in, and a stale-check is needed to keep them honest.

**API surface is uneven across types**
- `Vec3f` has `add(x,y,z)`, `distanceSquared`, `angle`, `abs`, `normalizeOrZero`; `Vec2f`/`Vec4f` lack most of these
  (`Vec4f` has no `div`, `fma`, `min`, `max`, `abs`, `distance`).
- `isFinite` exists only on `Mat4f`. No `Mat3` axis-angle, no `Mat2` at all.
- Missing basics: `reflect/refract/project/clamp/floor/ceil/fract/saturate/smoothstep/sign/mix`, `Quat` from matrix /
  from-to / euler / look rotation, `Mat4` decomposition, `ortho`, `frustum`, inverse projection, `lookTo`.
- No integer vectors (grid/voxel/chunk coordinates, texture sizes).

**Output paths are float-only and narrow**
- `writeTo` covers `float[]` and `FloatBuffer` only. No `ByteBuffer`, no `MemorySegment`, no read-back (`readFrom`).
- `Std140` handles `vec3`, `mat3`, `mat4` only: no scalars, `vec2`, `vec4`, arrays, structs, std430 or scalar layout.

**Missing infrastructure**
- No JMH module (the README's "allocations disappear" claim is unverified), no CI, no `module-info.java`,
  no API-compat check, no javadoc build. Baseline JDK 21 predates FFM (`MemorySegment`) going final in 22.

**Nothing above the math layer yet:** no shapes, no culling, no spatial structures, no bulk containers.

---

## 2. Architecture and rules

Proposed modules (Gradle multi-project; each a JPMS module):

| Module | Contents |
|---|---|
| `vmath-annotations` | `@GenerateDouble`, `@FloatOnly`, `@DoubleOnly`, `@ValueType`, `@Eps`, … (source retention, zero runtime cost) |
| `vmath-codegen` | Generator tool, run as a Gradle plugin/task (not shipped) |
| `vmath-core` | Vec/Quat/Mat, scalar utils, packed formats |
| `vmath-geo` | Shapes, intersection tests, curves |
| `vmath-bulk` | SoA/`MemorySegment` containers and transform/cull kernels |
| `vmath-spatial` | BVH, octree, grid, culling framework |
| `vmath-gl` | GPU layouts (std140/std430), indirect-draw structs, camera/render math |
| `vmath-mesh` | Mesh processing, meshlets, tangents, LOD |
| `vmath-bench` | JMH suite (not published) |

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
- [ ] **AF-9 (P2, M)** Optional: annotation processor that *validates* (readiness rules, no `==` on value types,
      no `synchronized`) at compile time, replacing `ValhallaReadinessTest`'s string heuristics.
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
- [ ] **INF-6 (P1, M)** Restructure into multi-project Gradle build per the module table. → AF-2  
      *Partial: annotations/codegen/bench are modules; core/geo/spatial still share the root project, which is one JPMS module `vmath` exporting `vmath.core`, `geo`, `bulk`, `spatial`, `gl`. Splitting it further means per-module template directories and a generator that resolves family renames across modules.*
- [x] **INF-7 (P2, M)** Fuzzing/degenerate suite: zero vectors, denormals, NaN/Inf, huge magnitudes, near-singular
      matrices. Explicit expected behavior per op.  
      *Done for the 14 core types: `DegenerateInputSweepTest` (257 200 reflective calls, fails on unexpected exceptions or hidden NaN), `DegenerateContractfTest` (explicit cases, both precisions), `docs/ROBUSTNESS.md`. Found and fixed `normalize` of huge and tiny vectors. Shapes, meshes and bulk arrays are not covered.*
- [ ] **INF-8 (P2, M)** Oracle strategy for features JOML lacks (BVH, culling): brute-force reference implementations
      in tests, analytic cases, cross-precision (f vs d) comparison.
- [ ] **INF-9 (P2, S)** Code coverage (JaCoCo) and mutation testing (PIT) on `core`.
- [ ] **INF-10 (P3, S)** Publish to Maven Central/GitHub Packages with sources and javadoc jars.

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
- [ ] **CORE-4 (P1, M)** `Mat4`: `ortho`, `orthoReversedZ`, `frustum`, `perspectiveInfinite`, inverse projection,  
      *Partial: `frustum`, `perspectiveInfinite`, `lookTo`, `rotationX/Y/Z/Axis`, `decompose`, `isAffine` done; inverse projection and shear open.*
      `lookTo`, `rotationAxis`, `shear`, TRS decomposition (with negative-scale and shear handling), `isAffine`, `isOrthonormal`.
- [x] **CORE-5 (P1, L)** `Transform` (TRS) and `Mat4x3` affine (48 B instead of 64 B, ~25% less bandwidth, cheaper multiply/invert).  
      *Done: `Transformf`/`Transformd` (composition without matrices, non-uniform scale limits documented in its class comment) and `Mat4x3f`/`Mat4x3d` (`mul`, `invert`, `writeTo`).*
      Transform composition without matrices. Non-uniform scale semantics documented.
- [ ] **CORE-6 (P1, M)** `Vec2i/3i/4i` (+ long variants if needed): grid coordinates, hashing, min/max, conversions.  
      *Partial: `Vec2i` and `Vec3i` (hand-written, `IntVecTest`); `Vec4i` and long variants are open.*
- [ ] **CORE-7 (P2, M)** `Mat2`, `Mat2x3`/`Mat3x2` (2D transforms), 2D helpers (rotate, perp-dot, winding).
- [ ] **CORE-8 (P2, M)** Dual quaternions, `Pose` (Quat + Vec3, rigid-only, 28 B), rigid inverse/compose fast paths.
- [ ] **CORE-9 (P2, M)** Fast math: polynomial `sin/cos/atan2/acos/exp/log`, fast `invSqrt`, documented max error each,
      with tests against `Math`. Opt-in `FastMath` class, never silently substituted.
- [ ] **CORE-10 (P2, M)** Robustness: `Predicates` (orient2d/3d, incircle/insphere, adaptive-precision), `DoubleDouble` type,
      stable `normalize` / angle-between / cross-based formulas.
- [ ] **CORE-11 (P2, S)** Consistent `hashCode`/`equals` semantics doc (`-0.0`, NaN) and epsilon-hash helpers for spatial hashing.  
      *Partial: semantics documented and tested (`docs/ROBUSTNESS.md`); epsilon-hash helpers are open.*
- [ ] **CORE-12 (P3, M)** Optional `ToString`/`fromString` formats, `Vec.parse`, debug formatter for matrices.
- [ ] **CORE-13 (P2, M)** Transform-space utilities: frame-tagged transforms, `Geodetic/Ecef/WGS-84` (from the existing "next steps"),
      camera-relative rendering helpers beyond `relativeTo` (rebasing a whole scene, double→float model matrices).

### Phase D. Bulk data and memory efficiency (P1)

- [ ] **MEM-1 (P1, L)** `vmath-bulk` SoA containers (`Vec3fArray`, `Vec4fArray`, `QuatArray`, `Mat4fArray`, `TransformArray`)  
      *Partial: `BoundsArray`, `Mat4fArray`, `Vec3fArray`, `QuatArray`, `TransformArray`, `VisibilitySet`, `IntList` on `float[]` (`docs/BULK.md`); `Vec4fArray`, `MemorySegment` (off-heap) storage and compaction are open.*
      over `float[]` (heap) and `MemorySegment` (off-heap) with one interface-free API pair. Growth policy, capacity, compaction.
- [ ] **MEM-2 (P1, L)** Kernels: batch transform points/normals, matrix multiply, TRS compose, quaternion normalize/slerp,  
      *Partial, scalar versions: `BoundsArray.transformFrom`, `Vec3fArray` transform positions/directions and normalize, `QuatArray` normalize/multiply/slerp/toMatrices, `TransformArray` toMatrices/blend, measured in `docs/BULK.md` (`QuatArray.slerp` is 72 ns per element). Matrix multiply and the Vector API variants are open.*
      AABB transform. Scalar versions first, then Vector API (incubator) variants selected at startup. → MEM-1, INF-1
- [ ] **MEM-3 (P1, M)** Generate the SoA container and scalar loops from the scalar ops via `@Kernel`/`@Bulk`, so
      new ops don't need hand-written loops. → AF-2
- [ ] **MEM-4 (P1, M)** `writeTo`/`readFrom` for `ByteBuffer` (with `ByteOrder`), `MemorySegment`, and strided/interleaved variants.  
      *Partial: `FloatBuffer` writers on the containers; `MemorySegment` and `ByteBuffer` strided/byte-order writers and readers (`Strided`, container `writeTo`/`readFrom`) done; named interleaved vertex-layout writers open (`MeshExport` covers the common mesh case).*
      Interleaved vertex writers for common layouts.
- [ ] **MEM-5 (P1, M)** Allocators: arena, slab/pool, free-list and ring allocators over `MemorySegment`; persistent-mapped
      buffer ring (N frames in flight) with fence tracking hooks.
- [ ] **MEM-6 (P2, M)** Handle/generation-index registry (sparse set) for entity IDs, with dense array iteration.
- [ ] **MEM-7 (P2, M)** Dirty-flag/change-tracking bitsets for incremental GPU upload (upload only changed ranges).
- [ ] **MEM-8 (P2, M)** Radix sort (float keys, 32/64-bit) and parallel-friendly prefix sums, for draw sorting and BVH build.
- [x] **MEM-9 (P3, M)** Thread-parallel kernel driver (`ForkJoin`/virtual-thread-free) with chunking and false-sharing avoidance.  
      *Done for frustum culling: `ParallelFrustumKernel` (caller-supplied executor, chunks at multiples of 64, bit-identical, 3x at 8 chunks); other kernels do not exist in bulk form yet.*

### Phase E. Compact and packed formats (P1/P2)

- [x] **FMT-1 (P1, M)** `Half` (float16) conversion, bulk convert, `Vec2h/3h/4h` as packed storage only.  
      *Done: `vmath.pack.Half` over the JDK's `Float.floatToFloat16`, bulk arrays and 2/4-in-a-word packing; exhaustively tested over all 65536 patterns. Dedicated `Vec2h/3h/4h` types were not needed and are not built. See `docs/FORMATS.md`.*
- [x] **FMT-2 (P1, M)** Normalized/packed types: `snorm/unorm 8/16`, `RGB10A2`, `R11G11B10F`, `RGB9E5`, with GL/Vulkan format tokens.  
      *Done: `Norm`, `SmallFloat`, `PackedFormat` (tokens verified against the Khronos headers).*
- [x] **FMT-3 (P1, M)** Octahedral normal/tangent encoding (2×16 or 2×8 bit), tangent-frame-as-quaternion (smallest-three, 32 bit).  
      *Done: `Octahedral` with best-of-four rounding and `QuatPacked`; measured error bounds are asserted in the tests.*
- [ ] **FMT-4 (P2, M)** Position quantization with bounds (`unorm16` × AABB), meshopt-style attribute quantization.  
      *Partial: `Quantizer` (unorm16 over an `Aabbf`, with dequantization matrix) done; a full meshopt-style attribute pipeline belongs with mesh processing (MESH-3).*
- [ ] **FMT-5 (P2, S)** Morton/Hilbert codes (2D/3D, 32/64-bit) for locality sorting and BVH/linear-octree construction.  
      *Partial: `Morton` (2D 32-bit/axis, 3D 21-bit/axis) done; Hilbert curves are not.*
- [ ] **FMT-6 (P2, S)** Color: sRGB↔linear (exact + fast), HSV/HSL/Oklab, luminance, tone-map curves, premultiplied alpha.

### Phase F. GPU interface (P1)

- [x] **GPU-1 (P1, L)** Layout framework `std140` / `std430` / `scalar` / tight, driven by a `@GpuStruct` annotation:  
      *Done: `GlslType`/`StructLayout`/`GpuWriter` for std140, std430 and scalar, and `@GpuStruct` generated `<Name>Gpu` writers with GLSL text; see `docs/GPU.md`.*
      generate layout computation, size/alignment constants and writers (scalars, vec2/3/4, mat2/3/4, arrays, nested structs). → AF-2
- [ ] **GPU-2 (P1, S)** Layout validator that compares the generated layout to reflection data from the shader
      (SPIR-V reflection or GL introspection) in an opt-in test.
- [x] **GPU-3 (P1, M)** Indirect draw command structs (`DrawArraysIndirect`, `DrawElementsIndirect`, multi-draw), compute
      dispatch structs, and packed instance-data writers.  
      *Done: `DrawArraysIndirect`, `DrawElementsIndirect`, `DispatchIndirect` (`@GpuStruct`, sizes 16/20/12 checked against the GL and Vulkan specs), `DrawCommandBuffer` (multi-draw runs, optional 16-byte stride, instance-count zeroing for culling), `InstanceWriter` (transform rows plus user data); `docs/GPU.md`.*
- [ ] **GPU-4 (P2, M)** Vertex-format descriptions (attribute, stride, offsets) and interleaved-buffer builders that emit the
      matching GL/Vulkan attribute specs.
- [ ] **GPU-5 (P2, M)** GLSL/Slang shared-header generator so shader code and Java use one struct definition. → GPU-1
- [x] **GPU-6 (P2, M)** Clip-space conventions as a type (`GL`, `Vulkan` (Y-down), `D3D`), applied consistently by
      projection builders. Removes the boolean `zZeroToOne` parameter.  
      *Done for the matrix builders: `ClipSpace` (`OPENGL`, `VULKAN`, `D3D`), `ClipSpace` overloads of `Mat4f.perspective`/`perspectiveInfinite`/`perspectiveReversedZ`/`ortho`/`frustum`, `Mat4f.flipY`, `DepthRange.of`. The boolean overloads stay (japicmp). `Cameraf` and its screen helpers still assume y-up NDC (changing its record components would be an API break); see `docs/CAMERA.md`.*

### Phase G. Geometry primitives (P1)

Value records for single shapes; SoA storage in `vmath-bulk` for large sets.

- [x] **GEO-1 (P1, M)** `Aabb`, `Sphere`, `Plane`, `Ray`, `Segment`, `Triangle`, `Capsule`, `Obb`, `Frustum` (six planes, 96 B),  
      *Done: all nine shapes (`Segment`, `Capsule` added with closest point, bounds, transform; capsule radius scales by the largest axis scale).*
      with construct/merge/expand/transform/contain/closest-point.
- [ ] **GEO-2 (P1, L)** Intersection matrix, all pairs: ray×{aabb, sphere, plane, tri, obb, capsule}, aabb×{aabb, sphere, plane,  
      *Partial: ray x {aabb, sphere, plane, triangle}, plane x {aabb, sphere}, sphere x {aabb, sphere, triangle} done; segment-segment, segment-aabb, sphere/capsule/aabb x capsule, capsule x capsule, ray-capsule, OBB-OBB (15-axis SAT) and aabb-triangle (13-axis SAT) done; ray-OBB, sphere-OBB, plane-OBB, plane-triangle and sphere-sphere sweep done; sweeps against boxes and triangles, and capsule sweeps, open.*
      tri, obb}, sphere×{sphere, plane, tri}, sweep tests, and distance queries. Each with conservative/exact variants documented.
- [x] **GEO-3 (P1, M)** Robust ray-triangle (watertight, Woop et al.), slab test with correct NaN/0-direction handling.  
      *Done: watertight ray-triangle.*
- [ ] **GEO-4 (P2, M)** Bounding-volume fitting: min sphere (Welzl), PCA OBB, k-DOP, bounding-volume from transformed AABB
      (Arvo, exact for affine).
- [ ] **GEO-5 (P2, M)** Convex hull (3D quickhull), convex polytope intersection, GJK/EPA distance + penetration, SAT helpers.
- [ ] **GEO-6 (P2, M)** Curves and interpolation: Bézier, Hermite, Catmull-Rom, B-spline, arc-length parameterization, easing.
- [ ] **GEO-7 (P2, M)** Polygon utilities: 2D/3D triangulation (ear clipping), polygon clip (Sutherland–Hodgman), winding.
- [ ] **GEO-8 (P3, L)** Signed distance function primitives and CSG combinators (CPU-side, for picking and mesh generation).

### Phase H. Spatial structures and culling framework (P1)

Design: culling produces a **visibility bitset / compact index list**, not per-object callbacks. Cullers are stages that
can be chained and composed, and they run on SoA bounds.

- [x] **CULL-1 (P1, L)** Culling framework core: `CullContext` (camera, frustum, LOD bias), `Cullable` bounds in SoA,  
      *Done: `CullContext`, `CullStage`, `CullPipeline`, `VisibilitySet`, stages: frustum, distance, small-feature.*
      `VisibilitySet` (bitset + compaction to index list), `CullStage` chain (frustum → distance → occlusion → small-feature).
      Zero-allocation per frame. → MEM-1
- [ ] **CULL-2 (P1, M)** Frustum extraction (Gribb–Hartmann, both clip conventions, reversed-Z, infinite far), plane normalization,  
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
- [ ] **CULL-7 (P1, L)** Occlusion culling: software Hi-Z rasterizer on the CPU (SIMD depth tile test), plus the data structures
      for GPU Hi-Z (mip-chain sizing, two-phase culling contract).  
      *Partial: `vmath.occlusion` with `DepthBuffer` (inner-conservative polygon rasterizer, farthest-depth storage, min pyramid, near-plane clipping), `OcclusionStage`, and `HiZ` (pyramid sizing, two-phase contract); property-tested against a ray-versus-box oracle. The SIMD tile test is not built (about 70 ns per tested object today); perspective projections only.*
- [x] **CULL-8 (P2, M)** LOD selection: distance/screen-space-error metrics, hysteresis, cross-fade factors, output to the visibility set.
      *Done: `LodSelector` (bounding-sphere screen size, descending thresholds, hysteresis, cross-fade, cull-below, bias; history in a caller-owned `byte[]`). Per-object thresholds are not built.*
- [x] **CULL-9 (P2, M)** Small-feature / contribution culling, backface cluster cone culling (meshlet cone test).
      *Done: small-feature stage (earlier) and `ConeCull` (cone builder, conservative sphere+cone test, orthographic variant, SoA `Clusters`).*
- [ ] **CULL-10 (P2, L)** Portal/sector culling for interiors, PVS import hooks.
- [x] **CULL-11 (P2, M)** Shadow culling: cascade frustum culling, light-space bounds, caster/receiver classification,
      point/spot light volumes, cube-face selection.  
      *Done: `CascadeCasters` (tight light-space caster test per cascade), `LightCull` (point, spot, cube-face masks). Caster/receiver classification bits are not built: the tight caster test covers the same saving.*
- [x] **CULL-12 (P2, M)** Spatial queries: ray cast, k-NN, AABB/sphere/frustum overlap, all against BVH/grid, no allocation  
      *Done: AABB/sphere overlap, frustum and ray queries on the trees; k-NN (`Neighbors`) on the BVH, dynamic tree, grid and octree; overlap on the grid and octree.*
      (caller-provided result buffer).
- [ ] **CULL-13 (P3, L)** GPU-driven culling support: compute-shader Hi-Z + frustum culling that writes indirect draw buffers,
      with the CPU-side layouts from GPU-1/GPU-3 and a CPU reference implementation used as the test oracle.
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
- [ ] **CAM-5 (P2, S)** Cubemap face matrices, omnidirectional shadow/probe setup, dual-paraboloid.  
      *Partial: `CubeFaces` (GL/Vulkan face orientation, view, projection, frustum, face-of-direction) done; dual-paraboloid is not.*
- [ ] **CAM-6 (P2, M)** Oblique near-plane clipping (planar reflections), portal camera transforms, stereo/VR projection.
- [ ] **CAM-7 (P2, S)** Physical camera model: exposure (EV100), FOV↔focal length, depth-of-field parameters.
- [x] **CAM-8 (P2, M)** Depth utilities: linearize depth for all conventions, depth reconstruction of view position, Z-slice for clustered lighting.  
      *Done: `linearizeDepth`, `viewPositionFromDepth`, `worldPositionFromDepth`; Z-slices for clustered lighting are not built. See `docs/CAMERA.md`.*
- [ ] **CAM-9 (P2, M)** Clustered/tiled light assignment math (froxel bounds, light-vs-cluster tests) with CPU reference.
- [ ] **CAM-10 (P3, M)** Sun/sky, atmosphere and time-of-day helpers (solar position).

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
- [ ] **ANIM-3 (P2, M)** Inverse kinematics (two-bone, FABRIK, CCD) and look-at constraints.
- [ ] **ANIM-4 (P3, M)** Morph targets/blend shapes packing, root motion extraction, animation compression (curve fitting, quantized keys).
- [ ] **ANIM-5 (P3, L)** Physics-adjacent math: rigid-body integrators, inertia tensors from shapes, contact manifold math
      (only the math; the engine would be a separate project).

### Phase L. Utilities (P2/P3)

- [ ] **UTIL-1 (P2, M)** Random: PCG/xoshiro (fast, seedable, splittable), sampling on sphere/hemisphere/disk, cosine-weighted,
      Poisson-disk, low-discrepancy (Halton, Sobol, R2), blue-noise tables.
- [ ] **UTIL-2 (P2, M)** Noise: value/Perlin/simplex/Worley/curl, fBm, domain warping, 2D/3D/4D, batch fill APIs into `float[]`.
- [ ] **UTIL-3 (P2, S)** Interpolators and smoothing: critically-damped spring, exponential smoothing (frame-rate independent), easing set.
- [ ] **UTIL-4 (P3, M)** Frame-timing/statistics helpers (rolling percentiles) for the bench/diagnostic layer.
- [ ] **UTIL-5 (P3, M)** SH (spherical harmonics) L1/L2 projection/evaluation, IBL prefilter math, BRDF LUT generator.
- [ ] **UTIL-6 (P3, M)** Debug draw geometry generators (lines for frustum/AABB/OBB/skeleton) into `float[]`.

### Phase M. Documentation and release (P1/P2)

- [x] **DOC-1 (P1, M)** `docs/PERFORMANCE.md` (contract from §2), `docs/CODEGEN.md`, `docs/API.md` parity table.  
      *Done: all three exist; the API table is backed by `ApiParityTest`.*
- [ ] **DOC-2 (P2, M)** Cookbook: "camera-relative rendering", "culling 1M instances", "GPU-driven pipeline", "migrating from JOML".
- [x] **DOC-3 (P2, S)** ~~JOML adapter module~~ *Dropped on purpose: it would be double bookkeeping; the README migration table is the bridge.*
- [x] **DOC-4 (P2, S)** Changelog + semver policy; mark experimental APIs (`@Experimental` annotation, also in the framework).  
      *Done: `CHANGELOG.md`, `docs/VERSIONING.md`, `vmath.annotations.Experimental` (class retention, excluded from japicmp). The framework side does not exist yet.*
- [ ] **DOC-5 (P3, M)** Sample app (LWJGL) that renders and culls 1M instances; doubles as an end-to-end benchmark.  
      *Partial: headless `CullAndDrawSample` (cull 1M, write instance buffer + indirect draw, numbers in `docs/GPU.md`); an LWJGL window that actually draws is open; `FrameBench` compares serial/parallel/BVH frames; `InstanceWriteBench` tuned the instance write (word-wise set walk, about 25% faster; packed centres and staged bulk copy rejected, numbers in `docs/GPU.md`).*

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
