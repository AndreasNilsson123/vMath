package vmath.samples.demos.maps;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_H;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import vmath.core.ClipSpace;
import vmath.geo.GeoFormat;
import vmath.geo.Units;
import vmath.geo.WebMercator;
import vmath.geo.WebMercatorProjection;
import vmath.map.ColorRamp;
import vmath.map.MapView2d;
import vmath.map.SymbolAtlas;
import vmath.map.SymbolBatch;
import vmath.map.SymbolRenderPlan;
import vmath.map.TerrainGrid;
import vmath.map.TerrainShading;
import vmath.map.Viewshed;
import vmath.samples.demos.globe.Planet;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.GpuTimer;
import vmath.samples.framework.Hud;
import vmath.samples.framework.LineTier;
import vmath.samples.framework.MapRenderers;
import vmath.samples.framework.Stats;

/**
 * Hillshaded elevation with a colour ramp indexed by the height relative to a reference altitude
 * (an observer's altitude that a key or the pointer changes), coloured on the CPU
 * ({@code TerrainShading}) or on the GPU ({@code TerrainShader}) from the same heights, and a
 * viewshed from an own position that moves over the terrain, computed on a worker thread and shown
 * as a mask, with its cost per cell.
 *
 * <p>The terrain is the procedural planet of the globe demo, sampled on a grid in Web Mercator at the
 * place where the land is most rugged. Nothing is downloaded.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> The render thread owns the OpenGL objects and the terrain; the viewshed
 * runs on one worker thread that reads the terrain grid and writes its own result array, which the
 * render thread reads only after the future is done.
 */
public final class MapTerrainDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("map-terrain", "Map terrain: height relative to an altitude, and a viewshed",
            "Hillshaded elevation coloured by height relative to a reference altitude that a key or the pointer changes, on the CPU or the GPU, and a viewshed from the moving own position computed off the render thread, with its cost per cell.",
            List.of("maps", "terrain", "2d"), 262_144L, List.of("--grid", "129", "--range", "20000"),
            "Up/Down: reference altitude | H: altitude of the terrain under the pointer | T: colour on CPU or GPU | M: viewshed mask | left mouse: pan | wheel: zoom");

    private static final String USAGE = """
            options of the map-terrain demo:
              --grid N       nodes along a side of the elevation tile (default 385)
              --range N      range of the viewshed in metres (default 40000)
              --size N       side of the tile in kilometres (default 240)
            """;

    private static final int RAMP_TEXELS = 512;

    private final int gridSize;
    private final double range;
    private final double sizeKm;
    private DemoContext ctx;
    private TerrainGrid grid;
    private double centerLat;
    private double centerLon;
    private double minHeight;
    private double maxHeight;
    private MapRenderers.Quads quads;
    private MapRenderers.GpuTerrain gpuTerrain;
    private MapRenderers.Symbols symbols;
    private int cpuTexture;
    private int maskTexture;
    private final SymbolBatch observerBatch = new SymbolBatch(2);
    private final float[] dotUv = new float[4];
    private byte[] cpuPixels;
    private byte[] maskPixels;
    private MapView2d view;
    private double mpp;
    private double panX;
    private double panY;
    private double reference;
    private double lastRendered = Double.NaN;
    private boolean useGpu = true;
    private boolean showMask = true;
    private ColorRamp ramp;
    private ColorRamp.Baked baked;
    private GpuTimer timer;
    private int cpuSeries;
    private int gpuSeries;
    private double cpuMs;
    private ExecutorService worker;
    private Future<long[]> pending;
    private byte[] pendingResult;
    private double[] pendingObserver = new double[2];
    private double[] shownObserver = new double[] {Double.NaN, Double.NaN};
    private double observerAngle;
    private double observerX;
    private double observerY;
    private long viewshedCells;
    private long viewshedVisible;
    private double viewshedNsPerCell;
    private double viewshedMs;
    private int viewshedRuns;
    private final double[] pointer = new double[2];
    private final double[] pointerLatLon = new double[2];
    private double pointerHeight = Double.NaN;
    private final float[] vp = new float[16];
    private double time;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public MapTerrainDemo(List<String> args) {
        MapArgs a = new MapArgs(args, USAGE);
        gridSize = a.integer("--grid", 385, 17, 2049);
        range = a.integer("--range", 40_000, 1000, 1_000_000);
        sizeKm = a.integer("--size", 240, 10, 2000);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        chooseRegion();
        buildGrid();
        var caps = LineTier.DRAW_ID.caps();
        quads = new MapRenderers.Quads();
        gpuTerrain = new MapRenderers.GpuTerrain(grid, caps);
        SymbolAtlas atlas = MapKit.sprites();
        atlas.rect(atlas.indexOf("dot"), dotUv);
        symbols = new MapRenderers.Symbols(SymbolRenderPlan.choose(caps), atlas, 4);
        cpuPixels = new byte[4 * gridSize * gridSize];
        maskPixels = new byte[4 * gridSize * gridSize];
        cpuTexture = MapRenderers.texture(gridSize, gridSize, cpuPixels);
        maskTexture = MapRenderers.texture(gridSize, gridSize, maskPixels);
        timer = new GpuTimer();
        cpuSeries = ctx.stats().timer("terrain CPU", "TerrainShading.render of the whole tile and the upload of the texture");
        gpuSeries = ctx.stats().timer("terrain GPU", "TerrainShader over the whole tile, on the GPU");
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "viewshed");
            t.setDaemon(true);
            return t;
        });
        reference = 0.5 * (minHeight + maxHeight);
        double[] c = new double[2];
        WebMercatorProjection.INSTANCE.forward(centerLat, centerLon, c);
        observerX = c[0];
        observerY = c[1];
        mpp = sizeKm * 1000.0 / 900.0 * 1.05;
        ctx.stats().note(String.format(Locale.ROOT, "tile %d x %d nodes of %.0f m at %s %s, heights %.0f to %.0f m", gridSize, gridSize, grid.cellEast() * grid.groundScale(),
                GeoFormat.latitude(centerLat, GeoFormat.Format.DECIMAL, 2), GeoFormat.longitude(centerLon, GeoFormat.Format.DECIMAL, 2, false), minHeight, maxHeight));
        ctx.clearColor(0.05f, 0.07f, 0.1f);
    }

    /** Looks for the part of the planet where the land is most rugged and mostly dry. */
    private void chooseRegion() {
        double best = -1;
        centerLat = Math.toRadians(20.0);
        centerLon = Math.toRadians(10.0);
        for (int la = -50; la <= 60; la += 5) {
            for (int lo = -180; lo < 180; lo += 5) {
                double lat = Math.toRadians(la), lon = Math.toRadians(lo);
                double halfDeg = Math.toDegrees(sizeKm * 500.0 / 6_371_000.0);
                double sum = 0, sum2 = 0;
                int land = 0, n = 12;
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i < n; i++) {
                        double h = Planet.height(lon + Math.toRadians((i / (n - 1.0) - 0.5) * 2 * halfDeg), lat + Math.toRadians((j / (n - 1.0) - 0.5) * 2 * halfDeg));
                        if (h > 0) {
                            land++;
                            sum += h;
                            sum2 += h * h;
                        }
                    }
                }
                if (land < 0.9 * n * n) {
                    continue;
                }
                double mean = sum / land, sd = Math.sqrt(Math.max(0, sum2 / land - mean * mean));
                if (sd > best) {
                    best = sd;
                    centerLat = lat;
                    centerLon = lon;
                }
            }
        }
    }

    private void buildGrid() {
        double[] c = new double[2];
        WebMercatorProjection.INSTANCE.forward(centerLat, centerLon, c);
        double half = sizeKm * 500.0 / Math.cos(centerLat);                  // projected metres: the ground size over the scale
        float[] h = new float[gridSize * gridSize];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int r = 0; r < gridSize; r++) {
            double y = c[1] + half - 2 * half * r / (gridSize - 1);
            double lat = WebMercator.latitudeOfY(y);
            for (int col = 0; col < gridSize; col++) {
                double x = c[0] - half + 2 * half * col / (gridSize - 1);
                double v = Planet.height(WebMercator.longitudeOfX(x), lat);
                h[r * gridSize + col] = (float) v;
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        minHeight = lo;
        maxHeight = hi;
        grid = new TerrainGrid(h, gridSize, gridSize, c[0] - half, c[1] - half, c[0] + half, c[1] + half, Math.cos(centerLat));
    }

    private void rebuildRamp() {
        ramp = ColorRamp.relativeSteps(reference, new double[] {-600.0, -300.0, 0.0, 300.0},
                new int[] {0x1E4020FF, 0x60A040FF, 0xE0C000FF, 0xE08000FF, 0xE00000FF});
        baked = ramp.bake((float) Math.floor(minHeight - 1), (float) Math.ceil(maxHeight + 1), RAMP_TEXELS);
    }

    @Override
    public void update(FrameInfo frame) {
        float dt = frame.benchmark() ? FrameInfo.SCRIPTED_DT : frame.dt();
        time += dt;
        var in = frame.input();
        double span = maxHeight - minHeight;
        if (frame.benchmark()) {
            reference = minHeight + span * (0.5 + 0.4 * Math.sin(time * 0.8));
            useGpu = (frame.frame() / 120) % 2 == 0;
        } else {
            if (in.down(GLFW_KEY_UP)) {
                reference += span * 0.25 * dt;
            }
            if (in.down(GLFW_KEY_DOWN)) {
                reference -= span * 0.25 * dt;
            }
            if (in.pressed(GLFW_KEY_T)) {
                useGpu = !useGpu;
                lastRendered = Double.NaN;
            }
            if (in.pressed(GLFW_KEY_M)) {
                showMask = !showMask;
            }
            float notches = in.scroll();
            if (notches != 0f) {
                mpp = Math.max(8.0, Math.min(2000.0, mpp * Math.pow(0.87, notches)));
            }
            if (in.mouseDown(GLFW_MOUSE_BUTTON_LEFT) && view != null) {
                double[] p = new double[2];
                view.screenToProjected(frame.width() / 2.0 - in.mouseDx(), frame.height() / 2.0 - in.mouseDy(), p);
                panX = p[0] - 0.5 * (grid.minX() + grid.maxX());
                panY = p[1] - 0.5 * (grid.minY() + grid.maxY());
            }
        }
        reference = Math.max(minHeight - 100, Math.min(maxHeight + 100, reference));
        double cx = 0.5 * (grid.minX() + grid.maxX()) + panX, cy = 0.5 * (grid.minY() + grid.maxY()) + panY;
        double[] ll = new double[2];
        WebMercatorProjection.INSTANCE.inverse(cx, cy, ll);
        view = MapView2d.of(WebMercatorProjection.INSTANCE, ll[0], ll[1], frame.width(), frame.height()).withMetersPerPixel(mpp);
        double mx = frame.benchmark() ? frame.width() * 0.6 : in.mouseX(), my = frame.benchmark() ? frame.height() * 0.45 : in.mouseY();
        view.screenToProjected(mx, my, pointer);
        WebMercatorProjection.INSTANCE.inverse(pointer[0], pointer[1], pointerLatLon);
        pointerHeight = grid.heightAt(pointer[0], pointer[1]);
        if (!frame.benchmark() && in.pressed(GLFW_KEY_H) && !Double.isNaN(pointerHeight)) {
            reference = pointerHeight + 150.0;
        }
        // the own position circles over the terrain; the viewshed follows it
        observerAngle += dt * 0.15;
        double rad = 0.28 * (grid.maxX() - grid.minX());
        observerX = 0.5 * (grid.minX() + grid.maxX()) + rad * Math.cos(observerAngle);
        observerY = 0.5 * (grid.minY() + grid.maxY()) + rad * Math.sin(observerAngle) * 0.7;
        manageViewshed(frame);
    }

    private void manageViewshed(FrameInfo frame) {
        if (pending != null && pending.isDone()) {
            try {
                long[] r = pending.get();
                viewshedCells = r[0];
                viewshedVisible = r[1];
                viewshedNsPerCell = r[2] / 1000.0;
                viewshedMs = r[3] / 1000.0;
                viewshedRuns++;
                shownObserver = pendingObserver;
                fillMask();
            } catch (Exception e) {
                ctx.stats().note("the viewshed failed: " + e);
            }
            pending = null;
        }
        boolean moved = Double.isNaN(shownObserver[0]) || Math.hypot(observerX - shownObserver[0], observerY - shownObserver[1]) > 2 * grid.cellEast();
        if (pending == null && moved) {
            final double ox = observerX, oy = observerY;
            pendingObserver = new double[] {ox, oy};
            pendingResult = new byte[gridSize * gridSize];
            final byte[] out = pendingResult;
            pending = worker.submit(() -> {
                long t0 = System.nanoTime();
                int visible = Viewshed.compute(grid, ox, oy, 30.0, 0.0, range, true, out);
                long ns = System.nanoTime() - t0;
                long cells = 0;
                double cell = grid.cellEast() * grid.groundScale();
                double rr = range / cell;
                cells = Math.round(Math.PI * rr * rr);
                return new long[] {cells, visible, Math.round(1000.0 * ns / Math.max(1, cells)), Math.round(ns / 1000.0)};
            });
        }
    }

    private void fillMask() {
        double cell = grid.cellEast() * grid.groundScale();
        for (int r = 0; r < gridSize; r++) {
            for (int c = 0; c < gridSize; c++) {
                int o = 4 * (r * gridSize + c);
                double d = Math.hypot(grid.xOf(c) - shownObserver[0], grid.yOf(r) - shownObserver[1]) * grid.groundScale();
                boolean inRange = d <= range;
                boolean seen = pendingResult[r * gridSize + c] == 1;
                maskPixels[o] = 0;
                maskPixels[o + 1] = 0;
                maskPixels[o + 2] = 0;
                maskPixels[o + 3] = (byte) (inRange ? (seen ? 0 : 150) : 40);
            }
        }
        MapRenderers.update(maskTexture, gridSize, gridSize, maskPixels);
        if (cell <= 0) {
            ctx.stats().note("empty cell");
        }
    }

    @Override
    public void render(FrameInfo frame) {
        double ox = 0.5 * (grid.minX() + grid.maxX()), oy = 0.5 * (grid.minY() + grid.maxY());
        view.viewProjection(ClipSpace.OPENGL, ox, oy, vp);
        if (ramp == null || reference != lastRendered) {
            rebuildRamp();
            long t0 = System.nanoTime();
            if (useGpu) {
                timer.begin();
                gpuTerrain.render(baked, Math.toRadians(315.0), Math.toRadians(40.0), 2.0, 0.65);
                timer.end();
                long ns = timer.poll();
                if (ns >= 0) {
                    ctx.stats().recordNanos(gpuSeries, ns);
                }
            } else {
                TerrainShading.render(grid, baked, Math.toRadians(315.0), Math.toRadians(40.0), 2.0, 0.65, cpuPixels);
                MapRenderers.update(cpuTexture, gridSize, gridSize, cpuPixels);
                cpuMs = (System.nanoTime() - t0) / 1e6;
                ctx.stats().recordNanos(cpuSeries, System.nanoTime() - t0);
            }
            lastRendered = reference;
        }
        float minX = (float) (grid.minX() - ox), minY = (float) (grid.minY() - oy), maxX = (float) (grid.maxX() - ox), maxY = (float) (grid.maxY() - oy);
        quads.draw(useGpu ? gpuTerrain.output() : cpuTexture, minX, minY, maxX, maxY, vp, 1f, 1f, 1f, 1f, useGpu);
        if (showMask && viewshedRuns > 0) {
            quads.draw(maskTexture, minX, minY, maxX, maxY, vp, 1f, 1f, 1f, 1f);
        }
        observerBatch.clear();
        observerBatch.add(observerX, observerY, 0.0, 14f, dotUv, 0xFFFFFFFF, 0);
        symbols.upload(observerBatch, ox, oy, null);
        symbols.draw(vp, frame.width(), frame.height(), SymbolRenderPlan.mapRotation(view), (float) view.pixelsPerMapUnit());
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        Stats stats = ctx.stats();
        hud.color(1f, 1f, 1f).line(String.format(Locale.ROOT, "reference altitude %.0f m (%.0f ft), terrain %.0f to %.0f m; coloured on the %s", reference, Units.metersToFeet(reference), minHeight, maxHeight,
                useGpu ? "GPU" : "CPU"));
        hud.line(String.format(Locale.ROOT, "last colouring: CPU %.2f ms (%d x %d nodes), GPU %.3f ms", stats.average(cpuSeries), gridSize, gridSize, stats.average(gpuSeries)));
        hud.line(viewshedRuns == 0 ? "viewshed: computing..." : String.format(Locale.ROOT,
                "viewshed from the own position (eye 30 m up, %.0f km): %d of about %d cells in range seen (%.0f%%), %.2f ms, %.0f ns per cell, %d runs, on a worker thread", range / 1000.0, viewshedVisible,
                viewshedCells, 100.0 * viewshedVisible / Math.max(1, viewshedCells), viewshedMs, viewshedNsPerCell, viewshedRuns));
        if (!Double.isNaN(pointerHeight)) {
            hud.line(MapKit.ascii(String.format(Locale.ROOT, "pointer %s %s: %.0f m (%.0f ft), %.0f m %s the reference", GeoFormat.latitude(pointerLatLon[0], GeoFormat.Format.DEGREES_MINUTES, 1), GeoFormat.longitude(
                    pointerLatLon[1], GeoFormat.Format.DEGREES_MINUTES, 1, true), pointerHeight, Units.metersToFeet(pointerHeight), Math.abs(pointerHeight - reference), pointerHeight >= reference ? "above" : "below")));
        }
        String[] names = {"more than 600 m below", "300 to 600 m below", "within 300 m below", "within 300 m above", "more than 300 m above"};
        float[][] colors = {{0.12f, 0.25f, 0.13f}, {0.38f, 0.63f, 0.25f}, {0.88f, 0.75f, 0f}, {0.88f, 0.5f, 0f}, {0.88f, 0f, 0f}};
        hud.gap(6f);
        for (int i = 0; i < names.length; i++) {
            float y = hud.cursorY();
            hud.rect(8f, y + 2f, 18f, 11f, colors[i][0], colors[i][1], colors[i][2], 1f);
            hud.color(1f, 1f, 1f).line("    " + names[i]);
        }
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "viewshed: %d runs, last %.2f ms for about %d cells, %.0f ns per cell, %.0f%% seen", viewshedRuns, viewshedMs, viewshedCells, viewshedNsPerCell,
                100.0 * viewshedVisible / Math.max(1, viewshedCells)));
    }

    @Override
    public void dispose() {
        if (worker != null) {
            worker.shutdownNow();
        }
        if (quads != null) {
            quads.close();
        }
        if (gpuTerrain != null) {
            gpuTerrain.close();
        }
        if (symbols != null) {
            symbols.close();
        }
        if (timer != null) {
            timer.dispose();
        }
        if (cpuTexture != 0) {
            org.lwjgl.opengl.GL46.glDeleteTextures(cpuTexture);
            org.lwjgl.opengl.GL46.glDeleteTextures(maskTexture);
        }
    }
}
