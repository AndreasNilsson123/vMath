package vmath.samples.demos.terrain;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_N;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_U;
import static org.lwjgl.opengl.GL45.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FILL;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL45.GL_LINE;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.glBindBuffer;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glMultiDrawElementsIndirect;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glPolygonMode;
import static org.lwjgl.opengl.GL45.glProgramUniform1i;
import static org.lwjgl.opengl.GL45.glProgramUniform3f;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayBindingDivisor;
import static org.lwjgl.opengl.GL45.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.gl.DrawCommandBuffer;
import vmath.mesh.Mesh;
import vmath.mesh.MeshLod;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FlyCamera;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Gl;
import vmath.samples.framework.Hud;
import vmath.samples.framework.Stats;
import vmath.samples.framework.Warmup;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernels;
import vmath.spatial.LodSelector;

/**
 * A terrain of 256 chunks flown over on a camera rail: fractal noise for the heights, a chain of
 * levels of detail for every chunk, and the level of each visible chunk chosen every frame from its
 * size on the screen.
 *
 * <p>The pieces of the library: {@code Noise.fbm2} for the height ({@link Terrain}),
 * {@code MeshLod.build} for the chains with the border locked so that neighbours at different levels
 * do not crack, {@code LodSelector} with hysteresis for the choice (its thresholds come from the
 * mean error of each level and a pixel budget that {@code [} and {@code ]} change), the frustum
 * kernel for the chunks in view, {@code DrawCommandBuffer} and {@code glMultiDrawElementsIndirect}
 * for one draw call of all the chunks, and {@code Curves.catmullRom} with {@code ArcLengthTable}
 * for the rail ({@link Rail}). {@code U} moves the camera by equal steps of the spline parameter
 * instead of equal steps of distance, which shows what the arc-length table is for.
 *
 * <p>{@code L} tints the chunks by their level, {@code N} draws everything in view at full detail
 * for comparison, {@code T} draws the wireframe, and {@code F} leaves the rail for a free camera.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context; the
 * chains are built at the start on the common fork-join pool.
 */
public final class TerrainDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("terrain", "Terrain with levels of detail on a camera rail",
            "A noise terrain of 256 chunks, each with a chain of levels of detail chosen by screen size, flown at constant speed along a spline.",
            List.of("geometry", "scale"), 8192, List.of("--chunks", "6", "--cells", "24"),
            "F: free camera (W A S D, Shift) | [ ]: error budget | L: tint by level | N: no LOD | U: parameter speed | T: wireframe");

    private static final float CHUNK_SIZE = 256f;
    private static final float SPEED = 70f;
    private static final int LEVELS = 5;
    private static final float RATIO = 0.3f;
    private static final float FOVY = 1.0f;
    private static final float FAR = 9000f;

    private final TerrainOptions options;
    private Terrain terrain;
    private Rail rail;
    private LodSelector selector;
    private BoundsArray bounds;
    private VisibilitySet visible;
    private CullPipeline pipeline;
    private FlyCamera fly;
    private DemoContext ctx;
    private int levels;
    private int[] indexCount;
    private int[] firstIndex;
    private int[] baseVertex;
    private float[] meanError;
    private byte[] chosen;
    private float[] fadeBuffer;
    private DrawCommandBuffer commands;
    private ByteBuffer commandBytes;
    private FloatBuffer levelStaging;
    private int commandBuffer;
    private int levelBuffer;
    private int vertexBuffer;
    private int indexBuffer;
    private int vao;
    private int program;
    private int viewProjectionLocation;
    private int eyeLocation;
    private int tintLocation;
    private Cameraf camera;
    private final float[] position = new float[3];
    private final float[] ahead = new float[3];
    private final float[] previous = new float[3];
    private boolean havePrevious;
    private boolean free;
    private boolean parameterSpeed;
    private boolean tint;
    private boolean noLod;
    private boolean wire;
    private float budget;
    private double distance;
    private double speedMin = Double.MAX_VALUE;
    private double speedMax;
    private double speedNow;
    private double runMin = Double.MAX_VALUE;
    private double runMax;
    private double speedWindowStart;
    private int drawn;
    private long trianglesDrawn;
    private long trianglesFull;
    private final int[] perLevel = new int[LEVELS];
    private double cullMs;
    private double selectMs;
    private int cullSeries;
    private int selectSeries;
    private int buildSeries;
    private int chunkSeries;
    private int triangleSeries;
    private int fullSeries;
    private int speedSeries;
    private long buildChainsMs;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public TerrainDemo(List<String> args) {
        this.options = TerrainOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        long t0 = System.nanoTime();
        terrain = new Terrain(options.chunks(), options.cells(), CHUNK_SIZE);
        terrain.buildChains(LEVELS, RATIO, 32);
        buildChainsMs = (System.nanoTime() - t0) / 1_000_000L;
        levels = terrain.levels();
        bounds = terrain.bounds();
        System.out.printf(Locale.ROOT, "built %d chunks and their chains of %d levels in %d ms (%.1f ms per chunk on the pool)%n", terrain.chunkCount(), levels, buildChainsMs,
                buildChainsMs / (double) terrain.chunkCount());
        meanError = terrain.meanErrors();
        budget = options.budget();
        noLod = options.noLod();
        free = options.free();
        parameterSpeed = options.parameterSpeed();
        rebuildSelector();
        packMeshes();

        visible = new VisibilitySet(terrain.chunkCount());
        chosen = new byte[terrain.chunkCount()];
        fadeBuffer = new float[terrain.chunkCount()];
        // a few hundred boxes: the SIMD kernel is slower than the scalar one here and allocates per call (docs/technical-debt.md TD-32)
        pipeline = CullPipeline.of(new CullStages.Frustum(FrustumKernels.scalar()));
        long stride = DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false);
        MemorySegment segment = ctx.arena().allocate(stride * terrain.chunkCount(), 16);
        commandBytes = segment.asByteBuffer();
        commands = new DrawCommandBuffer(segment, DrawCommandBuffer.Kind.ELEMENTS, false);
        commandBuffer = glCreateBuffers();
        glNamedBufferStorage(commandBuffer, segment.byteSize(), GL_DYNAMIC_STORAGE_BIT);
        levelBuffer = glCreateBuffers();
        glNamedBufferStorage(levelBuffer, (long) terrain.chunkCount() * Float.BYTES, GL_DYNAMIC_STORAGE_BIT);
        levelStaging = MemoryUtil.memAllocFloat(terrain.chunkCount());
        glVertexArrayVertexBuffer(vao, 1, levelBuffer, 0, Float.BYTES);
        glVertexArrayBindingDivisor(vao, 1, 1);
        glEnableVertexArrayAttrib(vao, 1);
        glVertexArrayAttribFormat(vao, 1, 1, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 1, 1);

        program = Gl.program("""
                #version 450 core
                layout(location = 0) in vec3 position;
                layout(location = 1) in float level;
                uniform mat4 viewProjection;
                out vec3 vWorld;
                out float vLevel;
                void main() {
                    vWorld = position;
                    vLevel = level;
                    gl_Position = viewProjection * vec4(position, 1.0);
                }
                """, """
                #version 450 core
                in vec3 vWorld;
                in float vLevel;
                uniform vec3 eye;
                uniform int tintLevels;
                out vec4 color;
                void main() {
                    vec3 n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
                    if (n.y < 0.0) n = -n;
                    float slope = 1.0 - n.y;
                    vec3 grass = vec3(0.24, 0.38, 0.16), rock = vec3(0.42, 0.39, 0.35), snow = vec3(0.92, 0.94, 0.97);
                    vec3 albedo = mix(grass, rock, smoothstep(0.12, 0.35, slope));
                    albedo = mix(albedo, snow, smoothstep(210.0, 270.0, vWorld.y) * smoothstep(0.5, 0.15, slope));
                    if (tintLevels == 1) {
                        vec3 tints[5] = vec3[5](vec3(0.2, 0.9, 0.2), vec3(0.9, 0.9, 0.2), vec3(0.95, 0.55, 0.15), vec3(0.9, 0.2, 0.2), vec3(0.7, 0.2, 0.9));
                        albedo = mix(albedo, tints[clamp(int(vLevel + 0.5), 0, 4)], 0.55);
                    }
                    float diffuse = max(dot(n, normalize(vec3(0.5, 0.7, 0.35))), 0.0);
                    vec3 lit = albedo * (0.25 + 0.8 * diffuse);
                    float fog = 1.0 - exp(-length(vWorld - eye) * 0.00018);
                    color = vec4(mix(lit, vec3(0.62, 0.74, 0.88), fog), 1.0);
                }
                """);
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        eyeLocation = glGetUniformLocation(program, "eye");
        tintLocation = glGetUniformLocation(program, "tintLevels");

        rail = new Rail(12, terrain.chunks() * CHUNK_SIZE * 0.32f, 90f);
        fly = new FlyCamera(new Vec3f(0f, 400f, 0f), 0f, -0.3f, FOVY, 1f, FAR, 150f, 700f);
        System.out.printf(Locale.ROOT, "rail: %.0f m long, %d spline segments%n", rail.length(), rail.segments());

        Stats stats = ctx.stats();
        cullSeries = stats.timer("frustum", "the frustum kernel over every chunk");
        selectSeries = stats.timer("select levels", "LodSelector over the chunks in view");
        buildSeries = stats.timer("draw commands", "one indirect command per visible chunk");
        chunkSeries = stats.series("chunks drawn", "", 1, "of " + terrain.chunkCount());
        triangleSeries = stats.series("triangles drawn", "", 0, "with the chosen levels");
        fullSeries = stats.series("triangles at full detail", "", 0, "what the same chunks would have at level 0");
        speedSeries = stats.series("camera speed", "m/s", 2, "measured between frames");
        ctx.clearColor(0.62f, 0.74f, 0.88f);
        prewarm();
    }

    /**
     * Makes the selector from the mean error of each level and the pixel budget: the thresholds are
     * the screen sizes, in pixels, at which a level stops being good enough.
     */
    private void rebuildSelector() {
        MeshLod.Chain mean = new MeshLod.Chain(java.util.Arrays.copyOf(terrain.chain(0).levels(), levels), meanError);
        float[] thresholds = mean.selectorThresholds(terrain.boundingRadius(), budget);
        float[] used = java.util.Arrays.copyOf(thresholds, levels - 1);
        selector = new LodSelector(used, 0f, 0.1f, 0.2f);
    }

    /**
     * Puts the vertices and indices of every level of every chunk into one vertex buffer and one
     * index buffer and remembers where each one starts.
     */
    private void packMeshes() {
        int chunks = terrain.chunkCount();
        indexCount = new int[chunks * levels];
        firstIndex = new int[chunks * levels];
        baseVertex = new int[chunks * levels];
        long vertices = 0, indices = 0;
        for (int c = 0; c < chunks; c++) {
            for (int l = 0; l < levels; l++) {
                Mesh m = terrain.chain(c).levels()[l];
                vertices += m.vertexCount();
                indices += m.indexCount();
            }
        }
        FloatBuffer v = MemoryUtil.memAllocFloat((int) vertices * 3);
        IntBuffer ix = MemoryUtil.memAllocInt((int) indices);
        int vertexAt = 0, indexAt = 0;
        long[] total = new long[levels];
        for (int c = 0; c < chunks; c++) {
            for (int l = 0; l < levels; l++) {
                Mesh m = terrain.chain(c).levels()[l];
                int slot = c * levels + l;
                baseVertex[slot] = vertexAt;
                firstIndex[slot] = indexAt;
                indexCount[slot] = m.indexCount();
                v.put(m.positions(), 0, m.vertexCount() * 3);
                ix.put(m.indices(), 0, m.indexCount());
                vertexAt += m.vertexCount();
                indexAt += m.indexCount();
                total[l] += m.triangleCount();
            }
        }
        v.flip();
        ix.flip();
        vertexBuffer = glCreateBuffers();
        glNamedBufferStorage(vertexBuffer, v, 0);
        indexBuffer = glCreateBuffers();
        glNamedBufferStorage(indexBuffer, ix, 0);
        MemoryUtil.memFree(v);
        MemoryUtil.memFree(ix);
        vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(vao, 0, vertexBuffer, 0, 3 * Float.BYTES);
        glVertexArrayElementBuffer(vao, indexBuffer);
        glEnableVertexArrayAttrib(vao, 0);
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 0, 0);
        StringBuilder line = new StringBuilder("triangles per level over all chunks:");
        for (int l = 0; l < levels; l++) {
            line.append(String.format(Locale.ROOT, " L%d %,d", l, total[l]));
        }
        System.out.println(line);
        System.out.printf(Locale.ROOT, "uploaded %,d vertices and %,d indices%n", vertices, indices);
    }

    /**
     * Runs the culling and the selection before the first frame until the JIT has compiled them
     * and they have stopped allocating.
     */
    private void prewarm() {
        Cameraf start = Cameraf.lookingAt(new Vec3f(0f, 300f, 0f), new Vec3f(100f, 200f, 100f), Vec3f.UNIT_Y, FOVY, 16f / 9f, 1f, FAR, DepthRange.NEGATIVE_ONE_TO_ONE);
        Warmup.untilQuiet(() -> chooseLevels(start, 900), 3000);
    }

    @Override
    public void update(FrameInfo frame) {
        if (frame.benchmark()) {
            distance = frame.time() * SPEED;
        } else {
            var in = frame.input();
            if (in.pressed(GLFW_KEY_F)) {
                free = !free;
                if (free) {
                    Vec3f f = camera.forward();
                    fly.place(position[0], position[1], position[2], (float) Math.atan2(f.x(), -f.z()), (float) Math.asin(Math.max(-1f, Math.min(1f, f.y()))));
                }
            }
            if (in.pressed(GLFW_KEY_U)) {
                parameterSpeed = !parameterSpeed;
            }
            if (in.pressed(GLFW_KEY_L)) {
                tint = !tint;
            }
            if (in.pressed(GLFW_KEY_N)) {
                noLod = !noLod;
            }
            if (in.pressed(GLFW_KEY_T)) {
                wire = !wire;
            }
            if (in.pressed(GLFW_KEY_LEFT_BRACKET)) {
                budget = Math.max(0.25f, budget / 1.3f);
                rebuildSelector();
            }
            if (in.pressed(GLFW_KEY_RIGHT_BRACKET)) {
                budget = Math.min(64f, budget * 1.3f);
                rebuildSelector();
            }
            distance += frame.dt() * SPEED;
        }

        if (free && !frame.benchmark()) {
            fly.update(frame);
            camera = fly.camera(frame.aspect());
            Vec3f p = camera.position();
            position[0] = p.x();
            position[1] = p.y();
            position[2] = p.z();
        } else {
            if (parameterSpeed) {
                double u = distance * rail.segments() / rail.length();
                rail.positionAtParameter(u, position);
                rail.positionAtParameter(u + 40.0 * rail.segments() / rail.length(), ahead);
            } else {
                rail.positionAtDistance(distance, position);
                rail.positionAtDistance(distance + 40.0, ahead);
            }
            camera = Cameraf.lookingAt(new Vec3f(position[0], position[1], position[2]), new Vec3f(ahead[0], ahead[1] - 25f, ahead[2]), Vec3f.UNIT_Y, FOVY, frame.aspect(), 1f, FAR,
                    DepthRange.NEGATIVE_ONE_TO_ONE);
        }
        measureSpeed(frame);

        long t0 = System.nanoTime();
        chooseLevels(camera, frame.height());
        long t1 = System.nanoTime();
        buildCommands();
        long t2 = System.nanoTime();
        glNamedBufferSubData(commandBuffer, 0, commandBytes);
        glNamedBufferSubData(levelBuffer, 0, levelStaging);
        Stats stats = ctx.stats();
        stats.recordNanos(cullSeries, (long) (cullMs * 1e6));
        stats.recordNanos(selectSeries, (long) (selectMs * 1e6));
        stats.recordNanos(buildSeries, t2 - t1);
        stats.record(chunkSeries, drawn);
        stats.record(triangleSeries, trianglesDrawn);
        stats.record(fullSeries, trianglesFull);
        stats.record(speedSeries, speedNow);
    }

    private void measureSpeed(FrameInfo frame) {
        if (havePrevious && frame.dt() > 0f) {
            float dx = position[0] - previous[0], dy = position[1] - previous[1], dz = position[2] - previous[2];
            speedNow = Math.sqrt(dx * dx + dy * dy + dz * dz) / frame.dt();
            if (speedNow < 10 * SPEED) {
                speedMin = Math.min(speedMin, speedNow);
                speedMax = Math.max(speedMax, speedNow);
                if (frame.frame() > 2) {
                    runMin = Math.min(runMin, speedNow);
                    runMax = Math.max(runMax, speedNow);
                }
            }
        }
        if (frame.time() - speedWindowStart > 3.0) {
            speedWindowStart = frame.time();
            speedMin = Double.MAX_VALUE;
            speedMax = 0.0;
        }
        System.arraycopy(position, 0, previous, 0, 3);
        havePrevious = true;
    }

    /**
     * Culls the chunks against the frustum and picks the level of every chunk in view.
     *
     * @return the number of chunks in the frustum
     */
    private int chooseLevels(Cameraf cam, int height) {
        long t0 = System.nanoTime();
        CullContext cull = CullContext.perspective(cam.frustum(), cam.position(), cam.fovy(), height);
        int n = pipeline.run(cull, bounds, visible);
        long t1 = System.nanoTime();
        if (noLod) {
            java.util.Arrays.fill(chosen, (byte) 0);
        } else {
            selector.select(cull, bounds, visible, chosen, fadeBuffer, 1f);
        }
        long t2 = System.nanoTime();
        cullMs = (t1 - t0) / 1e6;
        selectMs = (t2 - t1) / 1e6;
        return n;
    }

    /**
     * Writes one indirect command for every visible chunk at its chosen level, and the level of
     * each as the per-draw value that the shader uses for the tint.
     */
    private void buildCommands() {
        commands.clear();
        levelStaging.clear();
        drawn = 0;
        trianglesDrawn = 0;
        trianglesFull = 0;
        java.util.Arrays.fill(perLevel, 0);
        for (int c = visible.nextSetBit(0); c >= 0 && c < terrain.chunkCount(); c = visible.nextSetBit(c + 1)) {
            int level = chosen[c];
            if (level < 0) {
                continue;
            }
            level = Math.min(level, levels - 1);
            int slot = c * levels + level;
            commands.addElements(indexCount[slot], 1, firstIndex[slot], baseVertex[slot], drawn);
            levelStaging.put(level);
            trianglesDrawn += indexCount[slot] / 3;
            trianglesFull += indexCount[c * levels] / 3;
            perLevel[Math.min(level, LEVELS - 1)]++;
            drawn++;
        }
        levelStaging.flip();
    }

    @Override
    public void render(FrameInfo frame) {
        ctx.gpu().begin();
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        glUseProgram(program);
        Gl.uniform(program, viewProjectionLocation, camera.viewProjection());
        glProgramUniform3f(program, eyeLocation, position[0], position[1], position[2]);
        glProgramUniform1i(program, tintLocation, tint ? 1 : 0);
        glBindVertexArray(vao);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L, drawn, (int) commands.stride());
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }
        ctx.gpu().end();
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.line(String.format(Locale.ROOT, "%d of %d chunks drawn | %,d triangles (%,d at full detail: %.0f%%) | error budget %.1f px%s", drawn, terrain.chunkCount(), trianglesDrawn,
                trianglesFull, trianglesFull == 0 ? 0.0 : 100.0 * trianglesDrawn / trianglesFull, budget, noLod ? " | LOD off" : ""));
        StringBuilder sb = new StringBuilder("chunks per level:");
        for (int l = 0; l < levels; l++) {
            sb.append(String.format(Locale.ROOT, " L%d %d", l, perLevel[l]));
        }
        hud.line(sb.toString());
        hud.line(String.format(Locale.ROOT, "frustum %.3f ms | select %.3f ms", cullMs, selectMs));
        hud.line(String.format(Locale.ROOT, "%s | speed %.1f m/s (last 3 s: %.1f to %.1f)", free ? "free camera" : parameterSpeed ? "rail, equal steps of the parameter" : "rail, equal steps of arc length",
                speedNow, speedMin == Double.MAX_VALUE ? 0.0 : speedMin, speedMax));
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "%d chunks, %d levels each (built in %d ms), full detail %d x %d cells per chunk, error budget %.1f px", terrain.chunkCount(), levels, buildChainsMs,
                options.cells(), options.cells(), budget));
        stats.note(String.format(Locale.ROOT, "rail length %.0f m; camera speed over the run from %.1f to %.1f m/s (%s)", rail.length(), runMin, runMax,
                parameterSpeed ? "equal steps of the spline parameter" : "equal steps of arc length"));
    }

    @Override
    public void dispose() {
        MemoryUtil.memFree(levelStaging);
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vertexBuffer);
        glDeleteBuffers(indexBuffer);
        glDeleteBuffers(commandBuffer);
        glDeleteBuffers(levelBuffer);
        glDeleteProgram(program);
    }
}
