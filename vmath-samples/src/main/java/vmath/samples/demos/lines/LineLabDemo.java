package vmath.samples.demos.lines;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_N;

import java.util.List;
import java.util.Locale;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.DrawList;
import vmath.lines.LineBatch;
import vmath.lines.LineRenderPlan;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.GpuTimer;
import vmath.samples.framework.Hud;
import vmath.samples.framework.LineRenderer;
import vmath.samples.framework.LineTier;
import vmath.samples.framework.PanZoom2d;
import vmath.samples.framework.Stats;
import vmath.samples.verify.LineGpuCheck;

/**
 * One network of thousands of polylines, drawn by every tier of the line renderer: the same
 * {@code LineBatch}, written and drawn six times with the capabilities (and so the strategy, the
 * GLSL version and the way of submitting the draws) of six contexts from OpenGL 4.5 down to 3.3.
 * For each tier the demo shows the draw calls, the bytes, the CPU time to write the buffers, the CPU
 * time to issue the draws, the GPU time, and how many pixels differ from the reference expansion
 * of the library.
 *
 * <p>The pieces of the library: {@code LineBatch} and {@code LineStyle} (the data), {@code
 * LineRenderPlan} (the strategy, the shaders, the buffers and the draws for a set of capabilities),
 * {@code DrawList} and {@code DrawSubmission} (the way the draws are issued) and {@code
 * LineExpander} with {@code CoverageRaster} (the reference that the pixels are compared with, in a
 * 256 by 256 probe drawn at the start). The window is a pan and zoom of the plane; lines with a
 * width in pixels keep it, rivers have a width in world units and grow with the zoom, and nothing
 * is rebuilt when the view changes.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class LineLabDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("line-lab", "Line lab: one network, six ways to draw it",
            "The same five thousand polylines are drawn by every tier of the line renderer, from an indirect multi-draw on OpenGL 4.5 down to line strips on 3.3, with the calls, the bytes and the times of each.",
            List.of("lines", "rendering", "scale"), 4096, List.of("--polylines", "400", "--points", "24", "--tier-frames", "8"),
            "1-6 or N: tier | A: cycle the tiers | left mouse: pan | wheel: zoom");

    private static final String USAGE = """
            options of the line-lab demo:
              --polylines N     polylines in the network (default 5000)
              --points N        points of each polyline (default 64)
              --tier-frames N   frames per tier in a scripted run and in the automatic cycle (default 120)
              --tier N          the tier to start with, 1 to 6 (default 1)
              --seed N          seed of the network (default 1)
            """;

    /** What one tier needs, built once at the start. */
    private static final class TierState {
        LineRenderPlan plan;
        LineRenderer renderer;
        int draws;
        int calls;
        long segments;
        long dataBytes;
        long styleBytes;
        long commandBytes;
        double writeMs;
        int[] probe;
        String submission;
        String unavailable;
        int gpuSeries;
        int submitSeries;
    }

    private final int polylines;
    private final int points;
    private final int tierFrames;
    private final int firstTier;
    private final int seed;
    private DemoContext ctx;
    private LineBatch batch;
    private final LineTier[] tiers = LineTier.values();
    private TierState[] states;
    private int tier;
    private boolean cycle;
    private int measured;
    private PanZoom2d view;
    private final double[] worldMatrix = new double[16];
    private final float[] relativeMatrix = new float[16];
    private GpuTimer timer;
    private long issued;
    private final int[] tierRing = new int[4];
    private int tierSwitchedAt;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public LineLabDemo(List<String> args) {
        LineArgs a = new LineArgs(args, USAGE);
        polylines = a.integer("--polylines", 5000, 10, 200_000);
        points = a.integer("--points", 64, 3, 4096);
        tierFrames = a.integer("--tier-frames", 120, 1, 100_000);
        firstTier = a.integer("--tier", 1, 1, LineTier.values().length) - 1;
        seed = a.integer("--seed", 1, 0, Integer.MAX_VALUE);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        batch = new LineBatch();
        batch.setOrigin(NetworkScene.WIDTH / 2, NetworkScene.HEIGHT / 2, 0.0);
        NetworkScene.generate(polylines, points, seed, (xyz, n, closed, style) -> batch.addPolyline(xyz, 0, n, closed, style));
        LineBatch probeBatch = new LineBatch();
        probeBatch.setOrigin(NetworkScene.WIDTH / 2, NetworkScene.HEIGHT / 2, 0.0);
        NetworkScene.generate(1500, Math.min(points, 32), seed + 1L, (xyz, n, closed, style) -> probeBatch.addPolyline(xyz, 0, n, closed, style));
        // the probe looks at 1600 by 1600 units around the middle, in 256 by 256 pixels
        float k = 2f * 0.16f / 256f;
        float[] probeMatrix = {k, 0, 0, 0, 0, k, 0, 0, 0, 0, 1f, 0, 0, 0, 0, 1f};

        Stats stats = ctx.stats();
        states = new TierState[tiers.length];
        for (int i = 0; i < tiers.length; i++) {
            TierState s = new TierState();
            states[i] = s;
            s.gpuSeries = stats.timer("gpu " + tiers[i].shortName(), "GPU time of the lines in the tier " + tiers[i].label());
            s.submitSeries = stats.timer("submit " + tiers[i].shortName(), "CPU time to issue the draws of the tier " + tiers[i].label());
            try {
                build(i, s, probeBatch, probeMatrix);
            } catch (RuntimeException e) {
                s.unavailable = String.valueOf(e.getMessage()).split("\n")[0];
                stats.note("tier " + tiers[i].shortName() + " is not available on this driver: " + s.unavailable);
            }
        }
        tier = Math.max(0, Math.min(firstTier, tiers.length - 1));
        while (states[tier].unavailable != null && tier + 1 < tiers.length) {
            tier++;
        }
        view = new PanZoom2d(NetworkScene.WIDTH / 2, NetworkScene.HEIGHT / 2, 0.2, 0.03, 12.0);
        timer = new GpuTimer();
        ctx.clearColor(0.94f, 0.93f, 0.9f);
    }

    private void build(int index, TierState s, LineBatch probeBatch, float[] probeMatrix) {
        LineTier t = tiers[index];
        s.plan = t.plan();
        s.dataBytes = s.plan.dataBytes(batch);
        s.styleBytes = s.plan.styleBytes(batch);
        s.segments = batch.segmentCount();
        s.renderer = new LineRenderer(s.plan, t.caps(), s.dataBytes, s.styleBytes);
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1024);
        double best = Double.MAX_VALUE;
        for (int rep = 0; rep < 4; rep++) {
            long t0 = System.nanoTime();
            s.plan.write(batch, s.renderer.dataMirror(), s.renderer.styleMirror(), draws);
            best = Math.min(best, (System.nanoTime() - t0) / 1e6);
        }
        s.writeMs = best;
        s.draws = draws.size();
        s.renderer.uploadData(0, s.dataBytes);
        s.renderer.uploadStyles(s.styleBytes);
        s.calls = s.renderer.setDraws(draws);
        s.submission = s.renderer.submission().name();
        s.commandBytes = s.renderer.submission() == vmath.gl.DrawSubmission.MULTI_DRAW_INDIRECT ? (long) draws.size() * DrawCommandBuffer.Kind.ARRAYS.bytes()
                : s.renderer.submission() == vmath.gl.DrawSubmission.MULTI_DRAW_CLIENT ? 8L * draws.size() : 0L;
        if (s.plan.strategy() != vmath.lines.LineStrategy.HAIRLINE) {
            s.probe = LineGpuCheck.probe(s.plan, t.caps(), probeBatch, probeMatrix, 0.16f, 256);
        }
        ctx.stats().note(String.format(Locale.ROOT, "tier %s: %d draws, %d calls (%s), data %.2f MB, styles %.2f KB, commands %d B, write %.2f ms for %d segments%s", t.shortName(), s.draws, s.calls,
                s.submission, s.dataBytes / 1048576.0, s.styleBytes / 1024.0, s.commandBytes, s.writeMs, s.segments, s.probe == null ? ", no triangles to compare"
                        : ", probe of " + s.probe[0] + " pixels: " + s.probe[1] + " missing, " + s.probe[2] + " extra"));
    }

    private int usable() {
        int n = 0;
        for (TierState s : states) {
            n += s.unavailable == null ? 1 : 0;
        }
        return n;
    }

    private int nthUsable(int k) {
        for (int i = 0; i < states.length; i++) {
            if (states[i].unavailable == null && k-- == 0) {
                return i;
            }
        }
        return 0;
    }

    private void select(int index) {
        if (index >= 0 && index < states.length && states[index].unavailable == null && index != tier) {
            tier = index;
            tierSwitchedAt = 0;
        }
    }

    @Override
    public void update(FrameInfo frame) {
        long ns = timer.poll();
        if (ns >= 0) {
            ctx.stats().recordNanos(states[tierRing[Math.floorMod((int) (issued - 4), 4)]].gpuSeries, ns);
        }
        if (frame.benchmark()) {
            if (ctx.stats().measuring()) {
                measured++;
            }
            select(nthUsable(measured / tierFrames % usable()));
            double t = frame.time();
            view.place(NetworkScene.WIDTH / 2 + 2200 * Math.sin(t * 0.31), NetworkScene.HEIGHT / 2 + 1100 * Math.sin(t * 0.23), 0.25 * Math.exp(0.9 * Math.sin(t * 0.4)));
            return;
        }
        var in = frame.input();
        for (int i = 0; i < tiers.length; i++) {
            if (in.pressed(GLFW_KEY_1 + i)) {
                select(i);
                cycle = false;
            }
        }
        if (in.pressed(GLFW_KEY_N)) {
            select(nthUsable((indexAmongUsable() + 1) % usable()));
            cycle = false;
        }
        if (in.pressed(GLFW_KEY_A)) {
            cycle = !cycle;
        }
        if (cycle && ++tierSwitchedAt >= tierFrames) {
            select(nthUsable((indexAmongUsable() + 1) % usable()));
        }
        view.update(frame, false);
    }

    private int indexAmongUsable() {
        int k = 0;
        for (int i = 0; i < tier; i++) {
            k += states[i].unavailable == null ? 1 : 0;
        }
        return k;
    }

    @Override
    public void render(FrameInfo frame) {
        TierState s = states[tier];
        view.viewProjection(frame.width(), frame.height(), worldMatrix);
        batch.relativeViewProjection(worldMatrix, relativeMatrix);
        tierRing[(int) (issued & 3)] = tier;
        timer.begin();
        long t0 = System.nanoTime();
        s.renderer.draw(relativeMatrix, (float) view.scale(), frame.width(), frame.height());
        ctx.stats().recordNanos(s.submitSeries, System.nanoTime() - t0);
        timer.end();
        issued++;
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        TierState s = states[tier];
        Stats stats = ctx.stats();
        hud.color(1f, 1f, 1f).line(String.format(Locale.ROOT, "tier %d of %d: %s%s", tier + 1, tiers.length, tiers[tier].label(), cycle ? "   (cycling)" : ""));
        hud.line(String.format(Locale.ROOT, "%d polylines, %d segments, %d styles; %d draws in %d call%s (%s)", batch.polylineCount(), s.segments, batch.styleCount(), s.draws, s.calls,
                s.calls == 1 ? "" : "s", s.submission));
        hud.line(String.format(Locale.ROOT, "data %.2f MB, styles %.2f KB, commands %d B; written in %.2f ms on the CPU", s.dataBytes / 1048576.0, s.styleBytes / 1024.0, s.commandBytes, s.writeMs));
        hud.line(String.format(Locale.ROOT, "per frame: GPU %.3f ms, issuing the draws %.3f ms", stats.last(s.gpuSeries), stats.last(s.submitSeries)));
        hud.line(s.probe == null ? "against the reference: line strips have no triangles to compare" : String.format(Locale.ROOT,
                "against the reference (256 by 256 probe): %d pixels covered, %d missing, %d extra", s.probe[0], s.probe[1], s.probe[2]));
        hud.gap(4f);
        for (int i = 0; i < tiers.length; i++) {
            hud.color(i == tier ? 1f : 0.7f, i == tier ? 1f : 0.7f, i == tier ? 0.5f : 0.7f).line((i + 1) + "  " + tiers[i].shortName() + (states[i].unavailable != null ? "  (not available here)"
                    : String.format(Locale.ROOT, "   gpu %.3f ms, %d call%s", stats.average(states[i].gpuSeries), states[i].calls, states[i].calls == 1 ? "" : "s")));
        }
    }

    @Override
    public void report(Stats stats) {
        for (int i = 0; i < tiers.length; i++) {
            TierState s = states[i];
            if (s.unavailable == null) {
                stats.note(String.format(Locale.ROOT, "%-13s gpu %.3f ms, issuing %.3f ms, %d calls, data %.2f MB", tiers[i].shortName(), stats.average(s.gpuSeries), stats.average(s.submitSeries), s.calls,
                        s.dataBytes / 1048576.0));
            }
        }
    }

    @Override
    public void dispose() {
        if (states != null) {
            for (TierState s : states) {
                if (s.renderer != null) {
                    s.renderer.close();
                }
            }
        }
        if (timer != null) {
            timer.dispose();
        }
    }
}
