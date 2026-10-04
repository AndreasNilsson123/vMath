package vmath.samples.demos.city;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_X;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.gl.InstanceWriter;
import vmath.samples.framework.BoxRenderer;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FlyCamera;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.GpuMesh;
import vmath.samples.framework.Hud;
import vmath.samples.framework.InstanceStream;
import vmath.samples.framework.Scenes;
import vmath.samples.framework.Stats;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernels;
import vmath.spatial.ParallelFrustumKernel;
import vmath.util.DebugLines;

/**
 * A city of one million boxes drawn with the pieces of vmath that a GPU-driven frame is made of:
 * the box mesh is generated, optimised and exported with the vertex layout; the boxes are culled
 * against the camera frustum on the CPU (optionally on several threads, with the SIMD kernel when
 * it is available); the survivors are written as 64-byte instance records straight into a
 * persistently mapped storage buffer that a ring of regions, one per frame in flight and guarded by
 * fences, shares between the CPU and the GPU; and one indirect draw command, written by the
 * library's draw command buffer, draws them all.
 *
 * <p>The key {@code C} switches the culling off (every box is drawn) and {@code X} freezes the
 * camera that the culling uses and draws its frustum, so that flying away shows what the culling
 * removed. The options are in {@link CityOptions}.
 *
 * <p>This is the sample that was first called {@code MillionInstances}, ported to the demo
 * framework. Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context; only
 * the frustum culling may use worker threads, which it hands back before the frame goes on.
 */
public final class CityDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("city", "A city of a million boxes",
            "A million boxes culled on the CPU and drawn with one indirect call, from a persistently mapped, fenced instance buffer.",
            List.of("culling", "scale"), 4096, List.of("--instances", "50000"),
            "left mouse + move: look | W A S D: fly | Space, Ctrl: up, down | Shift: fast | C: culling on/off | X: freeze the culling camera | R: reset");

    private final CityOptions options;
    private BoundsArray bounds;
    private VisibilitySet visible;
    private ExecutorService executor;
    private FrustumKernel kernel;
    private CullPipeline pipeline;
    private BoxRenderer boxes;
    private InstanceStream stream;
    private FlyCamera fly;
    private float side;
    private boolean culling;
    private Cameraf camera;
    private Cameraf frozen;
    private final DebugLines frozenLines = new DebugLines();
    private DemoContext ctx;
    private int waitSeries;
    private int cullSeries;
    private int writeSeries;
    private int submitSeries;
    private int visibleSeries;
    private int dataSeries;
    private int visibleNow;
    private long waitNs;
    private long cullNs;
    private long writeNs;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public CityDemo(List<String> args) {
        this.options = CityOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        System.out.printf(Locale.ROOT, "building the city of %,d boxes ...%n", options.instances());
        bounds = Scenes.city(options.instances());
        visible = new VisibilitySet(bounds.size());
        if (options.threads() > 1) {
            executor = Executors.newFixedThreadPool(options.threads(), r -> {
                Thread t = new Thread(r, "frustum-culling");
                t.setDaemon(true);
                return t;
            });
            kernel = new ParallelFrustumKernel(executor, options.threads());
        } else {
            kernel = FrustumKernels.best();
        }
        pipeline = CullPipeline.of(new CullStages.Frustum(kernel));
        System.out.println("frustum kernel: " + kernel.name());

        boxes = new BoxRenderer(ctx);
        GpuMesh mesh = boxes.mesh();
        System.out.printf("mesh: %d vertices, %d indices, %d bytes per vertex%n", mesh.vertexCount(), mesh.indexCount(), mesh.stride());
        stream = new InstanceStream(ctx.arena(), bounds.size(), options.framesInFlight());
        System.out.printf(Locale.ROOT, "instance buffer: %d regions of %,d bytes (%.0f MB mapped)%n", stream.framesInFlight(), stream.regionBytes(),
                stream.regionBytes() * stream.framesInFlight() / 1e6);

        Stats stats = ctx.stats();
        waitSeries = stats.timer("wait for the ring", "the fence of the region, before it is written again");
        cullSeries = stats.timer("cull", "frustum, " + options.threads() + " thread" + (options.threads() == 1 ? "" : "s"));
        writeSeries = stats.timer("write instances", "visible boxes as 64-byte records into mapped memory");
        submitSeries = stats.timer("submit", "uniforms, indirect command, draw call, fence");
        visibleSeries = stats.series("visible", "", 0, "instances that passed the culling, of " + bounds.size());
        dataSeries = stats.series("instance data", "MB", 1, "written per frame");

        side = (float) Math.ceil(Math.sqrt(bounds.size())) * Scenes.CITY_SPACING;
        fly = new FlyCamera(new Vec3f(0f, side * 0.06f + 40f, -side * 0.5f), 0f, -0.1f, 1.0f, 0.5f, 6000f, 120f, 600f);
        culling = options.culling();
        ctx.clearColor(0.55f, 0.7f, 0.88f);
    }

    @Override
    public void update(FrameInfo frame) {
        fly.update(frame);
        if (frame.benchmark()) {
            fly.scripted(frame.frame(), side * 0.28f, 150f);
        } else {
            if (frame.input().pressed(GLFW_KEY_C)) {
                culling = !culling;
            }
            if (frame.input().pressed(GLFW_KEY_X)) {
                toggleFrozen();
            }
        }
        camera = fly.camera(frame.aspect());
        Cameraf cullCamera = frozen != null ? frozen : camera;

        long t0 = System.nanoTime();
        stream.begin();
        long t1 = System.nanoTime();
        int count = bounds.size();
        int n;
        if (culling) {
            CullContext cull = CullContext.perspective(cullCamera.frustum(), cullCamera.position(), fly.fovy(), frame.height());
            n = pipeline.run(cull, bounds, visible);
        } else {
            visible.setAll(count);
            n = count;
        }
        long t2 = System.nanoTime();
        int written = stream.write(visible, bounds);
        long t3 = System.nanoTime();

        Stats stats = ctx.stats();
        stats.recordNanos(waitSeries, t1 - t0);
        stats.recordNanos(cullSeries, t2 - t1);
        stats.recordNanos(writeSeries, t3 - t2);
        stats.record(visibleSeries, n);
        stats.record(dataSeries, written * (double) InstanceWriter.STRIDE / 1e6);
        visibleNow = n;
        waitNs = t1 - t0;
        cullNs = t2 - t1;
        writeNs = t3 - t2;
    }

    private void toggleFrozen() {
        if (frozen != null) {
            frozen = null;
            return;
        }
        frozen = camera;
        frozenLines.clear();
        frozenLines.setColor(1f, 0.9f, 0.1f, 1f).frustum(frozen.viewProjection(), DepthRange.NEGATIVE_ONE_TO_ONE, 6000f);
    }

    @Override
    public void render(FrameInfo frame) {
        long t0 = System.nanoTime();
        boxes.draw(camera, stream, bounds.size() - 1, 0, 0.00045f);
        if (frozen != null) {
            ctx.debug().draw(frozenLines, camera.viewProjection());
        }
        stream.end();
        ctx.stats().recordNanos(submitSeries, System.nanoTime() - t0);
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        Stats stats = ctx.stats();
        hud.line(String.format(Locale.ROOT, "%,d of %,d instances visible, culling %s%s", visibleNow, bounds.size(), culling ? "on" : "off",
                frozen != null ? ", culling camera frozen (X)" : ""));
        hud.line(String.format(Locale.ROOT, "wait %.2f ms | cull %.2f ms | write %.2f ms | gpu %.2f ms", waitNs / 1e6, cullNs / 1e6, writeNs / 1e6,
                Math.max(0.0, stats.last(stats.indexOf("gpu")))));
    }

    @Override
    public void report(Stats stats) {
        stats.note("culling " + (culling ? "on" : "off") + ", frustum kernel " + kernel.name());
        stats.note("stalls waiting for the GPU: " + stream.stalls());
    }

    @Override
    public void dispose() {
        stream.close();
        boxes.dispose();
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
