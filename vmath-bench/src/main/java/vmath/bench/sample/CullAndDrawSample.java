package vmath.bench.sample;

import com.sun.management.ThreadMXBean;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.util.SplittableRandom;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.InstanceWriter;
import vmath.spatial.CullContext;
import vmath.spatial.CullStages;

/**
 * The CPU half of a GPU-driven frame, headless so it runs anywhere: one million instances are frustum culled, the survivors' transforms are written
 * into an instance buffer, and one indirect draw command is emitted for them. A renderer would upload {@code instances} and {@code commands} and call
 * {@code glMultiDrawElementsIndirect} (or the Vulkan equivalent); nothing here depends on a graphics API.
 *
 * <p>Run with {@code ./gradlew :vmath-bench:sample}. The output is per-stage time and allocation averaged over the measured frames.
 */
public final class CullAndDrawSample {

    private static final int COUNT = 1_000_000;
    private static final float WORLD = 500f;
    private static final int WARMUP_FRAMES = 200;
    private static final int FRAMES = 300;
    /** Index count of the mesh every instance draws (a stand-in: a cube has 36). */
    private static final int MESH_INDEX_COUNT = 36;

    public static void main(String[] args) {
        SplittableRandom rnd = new SplittableRandom(7);
        BoundsArray bounds = new BoundsArray(COUNT);
        for (int i = 0; i < COUNT; i++) {
            float cx = (float) (rnd.nextDouble() * 2 - 1) * WORLD, cy = (float) (rnd.nextDouble() * 2 - 1) * WORLD;
            float cz = (float) (rnd.nextDouble() * 2 - 1) * WORLD, h = 0.2f + (float) rnd.nextDouble() * 2f;
            bounds.add(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
        }

        VisibilitySet visible = new VisibilitySet(COUNT);
        CullStages.Frustum frustumStage = new CullStages.Frustum();
        ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment instances = arena.allocate(COUNT * InstanceWriter.STRIDE, 16);
            MemorySegment commands = arena.allocate(DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false), 16);
            DrawCommandBuffer draw = new DrawCommandBuffer(commands, DrawCommandBuffer.Kind.ELEMENTS, false);

            long cullNs = 0, writeNs = 0, cullBytes = 0, writeBytes = 0;
            int lastVisible = 0;
            for (int frame = 0; frame < WARMUP_FRAMES + FRAMES; frame++) {
                float angle = frame * 0.01f;
                Vec3f dir = new Vec3f((float) Math.sin(angle), 0.1f, -(float) Math.cos(angle));
                Mat4f view = Mat4f.lookAt(Vec3f.ZERO, dir, Vec3f.UNIT_Y);
                Mat4f proj = Mat4f.perspective(1.0f, 16f / 9f, 0.1f, WORLD * 1.5f, ClipSpace.VULKAN);
                Frustumf frustum = Frustumf.fromViewProjection(proj.mul(view), DepthRange.of(ClipSpace.VULKAN));
                CullContext ctx = new CullContext(frustum, Vec3f.ZERO, 0f);

                long b0 = mx.getThreadAllocatedBytes(tid), t0 = System.nanoTime();
                visible.setAll(COUNT);
                frustumStage.cull(ctx, bounds, visible);
                long t1 = System.nanoTime(), b1 = mx.getThreadAllocatedBytes(tid);

                int n = InstanceWriter.writeVisibleTranslations(instances, 0, visible, bounds);
                draw.clear();
                draw.addElements(MESH_INDEX_COUNT, n, 0, 0, 0);
                long t2 = System.nanoTime(), b2 = mx.getThreadAllocatedBytes(tid);

                if (frame >= WARMUP_FRAMES) {
                    cullNs += t1 - t0;
                    writeNs += t2 - t1;
                    cullBytes += b1 - b0;
                    writeBytes += b2 - b1;
                }
                lastVisible = n;
            }
            System.out.printf("instances %,d, visible in the last frame %,d, draw commands %d (instance count %d)%n", COUNT, lastVisible, draw.count(),
                    draw.instanceCount(0));
            System.out.printf("cull            %8.1f us/frame   %8.1f B/frame%n", cullNs / 1e3 / FRAMES, (double) cullBytes / FRAMES);
            System.out.printf("write instances %8.1f us/frame   %8.1f B/frame%n", writeNs / 1e3 / FRAMES, (double) writeBytes / FRAMES);
        }
    }
}
