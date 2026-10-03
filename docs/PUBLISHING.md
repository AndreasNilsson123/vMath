# Publishing

Three modules are published: `vmath` (the library), `vmath-simd` (optional Vector API kernels, needs `--add-modules jdk.incubator.vector`) and `vmath-annotations` (the
`@Experimental` marker; optional for consumers, add it as `compileOnly` to silence javac's "cannot find annotation method" notes). Each is a jar with a module descriptor, a
`-sources.jar`, a `-javadoc.jar`, a POM and Gradle module metadata, and the licence inside every jar (`META-INF/LICENSE`). The code-generation tool and the benchmarks are not published.
The setup is in `gradle/publishing.gradle.kts`.

## Check what would be published, without publishing anything

```bash
./gradlew publishAllPublicationsToStagingRepository verifyPublication
```

The artifacts are `vmath-annotations`, `vmath-core`, `vmath-geo`, `vmath-scene`, `vmath-render`, `vmath-simd` and the aggregate `vmath`, whose jar holds only the module descriptor and whose POM depends on the four parts (so `io.github.andreasnilsson123:vmath` still gives the whole library, and `vmath-core` or `vmath-geo` alone give less). The code generator, the validator and the benchmarks are not published.

This writes `build/staging-repo/` (a Maven repository layout) and checks, per module, that the jar, sources jar, javadoc jar, POM and module metadata exist, that the POM carries the
fields Maven Central requires (name, description, url, licence, developer, SCM), that each jar contains the licence, that the library jar has its `module-info.class`, and that the
javadoc jar has documentation. Nothing leaves the machine.

## GitHub Packages

```bash
export GITHUB_ACTOR=<your github user>
export GITHUB_TOKEN=<a token with write:packages>
./gradlew publishAllPublicationsToGitHubPackagesRepository
```

The repository is `https://maven.pkg.github.com/AndreasNilsson123/vMath`. Consumers need a token with `read:packages` to resolve from it. Publishing is an outward-facing,
hard-to-undo step: do it from a release commit (below), not from a working tree.

## Maven Central

Three things are needed that the build cannot do for you:

1. **A verified namespace.** The coordinates are `io.github.andreasnilsson123:vmath` (`group` in `build.gradle.kts`; the JPMS module is still named `vmath`). Central only accepts a group whose namespace you have verified in the Central Portal
   (for an `io.github.<user>` group the portal asks you to create a public repository with a name it gives you); do that before the first upload. The group is part of every consumer's dependency, so settle it before the first release, not after.
2. **Signing.** Set `SIGNING_KEY` (an ASCII-armoured private key) and `SIGNING_PASSWORD` in the environment and the publications are signed with it (the `signing` plugin is applied only then).
3. **Upload.** Central takes the staged repository as a bundle through the Central Portal (or a publishing plugin of your choice); the staging output above is the content to bundle. No Central
   upload is configured in this build.

## Releasing

Follow the release checklist in `docs/VERSIONING.md` (changelog, drop `-SNAPSHOT`, both builds green, tag, move the compatibility baseline), then run the staging check above on the release commit
and publish from there.

## What is deliberately not published

- The `-Pvalhalla` build. Its classes use preview features of a JDK 28 early-access build, so they only load on a JVM started with `--enable-preview`; the publish tasks are disabled in that mode.
  A Valhalla artifact would need its own coordinates or classifier once value classes are final.
- Snapshots from a dirty tree: the version is `0.2.0-SNAPSHOT` until a release (`v0.1.0` is already tagged, so the next release is 0.2.0).
