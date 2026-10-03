# Code generation

vmath is written once, in float, and the build generates everything else. There are no checked-in generated
files: `./gradlew build` runs the `generateSources` task of each module (`vmath-core`, `vmath-geo`, `vmath-scene`, `vmath-render`) and of the root project, which write to `build/generated/sources/vmath/` of their own project.

**Several modules.** A template of a module uses the twins of the types of the modules below it (the `geo` shapes use `Vec3f`, `Cameraf` uses both), so each run is told where to look: `--family-templates DIR` (repeatable) names the template directory of a module below, read only to learn which types have a double twin, and `--gpu-register DIR` names its hand-written sources, read only so that a `@GpuStruct` can refer to the structs there. Nothing of the modules below is generated again. The shared build logic is `gradle/vmath-module.gradle.kts`; each module also generates its test templates (and the test `@GpuStruct` records) with the families of its own templates, of the templates and test templates of the modules below, and the structs below. `CodegenModulesTest` covers both options.

```
<module>/src/template/java   float templates of a module (core, geo, render) ─┐
<module>/src/main/java       its hand-written code                            ├─► vmath-codegen ─► <module>/build/generated/sources/vmath/main
<module>/src/testTemplate/java    float templates for tests                       ┘   (float twin + double twin, both emitted)
codegen-renames.properties   extra renames for the double output (the JOML oracle types)
```

## Annotations

They live in `vmath-annotations` and have source retention, except `@ValueType` and `@Experimental`, which have class retention. The generator removes them from the generated output, except `@ValueType`, which stays so that the validating processor (below) can tell a value type by its declaration.

| Annotation | On | Effect |
|---|---|---|
| `@GenerateDouble` | type | Emit `Xf` (float) and `Xd` (double). `Vec3f` → `Vec3d`, `Vec3fTest` → `Vec3dTest`; `twin = "..."` overrides. |
| `@FloatOnly` | member | Only in the float output (`toDouble()`). Written as ordinary float code. |
| `@DoubleOnly` | member | Only in the double output (`toFloat()`, `relativeTo`). **Copied verbatim**: write it in its final double form, and name the float twin (`Vec3f`) directly. |
| `@Eps(d = 1e-11)` | field | Float keeps its initializer, the double twin gets `d`. For tolerances in tests. |
| `@ValueType` | record | Ordinary record normally, `value record` under `-Pvalhalla`. Kept in the generated source for the validator. |

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
for every `@ValueType` type. In that profile the hand-written `src/main/java` sources of each module go through the generator
as well (only annotation stripping and `@ValueType`), so hand-written value types work the same way.
Requires a JDK 28 EA build with JEP 401.

## The validating processor: `vmath-validator`

`vmath-validator` is a javac annotation processor (a tool, like the generator: not published) that the `vmath` build runs on every compile of the main sources. It checks the identity rules of the value
types on the attributed syntax tree, so it sees what the compiler resolved rather than what the text says: a type is a value type when its declaration carries `@ValueType` (class retention, so it is visible on the types of the other modules and in the tests), whatever the variable is called, whether it
is a method result, a field of another class, a lambda parameter or `var`. It reports through the compiler's own diagnostics (an error at the line, the build fails):

| Rule | Rejected | Allowed |
|---|---|---|
| comparison | `a == b` and `a != b` with a value type on either side | comparison with `null`, of primitives, of arrays of value types, of other reference types, of type variables |
| locking | `synchronized (v)`, `v.wait()`, `v.notify()`, `v.notifyAll()` | the same on other objects |
| identity hash | `System.identityHashCode(v)` | on other objects |
| identity collections | IdentityHashMap, WeakHashMap, WeakReference, SoftReference, PhantomReference with a value type argument (explicit or inferred by the diamond) | the same with other types |
| declaration | a `@ValueType` that is not a record, a `synchronized` method or a `finalize()` in one | |

`-Avmath.validator=warn` turns the errors into warnings and `-Avmath.validator=off` turns the processor off. **What it sees:** the library's own types (the generated float and double twins keep the annotation) in every module and in the tests; the marker has class retention, so it is read from the class files of the modules below. Code that uses `vmath` from a jar is not checked unless it runs the processor too (the earlier text-based ValueTypeChecker, which scanned names in the sources, is gone). ValueTypeProcessorTest compiles
a violating program for each rule, one that passes, and checks the line number, the message and the options. The processor claims no annotations, so the build turns off javac's `-Xlint:processing` warning about that.

## Adding a type

1. Create `<module>/src/template/java/vmath/<pkg>/Aabbf.java` in the module that owns the package (name ending in `f`) annotated `@GenerateDouble`
   (and `@ValueType` for value records).
2. Put float-vs-double differences behind `@FloatOnly` / `@DoubleOnly` / `@Eps`.
3. Create the matching `<module>/src/testTemplate/java/.../AabbfTest.java` with `@GenerateDouble`.
4. `./gradlew build`. Look at the generated files if something surprises you.

## Tests of the generator

`vmath-codegen/src/test` has golden tests for every rule above plus the diagnostics. Run
`./gradlew :vmath-codegen:test`.

## Annotations the generator does not remove

The generator strips its own annotations (`GenerateDouble`, `FloatOnly`, `DoubleOnly`, `Eps`, `GpuStruct`, `GpuArray`, `GpuUint`) and their imports from the
output; it reads `@ValueType` to emit `value record` and leaves it in place for the validator. Other annotations of `vmath.annotations`, at the moment `@Experimental`, are passed through together with their import, which is why the main sources
compile with `compileOnly(project(":vmath-annotations"))`. (An earlier version removed every import from that package and broke the `-Pvalhalla` build as soon as
the first `@Experimental` class appeared; the Valhalla build is the one that runs hand-written sources through the generator.)
