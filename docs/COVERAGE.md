# Coverage and mutation testing

Two different questions. **Coverage** (JaCoCo) asks which lines and branches the tests execute. **Mutation testing** (PIT) asks whether the tests would *notice* if the code were wrong: it changes the
compiled code (flips a comparison, drops an addition, returns 0), re-runs the tests that cover that line, and counts the mutant as killed if one fails. A line can be covered and still checked by nothing;
the mutants that survive point at those lines.

## Coverage

```bash
./gradlew test jacocoTestReport        # build/reports/jacoco/test/html/index.html
./gradlew coverageSummary              # the table below, per package, from the XML report
./gradlew jacocoTestCoverageVerification   # part of `check`: fails when a package drops under its floor
```

Measured on the full test suite (JDK 25, generated float and double types included in the counts; not produced on the `-Pvalhalla` build, whose JDK 28 class files the JaCoCo release in use cannot read):

| Package | Lines | Branches |
|---|---|---|
| `vmath.core` | 100.0% (2006 of 2006) | 98.1% |
| `vmath.camera` | 100.0% | 92.4% |
| `vmath.geo` | 98.2% | 93.2% |
| `vmath.tex` | 98.1% | 88.1% |
| `vmath.mesh` | 97.9% | 92.8% |
| `vmath.occlusion` | 97.8% | 88.6% |
| `vmath.color` | 97.4% | 89.3% |
| `vmath.gltf` | 97.1% | 91.1% |
| `vmath.anim` | 97.0% | 94.3% |
| `vmath.gpucull` | 96.5% | 92.7% |
| `vmath.pack` | 96.4% | 93.1% |
| `vmath.spatial` | 95.3% | 85.5% |
| `vmath.gl` | 93.9% | 89.3% |
| `vmath.bulk` | 93.1% | 83.6% |
| `vmath.mem` | 87.2% | 83.3% |
| **all** | **96.8%** (13 686 of 14 144) | **90.7%** (6 737 of 7 428) |

**Floors.** `check` fails if a package falls under a floor set two to three points below what was measured at the time (lines first, branches second: core 96% / 93%, camera 97% / 89%, geo 95% / 90%, mesh 95% / 90%,
spatial 92% / 82%, bulk 90% / 80%, mem 84% / 80%, and so on; the table is `coverageFloors` in `build.gradle.kts`), and below 94% of lines or 87% of branches overall. They are there so that a change that adds untested code
is noticed, not to chase a number: raise a floor when you raise the coverage, lower one only with a reason in the commit. The least covered package is `vmath.mem`, whose misses are mostly argument checks and `slice`
helpers; the least covered branches are in `vmath.bulk` (the strided writers' byte-order variants) and `vmath.spatial`.

## Mutation testing

```bash
./gradlew mutationTest -Pmutation.classes='vmath.core.*' -Pmutation.tests='vmath.core.*'
```

The report is `build/reports/pitest/index.html` (the surviving mutants are in red, with the line). `-Pmutation.classes` and `-Pmutation.tests` take PIT patterns; `-Pmutation.threads` defaults to 4.
Only the production classes (the main output directories) are mutated. It is slow and is not part of `check`: run it on the classes you changed or want to audit. It is not available on the `-Pvalhalla` build.

### What was run, and what it found

**`vmath.core`, first run** (all 23 production classes, 4 758 mutants, 33 minutes on 8 threads, the whole `vmath.core` test package as the tests): **95.3% killed** (4 533; 168 of those by timeout, which PIT counts as
killed), 206 survived and 19 were not covered by any test. The survivors that were not near-equivalent pointed at real gaps:

- `approxEquals` returning `true` unconditionally survived on `Vec2`, `Vec4`, `Mat3`, `Quat` and `Transform`: no test ever checked that it can say no, or that each component is compared.
- `splat`, `toDouble`, `writeTo(array, offset)` and `Vec2i`/`Vec3i` `get`, `minComponent`, `maxComponent` were not called by any test (no coverage), or returned `null` without anyone noticing.
- `Vec3d.relativeTo`, the subtraction that camera-relative rendering relies on, could be turned into an addition without a failing test in `vmath.core` (the cookbook test checked it, but it lives in another package).
- the overflow and underflow paths of `normalize`, the angle ranges of `toEuler`, `squadControl` and the argument checks of the new `Hilbert` codes.

The gaps are now closed by `CoverageGapsfTest` and its generated double twin, `ConversionAndIntVecGapsTest` and extra cases in `HilbertMortonTest` and `EqualityContractTest`.

**`vmath.core`, second run** (4 845 mutants, which includes the new `SpatialHash`; 15 minutes): **98.4% killed** (4 767), 78 survived, none uncovered. `Mat3`, `Mat4`, `Mat4x3`, `Transform`, `Morton`, `Vec2i` and most of
the vector types are at or very near 100%.

A third run on only `SpatialHash`, `Vec3f` and `Vec3d` after the last tests were added: `SpatialHash` 89.9% (it was 80.9%), `Vec3` 92.8% to 92.9% (the full run had them at 95.6% and 96.8%).

**What survives.** Almost all of the remaining mutants are of two kinds that no test can reasonably kill: boundary mutants in guards that decide between a fast and a slow path at a threshold (`<` against `<=` in
`normalize`'s length test, `anyPerpendicular`, `faceForward`, `step`), where both sides give the same answer or differ by less than a rounding error, and `toEuler`/`wrapPi` branches in rotation-equivalent
formulations. In `SpatialHash` the survivors change how well `mix` scatters bits (the distribution tests would still pass with a weaker mixer, so they are thresholds, not a golden value) and the exact edge of
the representable cell range.

### How to read the numbers

- Run-to-run variation is real: the same code gave 7 and 16 surviving mutants in `Vec3f` in two runs, because a mutant that makes a loop run forever is killed by a timeout, and which tests run first
  depends on timing. Compare totals, not individual mutants.
- A killed mutant means *some* test failed, not that the right test did. The generated tests compare against JOML; they killed most of the arithmetic, and the survivors are where JOML has no opinion
  (conventions, thresholds, argument checks).
- Only `vmath.core` and the `SpatialHash` addition were mutation tested. The other packages have coverage numbers above and nothing more: no claim is made about their tests' strength. `-Pmutation.classes='vmath.bulk.*'`
  and the like run the same analysis; the packages with oracle tests of their own (`vmath.spatial`, `vmath.mesh`) are the interesting ones.
- The first run mutated test classes that live in the same package as production code (`vmath.core.*` matches `Vec3fTest`); that inflated the first attempt's count and was fixed by restricting PIT to the main
  output directories. Those numbers were discarded.

## The allocation contract under other JIT settings

`AllocationContractTest` measures bytes allocated per call after a warm-up, so it depends on the JIT having compiled the path and removed short-lived temporaries. `Alloc.applies()` therefore skips (reports as *skipped*, not passed) the whole contract under `-Xint`, with a
debugger attached, or with `-XX:TieredStopAtLevel` below 4 (C1 does no escape analysis); `-Dvmath.alloc.force=true` measures regardless, and `-Pvmath.testJvmArgs="..."` passes flags to the test JVM. Measured on 2026-10-02 with
`-Pvmath.testJvmArgs=-XX:TieredStopAtLevel=1 -Dvmath.alloc.force=true` (C1 only): **20 of the 21 contract tests still pass**, which says that the library's paths are written allocation-free rather than relying on escape analysis. The one that fails is the off-heap
accessor test, whose `MemorySegment` accesses depend on C2 removing the access wrappers. The figures printed by tests (error bounds, hit rates, counts) appear only with `-Dvmath.verbose=true`.

