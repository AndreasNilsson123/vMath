package vmath.samples.demos.maps;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import vmath.core.ClipSpace;
import vmath.geo.GeoFormat;
import vmath.geo.Geodesy;
import vmath.geo.Units;
import vmath.geo.WebMercatorProjection;
import vmath.lines.LineStyle;
import vmath.map.AreaBatch;
import vmath.map.AreaRenderPlan;
import vmath.map.AreaStyle;
import vmath.map.FlatTileSelector;
import vmath.map.MapShapes;
import vmath.map.MapView2d;
import vmath.map.SymbolAtlas;
import vmath.map.SymbolBatch;
import vmath.map.SymbolGpu;
import vmath.map.SymbolRenderPlan;
import vmath.map.TileGrid;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Hud;
import vmath.samples.framework.LineTier;
import vmath.samples.framework.MapRenderers;
import vmath.samples.framework.Stats;

/**
 * A moving map: an own position that flies a route of geodesic legs over a procedural planet, in a
 * {@code MapView2d} that centres on it or sits it near the bottom, north up, course up or heading
 * up, with range rings, the route and a corridor along it sampled by {@code MapShapes}, tiles chosen
 * by {@code FlatTileSelector} and drawn from images generated on the fly, a scale bar from the pixel
 * size on the ground, and a cursor read-out of latitude and longitude.
 *
 * <p>The lines go through the line renderer of {@code vmath.lines}, the corridor through the area
 * renderer and the own position through the symbol renderer. Nothing is downloaded: the tile images
 * come from the planet of the globe demo.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class MapViewDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("map-view", "Moving map: own position, route, rings and tiles",
            "A 2D map follows an own position along a route of great-circle legs, north up, course up or heading up, with range rings, a corridor and generated tiles, and the numbers of the view are read off in navigation units.",
            List.of("maps", "2d", "lines"), 1_048_576, List.of("--speed", "600"),
            "O: north up, course up, heading up | C: centred or low | R: rings | Space: pause | wheel: zoom | mouse: cursor position");

    private static final String USAGE = """
            options of the map-view demo:
              --speed N    speed of the own position in metres per second (default 450)
              --scale N    metres of ground per pixel at the start (default 150)
              --tier N     line tier, 1 to 6 (default 1)
            """;

    private static final int TILE_PIXELS = 128;
    private static final int TILE_CACHE = 300;
    private static final String[] ORIENTATIONS = {"north up", "course up", "heading up"};

    private final double speed;
    private final double startScale;
    private final int firstTier;
    private DemoContext ctx;
    private MapKit.LineLayer lines;
    private MapRenderers.Quads quads;
    private MapRenderers.Symbols symbols;
    private MapRenderers.Areas areas;
    private SymbolAtlas atlas;
    private float[] arrowUv = new float[4];
    private float[] diamondUv = new float[4];
    private final SymbolBatch symbolBatch = new SymbolBatch(64);
    private final AreaBatch areaBatch = new AreaBatch();
    private int corridorStyle;
    private MapKit.Route route;
    private TileGrid grid;
    private FlatTileSelector selector;
    private final Map<Long, Integer> tiles = new java.util.LinkedHashMap<>(512, 0.75f, true);
    private MapView2d view;
    private double distance;
    private double mpp;
    private int orientation;
    private boolean centered = true;
    private boolean rings = true;
    private boolean paused;
    private final double[] position = new double[3];
    private double heading;
    private final List<double[]> trail = new ArrayList<>();
    private double lastTrailAt = -100;
    private double[] scaleBar = new double[2];
    private double[] cursor = new double[2];
    private int generated;
    private double corridorScale = Double.NaN;
    private double corridorX;
    private double corridorY;
    private int corridorBuilds;
    private int drawnTiles;
    private int usedFallbacks;
    private double shapesMs;
    private long uploaded;
    private int shapesSeries;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public MapViewDemo(List<String> args) {
        MapArgs a = new MapArgs(args, USAGE);
        speed = a.integer("--speed", 450, 1, 100_000);
        startScale = a.integer("--scale", 150, 1, 100_000);
        firstTier = a.integer("--tier", 1, 1, LineTier.values().length) - 1;
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        LineTier[] tiers = LineTier.values();
        RuntimeException failure = null;
        for (int i = firstTier; i < tiers.length && lines == null; i++) {
            try {
                lines = new MapKit.LineLayer(tiers[i], 8L << 20);
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        if (lines == null) {
            throw failure;
        }
        quads = new MapRenderers.Quads();
        atlas = MapKit.sprites();
        atlas.rect(atlas.indexOf("arrow"), arrowUv);
        atlas.rect(atlas.indexOf("diamond"), diamondUv);
        symbols = new MapRenderers.Symbols(SymbolRenderPlan.choose(lines.tier().caps()), atlas, 64);
        areas = new MapRenderers.Areas(AreaRenderPlan.choose(lines.tier().caps()), 20000, 20000);
        grid = TileGrid.webMercator(256, TileGrid.Scheme.XYZ);
        selector = new FlatTileSelector(grid, 0, 11, 120, 0.3, 0.2, 64.0);
        // a route of five waypoints in the tropics of the procedural planet, about 2,000 km round
        route = new MapKit.Route(new double[] {r(8), r(10), r(14), r(19), r(9), r(26), r(2), r(22), r(1), r(13)});
        mpp = startScale;
        distance = 0;
        view = MapView2d.of(WebMercatorProjection.INSTANCE, r(8), r(10), 1280, 720).withMetersPerPixel(mpp);
        shapesSeries = ctx.stats().series("shapes", "ms", 3, "CPU time to sample the rings, the route and the corridor and to write the lines and areas");
        ctx.stats().note("line tier " + lines.tier().shortName() + ", symbols " + symbols.plan().strategy() + ", areas " + areas.getClass().getSimpleName() + " (" + AreaRenderPlan.choose(lines.tier().caps()).strategy() + ")");
        ctx.clearColor(0.05f, 0.14f, 0.2f);
    }

    private static double r(double degrees) {
        return Math.toRadians(degrees);
    }

    @Override
    public void update(FrameInfo frame) {
        float dt = frame.benchmark() ? FrameInfo.SCRIPTED_DT : frame.dt();
        double t = frame.benchmark() ? frame.frame() * (double) FrameInfo.SCRIPTED_DT : frame.time();
        var in = frame.input();
        if (frame.benchmark()) {
            orientation = (frame.frame() / 180) % 3;
            centered = (frame.frame() / 360) % 2 == 0;
            mpp = startScale * Math.exp(0.7 * Math.sin(t * 0.5));
        } else {
            if (in.pressed(GLFW_KEY_O)) {
                orientation = (orientation + 1) % 3;
            }
            if (in.pressed(GLFW_KEY_C)) {
                centered = !centered;
            }
            if (in.pressed(GLFW_KEY_R)) {
                rings = !rings;
            }
            if (in.pressed(GLFW_KEY_SPACE)) {
                paused = !paused;
            }
            float notches = in.scroll();
            if (notches != 0f) {
                mpp = Math.max(5.0, Math.min(60_000.0, mpp * Math.pow(0.87, notches)));
            }
        }
        if (!paused) {
            distance += speed * dt;
        }
        route.at(distance, position);
        heading = Geodesy.normalizeBearing(position[2] + Math.toRadians(9.0) * Math.sin(t * 0.4));
        MapView2d.Orientation o = orientation == 0 ? MapView2d.Orientation.NORTH_UP : orientation == 1 ? MapView2d.Orientation.COURSE_UP : MapView2d.Orientation.HEADING_UP;
        view = MapView2d.of(WebMercatorProjection.INSTANCE, position[0], position[1], frame.width(), frame.height()).withMetersPerPixel(mpp)
                .withCenterOffset(0.0, centered ? 0.0 : 0.3).withOrientation(o, orientation == 1 ? position[2] : heading);
        if (t - lastTrailAt > 3.0) {
            lastTrailAt = t;
            trail.add(new double[] {position[0], position[1]});
            if (trail.size() > 40) {
                trail.remove(0);
            }
        }
        double mx = frame.benchmark() ? frame.width() * 0.7 : in.mouseX(), my = frame.benchmark() ? frame.height() * 0.4 : in.mouseY();
        view.toGeographic(mx, my, cursor);
        view.scaleBar(220, scaleBar);
        requestTiles();
    }

    private static long key(int zoom, long x, long y) {
        return (long) zoom << 50 | (x & 0x1FFFFFF) << 25 | y & 0x1FFFFFF;
    }

    /** A tile image made on a worker thread, waiting to become a texture on the render thread. */
    private record Finished(long key, byte[] image) {
    }

    private final java.util.concurrent.ExecutorService tileWorkers = java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "tile generator");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.ConcurrentLinkedQueue<Finished> finished = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Set<Long> pending = new HashSet<>();

    private void requestTiles() {
        // what the workers have finished becomes textures here, a few a frame
        Finished f;
        int uploads = 0;
        while (uploads < 6 && (f = finished.poll()) != null) {
            pending.remove(f.key());
            tiles.put(f.key(), MapRenderers.texture(TILE_PIXELS, TILE_PIXELS, f.image()));
            generated++;
            uploads++;
            while (tiles.size() > TILE_CACHE) {
                var it = tiles.entrySet().iterator();
                var eldest = it.next();
                org.lwjgl.opengl.GL46.glDeleteTextures(eldest.getValue());
                it.remove();
            }
        }
        int n = selector.select(view);
        int zoom = selector.zoom();
        for (int i = 0; i < n && pending.size() < 24; i++) {
            long x = selector.x(i), y = selector.y(i);
            long k = key(zoom, x, y);
            if (!tiles.containsKey(k) && pending.add(k)) {
                tileWorkers.execute(() -> finished.add(new Finished(k, MapKit.tileImage(zoom, x, y, TILE_PIXELS))));
            }
        }
    }

    @Override
    public void render(FrameInfo frame) {
        double ox = view.centerX(), oy = view.centerY();
        float[] vp = new float[16];
        view.viewProjection(ClipSpace.OPENGL, ox, oy, vp);
        drawTiles(vp, ox, oy);

        long t0 = System.nanoTime();
        MapShapes shapes = new MapShapes(view, 0.5);
        double[] wp = route.waypoints();
        double[] corridorPoints = new double[wp.length + 2];
        System.arraycopy(wp, 0, corridorPoints, 0, wp.length);
        corridorPoints[wp.length] = wp[0];
        corridorPoints[wp.length + 1] = wp[1];
        // the corridor does not depend on the own position, only on the scale (the tolerance in pixels): it is made again when the scale has changed by 5 percent
        if (Double.isNaN(corridorScale) || Math.abs(Math.log(mpp / corridorScale)) > 0.05) {
            areaBatch.clear();
            corridorStyle = areaBatch.style(AreaStyle.hatch(0x30A0FF30, 0x30A0FFC0, 9f, 1.5f, Math.toRadians(45.0)));
            try {
                shapes.corridorAreas(corridorPoints, route.count() + 1, 14_000.0, areaBatch.sink(corridorStyle));
            } catch (IllegalArgumentException cannotTriangulate) {
                // a corridor whose pieces cross itself at this scale is skipped
            }
            double[] c = new double[2];
            view.projection().forward(wp[0], wp[1], c);
            corridorX = c[0];
            corridorY = c[1];
            areas.upload(areaBatch, corridorX, corridorY, null);
            corridorScale = mpp;
            corridorBuilds++;
        }
        lines.begin(ox, oy);
        if (rings) {
            double reach = frame.height() * 0.5 * mpp;
            double[] radii = {niceDistance(reach * 0.25), niceDistance(reach * 0.5), niceDistance(reach * 0.75)};
            shapes.rangeRings(position[0], position[1], radii, shapes.lines(lines.batch(), LineStyle.pixels(1.2f).withColor(0xB0FFB0C0)));
        }
        shapes.route(corridorPoints, route.count() + 1, true, shapes.lines(lines.batch(), LineStyle.pixels(3f).withColor(0x40D0FFFF).withJoin(LineStyle.Join.ROUND)));
        if (trail.size() >= 2) {
            double[] xy = new double[2];
            double[] pts = new double[3 * (trail.size() + 1)];
            for (int i = 0; i < trail.size(); i++) {
                view.projection().forward(trail.get(i)[0], trail.get(i)[1], xy);
                pts[3 * i] = xy[0];
                pts[3 * i + 1] = xy[1];
            }
            view.projection().forward(position[0], position[1], xy);
            pts[3 * trail.size()] = xy[0];
            pts[3 * trail.size() + 1] = xy[1];
            try {
                lines.batch().addPolyline(pts, 0, trail.size() + 1, false, LineStyle.pixels(2f).withColor(0xFFFFFFA0).withDash(8f * (float) view.mapUnitsPerPixel(), 5f * (float) view.mapUnitsPerPixel()));
            } catch (IllegalArgumentException repeatedPoints) {
                // two equal points at the start of a run
            }
        }
        uploaded = lines.end();

        symbolBatch.clear();
        double[] xy = new double[2];
        for (int i = 0; i < route.count(); i++) {
            view.projection().forward(wp[2 * i], wp[2 * i + 1], xy);
            symbolBatch.add(xy[0], xy[1], 0.0, 12f, diamondUv, 0xFFD040FF, 0);
        }
        view.projection().forward(position[0], position[1], xy);
        symbolBatch.add(xy[0], xy[1], -heading, 30f, arrowUv, 0xFFFFFFFF, SymbolGpu.ROTATE_WITH_MAP);
        symbols.upload(symbolBatch, ox, oy, null);
        shapesMs = (System.nanoTime() - t0) / 1e6;
        ctx.stats().record(shapesSeries, shapesMs);

        float[] areaMatrix = new float[16];
        view.viewProjection(ClipSpace.OPENGL, corridorX, corridorY, areaMatrix);
        areas.draw(areaMatrix, 0f, 0f);
        lines.draw(view, frame.width(), frame.height(), ox, oy);
        symbols.draw(vp, frame.width(), frame.height(), SymbolRenderPlan.mapRotation(view), (float) view.pixelsPerMapUnit());
    }

    private void drawTiles(float[] vp, double ox, double oy) {
        int n = selector.count();
        Set<Long> done = new HashSet<>();
        List<long[]> fallbacks = new ArrayList<>();
        drawnTiles = 0;
        usedFallbacks = 0;
        double[] box = new double[4];
        List<int[]> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long k = key(selector.zoom(), selector.x(i), selector.y(i));
            if (tiles.containsKey(k)) {
                order.add(new int[] {i, 0});
            } else {
                for (int up = 1; up <= selector.zoom(); up++) {
                    int z = selector.zoom() - up;
                    long px = selector.x(i) >> up, py = selector.y(i) >> up;
                    if (tiles.containsKey(key(z, px, py))) {
                        if (done.add(key(z, px, py) ^ ((long) selector.world(i) << 40))) {
                            fallbacks.add(new long[] {z, px, py, selector.world(i)});
                        }
                        break;
                    }
                }
            }
        }
        fallbacks.sort((a, b) -> Long.compare(a[0], b[0]));
        for (long[] f : fallbacks) {
            drawTile((int) f[0], f[1], f[2], (int) f[3], box, vp, ox, oy);
            usedFallbacks++;
        }
        for (int[] o : order) {
            drawTile(selector.zoom(), selector.x(o[0]), selector.y(o[0]), selector.world(o[0]), box, vp, ox, oy);
            drawnTiles++;
        }
    }

    private void drawTile(int zoom, long x, long y, int world, double[] box, float[] vp, double ox, double oy) {
        Integer texture = tiles.get(key(zoom, x, y));
        if (texture == null) {
            return;
        }
        grid.tileBounds(zoom, x, y, box);
        double shift = world * grid.width();
        quads.draw(texture, (float) (box[0] + shift - ox), (float) (box[1] - oy), (float) (box[2] + shift - ox), (float) (box[3] - oy), vp, 1f, 1f, 1f, 1f);
    }

    private static double niceDistance(double meters) {
        double e = Math.pow(10, Math.floor(Math.log10(meters)));
        double m = meters / e;
        return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * e;
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.color(1f, 1f, 1f);
        line(hud, "orientation: " + ORIENTATIONS[orientation] + (centered ? ", own position centred" : ", own position low") + (paused ? "   (paused)" : ""));
        line(hud, "position " + GeoFormat.latitude(position[0], GeoFormat.Format.DEGREES_MINUTES, 2) + "  " + GeoFormat.longitude(position[1], GeoFormat.Format.DEGREES_MINUTES, 2, true));
        line(hud, "course " + GeoFormat.bearing(position[2], 0) + "  heading " + GeoFormat.bearing(heading, 0) + "  speed " + GeoFormat.speed(speed, Units.Speed.KNOTS, 0));
        line(hud, "cursor " + GeoFormat.latitude(cursor[0], GeoFormat.Format.DECIMAL, 4) + "  " + GeoFormat.longitude(cursor[1], GeoFormat.Format.DECIMAL, 4, false)
                + (Double.isNaN(cursor[0]) ? "" : "   from here " + GeoFormat.bearingAndRange(bearingTo(cursor), rangeTo(cursor))));
        line(hud, String.format(Locale.ROOT, "scale %s per pixel, tiles zoom %d: %d drawn, %d from a parent, %d generated, %d cached", GeoFormat.distanceAuto(mpp, GeoFormat.UnitSystem.METRIC),
                selector.zoom(), drawnTiles, usedFallbacks, generated, tiles.size()));
        line(hud, String.format(Locale.ROOT, "line tier %s: %d call%s, %.1f KB; shapes and buffers %.3f ms%s", lines.tier().shortName(), lines.calls(), lines.calls() == 1 ? "" : "s", uploaded / 1024.0, shapesMs,
                lines.overflow() ? "  (lines did not fit the buffer)" : ""));
        float w = (float) scaleBar[1];
        float y = frame.height() - 84f;
        hud.rect(20f, y, w, 4f, 1f, 1f, 1f, 1f);
        hud.text(20f, y + 8f, MapKit.ascii(GeoFormat.distanceAuto(scaleBar[0], GeoFormat.UnitSystem.AVIATION)));
    }

    private static void line(Hud hud, String text) {
        hud.line(MapKit.ascii(text));
    }

    private double bearingTo(double[] p) {
        double[] r = new double[3];
        Geodesy.inverse(position[0], position[1], p[0], p[1], r);
        return r[1];
    }

    private double rangeTo(double[] p) {
        double[] r = new double[3];
        Geodesy.inverse(position[0], position[1], p[0], p[1], r);
        return r[0];
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "tiles generated %d, cached %d; last frame: %d drawn, %d from a parent; line calls %d", generated, tiles.size(), drawnTiles, usedFallbacks, lines.calls()));
    }

    @Override
    public void dispose() {
        tileWorkers.shutdownNow();
        for (int t : tiles.values()) {
            org.lwjgl.opengl.GL46.glDeleteTextures(t);
        }
        tiles.clear();
        if (lines != null) {
            lines.close();
        }
        if (quads != null) {
            quads.close();
        }
        if (symbols != null) {
            symbols.close();
        }
        if (areas != null) {
            areas.close();
        }
    }
}
