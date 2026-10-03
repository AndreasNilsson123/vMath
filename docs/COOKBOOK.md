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

```java
// the world is in double, the camera is a double camera, 6.4 million units from the origin
Camerad camera = Camerad.lookingAt(new Vec3d(6_400_000.0, 10.0, 0.0), new Vec3d(6_400_000.0, 10.0, -100.0), Vec3d.UNIT_Y,
        1.0, 16.0 / 9.0, 0.1, 1000.0, DepthRange.ZERO_TO_ONE);
Vec3d object = new Vec3d(6_400_003.25, 10.5, -50.0);

// wrong: narrowing to float first. A float cannot hold 6 400 003.25 (its spacing there is 0.5)
Vec3f naive = camera.toFloat().project(object.toFloat());

// right: cancel the large coordinates in double, then narrow the small difference
Cameraf relative = camera.cameraRelative();                      // same orientation, position at the origin
Vec3f local = object.relativeTo(camera.position());              // subtract in double, then narrow
Vec3f ndc = relative.project(local);
```

The test measured the result: the camera-relative projection is off by 5.3e-9 NDC units, narrowing to float first by 3.1e-3, about 580,000 times more (about three pixels on a 1920-wide screen).

For many objects, subtract while writing the per-instance data, and upload the offsets, not the positions. Rebuild the buffer every frame (the camera moves), which is cheap next to the cull:

```java
// many objects: subtract the camera in double while writing the instance buffer
int n = 1000;
double[] worldX = new double[n], worldY = new double[n], worldZ = new double[n];
SplittableRandom r = new SplittableRandom(1);
for (int i = 0; i < n; i++) {
    worldX[i] = 6_400_000.0 + r.nextDouble() * 200 - 100;
    worldY[i] = r.nextDouble() * 20;
    worldZ[i] = -r.nextDouble() * 200;
}
Vec3d eye = camera.position();
Arena arena = Arena.ofConfined();
MemorySegment instances = arena.allocate((long) n * InstanceWriter.STRIDE, 16);
for (int i = 0; i < n; i++) {
    InstanceWriter.writeTranslation(instances, i, (float) (worldX[i] - eye.x()), (float) (worldY[i] - eye.y()), (float) (worldZ[i] - eye.z()), i);
}
```

**Notes.** The view matrix on the GPU is then rotation only; there is no translation to lose precision in. For shadow maps and other secondary cameras use the same camera position as the origin
for everything in the frame, so all of it stays consistent. `docs/CAMERA.md` has the camera API.

## 2. Culling a million instances

**Problem.** Deciding per frame which of a million objects might be visible, with no allocation and in a couple of milliseconds.

**Recipe.** Keep the object bounds in structure-of-arrays form (`BoundsArray`), run a `CullPipeline` of stages that each only clear bits in a `VisibilitySet`, and read the survivors as indices
or write them straight to the instance buffer:

```java
// bounds live in structure-of-arrays form, one box per instance, no object per instance
BoundsArray bounds = new BoundsArray(count);
for (int i = 0; i < count; i++) {
    float cx = (float) (rnd.nextDouble() * 2 - 1) * 500f, cy = (float) (rnd.nextDouble() * 2 - 1) * 500f, cz = (float) (rnd.nextDouble() * 2 - 1) * 500f;
    float h = 0.2f + (float) rnd.nextDouble() * 2f;
    bounds.add(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
}

// once per frame: the frustum from the camera's matrices, the stages, the result set
Mat4f view = Mat4f.lookAt(Vec3f.ZERO, new Vec3f(0.3f, 0.1f, -1f), Vec3f.UNIT_Y);
Mat4f projection = Mat4f.perspective(1.0f, 16f / 9f, 0.1f, 750f, ClipSpace.VULKAN);
Frustumf frustum = Frustumf.fromViewProjection(projection.mul(view), DepthRange.of(ClipSpace.VULKAN));
CullContext context = CullContext.perspective(frustum, Vec3f.ZERO, 1.0f, 1080);
CullPipeline pipeline = CullPipeline.of(new CullStages.Frustum());    // add stages (distance, small feature, occlusion) in the order they should run
VisibilitySet visible = new VisibilitySet(count);
visible.setAll(count);                                           // everything is a candidate until a stage clears it
int survivors = pipeline.run(context, bounds, visible);

// the survivors go straight into the instance buffer (see recipe 3) or into an index list
int[] indices = new int[survivors];
visible.toIndices(indices);
```

The check in the test compares every box with a test of that box alone, so the bulk result is the per-box result, and the stage is conservative: a box that touches the frustum is never dropped.

**What it costs** (measured on one machine, JDK 25, 1M boxes with about 95,000 visible; from `docs/GPU.md`): the frustum stage takes about 2.0 ms on one thread, 0.73 ms split over four, 0.85 ms through
a static BVH, and the whole frame including writing the 95,000 instances takes 3.4, 2.3 and 2.9 ms. Nothing is allocated per frame in the serial case. `FrustumKernels.best()` uses the SIMD kernel when
`vmath-simd` and `--add-modules jdk.incubator.vector` are present. Add stages in the order they should run: distance, small feature, occlusion (`docs/CULLING.md`).

**Moving objects.** Rebuild the world bounds each frame from local bounds and matrices with `BoundsArray.transformFrom(local, matrices)`; use `StaticBvh.refit` or a `DynamicAabbTree` when most objects stand still.

## 3. A GPU-driven frame

**Problem.** Let the GPU decide what to draw: the CPU uploads object data once and changed transforms each frame, a compute pass culls and writes the indirect draw commands, and one multi-draw renders.

**Recipe, part one: the data and the culling pass.** The layouts are generated from Java records, so the CPU writer and the shader declaration cannot disagree (`GpuCullGlsl` and `ShaderHeader` print the
shader side). `GpuCullReference` is the CPU twin of the compute shader and the oracle it is tested against; on a GPU the same step is a dispatch:

```java
// 1. the object table, in the layout the culling shader reads (std430)
MemorySegment objects = arena.allocate(objectCount * CullObjectGpu.SIZE, 16);
float[] cx = new float[objectCount], cy = new float[objectCount], cz = new float[objectCount];
for (int i = 0; i < objectCount; i++) {
    cx[i] = (float) (rnd.nextDouble() * 200 - 100);
    cy[i] = (float) (rnd.nextDouble() * 40 - 20);
    cz[i] = (float) (-rnd.nextDouble() * 300);
    CullObjectGpu.write(new CullObject(new Vec3f(cx[i] - 1, cy[i] - 1, cz[i] - 1), 0, new Vec3f(cx[i] + 1, cy[i] + 1, cz[i] + 1), 0),
            objects, i * CullObjectGpu.SIZE);
}

// 2. the per-frame view block (std140): the six frustum planes and the matrices
Mat4f projection = Mat4f.perspective(1.0f, 16f / 9f, 0.1f, 400f, ClipSpace.VULKAN);
Mat4f view = Mat4f.IDENTITY;
Mat4f viewProjection = projection.mul(view);
Frustumf frustum = Frustumf.fromViewProjection(viewProjection, DepthRange.ZERO_TO_ONE);
Vec4f[] planes = new Vec4f[6];
for (int i = 0; i < 6; i++) {
    planes[i] = new Vec4f(frustum.plane(i).nx(), frustum.plane(i).ny(), frustum.plane(i).nz(), frustum.plane(i).d());
}
MemorySegment viewBlock = arena.allocate(CullViewGpu.SIZE, 16);
CullViewGpu.write(new CullView(planes, viewProjection, objectCount, 1, 1, 1, 1e-4f, 1, 0), viewBlock, 0);   // depthMode 1: 0..1, no pyramid yet

// 3. one indirect draw: the culling pass appends instances to it (on the GPU this is a compute shader; this is its CPU reference)
MemorySegment commandMemory = arena.allocate(DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false), 16);
DrawCommandBuffer draw = new DrawCommandBuffer(commandMemory, DrawCommandBuffer.Kind.ELEMENTS, false);
draw.addElements(36, 0, 0, 0, 0);                             // 36 indices, instance count starts at 0 and the pass counts it up
MemorySegment visibleIds = arena.allocate(4L * objectCount, 16);
GpuCullReference.Counters counters = new GpuCullReference.Counters();
GpuCullReference.cullSinglePass(viewBlock, objects, null, draw, new int[] {objectCount}, visibleIds, counters);
```

The test checks that the pass draws exactly the objects a per-box frustum test accepts and that the instance count in the command matches. Add a `HiZPyramid` (or the depth pyramid of your renderer in the same
layout) and `cullPhase1`/`cullPhase2` for the two-phase occlusion scheme described in `docs/GPU.md`.

**Recipe, part two: feeding the transforms.** A persistently mapped buffer is split into one region per frame in flight (`PersistentBufferRing`); a fence per region says when the GPU has finished reading
it. `FrameDirtyRanges` remembers, per region, which matrices changed since *that* region was written, so each frame copies only those:

```java
// transforms reach the GPU through a persistently mapped ring, uploading only what changed, once per frame in flight
Mat4fArray world = new Mat4fArray(objectCount);
for (int i = 0; i < objectCount; i++) {
    world.add(Mat4f.translation(cx[i], cy[i], cz[i]));
}
MemorySegment mapped = arena.allocate((long) frames * objectCount * 64, 256);          // stands in for the mapped buffer
int[] gpuFrame = {0};                                                                    // stands in for the GPU's progress
PersistentBufferRing<Integer> ring = new PersistentBufferRing<>(mapped, frames, 256, new PersistentBufferRing.FenceOps<>() {
    int submitted;

    @Override
    public Integer insert() {
        return ++submitted;                 // glFenceSync / vkQueueSubmit with a fence
    }

    @Override
    public boolean isSignaled(Integer fence) {
        return fence <= gpuFrame[0];
    }

    @Override
    public void await(Integer fence) {
        gpuFrame[0] = Math.max(gpuFrame[0], fence);       // glClientWaitSync / vkWaitForFences
    }

    @Override
    public void release(Integer fence) {
        // glDeleteSync / vkDestroyFence
    }
});
FrameDirtyRanges dirty = new FrameDirtyRanges(objectCount, frames);
dirty.markAll();                                          // the first upload writes everything
long bytesUploaded = 0;
for (int frame = 0; frame < 12; frame++) {
    long region = ring.beginFrame();                      // waits only if the GPU still reads this region
    if (frame > 0) {
        for (int k = 0; k < 20; k++) {                    // move a few objects
            int i = rnd.nextInt(objectCount);
            world.set(i, Mat4f.translation(cx[i] + frame, cy[i], cz[i]));
            dirty.mark(i);
        }
    }
    long at = ring.allocate((long) objectCount * 64, 256);
    bytesUploaded += dirty.forSlot((int) (ring.frameIndex() % frames)).uploadFloats(world.data(), Mat4fArray.STRIDE, objectCount, mapped, at, 16);
    ring.endFrame();                                      // right after submitting the frame's commands
}
```

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

```java
// the same operations, side by side (the assertions compare the two libraries' results)
// JOML mutates the receiver; vmath returns a new value. a.add(b) in JOML changes a; here it does not.
Vector3f ja = new Vector3f(1f, 2f, 3f), jb = new Vector3f(-4f, 5f, 0.5f);
Vec3f va = new Vec3f(1f, 2f, 3f), vb = new Vec3f(-4f, 5f, 0.5f);

Vector3f jsum = new Vector3f(ja).add(jb);                       // JOML: copy first if you need the original
Vec3f vsum = va.add(vb);                                        // vmath: va is unchanged
assertClose(jsum.x, vsum.x(), "add x");
assertEquals(1f, va.x(), "the receiver is not modified");

assertClose(ja.dot(jb), va.dot(vb), "dot");
Vector3f jcross = new Vector3f(ja).cross(jb);
Vec3f vcross = va.cross(vb);
assertClose(jcross.y, vcross.y(), "cross");
assertClose(new Vector3f(ja).normalize().z, va.normalize().z(), "normalize");
assertClose(ja.length(), va.length(), "length");
assertClose(ja.distance(jb), va.distance(vb), "distance");
assertClose(new Vector3f(ja).lerp(jb, 0.25f).x, va.lerp(vb, 0.25f).x(), "lerp");
assertClose(new Vector3f(ja).mul(2.5f).y, va.mul(2.5f).y(), "scale by a number");

// matrices: JOML's builder style ("new Matrix4f().perspective(...)") becomes static factories; both are column-major, right-handed, y up
Matrix4f jview = new Matrix4f().lookAt(new Vector3f(3f, 2f, 8f), new Vector3f(0f, 1f, 0f), new Vector3f(0f, 1f, 0f));
Mat4f vview = Mat4f.lookAt(new Vec3f(3f, 2f, 8f), new Vec3f(0f, 1f, 0f), new Vec3f(0f, 1f, 0f));
Matrix4f jproj = new Matrix4f().perspective(1.0f, 16f / 9f, 0.1f, 200f);          // JOML's default is the OpenGL depth range -1..1
Mat4f vproj = Mat4f.perspective(1.0f, 16f / 9f, 0.1f, 200f, ClipSpace.OPENGL);    // vmath names the convention; D3D and VULKAN are 0..1
Matrix4f jvp = new Matrix4f(jproj).mul(jview);                                    // JOML: this = this * right, in place
Mat4f vvp = vproj.mul(vview);                                                     // vmath: a new matrix
float[] jarray = new float[16], varray = new float[16];
jvp.get(jarray);
vvp.writeTo(varray, 0);
for (int i = 0; i < 16; i++) {
    assertClose(jarray[i], varray[i], "view-projection element " + i);
}
assertClose(new Matrix4f(jvp).invert().m30(), vvp.invert().m30(), "invert");

// transforming a point: JOML writes into its argument, vmath returns the result
Vector3f jp = new Vector3f(1f, 2f, 3f);
jview.transformPosition(jp);
Vec3f vp = vview.transformPosition(new Vec3f(1f, 2f, 3f));
assertClose(jp.x, vp.x(), "transformPosition x");
assertClose(jp.z, vp.z(), "transformPosition z");

// JOML's translate/rotate append to the matrix (this = this * T); vmath builds the pieces and multiplies them yourself, in the same order
Matrix4f jmodel = new Matrix4f().translate(1f, 2f, 3f).rotateY(0.7f).scale(2f);
Mat4f vmodel = Mat4f.translation(1f, 2f, 3f).mul(Mat4f.rotationY(0.7f)).mul(Mat4f.scaling(2f, 2f, 2f));
jmodel.get(jarray);
vmodel.writeTo(varray, 0);
for (int i = 0; i < 16; i++) {
    assertClose(jarray[i], varray[i], "model matrix element " + i);
}

// quaternions: JOML's rotateAxis becomes Quatf.fromAxisAngle; transform(Vector3f) rotates a vector in both
Quaternionf jq = new Quaternionf().rotateAxis(0.9f, 0f, 1f, 0f).mul(new Quaternionf().rotateAxis(0.4f, 1f, 0f, 0f));
Quatf vq = Quatf.fromAxisAngle(0.9f, new Vec3f(0f, 1f, 0f)).mul(Quatf.fromAxisAngle(0.4f, new Vec3f(1f, 0f, 0f)));
assertClose(jq.x, vq.x(), "quaternion x");
assertClose(jq.w, vq.w(), "quaternion w");
Vector3f jrot = jq.transform(new Vector3f(1f, 2f, 3f));
Vec3f vrot = vq.transform(new Vec3f(1f, 2f, 3f));
assertClose(jrot.y, vrot.y(), "rotated vector");
Quaternionf jslerp = new Quaternionf(jq).slerp(new Quaternionf(), 0.3f);
Quatf vslerp = vq.slerp(Quatf.IDENTITY, 0.3f);
assertClose(jslerp.z, vslerp.z(), "slerp");
```

**Habits to change.** Because values are immutable, a loop that updates a position must assign the result (`p = p.add(v)`); the allocation that suggests is sometimes removed by escape analysis in hot scalar
code but not reliably, and for per-element work over thousands of objects the bulk containers are the allocation-free way (`docs/PERFORMANCE.md` has the measurements). JOML's `equals` is exact and so is vmath's, but
vmath's treats `-0.0` and `0.0` as different and NaN as equal to itself (`docs/EQUALITY.md`); use `approxEquals(other, eps)` for tolerant comparison.

What there is no equivalent for: JOML's `Matrix4f.set...` mutators, `Matrix4f.mulAffine`-style in-place fast paths (use `Mat4x3f` for affine data), and its `Matrix4fStack`. The double types have
the same API under the `d` names, and a float or double type converts with `toDouble()` / `toFloat()`.
