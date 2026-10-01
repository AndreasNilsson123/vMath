# Versioning and compatibility policy

vmath follows [Semantic Versioning](https://semver.org). What that means here, because most public types are records:

## What counts as the public API

Everything public and exported from `module-info.java`, except what is marked `@Experimental` (below). For a record that includes the component list:
**adding, removing, reordering or retyping a component breaks the canonical constructor, the accessors, `equals` and `hashCode`**, so it is a breaking change.
Add capability to an existing record with methods, or with a new type.

The generated double twins (`Vec3d` and friends) are part of the API exactly like their float originals.

## Rules by version

| Version | Breaking change allowed? | How |
|---|---|---|
| `0.y.z` (now) | Yes, in a minor release (`0.y`), never in a patch (`0.y.z`) | Deliberate, listed under "Changed" or "Removed" in `CHANGELOG.md`, and run with `-Pjapicmp.allowBreak` once so the report is read |
| `1.0.0` and later | Only in a major release | Deprecate in a minor release first (`@Deprecated(since = ..., forRemoval = true)` with the replacement in the Javadoc), remove no earlier than the next major |

Patch releases: bug fixes and performance only. Minor releases: additions, deprecations, new `@Experimental` APIs, promotions out of experimental.

Numerical behaviour counts as behaviour: a change in the result of a documented operation beyond its stated error (for example a different `slerp` tolerance,
or a different handedness for a generated tangent) is listed in the changelog under "Changed" even when the signature is the same.

## `@Experimental`

`vmath.annotations.Experimental` marks a type, method, constructor or field that is new and may change or vanish in any release, patch releases included.
It is how a large new layer (a loader, an optimiser) can ship before its shape is settled without freezing it. Rules:

- The japicmp check ignores anything carrying it. Verified by experiment (2026-10): with the current jar as baseline, renaming a method of an `@Experimental` class passes,
  while renaming a method of a stable class fails with `METHOD_REMOVED`.
- The Javadoc says what is expected to change.
- It is removed in a minor release, announced in the changelog under "Promoted"; from then on the normal rules apply.
- Nothing stable may expose an experimental type in its signature; if it would, the stable method is experimental too.

The annotation has class retention: it is in the class file for tools, absent at run time, and a consumer does not need the annotations jar.

## Compatibility checks

`./gradlew check` runs japicmp against a tagged baseline jar (see `docs/API-COMPAT.md`). Tag each release, then move the `japicmpTag` default forward.
`ApiParityTest` separately keeps the float and double twins in step.

## Changelog

`CHANGELOG.md` uses the [Keep a Changelog](https://keepachangelog.com) headings (Added, Changed, Deprecated, Removed, Fixed, plus Promoted for graduated
experimental APIs). Every user-visible change goes under "Unreleased" in the commit that makes it; a release renames that section.

## Release checklist

1. `CHANGELOG.md`: rename "Unreleased" to the version and date, start a new empty "Unreleased".
2. `version` in `build.gradle.kts`: drop `-SNAPSHOT` for the release commit.
3. `./gradlew build` and the `-Pvalhalla` build (see `docs/API-COMPAT.md`) are green.
4. Commit, then `git tag -a vX.Y.Z -m "vX.Y.Z"` and push the tag.
5. **Only now** change `japicmpTag` in `build.gradle.kts` to the new tag. Doing it before the tag exists makes the check skip silently ("no baseline"), which removes the protection without a failure.
6. Bump `version` to the next `-SNAPSHOT`.
