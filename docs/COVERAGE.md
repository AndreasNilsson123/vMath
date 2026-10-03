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

Measured on the full test suite on 2026-10-03 (JDK 25; since the module split the classes measured are those of the four parts and the tests that cover them are all in the root project, generated float and double types included in the counts; not produced on the `-Pvalhalla` build, whose JDK 28 class files the JaCoCo release in use cannot read):

| Package | Lines | Branches |
|---|---|---|
| `vmath.core` | 99.8% (2875 of 2882) | 96.9% |
| `vmath.camera` | 100.0% (868 of 868) | 92.1% |
| `vmath.util` | 99.7% (1017 of 1020) | 97.5% |
| `vmath.occlusion` | 99.3% (265 of 267) | 89.8% |
| `vmath.anim` | 98.8% (1619 of 1639) | 94.0% |
| `vmath.tex` | 98.7% (231 of 234) | 90.8% |
| `vmath.pack` | 98.6% (361 of 366) | 97.8% |
| `vmath.mesh` | 98.5% (2490 of 2528) | 93.1% |
| `vmath.physics` | 98.4% (997 of 1013) | 89.8% |
| `vmath.bulk` | 98.4% (1446 of 1469) | 89.9% |
| `vmath.gltf` | 97.7% (854 of 874) | 91.6% |
| `vmath.gpucull` | 97.4% (335 of 344) | 92.7% |
| `vmath.color` | 97.4% (229 of 235) | 89.3% |
| `vmath.geo` | 97.2% (3459 of 3559) | 91.2% |
| `vmath.mem` | 97.1% (370 of 381) | 92.6% |
| `vmath.gl` | 96.6% (634 of 656) | 89.3% |
| `vmath.spatial` | 96.5% (2886 of 2992) | 88.1% |
| **all** | **98.2%** (20 936 of 21 327) | **92.1%** (10 407 of 11 305) |

**The other modules** (`./gradlew test` runs the tests of every module, and the root project merges the coverage of the four parts into `build/reports/jacoco/test`; `./gradlew :vmath-simd:test :vmath-codegen:test :vmath-validator:test` writes a report under each module's `build/reports/jacoco`; measured 2026-10-02): `vmath-simd` 95.6% of lines (86 of 90) and 94.4% of branches, floor 92% / 90%; `vmath-validator` (the annotation processor; measured 2026-10-03) 98.7% of lines (78 of 79) and 86.9% of branches (73 of 84), floor 96% / 84%; `vmath-codegen` 79.2% of lines (595 of 751) and 66.4% of branches, floor 76% / 62%, wired into each module's `check`. The code generator's own tests cover less than the root project's tests do indirectly: every generated `d` type and `XxxGpu` writer is exercised by the root test suite, which this figure does not count. `vmath-bench` has no tests and no figure.

**Floors.** `check` fails if a package falls under a floor set two to three points below what was measured at the time (lines first, branches second: core 96% / 93%, camera 97% / 89%, geo 95% / 90%, mesh 95% / 90%,
spatial 92% / 82%, bulk 90% / 80%, mem 84% / 80%, and so on; the table is `coverageFloors` in `build.gradle.kts`), and below 94% of lines or 87% of branches overall. They are there so that a change that adds untested code
is noticed, not to chase a number: raise a floor when you raise the coverage, lower one only with a reason in the commit. The least covered package by lines is `vmath.spatial` (96.5%), and by branches `vmath.spatial` (88.1%), then `vmath.color` and `vmath.gl` (89.3%); the floors of `vmath.physics` are 95% / 86%.

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

**Other packages, first runs (2026-10-02, 6 threads, the package's own test package as the tests, production classes only):**

| Package | Mutants | Killed | Survived | No coverage | Run time |
|---|---|---|---|---|---|
| `vmath.spatial` | 3 330 | 76.9% | 715 | 53 | 13.5 min |
| `vmath.mesh` | 3 996 | 74.9% | 835 | 168 | about 40 min |
| `vmath.bulk` | 1 955 | 87.9% | 189 | 47 | about 3 min |
| `vmath.gltf` | 841 | 84.8% | 112 | 16 | 4 min |

**Second runs, after the gaps below were closed (same settings):** `spatial` 77.9% killed (3 053 mutants; the run's raw 75.4% includes a stale, uncovered copy of `CascadeCasters`, which moved to `camera`), `mesh` 75.6% (3 998), `bulk` 88.3% (1 955), `gltf` 85.0% (844). `CullStages$SmallFeature` went from 50.0% to 95.2% with `SmallFeatureTest`, and `CullStages$Distance` is at 91.3%.

These are much lower than `vmath.core` (98.4%): the tests of these packages compare with brute-force oracles and check invariants, which kills the logic but not the arithmetic inside tolerances and thresholds. The weakest classes: `CascadeCasters` 52.5% (its test only checked that nothing was wrongly culled, so any change that culled less survived), `CullStages$SmallFeature` 50.0%, `LooseOctree$Query` 59.0%, `Overdraw` 50.7%, `FastMaps$TripleIntMap` 28.6%, `MeshSimplifier$Run` 71.1%, `ClusterHierarchy` 71.4%, `MeshOptimizer` 72.2%, `SegmentFloatArray` 76.8%, `FrameDirtyRanges` 66.7%.

**Gaps closed after these runs** (re-run on the changed classes only): `CascadeCasters` now has a test that compares every keep and cull decision with an independent double-precision light-space computation, in both directions, including near-vertical lights where the light view's basis has different zero entries: 52.5% to 79.2% killed (the survivors are the tolerance slack, comparison boundaries and entries that are structurally zero); `FastMaps` has a test against `HashMap` (and `TripleIntMap.put` now refuses negative values like `LongIntMap`): `TripleIntMap` 28.6% to 72.7%, `LongIntMap` 80.9% to 91.5%, the rest being the hash mixer, whose changes only alter the distribution; `FloatElements` (the new base of the containers) 82.1% to 96.4% and `FrameDirtyRanges` 66.7% to 93.3% with `FloatElementsTest` and a `FrameDirtyRanges` test. **Not closed, and what they are:** about 1 750 survivors remain in the four packages. The ones I looked at are of three kinds: (1) pruning and ordering arithmetic in the nearest-neighbour and overlap queries of `LooseOctree`, `UniformGrid`, `DynamicAabbTree` and `BvhQuery` (the extent of a loose box, the sort of children by distance), where a wrong value only makes a query visit more nodes and returns the same, correct, result, so only a visit counter could see it; (2) the heuristics of `Overdraw.optimize` and the mesh simplifier, which are guarded by "never worse" checks, so a mutated heuristic gives a different but still acceptable order or reduction (the tests check quality properties, not a golden result); (3) hash mixing (`FastMaps.mix`), whose changes only alter the distribution. The classes where a real gap may still hide are `MeshSimplifier$Run` (71%), `ClusterHierarchy` (71%), `MeshOptimizer` (72%), `LooseOctree$Query` (55%), `LightCull` (74%) and `SegmentFloatArray` (77%); I did not go through them mutant by mutant.

### How to read the numbers

- Run-to-run variation is real: the same code gave 7 and 16 surviving mutants in `Vec3f` in two runs, because a mutant that makes a loop run forever is killed by a timeout, and which tests run first
  depends on timing. Compare totals, not individual mutants.
- A killed mutant means *some* test failed, not that the right test did. The generated tests compare against JOML; they killed most of the arithmetic, and the survivors are where JOML has no opinion
  (conventions, thresholds, argument checks).
- `vmath.core`, `spatial`, `mesh`, `bulk` and `gltf` were mutation tested (tables above); the other packages have coverage numbers and nothing more: no claim is made about their tests' strength. `-Pmutation.classes='vmath.bulk.*'`
  and the like run the same analysis; the packages with oracle tests of their own (`vmath.spatial`, `vmath.mesh`) are the interesting ones.
- The first run mutated test classes that live in the same package as production code (`vmath.core.*` matches `Vec3fTest`); that inflated the first attempt's count and was fixed by restricting PIT to the main
  output directories. Those numbers were discarded.

## The allocation contract under other JIT settings

`AllocationContractTest` measures bytes allocated per call after a warm-up, so it depends on the JIT having compiled the path and removed short-lived temporaries. `Alloc.applies()` therefore skips (reports as *skipped*, not passed) the whole contract under `-Xint`, with a
debugger attached, or with `-XX:TieredStopAtLevel` below 4 (C1 does no escape analysis); `-Dvmath.alloc.force=true` measures regardless, and `-Pvmath.testJvmArgs="..."` passes flags to the test JVM. Measured on 2026-10-02 with
`-Pvmath.testJvmArgs=-XX:TieredStopAtLevel=1 -Dvmath.alloc.force=true` (C1 only): **20 of the 21 contract tests still pass**, which says that the library's paths are written allocation-free rather than relying on escape analysis. The one that fails is the off-heap
accessor test, whose `MemorySegment` accesses depend on C2 removing the access wrappers. The figures printed by tests (error bounds, hit rates, counts) appear only with `-Dvmath.verbose=true`.

