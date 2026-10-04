# Working in IntelliJ IDEA

Open the folder as a Gradle project (File > Open, choose the folder with `settings.gradle.kts`). Nothing has to be passed on a command line: the project recognises that IntelliJ started
Gradle (the properties `idea.active` and `idea.sync.active`) and sets itself up.

## What is set up for the IDE

| What | How |
|---|---|
| the `vmath-samples` module (LWJGL, the OpenGL sample) is part of the project | `settings.gradle.kts` includes it inside IntelliJ; on the command line it needs `-Psamples` (docs/SAMPLES.md), so `./gradlew build` never downloads LWJGL |
| the generated types (`Vec3d`, `Mat4d`, the `*Bulk` classes, the `*Gpu` writers) resolve, also on a fresh checkout or after `gradlew clean` | after every Gradle sync the IDE runs `generateSources` of the four library modules (`gradle/idea.gradle.kts`, which uses JetBrains' `idea-ext` plugin; the command line never reads that script), and `build/generated/sources/vmath` is marked as generated |
| the Gradle daemon has enough memory | `org.gradle.jvmargs=-Xmx3g` in `gradle.properties` |
| formatting | `.editorconfig`: UTF-8, four spaces, 160 columns for code (the 100-column wrap is the rule for Javadoc prose) |
| run configurations | `.run/*.run.xml`, shared through git (the `.idea` folder is not); the demo configurations are written by `python vmath-samples/tools/make_run_configs.py` after a demo is added |

The first sync downloads LWJGL and the `idea-ext` plugin, once.

## Settings that live in `.idea` (local, not in git)

- **Gradle JVM** and **project SDK**: JDK 25 (`C:\Users\<you>\.jdks\openjdk-25.0.1`), see Settings > Build, Execution, Deployment > Build Tools > Gradle and File > Project Structure.
- **Build and run using: Gradle** and **Run tests using: Gradle**, in the same Gradle settings page (the default for a Gradle project). The float and double types come from a Gradle task, so the IDE's
  own compiler cannot build the project by itself; the gutter icons next to a test then run it through Gradle.

If `vmath-samples` does not show up after File > Reload All Gradle Projects, add `samples=true` to `~/.gradle/gradle.properties`, which makes every Gradle run on the machine include it.

## Run configurations

They appear in the run dropdown, grouped in folders.

| Folder | Name | What it runs |
|---|---|---|
| Demos | Demos - menu | the launcher with its menu (`:vmath-samples:run`) |
| | Demos - smoke | every demo for a few frames, checked (`:vmath-samples:smoke`) |
| | Demos - list | prints the demos |
| | Demo - city | the interactive demo (`--args="--demo city"`); every demo has three configurations |
| | Demo - city benchmark | the scripted run of 600 frames, then the timings (`--frames 600`) |
| | Demo - city (debug) | an Application configuration with the JVM flags it needs, for the debugger |
| Build and tests | Build | `build`: generate, compile, test, javadoc, API check, coverage |
| | Test - core, geo, scene, render, codegen | the tests of one module |
| | Test - seed sweep | all tests, again (`--rerun-tasks`), with `-Dvmath.seed=1 -Dvmath.trials=6000` |
| | Javadoc | `javadoc` with the doclint settings of the build |
| | API compatibility | `japicmp` against the tag `v0.1.0` |
| | Coverage | `build` and the printed coverage per package |
| | Regenerate cookbook | rewrites `docs/COOKBOOK.md` from the test (`-Dvmath.writeDocs=true`) |
| | Generate sources | the generator, for a clean checkout |
| Benchmarks | JMH - one class | `:vmath-bench:jmh` for `CoreBench` with one fork and five iterations |
| | JMH - allocation profile | the same with `-prof gc` |
| | Headless cull and draw sample | `:vmath-bench:sample`, the CPU half of a frame without a graphics driver |

IntelliJ cannot ask for the arguments of a Gradle run, so the ones that change are written into the configuration: edit the configuration (Run > Edit Configurations, the field
*Arguments*) to change the seed and the trial count of the seed sweep, the benchmark class in `-Pjmh.args="..."`, or the frame count of the sample.

## What stays on the command line

The Valhalla build (`-Pvalhalla`, JDK 28 early access; docs/PERFORMANCE.md) and the publication checks need flags and a second JDK; run them in a terminal:

```
./gradlew build -Pvalhalla "-Pvmath.buildRoot=C:/tmp/vmath-build-valhalla" "-Porg.gradle.java.installations.paths=C:/Users/<you>/.jdks/jdk-28"
./gradlew publishAllPublicationsToStagingRepository verifyPublication
```
