package vmath.bench;

import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.gl.DrawCommandBuffer;
import vmath.gpucull.CullObject;
import vmath.gpucull.CullObjectGpu;
import vmath.gpucull.CullView;
import vmath.gpucull.CullViewGpu;
import vmath.gpucull.GpuCullReference;
import vmath.gpucull.HiZPyramid;

/**
 * The CPU reference of GPU-driven object culling over a scene of boxes scattered in front of the camera (about a third in the frustum), with and without the Hi-Z test against a pyramid
 * built from a depth image of random occluder rectangles. The score is time per pass; divide by the object count for time per object. Run with {@code -prof gc}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GpuCullBench {

    @Param({"100000", "1000000"})
    public int objects;

    private MemorySegment view, objectBuffer, visible;
    private HiZPyramid hzb;
    private DrawCommandBuffer commands;
    private int[] capacity;
    private final GpuCullReference.Counters counters = new GpuCullReference.Counters();

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(3);
        objectBuffer = MemorySegment.ofArray(new byte[(int) (objects * CullObjectGpu.SIZE)]);
        int draws = 16;
        for (int i = 0; i < objects; i++) {
            float z = -(float) Math.exp(Math.log(2) + r.nextDouble() * (Math.log(190) - Math.log(2)));
            float x = (float) (r.nextDouble() * 2 - 1) * -z * 1.1f, y = (float) (r.nextDouble() * 2 - 1) * -z * 0.7f, h = 0.3f + (float) r.nextDouble() * 1.5f;
            CullObjectGpu.write(new CullObject(new Vec3f(x - h, y - h, z - h), i % draws, new Vec3f(x + h, y + h, z + h), 0), objectBuffer, i * CullObjectGpu.SIZE);
        }
        Mat4f vp = Mat4f.perspective(1.0f, 16f / 9f, 0.1f, 200f, ClipSpace.D3D);
        Frustumf f = Frustumf.fromViewProjection(vp, DepthRange.ZERO_TO_ONE);
        Vec4f[] planes = new Vec4f[6];
        for (int i = 0; i < 6; i++) {
            planes[i] = new Vec4f(f.plane(i).nx(), f.plane(i).ny(), f.plane(i).nz(), f.plane(i).d());
        }
        int w = 480, h = 270;
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, 1f);
        for (int k = 0; k < 40; k++) {
            int x0 = r.nextInt(w), y0 = r.nextInt(h), x1 = Math.min(w, x0 + 1 + r.nextInt(w / 3)), y1 = Math.min(h, y0 + 1 + r.nextInt(h / 3));
            float d = 0.3f + (float) r.nextDouble() * 0.6f;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    depth[y * w + x] = Math.min(depth[y * w + x], d);
                }
            }
        }
        hzb = HiZPyramid.fromDepth(depth, w, h, DepthRange.ZERO_TO_ONE, true);
        view = MemorySegment.ofArray(new byte[(int) CullViewGpu.SIZE]);
        CullViewGpu.write(new CullView(planes, vp, objects, w, h, hzb.levels(), 1e-4f, 1, 0), view, 0);
        capacity = new int[draws];
        java.util.Arrays.fill(capacity, objects);
        commands = new DrawCommandBuffer(MemorySegment.ofArray(new byte[draws * 20]), DrawCommandBuffer.Kind.ELEMENTS, false);
        for (int d = 0; d < draws; d++) {
            commands.addElements(36, 0, 0, 0, d * 0);
        }
        visible = MemorySegment.ofArray(new byte[4 * objects]);
        // every draw writes into the same list: the benchmark measures the pass, not the layout of the lists
    }

    private void reset() {
        for (int d = 0; d < capacity.length; d++) {
            commands.setInstanceCount(d, 0);
        }
        counters.reset();
    }

    @Benchmark
    public int frustumOnly() {
        reset();
        GpuCullReference.cullSinglePass(view, objectBuffer, null, commands, capacity, visible, counters);
        return counters.drawn;
    }

    @Benchmark
    public int frustumAndHiZ() {
        reset();
        GpuCullReference.cullSinglePass(view, objectBuffer, hzb, commands, capacity, visible, counters);
        return counters.drawn;
    }
}
