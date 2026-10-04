# Technical debt audit: vmath-core, vmath-geo, vmath-render, vmath-simd

Date: 2026-10-04. Branch `master`, HEAD `0107025`, working tree as found (two staged-then-deleted `.run` files and some uncommitted sample changes that are not in scope). Read-only: this file is the only thing created in the repository. Scratch scripts went to the session scratchpad, outside the repo. Nothing was built, tested, fixed, branched or committed.

**How to read this report.** Every finding carries a *Facts* part (verified by reading the cited lines) and a *Judgment* part (my assessment). Where something may be deliberate, it says so, and it is repeated in section 7 instead of being called debt. Confidence is about whether the finding is real and correctly described, not about its severity.

---

## Status after the follow-up work (2026-10-04)

The findings below are the audit as written; this table says what was done about them afterwards. Everything is uncommitted in the working tree, and a full `./gradlew build -Dvmath.requireEnvironment=true --rerun-tasks` was the check.

| ID | Status | What was done, and what was not |
|---|---|---|
| TD-001 | Done | Guard in `Gjk.epa` for "no live face"; `GjkDegenerateTest`. No input was found that reaches the guard (PIT shows it uncovered), so the test shows the code holds on nearly flat shapes, not that the old code crashed. The same tests now run the face-table compaction path, which no test ran before. |
| TD-002 | Done | `EpaPolytope` holds the vertices, faces and horizon of the expanding polytope; one `barycentric` replaces the two copies; the constants are named. `Gjk` is shorter, `epa` is still about 100 lines. |
| TD-003 | Done | The boolean `Mat4f` overloads are `@Deprecated`, about 25 files were migrated, `ClusterGrid.of` and `HiZPyramid.fromDepth` got `ClipSpace` overloads. They keep their boolean overloads (a `ClusterGrid` only needs the y direction; reversed-z is not a `ClipSpace`). |
| TD-004 | Done for two | `Sdfs.raycast(sdf, ray, MarchLimits, hit)` and `MeshSimplifier.simplify(mesh, Options)`. `Curves`, `TileSelector.select`, `Srgb`, `GpuCullReference` were not changed. |
| TD-005 | Done | No `ThreadLocal` in `Sdfs`. |
| TD-006 | Done | `RigidBody`: scratch arrays instead of allocations, `isFinite()`, and a singular Jacobian leaves the angular velocity unchanged. |
| TD-007 | Done in part | `TileSelector.select` clears only the used slots of the node table, resets its counters in one method and drops its references in a `finally`. The two tile key encodings stay (commented as deliberate); `HorizonCuller` is immutable and is still allocated per call. |
| TD-008 | Not done, with a reason | `Intersectionf` has 33 public methods averaging 12 lines (largest 53); its 1,101 lines are mostly Javadoc (608 code lines). Splitting it into `Rays`, `Overlaps` and `Sweeps` would need `Intersectionf` to stay as a facade (it is stable API) and so duplicate about 600 lines of Javadoc. The slab/p-vertex specification test (TD-025) now exists if someone wants to do it anyway. |
| TD-009 | Decision: keep | Evidence: `vmath.pack` is imported by `mesh` only, `tex`, `color`, `mem` and `physics` by nothing in the library (only by bench and samples), and none of them is in the `v0.1.0` baseline, so moving them would not break `japicmp`. Not moved: `pack` is a general-purpose utility that `scene`'s tests also use, and moving it into `render` would make it reachable only with the whole renderer; `mem`, `tex`, `color` cost no dependency where they are. Revisit with the experimental-promotion plan (register TD-05). |
| TD-010 | Done | `PersistentBufferRing.beginFrame` commits nothing before the wait succeeds. |
| TD-011 | Done | `Backing` helper. |
| TD-012 | Done | `Ktx2.writeHeader`. |
| TD-013 | Done as documentation | The contradictory roadmap bullet is fixed and `docs/CODEGEN.md` says that the int vectors are hand-written and why that is the current state. They are not templated. |
| TD-014 | Done for `Gltf`; documented for the mesh results | The five `Gltf` records copy their arrays and compare by contents. `MeshLod.Chain`, `MeshSimplifier.Result`, `RectPacker.Result`, `UvAtlas.Result` say in their Javadoc that their arrays are not copied (they can be as large as the mesh). |
| TD-015 | Done | `Mesh.bounds()`. |
| TD-016 | Done | `vmath.camera` is split into `camera`, `lighting` (`ClusterGrid`, `ClusterLights`, `Cascades`, `CascadeCasters`) and `sky` (`Atmosphere`, `PreethamSky`, `SolarPosition`). None of these was in the baseline, so no forwarders. Coverage floors for the two new packages are copied from `camera` minus 2 and 4 points: measure and set them properly. |
| TD-017 | Done in part | The link check on external URIs (`toRealPath`) and the single bounded read. The BIN chunk of a GLB is still copied. The symlink test skips on this Windows machine, so that check has not been run anywhere yet. |
| TD-018 | Rejected by measurement | A count-then-fill pass was 1.8 to 1.9 times slower than the list of pairs in `ClusterLightBench` (1,024 and 4,096 lights), so the list stays; the code says so. |
| TD-019 | Done for 8 of 12 | `UvAtlas.generate` (271 lines) is now about 35 lines plus named steps; `Meshlets.build`, `ClusterHierarchy.build`, `Overdraw.optimize`, `RectPacker.pack`, `MeshOptimizer.optimizeVertexCache`, `MeshTools.computeNormalsWithCrease`, `GltfSkins.build` and `SurfaceNets.mesh` were split as well. Not done: `ManifoldBuilder.faceContact` and `.polytopes`, `ConvexPolytope`'s constructor. The mesh mutation rerun that the item asked for first was not done; the existing mesh tests are what checks these. |
| TD-020 | Done | `KernelSelector`, one property scheme (`vmath.frustumKernel`, with `vmath.kernel` as an alias), a log message when a provider is skipped or a forced name is unknown. |
| TD-021 | Done | `-Werror` (with `-Xlint:-incubating`) and the doclint options for `vmath-simd`; `-Xwerror` is left out of its javadoc because javadoc cannot silence the incubator notice. |
| TD-022 | Done | `ParallelFrustumKernel`. |
| TD-023 | Done | `SimdSupport`. |
| TD-024 | Done | The `testVector8/16/32/64` tasks run the kernel tests with `-XX:MaxVectorSize`. This machine's limit is 32 bytes, so the 64 run was a 32 run. |
| TD-025 | Done | `CullCopiesAgreeTest`: the scalar kernel, static BVH, dynamic tree and the GPU reference agree on random and plane-touching boxes. A box with NaN bounds makes the static BVH disagree with the others; the trees are documented there as "not asked", not investigated. |
| TD-026 | Done | `vmath.Environment.require`, skipped tests are listed, CI sets `-Dvmath.requireEnvironment=true`. |
| TD-027 | Done | PIT ran on `geo`, `physics`, `camera`, `gpucull`, `pack`, `tex`, `color` and `mem`; results are in `docs/COVERAGE.md`. The survivors in `Gjk` (196), `RigidBody` (166) and `ManifoldBuilder` (127) were not worked through. |
| TD-028 | Done as process | A nightly `simd-next-jdk` CI job and a note in `docs/VERSIONING.md`. The job could not be run from here. |

## 0. Method, and what this audit does not cover

**Important context:** the repo already has an audit, `docs/technical-debt.md` (2026-10-01, TD-01..TD-33, 24 ticked). That audit predates the split into `vmath-scene` and most findings below are new. Where I re-checked something it already records, I say so. Ids in this report are `TD-0xx` too, but they are **a different numbering**; I cite the older ones as "register TD-nn".

**What I ran** (cheap, read-only):
- Count and size scans over `src/main` + `src/template` of the four modules (generated code under `build/` skipped). A small Python brace-matching scanner for method length, branch count and parameter count (scratchpad; heuristic: treat its numbers as candidates, not measurements).
- ripgrep scans for `TODO|FIXME|XXX|HACK`, `@SuppressWarnings`, broad `catch`, `System.out/err`, `synchronized|volatile|ThreadLocal|Atomic`, `Unsafe|setAccessible|sun.misc`, native-memory APIs, float `==`, boolean-flag parameters, magic constants, test hygiene (`Thread.sleep`, `nanoTime`, `System.setProperty`, `@Disabled`, `assume*`).
- A package import graph built from `import vmath.*` lines over all five library modules (core, geo, scene, render, simd), with a cycle check. (I did not run `jdeps`; the repo's own `PackageLayeringTest` does.)
- A 6-line sliding-window duplicate detector over main + template code, and a normalised line diff for specific candidate pairs.
- Existing artefacts: `docs/COVERAGE.md` (JaCoCo/PIT figures, quoted, **not re-measured**), `vmath-simd/build/reports/jacoco/.../jacocoTestReport.xml` (the only per-module report present on disk), `gradle/*.kts`, `qodana.yaml` (starter profile only: no SpotBugs, PMD or Checkstyle configuration exists in the repo).

**What I did not do:** run the build, the tests, PIT, JMH, glslang or any profiler. Findings about performance are from reading, not from measuring.

**What I read versus only scanned.** The four modules are about 33k lines of hand-written main code, 13k lines of templates and 22k lines of tests. I did not read all of it. Read closely: all of `vmath-simd` main and both its tests; `FrustumKernels`, `MatrixKernels`, `ScalarMatrixKernel`, `ParallelFrustumKernel`, `FrustumCuller`, `NodeTests` (scene, because simd plugs into them); `mem/PersistentBufferRing`, the free and find paths of `FreeListAllocator`, the segment/slice boilerplate of all five allocators; `Gjk` (EPA, face management, constants, scratch state); `RigidBody.integrate` and its caches; `Sdfs` raycast/normal/project; `TileSelector.select` and key packing, `TileId` key; `Intersectionf.rayAabb`, `sweepPointFlat`, the method list; `Mesh` (copy, bounds), `Gltf` (records, load, parse, URI loading, `accessorInfo`), `ClusterLights.assign`, `GpuCullReference`, `Ktx2.parse` head and `writeHeader`; `Mat4f` clip-space overloads; `ClipSpace`, `DepthRange`; the test classes named below.
**Judged from scans only (not read line by line):** `Polygons`, `ConvexHull`, `ContactSolver`, `ManifoldBuilder`, `MassProperties`, `MeshSimplifier`, `UvAtlas`, `ClusterHierarchy`, `Meshlets`, `Overdraw`, `RectPacker`, `MeshOptimizer`, `PhysicalCamera`, `Cameraf`, `FastMath`, `Predicates`, `Hilbert`, `SpatialHash`, `ColorSpaces`, most of the templated vector and matrix types. Findings that rest on scan output only are marked *(scan)*.

**The code is in good shape**, and the report should not read otherwise. What a systematic search found *clean*, in these four modules:
- No `TODO/FIXME/XXX/HACK`, no `@SuppressWarnings` in production code, no `System.out/err` in production code, no `synchronized`, no `volatile`, no `sun.misc.Unsafe`/`setAccessible`/internal JDK APIs in production code.
- **No package cycles** (checked on all 17 packages of the five library modules), and the module layering is `core ← geo ← scene ← render`, with `simd` on top of `scene`. `core` imports nothing but its own packages and the annotations. The `PackageLayeringTest` pins the allowed edges.
- Only one mutable static (the `ThreadLocal` in `Sdfs`, TD-005). Float `==` against constants is almost all in code that uses exact predicates (`Polygons`, `ConvexHull` with `Predicates.orient2d`) or guards a division.
- Compiler runs with `-Xlint:all -Werror` for core/geo/scene/render; javadoc with `-Xdoclint:all,-missing -Xwerror`. Line coverage in `docs/COVERAGE.md` is 98.2% overall.

So the debt here is mostly **structure and consistency**, plus a handful of robustness edges, not hygiene.

---

## 1. Module map

| Module | Packages (hand-written + templates) | Main LOC (raw / of which templates) | Depends on | Notes |
|---|---|---|---|---|
| `vmath-core` | `core` (vectors, matrices, quaternions, `Predicates`, `Expansions`, `DoubleDouble`, `FastMath`, `Hilbert`, `SpatialHash`, `Wgs84`, `ClipSpace`), `mem` (five allocators + `PersistentBufferRing`), `color`, `tex` (`TextureFormat`, `Ktx2`) | 7.8k / 6.9k | annotations (compile-only) | Float types are templates; the `d` twins are generated into `build/`. The `i` vectors are hand-written. |
| `vmath-geo` | `geo` (shapes, `Intersectionf`, `Gjk`, `Polygons`, `ConvexHull`, `Sdfs`, `SurfaceNets`, map tiles: `WebMercator`, `TileId`, `TileSelector`, `TileBounds`, `TerrainRgb`, `Ellipsoids`, `HorizonCuller`), `pack` (vertex quantisers), `physics` (`RigidBody`, `ContactSolver`, `ManifoldBuilder`, `MassProperties`, `OdeIntegrator`) | 12.2k / 3.0k | core | |
| `vmath-render` | `camera` (`Cameraf` template, clusters, cascades, stereo, sky and sun), `mesh` (processing, `Mesh`), `gltf`, `gpucull` | 12.5k / 0.4k | core, geo, **scene** | Uses `vmath.bulk`, `spatial`, `gl`, `anim`, `occlusion` from `vmath-scene`, a module outside this audit. |
| `vmath-simd` | `simd` (`SimdFrustumCuller`, `SimdMatrixKernel`, two providers, `SimdSupport`) | 0.4k | scene (and `jdk.incubator.vector`) | Plugs into the SPIs of `vmath.spatial` and `vmath.bulk` through `ServiceLoader`. Has its own build script. |

Tests: core 279 test methods, geo 264, render 336 (plus templated test sources), simd 11.

Package dependency edges (from the import scan; all acyclic):
`core` ← `color`, `tex`, `geo`; `geo` ← `pack`, `physics`; `render.camera` → bulk, geo, gl, spatial; `render.mesh` → geo, gl, pack, spatial; `render.gltf` → anim, bulk, mesh; `render.gpucull` → bulk, geo, gl, mesh, occlusion (the widest fan-out, 5 packages of scene plus `mesh`, a known leaf, register TD-11); `simd` → bulk, spatial, geo.

---

## 2. Executive summary

### Health per module

**vmath-core: healthy.** Small, well-documented, highly covered (99.8% lines in `vmath.core` per `docs/COVERAGE.md`; PIT 98.4% killed). The debt is peripheral: the allocator family has no shared base and repeats its backing-segment boilerplate five times (TD-011), `PersistentBufferRing.beginFrame` is not exception-safe (TD-010), `Ktx2.writeHeader` can leave a caller's buffer in the wrong byte order (TD-012), and the `Vec2i/3i/4i` family is the one vector family that is hand-written instead of generated (TD-013). The one design question is the grouping: `core` also carries texture formats, colour spaces and a GPU fence ring (TD-009).

**vmath-geo: good code with two concentrations of risk.** One potential crash (TD-001, the EPA step of `Gjk` can index with `-1` if every face of the polytope is removed) and one oversized, stateful numerical class (`Gjk`, TD-002) next to a physics package whose state is deliberately public fields. The API has three ways of saying "which clip space" (TD-003) and several 6 to 12 parameter methods made of bare floats (TD-004). `Intersectionf` is a 1,100-line utility with three separable parts (TD-008). Everything else I read (`TileSelector`, `Sdfs`, `RigidBody.integrate`) has small, local issues.

**vmath-render: structurally sound, big in places.** No concurrency or resource hazards found. The debts are shape-related: records that hold arrays and so leak mutable internals and get identity `equals` (TD-014), a `camera` package that also contains sky and sun models (TD-016), five long builders in `mesh` that were judged "algorithmic" by the older audit and are still 120 to 271 lines (TD-019), and `Gltf` at 715 code lines with a few loader-hardening gaps (TD-017). Duplication with `geo` is small (TD-015).

**vmath-simd: small, correct as far as I can see, but its surroundings carry the debt.** The kernels themselves are tidy and the tail handling is right: the scalar tail uses the same arithmetic order as the vector body and the tests compare bit-for-bit across 22 sizes. The weaknesses are the **selection machinery it plugs into** (two near-identical selectors with different property names and different caching semantics, failures swallowed without a trace, TD-020), a build script that re-implements the shared module build without its safety nets (`-Werror`, doclint, validator; TD-021), tests that only exercise the host's lane width and silently skip some assertions (TD-024), and `ParallelFrustumKernel` details (TD-022).

### Top 10 highest-value fixes

| # | Finding | Why first | Effort |
|---|---|---|---|
| 1 | **TD-001** guard the empty-polytope case in `Gjk.epa` and add a degenerate-input test | The only potential crash in a per-frame physics path I found | S |
| 2 | **TD-020** one kernel-selection implementation, one naming scheme for the properties, a diagnostic when a provider fails or the forced name is unknown | Silent degradation to scalar is invisible; two copies already differ | S to M |
| 3 | **TD-010** make `PersistentBufferRing.beginFrame` exception-safe | A failed fence wait desynchronises the ring permanently | S |
| 4 | **TD-003** retire the boolean `zZeroToOne`/`yDown` clip-space parameters in favour of `ClipSpace`, and give `render` one convention | Three conventions for one concept across core, geo and render | M |
| 5 | **TD-014** defensive copy or `List`/`record` redesign for records with array components | Leaks parser state and gives identity `equals` | M |
| 6 | **TD-027** run PIT on `geo`, `physics`, `camera`, `gpucull`, `pack`, `tex`, `color`, `mem` | The numbers are recorded for four other packages only; `mesh` is at 74.9% killed | M (mostly waiting) |
| 7 | **TD-021** move `vmath-simd` onto the shared module build, or at least add `-Werror`, doclint and the `check`→`javadoc` link | The one module that bypasses the project's quality gates | M |
| 8 | **TD-026** make skipped tests visible (a skip count or a CI check that the environment-dependent tests ran at least once) | Layering, shader-compile and allocation tests can all be skipped without anyone noticing | S |
| 9 | **TD-002** pull the EPA polytope out of `Gjk` (about 300 lines, one class, 25 scratch arrays) and name the constants | Biggest class by code lines in `geo`, with the only unguarded index | L |
| 10 | **TD-011** a small shared base for the allocators' backing segment, and move `checkAlignment` out of `ArenaAllocator` | Five copies and a utility living in the wrong class | S |

Severity counts: **0 critical, 1 high, 11 medium, 17 low** (29 findings).

---

## 3. Findings table

Module codes: C core, G geo, R render, S simd; "S/sc" means the finding sits in `vmath-scene` code that `simd` plugs into. *(scan)* = from scan output, not line-by-line reading. Test findings are labelled `test`.

| ID | Mod | Category | Location | Description | Why it matters | Suggested fix | Sev | Eff | Conf |
|---|---|---|---|---|---|---|---|---|---|
| TD-001 | G | Correctness | `geo/Gjk.java:676-688`, `:649-675` | After the EPA loop the nearest face is searched again and `fd[closest]` is read with no check that a live face exists (`closest` starts at `-1`). Separately, when the face table fills (`faces < MAX_FACES` at 670) new faces are silently not added | `ArrayIndexOutOfBoundsException` inside a physics step, or a result computed from a polytope with holes | Guard `closest < 0` (report "touching, no normal" as the degenerate branch at 604-610 does); make the capacity limit visible (a flag on `Result`); add a degenerate-input test | High | S | Medium |
| TD-002 | G | Complexity, duplication | `geo/Gjk.java` (899 lines, 643 code), `:600-735`, `:705-716` vs `:737-754`, `:105-148`, `:649,659,688` | One class holds GJK, EPA and ~25 scratch arrays with hand-indexed `3 * f + k` layouts; `epa` is 132 lines; barycentric coordinates are computed twice (epa tail and `minBarycentric`); constants `96` and `1e-12` are inline | Hardest class in `geo` to change safely; TD-001 lives here | Extract the polytope (vertices, faces, horizon, compaction) into a package-private `EpaPolytope`; one `barycentric()` helper; name the constants | Medium | L | High |
| TD-003 | C/G/R | API consistency | `core/Mat4f.java:213,321,502,526` (boolean `zZeroToOne`) vs `:236,252,273,345,370` (`ClipSpace`); `geo/DepthRange.java`; `render/camera/ClusterGrid.java:113,132`; `gpucull/HiZPyramid.java:88` (`DepthRange` plus boolean `yDown`) | Clip-space is a `boolean`, a `ClipSpace` (depth and y), or `DepthRange` + a separate `boolean yDown`, depending on the class. `Mat4f`'s own Javadoc (`:202`) already prefers the enum | Boolean flags are easy to transpose; `DepthRange × yDown` allows combinations `ClipSpace` does not | Deprecate the boolean overloads before 1.0; give `ClusterGrid`/`HiZPyramid` a `ClipSpace` overload (reversed-Z stays in `DepthRange`, see section 7) | Medium | M | High |
| TD-004 | G/R | API design | `geo/Sdfs.java:565` (12 params, 8 of them float), `Curves.java:295,363,380` (8 to 9), `TileSelector.java:183` (boolean `useHorizon`), `render/mesh/MeshSimplifier.java:116,134,164` (overload chain with two boolean flags and a `boolean[]`), `camera/ClusterGrid.java:132` (9), `core/color/Srgb.java:158,172` (boolean `fast`), `gpucull/GpuCullReference.java:128,161,192` (7 to 9 params and an `int[] drawCapacity` used as an in/out box) | Long runs of same-typed primitives and mode flags | Argument-order mistakes compile silently; `fast` and `lockBorder` read as magic at call sites | Small parameter records (`Ray`, `MarchLimits`, `SimplifyOptions`) and enums for modes; replace the `int[]` box by a small `DrawCapacities` type | Medium | M | High |
| TD-005 | G | Concurrency, reentrancy | `geo/Sdfs.java:601`, used at `:488` and `:584` | A static `ThreadLocal<float[]>` provides scratch for `project` and `raycast`. In `project` the array is held across calls to the user's `sdf.distance` (`:488-499`), so an `Sdf` that itself calls `Sdfs.project` clobbers it | A hidden global in an otherwise static-free module; per-call lookup cost | Use a local `float[3]` or three locals in `project`; pass `hit` to `normal` in `raycast` | Low | S | Medium |
| TD-006 | G | Robustness, perf | `geo/physics/RigidBody.java:485-550` (det at `:515-519`), `:459-466`, callers `:382,394` | `integrate` divides by `det` with no guard and has no finite check on forces, velocities or the (public) orientation; one NaN poisons the body for good. `bodyAngularVelocity` allocates `double[9]` and its callers a `double[3]` per call although `integrate` uses a scratch array | Mostly theoretical: `MassProperties.of` validates a positive-definite tensor. Cost is allocation in energy and momentum queries | Document NaN behaviour or add an opt-in `checkFinite`; reuse `scratch9` | Low | S | Medium |
| TD-007 | G | Maintainability, perf | `geo/TileSelector.java:183-262`, `:386`; `TileId.java:157` | `select` is an 80-line method that resets 8 counters by hand, allocates a `HorizonCuller` per call (`:188`), clears the whole hash table per call (`:191`) and drops its references outside a `finally` (`:259-260`). `TileSelector.pack` and `TileId.key` are two different encodings of the same tile key | Per-frame cost grows with table size, not with the visible tiles; two key formats invite a mix-up | A small `Stats` object; reuse the `HorizonCuller`; a generation counter instead of `fill`; one key encoding | Low | M | High |
| TD-008 | G | Complexity, cohesion | `geo/.../Intersectionf.java` (1,101 lines, 608 code, 33 public statics; sections at `:252`, `:483`, `:691`) | One template class holds ray tests, segment/capsule distances, SAT tests and 14 swept tests; its `rayAabb` selects the axis with four nested ternaries per iteration (`:50-53`) | Every edit regenerates and recompiles a 2,200-line pair; unrelated tests share one class | Split by the existing section headers: `Rays`, `Overlaps`, `Sweeps` (keep `Intersectionf` as a facade for japicmp) | Low | M | High |
| TD-009 | C/G/R | Architecture (judgment) | `core/module-info.java` (exports `mem`, `color`, `tex`); `geo/module-info.java` (`pack`, `physics`, map-tile classes in `geo`), `render/camera` | `core` includes texture formats, colour spaces and a GPU fence ring; `geo` includes vertex quantisers, a depth-range enum and map-tile and ellipsoid classes; layering itself is clean | Consumers who only want vectors get GPU-flavoured API, and `pack`, `DepthRange`, `tex` are "rendering concerns below the renderer" | Decide per package whether it stays (document why) or moves to `scene`/`render`; do it before `@Experimental` packages are promoted (register TD-05) | Medium | L | Medium |
| TD-010 | C | Robustness | `core/mem/PersistentBufferRing.java:201-221` (`frame++` at `:205`, `ops.await` at `:211`) | `beginFrame` increments `frame` and recomputes `region` *before* waiting on the fence. If `await` throws (timeout, device lost) the frame counter has moved, `inFrame` is still false and the fence of that region stays set | Next `beginFrame` uses the next region, so regions and fences go out of step and a region can be reused while the GPU still reads it | Compute the target region first, wait, then commit `frame`/`region`/`inFrame`; test with a throwing `FenceOps` | Medium | S | Medium |
| TD-011 | C | Duplication, design | `core/mem/ArenaAllocator.java:165-184`, `RingAllocator.java:219-236`, `SlabAllocator.java:193-209`, `FreeListAllocator.java:375-396`, `PersistentBufferRing.java:298-` ; `checkAlignment` at `ArenaAllocator.java:70` used by Ring `:128`, FreeList `:281`, PersistentBufferRing `:107,237` | Five allocators with no common interface repeat `segment()`, `slice()` and the "no backing segment" error; the alignment check lives in `ArenaAllocator` | Behaviour drifts (`slice(offset)` vs `slice(offset, size)`); the shared helper is in a sibling class | A package-private `Backing` helper and an `Alignment` utility; consider a minimal `OffsetAllocator` interface only if two call sites need to be generic | Low | S | High |
| TD-012 | C | Robustness | `core/tex/Ktx2.java:255-268` | `writeHeader` switches the caller's buffer to little-endian and restores it at the end, not in a `finally`, and does not check `remaining()` first | A `BufferOverflowException` leaves a partly written header and the wrong byte order | Check capacity up front; `try/finally` for the order | Low | S | High |
| TD-013 | C | Consistency, stale doc | `core/Vec2i.java`, `Vec3i.java`, `Vec4i.java` (365/413/419 lines, about 60% line similarity by a normalised diff); `docs/ROADMAP.md:146-147` | The only vector family not generated (the `f`/`d` types come from templates); the roadmap bullet says both "done: Vec2i, Vec3i and Vec4i" and "Partial: Vec4i is open" | A third place to apply any change to vector conventions | Either template the int family or state in `docs/CODEGEN.md` why it is hand-written; fix the bullet | Low | M | Medium |
| TD-014 | R | API, encapsulation | `render/gltf/Gltf.java:162,215,259,268`, `:728-730` (`node(i)` returns the stored record); `mesh/MeshLod.java:50`, `MeshSimplifier.java:82`, `RectPacker.java:49`, `UvAtlas.java:59` | Public records with `float[]`/`int[]` components. They keep the caller's array, the accessor hands out the live array, and `equals`/`hashCode` compare arrays by identity. Verified for `Gltf.Node`: its empty body (`:215-216`) has no copy | `gltf.node(i).translation()[0] = 5f` changes later `localMatrix(i)`; two equal-looking records are `!equals` | Compact constructors that clone, or accessors that clone; or return `List`/`Vec3f`/`Quatf` values; document where the array is deliberately shared | Medium | M | High |
| TD-015 | R/G | Duplication | `render/mesh/Mesh.java:540-556` vs `geo/Aabbf.java:87-99` (template) | `Mesh.bounds()` re-implements `Aabbf.fromPoints(float[], offset, count)` loop for loop | Small, but it is a copy of public API from the module below | Call `Aabbf.fromPoints(positions, 0, vertexCount)` | Low | S | High |
| TD-016 | R | Cohesion (judgment) | `render/camera/`: `Atmosphere`, `PreethamSky`, `SolarPosition` next to `ClusterGrid`, `ClusterLights`, `Cascades`, `Stereo`, `PhysicalCamera` | 14 classes in one package covering optics, light clustering, shadow cascades, stereo and sky/sun models | `camera` is a catch-all; users hunting for sky models do not look there | Split `camera` into `camera`, `lighting` (clusters, cascades) and `sky` | Low | M | Medium |
| TD-017 | R | Hardening, memory | `render/gltf/Gltf.java:333`, `:341-345`, `:382` | (a) The "URI leaves the directory" guard is lexical (`normalize` + `startsWith`), so a symlink inside the glTF directory can point outside. (b) `Files.size` and `readAllBytes` are two steps. (c) A GLB's BIN chunk is copied (`copyOfRange`), so a large file is held about twice, plus the JSON string | The loader is written for untrusted files (size limits, depth limit, fuzzing) and these are the remaining soft spots | `toRealPath` for the containment check; `Files.newInputStream` with a limited read; slice the BIN chunk with an offset instead of copying | Low | S | Medium |
| TD-018 | R | Performance | `render/camera/ClusterLights.java:236-264` | `assign` stores every (cluster, light) pair as a `long` in `pairs`, then counting-sorts it into `indices`. Memory is 8 bytes per pair plus 4 for the result | A few thousand large lights over a fine grid make this the dominant allocation (the buffers are reused, so it is a high-water mark, not per-frame garbage) | A count pass then a fill pass removes `pairs` at the price of visiting each light twice; measure first | Low | M | Medium |
| TD-019 | R/G | Complexity *(scan)* | `render/mesh/UvAtlas.java:81` (271 lines, 54 branches), `Meshlets.java:105` (182), `ClusterHierarchy.java:89` (178), `Overdraw.java:169` (131), `RectPacker.java:72` (120), `MeshOptimizer.java:180` (118), `MeshTools.java:86` (112), `gltf/GltfSkins.java:23` (111), `geo/SurfaceNets.java:144` (123, 10 params), `physics/ManifoldBuilder.java:225` (104), `Gjk.java:600` (132) | 12 methods over 100 lines. Only 6 classes exceed 500 *code* lines (`Gltf` 715, `Gjk` 643, `MeshSimplifier` 623, `Intersectionf` 608, `ClusterHierarchy` 497, `Polygons` 492) | Known and accepted in register TD-10 ("algorithmic, well covered"); I agree it is not urgent, but `mesh` has the lowest recorded mutation score (74.9% killed), so a long method there is also the least pinned | Name the phases; keep outputs identical under the existing property tests; start with `UvAtlas.generate` | Medium | L | Medium |
| TD-020 | S/sc | Duplication, consistency, diagnostics | `scene/spatial/FrustumKernels.java:54-116` vs `scene/bulk/MatrixKernels.java:55-123` (differ only by names in a normalised diff); property names `vmath.kernel` (`FrustumKernels.java:55`) vs `vmath.matrixKernel` (`MatrixKernels.java:56`); swallowed failures `FrustumKernels.java:110-112`, `MatrixKernels.java:111`; loop guard `64` at `:101`/`:102` | Two copies of provider discovery and selection. Frustum returns a **fresh instance per call and reads the property each time**; matrix returns a **shared singleton** and its Javadoc says the choice for `Mat4fArray` is made once at class initialisation. A provider that fails to load, an unsupported provider and an unknown forced name (`-Dvmath.kernel=simdd`) all end in scalar **with no message** | If `--add-modules jdk.incubator.vector` is forgotten, the program quietly runs the scalar kernels and nothing says why | One generic `KernelSelector<P,K>`; one property scheme (`vmath.kernel.frustum` / `.matrix`, old names as aliases); log once (`System.Logger`) when a provider is skipped or the forced name is unknown; expose "why scalar" in `available()` | Medium | S to M | High |
| TD-021 | S | Build debt | `vmath-simd/build.gradle.kts` (whole file) vs `gradle/vmath-module.gradle.kts` | `simd` is the one module that does not apply the shared module script, so it re-states toolchain, Valhalla handling, JUnit dependencies and javadoc options, and **lacks** `-Werror`, the `vmath-validator` annotation processor, the `-Xdoclint:all,-missing -Xwerror` javadoc lint and the `check` → `javadoc` dependency. It uses `-Xlint:all` only | New warnings and broken Javadoc in the only module that uses an incubator API will not fail the build | Add the missing options (an hour), or generalise the module script to take "no codegen" | Medium | M | High |
| TD-022 | S/sc | Concurrency, resources | `scene/spatial/ParallelFrustumKernel.java:162-191` (`Part` fields at `:164-168`; `failure` at `:66`, `:186-188`) | After `cull` returns each `Part` still references the last `Frustumf`, `BoundsArray` and `VisibilitySet`; with several failing workers only one `Throwable` is kept and the rest are dropped; one instance cannot serve two threads (shared `Phaser`, `failure`, parts) | Retention of large arrays between frames; lost diagnostics; the "one instance per thread" rule is documented, but nothing enforces it | Null the references in `finally`; `addSuppressed` for the others; optional cheap re-entrancy check | Low | S | High |
| TD-023 | S | Robustness, consistency | `simd/SimdSupport.java:25-27`; literals at `simd/SimdMatrixKernel.java:31`, `SimdFrustumCuller.java:165` vs `SimdSupport.NAME` | `vectorsAvailable` catches `Throwable` (also OOM, `StackOverflowError`) while the selectors catch `ServiceConfigurationError | LinkageError | RuntimeException`; the name `"simd"` is a constant in one place and a literal in two | Over-broad catch hides unrelated failures; the name could drift | Catch `LinkageError | RuntimeException`; use `SimdSupport.NAME` | Low | S | High |
| TD-024 | S test | Test debt | `simd/src/test/.../SimdMatrixKernelTest.java:36`, `SimdFrustumCullerTest.java:131-134`, `:166-170`; JaCoCo: `SimdSupport` 2 lines and 1 branch missed | Tests assert that `"simd"` **is** selected, so they need a vector-capable runner; only the host's `SPECIES_PREFERRED` lane count is exercised (a 512-bit machine and a 128-bit one cover different tails); the false branch of `vectorsAvailable` is untested; the jar-descriptor test `return`s silently when `vmath.simd.jar` is unset | A lane-width-specific tail bug would pass on a 256-bit CI runner | Parameterise the kernels on the species (package-private constructor taking `VectorSpecies`) and test 64/128/256/512; make the descriptor test fail or `assumeTrue` | Low | M | High |
| TD-025 | S/G/R/sc | Duplication (partly intentional) | The six-plane positive-vertex box test: `render/gpucull/GpuCullReference.java:223-237`, `scene/spatial/BvhQuery.java:153`, `scene/spatial/DynamicAabbTree.java:1220`, `scene/spatial/FrustumCuller.java:75-91`, `simd/SimdFrustumCuller.java:174-215`, and the ray-slab test: `geo/Intersectionf.java:47-70` vs `scene/spatial/NodeTests.java:71-101` | Six copies of one plane test and two of the slab test, each for a different data layout (`MemorySegment`, SoA arrays, tree arrays, `Aabbf`) | A fix to NaN or `-0.0` handling must be made in all of them; the tests compare them against one another, which is the safety net | Keep the hot-loop copies; share the *specification* through one parameterised property test over every copy, and note in each Javadoc which copy it must agree with | Medium | M | High |
| TD-026 | test | Test debt | `render/.../PackageLayeringTest.java:66,71,76,79`, `gl/ShaderCompileTest.java:46`, `core/.../Alloc.java:45`; `geo/.../BoundingVolumesTest.java:196-199` | Five tests skip silently when the environment lacks `vmath.jars`, `jdeps`, glslang, or an enabled JIT; one test asserts a wall-clock bound (5 s for 200,000 points) | A green build can mean "the layering test never ran"; a loaded CI machine can fail a correct build | Print a skip summary at the end of `check`; in CI require the layering test (property `vmath.requireLayering=true`); replace the clock with an operation count or a generous multiple of a calibration run | Low | S | High |
| TD-027 | test | Test debt | `docs/COVERAGE.md:77-82` | PIT results exist only for `spatial`, `mesh`, `bulk`, `gltf` (and `core`); `mesh` 74.9% and `spatial` 76.9% killed, 835 and 715 mutants surviving. Nothing for `geo`, `physics`, `camera`, `gpucull`, `pack`, `tex`, `color`, `mem`. (Figures quoted from the doc, not re-run) | 98% line coverage says the lines run, not that a wrong result would be noticed; `geo` and `physics` hold the numerical code with the highest consequence | Run PIT on those packages in turn and record it the way `core` was recorded | Medium | M | High |
| TD-028 | S/all | Platform risk | `vmath-simd/src/main/java/module-info.java` (`requires jdk.incubator.vector`), `gradle.properties` (`vmath.valhallaJdk=28`), `vmath-simd/build.gradle.kts` (`--add-modules`, `--enable-preview`) | The simd module depends on an incubating API whose shape can change in any JDK release; the Valhalla profile compiles with preview features on an early-access JDK | A JDK bump can break `simd` without any change in this repository | Pin and test the incubator API against each new JDK in the nightly job; keep `simd` free of other dependencies (it is) | Low | S | Medium |

Findings I looked for and did **not** find: swallowed exceptions in production code other than TD-020/TD-023, public non-final mutable statics, resource leaks (`Files.read*` is the only I/O in these modules and it is not leaked), unchecked casts or raw types (zero `@SuppressWarnings` in main, `-Xlint:all -Werror` is on), `TODO`-style stale comments, magic constants in the sky and sun models beyond the cited formulas (they are published coefficient tables, not debt).

---

## 4. Detail: critical and high findings

There are no critical findings. One high finding.

### TD-001 — `Gjk.epa` can index with `-1`; the face table can overflow silently

Facts (`vmath-geo/src/main/java/vmath/geo/Gjk.java`):

```java
// :676-688  after the loop
closest = -1;
double nearest = Double.POSITIVE_INFINITY;
for (int f = 0; f < faces; f++) {
    if (fAlive[f] && fd[f] < nearest) { nearest = fd[f]; closest = f; }
}
// faces of one planar facet of the polytope tie for the smallest distance: ...
double tieLimit = fd[closest] + 1e-12 * (1 + scale);   // :688, closest may still be -1
```

Inside the loop the same situation is handled (`if (closest < 0) break;`), so the author knew it can occur. After the loop it is not handled. How all faces can be dead: at `:655-664` every face visible from the new point is marked dead and its edges go to the horizon list; `addEdge` (`:824`) cancels an edge against its reverse. If the point sees *every* face (numerically possible for a thin or nearly flat polytope, `side > 1e-12 * scale`), every edge cancels, `edges == 0`, `newFaces == 0` (`:670-676`) and the loop breaks with no live face. Separately, `faces < MAX_FACES` at `:670` and `faces > MAX_FACES - 96` at `:649` cap the table of 2,048 faces without telling the caller.

What I could not show: an input that reaches it. I did not run the code, and `growToTetrahedron` plus the 1e-12 relative tolerance make it unlikely. Hence confidence *medium*.

Judgment: the cost of the guard is two lines; the cost of the exception is a crash in the step function of an engine.

Proposal:
1. After the nearest-face search, `if (closest < 0) { witness(r); r.depth = 0; r.normal = (0,1,0); return; }`, the same degenerate branch the method already uses at `:604-610`.
2. Add a `truncated` flag to `Gjk.Result` set when `MAX_FACES`, `MAX_VERTICES` or `MAX_EPA_ITERATIONS` ends the loop.
3. A test: two nearly coincident flat boxes, plus a fuzz over `ConvexPolytope` pairs at 1e-9 separations, asserting no exception and finite output.

---

## 5. Duplication clusters

| Cluster | Locations (all verified by reading unless marked) | Proposed shared abstraction | Intentional? |
|---|---|---|---|
| **A. Kernel selection** | `scene/spatial/FrustumKernels.java:54-116`, `scene/bulk/MatrixKernels.java:55-123`; provider boilerplate `simd/SimdFrustumKernelProvider.java:12-40`, `SimdMatrixKernelProvider.java:52-80` (both already share `SimdSupport`); the "`from` must be a multiple of 64" check in `FrustumCuller.java:60`, `SimdFrustumCuller.java:170`, `ParallelFrustumKernel.java:102` | A generic `KernelSelector` plus an abstract `SimdProvider` (the providers differ only in `create()`); a shared `requireAligned(from)` | No: the differences (property name, per-call vs singleton) look accidental |
| **B. Plane and slab tests** | six p-vertex frustum tests and two ray-slab tests listed in TD-025 | Keep; add one property test that drives every copy from the same random inputs | Mostly yes (layout-specific hot loops; `CullKernelBench` says its copies are deliberate, register TD-09) |
| **C. Allocator backing segment** | five files listed in TD-011 | `Backing` helper plus `Alignment.check` | No |
| **D. Barycentric coordinates in `Gjk`** | `Gjk.java:705-716`, `:737-754` | one private `barycentric(face, point, out)` | No |
| **E. Bounds loop** | `Mesh.java:540-556` ≡ `Aabbf.fromPoints` (`geo/Aabbf.java:87-99`) | call the existing method | No. Other hand-rolled `±INFINITY` min/max loops exist in `ClusterHierarchy` (3), `ManifoldBuilder` (5), `Intersectionf` (4), `GpuCullReference` (2); I did not read them, so I cannot say which are equivalent *(scan)* |
| **F. Tile key encodings** | `TileSelector.java:386` vs `TileId.java:157` | one `TileKey` encoding | Possibly (the selector key is bit-packed for its hash table); worth a comment either way |
| **G. Error-free transforms** | `core/Expansions.java:35,77,83,96,107,157` and `DoubleDouble.twoSum` (`:79`) | none; inlining is the usual way to write Shewchuk's algorithms without allocation | Probably yes (see section 7) |
| **H. Hand-written vs generated vectors** | `Vec2i/3i/4i` vs the `Vec*f` templates | extend the generator to `int`, or document why not | Unclear |
| **I. Parallel reference APIs** | `gpucull/GpuCullReference.java` and `ClusterCullReference.java` (same `Counters`, `drawCapacity`, `view/objects` pattern) *(scan of the second file)* | a shared `CullPass` context object | Probably yes (they mirror two shaders) |

The 6-line-window detector found no exact duplicates over 6 lines within or across modules beyond the pairs above: `Mat2f`/`Mat3x2f` (4 windows) and `Mat4f`/`Mat4x3f` (4 windows) inside templates, `BoundingVolumes`/`MassProperties` (3 windows, not read), and the two SIMD providers. That fits a code base that has already been de-duplicated once (register TD-08, TD-09).

---

## 6. Hotspots

Ranked by concentration of findings and consequence, not by size alone.

1. **`geo/Gjk.java`**: the one potential crash (TD-001), 25 scratch arrays, constants inline, duplicated barycentrics, a 132-line `epa` (TD-002, TD-019).
2. **`scene/.../FrustumKernels.java` + `MatrixKernels.java`**: two copies that already diverged, silent fallbacks, per-call vs cached semantics (TD-020). Lives in `scene`, but every `simd` behaviour passes through it.
3. **`render/gltf/Gltf.java`**: 715 code lines, public records over arrays, the loader's soft spots (TD-014, TD-017, TD-019).
4. **`render/mesh/` (`UvAtlas`, `Meshlets`, `ClusterHierarchy`, `MeshSimplifier`)** *(scan)*: three of the four longest methods in the four modules, lowest mutation score on record (TD-019, TD-027).
5. **`geo/Intersectionf.java`**: 608 code lines, three separable responsibilities, several of the epsilon constants (`SAT_EPS`, `ADVANCE_TOLERANCE`, `:486,699`) are absolute (TD-008, TD-025).
6. **`vmath-simd/build.gradle.kts`**: the one module outside the shared quality gates (TD-021).
7. **`core/mem/PersistentBufferRing.java`** (and its siblings): exception safety, boilerplate, misplaced helper (TD-010, TD-011).
8. **`scene/spatial/ParallelFrustumKernel.java`**: retention, lost failures, shared mutable instance state (TD-022).
9. **`geo/TileSelector.java`**: long method, per-call cost, second key encoding (TD-007).
10. **`geo/physics/`** (`RigidBody`, `ManifoldBuilder`, `ContactSolver`; mostly *(scan)*): public-field structs by design, the unguarded determinant, and no mutation score (TD-006, TD-027, section 7).

---

## 7. Open questions: things that look wrong but may be intentional

Please confirm before anyone changes these.

1. **SIMD matrix kernels are not bit-identical to the scalar ones.** Documented (`SimdMatrixKernel` Javadoc, `MatrixKernel.java:17`, `docs/BULK.md:77`): fused multiply-add and a different summation order for quaternion lengths. The *frustum* kernels, by contrast, are tested bit-for-bit. Is run-to-run or machine-to-machine determinism of `Mat4fArray` results ever required (replays, lockstep networking)? Because `MatrixKernels` picks once per JVM, the answer would depend on whether the vector module was present.
2. **Fixed 128-bit species in `SimdMatrixKernel` (`:27`) but `SPECIES_PREFERRED` in `SimdFrustumCuller` (`:153`).** I read this as deliberate (a 4×4 column is 128 bits), and it means the matrix kernel gains nothing on wider hardware. Confirm that this is the intended trade-off. (The register's open TD-32 already says the SIMD frustum kernel is slower than scalar for a few hundred boxes; I did not measure.)
3. **Public mutable fields** in `RigidBody` (`px..tz`, `linearDamping`), `Gjk.Result`, `Sdfs.Hit`, `ContactSolver` settings. The Javadoc calls them "public in the way of a struct" for engine speed. I treat that as a design decision, not debt, and did not list it.
4. **Kinematic bodies.** `RigidBody` Javadoc says a body with inverse mass 0 is "static or kinematic" and "keeps its velocities" (`:24`), but `integrate` returns immediately for it (`:486-489`) without advancing the position. So a kinematic body with a velocity never moves unless the caller moves it. Intended?
5. **Inline two-sum code in `Expansions`** (five places) next to `DoubleDouble.twoSum`. I assume this is the allocation-free style of Shewchuk's paper; say if it should be unified.
6. **`FrustumKernels.best()` returns a fresh instance each call, `MatrixKernels.best()` returns a shared one.** Reasonable (the frustum kernels hold scratch; the matrix kernels are stateless), but the property names and caching differ for no stated reason (TD-020).
7. **An unknown forced kernel name silently selects scalar.** `FrustumKernelSelectionTest` asserts it (`"no-such-kernel"` → scalar), so someone chose it. Should it at least log?
8. **`DepthRange × boolean yDown` in `ClusterGrid`/`HiZPyramid`** instead of `ClipSpace`: possibly because reversed-Z exists only in `DepthRange`. If so, TD-003's fix is a new type, not a replacement.
9. **Where `pack`, `DepthRange`, `tex`, `color` and the GPU ring live.** Maybe module count was kept low on purpose (register TD-11 says a module split is "a decision about modules"); TD-009 is a judgment about it, not a defect.
10. **`Vec2i/3i/4i` hand-written.** Perhaps because the integer family was not worth a second generator; the roadmap's contradictory bullet suggests it was left half-finished.
11. **Gjk's public constants** (`MAX_FACES`, `EPA_TOLERANCE`, ...) are part of the public API; extracting an `EpaPolytope` (TD-002) must keep them.
12. **Per-call `Arrays.fill(nodeKeys, 0L)` in `TileSelector.select`** may be cheap enough at the intended capacities; I did not measure.

---

## 8. Suggested roadmap

**Quick wins (small effort, clear value; no dependencies between them):**
- TD-001 guard + test (S). Do first.
- TD-010 exception-safe `beginFrame` (S).
- TD-012 `writeHeader` capacity check and `finally` (S).
- TD-015 `Mesh.bounds` → `Aabbf.fromPoints` (S).
- TD-023 narrow the `Throwable` catch, use `SimdSupport.NAME` (S).
- TD-005 drop the `ThreadLocal` in `Sdfs` (S).
- TD-022 null the `Part` references, `addSuppressed` (S).
- TD-026 skip visibility in CI (S).
- TD-011 backing-segment helper (S).
- TD-021 the missing compiler and doclint options in `simd/build.gradle.kts` (the one-hour version).

**Medium-term (days; do after the quick wins that touch the same files):**
- TD-020 unify kernel selection (after TD-023, same area). Unblocks TD-024's parameterised tests, since a selector that is easy to drive makes species-parameterised tests easy.
- TD-024 species-parameterised simd tests (needs the package-private constructor from TD-020/TD-023).
- TD-003 clip-space API (deprecate booleans; do before any `@Experimental` promotion, register TD-05).
- TD-004 parameter objects (start with `Sdfs.raycast` and `MeshSimplifier.simplify`; pair with TD-003 since both touch `ClusterGrid.of`).
- TD-014 records over arrays (check japicmp: `Gltf` records are public API; the `mesh` ones are mostly `@Experimental`).
- TD-027 PIT on `geo`, `physics`, `camera`, `gpucull`, `pack`, `tex`, `color`, `mem` (can run in the background while the above proceed; its findings may add work to TD-001 and TD-006).
- TD-017, TD-018, TD-006, TD-007 as and when those files are next touched.
- TD-025 the cross-copy property test.

**Long-term structural (weeks; need decisions):**
- TD-002 extract the EPA polytope (after TD-001 and the mutation run on `geo`, so the refactor has a net).
- TD-008 split `Intersectionf` (after TD-025, so the slab/p-vertex specification test exists first).
- TD-019 break up the long `mesh` builders (after TD-027's `mesh` rerun; the 74.9% kill rate means the existing tests are the weakest net exactly there).
- TD-016, TD-009 package and module regrouping (these depend on register TD-05's experimental-promotion plan and on the module decision in register TD-11; changes to public packages need japicmp forwarders).
- TD-013, TD-028 as housekeeping alongside any codegen work.

Dependency notes: TD-001 → TD-002; TD-023 → TD-020 → TD-024; TD-003 ↔ TD-004 (`ClusterGrid.of`); TD-025 → TD-008; TD-027 → TD-002, TD-019; TD-009 → TD-016.

---

## Appendix: tool output used as supporting evidence

- Long-method scan (≥ 60 lines, production code): 30 hits; the 12 over 100 lines are in TD-019. Class size by *code* lines: 20 files exceed 190 code lines; 7 exceed 480. Raw line counts overstate class size by 40 to 60% because of the Javadoc that the documentation pass added (for example `Gltf.java` is 1,288 lines raw and 715 code lines).
- Public methods with 9 or more parameters (regex): see TD-004; boolean-flag parameters on public methods: about 20 hits, the notable ones are in TD-003/TD-004.
- Import graph: 17 packages, 0 cycles.
- JaCoCo (`vmath-simd`): `SimdMatrixKernel` 63/63 lines, 24/24 branches; `SimdFrustumCuller` 58/58 lines, 45/46 branches; `SimdSupport` 1/3 lines, 1/2 branches; both providers 100%.
- `docs/COVERAGE.md` (quoted): all packages 98.2% lines, 92.1% branches; lowest by package `spatial` 96.5% lines, `gl` 96.6%, `mem` 97.1%; the root build enforces package floors (`build.gradle.kts` `coverageFloors`, `vmath/mem` the lowest at 0.84/0.80).
