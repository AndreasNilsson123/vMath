# vmath

Immutable 3D math for a Java/LWJGL engine, built so every type can become a Valhalla
`value record` by flipping a build flag.

```
src/template/java         float templates: Vec2/3/4, Quat, Mat3/4, Mat4x3, Transform, the geo shapes and intersections, Camera: the single source of truth for the f and d types
src/testTemplate/java     float test templates (JOML-oracle property tests, generated for both precisions)
src/main/java/vmath/
  core        hand-written core types: integer vectors, Morton and Hilbert codes, SpatialHash, ClipSpace
  bulk        SoA containers (Vec3/Vec4/Quat/Mat4/Transform/Bounds arrays, off-heap SegmentFloatArray), kernels, radix sort, prefix sums, DirtyRanges, HandleRegistry, VisibilitySet
  spatial     culling framework: FrustumCuller, CullPipeline, StaticBvh, DynamicAabbTree, UniformGrid, LooseOctree, LOD, light and cascade culling
  occlusion   software Hi-Z occlusion culling (conservative depth buffer, pipeline stage, GPU Hi-Z sizing)
  camera      Jitter, Cascades, CubeFaces, PlanarViews, Stereo, DualParaboloid, clustered lighting (ClusterGrid, ClusterLights)
  mesh        indexed meshes, primitives, normals/tangents, simplification, meshlets, cluster LOD, UV atlas, optimisation, GPU export
  anim        skeletons, clips, poses, skinning, transform hierarchy
  gltf        glTF 2.0 loader     tex   KTX2 and texture layouts
  pack        compact formats: half, unorm/snorm, RGB10A2, R11G11B10F, RGB9E5, octahedral, quaternions, quantizers
  gl          GPU layouts (std140/std430/scalar), @GpuStruct writers, indirect draws, vertex formats, shader headers, layout validation
  gpucull     GPU-driven culling: layouts and CPU reference passes, Hi-Z model, compute shader text
  mem         allocators: arena, slab, free list, ring, persistently mapped upload ring
  color       sRGB, HSV/HSL/Oklab, tone mapping, premultiplied alpha
src/test/java             oracle helpers, contract tests (allocation, API parity, equality, module), cookbook tests
vmath-annotations         @GenerateDouble, @FloatOnly, @DoubleOnly, @Eps, @ValueType, @GpuStruct, @Experimental
vmath-codegen             build-time generator (float template -> float + double types, @GpuStruct writers)
vmath-simd                optional Vector API kernels (needs --add-modules jdk.incubator.vector)
vmath-bench               JMH benchmarks and a headless sample
```

## Conventions

| Topic | Choice |
|---|---|
| Storage | Column-major. `mCR` = column C, row R (JOML naming). Translation is `m30, m31, m32`. |
| Handedness | Right-handed; the camera looks down −Z. |
| Angles | Radians everywhere. |
| Composition | `a.mul(b)` = `a × b`, so `b` is applied first. The same holds for quaternions. |
| Depth | `perspective(..., zZeroToOne)` supports both GL [−1,1] and [0,1]. `perspectiveReversedZ` is infinite-far, [0,1] depth, and maps near to 1. |
| Buffers | `writeTo(buf, index)` is an absolute write that never moves the buffer position. Matrices upload with `transpose = false`. |
| Normalizing zero | `normalize()` returns NaN (same as JOML). `Vec3.normalizeOrZero()` returns ZERO. |

## Rules that keep it Valhalla-ready

`ValhallaReadinessTest` enforces these:

1. Each type is a `@ValueType public record` whose components are primitives or other value records (such as `Frustumf`, which is six `Planef`), with no interfaces.
2. No `==` or `!=` on math types. Use `equals` (exact) or `approxEquals` (tolerant).
3. No `synchronized`, weak/soft references or identity hash maps keyed by math types.
4. Operations return new values. Don't add mutable "dest" parameters, because escape analysis
   (today) and value types (later) make short-lived allocations cheap.
5. Bulk data (vertex arrays, instance transforms) belongs in `float[]`, `FloatBuffer` or `MemorySegment`,
   not `Vec3f[]` or `Mat4f[]`. Early Valhalla builds will flatten small value arrays at best, and a
   64-byte `Mat4f` probably not at all.

## Float is the source of truth

Edit only the templates under `src/template/java` and `src/testTemplate/java`. `./gradlew build` generates both the
float and the double types into `build/generated/`: nothing generated is checked in. Precision differences are
declared with annotations (`@FloatOnly`, `@DoubleOnly`, `@Eps`), not comments. See [docs/CODEGEN.md](docs/CODEGEN.md).

## Tests: JOML as the oracle

Every operation is checked against JOML on 2 000 seeded random inputs. Inputs include well-conditioned
TRS matrices, diagonally dominant dense matrices and uniformly random unit quaternions. There are also
properties the oracle doesn't cover, such as `M × M⁻¹ = I`, `a.mul(b)` applying `b` first,
`q ≅ −q`, and reversed-Z depth mapping near to 1 and infinity to 0.

```
./gradlew test                                        # fixed seed, reproducible
./gradlew test -Dvmath.seed=$(date +%s) -Dvmath.trials=20000   # exploratory / nightly
```

Every failure message includes the seed and trial number, so you can replay it exactly.

JOML is an oracle, not ground truth. In two known places we intentionally differ, and the tests say so:

- **JOML 1.10.8 `Quaterniond.rotationX`** has a bug: it returns `(sin, 0, cos, 0)`. It is fixed on JOML's
  main branch. Axis rotations are therefore compared against `rotationAxis` instead.
- **Quaternion `w` near 180°.** JOML computes `w` as `sqrt(1 − sin²)`, which loses about half the digits.
  We call `cos` directly, so those comparisons use a looser tolerance, and a separate test checks our
  own accuracy against `Math.cos` in double.
- **`Quat.angle()`** returns the shortest-arc angle in [0, π]. JOML returns [0, 2π].

A mutation check (flipping one sign in `Mat4f.invert`) fails four tests across both precisions, so the
harness does catch real regressions.

## Valhalla mode

```
./gradlew build -Pvalhalla
```

The generator emits `public value record` for every `@ValueType` type, then everything compiles and tests with
`--release 28 --enable-preview`. Only the modifier changes, so both builds run identical code and identical tests. Requirements:

- A JDK 28 early-access build from jdk.java.net/28. JEP 401 is integrated there as a preview feature.
  If Gradle doesn't detect it automatically, point it there with
  `org.gradle.java.installations.paths=/path/to/jdk-28` in `~/.gradle/gradle.properties`.
- A Gradle version that recognizes Java 28 toolchains.

`vmath-simd` and `vmath-bench` follow the same switch (Java 28 with `--enable-preview`), and the `jmh` task launches on the
matching JDK. Verified on JDK 28-ea+17 (mainline, `jdk-28` from `~/.jdks`): all 494 tests pass with real value records.

Benchmark both modes with JMH before switching the engine over; the first measurements are in `docs/PERFORMANCE.md`
("Valhalla measured": no allocation for single operations, but `Mat4f.invert` and some chains got slower). JEP 401 is
expected to stay in preview through the JDK 29 LTS.

## Migrating from JOML

The types are immutable records, so `m.mul(b)` returns a new matrix instead of changing `m`, and builders such as `new Matrix4f().perspective(...)` are static factories (`Mat4f.perspective(...)`). `docs/COOKBOOK.md` has the full
side-by-side table and a tested example of each operation.

## Limitations

What the library does not do (yet), in one place; each row names the document that has the details. Items without a reference are open roadmap items.

| Area | Limitation | Details |
|---|---|---|
| GPU layer | The shader text, the layout validator and the upload ring are tested against Java references and simulations only; nothing has run on a graphics API | `docs/GPU.md`, `docs/MEMORY.md`, `docs/technical-debt.md` TD-01 |
| GPU culling | Axis-aligned boxes only (no oriented boxes or spheres in the object pass); the Hi-Z test finds about half of the rectangles that are exactly hidden (it never hides a visible one) | `docs/GPU.md` |
| Cluster LOD | `ClusterHierarchy` carries positions only, so skinned and multi-attribute meshes cannot be reduced into a cluster hierarchy (`MeshSimplifier` does support attributes) | `docs/MESH.md` |
| Clustered lighting | Spot lights are assigned to up to 47% more clusters than they touch (conservative, never fewer) | `docs/CAMERA.md` |
| Cameras | No single culling frustum for both eyes of a stereo pair; the physical camera model is thin-lens only (no breathing, vignetting or bokeh) | `docs/CAMERA.md` |
| Culling | No portal cameras (mirrors, portal views) or visibility compiler for portal culling, no temporal coherence for occlusion queries, no SIMD occlusion test | `docs/CULLING.md` |
| Animation | No joint limits for the inverse kinematics, no dual-quaternion skinning or cubic-key compression; `AnimationClip` interpolates linearly (the glTF loader converts STEP and CUBICSPLINE curves) | `docs/ANIMATION.md` |
| Physics | Math only: no broadphase integration, islands, sleeping, joints or continuous collision; the solver uses a friction pyramid and the manifold builder tests all 156 axes of two boxes (no hill climbing) | `docs/PHYSICS.md` |
| Colour | No wide-gamut spaces (Display P3, Rec. 2020), no gamut mapping, no AgX or other image-formation transforms | `docs/COLOR.md` |
| Formats | No entropy-coded vertex and index buffer compression (the quantization it needs is built) | `docs/FORMATS.md` |
| Geometry and maths | No NURBS, dual quaternions; the convex queries (`Gjk`) only converge to about 1e-3 of the radius in penetration depth for nearly concentric smooth shapes; `SurfaceNets` rounds sharp edges of a signed distance field by about a cell and samples the whole box | `docs/GEOMETRY.md` |
| Utilities | No blue-noise tables, scrambled or higher-dimensional Sobol sequences, 4D simplex noise | `docs/ROADMAP.md` |
| Modularity | One JPMS module exports all packages; a consumer cannot depend on a subset | `docs/technical-debt.md` TD-11 |

## Build and benchmarks

Requires JDK 25 (the baseline moves to the newest JDK; there is no LTS constraint).

```
./gradlew build                                               # generate, compile, test everything
./gradlew :vmath-bench:jmh -Pjmh.args="-prof gc CoreBench"    # JMH with allocation profile
./gradlew build -Pvmath.buildRoot=C:/tmp/vmath-build          # keep build output out of a OneDrive checkout
```

Allocation findings and the performance contract are in [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Next steps

Versioning and the `@Experimental` marker: [docs/VERSIONING.md](docs/VERSIONING.md); changes: [CHANGELOG.md](CHANGELOG.md). The backlog is [docs/ROADMAP.md](docs/ROADMAP.md) and the known weaknesses are in
[docs/technical-debt.md](docs/technical-debt.md); design notes and measurements are in `docs/` (CODEGEN, CULLING, GPU, CAMERA, FORMATS, TEXTURES, GLTF, ROBUSTNESS, PERFORMANCE, API-COMPAT, BULK, MEMORY, COLOR, COOKBOOK, EQUALITY, COVERAGE,
PUBLISHING, FASTMATH, GEOMETRY, UTIL, CURVES, PHYSICS, API; the first review of the code is kept in `docs/history.md`).

The largest open items: temporal occlusion culling, and running the GPU layer
against a real graphics API (the shader text and the upload ring are only tested against Java references so far).

## License

MIT, see [LICENSE](LICENSE): free to use, copy, modify, merge, publish and distribute, in source or binary form, as long as the copyright notice and the licence text are kept
(that is the credit the licence asks for).
