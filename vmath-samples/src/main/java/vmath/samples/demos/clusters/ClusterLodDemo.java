package vmath.samples.demos.clusters;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_G;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_X;
import static org.lwjgl.opengl.GL45.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FILL;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL45.GL_LINE;
import static org.lwjgl.opengl.GL45.GL_NEAREST;
import static org.lwjgl.opengl.GL45.GL_R32F;
import static org.lwjgl.opengl.GL45.GL_R32UI;
import static org.lwjgl.opengl.GL45.GL_RED;
import static org.lwjgl.opengl.GL45.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.glBindBuffer;
import static org.lwjgl.opengl.GL45.glBindBufferBase;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glClearNamedBufferData;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteTextures;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glDispatchCompute;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glMemoryBarrier;
import static org.lwjgl.opengl.GL45.glMultiDrawElementsIndirect;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glPolygonMode;
import static org.lwjgl.opengl.GL45.glProgramUniform1i;
import static org.lwjgl.opengl.GL45.glProgramUniform3f;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;
import static org.lwjgl.opengl.GL45.glTextureSubImage2D;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribIFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayBindingDivisor;
import static org.lwjgl.opengl.GL45.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.gl.DrawCommandBuffer;
import vmath.gpucull.ClusterCullReference;
import vmath.gpucull.ClusterCullViewGpu;
import vmath.gpucull.GpuCullGlsl;
import vmath.mesh.ClusterHierarchy;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Gl;
import vmath.samples.framework.GpuTimer;
import vmath.samples.framework.Hud;
import vmath.samples.framework.OrbitCamera;
import vmath.samples.framework.ReadbackRing;
import vmath.samples.framework.Stats;

/**
 * A dense rock drawn with continuous level of detail from a cluster hierarchy, in the manner of
 * Nanite: the rock is cut into clusters of 64 vertices and 124 triangles, the clusters are grouped
 * and simplified level by level with their borders held, and every frame the clusters whose error
 * is just small enough on the screen are drawn.
 *
 * <p>The cut is chosen in one of two ways, switched with {@code G}: on the CPU by the library's
 * {@code ClusterHierarchy.select}, or on the GPU by the library's compute shader
 * ({@code GpuCullGlsl.clusterShader}), which also tests every cluster against the frustum and its
 * normal cone and writes one indirect draw command per survivor; one {@code glMultiDrawElementsIndirect}
 * draws them. {@code --verify} runs the shader at four distances and compares the clusters it chose
 * with the clusters that the library's CPU reference of the same shader chooses; they must be the
 * same set. Colours: each cluster has its own, or with {@code L} the colour of its level.
 * {@code [} and {@code ]} change the error budget in pixels, {@code T} draws wireframes, and
 * {@code X} freezes the camera that the cut and the culling use, so that flying away shows the
 * clusters that were chosen.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class ClusterLodDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("cluster-lod", "Cluster hierarchy with continuous level of detail",
            "A dense rock cut into clusters whose level of detail is chosen per cluster by screen-space error, on the CPU or in the library's compute shader.",
            List.of("culling", "geometry"), 16384, List.of("--detail", "4", "--verify"),
            "left mouse + move: orbit | wheel: zoom | G: CPU or GPU cut | [ ]: error budget | L: colour by level | X: freeze the camera of the cut | T: wireframe");

    private static final float FOVY = 1.0f;
    private static final int LEVEL_COLORS = 8;

    private final ClusterOptions options;
    private ClusterScene scene;
    private ClusterHierarchy hierarchy;
    private OrbitCamera orbit;
    private DemoContext ctx;
    private Cameraf camera;
    private Cameraf frozen;
    private int clusterCount;
    private int vao;
    private int vertexBuffer;
    private int indexBuffer;
    private int idBuffer;
    private int levelBuffer;
    private int clusterBuffer;
    private int viewBuffer;
    private int commandBuffer;
    private int counterBuffer;
    private int dummyHzb;
    private int drawProgram;
    private int computeProgram;
    private int viewProjectionLocation;
    private int eyeLocation;
    private int tintLocation;
    private MemorySegment clusters;
    private MemorySegment viewSegment;
    private MemorySegment cpuCommands;
    private DrawCommandBuffer commands;
    private ByteBuffer commandBytes;
    private int[] selected;
    private ReadbackRing readback;
    private GpuTimer drawTimer;
    private boolean gpu;
    private boolean tint;
    private boolean wire;
    private boolean freeze;
    private float budget;
    private int clustersNow;
    private long trianglesNow;
    private final int[] perLevel = new int[LEVEL_COLORS];
    private double selectMs;
    private double verifiedClusters = -1.0;
    private int selectSeries;
    private int clusterSeries;
    private int triangleSeries;
    private int cullGpuSeries;
    private int drawGpuSeries;
    private GpuTimer cullGpuTimer;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public ClusterLodDemo(List<String> args) {
        this.options = ClusterOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        System.out.printf(Locale.ROOT, "building the rock of %,d triangles and its cluster hierarchy ...%n", 20 * (int) Math.pow(4, options.detail()));
        scene = new ClusterScene(options.detail());
        hierarchy = scene.hierarchy();
        clusterCount = hierarchy.clusterCount();
        System.out.printf(Locale.ROOT, "%,d triangles, %,d clusters in %d levels, %,d pool vertices, built in %,d ms%n", scene.triangles(), clusterCount, hierarchy.levelCount(),
                scene.positions().length / 3, scene.buildMillis());
        budget = options.budget();
        gpu = options.gpu();
        selected = new int[clusterCount];
        clusters = scene.writeClusters(ctx.arena());
        viewSegment = ctx.arena().allocate(ClusterCullViewGpu.SIZE, 16);
        long stride = DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false);
        cpuCommands = ctx.arena().allocate(stride * clusterCount, 16);
        commands = new DrawCommandBuffer(cpuCommands, DrawCommandBuffer.Kind.ELEMENTS, false);
        commandBytes = cpuCommands.asByteBuffer();

        vertexBuffer = glCreateBuffers();
        glNamedBufferStorage(vertexBuffer, scene.positions(), 0);
        indexBuffer = glCreateBuffers();
        glNamedBufferStorage(indexBuffer, scene.indices(), 0);
        int[] ids = new int[clusterCount];
        float[] levels = new float[clusterCount];
        for (int c = 0; c < clusterCount; c++) {
            ids[c] = c;
            levels[c] = hierarchy.level(c);
        }
        idBuffer = glCreateBuffers();
        glNamedBufferStorage(idBuffer, ids, 0);
        levelBuffer = glCreateBuffers();
        glNamedBufferStorage(levelBuffer, levels, 0);
        clusterBuffer = glCreateBuffers();
        glNamedBufferStorage(clusterBuffer, clusters.asByteBuffer(), 0);
        viewBuffer = glCreateBuffers();
        glNamedBufferStorage(viewBuffer, ClusterCullViewGpu.SIZE, GL_DYNAMIC_STORAGE_BIT);
        commandBuffer = glCreateBuffers();
        glNamedBufferStorage(commandBuffer, stride * clusterCount, GL_DYNAMIC_STORAGE_BIT);
        counterBuffer = glCreateBuffers();
        glNamedBufferStorage(counterBuffer, 8, GL_DYNAMIC_STORAGE_BIT);

        // a pyramid of one texel at the far depth: nothing is occluded, so only the level, the frustum and the cone decide
        dummyHzb = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(dummyHzb, 1, GL_R32F, 1, 1);
        glTextureParameteri(dummyHzb, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(dummyHzb, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureSubImage2D(dummyHzb, 0, 0, 0, 1, 1, GL_RED, GL_FLOAT, new float[] {1f});

        vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(vao, 0, vertexBuffer, 0, 3 * Float.BYTES);
        glVertexArrayElementBuffer(vao, indexBuffer);
        glEnableVertexArrayAttrib(vao, 0);
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 0, 0);
        glVertexArrayVertexBuffer(vao, 1, idBuffer, 0, Integer.BYTES);
        glVertexArrayBindingDivisor(vao, 1, 1);
        glEnableVertexArrayAttrib(vao, 1);
        glVertexArrayAttribIFormat(vao, 1, 1, GL_UNSIGNED_INT, 0);
        glVertexArrayAttribBinding(vao, 1, 1);
        glVertexArrayVertexBuffer(vao, 2, levelBuffer, 0, Float.BYTES);
        glVertexArrayBindingDivisor(vao, 2, 1);
        glEnableVertexArrayAttrib(vao, 2);
        glVertexArrayAttribFormat(vao, 2, 1, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 2, 2);

        drawProgram = Gl.program("""
                #version 450 core
                layout(location = 0) in vec3 position;
                layout(location = 1) in uint clusterId;
                layout(location = 2) in float level;
                uniform mat4 viewProjection;
                out vec3 vWorld;
                flat out uint vId;
                flat out float vLevel;
                void main() {
                    vWorld = position;
                    vId = clusterId;
                    vLevel = level;
                    gl_Position = viewProjection * vec4(position, 1.0);
                }
                """, """
                #version 450 core
                in vec3 vWorld;
                flat in uint vId;
                flat in float vLevel;
                uniform vec3 eye;
                uniform int tintLevels;
                out vec4 color;
                uint hash(uint x) {
                    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
                    return x;
                }
                void main() {
                    vec3 n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
                    if (dot(n, eye - vWorld) < 0.0) n = -n;
                    uint h = hash(vId);
                    vec3 tint = vec3(float(h & 255u), float((h >> 8) & 255u), float((h >> 16) & 255u)) / 255.0;
                    vec3 albedo = mix(vec3(0.62, 0.58, 0.52), tint, 0.5);
                    if (tintLevels == 1) {
                        float t = clamp(vLevel / 7.0, 0.0, 1.0);
                        albedo = mix(vec3(0.15, 0.85, 0.25), vec3(0.9, 0.15, 0.2), t);
                    }
                    float key = max(dot(n, normalize(vec3(0.5, 0.7, 0.45))), 0.0);
                    float fill = max(dot(n, normalize(vec3(-0.6, 0.1, -0.4))), 0.0);
                    color = vec4(albedo * (0.2 + 0.8 * key + 0.2 * fill), 1.0);
                }
                """);
        viewProjectionLocation = glGetUniformLocation(drawProgram, "viewProjection");
        eyeLocation = glGetUniformLocation(drawProgram, "eye");
        tintLocation = glGetUniformLocation(drawProgram, "tintLevels");
        computeProgram = Gl.computeProgram(GpuCullGlsl.clusterShader(64));

        orbit = new OrbitCamera(new Vec3f(0f, 0f, 0f), 0.6f, 0.35f, scene.radius() * 4f, FOVY, 0.02f, 400f);
        readback = new ReadbackRing(4, 2);
        cullGpuTimer = new GpuTimer();
        drawTimer = new GpuTimer();

        Stats stats = ctx.stats();
        selectSeries = stats.timer("CPU cut", "ClusterHierarchy.select over every cluster");
        clusterSeries = stats.series("clusters drawn", "", 0, "of " + clusterCount + " in the hierarchy");
        triangleSeries = stats.series("triangles drawn", "", 0, "of " + scene.triangles() + " in the rock (CPU cut only)");
        cullGpuSeries = stats.timer("GPU cut", "the cluster shader: level, frustum and cone tests, one command per survivor");
        drawGpuSeries = stats.timer("GPU draw", "the multi-draw of the chosen clusters");
        ctx.clearColor(0.1f, 0.12f, 0.16f);
        if (options.verify()) {
            verifyAgainstReference();
        }
    }

    /**
     * Writes the view of a camera and clears the counters and the commands, runs the cluster shader
     * and leaves its commands in the command buffer, ready to be drawn.
     */
    private void dispatchCull(Cameraf cullCamera, int height) {
        scene.writeView(viewSegment, cullCamera, height, budget, 1, 1, 1);
        glNamedBufferSubData(viewBuffer, 0, viewSegment.asByteBuffer());
        glClearNamedBufferData(counterBuffer, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
        glClearNamedBufferData(commandBuffer, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
        glUseProgram(computeProgram);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, viewBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, clusterBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, commandBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, counterBuffer);
        glBindTextureUnit(0, dummyHzb);
        glDispatchCompute((clusterCount + 63) / 64, 1, 1);
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
    }

    @Override
    public void update(FrameInfo frame) {
        if (frame.benchmark()) {
            double t = frame.frame() / 240.0 * 2.0 * Math.PI;
            float u = (float) (0.5 - 0.5 * Math.cos(t));
            float dist = scene.radius() * (1.6f + 28f * u * u);
            orbit.place(0.6f + frame.frame() * 0.006f, 0.35f, dist);
        } else {
            orbit.update(frame);
            var in = frame.input();
            if (in.pressed(GLFW_KEY_G)) {
                gpu = !gpu;
            }
            if (in.pressed(GLFW_KEY_L)) {
                tint = !tint;
            }
            if (in.pressed(GLFW_KEY_T)) {
                wire = !wire;
            }
            if (in.pressed(GLFW_KEY_LEFT_BRACKET)) {
                budget = Math.max(0.1f, budget / 1.4f);
            }
            if (in.pressed(GLFW_KEY_RIGHT_BRACKET)) {
                budget = Math.min(200f, budget * 1.4f);
            }
            if (in.pressed(GLFW_KEY_X)) {
                freeze = !freeze;
                frozen = freeze ? camera : null;
            }
        }
        camera = orbit.camera(frame.aspect());
        Cameraf cullCamera = frozen != null ? frozen : camera;
        Stats stats = ctx.stats();
        if (!gpu) {
            Vec3f eye = cullCamera.position();
            float scale = frame.height() / (2f * (float) Math.tan(cullCamera.fovy() * 0.5f));
            long t0 = System.nanoTime();
            int n = hierarchy.select(eye.x(), eye.y(), eye.z(), scale, budget, selected);
            long t1 = System.nanoTime();
            selectMs = (t1 - t0) / 1e6;
            commands.clear();
            java.util.Arrays.fill(perLevel, 0);
            trianglesNow = 0;
            for (int i = 0; i < n; i++) {
                int c = selected[i];
                commands.addElements(hierarchy.triangleCount(c) * 3, 1, scene.firstIndex(c), 0, c);
                trianglesNow += hierarchy.triangleCount(c);
                perLevel[Math.min(hierarchy.level(c), LEVEL_COLORS - 1)]++;
            }
            clustersNow = n;
            glNamedBufferSubData(commandBuffer, 0, commandBytes.limit(n * 20).position(0));
            commandBytes.clear();
            stats.recordNanos(selectSeries, t1 - t0);
            stats.record(triangleSeries, trianglesNow);
        }
    }

    @Override
    public void render(FrameInfo frame) {
        Cameraf cullCamera = frozen != null ? frozen : camera;
        int drawCount;
        if (gpu) {
            cullGpuTimer.begin();
            dispatchCull(cullCamera, frame.height());
            cullGpuTimer.end();
            drawCount = clusterCount;
            readback.begin();
            readback.copy(counterBuffer, 0, 0, 2);
            readback.end();
            readback.poll();
            clustersNow = readback.latest(0);
            long c = cullGpuTimer.poll();
            if (c >= 0) {
                ctx.stats().recordNanos(cullGpuSeries, c);
            }
        } else {
            drawCount = clustersNow;
        }
        drawTimer.begin();
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        glUseProgram(drawProgram);
        Gl.uniform(drawProgram, viewProjectionLocation, camera.viewProjection());
        Vec3f e = camera.position();
        glProgramUniform3f(drawProgram, eyeLocation, e.x(), e.y(), e.z());
        glProgramUniform1i(drawProgram, tintLocation, tint ? 1 : 0);
        glBindVertexArray(vao);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L, drawCount, 20);
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }
        drawTimer.end();
        long d = drawTimer.poll();
        if (d >= 0) {
            ctx.stats().recordNanos(drawGpuSeries, d);
        }
        ctx.stats().record(clusterSeries, clustersNow);
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.line(String.format(Locale.ROOT, "%s | error budget %.2f px | %,d of %,d clusters drawn%s", gpu ? "cut chosen by the compute shader" : "cut chosen on the CPU (ClusterHierarchy.select)", budget, clustersNow,
                clusterCount, frozen != null ? " | camera of the cut frozen (X)" : ""));
        if (!gpu) {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%,d of %,d triangles (%.1f%%) | select %.3f ms | per level:", trianglesNow, scene.triangles(), 100.0 * trianglesNow / scene.triangles(), selectMs));
            for (int l = 0; l < hierarchy.levelCount() && l < LEVEL_COLORS; l++) {
                sb.append(String.format(Locale.ROOT, " L%d %d", l, perLevel[l]));
            }
            hud.line(sb.toString());
        } else {
            hud.line("the GPU path reports the clusters it chose two or three frames late (a fenced copy of the counter)");
        }
        if (verifiedClusters >= 0.0) {
            hud.line(String.format(Locale.ROOT, "shader against the library's CPU reference at four distances: %,.0f clusters compared, identical", verifiedClusters));
        }
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "the rock has %,d triangles in %,d clusters and %d levels; hierarchy built in %,d ms; error budget %.2f px; %s", scene.triangles(), clusterCount,
                hierarchy.levelCount(), scene.buildMillis(), budget, gpu ? "cut on the GPU" : "cut on the CPU"));
        if (verifiedClusters >= 0.0) {
            stats.note(String.format(Locale.ROOT, "the shader chose the same clusters as the CPU reference at four distances (%,.0f clusters compared)", verifiedClusters));
        }
    }

    /**
     * Runs the cluster shader at four distances and compares the clusters in its commands with the
     * clusters in the commands of the library's CPU reference of the same shader.
     *
     * @throws IllegalStateException if the two sets differ
     */
    private void verifyAgainstReference() {
        long stride = DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false);
        MemorySegment refSegment = ctx.arena().allocate(stride * clusterCount, 16);
        DrawCommandBuffer reference = new DrawCommandBuffer(refSegment, DrawCommandBuffer.Kind.ELEMENTS, false);
        ByteBuffer back = MemoryUtil.memAlloc((int) (stride * clusterCount)).order(ByteOrder.nativeOrder());
        ByteBuffer counters = MemoryUtil.memAlloc(8).order(ByteOrder.nativeOrder());
        double compared = 0;
        try {
            for (float factor : new float[] {1.5f, 3f, 6f, 20f}) {
                Cameraf cam = Cameraf.lookingAt(new Vec3f(0f, scene.radius() * 0.3f * factor * 0.2f, scene.radius() * factor), Vec3f.ZERO, Vec3f.UNIT_Y, FOVY, 16f / 9f, 0.02f, 400f,
                        vmath.geo.DepthRange.NEGATIVE_ONE_TO_ONE);
                dispatchCull(cam, 900);
                back.clear();
                glGetNamedBufferSubData(commandBuffer, 0, back);
                counters.clear();
                glGetNamedBufferSubData(counterBuffer, 0, counters);
                int gpuCount = counters.getInt(0), overflow = counters.getInt(4);
                ClusterCullReference.Counters ref = new ClusterCullReference.Counters();
                ref.reset();
                ClusterCullReference.cull(viewSegment, clusters, null, reference, ref);
                boolean[] fromGpu = new boolean[clusterCount], fromCpu = new boolean[clusterCount];
                for (int i = 0; i < gpuCount; i++) {
                    fromGpu[back.getInt(i * 20 + 16)] = true;
                }
                for (int i = 0; i < reference.count(); i++) {
                    fromCpu[reference.baseInstance(i)] = true;
                }
                int differ = 0;
                for (int c = 0; c < clusterCount; c++) {
                    if (fromGpu[c] != fromCpu[c]) {
                        differ++;
                    }
                }
                compared += clusterCount;
                System.out.printf(Locale.ROOT, "distance %.1f radii: shader %,d clusters, CPU reference %,d (level choice %,d, in the frustum %,d, front facing %,d), %d differ, %d overflow%n", factor, gpuCount,
                        reference.count(), ref.lodSelected, ref.inFrustum, ref.inFrustum - ref.backFacing, differ, overflow);
                if (differ > 0 || overflow > 0 || gpuCount != reference.count()) {
                    throw new IllegalStateException("the cluster shader chose " + gpuCount + " clusters and the CPU reference " + reference.count() + " at " + factor + " radii; " + differ
                            + " clusters differ");
                }
            }
        } finally {
            MemoryUtil.memFree(back);
            MemoryUtil.memFree(counters);
        }
        verifiedClusters = compared;
    }

    @Override
    public void dispose() {
        readback.dispose();
        cullGpuTimer.dispose();
        drawTimer.dispose();
        glDeleteVertexArrays(vao);
        for (int b : new int[] {vertexBuffer, indexBuffer, idBuffer, levelBuffer, clusterBuffer, viewBuffer, commandBuffer, counterBuffer}) {
            glDeleteBuffers(b);
        }
        glDeleteTextures(dummyHzb);
        glDeleteProgram(drawProgram);
        glDeleteProgram(computeProgram);
    }
}
