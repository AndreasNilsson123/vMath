package vmath.samples.demos.lines;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_K;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_N;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_U;
import static org.lwjgl.opengl.GL46.GL_BLEND;
import static org.lwjgl.opengl.GL46.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL46.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL46.glBlendFunc;
import static org.lwjgl.opengl.GL46.glDisable;
import static org.lwjgl.opengl.GL46.glEnable;

import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import vmath.gl.DrawList;
import vmath.lines.LineSet;
import vmath.lines.LineStrategy;
import vmath.lines.LineStyle;
import vmath.lines.TrailBuffer;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Hud;
import vmath.samples.framework.LineRenderer;
import vmath.samples.framework.LineTier;
import vmath.samples.framework.PanZoom2d;
import vmath.samples.framework.Stats;

/**
 * A few thousand polylines that are edited, removed and added every frame, and a hundred tracks
 * that leave a fading trail, all in one {@code LineSet}: the demo shows how few bytes the dirty
 * ranges upload compared with writing and uploading everything, what the handles guarantee, and
 * what fragmentation and compaction do to the number of draws.
 *
 * <p>The pieces of the library: {@code LineSet} ({@code add}, {@code set}, {@code remove}, {@code
 * update}, {@code dirtyOffset} and {@code dirtyLength}, {@code compact}, the free-list slots), {@code
 * TrailBuffer} (the ring of positions and the polylines that fade with age), {@code LineRenderPlan}
 * through {@code LineRenderer} (every tier, as in the line lab). {@code U} switches between
 * uploading the dirty ranges and rewriting and uploading every record of every frame, {@code K}
 * compacts now. In a scripted run the two modes alternate and each has its own series.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class LineStreamDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("line-stream", "Line stream: edits, trails and dirty ranges",
            "A few thousand polylines change every frame and a hundred tracks leave fading trails in one LineSet, which uploads only the bytes that changed, with stable handles and compaction.",
            List.of("lines", "memory", "scale"), 16384, List.of("--polylines", "400", "--tracks", "12", "--mode-frames", "10"),
            "U: dirty ranges / upload everything | K: compact now | 1-6 or N: tier | left mouse: pan | wheel: zoom");

    private static final String USAGE = """
            options of the line-stream demo:
              --polylines N        polylines that are edited and replaced (default 3000)
              --edit-percent N     percent of them edited in place every frame (default 5)
              --churn-percent N    percent of them removed and added again every frame (default 1)
              --tracks N           moving tracks with a trail (default 100)
              --trail-points N     positions kept per track (default 64)
              --mode-frames N      frames per upload mode in a scripted run (default 150)
              --compact-every N    frames between compactions in a scripted run, 0 for none (default 200)
              --tier N             the tier, 1 to 6 (default 2)
              --seed N             seed of the scene (default 1)
            """;

    private static final double WORLD_W = 6000.0;
    private static final double WORLD_H = 3400.0;
    private static final int SLICES = 8;
    private static final LineStyle[] STYLES = {
            LineStyle.pixels(2f).withColor(0x8899AAFF).withLayer(0), LineStyle.pixels(3f).withColor(0xE07A5FFF).withLayer(1), LineStyle.pixels(1.5f).withColor(0x3D405BFF).withLayer(0),
            LineStyle.pixels(4f).withColor(0x81B29AFF).withCap(LineStyle.Cap.ROUND).withJoin(LineStyle.Join.ROUND).withLayer(1),
            LineStyle.pixels(2.5f).withColor(0xF2CC8FFF).withLayer(2), LineStyle.pixels(2f).withColor(0x6D597AFF).withDash(24f, 12f).withLayer(0)};
    private static final int[] TRAIL_COLORS = {0x00B4D8FF, 0xFF006EFF, 0xFFBE0BFF, 0x8338ECFF, 0x06D6A0FF, 0xEF476FFF, 0xFB5607FF, 0x3A86FFFF};

    private final int polylines;
    private final int editPercent;
    private final int churnPercent;
    private final int tracks;
    private final int trailPoints;
    private final int modeFrames;
    private final int compactEvery;
    private final int firstTier;
    private final int seed;
    private DemoContext ctx;
    private LineTier tier;
    private LineRenderer renderer;
    private LineSet set;
    private TrailBuffer trails;
    private SplittableRandom rnd;
    private PanZoom2d view;
    private final DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 4096);
    private final double[] worldMatrix = new double[16];
    private final float[] relativeMatrix = new float[16];
    private final double[] xyz = new double[3 * 16];
    private final double[] box = new double[6];
    private long[] handles;
    private final double[] px = new double[4096];
    private final double[] py = new double[4096];
    private final double[] heading = new double[4096];
    private final double[] speed = new double[4096];
    private int[] trackIds;
    private LineStyle[] trailStyles;
    private boolean uploadAll;
    private int measured;
    private int frameNumber;
    private int lastDraws;
    private long uploadedNow;
    private long fullNow;
    private double updateMsNow;
    private int rangesNow;
    private long handleChecks;
    private long handleFailures;
    private int compactions;
    private String lastCompaction = "";
    private boolean compactPending;
    private int drawsBeforeCompaction;
    private long totalRemoved;
    private long totalAdded;
    private long totalEdited;

    private int dirtyBytesSeries;
    private int allBytesSeries;
    private int dirtyUpdateSeries;
    private int allUpdateSeries;
    private int drawsSeries;
    private int fragSeries;
    private int rangesSeries;
    private int usedSeries;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public LineStreamDemo(List<String> args) {
        LineArgs a = new LineArgs(args, USAGE);
        polylines = a.integer("--polylines", 3000, 10, 200_000);
        editPercent = a.integer("--edit-percent", 5, 0, 100);
        churnPercent = a.integer("--churn-percent", 1, 0, 100);
        tracks = a.integer("--tracks", 100, 0, 4000);
        trailPoints = a.integer("--trail-points", 64, 4, 4096);
        modeFrames = a.integer("--mode-frames", 150, 1, 100_000);
        compactEvery = a.integer("--compact-every", 200, 0, 1_000_000);
        firstTier = a.integer("--tier", 2, 1, LineTier.values().length) - 1;
        seed = a.integer("--seed", 1, 0, Integer.MAX_VALUE);
        if (tracks > 4096) {
            throw new IllegalArgumentException("at most 4096 tracks\n" + USAGE);
        }
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        Stats stats = ctx.stats();
        dirtyBytesSeries = stats.series("uploaded (dirty ranges)", "KB", 1, "per frame, the ranges of LineSet.update");
        allBytesSeries = stats.series("uploaded (everything)", "KB", 1, "per frame, every record rewritten and uploaded");
        dirtyUpdateSeries = stats.timer("update (dirty ranges)", "LineSet.update and the upload of the dirty ranges");
        allUpdateSeries = stats.timer("update (everything)", "LineSet.update after invalidate, and the upload of all records");
        drawsSeries = stats.series("draws", "", 1, "the draws of the set at the end of the frame");
        rangesSeries = stats.series("dirty ranges", "", 1, "byte ranges the update wrote, merged");
        fragSeries = stats.series("fragmentation", "%", 1, "100 * (1 - largest free piece / free records)");
        usedSeries = stats.series("records in use", "", 0, "segments (or vertices) of all polylines");
        view = new PanZoom2d(WORLD_W / 2, WORLD_H / 2, 0.3, 0.05, 8.0);
        trailStyles = new LineStyle[8];
        for (int i = 0; i < trailStyles.length; i++) {
            trailStyles[i] = LineStyle.pixels(3f).withColor(TRAIL_COLORS[i]).withCap(LineStyle.Cap.ROUND).withJoin(LineStyle.Join.ROUND).withLayer(3);
        }
        startTier(Math.min(firstTier, LineTier.values().length - 1));
        ctx.clearColor(0.08f, 0.09f, 0.12f);
    }

    /** Builds the set, the renderer and the population for a tier. */
    private void startTier(int index) {
        if (renderer != null) {
            renderer.close();
        }
        tier = LineTier.values()[index];
        var plan = tier.plan();
        int perPolyline = 11;
        long units = (long) polylines * perPolyline + (long) tracks * (trailPoints + SLICES) + 64;
        long capacity = Math.min(Integer.MAX_VALUE / 64L, units * 3 / 2);
        set = new LineSet(plan, (int) capacity);
        set.setOrigin(WORLD_W / 2, WORLD_H / 2, 0.0);
        long styleBytes = ((long) polylines + (long) tracks * SLICES + 64) * 64L;
        renderer = new LineRenderer(plan, tier.caps(), set.dataBytes(), styleBytes);
        trails = new TrailBuffer(Math.max(1, tracks), trailPoints, SLICES);
        rnd = new SplittableRandom(seed);
        handles = new long[polylines];
        for (int i = 0; i < polylines; i++) {
            handles[i] = addRandom();
        }
        trackIds = new int[tracks];
        for (int t = 0; t < tracks; t++) {
            trackIds[t] = trails.addTrack();
            px[t] = rnd.nextDouble() * WORLD_W;
            py[t] = rnd.nextDouble() * WORLD_H;
            heading[t] = rnd.nextDouble() * Math.PI * 2;
            speed[t] = 160 + rnd.nextDouble() * 160;
        }
        frameNumber = 0;
        lastDraws = 0;
        updateSet(0.0, true);
    }

    private long addRandom() {
        int n = 4 + rnd.nextInt(8);
        double x = rnd.nextDouble() * WORLD_W, y = rnd.nextDouble() * WORLD_H, h = rnd.nextDouble() * Math.PI * 2, curve = (rnd.nextDouble() - 0.5) * 0.5, step = 14 + rnd.nextDouble() * 22;
        fill(n, x, y, h, curve, step);
        return set.add(xyz, 0, n, false, STYLES[rnd.nextInt(STYLES.length)]);
    }

    private void fill(int n, double x, double y, double h, double curve, double step) {
        for (int k = 0; k < n; k++) {
            xyz[3 * k] = x;
            xyz[3 * k + 1] = y;
            xyz[3 * k + 2] = 0.0;
            h += curve;
            x += Math.cos(h) * step;
            y += Math.sin(h) * step;
        }
    }

    private void select(int index) {
        if (index >= 0 && index < LineTier.values().length && index != tier.ordinal()) {
            startTier(index);
        }
    }

    @Override
    public void update(FrameInfo frame) {
        frameNumber++;
        if (frame.benchmark()) {
            if (ctx.stats().measuring()) {
                measured++;
            }
            uploadAll = (measured / modeFrames) % 2 == 1;
            double t = frame.time();
            view.place(WORLD_W / 2 + 900 * Math.sin(t * 0.21), WORLD_H / 2 + 500 * Math.sin(t * 0.17), 0.45 * Math.exp(0.5 * Math.sin(t * 0.3)));
            if (compactEvery > 0 && frameNumber % compactEvery == 0) {
                compactNow();
            }
        } else {
            var in = frame.input();
            for (int i = 0; i < LineTier.values().length; i++) {
                if (in.pressed(GLFW_KEY_1 + i)) {
                    select(i);
                }
            }
            if (in.pressed(GLFW_KEY_N)) {
                select((tier.ordinal() + 1) % LineTier.values().length);
            }
            if (in.pressed(GLFW_KEY_U)) {
                uploadAll = !uploadAll;
            }
            if (in.pressed(GLFW_KEY_K)) {
                compactNow();
            }
            view.update(frame, false);
        }
        simulate(frame.time());
        updateSet(frame.time(), false);
    }

    private void compactNow() {
        drawsBeforeCompaction = lastDraws;
        set.compact();
        compactPending = true;
        compactions++;
    }

    /** Edits, replaces and moves. */
    private void simulate(double time) {
        int edits = polylines * editPercent / 100, churn = polylines * churnPercent / 100;
        for (int e = 0; e < edits; e++) {
            int i = rnd.nextInt(polylines);
            long h = handles[i];
            int n = set.pointCount(h);
            set.bounds(h, box);   // the bounding box of the polyline: it moves a little
            double[] b = box;
            double dx = (rnd.nextDouble() - 0.5) * 12, dy = (rnd.nextDouble() - 0.5) * 12;
            fill(n, (b[0] + b[3]) / 2 + dx, (b[1] + b[4]) / 2 + dy, rnd.nextDouble() * Math.PI * 2, (rnd.nextDouble() - 0.5) * 0.5, 14 + rnd.nextDouble() * 22);
            set.set(h, xyz, 0, n, false);
            totalEdited++;
        }
        for (int c = 0; c < churn; c++) {
            int i = rnd.nextInt(polylines);
            long old = handles[i];
            boolean removed = set.remove(old);
            handleChecks++;
            handleFailures += removed && !set.contains(old) ? 0 : 1;   // a removed handle is never valid again
            handles[i] = addRandom();
            handleChecks++;
            handleFailures += set.contains(handles[i]) && handles[i] != old ? 0 : 1;
            totalRemoved++;
            totalAdded++;
        }
        double dt = FrameInfo.SCRIPTED_DT;
        for (int t = 0; t < tracks; t++) {
            heading[t] += (rnd.nextDouble() - 0.5) * 0.12;
            px[t] += Math.cos(heading[t]) * speed[t] * dt;
            py[t] += Math.sin(heading[t]) * speed[t] * dt;
            if (px[t] < 0 || px[t] > WORLD_W) {
                heading[t] = Math.PI - heading[t];
                px[t] = Math.max(0, Math.min(WORLD_W, px[t]));
            }
            if (py[t] < 0 || py[t] > WORLD_H) {
                heading[t] = -heading[t];
                py[t] = Math.max(0, Math.min(WORLD_H, py[t]));
            }
            trails.push(trackIds[t], px[t], py[t], 0.0, frameNumber * dt);
            trails.updateLines(set, trackIds[t], frameNumber * dt, (trailPoints - 1) * dt, trailStyles[t % trailStyles.length]);
        }
    }

    /** Writes what changed into the mirrors and uploads it. */
    private void updateSet(double time, boolean first) {
        long t0 = System.nanoTime();
        if (uploadAll || first) {
            set.invalidate();
        }
        int n = set.update(renderer.dataMirror(), renderer.styleMirror(), draws);
        long bytes = 0;
        for (int i = 0; i < set.dirtyRangeCount(); i++) {
            renderer.uploadData(set.dirtyOffset(i), set.dirtyLength(i));
            bytes += set.dirtyLength(i);
        }
        renderer.uploadStyles(set.styleBytes());
        renderer.setDraws(draws);
        double ms = (System.nanoTime() - t0) / 1e6;
        lastDraws = n;
        uploadedNow = bytes;
        rangesNow = set.dirtyRangeCount();
        updateMsNow = ms;
        if (first) {
            return;
        }
        Stats stats = ctx.stats();
        long free = set.capacityUnits() - set.usedUnits();
        double frag = free > 0 ? 100.0 * (1.0 - (double) set.largestFreeUnits() / free) : 0.0;
        if (uploadAll) {
            stats.record(allBytesSeries, bytes / 1024.0);
            stats.record(allUpdateSeries, ms);
            fullNow = bytes;
        } else {
            stats.record(dirtyBytesSeries, bytes / 1024.0);
            stats.record(dirtyUpdateSeries, ms);
        }
        stats.record(drawsSeries, n);
        stats.record(rangesSeries, rangesNow);
        stats.record(fragSeries, frag);
        stats.record(usedSeries, set.usedUnits());
        if (compactPending) {
            compactPending = false;
            lastCompaction = String.format(Locale.ROOT, "compaction %d: %d draws before, %d after, %.1f KB rewritten", compactions, drawsBeforeCompaction, n, bytes / 1024.0);
            stats.note(lastCompaction);
        }
    }

    @Override
    public void render(FrameInfo frame) {
        view.viewProjection(frame.width(), frame.height(), worldMatrix);
        set.relativeViewProjection(worldMatrix, relativeMatrix);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        ctx.gpu().begin();
        renderer.draw(relativeMatrix, (float) view.scale(), frame.width(), frame.height());
        ctx.gpu().end();
        glDisable(GL_BLEND);
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        long free = set.capacityUnits() - set.usedUnits();
        double frag = free > 0 ? 100.0 * (1.0 - (double) set.largestFreeUnits() / free) : 0.0;
        hud.color(1f, 1f, 1f).line(String.format(Locale.ROOT, "tier %d of 6: %s", tier.ordinal() + 1, tier.label()));
        hud.line(String.format(Locale.ROOT, "%d polylines in the set (%d records of %d), %d styles; %d draws in %d call%s", set.size(), set.usedUnits(), set.capacityUnits(), set.styleCount(), lastDraws,
                renderer.calls(), renderer.calls() == 1 ? "" : "s"));
        hud.line(String.format(Locale.ROOT, "upload mode: %s", uploadAll ? "everything rewritten and uploaded every frame (U)" : "dirty ranges only (U)"));
        hud.line(String.format(Locale.ROOT, "this frame: %.1f KB in %d range%s, update %.3f ms; the whole data is %.1f KB", uploadedNow / 1024.0, rangesNow, rangesNow == 1 ? "" : "s", updateMsNow,
                set.usedUnits() * (set.dataBytes() / (double) set.capacityUnits()) / 1024.0));
        hud.line(String.format(Locale.ROOT, "fragmentation %.1f%%; %d edits, %d removals and additions; handles: %d checks, %d failures", frag, totalEdited, totalRemoved, handleChecks, handleFailures));
        hud.line(lastCompaction.isEmpty() ? "K compacts now" : lastCompaction);
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "tier %s; %d polylines, %d tracks of %d positions in %d slices; %d edits, %d removals and additions, %d compactions", tier.shortName(), polylines, tracks,
                trailPoints, SLICES, totalEdited, totalRemoved, compactions));
        stats.note(String.format(Locale.ROOT, "handle checks: %d, failures: %d; strategy %s", handleChecks, handleFailures, tier.strategy() == LineStrategy.HAIRLINE ? "hairline" : tier.strategy().name()));
    }

    @Override
    public void dispose() {
        if (renderer != null) {
            renderer.close();
        }
    }
}
