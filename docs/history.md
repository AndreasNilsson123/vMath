# History: the first review of the code

This is the "Where we are" section of `docs/ROADMAP.md` as it stood when the project started (a 2.4k-line prototype of twelve record types). It is kept because the phases of the
backlog answer these findings one by one, not because it describes the repository today; the current state is in section 1 of the roadmap and in the other documents of this directory.

## The findings

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

