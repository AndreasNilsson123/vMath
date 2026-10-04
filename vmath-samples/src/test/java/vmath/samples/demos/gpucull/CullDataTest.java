package vmath.samples.demos.gpucull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.gl.DrawCommandBuffer;
import vmath.gpucull.CullObjectGpu;
import vmath.gpucull.CullViewGpu;
import vmath.gpucull.GpuCullReference;
import vmath.gpucull.HiZPyramid;
import vmath.occlusion.HiZ;
import vmath.samples.framework.Scenes;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernels;

/**
 * Tests of the data of the GPU culling demo and of the library's CPU model of the pass on it: the
 * records have the right layout, the pass with an empty pyramid keeps what the frustum keeps, and
 * a pyramid built from a depth image that a wall covers removes what is behind the wall.
 *
 * <p><b>Thread safety.</b> Each test builds its own data; the tests may run in parallel.
 */
class CullDataTest {

    private static Cameraf camera(float z) {
        return Cameraf.lookingAt(new Vec3f(0f, 3f, z), new Vec3f(0f, 3f, z - 50f), Vec3f.UNIT_Y, 1.0f, 16f / 9f, 0.3f, 800f, DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    private static int[] runReference(Arena arena, BoundsArray bounds, Cameraf cam, HiZPyramid pyramid, float nearW, VisibilitySet out) {
        MemorySegment objects = CullData.writeObjects(arena, bounds);
        MemorySegment view = arena.allocate(CullViewGpu.SIZE, 16);
        int w = pyramid == null ? 1 : pyramid.width(0), h = pyramid == null ? 1 : pyramid.height(0), levels = pyramid == null ? 1 : pyramid.levels();
        CullData.writeView(view, cam, cam.viewProjection(), bounds.size(), w, h, levels, nearW);
        MemorySegment commandSegment = arena.allocate(DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false), 16);
        DrawCommandBuffer commands = new DrawCommandBuffer(commandSegment, DrawCommandBuffer.Kind.ELEMENTS, false);
        commands.addElements(36, 0, 0, 0, 0);
        MemorySegment visible = arena.allocate(4L * bounds.size(), 16);
        GpuCullReference.Counters counters = new GpuCullReference.Counters();
        counters.reset();
        GpuCullReference.cullSinglePass(view, objects, pyramid, commands, new int[] {bounds.size()}, visible, counters);
        int n = commands.instanceCount(0);
        for (int i = 0; i < n; i++) {
            out.set(visible.get(ValueLayout.JAVA_INT, 4L * i));
        }
        return new int[] {n, counters.inFrustum, counters.occluded};
    }

    @Test
    void theObjectsAreWrittenOneRecordEachAndTheGroundSkipsTheDepthTest() {
        Scenes.Blocks city = Scenes.blocks(6, 5);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = CullData.writeObjects(arena, city.bounds());
            int n = city.bounds().size();
            assertEquals(n * CullObjectGpu.SIZE, seg.byteSize());
            assertEquals(city.bounds().minX(3), seg.get(ValueLayout.JAVA_FLOAT, 3 * CullObjectGpu.SIZE + CullObjectGpu.OFFSET_MIN));
            assertEquals(city.bounds().maxY(3), seg.get(ValueLayout.JAVA_FLOAT, 3 * CullObjectGpu.SIZE + CullObjectGpu.OFFSET_MAX + 4));
            assertEquals(0, seg.get(ValueLayout.JAVA_INT, 3 * CullObjectGpu.SIZE + CullObjectGpu.OFFSET_FLAGS));
            assertEquals(GpuCullReference.OBJECT_NO_OCCLUSION, seg.get(ValueLayout.JAVA_INT, (n - 1) * CullObjectGpu.SIZE + CullObjectGpu.OFFSET_FLAGS));
        }
    }

    @Test
    void withNothingToOccludeThePassKeepsWhatTheFrustumKernelKeeps() {
        BoundsArray bounds = Scenes.blocks(12, 20).bounds();
        Cameraf cam = camera(60f);
        VisibilitySet expected = new VisibilitySet(bounds.size()), actual = new VisibilitySet(bounds.size());
        CullPipeline.of(new CullStages.Frustum(FrustumKernels.scalar())).run(CullContext.perspective(cam.frustum(), cam.position(), cam.fovy(), 900), bounds, expected);
        try (Arena arena = Arena.ofConfined()) {
            int[] counts = runReference(arena, bounds, cam, null, 0.3f, actual);
            assertEquals(expected.count(), counts[0]);
            assertEquals(0, counts[2]);
            for (int i = expected.nextSetBit(0); i >= 0 && i < bounds.size(); i = expected.nextSetBit(i + 1)) {
                assertTrue(actual.get(i), "box " + i);
            }
        }
    }

    @Test
    void aWallInTheDepthImageHidesWhatIsBehindItAndAHugeNearWSwitchesTheTestOff() {
        BoundsArray bounds = new BoundsArray(8);
        bounds.add(-0.5f, 0f, -20.5f, 0.5f, 1f, -19.5f); // behind the wall
        bounds.add(-0.5f, 0f, -5.5f, 0.5f, 1f, -4.5f);   // in front of the wall
        bounds.add(-100f, -1f, -100f, 100f, 0f, 100f);   // the ground
        Cameraf cam = Cameraf.lookingAt(new Vec3f(0f, 0.5f, 0f), new Vec3f(0f, 0.5f, -50f), Vec3f.UNIT_Y, 1.0f, 2f, 0.3f, 800f, DepthRange.NEGATIVE_ONE_TO_ONE);
        int w = 64, h = 32;
        float[] depth = new float[w * h];
        // a wall at 10 m that covers the middle of the screen: its normalised device depth
        float ndc = cam.viewProjection().transformProject(new Vec3f(0f, 0.5f, -10f)).z();
        java.util.Arrays.fill(depth, 1f);
        for (int y = 4; y < 28; y++) {
            for (int x = 20; x < 44; x++) {
                depth[y * w + x] = ndc;
            }
        }
        HiZPyramid pyramid = HiZPyramid.fromDepth(depth, w, h, DepthRange.NEGATIVE_ONE_TO_ONE, false);
        assertEquals(HiZ.mipCount(w, h), pyramid.levels());
        try (Arena arena = Arena.ofConfined()) {
            VisibilitySet seen = new VisibilitySet(8);
            int[] counts = runReference(arena, bounds, cam, pyramid, 0.3f, seen);
            assertTrue(!seen.get(0), "the box behind the wall is removed");
            assertTrue(seen.get(1), "the box in front of it stays");
            assertTrue(seen.get(2), "the ground is flagged and stays");
            assertEquals(1, counts[2]);
            VisibilitySet off = new VisibilitySet(8);
            runReference(arena, bounds, cam, pyramid, 1e30f, off);
            assertTrue(off.get(0), "with the depth test switched off the box behind the wall is kept");
        }
    }
}
