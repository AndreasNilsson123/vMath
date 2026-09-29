# vmath

Immutable 3D math for a Java/LWJGL engine, built so every type can become a Valhalla
`value record` by flipping a build flag.

```
src/template/java         float templates (Vec2/3/4, Quat, Mat3/4): the single source of truth
src/testTemplate/java     float test templates
src/template/java/vmath/geo  Aabb, Sphere, Plane, Ray, Triangle, Obb, Frustum, ray/shape intersections
src/main/java/vmath/bulk  SoA containers: BoundsArray, Mat4fArray, VisibilitySet, IntList
src/template/java/vmath/camera  Camera (view/projection/pick/unproject), plus Jitter and Cascades in src/main
src/main/java/vmath/spatial  culling framework: FrustumCuller, CullPipeline, StaticBvh, BvhQuery
src/main/java/vmath/gl    GPU layouts (std140/std430/scalar), writers, @GpuStruct generated classes
src/main/java/vmath/pack  compact formats: half, unorm/snorm, RGB10A2, R11G11B10F, RGB9E5, octahedral, quaternions
src/main/java/vmath/occlusion  software Hi-Z occlusion culling (conservative depth buffer, pipeline stage, GPU Hi-Z sizing)
src/main/java/vmath/mesh  indexed meshes, primitives, normals/tangents, weld/cache/fetch optimisation, GPU export
src/test/java             JOML-oracle helpers + Valhalla readiness checks
vmath-annotations         @GenerateDouble, @FloatOnly, @DoubleOnly, @Eps, @ValueType
vmath-codegen             build-time generator (float template -> float + double types)
vmath-simd                optional Vector API kernels (needs --add-modules jdk.incubator.vector)
vmath-bench               JMH benchmarks
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

| JOML | vmath |
|---|---|
| `m.mul(b)` (mutates `m`) | `m = m.mul(b)` |
| `m.mul(b, dest)` | `dest = m.mul(b)` |
| `new Matrix4f().perspective(...)` | `Mat4f.perspective(...)` |
| `m.translate(t)` | `m = m.mul(Mat4f.translation(t))` |
| `m.get(buf)` | `m.writeTo(buf, buf.position())` |
| `q.transform(v)` (mutates `v`) | `v = q.transform(v)` |

A thin adapter class with `toJoml` / `fromJoml` lets you migrate one subsystem at a time.

## Build and benchmarks

Requires JDK 25 (the baseline moves to the newest JDK; there is no LTS constraint).

```
./gradlew build                                               # generate, compile, test everything
./gradlew :vmath-bench:jmh -Pjmh.args="-prof gc CoreBench"    # JMH with allocation profile
./gradlew build -Pvmath.buildRoot=C:/tmp/vmath-build          # keep build output out of a OneDrive checkout
```

Allocation findings and the performance contract are in [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Next steps

The full backlog is in [docs/ROADMAP.md](docs/ROADMAP.md); design notes are in `docs/` (CODEGEN, CULLING, GPU, CAMERA, FORMATS,
PERFORMANCE, API-COMPAT). Not built yet, roughly in order of value:

- A SIMD occlusion test, temporal coherence for occlusion queries, and portal culling (the rest of culling is built: frustum
  kernels, BVH, dynamic tree, grid, octree, k-NN, LOD, cone, shadow, light and occlusion culling; see `docs/CULLING.md`).
- Mesh processing (tangents, vertex-cache optimization, meshlets, simplification) and a glTF loader.
- Animation (skinning, blending, IK) and a scene-transform hierarchy in SoA form.
- Indirect-draw and vertex-format structs, and a shared GLSL header generator.
- Random and noise utilities, color spaces, curves.
