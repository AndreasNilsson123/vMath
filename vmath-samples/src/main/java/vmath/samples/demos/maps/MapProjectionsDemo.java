package vmath.samples.demos.maps;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import vmath.geo.AzimuthalEquidistant;
import vmath.geo.GeoFormat;
import vmath.geo.Geodesy;
import vmath.geo.MapProjection;
import vmath.geo.PolarStereographic;
import vmath.geo.Utm;
import vmath.geo.WebMercatorProjection;
import vmath.lines.LineStyle;
import vmath.map.MapShapes;
import vmath.map.MapView2d;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Hud;
import vmath.samples.framework.LineTier;
import vmath.samples.framework.Stats;

/**
 * The same data in four projections, switched live: the coast of the procedural planet, a
 * geodesic and a rhumb line between two cities, circles of equal ground radius, and a grid of
 * small circles of equal ground radius that the projection turns into its distortion ellipses
 * (Tissot's indicatrices). For each projection the demo prints the round-trip error of
 * {@code forward} then {@code inverse} over random points of its domain, and how far the straight
 * line between the two cities on the projection is from the length of the geodesic.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class MapProjectionsDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("map-projections", "Map projections: the same data in four of them",
            "Coasts, a geodesic and a rhumb line, circles of equal ground radius and distortion ellipses are drawn in Web Mercator, azimuthal equidistant, polar stereographic and UTM, with the round-trip error and the distance error of each.",
            List.of("maps", "geodesy", "2d"), 262_144L, List.of("--projection-frames", "20"),
            "1-4 or A: projection | T: distortion ellipses | C: coasts | left mouse: pan | wheel: zoom");

    private static final String USAGE = """
            options of the map-projections demo:
              --projection-frames N   frames per projection in a scripted run (default 120)
              --projection N          the projection to start with, 1 to 4 (default 1)
            """;

    private static final double LAT_A = Math.toRadians(52.0), LON_A = Math.toRadians(9.0);
    private static final double LAT_B = Math.toRadians(40.0), LON_B = Math.toRadians(-75.0);
    private static final String[] NAMES = {"Web Mercator", "azimuthal equidistant about city A", "polar stereographic (north)", "UTM zone of city A"};

    private final int projectionFrames;
    private final int firstProjection;
    private DemoContext ctx;
    private MapKit.LineLayer lines;
    private final MapProjection[] projections = new MapProjection[4];
    private final double[] roundTrip = new double[4];
    private final double[] distanceError = new double[4];
    private final double[] scaleAtA = new double[4];
    private List<double[]> coasts;
    private int current;
    private boolean tissot = true;
    private boolean showCoasts = true;
    private boolean cycle;
    private int framesInProjection;
    private double centerLat;
    private double centerLon;
    private double mpp;
    private MapView2d view;
    private String builtFor = "";
    private int polylines;
    private int skipped;
    private double buildMs;
    private long uploaded;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public MapProjectionsDemo(List<String> args) {
        MapArgs a = new MapArgs(args, USAGE);
        projectionFrames = a.integer("--projection-frames", 120, 1, 100_000);
        firstProjection = a.integer("--projection", 1, 1, 4) - 1;
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        lines = new MapKit.LineLayer(LineTier.DRAW_ID, 24L << 20);
        projections[0] = WebMercatorProjection.INSTANCE;
        projections[1] = new AzimuthalEquidistant(LAT_A, LON_A);
        projections[2] = PolarStereographic.ups(true);
        projections[3] = Utm.projection(Utm.zoneOf(LAT_A, LON_A), true);
        coasts = MapKit.coastlines(360, 180, Math.toRadians(84.0));
        for (int i = 0; i < 4; i++) {
            measure(i);
            ctx.stats().note(String.format(Locale.ROOT, "%s: round trip %.3g m, %s, scale at A %.5f", NAMES[i], roundTrip[i],
                    Double.isNaN(distanceError[i]) ? "city B is outside the domain" : String.format(Locale.ROOT, "the straight line A to B is off the geodesic by %.2f percent", 100.0 * distanceError[i]), scaleAtA[i]));
        }
        current = firstProjection;
        select(current);
        ctx.clearColor(0.06f, 0.09f, 0.13f);
    }

    /** Whether the projection can draw the point: inside its domain and in the part of the world that it is used for. */
    private static boolean usable(int index, double lat, double lon) {
        switch (index) {
            case 0:
                return Math.abs(lat) < Math.toRadians(84.0);
            case 1: {
                double[] r = new double[3];
                Geodesy.inverse(LAT_A, LON_A, lat, lon, r);
                return r[0] < 19_500_000.0;
            }
            case 2:
                return lat > Math.toRadians(-5.0);
            default: {
                double cm = Math.toRadians(Utm.zoneOf(LAT_A, LON_A) * 6 - 183);
                return Math.abs(Geodesy.wrapPi(lon - cm)) < Math.toRadians(55.0) && Math.abs(lat) < Math.toRadians(84.0);
            }
        }
    }

    private void measure(int i) {
        MapProjection p = projections[i];
        Random rnd = new Random(7 + i);
        double[] xy = new double[2], back = new double[2], r = new double[3];
        double worst = 0;
        int n = 0;
        while (n < 3000) {
            double lat = Math.toRadians(rnd.nextDouble() * 170 - 85), lon = Math.toRadians(rnd.nextDouble() * 360 - 180);
            if (!usable(i, lat, lon)) {
                continue;
            }
            n++;
            p.forward(lat, lon, xy);
            p.inverse(xy[0], xy[1], back);
            Geodesy.inverse(lat, lon, back[0], back[1], r);
            worst = Math.max(worst, r[0]);
        }
        roundTrip[i] = worst;
        Geodesy.inverse(LAT_A, LON_A, LAT_B, LON_B, r);
        double geodesic = r[0];
        double[] a = new double[2], b = new double[2];
        distanceError[i] = Double.NaN;
        if (usable(i, LAT_A, LON_A) && usable(i, LAT_B, LON_B)) {
            p.forward(LAT_A, LON_A, a);
            p.forward(LAT_B, LON_B, b);
            double planar = Math.hypot(a[0] - b[0], a[1] - b[1]);
            distanceError[i] = (planar - geodesic) / geodesic;
        }
        scaleAtA[i] = p.scale(LAT_A, LON_A);
    }

    private void select(int index) {
        current = index;
        framesInProjection = 0;
        switch (index) {
            case 0 -> {
                centerLat = Math.toRadians(30.0);
                centerLon = 0.0;
                mpp = 24_000.0 / 1.0;
            }
            case 1 -> {
                centerLat = LAT_A;
                centerLon = LON_A;
                mpp = 24_000.0;
            }
            case 2 -> {
                centerLat = Math.toRadians(89.0);
                centerLon = 0.0;
                mpp = 16_000.0;
            }
            default -> {
                centerLat = LAT_A;
                centerLon = LON_A;
                mpp = 7_000.0;
            }
        }
        builtFor = "";
    }

    @Override
    public void update(FrameInfo frame) {
        var in = frame.input();
        if (frame.benchmark()) {
            int idx = (firstProjection + frame.frame() / projectionFrames) % 4;
            if (idx != current) {
                select(idx);
            }
        } else {
            for (int i = 0; i < 4; i++) {
                if (in.pressed(GLFW_KEY_1 + i)) {
                    select(i);
                    cycle = false;
                }
            }
            if (in.pressed(GLFW_KEY_A)) {
                cycle = !cycle;
            }
            if (cycle && ++framesInProjection >= projectionFrames) {
                select((current + 1) % 4);
            }
            if (in.pressed(GLFW_KEY_T)) {
                tissot = !tissot;
            }
            if (in.pressed(GLFW_KEY_C)) {
                showCoasts = !showCoasts;
            }
            if (in.mouseDown(GLFW_MOUSE_BUTTON_LEFT) && view != null) {
                double[] ll = new double[2];
                view.toGeographic(frame.width() / 2.0 - in.mouseDx(), frame.height() / 2.0 - in.mouseDy(), ll);
                if (Double.isFinite(ll[0]) && Double.isFinite(ll[1]) && usable(current, ll[0], ll[1])) {
                    centerLat = Math.max(Math.toRadians(-84), Math.min(Math.toRadians(89.5), ll[0]));
                    centerLon = ll[1];
                }
            }
            float notches = in.scroll();
            if (notches != 0f) {
                mpp = Math.max(300.0, Math.min(80_000.0, mpp * Math.pow(0.87, notches)));
            }
        }
        view = MapView2d.of(projections[current], centerLat, centerLon, frame.width(), frame.height()).withMetersPerPixel(mpp);
    }

    private void build() {
        long t0 = System.nanoTime();
        double ox = view.centerX(), oy = view.centerY();
        lines.begin(ox, oy);
        polylines = 0;
        skipped = 0;
        MapProjection p = projections[current];
        double[] a = new double[2], b = new double[2];
        double[] seg = new double[6];
        double limit = 25_000_000.0;
        if (showCoasts) {
            LineStyle coast = LineStyle.pixels(1.3f).withColor(0xE8E0B0FF);
            for (double[] s : coasts) {
                if (!usable(current, s[0], s[1]) || !usable(current, s[2], s[3])) {
                    skipped++;
                    continue;
                }
                p.forward(s[0], s[1], a);
                p.forward(s[2], s[3], b);
                if (current == 0 && Math.abs(a[0] - b[0]) > 15_000_000.0 || Math.abs(a[0]) > limit || Math.abs(a[1]) > limit || Math.abs(b[0]) > limit
                        || Math.abs(b[1]) > limit) {
                    skipped++;
                    continue;
                }
                seg[0] = a[0];
                seg[1] = a[1];
                seg[3] = b[0];
                seg[4] = b[1];
                if (a[0] != b[0] || a[1] != b[1]) {
                    lines.batch().addPolyline(seg, 0, 2, false, coast);
                    polylines++;
                }
            }
        }
        MapShapes shapes = new MapShapes(view, 0.6);
        try {
            if (tissot) {
                MapShapes.Sink tissotSink = shapes.lines(lines.batch(), LineStyle.pixels(1.5f).withColor(0xFF9040FF));
                for (int la = -60; la <= 80; la += 20) {
                    for (int lo = -180; lo < 180; lo += 30) {
                        double lat = Math.toRadians(la), lon = Math.toRadians(lo);
                        if (usable(current, lat, lon) && usable(current, lat + Math.toRadians(5), lon) && usable(current, lat - Math.toRadians(5), lon)) {
                            try {
                                shapes.circle(lat, lon, 450_000.0, tissotSink);
                            } catch (IllegalArgumentException outside) {
                                skipped++;
                            }
                        }
                    }
                }
            }
            MapShapes.Sink big = shapes.lines(lines.batch(), LineStyle.pixels(2f).withColor(0x60E0FFFF));
            for (double[] c : new double[][] {{LAT_A, LON_A}, {LAT_B, LON_B}}) {
                if (usable(current, c[0], c[1])) {
                    shapes.circle(c[0], c[1], 1_500_000.0, big);
                }
            }
            if (usable(current, LAT_A, LON_A) && usable(current, LAT_B, LON_B)) {
                shapes.geodesicLeg(LAT_A, LON_A, LAT_B, LON_B, shapes.lines(lines.batch(), LineStyle.pixels(3f).withColor(0x40FF80FF)));
                shapes.rhumbLeg(LAT_A, LON_A, LAT_B, LON_B, shapes.lines(lines.batch(), LineStyle.pixels(3f).withColor(0xFF4080FF)));
            }
        } catch (IllegalArgumentException domain) {
            skipped++;
        }
        uploaded = lines.end();
        buildMs = (System.nanoTime() - t0) / 1e6;
    }

    @Override
    public void render(FrameInfo frame) {
        String key = current + "|" + view.centerLatitude() + "|" + view.centerLongitude() + "|" + mpp + "|" + tissot + "|" + showCoasts + "|" + frame.width() + "x" + frame.height();
        if (!key.equals(builtFor)) {
            build();
            builtFor = key;
        }
        lines.draw(view, frame.width(), frame.height(), view.centerX(), view.centerY());
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.color(1f, 1f, 1f).line(MapKit.ascii((current + 1) + " of 4: " + NAMES[current] + (cycle ? "   (cycling)" : "")));
        hud.line(String.format(Locale.ROOT, "round trip over 3000 random points of its domain: worst %.3g m", roundTrip[current]));
        hud.line(Double.isNaN(distanceError[current]) ? "the straight line between the cities is outside this projection"
                : String.format(Locale.ROOT, "straight line A to B on the plane: %+.2f percent of the geodesic (%s)", 100 * distanceError[current], GeoFormat.distanceAuto(
                        geodesicAB(), GeoFormat.UnitSystem.METRIC)));
        hud.line(String.format(Locale.ROOT, "scale at A %.5f; %d polylines, %d skipped, built in %.1f ms, %.1f KB, %d call%s", scaleAtA[current], polylines, skipped, buildMs, uploaded / 1024.0, lines.calls(),
                lines.calls() == 1 ? "" : "s"));
        hud.color(0.5f, 1f, 0.6f).line("green: geodesic A to B");
        hud.color(1f, 0.5f, 0.7f).line("pink: rhumb line A to B");
        hud.color(1f, 0.65f, 0.3f).line("orange: circles of 450 km on the ground (distortion ellipses)");
        hud.color(0.5f, 0.9f, 1f).line("blue: circles of 1,500 km about the cities");
        for (int i = 0; i < 4; i++) {
            hud.color(i == current ? 1f : 0.7f, i == current ? 1f : 0.7f, i == current ? 0.5f : 0.7f).line(String.format(Locale.ROOT, "%d  %s: round trip %.2g m", i + 1, NAMES[i], roundTrip[i]));
        }
    }

    private static double geodesicAB() {
        double[] r = new double[3];
        Geodesy.inverse(LAT_A, LON_A, LAT_B, LON_B, r);
        return r[0];
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "last build: %d polylines, %d skipped, %.1f ms, %.1f KB", polylines, skipped, buildMs, uploaded / 1024.0));
    }

    @Override
    public void dispose() {
        if (lines != null) {
            lines.close();
        }
    }
}
