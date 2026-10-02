# vmath technical-debt register

Audit of 2026-10-01, taken at commit `a39717f` plus the uncommitted work of that day (coverage and mutation tooling, publishing setup, `SpatialHash`, the cookbook). Nothing was fixed during the
audit: this file is the only change it made. Items are meant to be picked up the way `docs/ROADMAP.md` items are: take one, do it, tick it, record what was measured.

Severity: **Critical** = known wrong results, data loss, a security hole, memory unsafety, or a release blocker with no workaround. **High** = a real risk to correctness, usability or the first release that
should be settled before the code is relied on or published. **Medium** = maintainability or risk that grows with time. **Low** = housekeeping.
Scope uses the roadmap's sizes: S ≈ hours, M ≈ days, L ≈ 1–2 weeks, XL = multi-week. `→` marks a dependency; ids like `INF-5` are `docs/ROADMAP.md` items.

---

## 1. Summary

| Severity | Items | Headline |
|---|---|---|
| Critical | 0 | No known wrong result, no unsafe memory access, no open security finding. See section 2 for what was checked to say that. |
| High | 4 | The GPU-facing layer has never met a GPU; the release identity (group, version, licence holder) is unsettled; 35% of the public API has no documentation; thread-safety contracts are mostly unwritten and two hazards are untested. |
| Medium | 16 | Experimental surface, test strength outside `vmath.core`, duplicated container and traversal code, oversized classes, package layering, stale README/ROADMAP text, dependency lag, loaders without fuzzing. |
| Low | 8 | Warnings, dead code, error-signalling style, suppressed lints, small gaps. |

Index (tick when fixed):

- [ ] **TD-01** High: GPU-facing code verified only against Java references
- [ ] **TD-02** High: release identity unresolved (group id, version against tag, licence holder)
- [ ] **TD-03** High: 35% of public declarations have no doc comment
- [ ] **TD-04** High: thread-safety contracts missing; two concurrency hazards untested
- [ ] **TD-05** Medium: one third of the classes are `@Experimental` with no promotion plan
- [ ] **TD-06** Medium: test strength unmeasured outside `vmath.core`; coverage ignores three modules
- [ ] **TD-07** Medium: kernel selection (SPI) logic barely tested, and repeated service lookups
- [ ] **TD-08** Medium: copy-pasted container boilerplate
- [ ] **TD-09** Medium: duplicated traversal and kernel code
- [ ] **TD-10** Medium: oversized classes and methods
- [ ] **TD-11** Medium: package layering and a single 17-package module
- [ ] **TD-12** Medium: stale README and ROADMAP text
- [ ] **TD-13** Medium: dependency lag and version strings in four places
- [ ] **TD-14** Medium: build logic complexity and hard-coded toolchains
- [ ] **TD-15** Medium: native memory ownership (`SegmentFloatArray`, rings)
- [ ] **TD-16** Medium: algorithmic hot spots with measured cost
- [ ] **TD-17** Medium: allocation in paths that read as allocation-free
- [ ] **TD-18** Medium: generated writers and new classes below the coverage of their neighbours; public methods no test calls
- [ ] **TD-19** Medium: loaders for untrusted input are not fuzzed
- [ ] **TD-20** Medium: performance is not guarded in CI
- [ ] **TD-21** Low: test hygiene (threshold oracles, JIT-dependent tests, an assertion-free test, stdout)
- [ ] **TD-22** Low: Valhalla readiness checked by string matching; Valhalla CI job cannot fail
- [ ] **TD-23** Low: compiler and javadoc warnings
- [ ] **TD-24** Low: dead and superseded code
- [ ] **TD-25** Low: inconsistent error signalling
- [ ] **TD-26** Low: live backing arrays exposed by the containers and `Mesh`
- [ ] **TD-27** Low: suppressed lints and unchecked casts
- [ ] **TD-28** Low: known functional gaps recorded in prose only

---

## 2. What was checked, and what came back clean

Method: compiler and javadoc output of a clean `--rerun-tasks` build of every module; JaCoCo class-level coverage and the two PIT runs of `docs/COVERAGE.md`; and heuristic scans over the 154 main and template sources,
108 test sources and 45 sources of the other modules (size and branch counts per method, a 7-line sliding window for duplicate code, a regex scan for public declarations with no preceding doc comment, import graph with cycle
detection, name-occurrence counting for dead code, a check of every backticked identifier in the docs against the code). The scans are heuristics: they find candidates, and each item below was then checked by reading the code.
No dynamic analysis, security scanner or profiler was run.

Clean, so not itemised:

- **No `TODO`, `FIXME`, `XXX` or `HACK` comments** in any source, build file or document.
- **No package cycles** among the 15 packages (the import graph is acyclic; fan-in is highest for `core` 13 and `geo` 7).
- **No mutable static state** in main or template code (every `static` field is `final`), no `synchronized`, no `ThreadLocal`, no `System.out`/`System.err`/`printStackTrace`, one `catch (Throwable)` (the executor hand-off in `ParallelFrustumKernel`, which rethrows).
- **No `@Deprecated` usage** and no deprecation warnings from the compiler.
- **The compiler is quiet:** two `javac` warnings in the whole library (TD-23), none in tests.
- **Untrusted input is guarded where it enters:** `Gltf` checks sizes and offsets with overflow-safe arithmetic, limits JSON depth and zero-fill size (TD-19 is about *testing* that, not about missing guards).
- **Release hygiene that exists:** `.github/workflows/ci.yml` builds on Linux and Windows, runs a nightly random-seed build and a Valhalla job; japicmp runs in `check`; per-package coverage floors run in `check`.

---

## 3. Critical

None found. The closest calls are listed as High (TD-01, TD-04) with the reason they were not rated Critical.

---

## 4. High

### TD-01 — GPU-facing code is verified only against Java references

- **Severity:** High. Not Critical because every affected class is `@Experimental`, says in its Javadoc that it is untested on hardware, and nothing else depends on it being right.
- **Affected files:** `gpucull/GpuCullGlsl.java` (the compute shader text), `gpucull/GpuCullReference.java`, `gpucull/ClusterCullReference.java`, `camera/ClusterGrid.java` (`glslLookup`), `camera/DualParaboloid.java` (`glsl`), `gl/ShaderHeader.java` (GLSL and Slang output), `gl/LayoutValidator.java`, `gl/VertexFormat.java` (GL and Vulkan enum numbers), `gl/VertexBufferLayout.java`, `mem/PersistentBufferRing.java` (`FenceOps` hooks), `gl/DrawCommandBuffer.java`, and `docs/GPU.md`, `docs/COOKBOOK.md` (recipe 3).
- **Explanation:** Everything that touches a graphics API is checked against the Java code written alongside it, not against a driver. The shaders were never compiled or run; the layout validator was checked against reflection data worked out by hand;
  the persistent ring was checked against a simulated GPU; the GL and Vulkan numbers in `VertexFormat` were checked against the registries from memory and against `TextureFormat`'s values for the formats they share. A transcription error in any of these
  would pass every test in the repository. The roadmap's own DOC-5 (an LWJGL sample) is the only planned check.
- **Recommended fix:** (1) cheapest: compile `GpuCullGlsl`, `ClusterGrid.glslLookup`, `DualParaboloid.glsl` and the `ShaderHeader` output with a command-line compiler (`glslangValidator` or `shaderc`) in CI, as a test that skips when the tool is missing. (2) an opt-in integration module
  (`vmath-gpu-it`, not part of `build`) that opens a headless OpenGL 4.5 or Vulkan context through LWJGL, runs the culling compute shader on the generated buffers and compares the instance lists, the cluster commands and the layout reflection with the references, and exercises the persistent
  ring with real fences. (3) pin the GL and Vulkan numbers against the Khronos header files in a test that reads them when present.
- **Estimated scope:** step 1 S, step 3 S, step 2 L to XL (needs a GPU runner or a software rasterizer such as Mesa llvmpipe/lavapipe on the CI machine).
- **Testing required:** shader compilation as a test; GPU-versus-reference comparison on random scenes in all three depth conventions; reflection-versus-`LayoutValidator` for every `@GpuStruct` in the repository; a fence-ordering test against the real API.
- **Depends on:** DOC-5 (sample app) overlaps step 2. TD-04 for the ring's threading contract.

### TD-02 — Release identity is unresolved

- **Severity:** High: it blocks the first publication, and two of the three problems are cheap to get wrong once published.
- **Affected files:** `build.gradle.kts` (`group = "vmath"`, `version = "0.1.0-SNAPSHOT"`), `gradle/publishing.gradle.kts`, `CHANGELOG.md`, `LICENSE`, `docs/VERSIONING.md`, `docs/PUBLISHING.md`, `docs/API-COMPAT.md`.
- **Explanation:** (a) The git tag `v0.1.0` exists and is the japicmp baseline, but the build version is still `0.1.0-SNAPSHOT` and the changelog says "nothing has been released yet": by the policy's own checklist the next version should not be a snapshot of an already-tagged number.
  (b) The Maven group `vmath` cannot be published to Maven Central (a namespace must be verified; `io.github.andreasnilsson123` is the obvious candidate) and is part of every consumer's dependency, so it must change before the first release, not after. (c) The `LICENSE` holder ("Andreas Nilsson") is recorded in notes as
  "to be confirmed by the owner". (d) The `japicmp` baseline jar is built from the tag in a nested Gradle build; if the tag were ever re-pointed the check would silently compare against something else.
- **Recommended fix:** decide the coordinates (group, artifact names), the next version (`0.2.0-SNAPSHOT` if `v0.1.0` stays the baseline, or delete and re-cut the tag before anyone depends on it), and confirm the licence holder; then update the three files, run `verifyPublication`, and do one dry run of `docs/VERSIONING.md`'s release checklist
  from a clean clone.
- **Estimated scope:** S, but it needs the owner's decisions (not an engineering problem).
- **Testing required:** `./gradlew publishAllPublicationsToStagingRepository verifyPublication`, a fresh-clone `build`, `japicmp` against the chosen baseline, and a consumer project that resolves the staged artifacts.
- **Depends on:** none. INF-10 (done as setup) consumes it.

### TD-03 — 35% of the public declarations have no doc comment

- **Severity:** High for a library whose pitch includes being complete and usable; Javadoc is the only reference for most method contracts (NaN behaviour, aliasing, what is returned on failure).
- **Affected files:** 673 of 1,902 public declarations. By package: `core` 225 of 449 (50%), `geo` 68 of 162 (42%), `gltf` 22 of 48 (46%), `bulk` 108 of 277 (39%), `camera` 37 of 121, `mesh` 51 of 160, `pack` 32 of 109, `spatial` 36 of 159, `gl` 31 of 128. Worst files:
  `template/.../Vec3f.java` (31), `Vec2f` (30), `Vec4f` (29), `core/Vec3i.java` (26), `Vec2i` (25), `gltf/Gltf.java` (22), `bulk/BoundsArray.java` (20), `template/.../Mat4f.java` (20), `mesh/ClusterHierarchy.java` (17). Because the double types are generated, each template gap is doubled in the API.
- **Explanation:** The javadoc build runs with `-Xdoclint:all,-missing -Xwerror`, which catches wrong documentation but not absent documentation (roadmap INF-5, left open on purpose). The other modules show the same: the javadoc tool reports 8 missing comments in `vmath-annotations`, 26 in `vmath-codegen`, 100 in `vmath-bench` (a bench is not API, but the 12 "default constructor" notes are
  the same pattern as TD-23's).
- **Recommended fix:** raise the bar package by package instead of all at once: add `missing` to the doclint of one package at a time (start with `core` and `geo`, which have the most users), fix its report, then lock it. For the templates, write the Javadoc once; the generator copies comments to the twins.
  One sentence per method is enough where the behaviour is the obvious one; the value is in stating the corner cases (zero vector, NaN, aliasing, units).
- **Estimated scope:** L (about 670 declarations; the arithmetic ones are quick).
- **Testing required:** the `javadoc` task with `missing` enabled per package and `-Xwerror`; a check that generated twins receive the template comments.
- **Depends on:** INF-5 (the roadmap item this is the remainder of). Pairs with TD-12.

### TD-04 — Thread-safety contracts are mostly unwritten, and two hazards are untested

- **Severity:** High: concurrency is part of the product (parallel culling, shared upload staging), and the unwritten rule ("one instance per thread unless stated") lives in the author's head. Not Critical because the hazards need a misbehaving executor or a deliberate cross-thread resize.
- **Affected files:** only 17 of the 135 hand-written main sources mention thread safety at all. Concrete hazards: `bulk/SegmentFloatArray.java` (class comment says the shared `Arena` lets other threads *read* the memory, but `ensureCapacity` closes the old arena, so a reader holding the old `segment()` gets an `IllegalStateException`; the contract for concurrent readers is not written);
  `spatial/ParallelFrustumKernel.java` (waits on a `Phaser` for every chunk it handed out; an executor that accepts a task and never runs it, for example a pool shut down with `shutdownNow` after the task was queued, hangs the caller forever; there is no timeout and no test with a failing or discarding executor);
  `bulk/PrefixSum.java` (`exclusiveParallel` uses the common fork-join pool and allocates; documented, but a caller cannot supply their own pool); `spatial/FrustumKernels.java`, `bulk/MatrixKernels.java` (`best()` is safe to call from many threads, but nothing says so); stateful classes with scratch buffers (`RadixSorter`, `DirtyRanges`, `HandleRegistry`, the allocators, `ClusterLights`, `GpuCullReference.Counters`).
- **Explanation:** The immutable records are safe by construction. Everything else is "not thread-safe, one per thread" by convention, stated unevenly. Two classes make promises that a test does not back.
- **Recommended fix:** give every public non-record class one `<p><b>Thread safety.</b>` sentence (immutable / one instance per thread / safe for concurrent reads between mutations / fully thread-safe); add the missing contract to `SegmentFloatArray` (readers must stop before growth, or growth must be explicit and never implicit under sharing);
  give `ParallelFrustumKernel` a documented limit on executors (must run every accepted task) and a test with a rejecting executor and one that drops queued work (decide whether to add a timeout or only document); optionally let `PrefixSum.exclusiveParallel` take an `Executor`.
- **Estimated scope:** M (documentation is S; the tests and the two behavioural decisions are M).
- **Testing required:** concurrency tests: `ParallelFrustumKernel` with a rejecting executor, a throwing task and a discarding executor (assert it fails fast or document the hang); `SegmentFloatArray` read-while-grow; a stress test of `FrustumKernels.best()` and `MatrixKernels.best()` from many threads; ideally a `jcstress`-style check of `HandleRegistry` misuse being detected, not hidden.
- **Depends on:** TD-15 (ownership of the same arena), TD-03 (the Javadoc pass is the natural moment).

---

## 5. Medium

### TD-05 — One third of the classes are `@Experimental`, with no promotion plan

- **Severity:** Medium.
- **Affected files:** 54 of 154 hand-written and template classes: all of `vmath.gpucull`, `vmath.mem`, `vmath.color`, and most of the layers added since `v0.1.0` (`bulk` additions, `gl` vertex and shader classes, `camera` lighting and views, `pack` quantizers, `mesh` processing). `docs/VERSIONING.md`, `vmath-annotations/.../Experimental.java`.
- **Explanation:** The japicmp check ignores anything marked `@Experimental`, so a third of the surface has no compatibility protection, and the policy says "removed in a minor release, announced as Promoted" but nothing records which classes are candidates, what evidence promotion needs, or when. Every release postponed makes the unprotected surface larger.
- **Recommended fix:** add a table to `docs/VERSIONING.md` (or a `docs/experimental.md`) that lists each experimental class or package with the evidence still missing (hardware validation for the GPU layers, real-world use for `mem`, a second consumer for `color`), and a rule such as "promote after one release with no change". Review it at each release.
- **Estimated scope:** S.
- **Testing required:** none new; japicmp then covers the promoted classes automatically.
- **Depends on:** TD-01 (the GPU classes cannot be promoted before that), TD-03 (promoted APIs need documentation).

### TD-06 — Test strength is unmeasured outside `vmath.core`; coverage ignores three modules

- **Severity:** Medium.
- **Affected files:** `docs/COVERAGE.md`; `vmath-simd/` (the Vector API kernels and their providers), `vmath-codegen/` (727-line `Transformer.java`, `GpuStructGenerator.java`), `vmath-bench/`; every package other than `core` for mutation testing.
- **Explanation:** The JaCoCo report covers the root project only, so the SIMD kernels (which are selected at run time and affect results) and the code generator (which produces every `d` type and every `XxxGpu` writer) have no coverage figure. PIT was run only on `vmath.core` (98.4% killed after the first fixes). The two largest oracle-tested areas, `spatial` (95.3% lines, 85.5% branches) and `mesh`, have not been mutation tested, so their tests may cover lines without checking results.
- **Recommended fix:** aggregate JaCoCo across `vmath-simd` and `vmath-codegen` (the `jacoco-report-aggregation` plugin or a root task over the sub-project data) and give them floors; run `mutationTest` on `vmath.spatial.*`, `vmath.mesh.*`, `vmath.bulk.*` and `vmath.gltf.*` in turn (each 15 to 40 minutes), fix the real gaps, and record the numbers in `docs/COVERAGE.md` the way `core` was.
- **Estimated scope:** M (the runs are mostly waiting; the fixes depend on what they find).
- **Testing required:** the new tests themselves; a floor per added module.
- **Depends on:** none. TD-18 and TD-21 are likely to shrink as a result.

### TD-07 — Kernel selection (SPI) logic is barely tested, and does repeated service lookups

- **Severity:** Medium.
- **Affected files:** `bulk/MatrixKernels.java` (35.5% lines, 20% branches), `spatial/FrustumKernels.java` (36.7% lines, 20% branches), `bulk/MatrixKernelProvider.java`, `spatial/FrustumKernelProvider.java`; `vmath-simd/` providers.
- **Explanation:** The paths that decide which kernel runs are the least covered classes in the library: the forced-name property, a provider that throws, a provider that reports unsupported, and the scalar fallback are only exercised in the `vmath-simd` module's tests, if at all, and not in the core module where the logic lives. `FrustumKernels.best()` also creates a `ServiceLoader` on every call and
  `new ParallelFrustumKernel(executor, parts)` calls it once per part, so a service scan and provider instantiation are repeated at each construction.
- **Recommended fix:** unit-test selection in core with a test-only provider registered through `META-INF/services` in the test resources (forced name present and absent, a provider that throws `ServiceConfigurationError`, one that returns `isSupported() == false`, priority ties); cache the provider list (or the chosen provider) per class loader after the first lookup and document that `-Dvmath.kernel` is read at that time.
- **Estimated scope:** S.
- **Testing required:** the cases above in core; one test that two `best()` calls do not re-scan (a counting provider).
- **Depends on:** TD-04 (the cache must be safe for concurrent first use).

### TD-08 — Copy-pasted container boilerplate

- **Severity:** Medium: a fix to growth, bounds checks or `writeTo` has to be made in six places, and they have already drifted slightly (for example the minimum capacity is 1 in `Vec3fArray` and 4 in `BoundsArray`).
- **Affected files:** `bulk/Vec3fArray.java`, `Vec4fArray.java`, `QuatArray.java`, `Mat4fArray.java`, `TransformArray.java` (duplicate-window scan: 16 shared windows between `Mat4fArray` and `QuatArray`, 8 between `QuatArray` and `Vec4fArray`, 6 and 3 for the other pairs), `BoundsArray.java`, and the off-heap twin `SegmentFloatArray.java`.
- **Explanation:** `size`, `capacity`, `clear`, `setSize`, `ensureCapacity`, `checkIndex`, `data`, both `writeTo`s, `readFrom`, `removeSwap` and `compact` are the same code with a different stride in each class. `docs/BULK.md` and the roadmap's MEM-3 already name the fix (generating them) but it has not been done.
- **Recommended fix:** put the shared mechanics in one package-private base (`FloatElements` with a stride, a `float[]` and a size) that the concrete classes extend or delegate to, keeping the public methods and their types as they are (so japicmp stays quiet); or generate the containers from one template with the codegen tool. Delegation is the smaller change.
- **Estimated scope:** M.
- **Testing required:** the existing container tests unchanged plus the allocation-contract tests (the delegation must stay allocation-free and JIT-inlinable); a before/after run of `BulkBench` to show no slowdown.
- **Depends on:** none. Supersedes the roadmap's MEM-3 for the containers.

### TD-09 — Duplicated traversal and kernel code

- **Severity:** Medium.
- **Affected files:** `spatial/BvhQuery.java` and `spatial/DynamicAabbTree.java` (31 shared 7-line windows: frustum, overlap and ray traversal written twice); `spatial/UniformGrid.java` and `spatial/LooseOctree.java` (14 windows); `vmath-bench/.../CullKernelBench.java` against `vmath-simd/.../SimdFrustumCuller.java` (7) and `spatial/FrustumCuller.java` (2: the benchmark carries private copies of the kernels);
  `vmath-simd/.../SimdFrustumKernelProvider.java` and `SimdMatrixKernelProvider.java` (3, near-identical providers).
- **Explanation:** Two dynamic and static BVH queries that must agree are maintained separately, which is exactly the kind of drift the brute-force oracle tests then have to catch. The benchmark copies mean a kernel can be optimized and its benchmark keep measuring the old code.
- **Recommended fix:** extract the node-traversal core (visit with a plane mask, ray slab step, overlap test) into package-private helpers used by both trees; make `CullKernelBench` call the production kernels instead of copies (or document the copies as deliberate baselines); share the provider boilerplate through a small abstract class in `vmath-simd`.
- **Estimated scope:** M.
- **Testing required:** the BVH and tree oracle tests unchanged; `FrameBench` and `SpatialStructBench` before and after (no regression); the allocation contract.
- **Depends on:** TD-10 (same classes), TD-20 (benchmarks need to catch a regression).

### TD-10 — Oversized classes and methods

- **Severity:** Medium.
- **Affected files:** classes: `gltf/Gltf.java` (1,228 lines: parsing, accessor decoding, mesh building, skins, animations and security checks in one class), `spatial/DynamicAabbTree.java` (1,052), `mesh/MeshSimplifier.java` (737), `vmath-codegen/.../Transformer.java` (727), `spatial/UniformGrid.java` (665), `mesh/ClusterHierarchy.java` (591), `spatial/LooseOctree.java` (567).
  Methods (length, branch count): `mesh/UvAtlas.generate` (271 lines, 55 branches), `spatial/StaticBvh.build` (208, 33), `mesh/Meshlets.build` (182, 43), `mesh/ClusterHierarchy.build` (178, 37), `mesh/MeshSimplifier.build` (134, 30), `mesh/Overdraw.optimize` (131, 29), `mesh/RectPacker.pack` (120, 37), `mesh/MeshOptimizer.optimizeVertexCache` (118, 31),
  `gltf/Gltf.skin` (116, 34), `mesh/MeshTools.computeNormalsWithCrease` (112, 26), `gltf/Gltf.toMesh` (95, 31), `spatial/LightCull.faceMask` (29 lines, 26 branches).
- **Explanation:** The long methods are algorithmic (greedy builds with several phases) and are well covered (`mesh` 97.9% lines), so this is a readability and change-risk cost rather than a defect; `Gltf` is the one class whose responsibilities are separable.
- **Recommended fix:** split `Gltf` into the container parser, an accessor reader and the scene/mesh/skin builders (same public API, package-private pieces); break the long builds into named phase methods with their intermediate state in a small private record or class; leave `LightCull.faceMask` alone unless it is touched (it is a table-like case analysis).
- **Estimated scope:** L for `Gltf` and the `mesh` builds together, M for the trees.
- **Testing required:** the existing tests plus a before/after comparison of outputs on the real-asset tests (`RealGltfAssetsTest`) and the property tests; JMH where a method is timed (`HierarchyBench`, `SpatialStructBench`).
- **Depends on:** TD-09 (same trees), TD-19 (fuzz the loader before restructuring it).

### TD-11 — Package layering, and one module with 17 exported packages

- **Severity:** Medium.
- **Affected files:** `module-info.java`; `spatial/CascadeCasters.java` (the only reason `spatial` imports `camera`); `mesh/ClusterHierarchy.java`, `mesh/Meshlets.java` (import `spatial.ConeCull`); `gl/InstanceWriter.java` (the only reason `gl` imports `bulk`); `gpucull/*` (imports seven other packages, `bulk`, `core`, `geo`, `gl`, `mesh`, `occlusion` and `spatial`, besides the annotations); `gl/ClusterLight.java` (a lighting struct in the layout package).
- **Explanation:** There are no cycles, but a few edges point against the natural layers: the low-level `spatial` package depends on `camera`, `mesh` depends on `spatial` for a cone test that is plain geometry, and `gpucull` is a leaf with a very wide fan-out, so a change in any of seven packages can break it. All 17 packages are exported from one JPMS module (roadmap INF-6, open), so a consumer that wants only `core` and `geo` cannot depend on less.
- **Recommended fix:** move `ConeCull` to `geo` and `CascadeCasters` next to `Cascades` in `camera` (both are additions only if the old names stay as deprecated forwarders; the experimental ones can simply move); decide whether `gpucull` stays in the core module or becomes `vmath-gpucull` with `vmath-gl`; then carry out INF-6 (separate modules for `core`+`geo`, `bulk`/`spatial`, `mesh`/`gltf`/`tex`, `gl`/`gpucull`, `anim`, `camera`).
- **Estimated scope:** M for the moves, XL for the module split (templates and the generator need per-module directories).
- **Testing required:** `ModuleDescriptorTest` per module, japicmp (the moves of stable classes need deprecation forwarders), the full build and the Valhalla build.
- **Depends on:** TD-02 (coordinates), TD-05 (experimental classes move freely before promotion).

### TD-12 — Stale README and ROADMAP text

- **Severity:** Medium: the first page a reader sees contradicts the repository.
- **Affected files:** `README.md` ("Next steps" list), `docs/ROADMAP.md` (sections 1 and 2), `README.md`'s JOML table (a second copy of `docs/COOKBOOK.md` recipe 4).
- **Explanation:** README's "Not built yet, roughly in order of value" lists mesh processing and a glTF loader, skinning and blending, indirect-draw and vertex-format structs and a GLSL header generator, and colour spaces: all built. It still lists IK, random and noise utilities and curves, which are real gaps. ROADMAP section 1 ("Where we are") describes "12 immutable records ... ~2.4k lines of main code" and a text-based generator in `tools/GenDouble.java`;
  the repository now has about 23,000 lines of main code, 15 packages and an annotation-driven code generator in `vmath-codegen`, and the findings of section 2 were addressed long ago. The identifier scan of the docs found no other missing class names (`CHANGELOG`/`ROADMAP` mentions of `Vec4i`, `Mat2`, `Predicates`, `FastMath`, `DoubleDouble` are planned work, not stale).
- **Recommended fix:** replace README's "Next steps" with a three-line pointer to the roadmap plus the current top gaps; move the historical analysis of ROADMAP sections 1 and 2 into a short `docs/history.md` or delete it, leaving the phase lists (which are accurate); make README's JOML section link to the cookbook instead of repeating it; add a test (like `CookbookDocTest`) that fails when a doc mentions a class that does not exist, using the scan that produced this finding.
- **Estimated scope:** S.
- **Testing required:** the doc-identifier test above; `CookbookDocTest` already guards the cookbook.
- **Depends on:** none.

### TD-13 — Dependency lag, and the version strings live in four places

- **Severity:** Medium.
- **Affected files:** `build.gradle.kts` (`org.joml:joml:1.10.8`, `junit-bom:5.13.4`, japicmp `0.23.1`, JaCoCo `0.8.14`, PIT `1.30.0`/`1.2.3`, JMH `1.37` in `vmath-bench`), `vmath-codegen/build.gradle.kts`, `vmath-simd/build.gradle.kts` (each repeats `junit-bom:5.13.4`), `gradle/wrapper/gradle-wrapper.properties` (Gradle 9.6.0).
- **Explanation:** Maven Central lists JUnit BOM 6.1.3 (a new major), japicmp 0.26.2, JOML 1.10.9 and JaCoCo 0.8.15; PIT and JMH are current. JOML is only the test oracle, so a bump matters for the oracle's own bug fixes, not for users. The JUnit 5.13.4 string appears in three build files and nothing reminds anyone to keep them equal. There is no Dependabot or Renovate configuration, so
  the lag is found only by looking.
- **Recommended fix:** introduce a Gradle version catalog (`gradle/libs.versions.toml`) and reference it from every module; enable Dependabot (or Renovate) for Gradle and for the GitHub Actions in `ci.yml`; take JUnit 6 and the japicmp, JOML and JaCoCo bumps as separate small changes, each followed by a full build.
- **Estimated scope:** S for the catalog and the bot, S to M for JUnit 6 (a major version: check the launcher and the `junit-platform-launcher` runtime dependency).
- **Testing required:** the whole build, the Valhalla build and the japicmp comparison after each bump; the JOML oracle tests may expose a JOML behaviour change that must be understood, not tolerated.
- **Depends on:** none.

### TD-14 — Build logic complexity and hard-coded toolchains

- **Severity:** Medium.
- **Affected files:** `build.gradle.kts` (443 lines: code generation, Valhalla switching, japicmp with a nested baseline build, JaCoCo with a floors table, PIT, publishing hook), `gradle/publishing.gradle.kts` (137), `vmath-annotations/build.gradle.kts` and `vmath-codegen/build.gradle.kts` (toolchain `25` hard-coded), `vmath-simd/build.gradle.kts`, `vmath-bench/build.gradle.kts`.
- **Explanation:** Everything is in one root script with no convention plugins, so a change to, say, the JDK baseline touches `baselineJdk` in the root script and a literal `25` in two other modules, and `valhallaJdk = 28` is repeated in the simd and bench scripts. The japicmp baseline machinery (git archive, unpack, nested Gradle, stage) is the most fragile part and has no test of its own. The build also suppresses lints
  (TD-27) and does not use the configuration cache (Gradle prints the suggestion on every run).
- **Recommended fix:** move the shared pieces into `buildSrc` or an included `build-logic` build as convention plugins (`vmath.java-conventions`, `vmath.coverage`, `vmath.publishing`, `vmath.compat`); read the JDK numbers from the version catalog; try the configuration cache and fix what it reports; add a small test (or a CI step) that exercises the baseline build path from a clean clone.
- **Estimated scope:** M.
- **Testing required:** full build, Valhalla build, `japicmp` from a clean clone (no `build/` directory), `verifyPublication`, and a timing comparison of a no-change build.
- **Depends on:** TD-13 (the catalog), TD-11 (a module split would multiply the duplication if done first).

### TD-15 — Native memory ownership

- **Severity:** Medium.
- **Affected files:** `bulk/SegmentFloatArray.java`, `mem/PersistentBufferRing.java`, `mem/ArenaAllocator.java`, `mem/SlabAllocator.java`, `mem/FreeListAllocator.java`, `mem/RingAllocator.java`, `gltf/Gltf.java` (`Files.readAllBytes`), `docs/MEMORY.md`.
- **Explanation:** `SegmentFloatArray` owns native memory in a shared `Arena` that is released only by `close()`; there is no `Cleaner`, so forgetting `close()` leaks until the process ends (shared arenas are not reclaimed by the garbage collector), and `segment()` hands out views that become invalid on growth or close, as TD-04 describes. The allocators and the ring deliberately do not own their memory (they take a `MemorySegment` or just a size),
  which is documented, but `PersistentBufferRing.drain()` must be called before the mapped buffer is unmapped and nothing enforces it. `Gltf.load(Path)` reads a whole file into the heap before parsing, so a very large `.glb` costs its full size in memory.
- **Recommended fix:** register a `Cleaner` (or offer an automatic-arena mode) for `SegmentFloatArray` so a missed `close()` is not a permanent leak, and say in one place which classes own memory (a table in `docs/MEMORY.md`); make `PersistentBufferRing` implement `AutoCloseable` where `close()` drains; add a streaming or memory-mapped path to `Gltf` for large files, or document the size limit.
- **Estimated scope:** S to M.
- **Testing required:** a test that drops a `SegmentFloatArray` without closing it and checks the arena is reclaimed (if a cleaner is added); a ring test that `close()` drains and releases every fence; a `Gltf` test on a file larger than the default heap fraction is not practical, so test the chosen limit.
- **Depends on:** TD-04.

### TD-16 — Algorithmic hot spots with measured cost

- **Severity:** Medium: each is documented and measured, none is wrong, and each is the slow part of a feature that is sold on speed.
- **Affected files:** `mem/FreeListAllocator.java`, `core/Hilbert.java`, `bulk/RadixSorter.java`, `gpucull/GpuCullReference.java`, `spatial/FrustumKernels.java`.
- **Explanation:** (a) `FreeListAllocator` scans every block on each allocation and shifts arrays on each split and merge: 805 µs for 1,000 allocations plus 1,000 frees at up to 1,000 live blocks (about 0.4 µs per call, growing with the block count); `docs/MEMORY.md` says so and names the fix (size-class index). (b) Hilbert encode costs about 104 ns per code against 6 ns for Morton (after a branch-free rewrite gained 11%), so locality-ordering a million points with Hilbert takes 186 ms against 75 ms
  (a table-driven, several-levels-per-lookup version is untried). (c) `RadixSorter` on 64-bit keys with a payload was no clear win at a million elements (72 ms ± 37 against 80 ms for `Arrays.sort`): six passes over 8-byte keys are memory-bound. (d) `GpuCullReference` reads every field through `MemorySegment` accessors: about 35 ns per object frustum-only and 150 ns with Hi-Z, which is fine for an oracle and wrong to use as a production path (documented).
  (e) `FrustumKernels.best()` rescans services on every call (TD-07).
- **Recommended fix:** (a) keep free blocks in size-segregated lists or a balanced tree keyed by size, with a boundary-tag or sorted-offset structure for merging; (b) try a lookup-table Hilbert with 2 to 3 levels per step and keep it only if it wins; (c) try 8 passes of 8 bits or an MSD first pass for 64-bit keys with fewer significant bits, keep only if measured faster; (d) no change, keep the warning; (e) TD-07.
- **Estimated scope:** M for (a), S each for (b) and (c).
- **Testing required:** the existing oracle tests (`AllocatorsTest` with its `validate()` invariant, `HilbertMortonTest` properties, `SortingTest`) unchanged; `MemBench` and `SortBench` before and after; record losses as well as wins, as the project does.
- **Depends on:** TD-20 (so that the next regression is noticed).

### TD-17 — Allocation in paths that read as allocation-free

- **Severity:** Medium: the library's contract is zero allocation on hot paths, and these paths allocate without saying so in a way a reader of the call site would notice.
- **Affected files:** `bulk/SegmentFloatArray.java` (`addMat4` and `getMat4` allocate a 16-float scratch array per call), `mesh/MeshExport.java` (`writeVertices` allocates a `Vec3f` per vertex for the octahedral and quantized formats and a scratch `short[]`), `color/ColorSpaces.java` and `core/SpatialHash.java` (the `Vec3f`-returning overloads allocate, by design, next to allocation-free `float[]` variants),
  `bulk/PrefixSum.java` (`exclusiveParallel`), `spatial/ParallelFrustumKernel.java` (executor hand-off, documented), `AllocationContractTest` (which does not cover `SegmentFloatArray`'s typed accessors, `MeshExport`, or `VertexBufferLayout`'s list-returning methods).
- **Explanation:** The contract test pins the kernels and containers that matter most. The paths above are outside it. Most are conversion or setup code where an allocation is acceptable, but `MeshExport.writeVertices` is a bulk loop over every vertex of a mesh and `SegmentFloatArray.addMat4` is the obvious way to fill an off-heap matrix array element by element.
- **Recommended fix:** write the matrix and `Vec3f`-free variants (`add(float[], int)` and `get(int, float[], int)` are the allocation-free path: say so in the Javadoc of `addMat4`/`getMat4`); rewrite `MeshExport`'s per-vertex conversions on primitives (`Octahedral.pack16(x, y, z)` and a `Quantizer.pack(float, float, float, ...)`); add the remaining entry points to `AllocationContractTest`, marking the ones that allocate by design.
- **Estimated scope:** S to M.
- **Testing required:** extend `AllocationContractTest`; a mesh export test on a large mesh comparing the bytes with the previous output (byte-identical); `MeshBench` if one exists for export (add one).
- **Depends on:** none.

### TD-18 — Generated writers and new classes below their neighbours' coverage; public methods no test calls

- **Severity:** Medium.
- **Affected files:** classes under 90% of lines (measured, `docs/COVERAGE.md` run): `gl/DrawArraysIndirectGpu` (56%), `gl/DispatchIndirectGpu` (57%), `mem/RingAllocator` (75%, 17 lines missed), `bulk/FrameDirtyRanges` (79%), `pack/UvQuantizer` (82%), `bulk/Vec4fArray` (83% lines, 69% branches), `mem/SlabAllocator` (87%),
  `bulk/QuatArray` (87%), `mem/FreeListAllocator` (88%), `bulk/TransformArray` (88%), `anim/Skeleton` (89%), `bulk/SegmentFloatArray` (90% lines, 70.5% branches), `anim/TransformMath` (90%).
  Public methods that no test, benchmark or document references: `gltf/Gltf.materialCount`, `textureCount`, `samplerCount`; `mesh/Mesh.disableTangents`; `occlusion/DepthBuffer.addTriangles`; `tex/TextureFormat.isCompressed`; `gpucull/HiZPyramid.depthRange`.
- **Explanation:** The generated `XxxGpu` writers are covered through the structs that tests write, so the `write(ByteBuffer, ...)` overloads and similar members that no test calls show up as uncovered (the indirect-draw structs are the clearest case). The new allocators and containers were written with a random-simulation test each, which leaves argument-check and `slice` helper branches uncovered. The unreferenced public methods are API that could be wrong and would not be noticed.
- **Recommended fix:** add the missing cases (a ring test with a zero-size allocation and a wrap with alignment, `FrameDirtyRanges` growth and slot errors, `UvQuantizer` bad rectangles and 1-bit grids, `Vec4fArray` and `QuatArray` buffer writers, byte-order variants), exercise both `write` overloads of every generated writer in `GpuStructTest`, and add one-line tests for the unreferenced public accessors.
- **Estimated scope:** M.
- **Testing required:** the tests themselves, then `coverageSummary` (raise the floors of the packages that improve).
- **Depends on:** TD-06 (mutation testing will point at the same places more precisely).

### TD-19 — Loaders for untrusted input are not fuzzed

- **Severity:** Medium: the code says "a glTF file is untrusted input" and guards it carefully; nothing in the repository proves the guards hold against input nobody thought of.
- **Affected files:** `gltf/Gltf.java`, `gltf/Json.java`, `tex/Ktx2.java`, `mesh/MeshExport.java` (reads nothing, but writes into caller memory), tests `gltf/GltfTest.java` (26 tests), `gltf/JsonTest.java` (6), `tex/TextureTest.java`, `assets/RealGltfAssetsTest.java`.
- **Explanation:** The tests cover specific malformed cases (truncated buffers, huge counts, nesting) that the author thought of, which is how `Gltf`'s limits were written. A randomized corpus (bit flips and truncations of the valid assets, random JSON, random KTX2 headers) would find the exceptions that are not `GltfException`/`IllegalArgumentException`, out-of-memory sizes, infinite loops and index-out-of-bounds that slip through.
- **Recommended fix:** add a seeded mutation fuzz test (flip, truncate, splice bytes of the generated test assets and of the real-asset tests' files; random JSON; random KTX2 level tables), asserting that the only failure type is the documented exception, that it completes within a time bound and that allocation stays under a cap; run it with more iterations in the nightly CI job.
- **Estimated scope:** M.
- **Testing required:** the fuzz test itself (it is the test); it should print the seed on failure like the existing property tests.
- **Depends on:** TD-10 (do this before splitting `Gltf`).

### TD-20 — Performance is not guarded in CI

- **Severity:** Medium: "extremely fast" is the first goal in the roadmap and the only check on it is a person running JMH.
- **Affected files:** `vmath-bench/` (all benchmarks), `.github/workflows/ci.yml`, `docs/PERFORMANCE.md`, `docs/BULK.md`, `docs/MEMORY.md`, `docs/GPU.md`, `docs/COLOR.md`.
- **Explanation:** Measured numbers are quoted in six documents from single runs on one machine, with no automated way to learn that a change made a documented kernel slower. The allocation contract is tested (and does protect the zero-allocation claim); time is not.
- **Recommended fix:** a nightly CI job that runs a small, stable subset of the benchmarks (the frustum kernel, `Mat4fArray.multiply`, `RadixSorter`, the allocators, `DirtyRanges` upload) with a fixed JMH configuration and stores the results as a build artifact; compare against the previous run with a generous threshold and open an issue or fail the nightly job on a large regression. Shared CI machines are noisy, so alert on ratios between
  related benchmarks (for example scalar against SIMD) as well as absolute time.
- **Estimated scope:** M.
- **Testing required:** a deliberate regression (an inserted slow path) must be caught, and the quiet baseline must not trip the threshold over a week of nightly runs.
- **Depends on:** none. TD-09 and TD-16 benefit.

---

## 6. Low

### TD-21 — Test hygiene

- **Severity:** Low.
- **Affected files:** `assets/AssetFilesTest.java` (`writeAssetsWhenAsked` has no assertion by design: it only writes files when `-Dvmath.writeAssets` is set), 14 test classes that print measured values to stdout (`SortingTest`, `ColorTest`, `ClusterExactOracleTest`, `GpuCullReferenceTest`, `HiZPyramidTest`, `EqualityContractTest`, ...), `AllocationContractTest` (JIT-dependent), threshold-style oracles (`HiZPyramidTest` requires at least 40% of hidden rectangles found against a measured 49.5%; the `SpatialHash` distribution tests; `ColorTest` fast-sRGB bounds), `DegenerateInputSweepTest` (12 s, the slowest test).
- **Explanation:** None of these is wrong. The allocation tests assume the JIT has compiled the path after a fixed warm-up and allow a tiny slack, which could flake on a very different machine or with a flag such as `-Xint`; the threshold tests pin a measured property loosely (by design: a regression fails, noise does not) so they cannot detect a small loss; the stdout lines are the source of the numbers quoted in the docs but clutter every run; the one assertion-free test should say so in its name or move to a tool.
- **Recommended fix:** move the writer test out of the test suite (a Gradle task) or give it a trivial assertion that the output path exists after writing; route measured-value prints through one helper that is silent unless `-Dvmath.verbose` is set; document in `Alloc.java` the conditions under which the contract is meaningful and skip (not fail) under `-Xint` or a debugger; keep the thresholds, and add the measured value to each failure message.
- **Estimated scope:** S.
- **Testing required:** the suite stays green on both CI operating systems; the contract test under `-Xcomp` and `-XX:TieredStopAtLevel=1` to see which settings it tolerates.
- **Depends on:** none.

### TD-22 — Valhalla readiness is checked by string matching, and the Valhalla CI job cannot fail

- **Severity:** Low (until Valhalla value classes become final and the build moves to them).
- **Affected files:** `src/test/java/vmath/core/ValhallaReadinessTest.java`, `.github/workflows/ci.yml` (`valhalla` job with `continue-on-error: true`), `docs/ROADMAP.md` AF-9.
- **Explanation:** `ValhallaReadinessTest` looks for `==` on value types, `synchronized` and identity-sensitive constructs with string heuristics. The Valhalla job is allowed to fail, so a regression on the early-access JDK is visible only to someone who opens the job.
- **Recommended fix:** the roadmap's AF-9: an annotation processor (or an `-Xplugin` javac plugin) that checks the rules on the syntax tree; make the Valhalla job blocking when a stable early-access build is pinned (a fixed build number in `setup-java`), keep it non-blocking against the moving latest build.
- **Estimated scope:** M for the processor, S for the CI change.
- **Testing required:** golden tests for each rule (like `vmath-codegen`'s), a deliberately violating fixture that the processor must reject.
- **Depends on:** TD-13 (pinning a build is a dependency decision).

### TD-23 — Compiler and javadoc warnings

- **Severity:** Low.
- **Affected files:** `gpucull/GpuCullReference.java` (line 43) and `gpucull/ClusterCullReference.java` (line 28): `[missing-explicit-ctor]` on the public nested class `Counters`, once each in a clean build; `vmath-simd` and `vmath-bench` builds: `[incubating] using incubating module(s): jdk.incubator.vector` (4 notices, unavoidable while the Vector API is incubating); the javadoc task of the other modules: `vmath-annotations` 8, `vmath-codegen` 26, `vmath-bench` 100 (79 missing comments, 12 default constructors, 9 missing `@return`), `vmath-simd` 1.
- **Explanation:** The core library compiles with `-Xlint:all` minus `exports` and `preview` and has exactly the two warnings above; all tests compile with none. The other modules' javadoc warnings are documentation gaps (TD-03's cousins). Gradle also prints a pointer to its incubating problems report on every run.
- **Recommended fix:** add explicit public constructors (or make `Counters` constructible through a factory) to remove the two warnings, then add `-Werror` to the core compile so the count stays at zero; document the annotations (8 comments, a few minutes); decide whether bench and codegen javadoc matters (if not, exclude them from the `javadoc` task and say so).
- **Estimated scope:** S.
- **Testing required:** a clean `--rerun-tasks` build with `-Werror`.
- **Depends on:** TD-27 (what the suppressed lints would show).

### TD-24 — Dead and superseded code

- **Severity:** Low.
- **Affected files:** `anim/TransformMath.java` (`multiplyQuat`, package-private, never called; its sibling `multiplyQuatByConjugate` is), `gl/Std140.java` (superseded by `GlslType`, `StructLayout` and `GpuWriter`, still public and tested by `Std140Test`, referenced from the `Mat3f` template's comment, never marked `@Deprecated`; `docs/GPU.md` says the layout engine "supersedes" it).
- **Explanation:** The scans found only these two. Everything else flagged by the name-occurrence scan was either used or a public accessor (TD-18).
- **Recommended fix:** delete `multiplyQuat` (or add the use that was intended); mark `Std140` `@Deprecated(since = ..., forRemoval = true)` with the replacement in the Javadoc and plan the removal for the next breaking release (`docs/VERSIONING.md` allows it before 1.0).
- **Estimated scope:** S.
- **Testing required:** `japicmp` (deprecation is compatible); `Std140Test` stays until removal.
- **Depends on:** TD-02 (the version in `since`).

### TD-25 — Inconsistent error signalling

- **Severity:** Low.
- **Affected files:** `mem/*Allocator.java` and `bulk/HandleRegistry.java` (return `-1`/`NONE` or `-1`), the containers (`IndexOutOfBoundsException`, `IllegalArgumentException`), `mesh/MeshExport.java` (`IllegalStateException` for a missing stream, `IllegalArgumentException` for a too-small destination), `gl/VertexBufferLayout.java` (`IllegalArgumentException` and `IllegalStateException`), `camera/PlanarViews.java`.
- **Explanation:** Each choice is reasoned and documented in its class (a full upload buffer is not exceptional; a bad index is a bug), but a user moving between classes meets three conventions: sentinel returns, unchecked exceptions chosen by the author, and in `MeshExport` two exception types for what is one class of mistake. There is no statement of the rule.
- **Recommended fix:** write the rule in one page (`docs/API.md` or the package docs): bad arguments throw `IllegalArgumentException`, bad object state `IllegalStateException`, out-of-range indices `IndexOutOfBoundsException`, "may legitimately fail" returns a documented sentinel; then align the outliers (they are experimental, so renaming costs nothing).
- **Estimated scope:** S.
- **Testing required:** the exception-type assertions already in the tests, updated where an outlier changes.
- **Depends on:** TD-03.

### TD-26 — Live backing arrays are exposed

- **Severity:** Low.
- **Affected files:** `bulk/*Array.java` (`data()`), `mesh/Mesh.java` (`positions()`, `normals()`, `tangents()`, `uvs(set)`, `indices()`), `bulk/VisibilitySet.java` (`words()`), `bulk/IntList.java` (`array()`), `gl/DrawCommandBuffer.java`.
- **Explanation:** Handing out the live array is the point (kernels and `System.arraycopy` need it) and every Javadoc says the array is replaced on growth. The cost is that a caller can write past `size`, keep a stale array after growth, or change a `Mesh` behind its invariants (`setSize` and counts) without any check.
- **Recommended fix:** none needed for performance code; add a debug validation (`Mesh.validate()` exists for some invariants; `assert`-style checks behind `-ea` in `setSize` callers) and keep stating the rule in the class comments. Revisit only if a bug is traced to it.
- **Estimated scope:** S.
- **Testing required:** none new.
- **Depends on:** none.

### TD-27 — Suppressed lints and unchecked casts

- **Severity:** Low.
- **Affected files:** `build.gradle.kts` (`-Xlint:-exports`, `-Xlint:-preview`), `gltf/Gltf.java` (three `@SuppressWarnings("unchecked")` for the JSON tree casts), `mem/PersistentBufferRing.java` (two for the generic fence array).
- **Explanation:** `-Xlint:-exports` is a deliberate answer to the class-retention `@Experimental` annotation (comment in the build file) and `-preview` to the Valhalla build; both hide a class of future warnings. The unchecked casts are local and tested but untyped: a malformed JSON tree reaches them, and they are the reason `Gltf` needs defensive checks everywhere.
- **Recommended fix:** after TD-23, try turning `exports` back on and see what it reports; replace the JSON `Object` tree with a small sealed `JsonValue` hierarchy (`JsonObject`, `JsonArray`, ...) so `Gltf` needs no casts; store the ring's fences in a typed `ArrayList<F>`-like structure or a generic helper class.
- **Estimated scope:** M for the JSON types (touches `Gltf` heavily), S for the rest.
- **Testing required:** `JsonTest`, `GltfTest` and the real-asset tests unchanged; TD-19's fuzz test first.
- **Depends on:** TD-10, TD-19, TD-23.

### TD-28 — Known functional gaps are recorded in prose only

- **Severity:** Low.
- **Affected files:** `mesh/ClusterHierarchy.java` (no vertex attributes: skinned and multi-attribute meshes cannot be reduced into a cluster hierarchy, while `MeshSimplifier` supports attributes), `gpucull/GpuCullReference.java` (boxes only; no oriented boxes or spheres), `camera/ClusterLights.java` (spot lights over-assign clusters by up to 47%),
  `color/*` (no wide-gamut spaces or gamut mapping), `docs/*.md` "Not covered yet" sections, `docs/ROADMAP.md` open items (CORE-4 inverse projection and shear, CORE-6 `Vec4i`, CORE-7 `Mat2`, CORE-8 dual quaternions, CORE-9, CORE-10, GEO-4 to GEO-8, ANIM-3 to ANIM-5, UTIL-1 to UTIL-6, CULL-10, CULL-14).
- **Explanation:** These are features, not defects, and each is stated honestly where it applies, but they live in a dozen "Not covered" paragraphs, which makes it hard to see what a user can rely on. They are listed here so the register is complete.
- **Recommended fix:** none now; when one is taken up it becomes a roadmap item. Consider a single "Limitations" table in the README that links to each section.
- **Estimated scope:** S for the table.
- **Testing required:** none.
- **Depends on:** TD-12.

---

## 7. Suggested order

1. **Cheap and unblocking:** TD-02 (owner decisions), TD-12 (stale text), TD-23 and TD-24 (warnings and dead code), TD-13 (catalog and bot). All S, all independent.
2. **Make the claims checkable:** TD-04 (contracts and the two tests), TD-07, TD-19, TD-06 (mutation runs), TD-18.
3. **Documentation pass:** TD-03, with TD-25 and TD-05 in the same sitting.
4. **Hardware reality:** TD-01 step 1 (compile the shader text in CI) early; the integration module when a GPU runner is available.
5. **Structure and speed, when something else needs the code:** TD-08, TD-09, TD-10, TD-11, TD-14, TD-16, TD-17, TD-20, TD-15.
6. **Whenever:** TD-21, TD-22, TD-26, TD-27, TD-28.

## 8. What this audit did not do

It ran no profiler, no allocation tracer beyond the existing contract tests, no security scanner and no dependency vulnerability check (the dependencies are test-time and build-time only: the published jar has no runtime dependencies, which the staged POM confirms). The duplicate, dead-code and documentation scans are text heuristics: a clone with renamed
variables, a method used only by reflection, or a doc mentioning a symbol in prose would be missed. It did not review the generated sources in `build/generated`, the `vmath-codegen` internals beyond size and warnings, or the benchmark code for correctness. Numbers quoted come from the sources named: this session's builds, `docs/COVERAGE.md`, `docs/MEMORY.md`, `docs/BULK.md` and `docs/PERFORMANCE.md`.
