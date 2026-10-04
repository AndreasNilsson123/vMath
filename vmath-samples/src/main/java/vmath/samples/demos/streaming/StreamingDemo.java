package vmath.samples.demos.streaming;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_B;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.opengl.GL45.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL45.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL45.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL45.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL45.GL_POINTS;
import static org.lwjgl.opengl.GL45.GL_PROGRAM_POINT_SIZE;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.glBindBuffer;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glDisable;
import static org.lwjgl.opengl.GL45.glDrawArrays;
import static org.lwjgl.opengl.GL45.glEnable;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glMapNamedBufferRange;
import static org.lwjgl.opengl.GL45.glMultiDrawArraysIndirect;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glProgramUniform1f;
import static org.lwjgl.opengl.GL45.glProgramUniform1i;
import static org.lwjgl.opengl.GL45.glUnmapNamedBuffer;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribIFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.gl.DrawCommandBuffer;
import vmath.mem.PersistentBufferRing;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.GlFences;
import vmath.samples.framework.Gl;
import vmath.samples.framework.GpuTimer;
import vmath.samples.framework.Hud;
import vmath.samples.framework.Stats;
import vmath.samples.framework.Warmup;

/**
 * The memory layer under a synthetic streaming load: chunks of points are uploaded as the camera
 * moves over a large world, through a persistently mapped staging ring with one region per frame in
 * flight, into a pool in GPU memory that an allocator manages, and 200,000 more points are written
 * into the ring every frame and drawn from it.
 *
 * <p>The pieces of the library: {@code PersistentBufferRing} (the regions, the fences, the stalls
 * and the high-water mark; the fence operations are {@code glFenceSync} and friends in
 * {@code GlFences}), and for the pool {@code FreeListAllocator} (first fit or best fit, with
 * coalescing, {@code largestFree} and {@code validate}) or {@code SlabAllocator}. A chunk is written
 * into the ring ({@code allocate}), copied to the pool with {@code glCopyNamedBufferSubData} and
 * drawn from there; when the pool is full the farthest chunks are evicted. The draws of all the
 * resident chunks are one {@code glMultiDrawArraysIndirect} built with {@code DrawCommandBuffer}.
 *
 * <p>The GPU side is a filler pass whose cost the keys set ({@code [} and {@code ]}), so that the
 * ring sees a GPU that is slower than the CPU, which is when {@code beginFrame} has to wait. {@code F}
 * changes the number of regions (frames in flight), {@code P} changes the allocator of the pool (it
 * starts empty again) and {@code T} jumps to a new place now, which makes the whole neighbourhood
 * reload at once.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class StreamingDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("streaming-ring", "Streaming through a ring and a pool",
            "Chunks of points are streamed through a persistently mapped ring into a pool managed by the library's allocators, under teleports and a GPU that lags the CPU.",
            List.of("memory", "scale"), 16384, List.of("--world", "60", "--radius", "6", "--pool-mb", "6", "--region-mb", "4", "--budget-mb", "2", "--particles", "20000", "--teleport", "0.5"),
            "F: frames in flight | P: pool allocator | [ ]: GPU load | T: teleport now | B: pause the camera");

    private static final long POINT = StreamPlan.POINT_BYTES;

    private final StreamingOptions options;
    private DemoContext ctx;
    private StreamPlan plan;
    private StreamPool pool;
    private StreamPool.Kind poolKind;
    private PersistentBufferRing<Long> ring;
    private int framesInFlight;
    private int stagingBuffer;
    private int poolBuffer;
    private int commandBuffer;
    private int chunkVao;
    private int particleVao;
    private int fillVao;
    private int program;
    private int fillProgram;
    private int viewProjectionLocation;
    private int pointScaleLocation;
    private int fillLoadLocation;
    private int fillSeedLocation;
    private MemorySegment mapped;
    private long regionBytes;
    private DrawCommandBuffer commands;
    private ByteBuffer commandBytes;
    private GpuTimer gpuTimer;
    private Cameraf camera;
    private int gpuLoad;
    private boolean paused;
    private double cameraTime;
    private double teleportShift;
    private final double[] cell = new double[2];

    private long[] cellOffset;
    private int[] resident;
    private int[] residentIndex;
    private int residentCount;
    private long residentBytes;
    private int particleOffsetFrame = -1;
    private long particleOffset;
    private final double[] usedFraction = new double[4];

    private long frameStallNanos;
    private long frameUploadBytes;
    private int frameUploads;
    private int frameDeferred;
    private int frameRingFull;
    private long totalUploads;
    private long totalUploadBytes;
    private long totalEvictions;
    private long totalRingFull;
    private long totalDeferred;
    private long stallNanosTotal;
    private long poolFailsAtStart;
    private int missingNow;
    private int frameNumber;
    private long verifiedChunks;
    private long verifyFailures;
    private int verifyRounds;
    private int drawCount;

    private int stallSeries;
    private int uploadSeries;
    private int uploadBytesSeries;
    private int ringUsedSeries;
    private int residentSeries;
    private int missingSeries;
    private int evictionSeries;
    private int deferredSeries;
    private int fragSeries;
    private int gpuSeries;
    private int stalledFramesSeries;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public StreamingDemo(List<String> args) {
        this.options = StreamingOptions.parse(args);
        if ((long) options.particles() * POINT + options.budgetMb() * 1_048_576L > options.regionMb() * 1_048_576L) {
            throw new IllegalArgumentException("the particles and the upload budget do not fit in one region of the ring\n" + StreamingOptions.USAGE);
        }
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        plan = new StreamPlan(options.world(), options.radius());
        regionBytes = options.regionMb() * 1_048_576L;
        framesInFlight = options.framesInFlight();
        gpuLoad = options.gpuLoad();

        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        stagingBuffer = glCreateBuffers();
        glNamedBufferStorage(stagingBuffer, regionBytes * 4, flags);
        mapped = MemorySegment.ofBuffer(glMapNamedBufferRange(stagingBuffer, 0, regionBytes * 4, flags));
        makeRing();
        poolBuffer = glCreateBuffers();
        long poolBytes = options.poolMb() * 1_048_576L;
        glNamedBufferStorage(poolBuffer, poolBytes, 0);
        makePool(options.pool());

        cellOffset = new long[options.world() * options.world()];
        java.util.Arrays.fill(cellOffset, -1L);
        resident = new int[plan.neighbourhood() * 4 + 64];
        residentIndex = new int[cellOffset.length];
        long stride = DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ARRAYS, false);
        MemorySegment commandSegment = ctx.arena().allocate(stride * resident.length, 16);
        commandBytes = commandSegment.asByteBuffer();
        commands = new DrawCommandBuffer(commandSegment, DrawCommandBuffer.Kind.ARRAYS, false);
        commandBuffer = glCreateBuffers();
        glNamedBufferStorage(commandBuffer, commandSegment.byteSize(), GL_DYNAMIC_STORAGE_BIT);

        chunkVao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(chunkVao, 0, poolBuffer, 0, (int) POINT);
        setupPointFormat(chunkVao);
        particleVao = glCreateVertexArrays();
        setupPointFormat(particleVao);
        fillVao = glCreateVertexArrays();

        program = Gl.program("""
                #version 450 core
                layout(location = 0) in vec3 position;
                layout(location = 1) in uint packedColor;
                uniform mat4 viewProjection;
                uniform float pointScale;
                flat out uint vColor;
                void main() {
                    gl_Position = viewProjection * vec4(position, 1.0);
                    gl_PointSize = clamp(pointScale / gl_Position.w, 1.0, 5.0);
                    vColor = packedColor;
                }
                """, """
                #version 450 core
                flat in uint vColor;
                out vec4 color;
                void main() {
                    color = vec4(float(vColor & 255u), float((vColor >> 8) & 255u), float((vColor >> 16) & 255u), 255.0) / 255.0;
                }
                """);
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        pointScaleLocation = glGetUniformLocation(program, "pointScale");
        fillProgram = Gl.program("""
                #version 450 core
                out vec2 uv;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    uv = p;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.999, 1.0);
                }
                """, """
                #version 450 core
                in vec2 uv;
                uniform int load;
                uniform float seed;
                out vec4 color;
                void main() {
                    float a = seed + uv.x, b = uv.y;
                    for (int i = 0; i < load; i++) {
                        a = sin(a * 1.7 + b) * 0.5 + 0.5;
                        b = cos(b * 1.3 + a) * 0.5 + 0.5;
                    }
                    color = vec4(0.015 + 0.02 * a, 0.02 + 0.02 * b, 0.05, 1.0);
                }
                """);
        fillLoadLocation = glGetUniformLocation(fillProgram, "load");
        fillSeedLocation = glGetUniformLocation(fillProgram, "seed");
        gpuTimer = new GpuTimer();

        Stats stats = ctx.stats();
        stallSeries = stats.timer("ring wait", "time in beginFrame, waiting for the GPU to release a region");
        stalledFramesSeries = stats.series("frames that waited", "", 3, "fraction of frames in which beginFrame blocked");
        uploadSeries = stats.series("chunks uploaded", "", 2, "per frame");
        uploadBytesSeries = stats.series("bytes uploaded", "B", 0, "chunks and particles per frame");
        ringUsedSeries = stats.series("ring region used", "B", 0, "of the frame's region");
        residentSeries = stats.series("chunks resident", "", 0, "in the pool");
        missingSeries = stats.series("chunks missing", "", 1, "wanted around the camera and not loaded yet");
        evictionSeries = stats.series("chunks evicted", "", 2, "per frame");
        deferredSeries = stats.series("uploads deferred", "", 2, "per frame, by the byte budget or a full region");
        fragSeries = stats.series("pool fragmentation", "%", 1, "100 * (1 - largest free block / free bytes)");
        gpuSeries = stats.timer("gpu", "GPU time of the filler, the chunks and the particles");
        ctx.clearColor(0.02f, 0.02f, 0.05f);
        Warmup.untilQuiet(() -> {
            plan.camera(0.0, options.speed(), options.teleport(), cell);
            for (int i = 0; i < 50; i++) {
                StreamPlan.bytes(i, i);
            }
        }, 1000);
    }

    private static void setupPointFormat(int vao) {
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribIFormat(vao, 1, 1, GL_UNSIGNED_INT, 12);
        for (int a = 0; a < 2; a++) {
            glEnableVertexArrayAttrib(vao, a);
            glVertexArrayAttribBinding(vao, a, 0);
        }
    }

    // a ring over the first regions of the staging buffer: changing the count keeps the region size
    private void makeRing() {
        if (ring != null) {
            ring.drain();
        }
        ring = new PersistentBufferRing<>(mapped.asSlice(0, regionBytes * framesInFlight), framesInFlight, 256, new GlFences());
        java.util.Arrays.fill(usedFraction, 0.0);
    }

    private void makePool(StreamPool.Kind kind) {
        poolKind = kind;
        pool = new StreamPool(kind, options.poolMb() * 1_048_576L, StreamPlan.MAX_POINTS * POINT, 16);
        poolFailsAtStart = 0;
    }

    private void resetPool(StreamPool.Kind kind) {
        for (int i = residentCount - 1; i >= 0; i--) {
            cellOffset[resident[i]] = -1L;
        }
        residentCount = 0;
        residentBytes = 0;
        makePool(kind);
    }

    private int cellId(int cx, int cz) {
        return cz * options.world() + cx;
    }

    private void evict(int slot) {
        int id = resident[slot];
        int cx = id % options.world(), cz = id / options.world();
        int bytes = StreamPlan.bytes(cx, cz);
        pool.free(cellOffset[id], bytes);
        cellOffset[id] = -1L;
        residentBytes -= bytes;
        int last = resident[--residentCount];
        resident[slot] = last;
        residentIndex[last] = slot;
        totalEvictions++;
        evictedThisFrame++;
    }

    private int evictedThisFrame;

    @Override
    public void update(FrameInfo frame) {
        frameNumber = frame.frame();
        evictedThisFrame = 0;
        if (!frame.benchmark()) {
            var in = frame.input();
            if (in.pressed(GLFW_KEY_F)) {
                framesInFlight = framesInFlight % 4 + 1;
                makeRing();
            }
            if (in.pressed(GLFW_KEY_P)) {
                resetPool(StreamPool.Kind.values()[(poolKind.ordinal() + 1) % StreamPool.Kind.values().length]);
            }
            if (in.pressed(GLFW_KEY_RIGHT_BRACKET)) {
                gpuLoad = Math.min(1000, Math.max(1, gpuLoad * 2));
            }
            if (in.pressed(GLFW_KEY_LEFT_BRACKET)) {
                gpuLoad = gpuLoad / 2;
            }
            if (in.pressed(GLFW_KEY_T)) {
                teleportShift += 1.7;
            }
            if (in.pressed(GLFW_KEY_B)) {
                paused = !paused;
            }
        }
        if (!paused) {
            cameraTime = frame.time();
        }
        plan.camera(cameraTime + teleportShift * 100.0, options.speed(), options.teleport(), cell);
        int ccx = (int) Math.round(cell[0]), ccz = (int) Math.round(cell[1]);

        long t0 = System.nanoTime();
        long offset = ring.beginFrame();
        frameStallNanos = System.nanoTime() - t0;
        stallNanosTotal += frameStallNanos;
        frameUploadBytes = 0;
        frameUploads = 0;
        frameDeferred = 0;
        frameRingFull = 0;
        usedFraction[(int) (ring.frameIndex() % framesInFlight)] = 0.0;

        // chunks beyond the neighbourhood and a margin are released
        int keep = (options.radius() + 2) * (options.radius() + 2);
        for (int s = residentCount - 1; s >= 0; s--) {
            int id = resident[s];
            int dx = id % options.world() - ccx, dz = id / options.world() - ccz;
            if (dx * dx + dz * dz > keep) {
                evict(s);
            }
        }
        // the missing ones, nearest first, within the byte budget of the frame and the room in the region
        long budget = options.budgetMb() * 1_048_576L;
        missingNow = 0;
        boolean stop = false;
        for (int i = 0; i < plan.neighbourhood(); i++) {
            int cx = ccx + plan.dx(i), cz = ccz + plan.dz(i);
            if (cx < 0 || cz < 0 || cx >= options.world() || cz >= options.world()) {
                continue;
            }
            int id = cellId(cx, cz);
            if (cellOffset[id] >= 0) {
                continue;
            }
            missingNow++;
            if (stop) {
                continue;
            }
            int bytes = StreamPlan.bytes(cx, cz);
            if (frameUploadBytes + bytes > budget) {
                frameDeferred++;
                stop = true;
                continue;
            }
            long staged = ring.allocate(bytes, 16);
            if (staged == PersistentBufferRing.NONE) {
                frameRingFull++;
                stop = true;
                continue;
            }
            long at = pool.allocate(bytes);
            if (at == StreamPool.NONE) {
                // make room by releasing the farthest chunk that is outside the wanted disc
                int farthest = -1, best = -1;
                for (int s = 0; s < residentCount; s++) {
                    int rid = resident[s];
                    int dx = rid % options.world() - ccx, dz = rid / options.world() - ccz;
                    int d2 = dx * dx + dz * dz;
                    if (d2 > options.radius() * options.radius() + options.radius() && d2 > best) {
                        best = d2;
                        farthest = s;
                    }
                }
                if (farthest >= 0) {
                    evict(farthest);
                    at = pool.allocate(bytes);
                }
            }
            if (at == StreamPool.NONE) {
                continue;
            }
            StreamPlan.fill(cx, cz, mapped, staged);
            glCopyNamedBufferSubData(stagingBuffer, poolBuffer, staged, at, bytes);
            cellOffset[id] = at;
            resident[residentCount] = id;
            residentIndex[id] = residentCount++;
            residentBytes += bytes;
            frameUploadBytes += bytes;
            frameUploads++;
            totalUploads++;
        }
        // the particles of this frame go straight into the ring and are drawn from it
        particleOffset = PersistentBufferRing.NONE;
        long pbytes = (long) options.particles() * POINT;
        if (pbytes > 0) {
            particleOffset = ring.allocate(pbytes, 256);
            if (particleOffset != PersistentBufferRing.NONE) {
                writeParticles(particleOffset, ccx, ccz, frame.time());
                frameUploadBytes += pbytes;
            } else {
                frameRingFull++;
            }
        }
        totalUploadBytes += frameUploadBytes;
        totalRingFull += frameRingFull;
        totalDeferred += frameDeferred;
        usedFraction[(int) (ring.frameIndex() % framesInFlight)] = ring.used() / (double) regionBytes;

        commands.clear();
        for (int s = 0; s < residentCount; s++) {
            int id = resident[s];
            int cx = id % options.world(), cz = id / options.world();
            commands.addArrays(StreamPlan.points(cx, cz), 1, (int) (cellOffset[id] / POINT), 0);
        }
        drawCount = residentCount;
        glNamedBufferSubData(commandBuffer, 0, commandBytes);

        float px = (float) (cell[0] * StreamPlan.CELL), pz = (float) (cell[1] * StreamPlan.CELL);
        camera = Cameraf.lookingAt(new Vec3f(px, 360f, pz - 300f), new Vec3f(px, 0f, pz), Vec3f.UNIT_Y, 1.0f, frame.aspect(), 1f, 3000f, DepthRange.NEGATIVE_ONE_TO_ONE);

        if (options.verify() && frame.frame() % 120 == 119) {
            verify();
        }
        Stats stats = ctx.stats();
        stats.recordNanos(stallSeries, frameStallNanos);
        stats.record(stalledFramesSeries, frameStallNanos > 50_000 ? 1.0 : 0.0);
        stats.record(uploadSeries, frameUploads);
        stats.record(uploadBytesSeries, frameUploadBytes);
        stats.record(ringUsedSeries, ring.used());
        stats.record(residentSeries, residentCount);
        stats.record(missingSeries, missingNow);
        stats.record(evictionSeries, evictedThisFrame);
        stats.record(deferredSeries, frameDeferred + frameRingFull);
        stats.record(fragSeries, pool.freeBytes() == 0 ? 0.0 : 100.0 * (1.0 - pool.largestFree() / (double) pool.freeBytes()));
        if (offset < 0) {
            throw new IllegalStateException("the ring returned an offset of " + offset);
        }
    }

    private void writeParticles(long offset, int ccx, int ccz, double time) {
        int n = options.particles();
        float cxm = (float) (cell[0] * StreamPlan.CELL), czm = (float) (cell[1] * StreamPlan.CELL);
        float span = (options.radius() + 1) * StreamPlan.CELL;
        float t = (float) time;
        for (int i = 0; i < n; i++) {
            float u = (i * 0.61803399f) % 1f, v = (i * 0.75487767f) % 1f, w = (i * 0.5698403f) % 1f;
            float phase = (w * 7f + t * (0.15f + 0.4f * u)) % 1f;
            long o = offset + (long) i * POINT;
            mapped.set(ValueLayout.JAVA_FLOAT_UNALIGNED, o, cxm + (u * 2f - 1f) * span);
            mapped.set(ValueLayout.JAVA_FLOAT_UNALIGNED, o + 4, 4f + phase * 70f);
            mapped.set(ValueLayout.JAVA_FLOAT_UNALIGNED, o + 8, czm + (v * 2f - 1f) * span);
            int shade = 160 + (int) (95 * (1f - phase));
            mapped.set(ValueLayout.JAVA_INT_UNALIGNED, o + 12, shade | (shade << 8) | (255 << 16) | 0xFF000000);
        }
    }

    @Override
    public void render(FrameInfo frame) {
        gpuTimer.begin();
        glDisable(GL_DEPTH_TEST);
        if (gpuLoad > 0) {
            glUseProgram(fillProgram);
            glProgramUniform1i(fillProgram, fillLoadLocation, gpuLoad * 1000);
            glProgramUniform1f(fillProgram, fillSeedLocation, frameNumber * 0.001f);
            glBindVertexArray(fillVao);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_PROGRAM_POINT_SIZE);
        glUseProgram(program);
        Gl.uniform(program, viewProjectionLocation, camera.viewProjection());
        glProgramUniform1f(program, pointScaleLocation, 700f);
        glBindVertexArray(chunkVao);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        glMultiDrawArraysIndirect(GL_POINTS, 0L, drawCount, (int) commands.stride());
        if (particleOffset != PersistentBufferRing.NONE) {
            glVertexArrayVertexBuffer(particleVao, 0, stagingBuffer, particleOffset, (int) POINT);
            glBindVertexArray(particleVao);
            glDrawArrays(GL_POINTS, 0, options.particles());
        }
        gpuTimer.end();
        ring.endFrame();
        long g = gpuTimer.poll();
        if (g >= 0) {
            ctx.stats().recordNanos(gpuSeries, g);
        }
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.line(String.format(Locale.ROOT, "ring: %d regions of %d MB | used %.1f MB of this frame's region, high water %.1f MB | %,d waits for the GPU, last %.3f ms, total %.1f ms", framesInFlight,
                regionBytes >> 20, ring.used() / 1048576.0, ring.highWaterMark() / 1048576.0, ring.stalls(), frameStallNanos / 1e6, stallNanosTotal / 1e6));
        float y = hud.cursorY();
        for (int i = 0; i < framesInFlight; i++) {
            hud.bar(8 + i * 106, y, 100, 8, (float) Math.min(1.0, usedFraction[i]), 0.4f, 0.8f, 1f);
        }
        hud.gap(14);
        hud.line(String.format(Locale.ROOT, "pool: %s, %.1f MB | %.1f MB reserved (%.1f MB asked for), largest free %.2f MB of %.1f MB free in %,d blocks | %,d allocation failures",
                poolKind.text(), pool.capacity() / 1048576.0, pool.reservedBytes() / 1048576.0, pool.requestedBytes() / 1048576.0, pool.largestFree() / 1048576.0, pool.freeBytes() / 1048576.0,
                pool.blocks(), pool.failures()));
        float y2 = hud.cursorY();
        hud.bar(8, y2, 320, 8, (float) (pool.reservedBytes() / (double) pool.capacity()), 1f, 0.7f, 0.3f);
        hud.gap(14);
        hud.line(String.format(Locale.ROOT, "%,d chunks resident, %,d missing | this frame: %d uploaded (%.2f MB incl. particles), %d evicted, %d deferred, %d refused by a full region | %,d evictions in all", residentCount,
                missingNow, frameUploads, frameUploadBytes / 1048576.0, evictedThisFrame, frameDeferred, frameRingFull, totalEvictions));
        hud.line(String.format(Locale.ROOT, "GPU load %d | camera %s%s", gpuLoad, paused ? "paused" : "moving", options.verify() ? String.format(Locale.ROOT, " | verified %,d chunks, %d failures", verifiedChunks, verifyFailures) : ""));
        // the neighbourhood: green resident, red wanted and missing, grey resident outside the disc
        float x0 = hud.width() - 6 - (2 * options.radius() + 1) * 5, y0 = 8;
        int ccx = (int) Math.round(cell[0]), ccz = (int) Math.round(cell[1]);
        for (int dz = -options.radius(); dz <= options.radius(); dz++) {
            for (int dx = -options.radius(); dx <= options.radius(); dx++) {
                int cx = ccx + dx, cz = ccz + dz;
                if (cx < 0 || cz < 0 || cx >= options.world() || cz >= options.world()) {
                    continue;
                }
                boolean in = dx * dx + dz * dz <= options.radius() * options.radius() + options.radius();
                boolean have = cellOffset[cellId(cx, cz)] >= 0;
                if (!in && !have) {
                    continue;
                }
                hud.rect(x0 + (dx + options.radius()) * 5, y0 + (dz + options.radius()) * 5, 4, 4, have ? (in ? 0.2f : 0.5f) : 0.9f, have ? (in ? 0.85f : 0.5f) : 0.2f, have ? (in ? 0.3f : 0.5f) : 0.2f, 0.9f);
            }
        }
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "world of %d x %d cells, neighbourhood radius %d (%d cells), chunks of %d to %d points of %d bytes, pool %d MB (%s), ring %d regions of %d MB, budget %d MB per frame, %,d particles per frame, GPU load %d",
                options.world(), options.world(), options.radius(), plan.neighbourhood(), StreamPlan.MIN_POINTS, StreamPlan.MAX_POINTS, StreamPlan.POINT_BYTES, options.poolMb(), poolKind.text(), framesInFlight,
                options.regionMb(), options.budgetMb(), options.particles(), gpuLoad));
        stats.note(String.format(Locale.ROOT, "ring: %,d waits for the GPU in %,d frames, %.1f ms waiting in all, high water %.2f MB of a %d MB region", ring.stalls(), ring.frameIndex(), stallNanosTotal / 1e6,
                ring.highWaterMark() / 1048576.0, regionBytes >> 20));
        stats.note(String.format(Locale.ROOT, "chunks: %,d uploaded (%.1f MB), %,d evicted, %,d uploads deferred by the budget, %,d refused by a full region, %,d allocation failures of the pool", totalUploads,
                totalUploadBytes / 1048576.0, totalEvictions, totalDeferred, totalRingFull, pool.failures()));
        stats.note(String.format(Locale.ROOT, "pool at the end: %.1f MB reserved of %.1f MB, largest free block %.2f MB of %.1f MB free in %,d blocks", pool.reservedBytes() / 1048576.0, pool.capacity() / 1048576.0,
                pool.largestFree() / 1048576.0, pool.freeBytes() / 1048576.0, pool.blocks()));
        if (options.verify()) {
            stats.note(String.format(Locale.ROOT, "verification: %,d chunks read back from the pool in %d rounds, %d wrong", verifiedChunks, verifyRounds, verifyFailures));
        }
    }

    /**
     * Reads every resident chunk back from the pool and compares it with the content that the plan
     * generates for its cell, and checks the allocator's books; throws on any difference.
     */
    private void verify() {
        verifyRounds++;
        String problem = pool.validate();
        if (problem != null) {
            throw new IllegalStateException("the allocator is inconsistent: " + problem);
        }
        long sum = 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment expected = arena.allocate(StreamPlan.MAX_POINTS * POINT, 16);
            ByteBuffer got = MemoryUtil.memAlloc((int) (StreamPlan.MAX_POINTS * POINT));
            try {
                for (int s = 0; s < residentCount; s++) {
                    int id = resident[s];
                    int cx = id % options.world(), cz = id / options.world();
                    int bytes = StreamPlan.bytes(cx, cz);
                    sum += bytes;
                    StreamPlan.fill(cx, cz, expected, 0);
                    got.clear().limit(bytes);
                    glGetNamedBufferSubData(poolBuffer, cellOffset[id], got);
                    if (MemorySegment.ofBuffer(got).mismatch(expected.asSlice(0, bytes)) != -1) {
                        verifyFailures++;
                    }
                    verifiedChunks++;
                }
            } finally {
                MemoryUtil.memFree(got);
            }
        }
        if (verifyFailures > 0) {
            throw new IllegalStateException(verifyFailures + " chunks in the pool differ from their content");
        }
        if (sum != pool.requestedBytes() || sum != residentBytes) {
            throw new IllegalStateException("the pool's books are wrong: chunks " + sum + " B, requested " + pool.requestedBytes() + " B, counted " + residentBytes + " B");
        }
    }

    @Override
    public void dispose() {
        ring.drain();
        ring.close();
        gpuTimer.dispose();
        glUnmapNamedBuffer(stagingBuffer);
        glDeleteBuffers(stagingBuffer);
        glDeleteBuffers(poolBuffer);
        glDeleteBuffers(commandBuffer);
        glDeleteVertexArrays(chunkVao);
        glDeleteVertexArrays(particleVao);
        glDeleteVertexArrays(fillVao);
        glDeleteProgram(program);
        glDeleteProgram(fillProgram);
    }
}
