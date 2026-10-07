package vmath.cookbook;

import vmath.Report;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.SplittableRandom;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.FrameDirtyRanges;
import vmath.bulk.Mat4fArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Camerad;
import vmath.camera.Cameraf;
import vmath.camera.OrthoCameraf;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec3d;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.geo.Rayf;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.InstanceWriter;
import vmath.gpucull.CullObject;
import vmath.gpucull.CullObjectGpu;
import vmath.gpucull.CullView;
import vmath.gpucull.CullViewGpu;
import vmath.gpucull.GpuCullReference;
import vmath.mem.PersistentBufferRing;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;

/**
 * The code of docs/COOKBOOK.md, as tests: every recipe in the cookbook is a method here, so the examples compile and do what the text says. When a recipe changes, change
 * both. The comments marked "recipe" delimit what the cookbook shows.
 */
class CookbookTest {

    // ================================================================ 1. camera-relative rendering

    @Test
    void cameraRelativeRenderingKeepsPrecisionAtPlanetScale() {
        // recipe[camera-world]: the world is in double, the camera is a double camera, 6.4 million units from the origin
        Camerad camera = Camerad.lookingAt(new Vec3d(6_400_000.0, 10.0, 0.0), new Vec3d(6_400_000.0, 10.0, -100.0), Vec3d.UNIT_Y,
                1.0, 16.0 / 9.0, 0.1, 1000.0, DepthRange.ZERO_TO_ONE);
        Vec3d object = new Vec3d(6_400_003.25, 10.5, -50.0);

        // wrong: narrowing to float first. A float cannot hold 6 400 003.25 (its spacing there is 0.5)
        Vec3f naive = camera.toFloat().project(object.toFloat());

        // right: cancel the large coordinates in double, then narrow the small difference
        Cameraf relative = camera.cameraRelative();                      // same orientation, position at the origin
        Vec3f local = object.relativeTo(camera.position());              // subtract in double, then narrow
        Vec3f ndc = relative.project(local);
        // recipe end

        Vec3d reference = camera.project(object);                        // everything in double
        double relativeError = Math.max(Math.abs(ndc.x() - reference.x()), Math.abs(ndc.y() - reference.y()));
        double naiveError = Math.max(Math.abs(naive.x() - reference.x()), Math.abs(naive.y() - reference.y()));
        Report.printf("camera-relative recipe: error %.2e NDC units, narrowing first %.2e%n", relativeError, naiveError);
        assertTrue(relativeError < 1e-5, "camera-relative error " + relativeError);
        assertTrue(naiveError > 100 * relativeError, "narrowing first is far worse: " + naiveError + " against " + relativeError);

        // recipe[camera-instances]: many objects: subtract the camera in double while writing the instance buffer
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
        // recipe end
        // the buffer holds the offsets from the camera, exact to float precision of the *offset*
        float x = instances.get(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) 7 * InstanceWriter.STRIDE + 12);
        assertTrue(Math.abs(x - (worldX[7] - eye.x())) < 1e-5 * 100, "offset " + x);
        arena.close();
    }

    // ================================================================ 2. culling a million instances

    @Test
    void cullingManyInstancesWithTheBulkPipeline() {
        int count = 100_000;                                             // the sample in vmath-bench does 1 000 000 the same way
        SplittableRandom rnd = new SplittableRandom(7);
        // recipe[cull]: bounds live in structure-of-arrays form, one box per instance, no object per instance
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
        // recipe end

        // the check: the same answer as testing each box on its own, and conservative
        int expected = 0;
        for (int i = 0; i < count; i++) {
            boolean inside = frustum.intersects(new Aabbf(bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i)));
            assertEquals(inside, visible.get(i), "box " + i);
            expected += inside ? 1 : 0;
        }
        assertEquals(expected, survivors);
        assertTrue(survivors > 100 && survivors < count / 2, "a plausible fraction is visible: " + survivors);
        assertEquals(survivors, visible.count());
    }

    // ================================================================ 3. a GPU-driven frame, CPU side

    @Test
    void aGpuDrivenFrameWithTheCpuReferenceAndAPersistentUploadRing() {
        int objectCount = 5_000, frames = 3;
        SplittableRandom rnd = new SplittableRandom(11);
        try (Arena arena = Arena.ofConfined()) {
            // recipe[gpu-cull]: 1. the object table, in the layout the culling shader reads (std430)
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
            // recipe end
            int expected = 0;
            for (int i = 0; i < objectCount; i++) {
                expected += frustum.intersects(new Aabbf(cx[i] - 1, cy[i] - 1, cz[i] - 1, cx[i] + 1, cy[i] + 1, cz[i] + 1)) ? 1 : 0;
            }
            assertEquals(expected, counters.drawn, "the culling pass draws what a per-box frustum test accepts");
            assertEquals(expected, draw.instanceCount(0));
            assertTrue(expected > 50 && expected < objectCount);

            // recipe[gpu-upload]: transforms reach the GPU through a persistently mapped ring, uploading only what changed, once per frame in flight
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
                // recipe skip
                gpuFrame[0] = Math.max(gpuFrame[0], frame);           // the simulated GPU finishes a frame behind
                for (int i = 0; i < objectCount; i += 97) {
                    assertEquals(world.data()[i * 16 + 12], mapped.get(ValueLayout.JAVA_FLOAT_UNALIGNED, at + i * 64L + 48), "frame " + frame + " object " + i);
                }
                assertEquals(region, at, "the region starts at the first allocation");
                // recipe resume
            }
            // recipe end
            assertEquals(0, ring.stalls(), "a GPU one frame behind never makes a 3-frame ring wait");
            long everything = 12L * objectCount * 64;
            Report.printf("GPU-driven recipe: uploaded %d of %d bytes (%.1f%%)%n", bytesUploaded, everything, 100.0 * bytesUploaded / everything);
            assertTrue(bytesUploaded < everything / 3, "only the changed matrices after the first frames: " + bytesUploaded);
            ring.drain();
        }
    }

    // ================================================================ 4. migrating from JOML

    private static void assertClose(float expected, float actual, String what) {
        assertEquals(expected, actual, 1e-4f * Math.max(1f, Math.abs(expected)), what);
    }

    @Test
    void migratingFromJoml() {
        // recipe[joml]: the same operations, side by side (the assertions compare the two libraries' results)
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
        // recipe end
    }

    // ================================================================ 5. orthographic views

    @Test
    void anOrthographicViewHasAPixelExactSizeAndAPickRayThatMoves() {
        int width = 1280, height = 720;
        // recipe[ortho-pixels]: a window in which one world unit is one pixel, the world origin at the bottom left (2D, user interface, CAD)
        OrthoCameraf camera = OrthoCameraf.forViewport(new Vec3f(width / 2f, height / 2f, 10f), width, height, 1f, 0.1f, 100f, DepthRange.of(ClipSpace.OPENGL));
        Mat4f viewProjection = camera.viewProjection();                          // world to clip space: what the shader gets
        Vec3f pixel = camera.toScreen(new Vec3f(100f, 200f, 0f), width, height); // x 100 from the left, y 520 from the top
        float unitsPerPixel = camera.pixelSize(height);                          // 1: widths and dash lengths in pixels are the same numbers in world units
        OrthoCameraf zoomed = camera.zoomed(2f);                                 // about the same middle: a world unit is now two pixels
        // recipe end
        assertEquals(100f, pixel.x(), 1e-3f);
        assertEquals(520f, pixel.y(), 1e-3f);
        assertEquals(1f, unitsPerPixel, 1e-6f);
        assertEquals(0.5f, zoomed.pixelSize(height), 1e-6f);
        assertEquals(1f, viewProjection.transformProject(new Vec3f(width, height, 0f)).y(), 1e-4f, "the top right corner of the window is at NDC (1, 1)");

        float mouseX = 400f, mouseY = 300f;
        // recipe[ortho-pick]: picking: every ray has the direction of the view and an origin that follows the pixel (the perspective ray is the other way round)
        Rayf ray = camera.pickRay(mouseX + 0.5f, mouseY + 0.5f, width, height);
        float t = -ray.origin().z() / ray.direction().z();                       // where it meets the plane z = 0 of a 2D scene
        Vec3f onPlane = ray.origin().add(ray.direction().mul(t));
        // recipe end
        assertEquals(400.5f, onPlane.x(), 1e-3f, "the world x under the cursor is the pixel x");
        assertEquals(720f - 300.5f, onPlane.y(), 1e-3f, "the world y counts from the bottom");
        assertEquals(0f, onPlane.z(), 1e-4f);
    }
}
