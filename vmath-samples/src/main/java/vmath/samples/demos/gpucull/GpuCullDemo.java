package vmath.samples.demos.gpucull;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_X;
import static org.lwjgl.opengl.GL45.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.glBindBuffer;
import static org.lwjgl.opengl.GL45.glBindBufferBase;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glClear;
import static org.lwjgl.opengl.GL45.glClearColor;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDispatchCompute;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glMemoryBarrier;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glProgramUniform1f;
import static org.lwjgl.opengl.GL45.glProgramUniform1ui;
import static org.lwjgl.opengl.GL45.glProgramUniform3f;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribIFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayBindingDivisor;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.gl.DrawCommandBuffer;
import vmath.gpucull.CullViewGpu;
import vmath.gpucull.GpuCullGlsl;
import vmath.gpucull.GpuCullReference;
import vmath.gpucull.HiZPyramid;
import vmath.occlusion.HiZ;
import vmath.samples.framework.BoxRenderer;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FlyCamera;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Gl;
import vmath.samples.framework.GpuMesh;
import vmath.samples.framework.GpuTimer;
import vmath.samples.framework.Hud;
import vmath.samples.framework.ReadbackRing;
import vmath.samples.framework.SceneTarget;
import vmath.samples.framework.Scenes;
import vmath.samples.framework.Stats;
import vmath.samples.framework.Warmup;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernels;

/**
 * GPU-driven culling of a dense city: no object is looked at by the CPU. The library's compute shader
 * ({@code GpuCullGlsl.computeShader}) tests every box against the frustum and against a Hi-Z
 * pyramid, and for each survivor takes a slot of the indirect command with an atomic add and writes
 * the object's index; one {@code glDrawElementsIndirect} draws them all, the vertex shader finding the
 * object through an instanced attribute that reads the survivor list.
 *
 * <p>The pyramid is built on the GPU from the depth of the previous frame
 * ({@link HizBuilder}); the cull of a frame uses its own frustum and the view projection of the frame
 * that made the pyramid (the {@code CullView} was designed for this), so a fast turn can keep an
 * object for a frame that it should not, never remove one that it should not. A box that is hidden
 * by something drawn last frame and uncovered since is the known risk of one-pass culling with last
 * frame's depth, and the demo does not hide it: the two-phase scheme in the library's Javadoc
 * (draw last frame's survivors first, build the pyramid, cull the rest) is the cure and is not built
 * here.
 *
 * <p>{@code --verify} checks every 20 frames, after the frame has been drawn: the depth is read back
 * and the library's {@code HiZPyramid} is built from it on the CPU and compared with every level of
 * the GPU pyramid; then the shader is run again with the pyramid of that depth and the view
 * projection that made it, and its survivors are compared with those of
 * {@code GpuCullReference.cullSinglePass} on the same bytes. The CPU frustum cull with the library's
 * SIMD kernel runs beside it for the comparison. {@code O} switches the pyramid off, {@code X} freezes
 * the camera of the culling.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class GpuCullDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("gpu-culling", "GPU-driven culling with a Hi-Z pyramid",
            "The library's compute shader culls 410,000 boxes against the frustum and a depth pyramid built on the GPU, and one indirect draw follows; checked against the CPU model.",
            List.of("culling", "scale"), 16384, List.of("--blocks", "24", "--props", "30"),
            "left mouse + move: look | W A S D: fly | Space, Ctrl: up, down | Shift: fast | O: depth pyramid on/off | X: freeze the camera of the culling");

    private static final float FAR = 1500f;
    private static final int VERIFY_EVERY = 20;
    private static final float PYRAMID_TOLERANCE = 1e-6f;

    private final GpuCullOptions options;
    private Scenes.Blocks city;
    private BoundsArray bounds;
    private MemorySegment objects;
    private MemorySegment viewSegment;
    private int objectCount;
    private GpuMesh cube;
    private int vao;
    private int drawProgram;
    private int computeProgram;
    private int viewProjectionLocation;
    private int groundLocation;
    private int neutralLocation;
    private int fogLocation;
    private int fogColorLocation;
    private int objectsBuffer;
    private int viewBuffer;
    private int commandBuffer;
    private int visibleBuffer;
    private int capacityBuffer;
    private int overflowBuffer;
    private SceneTarget target;
    private HizBuilder hiz;
    private ReadbackRing readback;
    private GpuTimer cullTimer;
    private GpuTimer drawTimer;
    private GpuTimer hizTimer;
    private FlyCamera fly;
    private DemoContext ctx;
    private Cameraf camera;
    private Cameraf frozen;
    private Mat4f depthImageMatrix;
    private boolean occlusion;
    private boolean freeze;
    private CullPipeline cpuPipeline;
    private VisibilitySet cpuVisible;
    private double cpuMs;
    private int cpuCount;
    private float streetX;
    private float pathLength;
    private int frameNumber;
    private int cullSeries;
    private int drawSeries;
    private int hizSeries;
    private int cpuSeries;
    private int frustumSeries;
    private int survivorSeries;
    private int gpuSurvivors;
    private int gpuOverflow;
    private long verifiedFrames;
    private long pyramidTexelsChecked;
    private long gpuOnlyTotal;
    private long cpuOnlyTotal;
    private long comparedSurvivors;
    private String verdict = "";

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public GpuCullDemo(List<String> args) {
        this.options = GpuCullOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        long t0 = System.nanoTime();
        city = Scenes.blocks(options.blocks(), options.props());
        bounds = city.bounds();
        objectCount = bounds.size();
        objects = CullData.writeObjects(ctx.arena(), bounds);
        viewSegment = ctx.arena().allocate(CullViewGpu.SIZE, 16);
        System.out.printf(Locale.ROOT, "built %,d objects in %.0f ms%n", objectCount, (System.nanoTime() - t0) / 1e6);
        cpuVisible = new VisibilitySet(objectCount);
        cpuPipeline = CullPipeline.of(new CullStages.Frustum());
        occlusion = options.occlusion();

        objectsBuffer = glCreateBuffers();
        glNamedBufferStorage(objectsBuffer, objects.asByteBuffer(), 0);
        viewBuffer = glCreateBuffers();
        glNamedBufferStorage(viewBuffer, CullViewGpu.SIZE, GL_DYNAMIC_STORAGE_BIT);
        commandBuffer = glCreateBuffers();
        glNamedBufferStorage(commandBuffer, 20, GL_DYNAMIC_STORAGE_BIT);
        visibleBuffer = glCreateBuffers();
        glNamedBufferStorage(visibleBuffer, 4L * objectCount, GL_DYNAMIC_STORAGE_BIT);
        capacityBuffer = glCreateBuffers();
        glNamedBufferStorage(capacityBuffer, new int[] {objectCount}, 0);
        overflowBuffer = glCreateBuffers();
        glNamedBufferStorage(overflowBuffer, 4, GL_DYNAMIC_STORAGE_BIT);

        cube = BoxRenderer.createCube(ctx);
        vao = cube.vao();
        glVertexArrayVertexBuffer(vao, 1, visibleBuffer, 0, Integer.BYTES);
        glVertexArrayBindingDivisor(vao, 1, 1);
        glEnableVertexArrayAttrib(vao, 2);
        glVertexArrayAttribIFormat(vao, 2, 1, GL_UNSIGNED_INT, 0);
        glVertexArrayAttribBinding(vao, 2, 1);

        drawProgram = Gl.program("""
                #version 450 core
                layout(location = 0) in vec3 position;
                layout(location = 1) in vec3 normal;
                layout(location = 2) in uint objectIndex;
                layout(std430, binding = 0) readonly buffer Objects { vec4 objectData[]; };   // per object: min and the draw index, max and the flags
                uniform mat4 viewProjection;
                uniform uint groundId;
                uniform uint neutralBelow;
                out vec3 vNormal;
                out vec3 vColor;
                out float vDepth;
                uint hash(uint x) {
                    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
                    return x;
                }
                void main() {
                    vec4 lo = objectData[2u * objectIndex], hi = objectData[2u * objectIndex + 1u];
                    vec3 world = lo.xyz + (position + 0.5) * (hi.xyz - lo.xyz);
                    vNormal = normal;
                    uint h = hash(objectIndex);
                    vec3 tint = vec3(float(h & 255u), float((h >> 8) & 255u), float((h >> 16) & 255u)) / 255.0;
                    vColor = objectIndex == groundId ? vec3(0.32, 0.34, 0.33) : objectIndex < neutralBelow ? vec3(0.62, 0.62, 0.6) + (tint - 0.5) * 0.12 : mix(vec3(0.55, 0.6, 0.7), tint, 0.45);
                    gl_Position = viewProjection * vec4(world, 1.0);
                    vDepth = gl_Position.w;
                }
                """, """
                #version 450 core
                in vec3 vNormal;
                in vec3 vColor;
                in float vDepth;
                uniform float fogDensity;
                uniform vec3 fogColor;
                out vec4 color;
                void main() {
                    vec3 n = normalize(vNormal);
                    float light = 0.28 + 0.72 * max(dot(n, normalize(vec3(0.4, 0.8, 0.3))), 0.0);
                    float fog = 1.0 - exp(-vDepth * fogDensity);
                    color = vec4(mix(vColor * light, fogColor, fog), 1.0);
                }
                """);
        viewProjectionLocation = glGetUniformLocation(drawProgram, "viewProjection");
        groundLocation = glGetUniformLocation(drawProgram, "groundId");
        neutralLocation = glGetUniformLocation(drawProgram, "neutralBelow");
        fogLocation = glGetUniformLocation(drawProgram, "fogDensity");
        fogColorLocation = glGetUniformLocation(drawProgram, "fogColor");
        computeProgram = Gl.computeProgram(GpuCullGlsl.computeShader(64));

        readback = new ReadbackRing(4, 2);
        cullTimer = new GpuTimer();
        drawTimer = new GpuTimer();
        hizTimer = new GpuTimer();
        float half = options.blocks() * Scenes.BLOCK_PITCH * 0.5f;
        streetX = (options.blocks() / 2 + 1) * Scenes.BLOCK_PITCH - half;
        pathLength = 2f * half - 200f;
        fly = new FlyCamera(new Vec3f(streetX, 3f, -half + 100f), (float) Math.PI, -0.02f, 1.0f, 0.3f, FAR, 30f, 150f);

        Stats stats = ctx.stats();
        cullSeries = stats.timer("GPU cull", "the compute shader over every object: frustum, depth pyramid, atomic append");
        drawSeries = stats.timer("GPU draw", "one indirect draw of the survivors into the offscreen target");
        hizSeries = stats.timer("GPU pyramid", "the depth of the frame into the pyramid, all levels");
        cpuSeries = stats.timer("CPU frustum (for comparison)", "the library's frustum kernel over every object, no depth test");
        frustumSeries = stats.series("in the frustum", "", 0, "objects that the CPU frustum cull keeps, of " + objectCount);
        survivorSeries = stats.series("drawn", "", 0, "survivors of the shader as read back two or three frames late");
        ctx.clearColor(0.62f, 0.72f, 0.85f);
        // the library's kernel is warmed up for the comparison column
        Cameraf start = fly.camera(16f / 9f);
        Warmup.untilQuiet(() -> cpuPipeline.run(CullContext.perspective(start.frustum(), start.position(), start.fovy(), 900), bounds, cpuVisible), 3000);
    }

    private void ensureTargets(int width, int height) {
        if (target != null && target.width() == width && target.height() == height) {
            return;
        }
        if (target != null) {
            target.dispose();
            hiz.dispose();
            hiz = null;
        }
        target = new SceneTarget(width, height);
        hiz = new HizBuilder(width, height);
        depthImageMatrix = null;
    }

    @Override
    public void update(FrameInfo frame) {
        fly.update(frame);
        if (frame.benchmark()) {
            float p = (frame.frame() * 0.4f) % (2f * pathLength);
            boolean forward = p < pathLength;
            float half = options.blocks() * Scenes.BLOCK_PITCH * 0.5f;
            fly.place(streetX, 3f, -half + 100f + (forward ? p : 2f * pathLength - p), (forward ? (float) Math.PI : 0f) + 0.5f * (float) Math.sin(frame.frame() * 0.015), -0.02f);
        } else {
            var in = frame.input();
            if (in.pressed(GLFW_KEY_O)) {
                occlusion = !occlusion;
            }
            if (in.pressed(GLFW_KEY_X)) {
                freeze = !freeze;
                frozen = freeze ? camera : null;
            }
        }
        camera = fly.camera(frame.aspect());
        ensureTargets(frame.width(), frame.height());
        Cameraf cullCamera = frozen != null ? frozen : camera;
        long t0 = System.nanoTime();
        cpuCount = cpuPipeline.run(CullContext.perspective(cullCamera.frustum(), cullCamera.position(), cullCamera.fovy(), frame.height()), bounds, cpuVisible);
        cpuMs = (System.nanoTime() - t0) / 1e6;
        Stats stats = ctx.stats();
        stats.recordNanos(cpuSeries, (long) (cpuMs * 1e6));
        stats.record(frustumSeries, cpuCount);
    }

    /**
     * Writes the view, clears the command and the counter and runs the culling shader over every
     * object.
     */
    private void dispatchCull(Cameraf cullCamera, Mat4f depthMatrix, float nearW) {
        CullData.writeView(viewSegment, cullCamera, depthMatrix, objectCount, hiz.width(), hiz.height(), hiz.levels(), nearW);
        glNamedBufferSubData(viewBuffer, 0, viewSegment.asByteBuffer());
        glNamedBufferSubData(commandBuffer, 0, new int[] {cube.indexCount(), 0, 0, 0, 0});
        glNamedBufferSubData(overflowBuffer, 0, new int[] {0});
        glUseProgram(computeProgram);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, viewBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, objectsBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, commandBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, visibleBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, capacityBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, overflowBuffer);
        glBindTextureUnit(0, hiz.texture());
        glDispatchCompute((objectCount + 63) / 64, 1, 1);
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
    }

    @Override
    public void render(FrameInfo frame) {
        Cameraf cullCamera = frozen != null ? frozen : camera;
        Mat4f matrix = depthImageMatrix != null ? depthImageMatrix : camera.viewProjection();
        cullTimer.begin();
        dispatchCull(cullCamera, matrix, occlusion ? camera.near() : 1e30f);
        cullTimer.end();

        target.bind();
        glClearColor(0.62f, 0.72f, 0.85f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        drawTimer.begin();
        glUseProgram(drawProgram);
        Gl.uniform(drawProgram, viewProjectionLocation, camera.viewProjection());
        glProgramUniform1ui(drawProgram, groundLocation, objectCount - 1);
        glProgramUniform1ui(drawProgram, neutralLocation, city.buildings());
        glProgramUniform1f(drawProgram, fogLocation, 0.0006f);
        glProgramUniform3f(drawProgram, fogColorLocation, 0.62f, 0.72f, 0.85f);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, objectsBuffer);
        glBindVertexArray(vao);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        org.lwjgl.opengl.GL45.glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L);
        drawTimer.end();

        hizTimer.begin();
        hiz.build(target.depthTexture());
        hizTimer.end();
        depthImageMatrix = camera.viewProjection();

        readback.begin();
        readback.copy(commandBuffer, 4, 0, 1);
        readback.copy(overflowBuffer, 0, 1, 1);
        readback.end();
        readback.poll();
        gpuSurvivors = readback.latest(0);
        gpuOverflow = readback.latest(1);

        if (options.verify() && frameNumber % VERIFY_EVERY == VERIFY_EVERY - 1) {
            verify(camera);
        }
        target.blitToWindow(frame.width(), frame.height());
        frameNumber++;

        Stats stats = ctx.stats();
        long c = cullTimer.poll(), d = drawTimer.poll(), h = hizTimer.poll();
        if (c >= 0) {
            stats.recordNanos(cullSeries, c);
        }
        if (d >= 0) {
            stats.recordNanos(drawSeries, d);
        }
        if (h >= 0) {
            stats.recordNanos(hizSeries, h);
        }
        stats.record(survivorSeries, gpuSurvivors);
    }

    /**
     * Checks the frame that has just been drawn: the pyramid against the library's CPU pyramid of the
     * same depth, and the survivors of the shader (run again with that pyramid and the matrix that
     * made it) against {@code GpuCullReference.cullSinglePass} on the same bytes.
     *
     * @throws IllegalStateException if a pyramid texel differs, or if the shader and the reference
     *     disagree about more than a rounding fraction of the survivors
     */
    private void verify(Cameraf cam) {
        int sw = target.width(), sh = target.height(), w = hiz.width(), h = hiz.height();
        FloatBuffer depth = MemoryUtil.memAllocFloat(sw * sh);
        try {
            target.readDepth(depth);
            float[] ndc = new float[sw * sh];
            for (int i = 0; i < ndc.length; i++) {
                ndc[i] = depth.get(i) * 2f - 1f;
            }
            HiZPyramid cpuPyramid = HiZPyramid.fromDepth(HizBuilder.resample(ndc, sw, sh, w, h), w, h, DepthRange.NEGATIVE_ONE_TO_ONE, false);
            for (int l = 0; l < hiz.levels(); l++) {
                int lw = HiZ.mipSize(w, l), lh = HiZ.mipSize(h, l);
                FloatBuffer gpuLevel = MemoryUtil.memAllocFloat(lw * lh);
                try {
                    hiz.readLevel(l, gpuLevel);
                    float[] cpuLevel = cpuPyramid.level(l);
                    for (int i = 0; i < lw * lh; i++) {
                        // the GPU texels are normalised device depth; the CPU pyramid keeps the same thing as "farness", which for this depth range is (z + 1) / 2
                        if (Math.abs((gpuLevel.get(i) + 1f) * 0.5f - cpuLevel[i]) > PYRAMID_TOLERANCE) {
                            throw new IllegalStateException(String.format(Locale.ROOT, "the GPU pyramid differs from the CPU pyramid at level %d, texel %d: %.7f against %.7f", l, i, gpuLevel.get(i),
                                    cpuLevel[i]));
                        }
                    }
                    pyramidTexelsChecked += (long) lw * lh;
                } finally {
                    MemoryUtil.memFree(gpuLevel);
                }
            }
            Mat4f vp = cam.viewProjection();
            dispatchCull(cam, vp, cam.near());
            ByteBuffer counts = MemoryUtil.memAlloc(4).order(ByteOrder.nativeOrder());
            IntBuffer ids = MemoryUtil.memAllocInt(objectCount);
            try {
                glGetNamedBufferSubData(commandBuffer, 4, counts);
                int gpuCount = counts.getInt(0);
                ids.clear();
                glGetNamedBufferSubData(visibleBuffer, 0, ids);
                MemorySegment commandSegment = ctx.arena().allocate(DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false), 16);
                DrawCommandBuffer commands = new DrawCommandBuffer(commandSegment, DrawCommandBuffer.Kind.ELEMENTS, false);
                commands.addElements(cube.indexCount(), 0, 0, 0, 0);
                MemorySegment visible = ctx.arena().allocate(4L * objectCount, 16);
                GpuCullReference.Counters counters = new GpuCullReference.Counters();
                counters.reset();
                GpuCullReference.cullSinglePass(viewSegment, objects, cpuPyramid, commands, new int[] {objectCount}, visible, counters);
                int cpuCount2 = commands.instanceCount(0);
                boolean[] fromGpu = new boolean[objectCount], fromCpu = new boolean[objectCount];
                for (int i = 0; i < gpuCount; i++) {
                    fromGpu[ids.get(i)] = true;
                }
                for (int i = 0; i < cpuCount2; i++) {
                    fromCpu[visible.get(ValueLayout.JAVA_INT, 4L * i)] = true;
                }
                int gpuOnly = 0, cpuOnly = 0;
                for (int i = 0; i < objectCount; i++) {
                    if (fromGpu[i] && !fromCpu[i]) {
                        gpuOnly++;
                    } else if (fromCpu[i] && !fromGpu[i]) {
                        cpuOnly++;
                    }
                }
                gpuOnlyTotal += gpuOnly;
                cpuOnlyTotal += cpuOnly;
                comparedSurvivors += cpuCount2;
                verifiedFrames++;
                verdict = String.format(Locale.ROOT, "%d frames checked: pyramid identical (%,d texels), survivors %,d compared, %d only on the GPU, %d only on the CPU", verifiedFrames, pyramidTexelsChecked,
                        comparedSurvivors, gpuOnlyTotal, cpuOnlyTotal);
                if (gpuCount != cpuCount2 && (gpuOnly + cpuOnly) > Math.max(2, cpuCount2 / 2000)) {
                    throw new IllegalStateException("the shader kept " + gpuCount + " objects and the CPU reference " + cpuCount2 + ", " + gpuOnly + " only on the GPU and " + cpuOnly + " only on the CPU, at frame "
                            + frameNumber);
                }
            } finally {
                MemoryUtil.memFree(counts);
                MemoryUtil.memFree(ids);
            }
        } finally {
            MemoryUtil.memFree(depth);
        }
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.line(String.format(Locale.ROOT, "%,d objects | frustum alone (CPU) keeps %,d | the shader drew %,d (%.1f%% of the frustum's) | depth pyramid %s%s", objectCount, cpuCount, gpuSurvivors,
                cpuCount == 0 ? 0.0 : 100.0 * gpuSurvivors / cpuCount, occlusion ? "on" : "off", frozen != null ? " | camera of the culling frozen (X)" : ""));
        hud.line(String.format(Locale.ROOT, "CPU frustum kernel %.3f ms (no depth test) | overflow %d | pyramid %d x %d, %d levels", cpuMs, gpuOverflow, hiz.width(), hiz.height(), hiz.levels()));
        if (options.verify() && !verdict.isEmpty()) {
            hud.line(verdict);
        }
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "%,d objects (%,d buildings and %,d props), pyramid %d x %d with %d levels, depth pyramid %s", objectCount, city.buildings(), city.props(), hiz.width(), hiz.height(),
                hiz.levels(), occlusion ? "on" : "off"));
        if (options.verify()) {
            stats.note(verdict);
        }
    }

    @Override
    public void dispose() {
        readback.dispose();
        cullTimer.dispose();
        drawTimer.dispose();
        hizTimer.dispose();
        if (target != null) {
            target.dispose();
        }
        if (hiz != null) {
            hiz.dispose();
        }
        cube.delete();
        for (int b : new int[] {objectsBuffer, viewBuffer, commandBuffer, visibleBuffer, capacityBuffer, overflowBuffer}) {
            glDeleteBuffers(b);
        }
        glDeleteProgram(drawProgram);
        glDeleteProgram(computeProgram);
    }
}
