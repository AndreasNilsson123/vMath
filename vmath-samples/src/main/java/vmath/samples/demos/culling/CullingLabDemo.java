package vmath.samples.demos.culling;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_B;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_N;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_U;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_X;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.samples.framework.BoxRenderer;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FlyCamera;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Hud;
import vmath.samples.framework.InstanceStream;
import vmath.samples.framework.Scenes;
import vmath.samples.framework.Stats;
import vmath.samples.framework.Warmup;
import vmath.spatial.StaticBvh;
import vmath.util.DebugLines;

/**
 * The same city of boxes culled by seven different methods, one key apart: the batch kernels that
 * look at every box (scalar, SIMD, parallel) and the spatial structures that avoid looking at most
 * of them (a static BVH, a dynamic AABB tree, a loose octree and a uniform grid).
 *
 * <p>Every frame the active method finds the boxes in the frustum, and the demo draws them; the
 * time of the cull is shown live and recorded per method. {@code --verify} compares each result
 * with the scalar kernel's and fails if a method misses a box that the scalar kernel finds
 * (conservative results with extra boxes are counted, not failed). {@code U} moves a fiftieth of
 * the boxes every frame, which shows what each structure costs to keep up to date; {@code B} draws
 * the boxes of one level of the BVH; {@code T} runs every method on the current view and shows the
 * table. A scripted run spends {@code --segment} frames on each method in turn.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context; the
 * parallel kernel uses worker threads that it hands back before the frame goes on.
 */
public final class CullingLabDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("culling-lab", "Culling lab: seven ways to cull",
            "The same boxes culled by three batch kernels and four spatial structures, timed live, each checked against the scalar kernel.",
            List.of("culling", "scale"), 16384, List.of("--instances", "30000", "--verify", "--segment", "13"),
            "left mouse + move: look | W A S D: fly | M, N or 1-7: method | U: animate | B: BVH boxes, [ ]: level | T: compare all | X: freeze the culling camera");

    private static final float FAR = 1500f;
    private static final int WARM = 20;
    private static final int WINDOWS = 50;
    private static final long WARMUP_MILLIS = 3000;
    private static final float MARGINAL = 0.01f;

    private final CullingLabOptions options;
    private final List<CullMethod> methods = new ArrayList<>();
    private final DebugLines lines = new DebugLines();
    private final int[] bvhStack = new int[256];
    private final int[] bvhDepthStack = new int[256];
    private BoundsArray bounds;
    private BoundsArray base;
    private VisibilitySet visible;
    private VisibilitySet reference;
    private VisibilitySet scratch;
    private ExecutorService executor;
    private InstanceStream stream;
    private BoxRenderer boxes;
    private FlyCamera fly;
    private DemoContext ctx;
    private float side;
    private int active;
    private boolean animate;
    private boolean showBvh;
    private int bvhLevel = 3;
    private boolean compare;
    private boolean freeze;
    private Cameraf camera;
    private Cameraf frozen;
    private int[] cullSeries;
    private int[] updateSeries;
    private int visibleSeries;
    private double[] buildMs;
    private double[] compareMs;
    private int visibleNow;
    private double cullMs;
    private double updateMs;
    private long verifiedFrames;
    private long missedBoxes;
    private long marginalBoxes;
    private long extraBoxes;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public CullingLabDemo(List<String> args) {
        this.options = CullingLabOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        System.out.printf(Locale.ROOT, "building the city of %,d boxes ...%n", options.instances());
        bounds = Scenes.city(options.instances());
        base = new BoundsArray(bounds.size());
        for (int i = 0; i < bounds.size(); i++) {
            base.add(bounds.minX(i), bounds.minY(i), bounds.minZ(i), bounds.maxX(i), bounds.maxY(i), bounds.maxZ(i));
        }
        visible = new VisibilitySet(bounds.size());
        reference = new VisibilitySet(bounds.size());
        scratch = new VisibilitySet(bounds.size());
        executor = Executors.newFixedThreadPool(options.threads(), r -> {
            Thread t = new Thread(r, "culling-lab");
            t.setDaemon(true);
            return t;
        });
        methods.add(CullMethod.Kernel.scalar());
        methods.add(CullMethod.Kernel.best());
        methods.add(CullMethod.Kernel.parallel(executor, options.threads(), () -> { }));
        methods.add(new CullMethod.Bvh());
        methods.add(new CullMethod.Dynamic());
        methods.add(new CullMethod.Octree());
        methods.add(new CullMethod.Grid());

        buildMs = new double[methods.size()];
        compareMs = new double[methods.size()];
        Stats stats = ctx.stats();
        cullSeries = new int[methods.size()];
        updateSeries = new int[methods.size()];
        for (int m = 0; m < methods.size(); m++) {
            CullMethod method = methods.get(m);
            long t0 = System.nanoTime();
            method.build(bounds);
            buildMs[m] = (System.nanoTime() - t0) / 1e6;
            cullSeries[m] = stats.timer("cull: " + method.name(), "frustum culling of the whole city");
            updateSeries[m] = method.hasStructure() ? stats.timer("update: " + method.name(), "keeping the structure current while a fiftieth of the boxes move") : -1;
            if (method.hasStructure()) {
                System.out.printf(Locale.ROOT, "built %-14s in %,8.1f ms%n", method.name(), buildMs[m]);
            }
        }
        visibleSeries = stats.series("visible", "", 0, "boxes in the frustum, of " + bounds.size());

        stream = new InstanceStream(ctx.arena(), bounds.size(), 3);
        boxes = new BoxRenderer(ctx);
        side = (float) Math.ceil(Math.sqrt(bounds.size())) * Scenes.CITY_SPACING;
        fly = new FlyCamera(new Vec3f(0f, side * 0.06f + 40f, -side * 0.5f), 0f, -0.1f, 1.0f, 0.5f, FAR, 120f, 600f);
        animate = options.animate();
        showBvh = options.showBvh();
        if (options.method() != null) {
            String wanted = options.method().toLowerCase(Locale.ROOT);
            boolean found = false;
            for (int m = 0; m < methods.size() && !found; m++) {
                if (methods.get(m).name().toLowerCase(Locale.ROOT).contains(wanted)) {
                    active = m;
                    found = true;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("no method named " + options.method() + "\n" + CullingLabOptions.USAGE);
            }
        }
        ctx.clearColor(0.55f, 0.7f, 0.88f);
        prewarm();
    }

    /**
     * Runs every method on the start view before the first frame until the JIT has compiled it and
     * it has stopped allocating ({@link Warmup}). The SIMD kernel allocates a vector object per
     * operation until then (about 9.5 MB per frame over its first 100 frames on 250,000 boxes), so
     * a method that is switched to later would otherwise charge that to the frame that first
     * uses it.
     */
    private void prewarm() {
        Cameraf start = fly.camera(16f / 9f);
        for (CullMethod method : methods) {
            Warmup.untilQuiet(() -> method.cull(start, 900, bounds, visible), WARMUP_MILLIS);
        }
    }

    @Override
    public void update(FrameInfo frame) {
        fly.update(frame);
        boolean recording = true;
        if (frame.benchmark()) {
            fly.scripted(frame.frame(), side * 0.28f, 150f);
            if (options.method() == null) {
                active = (frame.frame() / options.segment()) % methods.size();
                recording = frame.frame() % options.segment() >= Math.min(WARM, options.segment() / 3);
            }
        } else {
            keys(frame);
        }
        camera = fly.camera(frame.aspect());
        Cameraf cullCamera = frozen != null ? frozen : camera;

        updateMs = 0.0;
        if (animate) {
            moveBoxes(frame, recording);
        }

        CullMethod method = methods.get(active);
        long t0 = System.nanoTime();
        int n = method.cull(cullCamera, frame.height(), bounds, visible);
        long t1 = System.nanoTime();
        cullMs = (t1 - t0) / 1e6;
        visibleNow = n;
        if (options.verify()) {
            verify(method, cullCamera, frame.height());
        }
        if (compare && frame.frame() % 20 == 0) {
            compareAll(cullCamera, frame.height());
        }
        stream.begin();
        stream.write(visible, bounds);

        Stats stats = ctx.stats();
        if (recording) {
            stats.recordNanos(cullSeries[active], t1 - t0);
        }
        stats.record(visibleSeries, n);
    }

    private void keys(FrameInfo frame) {
        var in = frame.input();
        if (in.pressed(GLFW_KEY_M)) {
            active = (active + 1) % methods.size();
        }
        if (in.pressed(GLFW_KEY_N)) {
            active = (active + methods.size() - 1) % methods.size();
        }
        for (int m = 0; m < methods.size(); m++) {
            if (in.pressed(GLFW_KEY_1 + m)) {
                active = m;
            }
        }
        if (in.pressed(GLFW_KEY_U)) {
            animate = !animate;
        }
        if (in.pressed(GLFW_KEY_B)) {
            showBvh = !showBvh;
        }
        if (in.pressed(GLFW_KEY_LEFT_BRACKET)) {
            bvhLevel = Math.max(0, bvhLevel - 1);
        }
        if (in.pressed(GLFW_KEY_RIGHT_BRACKET)) {
            bvhLevel++;
        }
        if (in.pressed(GLFW_KEY_T)) {
            compare = !compare;
        }
        if (in.pressed(GLFW_KEY_X)) {
            freeze = !freeze;
            frozen = freeze ? camera : null;
        }
    }

    private void moveBoxes(FrameInfo frame, boolean recording) {
        int movable = bounds.size() - 1; // everything but the ground
        int per = Math.max(1, movable / WINDOWS);
        int from = (frame.frame() % WINDOWS) * per, to = Math.min(movable, from + per);
        float time = (float) frame.time();
        for (int i = from; i < to; i++) {
            float off = 1.5f * (float) Math.sin(time * 1.7f + i * 0.37f);
            bounds.set(i, base.minX(i), base.minY(i) + off, base.minZ(i), base.maxX(i), base.maxY(i) + off, base.maxZ(i));
        }
        Stats stats = ctx.stats();
        for (int m = 0; m < methods.size(); m++) {
            long t0 = System.nanoTime();
            methods.get(m).moved(bounds, from, to, 1.5f);
            long dt = System.nanoTime() - t0;
            if (m == active) {
                updateMs = dt / 1e6;
                if (recording && updateSeries[m] >= 0) {
                    stats.recordNanos(updateSeries[m], dt);
                }
            }
        }
    }

    /**
     * Compares the active method's result with the scalar kernel's. A box that only the method
     * finds is counted (conservative results are allowed). A box that the scalar kernel finds and
     * the method does not is either <em>marginal</em>, which means that it is within a centimetre
     * of a frustum plane, where the kernels' float rounding legitimately differs and which is
     * counted, or a bug, which ends the run.
     */
    private void verify(CullMethod method, Cameraf cullCamera, int height) {
        if (method == methods.get(0)) {
            return;
        }
        methods.get(0).cull(cullCamera, height, bounds, reference);
        scratch.copyFrom(reference);
        scratch.andNot(visible);
        verifiedFrames++;
        for (int i = scratch.nextSetBit(0); i >= 0; i = scratch.nextSetBit(i + 1)) {
            Aabbf b = bounds.get(i);
            Aabbf slack = new Aabbf(b.minX() - MARGINAL, b.minY() - MARGINAL, b.minZ() - MARGINAL, b.maxX() + MARGINAL, b.maxY() + MARGINAL, b.maxZ() + MARGINAL);
            if (cullCamera.frustum().intersects(slack)) {
                marginalBoxes++;
            } else {
                missedBoxes++;
                throw new IllegalStateException(method.name() + " missed box " + i + " " + b + ", which the scalar kernel finds and which is not within " + MARGINAL
                        + " of a frustum plane");
            }
        }
        scratch.copyFrom(visible);
        scratch.andNot(reference);
        extraBoxes += scratch.count();
    }

    private void compareAll(Cameraf cullCamera, int height) {
        VisibilitySet out = new VisibilitySet(bounds.size());
        for (int m = 0; m < methods.size(); m++) {
            long best = Long.MAX_VALUE;
            for (int r = 0; r < 3; r++) {
                long t0 = System.nanoTime();
                methods.get(m).cull(cullCamera, height, bounds, out);
                best = Math.min(best, System.nanoTime() - t0);
            }
            compareMs[m] = best / 1e6;
        }
    }

    @Override
    public void render(FrameInfo frame) {
        boxes.draw(camera, stream, bounds.size() - 1, 0, 0.00045f);
        if (showBvh || frozen != null) {
            lines.clear();
            if (showBvh) {
                bvhBoxes();
            }
            if (frozen != null) {
                lines.setColor(1f, 0.9f, 0.1f, 1f).frustum(frozen.viewProjection(), vmath.geo.DepthRange.NEGATIVE_ONE_TO_ONE, FAR);
            }
            ctx.debug().draw(lines, camera.viewProjection());
        }
        stream.end();
    }

    /**
     * Adds the boxes of the BVH nodes at one depth (and the leaves that end earlier) to the debug
     * lines, so that the shape of the hierarchy can be seen: coarse nodes at the top, the boxes
     * around a few primitives at the bottom.
     */
    private void bvhBoxes() {
        CullMethod.Bvh bvhMethod = (CullMethod.Bvh) methods.get(3);
        StaticBvh bvh = bvhMethod.tree();
        float[] nb = bvh.nodeBounds();
        int sp = 0;
        bvhStack[sp] = 0;
        bvhDepthStack[sp++] = 0;
        int level = Math.min(bvhLevel, bvhMethod.depth());
        while (sp > 0) {
            int node = bvhStack[--sp], depth = bvhDepthStack[sp];
            boolean leaf = bvh.isLeaf(node);
            if (depth == level || leaf) {
                float t = level == 0 ? 0f : (float) depth / level;
                lines.setColor(1f - t, 0.4f + 0.6f * t, 0.9f * t + 0.1f, 1f);
                int o = node * 6;
                lines.box(nb[o], nb[o + 1], nb[o + 2], nb[o + 3], nb[o + 4], nb[o + 5]);
            } else if (sp + 2 < bvhStack.length) {
                bvhStack[sp] = node + 1;
                bvhDepthStack[sp++] = depth + 1;
                bvhStack[sp] = bvh.rightChild(node);
                bvhDepthStack[sp++] = depth + 1;
            }
        }
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        CullMethod method = methods.get(active);
        hud.line(String.format(Locale.ROOT, "method %d of %d: %s | %,d of %,d boxes in the frustum", active + 1, methods.size(), method.name(), visibleNow, bounds.size()));
        hud.line(String.format(Locale.ROOT, "cull %.3f ms%s%s", cullMs, animate ? String.format(Locale.ROOT, " | update %.3f ms (a fiftieth of the boxes move)", updateMs) : "",
                frozen != null ? " | culling camera frozen (X)" : ""));
        if (compare) {
            hud.gap(6f).color(1f, 0.95f, 0.6f);
            hud.line("all methods on this view (best of 3, ms), build time in brackets:");
            for (int m = 0; m < methods.size(); m++) {
                CullMethod c = methods.get(m);
                String build = c.hasStructure() ? String.format(Locale.ROOT, "  [build %,.0f ms]", buildMs[m]) : "";
                hud.line(String.format(Locale.ROOT, "%s %-14s %8.3f%s", m == active ? ">" : " ", c.name(), compareMs[m], build));
            }
            hud.color(0.8f, 0.9f, 1f);
        }
        if (options.verify()) {
            hud.line(String.format(Locale.ROOT, "verified %,d frames against the scalar kernel: %d missed, %,d marginal, %,d extra", verifiedFrames, missedBoxes, marginalBoxes, extraBoxes));
        }
        if (showBvh) {
            hud.line(String.format(Locale.ROOT, "BVH level %d of %d (the boxes of the nodes at that depth)", Math.min(bvhLevel, ((CullMethod.Bvh) methods.get(3)).depth()),
                    ((CullMethod.Bvh) methods.get(3)).depth()));
        }
    }

    @Override
    public void report(Stats stats) {
        for (int m = 0; m < methods.size(); m++) {
            if (methods.get(m).hasStructure()) {
                stats.note(String.format(Locale.ROOT, "build %s: %,.1f ms for %,d boxes", methods.get(m).name(), buildMs[m], bounds.size()));
            }
        }
        if (options.verify()) {
            stats.note(String.format(Locale.ROOT, "verified %,d frames against the scalar kernel: %d boxes missed, %,d marginal (within %.2f of a plane), %,d extra", verifiedFrames, missedBoxes,
                    marginalBoxes, MARGINAL, extraBoxes));
        }
        stats.note("stalls waiting for the GPU: " + stream.stalls());
    }

    @Override
    public void dispose() {
        stream.close();
        boxes.dispose();
        for (CullMethod m : methods) {
            m.close();
        }
        executor.shutdownNow();
    }
}
