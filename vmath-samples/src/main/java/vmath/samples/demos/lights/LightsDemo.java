package vmath.samples.demos.lights;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.opengl.GL45.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindBufferBase;
import static org.lwjgl.opengl.GL45.glClear;
import static org.lwjgl.opengl.GL45.glClearColor;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glProgramUniform1f;
import static org.lwjgl.opengl.GL45.glProgramUniform1i;
import static org.lwjgl.opengl.GL45.glProgramUniform1ui;
import static org.lwjgl.opengl.GL45.glUseProgram;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.lighting.ClusterGrid;
import vmath.lighting.ClusterLights;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.mesh.VertexLayout;
import vmath.samples.framework.BoxRenderer;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FlyCamera;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Gl;
import vmath.samples.framework.GpuMesh;
import vmath.samples.framework.Hud;
import vmath.samples.framework.InstanceStream;
import vmath.samples.framework.SceneTarget;
import vmath.samples.framework.Scenes;
import vmath.samples.framework.Stats;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernels;

/**
 * Thousands of point lights over a city, shaded with clustered forward lighting: the library cuts the
 * view frustum into a grid of tiles and exponential depth slices ({@code ClusterGrid}), assigns the
 * lights to the clusters that they reach ({@code ClusterLights}, on the CPU), and the fragment
 * shader finds the few lights of its own cluster with the lookup that {@code ClusterGrid.glslLookup}
 * writes and loops over only those.
 *
 * <p>The assignment is redone every frame, since both the lights and the camera move; its cost
 * grows with the number of lights and the area they cover (the library documents it as a reference
 * and an oracle, not a production path), and the demo shows it next to the GPU time. {@code M} cycles the
 * way of lighting: the clusters, a loop over every light (the comparison), and a heat map of how
 * many lights each cluster holds; {@code [} and {@code ]} halve and double the number of lights.
 * {@code --verify} renders three views at 640 by 360 both with the clusters and with the loop over
 * every light and fails if the two images differ by more than rounding.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class LightsDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("clustered-lights", "Clustered forward lighting with thousands of lights",
            "Point lights assigned to a frustum cluster grid by the library and shaded by looking up the fragment's cluster, checked against a loop over every light.",
            List.of("rendering", "scale"), 16384, List.of("--lights", "256", "--blocks", "8", "--verify"),
            "left mouse + move: look | W A S D: fly | Space, Ctrl: up, down | Shift: fast | M: clusters, every light, heat map | [ ]: fewer, more lights");

    private static final float FAR = 300f;
    private static final int MAX_CLUSTERS = 65_536;
    private static final int MAX_ASSIGNMENTS = 4_000_000;
    private static final int TILE = 64;
    private static final int SLICES = 24;
    private static final int VERIFY_WIDTH = 640;
    private static final int VERIFY_HEIGHT = 360;
    private static final int VERIFY_DIFFERENCE = 2;

    private final LightsOptions options;
    private Scenes.Blocks city;
    private BoundsArray bounds;
    private VisibilitySet visible;
    private CullPipeline pipeline;
    private InstanceStream stream;
    private GpuMesh cube;
    private LightField field;
    private ClusterGrid grid;
    private FlyCamera fly;
    private DemoContext ctx;
    private Cameraf camera;
    private int program;
    private int viewProjectionLocation;
    private int groundLocation;
    private int neutralLocation;
    private int viewLocation;
    private int modeLocation;
    private int bruteLocation;
    private int exposureLocation;
    private int heatLocation;
    private int rangesBuffer;
    private int indicesBuffer;
    private int lightsBuffer;
    private MemorySegment rangesStaging;
    private MemorySegment indicesStaging;
    private MemorySegment lightsStaging;
    private int lightCount;
    private int mode;
    private float streetX;
    private float pathLength;
    private int maxPerCluster;
    private long assignments;
    private double assignMs;
    private double uploadMs;
    private double verifiedDifference = -1.0;
    private int assignSeries;
    private int uploadSeries;
    private int pairSeries;
    private int meanSeries;
    private int maxSeries;
    private int visibleSeries;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public LightsDemo(List<String> args) {
        this.options = LightsOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        city = Scenes.blocks(options.blocks(), 20);
        bounds = city.bounds();
        visible = new VisibilitySet(bounds.size());
        pipeline = CullPipeline.of(new CullStages.Frustum(FrustumKernels.scalar()));
        stream = new InstanceStream(ctx.arena(), bounds.size(), 3);
        cube = BoxRenderer.createCube(ctx);
        lightCount = options.lights();
        mode = options.mode();
        float half = options.blocks() * Scenes.BLOCK_PITCH * 0.5f;
        field = new LightField(lightCount, half, 17);
        streetX = (options.blocks() / 2 + 1) * Scenes.BLOCK_PITCH - half;
        pathLength = 2f * half - 60f;
        fly = new FlyCamera(new Vec3f(streetX, 3f, -half + 30f), (float) Math.PI, -0.05f, 1.0f, 0.1f, FAR, 20f, 80f);

        rangesBuffer = glCreateBuffers();
        glNamedBufferStorage(rangesBuffer, (long) MAX_CLUSTERS * ClusterLights.RANGE_BYTES, GL_DYNAMIC_STORAGE_BIT);
        indicesBuffer = glCreateBuffers();
        glNamedBufferStorage(indicesBuffer, (long) MAX_ASSIGNMENTS * Integer.BYTES, GL_DYNAMIC_STORAGE_BIT);
        lightsBuffer = glCreateBuffers();
        glNamedBufferStorage(lightsBuffer, (long) 100_000 * 32, GL_DYNAMIC_STORAGE_BIT);
        rangesStaging = ctx.arena().allocate((long) MAX_CLUSTERS * ClusterLights.RANGE_BYTES, 16);
        indicesStaging = ctx.arena().allocate((long) MAX_ASSIGNMENTS * Integer.BYTES, 16);
        lightsStaging = ctx.arena().allocate((long) 100_000 * 32, 16);

        Stats stats = ctx.stats();
        assignSeries = stats.timer("assign lights", "ClusterLights.assign on the CPU: every light into the clusters it reaches");
        uploadSeries = stats.timer("upload", "the ranges, the light indices and the light records into the GPU buffers");
        pairSeries = stats.series("assignments", "", 0, "(cluster, light) pairs in the index list");
        meanSeries = stats.series("lights per cluster", "", 2, "mean over all clusters of the grid");
        maxSeries = stats.series("most lights in a cluster", "", 0, "the worst cluster");
        visibleSeries = stats.series("boxes drawn", "", 0, "of " + bounds.size());
        ctx.clearColor(0.02f, 0.02f, 0.04f);
        if (options.verify()) {
            verifyAgainstEveryLight(ctx);
        }
    }

    /**
     * Makes the grid and the program for a viewport size.
     */
    private void buildGrid(Cameraf cam, int width, int height) {
        grid = ClusterGrid.of(cam, width, height, TILE, SLICES, FAR, false);
        if (grid.clusterCount() > MAX_CLUSTERS) {
            throw new IllegalStateException("a window of " + width + " x " + height + " has " + grid.clusterCount() + " clusters, more than " + MAX_CLUSTERS);
        }
        if (program != 0) {
            glDeleteProgram(program);
        }
        program = Gl.program(LightShaders.vertex(VertexLayout.builder().position().normal().build()), LightShaders.fragment(grid));
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        groundLocation = glGetUniformLocation(program, "groundId");
        neutralLocation = glGetUniformLocation(program, "neutralBelow");
        viewLocation = glGetUniformLocation(program, "viewMatrix");
        modeLocation = glGetUniformLocation(program, "mode");
        bruteLocation = glGetUniformLocation(program, "bruteCount");
        exposureLocation = glGetUniformLocation(program, "exposure");
        heatLocation = glGetUniformLocation(program, "heatScale");
    }

    /**
     * Moves the lights, assigns them to the clusters of the view and uploads the three buffers.
     */
    private void prepare(Cameraf cam, int width, int height, double time) {
        if (grid == null || grid.viewportWidth() != width || grid.viewportHeight() != height) {
            buildGrid(cam, width, height);
        }
        long t0 = System.nanoTime();
        field.assign(time, cam.view(), grid);
        long t1 = System.nanoTime();
        ClusterLights lights = field.assignment();
        assignments = lights.totalAssignments(grid);
        if (assignments > MAX_ASSIGNMENTS) {
            throw new IllegalStateException("the lights reach " + assignments + " clusters in all, more than " + MAX_ASSIGNMENTS + ": use fewer lights");
        }
        lights.writeRanges(grid, rangesStaging, 0);
        lights.writeIndices(grid, indicesStaging, 0);
        glNamedBufferSubData(rangesBuffer, 0, rangesStaging.asSlice(0, (long) grid.clusterCount() * ClusterLights.RANGE_BYTES).asByteBuffer());
        if (assignments > 0) {
            glNamedBufferSubData(indicesBuffer, 0, indicesStaging.asSlice(0, assignments * Integer.BYTES).asByteBuffer());
        }
        float[] data = field.lightData();
        ByteBuffer lb = lightsStaging.asSlice(0, (long) lightCount * 32).asByteBuffer().order(java.nio.ByteOrder.nativeOrder());
        lb.asFloatBuffer().put(data, 0, lightCount * 8);
        glNamedBufferSubData(lightsBuffer, 0, lb);
        long t2 = System.nanoTime();
        assignMs = (t1 - t0) / 1e6;
        uploadMs = (t2 - t1) / 1e6;
        int max = 0;
        for (int c = 0; c < grid.clusterCount(); c++) {
            max = Math.max(max, lights.count(c));
        }
        maxPerCluster = max;
    }

    @Override
    public void update(FrameInfo frame) {
        fly.update(frame);
        if (frame.benchmark()) {
            float p = (frame.frame() * 0.25f) % (2f * pathLength);
            boolean forward = p < pathLength;
            float half = options.blocks() * Scenes.BLOCK_PITCH * 0.5f;
            fly.place(streetX, 3f, -half + 30f + (forward ? p : 2f * pathLength - p), (forward ? (float) Math.PI : 0f) + 0.4f * (float) Math.sin(frame.frame() * 0.012), -0.05f);
        } else {
            var in = frame.input();
            if (in.pressed(GLFW_KEY_M)) {
                mode = (mode + 1) % 3;
            }
            if (in.pressed(GLFW_KEY_LEFT_BRACKET)) {
                resizeLights(Math.max(1, lightCount / 2));
            }
            if (in.pressed(GLFW_KEY_RIGHT_BRACKET)) {
                resizeLights(Math.min(100_000, lightCount * 2));
            }
        }
        camera = fly.camera(frame.aspect());
        prepare(camera, frame.width(), frame.height(), frame.time());

        CullContext cull = CullContext.perspective(camera.frustum(), camera.position(), camera.fovy(), frame.height());
        int n = pipeline.run(cull, bounds, visible);
        stream.begin();
        stream.write(visible, bounds);

        Stats stats = ctx.stats();
        stats.recordNanos(assignSeries, (long) (assignMs * 1e6));
        stats.recordNanos(uploadSeries, (long) (uploadMs * 1e6));
        stats.record(pairSeries, assignments);
        stats.record(meanSeries, assignments / (double) grid.clusterCount());
        stats.record(maxSeries, maxPerCluster);
        stats.record(visibleSeries, n);
    }

    private void resizeLights(int count) {
        lightCount = count;
        field = new LightField(count, options.blocks() * Scenes.BLOCK_PITCH * 0.5f, 17);
    }

    /**
     * Draws the visible boxes of this frame in the current target with the given way of lighting.
     */
    private void drawScene(Cameraf cam, int shaderMode, int bruteLights) {
        glUseProgram(program);
        Gl.uniform(program, viewProjectionLocation, cam.viewProjection());
        Gl.uniform(program, viewLocation, cam.view());
        glProgramUniform1ui(program, groundLocation, bounds.size() - 1);
        glProgramUniform1ui(program, neutralLocation, city.buildings());
        glProgramUniform1i(program, modeLocation, shaderMode);
        glProgramUniform1i(program, bruteLocation, bruteLights);
        glProgramUniform1f(program, exposureLocation, 1.4f);
        glProgramUniform1f(program, heatLocation, Math.max(8f, maxPerCluster));
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, rangesBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, indicesBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, lightsBuffer);
        stream.draw(cube);
    }

    @Override
    public void render(FrameInfo frame) {
        ctx.gpu().begin();
        drawScene(camera, mode, lightCount);
        ctx.gpu().end();
        stream.end();
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        String how = mode == 0 ? "clusters" : mode == 1 ? "a loop over every light" : "heat map of lights per cluster";
        hud.line(String.format(Locale.ROOT, "%,d lights, %s | grid %d x %d x %d = %,d clusters", lightCount, how, grid.tilesX(), grid.tilesY(), grid.slices(), grid.clusterCount()));
        hud.line(String.format(Locale.ROOT, "%,d assignments (%.1f per cluster, at most %d) | assign %.2f ms | upload %.2f ms", assignments, assignments / (double) grid.clusterCount(), maxPerCluster,
                assignMs, uploadMs));
        if (verifiedDifference >= 0.0) {
            hud.line(String.format(Locale.ROOT, "clusters against every light, three views: largest difference %.0f of 255", verifiedDifference));
        }
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "%,d lights, mode %s, grid %d x %d x %d (%d-pixel tiles)", lightCount, mode == 0 ? "clusters" : mode == 1 ? "every light" : "heat map", grid.tilesX(),
                grid.tilesY(), grid.slices(), TILE));
        if (verifiedDifference >= 0.0) {
            stats.note(String.format(Locale.ROOT, "the clustered image equals the loop over every light on three views at %d x %d: largest difference %.0f of 255", VERIFY_WIDTH, VERIFY_HEIGHT,
                    verifiedDifference));
        }
    }

    /**
     * Renders three views at a small size with the clusters and with a loop over every light, reads
     * both back and compares them.
     *
     * @throws IllegalStateException if a pixel differs by more than the rounding of the sum
     */
    private void verifyAgainstEveryLight(DemoContext context) {
        SceneTarget target = new SceneTarget(VERIFY_WIDTH, VERIFY_HEIGHT);
        ByteBuffer a = MemoryUtil.memAlloc(VERIFY_WIDTH * VERIFY_HEIGHT * 4), b = MemoryUtil.memAlloc(VERIFY_WIDTH * VERIFY_HEIGHT * 4);
        float half = options.blocks() * Scenes.BLOCK_PITCH * 0.5f;
        int worst = 0;
        long differing = 0;
        try {
            for (int v = 0; v < 3; v++) {
                Vec3f eye = new Vec3f(streetX, 3f, -half + 30f + v * 40f);
                Cameraf cam = Cameraf.lookingAt(eye, new Vec3f(streetX + (v - 1) * 20f, 2f, eye.z() + 30f), Vec3f.UNIT_Y, 1.0f, (float) VERIFY_WIDTH / VERIFY_HEIGHT, 0.1f, FAR,
                        DepthRange.NEGATIVE_ONE_TO_ONE);
                buildGrid(cam, VERIFY_WIDTH, VERIFY_HEIGHT);
                prepare(cam, VERIFY_WIDTH, VERIFY_HEIGHT, 1.0 + v);
                CullContext cull = CullContext.perspective(cam.frustum(), cam.position(), cam.fovy(), VERIFY_HEIGHT);
                pipeline.run(cull, bounds, visible);
                stream.begin();
                stream.write(visible, bounds);
                target.bind();
                glClearColor(0.02f, 0.02f, 0.04f, 1f);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                drawScene(cam, 0, lightCount);
                a.clear();
                target.readColor(a);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                drawScene(cam, 1, lightCount);
                b.clear();
                target.readColor(b);
                stream.end();
                for (int i = 0; i < VERIFY_WIDTH * VERIFY_HEIGHT * 4; i++) {
                    int d = Math.abs((a.get(i) & 255) - (b.get(i) & 255));
                    worst = Math.max(worst, d);
                    if (d > VERIFY_DIFFERENCE) {
                        differing++;
                    }
                }
            }
        } finally {
            MemoryUtil.memFree(a);
            MemoryUtil.memFree(b);
            target.dispose();
            glBindFramebufferWindow();
            grid = null;
        }
        verifiedDifference = worst;
        System.out.printf(Locale.ROOT, "verified the clustered lighting against a loop over %,d lights on three views: largest difference %d of 255, %d values above %d%n", lightCount, worst, differing,
                VERIFY_DIFFERENCE);
        if (differing > 0) {
            throw new IllegalStateException(differing + " colour values of the clustered image differ by more than " + VERIFY_DIFFERENCE + " from the loop over every light (largest " + worst + ")");
        }
    }

    private static void glBindFramebufferWindow() {
        org.lwjgl.opengl.GL45.glBindFramebuffer(org.lwjgl.opengl.GL45.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void dispose() {
        stream.close();
        cube.delete();
        glDeleteProgram(program);
        glDeleteBuffers(rangesBuffer);
        glDeleteBuffers(indicesBuffer);
        glDeleteBuffers(lightsBuffer);
    }
}
