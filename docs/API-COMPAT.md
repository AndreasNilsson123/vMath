# API compatibility (japicmp)

`./gradlew check` (and so `build`) compares the public API of the library with a **baseline jar built from a git tag**
and fails on binary- or source-incompatible changes. The tool is the japicmp CLI, run by the `japicmp` task.

```
./gradlew japicmp                               # against tag v0.1.0
./gradlew japicmp -Pjapicmp.baselineTag=v0.2.0  # against another tag
./gradlew japicmp -Pjapicmp.baseline=path.jar   # against a jar you already have
./gradlew japicmp -Pjapicmp.allowBreak          # report the differences but do not fail (deliberate break)
```

The report is written to `build/reports/japicmp/index.html`. The baseline tag is **pinned**: `gradle/baseline-commits.txt` records `<tag> <commit>`, and `exportBaselineSource` stops with a message if the tag points anywhere else
(a moved tag would otherwise change what the API is compared with, silently). Move a tag only on purpose, and update the file in the same commit. The baseline tag is **pinned**: `gradle/baseline-commits.txt` records `<tag> <commit>`, and `exportBaselineSource` stops with a message if the tag points anywhere else
(a moved tag would otherwise change what the API is compared with, silently). Move a tag only on purpose, and update the file in the same commit. The baseline is built with `git archive <tag>` plus a nested
`gradlew jar` into `build/baseline/vmath-baseline.jar` (task chain `exportBaselineSource`, `unpackBaselineSource`,
`buildBaselineJar`, `stageBaselineJar`), so it is only rebuilt when the tag changes or `build/` is cleaned.

## When the check is skipped

The task prints a message and is skipped, never failed, when there is no baseline: the tag does not exist in this checkout
(a source archive, or a shallow clone without tags) and no `-Pjapicmp.baseline` jar was given. It is also skipped under
`-Pvalhalla`, where record kinds change on purpose. CI fetches full history and tags so the check really runs there.

## Releasing

1. Make sure `./gradlew build` is green, then tag the release, e.g. `git tag -a v0.2.0 -m "..."`.
2. From then on, pass `-Pjapicmp.baselineTag=v0.2.0`, or change the default in `build.gradle.kts` (`japicmpTag`).

## Records and the stability rule

Most public types are records. **Adding, removing or reordering a record component changes the canonical constructor, the
accessors and `equals`/`hashCode`, so japicmp reports it as a break.** That is correct: callers that construct the type
break. Consequences:

- Treat the component list of an existing record as frozen once it is in a tagged release. Add capability with methods, or a
  new type, instead.
- New types are additions and never break the check.
- While the version is `0.x` breaks are allowed, but they must be intentional: run with `-Pjapicmp.allowBreak`, read the
  report, and say so in the commit message. Types marked `@Experimental` are excluded from the check; the full policy is in `docs/VERSIONING.md`.

The generated double twins and the float types share one jar, so a change to a template shows up twice in the report.

**Since the module split (INF-6)** the baseline is still one jar (`v0.1.0` had a single module), and the new side is the jars of the four parts together (`vmath-core`, `vmath-geo`, `vmath-scene`, `vmath-render`; japicmp takes several archives for `--new`), so a class that moved between the jars does not count as removed: only a class that is in no part any more does. The aggregate jar has no classes and is not compared.
