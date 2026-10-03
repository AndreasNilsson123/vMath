# Javadoc pass: report

Scope: all production sources of `vmath-core`, `vmath-geo`, `vmath-scene`, `vmath-render`, `vmath-simd`,
`vmath-annotations`, `vmath-codegen` and `vmath-validator`, including the `src/template/java` sources
(the `double` twins are generated from those and inherit the text).

Documentation only. Every rewrite was checked by a guard that compares the code with comments stripped before
and after; no behaviour, signature or code formatting changed.

## Verification

| Check | Result |
| --- | --- |
| `./gradlew build` (tests, javadoc with `-Xdoclint:all,-missing -Xwerror`, japicmp against v0.1.0, coverage) | passed |
| `./gradlew build -Pvalhalla` on JDK 28 | passed |
| `publishAllPublicationsToStagingRepository verifyPublication` | passed |
| All 201 usage examples compiled against the real API (`excheck`) | 0 failing |

The japicmp baseline (tag v0.1.0) was not moved. Nothing is committed (see "Commits").

## (a) Thread safety that is not specified

Every public class has a thread-safety paragraph. These six say "Thread safety: not specified" because
the contract depends on the implementation that a caller plugs in, or I could not establish it from the code:

- `vmath.bulk.MatrixKernelProvider`: depends on the provider.
- `vmath.spatial.FrustumKernelProvider`: depends on the provider.
- `vmath.simd.SimdMatrixKernelProvider`: only the `ServiceLoader` instance is documented.
- `vmath.simd.SimdFrustumKernelProvider`: the same.
- `vmath.spatial.SectorVisibility`: which calls may overlap is not established.
- `vmath.spatial.ParallelFrustumKernel`: concurrent calls on one instance are not specified; the executor
  hand-off itself is.

Please decide each and replace the sentence.

## (b) Ambiguous intended usage

These are impressions from reading the code, not exhaustive; confirm before relying on them.

- Several classes can be used both as a one-shot helper and with a reusable scratch object
  (`MeshSimplifier`, `Overdraw`, `UvAtlas`, `ClusterHierarchy`). The docs show the one-shot form;
  say if the scratch form is meant to be public API.
- `GpuCullReference`, `ClusterCullReference` and `GpuCullGlsl` are described as CPU mirrors of the shader.
  Whether callers may rely on bit-identical results with a GPU is not stated anywhere and I did not claim it.
- `vmath.bulk` kernel providers: whether third parties should implement them or only `vmath-simd` is unclear.
  They are documented as extension points.
- Several `@GpuStruct` records were documented by layout only; their intended consumers (which shader stage) are not
  recorded in the code.
- `vmath-codegen` and `vmath-validator` are build tools. Their classes carry an "internal, not runtime API"
  note instead of usage examples. `package-info` for both says the same.

## (c) Suspected bugs and doc/code contradictions

No behaviour bug was found while documenting. Items worth a look:

- A second pass rewrote about 1,500 summaries that only restated the `@return` text (see "Summary pass").
  While doing it I found `@return` text that described something other than the return value, and fixed
  `MeshTools.computeNormalsWithCrease` (returns the remap array), `MeshSimplifier.simplify` overloads and
  `IkSolver.twoBone`/`lookAt` overloads. Other `@return` texts were derived from the old summaries and were
  not all re-checked against the code.
- Many `@param` descriptions of record components and short names are still generic ("the index",
  "the matrix"); only the two ungrammatical ones (`whether allow`, `whether stabilize`) were fixed.
- `@throws` clauses were generated from `throw` statements and `Objects.requireNonNull` calls in each body.
  Exceptions thrown by callees (for example from an argument's own method) are not listed, so the lists are a
  lower bound.
- Parameter descriptions for overloads that share a name were merged. Where several overloads take a parameter
  with the same name but a different meaning, the text is the common denominator (about 770 descriptions
  are still short, name-restating phrases; see `weak.json` in the working directory of the tool).
- Examples show intent, not benchmarks. Trailing comments in examples state only what the call does; no
  performance figure appears in any Javadoc.
- I looked for the mis-encoded character U+FFFD (for example in `Curves`) in all production sources: none found.

## Summary pass

Summaries of methods that returned a value are now about what the method does (operation, algorithm, cost,
caveats such as overflow, clamping, allocation, live arrays, conservative results). The value itself is described
only in `@return`. Statements about algorithms and caveats were written from reading the code and the class
comments; they were not each verified at run time.

## (d) Skipped, and why

- `vmath-bench`: excluded by the task (JMH harness).
- Generated sources (the `double` twins, generated GPU struct code): they inherit from the templates.
- Private and package-private members: not part of the documented API; existing comments were left as they are.
- Methods marked `@Override` without documentation inherit the parent's text, as intended.
- Test sources.
- Line width: about 290 comment lines exceed 100 columns. These are lines inside `<pre>{@code ...}</pre>`
  usage examples, where I kept trailing `//` comments aligned and did not wrap code (wrapping would change the
  example), plus a few lines with long `{@link}` targets that cannot be broken. Prose outside `<pre>` is wrapped at 100.
- `{@inheritDoc}` is used only where the override's contract is unchanged.

## Added

- `package-info.java` for every package that lacked one: core, mem, color, tex, geo, pack, physics, anim, bulk, gl,
  occlusion, spatial, util, camera, gltf, gpucull, mesh, simd, codegen, validator (`vmath.annotations` and the
  `vmath` module already had one).
- Usage examples on public API classes (compile-checked), thread-safety paragraphs on all public classes.

## Commits

The task asked for one commit per module. Your standing rule is that you commit and push yourself, so nothing
is committed. Suggested messages, one per module (each ends with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`):

- `docs(core): complete Javadoc for vmath-core and add package-info files`
- `docs(geo): complete Javadoc for vmath-geo and add package-info files`
- `docs(scene): complete Javadoc for vmath-scene and add package-info files`
- `docs(render): complete Javadoc for vmath-render and add package-info files`
- `docs(tools): complete Javadoc for simd, annotations, codegen and validator`
- `docs: add DOCS_REPORT.md`
