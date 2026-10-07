package vmath.samples.demos.lines;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_N;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;

import java.util.List;
import java.util.Locale;
import vmath.gl.DrawList;
import vmath.lines.LineSet;
import vmath.lines.LineStyle;
import vmath.lines.LineStrategy;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Hud;
import vmath.samples.framework.LineRenderer;
import vmath.samples.framework.LineTier;
import vmath.samples.framework.PanZoom2d;
import vmath.samples.framework.Stats;
import vmath.samples.verify.LineGpuCheck;

/**
 * The style model of the line renderer on one panel: widths from one to thirty pixels, the nine
 * combinations of cap and join, sharp turns with two miter limits, dash patterns on lines and on a
 * circle, and lines with a width in world units beside lines with a width in pixels. Zooming and
 * panning only change a matrix; nothing is rebuilt, which is what the world-unit widths and dashes
 * show (they grow with the zoom), while {@code Z} makes the dashes keep their length in pixels by
 * rewriting the style table and nothing else.
 *
 * <p>The pieces of the library: {@code LineStyle} ({@code pixels}, {@code world}, caps, joins,
 * {@code withMiterLimit}, {@code withDash}, {@code dashedInPixels}), {@code LineBatch}, {@code
 * LineRenderPlan.write} for the rewrite, and {@code LineExpander} with {@code CoverageRaster} for
 * the reference: {@code C} (and every change of tier in a scripted run) draws a 512 by 512 square
 * of the current view offscreen with the tier's shaders and counts the pixels that differ from the
 * reference. The geometry has no anti-aliasing fringe, so the comparison is exact up to the edge
 * pixels that two rasterisers decide differently.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class LineStylesDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("line-styles", "Line styles: widths, caps, joins and dashes",
            "Widths from a pixel to thirty, caps, joins, miter limits, dashes and widths in world units are drawn by the same shaders in every tier, zoomed without rebuilding and compared with the reference.",
            List.of("lines", "rendering"), 16384, List.of("--tier-frames", "100000", "--dashes", "1"),
            "1-6 or N: tier | Z: dashes in pixels | C: compare with the reference | left mouse: pan | wheel: zoom");

    private static final String USAGE = """
            options of the line-styles demo:
              --tier-frames N   frames per tier in a scripted run (default 90)
              --tier N          the tier to start with, 1 to 6 (default 1)
              --dashes N        in a scripted run: 0 alternate world units and pixels, 1 pixels, 2 world units (default 0)
            """;

    private static final int PROBE = 512;

    private final int tierFrames;
    private final int firstTier;
    private final int dashMode;
    private DemoContext ctx;
    private final LineTier[] tiers = LineTier.values();
    private LineRenderer[] renderers;
    private String[] unavailable;
    private int tier;
    private LineSet[] sets;
    private long[][] dashed;
    private final DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 64);
    private boolean pixelDashes;
    private double builtPixelSize;
    private boolean builtPixelDashes;
    private PanZoom2d view;
    private final double[] worldMatrix = new double[16];
    private final float[] relativeMatrix = new float[16];
    private int measured;
    private int[] lastProbe;
    private String lastProbeTier = "";
    private int worstMissing;
    private int worstExtra;
    private int rewriteSeries;
    private int missingSeries;
    private int extraSeries;
    private int rewrites;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public LineStylesDemo(List<String> args) {
        LineArgs a = new LineArgs(args, USAGE);
        tierFrames = a.integer("--tier-frames", 90, 1, 100_000);
        firstTier = a.integer("--tier", 1, 1, LineTier.values().length) - 1;
        dashMode = a.integer("--dashes", 0, 0, 2);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        Stats stats = ctx.stats();
        rewriteSeries = stats.timer("rewrite", "writing the buffers of the panel again when the dashes follow the zoom");
        missingSeries = stats.series("probe: missing", "px", 0, "fully covered pixels of the reference that the GPU left empty, at each comparison");
        extraSeries = stats.series("probe: extra", "px", 0, "pixels the GPU drew where the reference has almost no coverage, at each comparison");
        renderers = new LineRenderer[tiers.length];
        sets = new LineSet[tiers.length];
        dashed = new long[tiers.length][StyleScene.DASHED];
        unavailable = new String[tiers.length];
        for (int i = 0; i < tiers.length; i++) {
            try {
                var plan = tiers[i].plan();
                sets[i] = new LineSet(plan, 4096);
                sets[i].setOrigin(StyleScene.WIDTH / 2, StyleScene.HEIGHT / 2, 0.0);
                LineSet set = sets[i];
                StyleScene.populate((xyz, n, closed, style) -> set.add(xyz, 0, n, closed, style), dashed[i]);
                renderers[i] = new LineRenderer(plan, tiers[i].caps(), set.dataBytes(), 4096 * 64L);
            } catch (RuntimeException e) {
                unavailable[i] = String.valueOf(e.getMessage()).split("\n")[0];
                stats.note("tier " + tiers[i].shortName() + " is not available on this driver: " + unavailable[i]);
            }
        }
        tier = Math.min(firstTier, tiers.length - 1);
        while (unavailable[tier] != null && tier + 1 < tiers.length) {
            tier++;
        }
        view = new PanZoom2d(StyleScene.WIDTH / 2, StyleScene.HEIGHT / 2, 0.7, 0.1, 10.0);
        rewrite(tier);
        probe();
        ctx.clearColor(0.97f, 0.97f, 0.95f);
    }

    /** Writes what changed in the set of a tier and uploads it. */
    private void rewrite(int index) {
        LineRenderer r = renderers[index];
        LineSet set = sets[index];
        long t0 = System.nanoTime();
        set.update(r.dataMirror(), r.styleMirror(), draws);
        for (int i = 0; i < set.dirtyRangeCount(); i++) {
            r.uploadData(set.dirtyOffset(i), set.dirtyLength(i));
        }
        r.uploadStyles(set.styleBytes());
        r.setDraws(draws);
        ctx.stats().recordNanos(rewriteSeries, System.nanoTime() - t0);
        rewrites++;
    }

    private void select(int index) {
        if (index >= 0 && index < tiers.length && unavailable[index] == null && index != tier) {
            tier = index;
            rewrite(tier);
            probe();
        }
    }

    private int usable() {
        int n = 0;
        for (String u : unavailable) {
            n += u == null ? 1 : 0;
        }
        return n;
    }

    private int nthUsable(int k) {
        for (int i = 0; i < tiers.length; i++) {
            if (unavailable[i] == null && k-- == 0) {
                return i;
            }
        }
        return 0;
    }

    private int indexAmongUsable() {
        int k = 0;
        for (int i = 0; i < tier; i++) {
            k += unavailable[i] == null ? 1 : 0;
        }
        return k;
    }

    /** Draws the middle of the current view offscreen with the shaders of the tier and counts the differences from the reference. */
    private void probe() {
        LineRenderer r = renderers[tier];
        if (r.plan().strategy() == LineStrategy.HAIRLINE) {
            lastProbe = null;
            lastProbeTier = tiers[tier].shortName();
            return;
        }
        view.viewProjection(PROBE, PROBE, worldMatrix);
        sets[tier].relativeViewProjection(worldMatrix, relativeMatrix);
        lastProbe = LineGpuCheck.probe(r.plan(), tiers[tier].caps(), sets[tier].toBatch(), relativeMatrix, (float) view.scale(), PROBE);
        lastProbeTier = tiers[tier].shortName();
        worstMissing = Math.max(worstMissing, lastProbe[1]);
        worstExtra = Math.max(worstExtra, lastProbe[2]);
        ctx.stats().record(missingSeries, lastProbe[1]);
        ctx.stats().record(extraSeries, lastProbe[2]);
    }

    @Override
    public void update(FrameInfo frame) {
        if (frame.benchmark()) {
            if (ctx.stats().measuring()) {
                measured++;
            }
            select(nthUsable(measured / tierFrames % usable()));
            double t = frame.time();
            view.place(StyleScene.WIDTH / 2 + 300 * Math.sin(t * 0.37), StyleScene.HEIGHT / 2 + 150 * Math.sin(t * 0.29), 0.8 * Math.exp(0.8 * Math.sin(t * 0.5)));
            pixelDashes = dashMode == 1 || dashMode == 0 && (measured / (tierFrames * 2)) % 2 == 1;
        } else {
            var in = frame.input();
            for (int i = 0; i < tiers.length; i++) {
                if (in.pressed(GLFW_KEY_1 + i)) {
                    select(i);
                }
            }
            if (in.pressed(GLFW_KEY_N)) {
                select(nthUsable((indexAmongUsable() + 1) % usable()));
            }
            if (in.pressed(GLFW_KEY_Z)) {
                pixelDashes = !pixelDashes;
            }
            if (in.pressed(GLFW_KEY_C)) {
                probe();
            }
            view.update(frame, false);
        }
        // the dashes in pixels need the size of a pixel on the ground: the style table is written again when it changed
        double pixelSize = 1.0 / view.scale();
        if (pixelDashes != builtPixelDashes || pixelDashes && Math.abs(pixelSize - builtPixelSize) > 0.002 * pixelSize) {
            builtPixelDashes = pixelDashes;
            builtPixelSize = pixelSize;
            for (int d = 0; d < StyleScene.DASHED; d++) {
                LineStyle style = StyleScene.dashed(d, pixelDashes, (float) pixelSize);
                for (int i = 0; i < tiers.length; i++) {
                    if (unavailable[i] == null) {
                        sets[i].setStyle(dashed[i][d], style);
                    }
                }
            }
            rewrite(tier);
        }
    }

    @Override
    public void render(FrameInfo frame) {
        view.viewProjection(frame.width(), frame.height(), worldMatrix);
        sets[tier].relativeViewProjection(worldMatrix, relativeMatrix);
        ctx.gpu().begin();
        renderers[tier].draw(relativeMatrix, (float) view.scale(), frame.width(), frame.height());
        ctx.gpu().end();
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.color(0.05f, 0.05f, 0.1f).line(String.format(Locale.ROOT, "tier %d of %d: %s", tier + 1, tiers.length, tiers[tier].label()));
        hud.line(String.format(Locale.ROOT, "scale %.2f pixels per unit; dashes in %s%s", view.scale(), pixelDashes ? "pixels (the dashed polylines get a new style when the zoom changes)" : "world units (they grow with the zoom)",
                pixelDashes ? String.format(Locale.ROOT, ", %d rewrites", rewrites) : ""));
        hud.line(lastProbe == null ? "against the reference: press C (the line strips of the last tier have no triangles to compare)" : String.format(Locale.ROOT,
                "against the reference, %s: %d pixels covered, %d missing, %d extra (worst of the run: %d, %d)", lastProbeTier, lastProbe[0], lastProbe[1], lastProbe[2], worstMissing, worstExtra));
        hud.line("rows, from the top: widths 1 2 3 5 8 12 20 30 px | caps (butt, square, round) by joins (miter, bevel, round), and the miter limits 8 and 1.5");
        hud.line("then: dashes and a closed ring | a width in world units (left fan) and in pixels (right fan)");
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "worst of the comparisons with the reference over all tiers: %d fully covered pixels missing, %d extra; rewrites of the style table: %d", worstMissing, worstExtra, rewrites));
    }

    @Override
    public void dispose() {
        if (renderers != null) {
            for (LineRenderer r : renderers) {
                if (r != null) {
                    r.close();
                }
            }
        }
    }
}
