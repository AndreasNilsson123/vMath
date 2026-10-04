package vmath.samples.demos.occlusion;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_I;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_X;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import vmath.bulk.BoundsArray;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.samples.framework.BoxRenderer;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.DepthInset;
import vmath.samples.framework.FlyCamera;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Hud;
import vmath.samples.framework.InstanceStream;
import vmath.samples.framework.Scenes;
import vmath.samples.framework.Stats;
import vmath.samples.framework.Warmup;
import vmath.util.DebugLines;

/**
 * Software occlusion culling in a dense city: the big buildings near the camera are rasterised into
 * a small depth buffer, and everything behind them (the other buildings and, above all, the
 * hundreds of thousands of props in the streets) is removed before it is drawn.
 *
 * <p>The CPU work of a frame is in {@link OcclusionFrame}: the frustum kernel, the choice of
 * occluders (the buildings in the frustum within 300 m), rasterising them into the
 * {@link vmath.occlusion.DepthBuffer}, and testing every box that is left against the buffer's Hi-Z
 * pyramid. The test is conservative: an object is removed only if it is certainly hidden.
 * {@code --verify} checks that claim every frame by shooting rays from the camera at nine points
 * of each of up to 200 removed boxes (those that are on screen) and requiring that an occluder
 * blocks every one; a box that the rays find visible fails the run. {@code I} shows the depth
 * buffer (a level of its pyramid with {@code [} and {@code ]}), {@code O} switches the occlusion
 * culling off, and {@code X} freezes the culling camera, so that flying away shows the buildings'
 * shadows in the scene.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context; the
 * occlusion test may use worker threads, which it hands back before the frame goes on.
 */
public final class OcclusionDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("occlusion", "Occlusion culling in a dense city",
            "Buildings rasterised into a small depth buffer hide most of a city's props; a ray check proves that nothing visible is removed.",
            List.of("culling", "scale"), 24576, List.of("--blocks", "24", "--props", "30"),
            "left mouse + move: look | W A S D: fly | Space, Ctrl: up, down | Shift: fast | O: occlusion on/off | I: depth view, [ ]: level | Y: verify | X: freeze the culling camera");

    private static final float FAR = 1500f;
    private static final long WARMUP_MILLIS = 4000;

    private final OcclusionOptions options;
    private Scenes.Blocks city;
    private BoundsArray bounds;
    private OcclusionFrame work;
    private ExecutorService executor;
    private DepthInset inset;
    private InstanceStream stream;
    private BoxRenderer boxes;
    private FlyCamera fly;
    private DemoContext ctx;
    private final DebugLines lines = new DebugLines();
    private Cameraf camera;
    private Cameraf frozen;
    private boolean occlusion;
    private boolean showInset = true;
    private boolean verify;
    private boolean freeze;
    private int level;
    private float streetX;
    private float pathLength;
    private int frustumSeries;
    private int occluderSeries;
    private int rasterSeries;
    private int testSeries;
    private int frustumCountSeries;
    private int finalCountSeries;
    private int occluderCountSeries;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public OcclusionDemo(List<String> args) {
        this.options = OcclusionOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        long t0 = System.nanoTime();
        city = Scenes.blocks(options.blocks(), options.props());
        bounds = city.bounds();
        System.out.printf(Locale.ROOT, "built %,d buildings, %,d props and a ground in %.0f ms%n", city.buildings(), city.props(), (System.nanoTime() - t0) / 1e6);
        if (options.threads() > 1) {
            executor = Executors.newFixedThreadPool(options.threads(), r -> {
                Thread t = new Thread(r, "occlusion-test");
                t.setDaemon(true);
                return t;
            });
        }
        work = new OcclusionFrame(city, options.depthWidth(), executor, options.threads());
        inset = new DepthInset(options.depthWidth(), options.depthWidth() / 2);
        stream = new InstanceStream(ctx.arena(), bounds.size(), 3);
        boxes = new BoxRenderer(ctx);
        boxes.fogColor(0.62f, 0.72f, 0.85f);
        occlusion = options.occlusion();
        verify = options.verify();
        float half = options.blocks() * Scenes.BLOCK_PITCH * 0.5f;
        streetX = (options.blocks() / 2 + 1) * Scenes.BLOCK_PITCH - half;
        pathLength = 2f * half - 200f;
        fly = new FlyCamera(new Vec3f(streetX, 3f, -half + 100f), (float) Math.PI, -0.02f, 1.0f, 0.3f, FAR, 30f, 150f);

        Stats stats = ctx.stats();
        frustumSeries = stats.timer("frustum", "the frustum kernel over every box");
        occluderSeries = stats.timer("choose occluders", "the buildings in the frustum within " + (int) OcclusionFrame.OCCLUDER_RANGE + " m");
        rasterSeries = stats.timer("rasterise", "the occluders into the " + options.depthWidth() + " x " + options.depthWidth() / 2 + " depth buffer");
        testSeries = stats.timer("occlusion test", "every box that is left against the Hi-Z pyramid, " + options.threads() + " thread" + (options.threads() == 1 ? "" : "s"));
        frustumCountSeries = stats.series("after frustum", "", 0, "boxes in the frustum, of " + bounds.size());
        finalCountSeries = stats.series("after occlusion", "", 0, "boxes that are drawn");
        occluderCountSeries = stats.series("occluders", "", 0, "buildings rasterised");
        ctx.clearColor(0.62f, 0.72f, 0.85f);
        prewarm();
    }

    /**
     * Runs the culling of a frame before the first frame until the JIT has compiled it and it has
     * stopped allocating ({@link Warmup}).
     */
    private void prewarm() {
        Cameraf start = fly.camera(16f / 9f);
        int rounds = Warmup.untilQuiet(() -> work.cull(start, 900, true), WARMUP_MILLIS);
        System.out.println("warm after " + rounds + " culling passes");
    }

    @Override
    public void update(FrameInfo frame) {
        fly.update(frame);
        if (frame.benchmark()) {
            scripted(frame.frame());
        } else {
            keys(frame);
        }
        camera = fly.camera(frame.aspect());
        Cameraf cullCamera = frozen != null ? frozen : camera;

        work.cull(cullCamera, frame.height(), occlusion);
        if (verify && occlusion) {
            work.verify(cullCamera);
        }
        Stats stats = ctx.stats();
        stats.recordNanos(frustumSeries, work.frustumNs());
        if (occlusion) {
            stats.recordNanos(occluderSeries, work.chooseNs());
            stats.recordNanos(rasterSeries, work.rasterNs());
            stats.recordNanos(testSeries, work.testNs());
        }
        stats.record(frustumCountSeries, work.frustumCount());
        stats.record(finalCountSeries, work.finalCount());
        stats.record(occluderCountSeries, occlusion ? work.occluders().size() : 0);
        stream.begin();
        stream.write(work.visible(), bounds);
    }

    /**
     * Walks the camera along a street from one end of the city to the other and back, with the
     * view swinging from side to side; it depends only on the frame number.
     */
    private void scripted(int frame) {
        float p = (frame * 0.4f) % (2f * pathLength);
        boolean forward = p < pathLength;
        float half = options.blocks() * Scenes.BLOCK_PITCH * 0.5f;
        float z = -half + 100f + (forward ? p : 2f * pathLength - p);
        float yaw = (forward ? (float) Math.PI : 0f) + 0.5f * (float) Math.sin(frame * 0.015);
        fly.place(streetX, 3f, z, yaw, -0.02f);
    }

    private void keys(FrameInfo frame) {
        var in = frame.input();
        if (in.pressed(GLFW_KEY_O)) {
            occlusion = !occlusion;
        }
        if (in.pressed(GLFW_KEY_I)) {
            showInset = !showInset;
        }
        if (in.pressed(GLFW_KEY_Y)) {
            verify = !verify;
        }
        if (in.pressed(GLFW_KEY_LEFT_BRACKET)) {
            level = Math.max(0, level - 1);
        }
        if (in.pressed(GLFW_KEY_RIGHT_BRACKET)) {
            level = Math.min(work.depth().levels() - 1, level + 1);
        }
        if (in.pressed(GLFW_KEY_X)) {
            freeze = !freeze;
            frozen = freeze ? camera : null;
        }
    }

    @Override
    public void render(FrameInfo frame) {
        boxes.draw(camera, stream, bounds.size() - 1, city.buildings(), 0.0006f);
        if (frozen != null) {
            lines.clear();
            lines.setColor(1f, 0.9f, 0.1f, 1f).frustum(frozen.viewProjection(), DepthRange.NEGATIVE_ONE_TO_ONE, FAR);
            ctx.debug().draw(lines, camera.viewProjection());
        }
        stream.end();
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        int frustum = work.frustumCount(), drawn = work.finalCount();
        hud.line(String.format(Locale.ROOT, "%,d boxes | frustum keeps %,d | occlusion %s: %,d drawn (%.1f%% of the frustum's)", bounds.size(), frustum,
                occlusion ? "on" : "off", drawn, frustum == 0 ? 0.0 : 100.0 * drawn / frustum));
        hud.line(String.format(Locale.ROOT, "frustum %.3f ms | occluders %d, rasterise %.3f ms | test %.3f ms%s", work.frustumNs() / 1e6, work.occluders().size(),
                work.rasterNs() / 1e6, work.testNs() / 1e6, frozen != null ? " | culling camera frozen (X)" : ""));
        if (verify) {
            hud.line(String.format(Locale.ROOT, "verified %,d removed boxes with rays: %d visible", work.checked(), work.violations()));
        }
        if (showInset && occlusion) {
            float w = Math.min(frame.width() * 0.4f, 640f), h = w / 2f;
            float x = frame.width() - w - 8f, y = 8f;
            inset.upload(work.depth(), level);
            inset.draw(x, y, w, h, frame.width(), frame.height(), OcclusionFrame.OCCLUDER_RANGE);
            hud.text(x, y + h + 4f, String.format(Locale.ROOT, "depth buffer level %d, %d x %d (nearer is brighter, blue is empty)", level,
                    Math.max(1, options.depthWidth() >> level), Math.max(1, options.depthWidth() / 2 >> level)));
        }
    }

    @Override
    public void report(Stats stats) {
        if (verify) {
            stats.note(String.format(Locale.ROOT, "checked %,d removed boxes with rays at nine points each: %d visible", work.checked(), work.violations()));
        }
        stats.note(String.format(Locale.ROOT, "%,d buildings, %,d props, depth buffer %d x %d", city.buildings(), city.props(), options.depthWidth(), options.depthWidth() / 2));
        stats.note("stalls waiting for the GPU: " + stream.stalls());
    }

    @Override
    public void dispose() {
        if (executor != null) {
            executor.shutdownNow();
        }
        inset.dispose();
        stream.close();
        boxes.dispose();
    }
}
