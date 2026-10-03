# Cookbook

Four recipes, each short enough to read in a few minutes and each **compiled and run by the test suite**: the code blocks below are cut out of
`vmath-render/src/test/java/vmath/cookbook/CookbookTest.java` by `CookbookDocTest`, which fails when this file and that one disagree. To change a recipe, edit the test and regenerate this
file with `./gradlew :vmath-render:test --tests "vmath.cookbook.CookbookDocTest" -Dvmath.writeDocs=true` (edit `docs/COOKBOOK.template.md` for the prose).

The recipes use the experimental layers (`vmath.gpucull`, `vmath.mem`) and the stable ones side by side; what the shaders do is the part this library does not run (see `docs/GPU.md`).

## 1. Camera-relative rendering

**Problem.** Far from the origin a `float` runs out of digits. At 6.4 million units from the origin its spacing is 0.5, so projecting float world coordinates visibly jitters. Keeping
the world in `double` is not enough on its own: the GPU works in float, so what you hand it must already be small.

**Recipe.** Keep world positions and the camera in double, subtract the camera position *in double*, and only then narrow to float. `Camerad.cameraRelative()` is the same camera with its position
at the origin, so it pairs with the small offsets:

{{recipe:camera-world}}

The test measured the result: the camera-relative projection is off by 5.3e-9 NDC units, narrowing to float first by 3.1e-3, about 580,000 times more (about three pixels on a 1920-wide screen).

For many objects, subtract while writing the per-instance data, and upload the offsets, not the positions. Rebuild the buffer every frame (the camera moves), which is cheap next to the cull:

{{recipe:camera-instances}}

**Notes.** The view matrix on the GPU is then rotation only; there is no translation to lose precision in. For shadow maps and other secondary cameras use the same camera position as the origin
for everything in the frame, so all of it stays consistent. `docs/CAMERA.md` has the camera API.

## 2. Culling a million instances

**Problem.** Deciding per frame which of a million objects might be visible, with no allocation and in a couple of milliseconds.

**Recipe.** Keep the object bounds in structure-of-arrays form (`BoundsArray`), run a `CullPipeline` of stages that each only clear bits in a `VisibilitySet`, and read the survivors as indices
or write them straight to the instance buffer:

{{recipe:cull}}

The check in the test compares every box with a test of that box alone, so the bulk result is the per-box result, and the stage is conservative: a box that touches the frustum is never dropped.

**What it costs** (measured on one machine, JDK 25, 1M boxes with about 95,000 visible; from `docs/GPU.md`): the frustum stage takes about 2.0 ms on one thread, 0.73 ms split over four, 0.85 ms through
a static BVH, and the whole frame including writing the 95,000 instances takes 3.4, 2.3 and 2.9 ms. Nothing is allocated per frame in the serial case. `FrustumKernels.best()` uses the SIMD kernel when
`vmath-simd` and `--add-modules jdk.incubator.vector` are present. Add stages in the order they should run: distance, small feature, occlusion (`docs/CULLING.md`).

**Moving objects.** Rebuild the world bounds each frame from local bounds and matrices with `BoundsArray.transformFrom(local, matrices)`; use `StaticBvh.refit` or a `DynamicAabbTree` when most objects stand still.

## 3. A GPU-driven frame

**Problem.** Let the GPU decide what to draw: the CPU uploads object data once and changed transforms each frame, a compute pass culls and writes the indirect draw commands, and one multi-draw renders.

**Recipe, part one: the data and the culling pass.** The layouts are generated from Java records, so the CPU writer and the shader declaration cannot disagree (`GpuCullGlsl` and `ShaderHeader` print the
shader side). `GpuCullReference` is the CPU twin of the compute shader and the oracle it is tested against; on a GPU the same step is a dispatch:

{{recipe:gpu-cull}}

The test checks that the pass draws exactly the objects a per-box frustum test accepts and that the instance count in the command matches. Add a `HiZPyramid` (or the depth pyramid of your renderer in the same
layout) and `cullPhase1`/`cullPhase2` for the two-phase occlusion scheme described in `docs/GPU.md`.

**Recipe, part two: feeding the transforms.** A persistently mapped buffer is split into one region per frame in flight (`PersistentBufferRing`); a fence per region says when the GPU has finished reading
it. `FrameDirtyRanges` remembers, per region, which matrices changed since *that* region was written, so each frame copies only those:

{{recipe:gpu-upload}}

In the test, 20 of 5,000 matrices move per frame, and after the first three frames (which write everything into each region) the uploads are small: over 12 frames it copied 27.3% of the bytes that
uploading everything every frame would have. The fence hooks are four methods (`insert`, `isSignaled`, `await`, `release`); the comments show what each is in OpenGL and Vulkan.

**Not covered here, and not tested anywhere in this repository:** compiling the compute shader text, binding the buffers, and running any of this on a GPU. The Java references and the layout validator
(`LayoutValidator`, fed with your driver's reflection data) are how you check the shader once you have one.

## 4. Migrating from JOML

The types are close cousins: right-handed, column-major, y up, the same names for most operations. The differences that matter:

| JOML | vmath |
|---|---|
| Mutable: `a.add(b)` changes `a` and returns it | Immutable records: `a.add(b)` returns a new value, `a` is unchanged. There is no `dest` argument. |
| `new Matrix4f().perspective(...)`, `.lookAt(...)`, `.translate(...)` build in place | Static factories: `Mat4f.perspective(fovy, aspect, near, far, ClipSpace)`, `Mat4f.lookAt`, `Mat4f.translation`, and `mul` to combine |
| `translate`/`rotate`/`scale` append (`this = this * T`) | Build the pieces and multiply them in the same order: `Mat4f.translation(...).mul(Mat4f.rotationY(...)).mul(Mat4f.scaling(...))` |
| `perspective` defaults to the OpenGL depth range (-1..1); `zZeroToOne` is a boolean | A `ClipSpace` argument (`OPENGL`, `VULKAN` with a flipped y, `D3D`), plus `perspectiveReversedZ` and infinite variants |
| `transformPosition(v)` writes into `v` | `transformPosition(v)` returns the result |
| `Quaternionf.rotateAxis(angle, x, y, z)` | `Quatf.fromAxisAngle(angle, axis)` and `mul` |
| `Vector3f`, `Vector3d`, `Vector3i`... | `Vec3f`, `Vec3d` (generated from one template, so the same API), `Vec3i` |
| `FrustumIntersection` | `Frustumf.fromViewProjection(vp, DepthRange)` and `intersects`/`classify` |
| One object per element | `Vec3fArray`, `Mat4fArray`, `QuatArray`, `TransformArray`, `BoundsArray`: structure-of-arrays containers with batch kernels, for anything that scales with the object count |

Side by side, with each pair of results compared in the test (so the table is a tested claim, not a memory of the two APIs):

{{recipe:joml}}

**Habits to change.** Because values are immutable, a loop that updates a position must assign the result (`p = p.add(v)`); the allocation that suggests is sometimes removed by escape analysis in hot scalar
code but not reliably, and for per-element work over thousands of objects the bulk containers are the allocation-free way (`docs/PERFORMANCE.md` has the measurements). JOML's `equals` is exact and so is vmath's, but
vmath's treats `-0.0` and `0.0` as different and NaN as equal to itself (`docs/EQUALITY.md`); use `approxEquals(other, eps)` for tolerant comparison.

What there is no equivalent for: JOML's `Matrix4f.set...` mutators, `Matrix4f.mulAffine`-style in-place fast paths (use `Mat4x3f` for affine data), and its `Matrix4fStack`. The double types have
the same API under the `d` names, and a float or double type converts with `toDouble()` / `toFloat()`.
