package vmath.samples.verify;

import static org.lwjgl.opengl.GL46.*;

import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.lwjgl.BufferUtils;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.GlslVersion;
import vmath.gl.GpuWriter;
import vmath.gpucull.ClusterCullObject;
import vmath.gpucull.ClusterCullObjectGpu;
import vmath.gpucull.ClusterCullReference;
import vmath.gpucull.ClusterCullView;
import vmath.gpucull.ClusterCullViewGpu;
import vmath.gpucull.CullObject;
import vmath.gpucull.CullObjectGpu;
import vmath.gpucull.CullView;
import vmath.gpucull.CullViewGpu;
import vmath.gpucull.GpuCullGlsl;
import vmath.gpucull.GpuCullReference;
import vmath.gpucull.HiZPyramid;
import vmath.occlusion.HiZ;
import vmath.samples.framework.Gl;

/**
 * Runs the two culling compute shaders of {@code GpuCullGlsl} on the OpenGL driver of the machine on
 * random scenes, in the three depth conventions (OpenGL's -1 to 1, 0 to 1, reversed 0 to 1) and at the
 * lowest GLSL version the generator writes (4.30) and at the default (4.50), and compares what they
 * produce with the CPU references: the instance lists of the object shader as sets per draw (and, when
 * a draw has too little room, the counts and the overflow), and the clusters that the cluster shader chooses
 * with those of {@code ClusterCullReference}.
 *
 * <p>The Hi-Z pyramid is uploaded as a texture with a power-of-two base (the contract of
 * {@code HiZ}), its level 0 the depth image in the convention and the others the farthest of four, which is
 * what {@code HiZPyramid} computes on the CPU, so the two sides see the same numbers. The scenes are random,
 * with occluders in the depth image, boxes of every size, some that must not be occlusion tested, and some
 * that cross the camera plane.
 *
 * <p>Needs a display and an OpenGL 4.3 driver; {@code ./gradlew -Psamples :vmath-samples:gpuIt}. Internal: part
 * of the samples.
 *
 * <p><b>Thread safety.</b> Not thread-safe: run it on the main thread.
 */
public final class CullShaderGpuCheck {

    private static final int W = 128;
    private static final int H = 64;

    private CullShaderGpuCheck() {
    }

    /** One line of the report. */
    public record Outcome(String name, boolean ok, String detail) {
    }

    private static Mat4f projection(ClipSpace space, boolean reversed) {
        return reversed ? Mat4f.perspectiveReversedZ(1.0f, 16f / 9f, 0.1f, space) : Mat4f.perspective(1.0f, 16f / 9f, 0.1f, 200f, space);
    }

    private static List<Aabbf> randomBoxes(Random rnd, int n, float xyRange, float zNear, float zFar) {
        List<Aabbf> boxes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float z = -(float) Math.exp(Math.log(zNear) + rnd.nextDouble() * (Math.log(zFar) - Math.log(zNear)));
            float x = (rnd.nextFloat() * 2 - 1) * xyRange * -z * 0.6f, y = (rnd.nextFloat() * 2 - 1) * xyRange * -z * 0.4f;
            float hx = 0.1f + rnd.nextFloat() * 3f * (-z / 50f + 0.2f), hy = 0.1f + rnd.nextFloat() * 3f * (-z / 50f + 0.2f), hz = 0.1f + rnd.nextFloat() * 2f;
            boxes.add(new Aabbf(x - hx, y - hy, z - hz, x + hx, y + hy, z + hz));
        }
        return boxes;
    }

    /** A depth image of occluder rectangles, in the convention of the range; every value is a multiple of 1/1024 so that the conversions are exact. */
    private static float[] depthImage(Random rnd, DepthRange range, int rects) {
        float[] farness = new float[W * H];
        java.util.Arrays.fill(farness, 1f);
        for (int r = 0; r < rects; r++) {
            int x0 = rnd.nextInt(W), y0 = rnd.nextInt(H), x1 = Math.min(W, x0 + 1 + rnd.nextInt(W / 2)), y1 = Math.min(H, y0 + 1 + rnd.nextInt(H / 2));
            float f = (512 + rnd.nextInt(500)) / 1024f;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    farness[y * W + x] = Math.min(farness[y * W + x], f);
                }
            }
        }
        float[] api = new float[W * H];
        for (int i = 0; i < api.length; i++) {
            api[i] = switch (range) {
                case ZERO_TO_ONE -> farness[i];
                case NEGATIVE_ONE_TO_ONE -> farness[i] * 2f - 1f;
                case REVERSED_ZERO_TO_ONE -> 1f - farness[i];
            };
        }
        return api;
    }

    /** Uploads the pyramid: level 0 is the image as given, the others are the farthest values that the CPU pyramid holds, converted back to the convention. */
    private static int uploadPyramid(float[] api, HiZPyramid cpu, DepthRange range) {
        int texture = glGenTextures();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        int levels = cpu.levels();
        glTexStorage2D(GL_TEXTURE_2D, levels, GL_R32F, W, H);
        for (int l = 0; l < levels; l++) {
            int w = cpu.width(l), h = cpu.height(l);
            FloatBuffer data = BufferUtils.createFloatBuffer(w * h);
            if (l == 0) {
                data.put(api, 0, w * h);
            } else {
                float[] f = cpu.level(l);
                for (int i = 0; i < w * h; i++) {
                    data.put(switch (range) {
                        case ZERO_TO_ONE -> f[i];
                        case NEGATIVE_ONE_TO_ONE -> f[i] * 2f - 1f;
                        case REVERSED_ZERO_TO_ONE -> 1f - f[i];
                    });
                }
            }
            data.flip();
            glTexSubImage2D(GL_TEXTURE_2D, l, 0, 0, w, h, GL_RED, GL_FLOAT, data);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, levels - 1);
        return texture;
    }

    private static int buffer(int target, int slot, ByteBuffer data, long size) {
        int b = glGenBuffers();
        glBindBuffer(target, b);
        if (data == null) {
            glBufferData(target, size, GL_DYNAMIC_COPY);
        } else {
            glBufferData(target, data, GL_DYNAMIC_COPY);
        }
        glBindBufferBase(target, slot, b);
        return b;
    }

    private static ByteBuffer read(int target, int b, long size) {
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
        glBindBuffer(target, b);
        ByteBuffer out = BufferUtils.createByteBuffer((int) size);
        glGetBufferSubData(target, 0, out);
        return out;
    }

    private static ByteBuffer bytes(MemorySegment s, long n) {
        ByteBuffer b = BufferUtils.createByteBuffer((int) n);
        b.put(s.asSlice(0, n).asByteBuffer()).flip();
        return b;
    }

    // ---------------------------------------------------------------- the object shader

    private static String objectCase(GlslVersion version, DepthRange range, boolean reversedProjection, ClipSpace space, boolean tight, long seed) {
        Random rnd = new Random(seed);
        int n = 3000, draws = 5;
        List<Aabbf> boxes = randomBoxes(rnd, n, 1.0f, 0.3f, 190f);
        Mat4f vp = projection(space, reversedProjection);
        float[] api = depthImage(rnd, range, 20);
        HiZPyramid cpuPyramid = HiZPyramid.fromDepth(api, W, H, range, false);
        int[] draw = new int[n], flags = new int[n];
        int[] visibleOf = new int[draws];
        Frustumf frustum = Frustumf.fromViewProjection(vp, range);
        for (int i = 0; i < n; i++) {
            draw[i] = rnd.nextInt(draws);
            flags[i] = rnd.nextInt(10) == 0 ? GpuCullReference.OBJECT_NO_OCCLUSION : 0;
            if (frustum.intersects(boxes.get(i))) {
                visibleOf[draw[i]]++;
            }
        }
        int[] capacity = new int[draws];
        for (int d = 0; d < draws; d++) {
            capacity[d] = tight ? Math.max(1, visibleOf[d] / 3) : n;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment objects = arena.allocate(n * CullObjectGpu.SIZE, 16);
            for (int i = 0; i < n; i++) {
                Aabbf b = boxes.get(i);
                CullObjectGpu.write(new CullObject(new Vec3f(b.minX(), b.minY(), b.minZ()), draw[i], new Vec3f(b.maxX(), b.maxY(), b.maxZ()), flags[i]), objects, i * CullObjectGpu.SIZE);
            }
            Vec4f[] planes = new Vec4f[6];
            for (int i = 0; i < 6; i++) {
                planes[i] = new Vec4f(frustum.plane(i).nx(), frustum.plane(i).ny(), frustum.plane(i).nz(), frustum.plane(i).d());
            }
            MemorySegment view = arena.allocate(CullViewGpu.SIZE, 16);
            CullViewGpu.write(new CullView(planes, vp, n, cpuPyramid.width(0), cpuPyramid.height(0), cpuPyramid.levels(), 1e-4f, range.ordinal(), 0), view, 0);
            int total = 0;
            for (int c : capacity) {
                total += c;
            }
            // the commands: counts zero, base instances laid out one after the other
            MemorySegment cmdSeg = arena.allocate(draws * 20L, 16);
            DrawCommandBuffer cmd = new DrawCommandBuffer(cmdSeg, DrawCommandBuffer.Kind.ELEMENTS, false);
            int base = 0;
            for (int d = 0; d < draws; d++) {
                cmd.addElements(36, 0, d * 36, 0, base);
                base += capacity[d];
            }
            MemorySegment cmdRef = arena.allocate(draws * 20L, 16);
            DrawCommandBuffer ref = new DrawCommandBuffer(cmdRef, DrawCommandBuffer.Kind.ELEMENTS, false);
            base = 0;
            for (int d = 0; d < draws; d++) {
                ref.addElements(36, 0, d * 36, 0, base);
                base += capacity[d];
            }
            MemorySegment refVisible = arena.allocate(4L * total, 16);
            GpuCullReference.Counters counters = new GpuCullReference.Counters();
            GpuCullReference.cullSinglePass(view, objects, cpuPyramid, ref, capacity, refVisible, counters);

            int program = Gl.computeProgram(GpuCullGlsl.computeShader(64, version));
            int hzb = uploadPyramid(api, cpuPyramid, range);
            int ubo = buffer(GL_UNIFORM_BUFFER, 0, bytes(view, CullViewGpu.SIZE), 0);
            int obj = buffer(GL_SHADER_STORAGE_BUFFER, 1, bytes(objects, n * CullObjectGpu.SIZE), 0);
            int commands = buffer(GL_SHADER_STORAGE_BUFFER, 2, bytes(cmdSeg, draws * 20L), 0);
            int visible = buffer(GL_SHADER_STORAGE_BUFFER, 3, null, 4L * total);
            ByteBuffer cap = BufferUtils.createByteBuffer(4 * draws);
            for (int c : capacity) {
                cap.putInt(c);
            }
            cap.flip();
            int capBuffer = buffer(GL_SHADER_STORAGE_BUFFER, 4, cap, 0);
            int overflow = buffer(GL_SHADER_STORAGE_BUFFER, 5, BufferUtils.createByteBuffer(4), 0);
            glUseProgram(program);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, hzb);
            glDispatchCompute((n + 63) / 64, 1, 1);
            Gl.check("running the object culling shader");
            ByteBuffer gpuCommands = read(GL_SHADER_STORAGE_BUFFER, commands, draws * 20L);
            ByteBuffer gpuVisible = read(GL_SHADER_STORAGE_BUFFER, visible, 4L * total);
            int gpuOverflow = read(GL_SHADER_STORAGE_BUFFER, overflow, 4).getInt(0);
            glDeleteProgram(program);
            glDeleteTextures(hzb);
            glDeleteBuffers(new int[] {ubo, obj, commands, visible, capBuffer, overflow});
            StringBuilder problem = new StringBuilder();
            long compared = 0;
            for (int d = 0; d < draws; d++) {
                int gpuCount = gpuCommands.getInt(20 * d + 4), gpuBase = gpuCommands.getInt(20 * d + 16);
                TreeSet<Integer> gpu = new TreeSet<>();
                for (int k = 0; k < gpuCount; k++) {
                    gpu.add(gpuVisible.getInt(4 * (gpuBase + k)));
                }
                TreeSet<Integer> cpu = new TreeSet<>();
                for (int k = 0; k < ref.instanceCount(d); k++) {
                    cpu.add(GpuWriter.getInt(refVisible, 4L * (ref.baseInstance(d) + k)));
                }
                if (gpuCount != ref.instanceCount(d)) {
                    problem.append("draw ").append(d).append(": ").append(gpuCount).append(" instances on the GPU, ").append(ref.instanceCount(d)).append(" on the CPU; ");
                }
                if (!tight && !gpu.equals(cpu)) {
                    TreeSet<Integer> onlyGpu = new TreeSet<>(gpu), onlyCpu = new TreeSet<>(cpu);
                    onlyGpu.removeAll(cpu);
                    onlyCpu.removeAll(gpu);
                    problem.append("draw ").append(d).append(": ").append(onlyGpu.size()).append(" only on the GPU, ").append(onlyCpu.size()).append(" only on the CPU; ");
                }
                compared += cpu.size();
            }
            if (gpuOverflow != counters.overflow) {
                problem.append("overflow ").append(gpuOverflow).append(" on the GPU, ").append(counters.overflow).append(" on the CPU; ");
            }
            if (!tight && compared < 200) {
                problem.append("only ").append(compared).append(" survivors: the scene proves little; ");
            }
            return problem.length() == 0 ? null : problem.toString();
        }
    }

    // ---------------------------------------------------------------- the cluster shader

    private static String clusterCase(GlslVersion version, DepthRange range, boolean reversedProjection, ClipSpace space, long seed) {
        Random rnd = new Random(seed);
        int n = 4000;
        Mat4f vp = projection(space, reversedProjection);
        float[] api = depthImage(rnd, range, 20);
        HiZPyramid cpuPyramid = HiZPyramid.fromDepth(api, W, H, range, false);
        Frustumf frustum = Frustumf.fromViewProjection(vp, range);
        float budget = 4f;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment clusters = arena.allocate(n * ClusterCullObjectGpu.SIZE, 16);
            for (int i = 0; i < n; i++) {
                float z = -(float) Math.exp(Math.log(0.5) + rnd.nextDouble() * (Math.log(150.0) - Math.log(0.5)));
                float x = (rnd.nextFloat() * 2 - 1) * -z * 0.6f, y = (rnd.nextFloat() * 2 - 1) * -z * 0.4f, r = 0.2f + rnd.nextFloat() * 2f;
                Vec3f axis = new Vec3f(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1);
                float len = (float) Math.sqrt(axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z());
                Vec4f cone = rnd.nextInt(3) == 0 ? new Vec4f(0f, 0f, 1f, 1f) : new Vec4f(axis.x() / len, axis.y() / len, axis.z() / len, rnd.nextFloat() * 0.9f);
                Vec4f sphere = new Vec4f(x, y, z, r);
                float own = rnd.nextFloat() * 0.05f, parent = own + rnd.nextFloat() * 0.5f;
                ClusterCullObjectGpu.write(new ClusterCullObject(sphere, cone, sphere, sphere, own, parent, i * 3, 3), clusters, i * ClusterCullObjectGpu.SIZE);
            }
            Vec4f[] planes = new Vec4f[6];
            for (int i = 0; i < 6; i++) {
                planes[i] = new Vec4f(frustum.plane(i).nx(), frustum.plane(i).ny(), frustum.plane(i).nz(), frustum.plane(i).d());
            }
            Vec3f eye = new Vec3f(0f, 0f, 0f);
            float pixelScale = 600f;
            MemorySegment view = arena.allocate(ClusterCullViewGpu.SIZE, 16);
            ClusterCullViewGpu.write(new ClusterCullView(planes, vp, new Vec4f(eye.x(), eye.y(), eye.z(), pixelScale), n, cpuPyramid.width(0), cpuPyramid.height(0), cpuPyramid.levels(), 1e-4f, budget,
                    range.ordinal(), 0), view, 0);
            DrawCommandBuffer out = new DrawCommandBuffer(arena.allocate(n * 20L, 16), DrawCommandBuffer.Kind.ELEMENTS, false);
            ClusterCullReference.Counters counters = new ClusterCullReference.Counters();
            ClusterCullReference.cull(view, clusters, cpuPyramid, out, counters);
            TreeSet<Integer> cpu = new TreeSet<>();
            for (int k = 0; k < out.count(); k++) {
                cpu.add(out.baseInstance(k));
            }

            int program = Gl.computeProgram(GpuCullGlsl.clusterShader(64, version));
            int hzb = uploadPyramid(api, cpuPyramid, range);
            int ubo = buffer(GL_UNIFORM_BUFFER, 0, bytes(view, ClusterCullViewGpu.SIZE), 0);
            int clusterBuffer = buffer(GL_SHADER_STORAGE_BUFFER, 1, bytes(clusters, n * ClusterCullObjectGpu.SIZE), 0);
            int commands = buffer(GL_SHADER_STORAGE_BUFFER, 2, null, n * 20L);
            int counterBuffer = buffer(GL_SHADER_STORAGE_BUFFER, 3, BufferUtils.createByteBuffer(8), 0);
            glUseProgram(program);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, hzb);
            glDispatchCompute((n + 63) / 64, 1, 1);
            Gl.check("running the cluster culling shader");
            ByteBuffer gpuCounters = read(GL_SHADER_STORAGE_BUFFER, counterBuffer, 8);
            int drawCount = gpuCounters.getInt(0);
            ByteBuffer gpuCommands = read(GL_SHADER_STORAGE_BUFFER, commands, n * 20L);
            glDeleteProgram(program);
            glDeleteTextures(hzb);
            glDeleteBuffers(new int[] {ubo, clusterBuffer, commands, counterBuffer});
            TreeSet<Integer> gpu = new TreeSet<>();
            for (int k = 0; k < Math.min(drawCount, n); k++) {
                gpu.add(gpuCommands.getInt(20 * k + 16));
            }
            StringBuilder problem = new StringBuilder();
            if (!gpu.equals(cpu)) {
                TreeSet<Integer> onlyGpu = new TreeSet<>(gpu), onlyCpu = new TreeSet<>(cpu);
                onlyGpu.removeAll(cpu);
                onlyCpu.removeAll(gpu);
                problem.append(onlyGpu.size()).append(" clusters only on the GPU, ").append(onlyCpu.size()).append(" only on the CPU (of ").append(cpu.size()).append(" chosen); ");
            }
            if (cpu.size() < 100) {
                problem.append("only ").append(cpu.size()).append(" clusters chosen: the scene proves little; ");
            }
            return problem.length() == 0 ? null : problem.toString();
        }
    }

    /**
     * Runs the check on the current context.
     *
     * @param out where to print the progress; may be {@code null}
     * @return one outcome per shader, version, depth convention and scene
     */
    public static List<Outcome> check(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        record Convention(String name, DepthRange range, boolean reversed, ClipSpace space) {
        }
        List<Convention> conventions = List.of(new Convention("OpenGL, -1 to 1", DepthRange.NEGATIVE_ONE_TO_ONE, false, ClipSpace.OPENGL), new Convention("0 to 1", DepthRange.ZERO_TO_ONE, false, ClipSpace.VULKAN),
                new Convention("reversed 0 to 1", DepthRange.REVERSED_ZERO_TO_ONE, true, ClipSpace.VULKAN));
        for (GlslVersion version : List.of(GlslVersion.V430, GlslVersion.V450)) {
            for (Convention c : conventions) {
                for (int scene = 0; scene < 3; scene++) {
                    for (boolean tight : new boolean[] {false, true}) {
                        String name = "object shader, GLSL " + version.number() + ", " + c.name() + ", scene " + scene + (tight ? ", too little room" : "");
                        String problem;
                        try {
                            problem = objectCase(version, c.range(), c.reversed(), c.space(), tight, 500 + scene);
                        } catch (RuntimeException e) {
                            problem = String.valueOf(e.getMessage());
                        }
                        add(results, out, name, problem);
                    }
                    String name = "cluster shader, GLSL " + version.number() + ", " + c.name() + ", scene " + scene;
                    String problem;
                    try {
                        problem = clusterCase(version, c.range(), c.reversed(), c.space(), 900 + scene);
                    } catch (RuntimeException e) {
                        problem = String.valueOf(e.getMessage());
                    }
                    add(results, out, name, problem);
                }
            }
        }
        if (HiZ.baseSize(W) != W) {
            throw new AssertionError("the base of the test pyramid must be a power of two");
        }
        return results;
    }

    private static void add(List<Outcome> results, PrintStream out, String name, String problem) {
        Outcome o = new Outcome(name, problem == null, problem == null ? "same as the CPU reference" : problem);
        results.add(o);
        if (out != null) {
            out.println((o.ok() ? "ok    " : "FAIL  ") + name + (o.ok() ? "" : "\n      " + o.detail()));
        }
    }
}
