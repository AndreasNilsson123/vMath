# Code generation

vmath is written once, in float, and the build generates everything else. There are no checked-in generated
files: `./gradlew build` runs the `generateSources` task, which writes to `build/generated/sources/vmath/`.

```
src/template/java      float templates for main   ─┐
src/testTemplate/java  float templates for tests   ├─► vmath-codegen ─► build/generated/sources/vmath/{main,test}
src/main/java          hand-written code           ┘     (float twin + double twin, both emitted)
codegen-renames.properties   extra renames for the double output (the JOML oracle types)
```

## Annotations

They live in `vmath-annotations`, have source retention, and are removed from the generated output.

| Annotation | On | Effect |
|---|---|---|
| `@GenerateDouble` | type | Emit `Xf` (float) and `Xd` (double). `Vec3f` → `Vec3d`, `Vec3fTest` → `Vec3dTest`; `twin = "..."` overrides. |
| `@FloatOnly` | member | Only in the float output (`toDouble()`). Written as ordinary float code. |
| `@DoubleOnly` | member | Only in the double output (`toFloat()`, `relativeTo`). **Copied verbatim**: write it in its final double form, and name the float twin (`Vec3f`) directly. |
| `@Eps(d = 1e-11)` | field | Float keeps its initializer, the double twin gets `d`. For tolerances in tests. |
| `@ValueType` | record | Ordinary record normally, `value record` under `-Pvalhalla`. |

Templates are ordinary Java, so a template with annotations reads like the float type it will become.
They are **not** compiled directly: the generated float twin is what gets compiled.

## What the double twin gets

The generator parses the template with the JDK compiler's tree API and edits real tokens only:

- `float` → `double` (types, arrays, generics), `1f`/`1e-4f`/`0.5F` → `1.0`/`1e-4`/`0.5`
- narrowing casts `(float) expr` are **dropped**. That is right for the idiom `(float) Math.sqrt(x)`. Don't use
  `(float) i` to convert an integer, because in the double twin it becomes plain `i` (integer division!).
  Use `i * 1f` or a `@DoubleOnly` variant.
- family names (`Vec3f` → `Vec3d`), including inside longer identifiers at a word boundary (`nextVec3f`,
  `Vec3fTest`, but not `Vec3fish`), and declared method/field names
- `Float*` types (`FloatBuffer`, `Float`) → `Double*`; `floatToIntBits` and friends via a table in `Renames`.
  Bit-level helpers change the integer width (`int` → `long`), so write those as `@DoubleOnly` variants
- comments and string literals get the same word-level rename (`float` → `double`) so docs stay true

## Diagnostics

Mistakes fail the build with `Template.java:LINE: message`:

- a member that is both `@FloatOnly` and `@DoubleOnly`
- `@Eps` without an initializer or without `d`
- kept code calling a member that is dropped from that output (`toDouble()` called from shared code)
- a template with no `@GenerateDouble` type, or a name that can't produce a twin
- Java syntax errors

## Valhalla profile

`./gradlew build -Pvalhalla` passes `--valhalla` to the generator, which emits `public value record`
for every `@ValueType` type. In that profile the hand-written `src/main/java` sources go through the generator
as well (only annotation stripping and `@ValueType`), so hand-written value types work the same way.
Requires a JDK 28 EA build with JEP 401.

## Adding a type

1. Create `src/template/java/vmath/<pkg>/Aabbf.java` (name ending in `f`) annotated `@GenerateDouble`
   (and `@ValueType` for value records).
2. Put float-vs-double differences behind `@FloatOnly` / `@DoubleOnly` / `@Eps`.
3. Create the matching `src/testTemplate/java/.../AabbfTest.java` with `@GenerateDouble`.
4. `./gradlew build`. Look at the generated files if something surprises you.

## Tests of the generator

`vmath-codegen/src/test` has golden tests for every rule above plus the diagnostics. Run
`./gradlew :vmath-codegen:test`.

## Annotations the generator does not remove

The generator strips its own annotations (`GenerateDouble`, `FloatOnly`, `DoubleOnly`, `Eps`, `ValueType`, `GpuStruct`, `GpuArray`, `GpuUint`) and their imports from the
output. Other annotations of `vmath.annotations`, at the moment `@Experimental`, are passed through together with their import, which is why the main sources
compile with `compileOnly(project(":vmath-annotations"))`. (An earlier version removed every import from that package and broke the `-Pvalhalla` build as soon as
the first `@Experimental` class appeared; the Valhalla build is the one that runs hand-written sources through the generator.)
