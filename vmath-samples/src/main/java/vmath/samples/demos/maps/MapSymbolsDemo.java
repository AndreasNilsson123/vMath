package vmath.samples.demos.maps;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_N;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;

import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import vmath.core.ClipSpace;
import vmath.geo.GeoFormat;
import vmath.geo.Units;
import vmath.geo.WebMercatorProjection;
import vmath.gl.DrawList;
import vmath.lines.LineRenderPlan;
import vmath.lines.LineSet;
import vmath.lines.LineStyle;
import vmath.lines.TrailBuffer;
import vmath.map.Declutter;
import vmath.map.MapView2d;
import vmath.map.SymbolAtlas;
import vmath.map.SymbolBatch;
import vmath.map.SymbolGpu;
import vmath.map.SymbolRenderPlan;
import vmath.map.SymbolStrategy;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.GpuTimer;
import vmath.samples.framework.Hud;
import vmath.samples.framework.LineRenderer;
import vmath.samples.framework.LineTier;
import vmath.samples.framework.MapRenderers;
import vmath.samples.framework.Stats;

/**
 * Ten thousand moving symbols over a map that turns: each with a heading, drawn by the symbol
 * renderer in each of its three tiers (instanced, texture fetch, expanded) with the CPU time to write
 * the records and the GPU time of each; trails for some of them as fading lines of a {@code LineSet}
 * fed by a {@code TrailBuffer}; labels placed by {@code Declutter} by priority, stable from frame to
 * frame; a pointer that picks the nearest symbol through {@code screenToProjected}; and symbols
 * (the cities) that stay upright on the rotated map while the others turn with it.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class MapSymbolsDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("map-symbols", "Map symbols: ten thousand movers, trails and labels",
            "Ten thousand moving symbols with headings and trails, priority-declutter labels that do not flicker, a pointer that picks the nearest, and symbols that stay upright on a rotating map, in every tier of the symbol renderer.",
            List.of("maps", "symbols", "scale"), 1_048_576L, List.of("--symbols", "1500", "--trails", "40", "--cities", "200", "--tier-frames", "10"),
            "1-3 or N: symbol tier | A: cycle tiers | R: rotate the map | L: labels | left mouse: pan | wheel: zoom | mouse: pick");

    private static final String USAGE = """
            options of the map-symbols demo:
              --symbols N       moving symbols (default 10000)
              --trails N        of them with a trail (default 300)
              --cities N        fixed symbols that stay upright (default 1500)
              --tier-frames N   frames per tier in a scripted run and in the cycle (default 120)
              --label-share N   percent of the symbols that have a label to place (default 30)
              --seed N          seed (default 1)
            """;

    private static final double HALF = 2_500_000.0;
    private static final int TRAIL_POINTS = 16;
    private static final int SLICES = 4;
    private static final double TIME_SCALE = 250.0;
    private static final int LABEL_CADENCE = 3;

    private final int count;
    private final int trailCount;
    private final int cityCount;
    private final int tierFrames;
    private final int seed;
    private final int labelShare;
    private DemoContext ctx;
    private SymbolAtlas atlas;
    private final SymbolStrategy[] strategies = SymbolStrategy.values();
    private final MapRenderers.Symbols[] renderers = new MapRenderers.Symbols[3];
    private final int[] writeSeries = new int[3];
    private final int[] gpuSeries = new int[3];
    private final long[] tierBytes = new long[3];
    private int tier;
    private boolean cycle;
    private int measured;
    private int tierSwitchedAt;
    private GpuTimer timer;
    private final int[] tierRing = new int[4];
    private long issued;
    private SymbolBatch batch;
    private double[] x;
    private double[] y;
    private double[] vx;
    private double[] vy;
    private double[] heading;
    private int[] priority;
    private int[] trailId;
    private LineSet trailSet;
    private TrailBuffer trails;
    private LineRenderer trailRenderer;
    private final DrawList trailDraws = new DrawList(DrawList.Kind.ARRAYS, 64);
    private LineRenderPlan trailPlan;
    private LineStyle[] trailStyles;
    private Declutter declutter;
    private MapView2d view;
    private double centerX;
    private double centerY;
    private double mpp = 2600.0;
    private double rotation;
    private boolean rotating = true;
    private boolean labels = true;
    private int picked = -1;
    private int highlighted = -1;
    private float charWidth = 8f;
    private float charHeight = 14f;
    private int labelItems;
    private int labelsShown;
    private int labelsMoved;
    private double declutterMs;
    private double trailMs;
    private int trailSeries;
    private int declutterSeries;
    private double time;
    private final double[] screen = new double[2];
    private final double[] pointer = new double[2];
    private final int[] shownItems = new int[512];
    private int shownCount;
    private int[] itemSymbol = new int[16384];
    private final float[] cityUv = new float[4];
    private final float[] arrowUv = new float[4];
    private final float[] vpMatrix = new float[16];

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public MapSymbolsDemo(List<String> args) {
        MapArgs a = new MapArgs(args, USAGE);
        count = a.integer("--symbols", 10_000, 10, 200_000);
        trailCount = Math.min(count, a.integer("--trails", 300, 0, 5000));
        cityCount = a.integer("--cities", 1500, 0, 50_000);
        tierFrames = a.integer("--tier-frames", 120, 1, 100_000);
        labelShare = a.integer("--label-share", 30, 1, 100);
        seed = a.integer("--seed", 1, 0, Integer.MAX_VALUE);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        Stats stats = ctx.stats();
        atlas = MapKit.sprites();
        atlas.rect(atlas.indexOf("arrow"), arrowUv);
        atlas.rect(atlas.indexOf("square"), cityUv);
        for (int i = 0; i < 3; i++) {
            var caps = vmath.gl.GraphicsCapabilities.openGl(3, 3, List.of());
            renderers[i] = new MapRenderers.Symbols(SymbolRenderPlan.force(strategies[i], caps), atlas, count + cityCount + 8);
            writeSeries[i] = stats.timer("write " + strategies[i], "CPU time to write the records of the tier " + strategies[i]);
            gpuSeries[i] = stats.timer("gpu " + strategies[i], "GPU time of the symbols in the tier " + strategies[i]);
        }
        trailSeries = stats.timer("trails", "TrailBuffer.updateLines for the tracks of the frame, and the upload of the dirty ranges");
        declutterSeries = stats.timer("declutter", "Declutter.solve for the labels of the visible symbols");
        SplittableRandom rnd = new SplittableRandom(seed);
        batch = new SymbolBatch(count + cityCount + 8);
        x = new double[count];
        y = new double[count];
        vx = new double[count];
        vy = new double[count];
        heading = new double[count];
        priority = new int[count];
        // the map is Web Mercator about a point at 45 degrees north
        double[] c = new double[2];
        WebMercatorProjection.INSTANCE.forward(Math.toRadians(45.0), Math.toRadians(5.0), c);
        centerX = c[0];
        centerY = c[1];
        for (int i = 0; i < cityCount; i++) {
            batch.add(centerX + (rnd.nextDouble() * 2 - 1) * HALF, centerY + (rnd.nextDouble() * 2 - 1) * HALF * 0.6, 0.0, 7f, cityUv, 0xE0C060FF, 0);
        }
        for (int i = 0; i < count; i++) {
            x[i] = centerX + (rnd.nextDouble() * 2 - 1) * HALF;
            y[i] = centerY + (rnd.nextDouble() * 2 - 1) * HALF * 0.6;
            double h = rnd.nextDouble() * Math.PI * 2, s = 150 + rnd.nextDouble() * 150;
            vx[i] = Math.sin(h) * s * TIME_SCALE;
            vy[i] = Math.cos(h) * s * TIME_SCALE;
            heading[i] = h;
            priority[i] = rnd.nextInt(1000);
            batch.add(x[i], y[i], -h, 12f, arrowUv, i < trailCount ? 0x60E0FFFF : 0xFFFFFFE0, SymbolGpu.ROTATE_WITH_MAP);
        }
        // trails
        LineTier lineTier = LineTier.PULL_33;
        trailPlan = lineTier.plan();
        long units = (long) trailCount * (TRAIL_POINTS + SLICES) + 64;
        trailSet = new LineSet(trailPlan, (int) Math.min(Integer.MAX_VALUE / 64L, units * 3 / 2));
        trailSet.setOrigin(centerX, centerY, 0.0);
        trailRenderer = new LineRenderer(trailPlan, lineTier.caps(), trailSet.dataBytes(), ((long) trailCount * SLICES + 64) * 64L);
        trails = new TrailBuffer(Math.max(1, trailCount), TRAIL_POINTS, SLICES);
        trailId = new int[trailCount];
        for (int i = 0; i < trailCount; i++) {
            trailId[i] = trails.addTrack();
        }
        trailStyles = new LineStyle[4];
        int[] colors = {0x60E0FFC0, 0x60E0FF90, 0x60E0FF60, 0x60E0FF30};
        for (int i = 0; i < 4; i++) {
            trailStyles[i] = LineStyle.pixels(1.6f).withColor(colors[i]);
        }
        declutter = new Declutter(64f, 4096);
        declutter.useRingCandidates(20f, 1);
        declutter.setPadding(3f);
        declutter.setStickiness(40);
        timer = new GpuTimer();
        ctx.clearColor(0.07f, 0.1f, 0.14f);
        stats.note(count + " symbols, " + cityCount + " cities, " + trailCount + " trails, tiers " + java.util.Arrays.toString(strategies));
    }

    private void select(int i) {
        if (i >= 0 && i < 3 && i != tier) {
            tier = i;
            tierSwitchedAt = 0;
        }
    }

    @Override
    public void update(FrameInfo frame) {
        float dt = frame.benchmark() ? FrameInfo.SCRIPTED_DT : frame.dt();
        time += dt;
        long ns = timer.poll();
        if (ns >= 0) {
            ctx.stats().recordNanos(gpuSeries[tierRing[Math.floorMod((int) (issued - 4), 4)]], ns);
        }
        var in = frame.input();
        if (frame.benchmark()) {
            if (ctx.stats().measuring()) {
                measured++;
            }
            select(measured / tierFrames % 3);
        } else {
            for (int i = 0; i < 3; i++) {
                if (in.pressed(GLFW_KEY_1 + i)) {
                    select(i);
                    cycle = false;
                }
            }
            if (in.pressed(GLFW_KEY_N)) {
                select((tier + 1) % 3);
                cycle = false;
            }
            if (in.pressed(GLFW_KEY_A)) {
                cycle = !cycle;
            }
            if (cycle && ++tierSwitchedAt >= tierFrames) {
                select((tier + 1) % 3);
            }
            if (in.pressed(GLFW_KEY_R)) {
                rotating = !rotating;
            }
            if (in.pressed(GLFW_KEY_L)) {
                labels = !labels;
            }
            float notches = in.scroll();
            if (notches != 0f) {
                mpp = Math.max(300.0, Math.min(30_000.0, mpp * Math.pow(0.87, notches)));
            }
            if (in.mouseDown(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) && view != null) {
                double[] p = new double[2];
                view.screenToProjected(frame.width() / 2.0 - in.mouseDx(), frame.height() / 2.0 - in.mouseDy(), p);
                centerPanX = p[0] - centerX;
                centerPanY = p[1] - centerY;
            }
        }
        if (rotating) {
            rotation += dt * 0.12;
        }
        double[] ll = new double[2];
        WebMercatorProjection.INSTANCE.inverse(centerX + centerPanX, centerY + centerPanY, ll);
        view = MapView2d.of(WebMercatorProjection.INSTANCE, ll[0], ll[1], frame.width(), frame.height()).withMetersPerPixel(mpp).withOrientation(MapView2d.Orientation.ANGLE, rotation);
        // the movers
        double minX = centerX - HALF, maxX = centerX + HALF, minY = centerY - HALF * 0.6, maxY = centerY + HALF * 0.6;
        for (int i = 0; i < count; i++) {
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            if (x[i] < minX || x[i] > maxX) {
                vx[i] = -vx[i];
                x[i] = Math.max(minX, Math.min(maxX, x[i]));
                heading[i] = Math.atan2(vx[i], vy[i]);
            }
            if (y[i] < minY || y[i] > maxY) {
                vy[i] = -vy[i];
                y[i] = Math.max(minY, Math.min(maxY, y[i]));
                heading[i] = Math.atan2(vx[i], vy[i]);
            }
            batch.setPosition(cityCount + i, x[i], y[i]);
            batch.setAngle(cityCount + i, -heading[i]);
        }
        updateTrails(frame);
        pick(frame, in);
        placeLabels(frame);
    }

    private double centerPanX;
    private double centerPanY;

    private void updateTrails(FrameInfo frame) {
        long t0 = System.nanoTime();
        int f = frame.frame();
        double maxAge = (TRAIL_POINTS - 1) * 6 * (double) FrameInfo.SCRIPTED_DT * 2.0;
        for (int i = 0; i < trailCount; i++) {
            if ((f + i) % 6 == 0) {
                trails.push(trailId[i], x[i], y[i], 0.0, time);
            }
            if ((f + i) % 3 == 0) {
                trails.updateLines(trailSet, trailId[i], time, maxAge, trailStyles[0]);
            }
        }
        int n = trailSet.update(trailRenderer.dataMirror(), trailRenderer.styleMirror(), trailDraws);
        for (int i = 0; i < trailSet.dirtyRangeCount(); i++) {
            trailRenderer.uploadData(trailSet.dirtyOffset(i), trailSet.dirtyLength(i));
        }
        trailRenderer.uploadStyles(trailSet.styleBytes());
        trailRenderer.setDraws(trailDraws);
        trailMs = (System.nanoTime() - t0) / 1e6;
        ctx.stats().recordNanos(trailSeries, System.nanoTime() - t0);
        if (n < 0) {
            trailMs = -1;
        }
    }

    private void pick(FrameInfo frame, vmath.samples.framework.Input in) {
        double mx = frame.benchmark() ? frame.width() * 0.5 : in.mouseX(), my = frame.benchmark() ? frame.height() * 0.5 : in.mouseY();
        view.screenToProjected(mx, my, pointer);
        double best = Double.MAX_VALUE;
        int bestIndex = -1;
        for (int i = 0; i < count; i++) {
            double dx = x[i] - pointer[0], dy = y[i] - pointer[1], d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                bestIndex = i;
            }
        }
        double limit = 24.0 * view.mapUnitsPerPixel();
        picked = best <= limit * limit ? bestIndex : -1;
        if (highlighted != picked) {
            if (highlighted >= 0) {
                batch.setColor(cityCount + highlighted, highlighted < trailCount ? 0x60E0FFFF : 0xFFFFFFE0);
            }
            if (picked >= 0) {
                batch.setColor(cityCount + picked, 0xFF3030FF);
            }
            highlighted = picked;
        }
    }

    private static String callsign(int i) {
        return "" + (char) ('A' + i % 26) + (char) ('A' + i / 26 % 26) + (1000 + i * 37 % 9000);
    }

    private void placeLabels(FrameInfo frame) {
        if (labels && labelItems > 0 && frame.frame() % LABEL_CADENCE != 0) {
            return;                        // the placement of the last solve stands for the frames in between: the labels follow their symbols, and stickiness keeps them from jumping
        }
        labelItems = 0;
        labelsShown = 0;
        labelsMoved = 0;
        shownCount = 0;
        declutterMs = 0;
        if (!labels) {
            return;
        }
        long t0 = System.nanoTime();
        declutter.begin();
        declutter.setClip(0f, 0f, frame.width(), frame.height());
        float w = 6 * charWidth, h = charHeight;
        for (int i = 0; i < count; i++) {
            if (priority[i] < 1000 - labelShare * 10 && i != picked) {
                continue;
            }
            view.projectedToScreen(x[i], y[i], screen);
            if (screen[0] < 0 || screen[0] >= frame.width() || screen[1] < 0 || screen[1] >= frame.height()) {
                continue;
            }
            int item = declutter.add(i, (float) screen[0], (float) screen[1] + 14f, w, h, priority[i] + (i == picked ? 100_000 : 0), i == picked);
            if (item >= itemSymbol.length) {
                itemSymbol = java.util.Arrays.copyOf(itemSymbol, itemSymbol.length * 2);
            }
            itemSymbol[item] = i;
        }
        labelItems = declutter.count();
        labelsShown = declutter.solve();
        for (int item = 0; item < labelItems && shownCount < shownItems.length; item++) {
            if (declutter.shown(item)) {
                shownItems[shownCount++] = item;
                labelsMoved += declutter.needsLeader(item) ? 1 : 0;
            }
        }
        declutterMs = (System.nanoTime() - t0) / 1e6;
        ctx.stats().recordNanos(declutterSeries, System.nanoTime() - t0);
    }

    @Override
    public void render(FrameInfo frame) {
        double ox = centerX, oy = centerY;
        view.viewProjection(ClipSpace.OPENGL, ox, oy, vpMatrix);
        // the trails first, then the symbols over them
        if (trailCount > 0) {
            org.lwjgl.opengl.GL46.glEnable(org.lwjgl.opengl.GL46.GL_BLEND);
            org.lwjgl.opengl.GL46.glBlendFunc(org.lwjgl.opengl.GL46.GL_SRC_ALPHA, org.lwjgl.opengl.GL46.GL_ONE_MINUS_SRC_ALPHA);
            trailRenderer.draw(vpMatrix, (float) view.pixelsPerMapUnit(), frame.width(), frame.height());
            org.lwjgl.opengl.GL46.glDisable(org.lwjgl.opengl.GL46.GL_BLEND);
        }
        long t0 = System.nanoTime();
        tierBytes[tier] = renderers[tier].upload(batch, ox, oy, null);
        ctx.stats().recordNanos(writeSeries[tier], System.nanoTime() - t0);
        tierRing[(int) (issued & 3)] = tier;
        timer.begin();
        renderers[tier].draw(vpMatrix, frame.width(), frame.height(), SymbolRenderPlan.mapRotation(view), (float) view.pixelsPerMapUnit());
        timer.end();
        issued++;
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        charWidth = hud.advance();
        charHeight = hud.lineHeight();
        Stats stats = ctx.stats();
        hud.color(1f, 1f, 1f).line(MapKit.ascii(String.format(Locale.ROOT, "tier %d of 3: %s%s; %d moving symbols, %d upright cities, %d trails", tier + 1, strategies[tier], cycle ? " (cycling)" : "", count,
                cityCount, trailCount)));
        hud.line(String.format(Locale.ROOT, "records: %.2f MB written in %.3f ms (CPU), GPU %.3f ms, %d draw call%s", tierBytes[tier] / 1048576.0, stats.last(writeSeries[tier]), stats.last(gpuSeries[tier]),
                renderers[tier].calls(), renderers[tier].calls() == 1 ? "" : "s"));
        hud.line(String.format(Locale.ROOT, "trails: %.3f ms, %d dirty ranges; labels: %d candidates, %d placed, %d off their anchor, solved in %.3f ms", trailMs, trailSet.dirtyRangeCount(), labelItems, labelsShown,
                labelsMoved, declutterMs));
        hud.line(String.format(Locale.ROOT, "map turned to %.0f degrees (%s)", Math.toDegrees(rotation) % 360.0, rotating ? "turning" : "held"));
        if (picked >= 0) {
            double[] ll = new double[2];
            WebMercatorProjection.INSTANCE.inverse(x[picked], y[picked], ll);
            hud.color(1f, 0.5f, 0.5f).line(MapKit.ascii(callsign(picked) + "  heading " + GeoFormat.bearing(heading[picked], 0) + "  " + GeoFormat.speed(Math.hypot(vx[picked], vy[picked]) / TIME_SCALE,
                    Units.Speed.KNOTS, 0) + "  " + GeoFormat.latitude(ll[0], GeoFormat.Format.DEGREES_MINUTES, 1) + " " + GeoFormat.longitude(ll[1], GeoFormat.Format.DEGREES_MINUTES, 1, true)));
        }
        for (int s = 0; s < shownCount; s++) {
            int item = shownItems[s];
            int sym = itemSymbol[item];
            view.projectedToScreen(x[sym], y[sym], screen);
            float lx = (float) screen[0] + declutter.offsetX(item) - 3 * charWidth, ly = (float) screen[1] + 14f + declutter.offsetY(item) - 0.5f * charHeight;
            hud.color(sym == picked ? 1f : 0.85f, sym == picked ? 0.4f : 0.95f, sym == picked ? 0.4f : 1f).text(lx, ly, callsign(sym));
        }
        for (int i = 0; i < 3; i++) {
            hud.color(i == tier ? 1f : 0.7f, i == tier ? 1f : 0.7f, i == tier ? 0.5f : 0.7f).line(String.format(Locale.ROOT, "%d  %s   write %.3f ms, gpu %.3f ms", i + 1, strategies[i], stats.average(writeSeries[i]),
                    stats.average(gpuSeries[i])));
        }
    }

    @Override
    public void report(Stats stats) {
        for (int i = 0; i < 3; i++) {
            stats.note(String.format(Locale.ROOT, "%-14s write %.3f ms, gpu %.3f ms, %.2f MB, %d call%s", strategies[i], stats.average(writeSeries[i]), stats.average(gpuSeries[i]),
                    tierBytes[i] / 1048576.0, renderers[i].calls(), renderers[i].calls() == 1 ? "" : "s"));
        }
        stats.note(String.format(Locale.ROOT, "declutter %.3f ms for %d candidates, %d placed; trails %.3f ms", stats.average(declutterSeries), labelItems, labelsShown, stats.average(trailSeries)));
    }

    @Override
    public void dispose() {
        for (MapRenderers.Symbols r : renderers) {
            if (r != null) {
                r.close();
            }
        }
        if (trailRenderer != null) {
            trailRenderer.close();
        }
        if (timer != null) {
            timer.dispose();
        }
    }
}
