# vmath

Immutable 3D math for a Java/LWJGL engine, built so every type can become a Valhalla
`value record` by flipping a build flag.

```
src/main/java/vmath/core   Vec2/3/4, Quat, Mat3, Mat4. The f variants are hand-written, the d variants are generated
src/main/java/vmath/gl     std140 layout helpers
src/test/java/vmath/core   JOML-oracle property tests + Valhalla readiness checks
tools/GenDouble.java       float -> double source generator
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

1. Each type is a `public /*value*/ record` whose components are all primitives, with no interfaces.
2. No `==` or `!=` on math types. Use `equals` (exact) or `approxEquals` (tolerant).
3. No `synchronized`, weak/soft references or identity hash maps keyed by math types.
4. Operations return new values. Don't add mutable "dest" parameters, because escape analysis
   (today) and value types (later) make short-lived allocations cheap.
5. Bulk data (vertex arrays, instance transforms) belongs in `float[]`, `FloatBuffer` or `MemorySegment`,
   not `Vec3f[]` or `Mat4f[]`. Early Valhalla builds will flatten small value arrays at best, and a
   64-byte `Mat4f` probably not at all.

## Float is the source of truth

Edit only the `*f.java` files and `*fTest.java` files, then run:

```
java tools/GenDouble.java          # regenerate the *d.java and *dTest.java files
java tools/GenDouble.java --check  # CI: fails if generated files are stale (also runs in `gradle check`)
```

The generator understands these markers:

- `// @float-only-begin` … `// @float-only-end` marks code dropped from the double version (for example `toDouble()`).
- `// @eps-double 1e-12` on a field declaration replaces that field's value in the double test.
- Members that exist only on double types (`toFloat()`, `Vec3d.relativeTo` for camera-relative
  rendering) are declared in `GenDouble.DOUBLE_EXTRAS`.

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

This rewrites each `public /*value*/ record` to `public value record` into `build/generated/valhalla`,
then compiles and tests with `--release 28 --enable-preview`. Only the modifier changes, so both builds
run identical code and identical tests. Requirements:

- A JDK 28 early-access build from jdk.java.net/28. JEP 401 is integrated there as a preview feature.
  If Gradle doesn't detect it automatically, point it there with
  `org.gradle.java.installations.paths=/path/to/jdk-28` in `~/.gradle/gradle.properties`.
- A Gradle version that recognizes Java 28 toolchains.

Benchmark both modes with JMH before switching the engine over. JEP 401 is expected to stay in preview
through the JDK 29 LTS.

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

## Next steps

- `vmath.bulk`: structure-of-arrays containers with transform kernels.
- `vmath.geo`: `Geodetic`, `Ecef`, WGS-84 conversions, frame-tagged transforms.
- A JMH module that checks allocations actually disappear (`-prof gc`) on the hot paths.
- Frustum planes / culling, `Mat4` decomposition (TRS extraction), `Quat` from rotation matrix.
