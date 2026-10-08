package vmath.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Wgs84;
import vmath.geo.AzimuthalEquidistant;
import vmath.geo.GeodesicLine;
import vmath.geo.Geodesy;
import vmath.geo.MapProjection;
import vmath.geo.Polygons;
import vmath.geo.Utm;
import vmath.geo.WebMercatorProjection;
import vmath.lines.LineBatch;
import vmath.lines.LineRenderPlan;
import vmath.lines.LineSet;
import vmath.lines.LineStyle;
import vmath.lines.LineStrategy;
import vmath.gl.GraphicsCapabilities;

/**
 * {@link MapShapes}: that the samples are on the true curves and within the tolerance of them in
 * pixels, that circles in the azimuthal view are circles, that sectors, corridors and routes have
 * the shape they are named after, and that the sinks feed the line containers.
 */
class MapShapesTest {

    private final SplittableRandom rnd = new SplittableRandom(8);

    private static double deg(double d) {
        return Math.toRadians(d);
    }

    /** What a sink got. */
    private static final class Collector implements MapShapes.Sink {
        final List<double[]> lines = new ArrayList<>();
        final List<Boolean> closed = new ArrayList<>();
        final List<double[]> areas = new ArrayList<>();

        @Override
        public void line(double[] xy, int n, boolean isClosed) {
            lines.add(java.util.Arrays.copyOf(xy, 2 * n));
            closed.add(isClosed);
        }

        @Override
        public void area(double[] xy, int n) {
            areas.add(java.util.Arrays.copyOf(xy, 2 * n));
        }
    }

    private static MapView2d aeView(double mpp) {
        return MapView2d.of(new AzimuthalEquidistant(deg(59.33), deg(18.07)), deg(59.33), deg(18.07), 1000, 1000).withMetersPerPixel(mpp);
    }

    private static double distanceToPolyline(double[] xy, boolean closed, double px, double py) {
        int n = xy.length / 2;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + (closed ? 0 : 1) < n; i++) {
            int j = (i + 1) % n;
            double ax = xy[2 * i], ay = xy[2 * i + 1], bx = xy[2 * j], by = xy[2 * j + 1];
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 > 0 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2)) : 0;
            best = Math.min(best, Math.hypot(px - ax - t * dx, py - ay - t * dy));
        }
        return best;
    }

    private static float[] relative(double[] xy, MapView2d v) {
        float[] f = new float[xy.length];
        for (int i = 0; i < xy.length; i += 2) {
            f[i] = (float) (xy[i] - v.centerX());
            f[i + 1] = (float) (xy[i + 1] - v.centerY());
        }
        return f;
    }

    // ---------------------------------------------------------------- circles

    @Test
    void aCircleInTheAzimuthalViewIsACircleSampledToTheTolerance() {
        for (double radius : new double[] {2_000.0, 40_000.0, 500_000.0}) {
            MapView2d v = aeView(radius / 400.0);
            for (double tolPx : new double[] {2.0, 0.5, 0.1}) {
                MapShapes shapes = new MapShapes(v, tolPx);
                Collector c = new Collector();
                int n = shapes.circle(deg(59.33), deg(18.07), radius, c);
                assertEquals(1, c.lines.size());
                assertTrue(c.closed.get(0));
                double[] xy = c.lines.get(0);
                for (int i = 0; i < n; i++) {
                    assertEquals(radius, Math.hypot(xy[2 * i], xy[2 * i + 1]), radius * 1e-9, "a vertex of the ring is at the radius");
                }
                // the chord of the widest piece is within the tolerance of the circle
                double worst = 0;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    double mx = 0.5 * (xy[2 * i] + xy[2 * j]), my = 0.5 * (xy[2 * i + 1] + xy[2 * j + 1]);
                    worst = Math.max(worst, radius - Math.hypot(mx, my));
                }
                assertTrue(worst <= tolPx * v.mapUnitsPerPixel() * 1.0001, "the chord deviates by " + worst / v.mapUnitsPerPixel() + " px, tolerance " + tolPx);
            }
        }
        // a finer tolerance needs more points, about the square root of the ratio
        MapView2d v = aeView(100.0);
        Collector a = new Collector(), b = new Collector();
        int coarse = new MapShapes(v, 1.0).circle(deg(59.33), deg(18.07), 40_000.0, a), fine = new MapShapes(v, 0.01).circle(deg(59.33), deg(18.07), 40_000.0, b);
        assertTrue(fine > 5 * coarse && fine < 20 * coarse, coarse + " points at one pixel, " + fine + " at a hundredth");
    }

    @Test
    void aCircleInAMercatorViewIsOnTheGroundCircleWithinTheTolerance() {
        for (MapProjection p : new MapProjection[] {WebMercatorProjection.INSTANCE, Utm.projection(33, true)}) {
            MapView2d v = MapView2d.of(p, deg(65), deg(18), 1000, 1000).withMetersPerPixel(1000.0);
            MapShapes shapes = new MapShapes(v, 0.5);
            Collector c = new Collector();
            shapes.circle(deg(65), deg(18), 300_000.0, c);
            double[] xy = c.lines.get(0);
            double[] q = new double[3], pr = new double[2];
            double worst = 0;
            for (int k = 0; k < 3000; k++) {
                Geodesy.direct(deg(65), deg(18), 2 * Math.PI * k / 3000, 300_000.0, q);
                p.forward(q[0], q[1], pr);
                worst = Math.max(worst, distanceToPolyline(xy, true, pr[0], pr[1]));
            }
            assertTrue(worst <= 0.5 * v.mapUnitsPerPixel() * 1.02, p.name() + ": the true circle is within " + worst / v.mapUnitsPerPixel() + " px of the ring");
            // it is not a circle in the map: the two radii differ
            double minR = Double.MAX_VALUE, maxR = 0;
            for (int i = 0; i < xy.length / 2; i++) {
                double d = Math.hypot(xy[2 * i] - v.centerX(), xy[2 * i + 1] - v.centerY());
                minR = Math.min(minR, d);
                maxR = Math.max(maxR, d);
            }
            assertTrue(maxR > minR * 1.0005 || p == Utm.projection(33, true), "Mercator stretches a circle");
        }
    }

    @Test
    void arcsAndSectorsAreWhatTheyAreNamed() {
        MapView2d v = aeView(200.0);
        MapShapes shapes = new MapShapes(v, 0.5);
        Collector c = new Collector();
        int n = shapes.arc(deg(59.33), deg(18.07), 50_000.0, deg(30), deg(90), c);
        double[] xy = c.lines.get(0);
        assertFalse(c.closed.get(0));
        assertEquals(50_000.0 * Math.sin(deg(30)), xy[0], 1e-6, "the arc starts at bearing 30");
        assertEquals(50_000.0 * Math.cos(deg(30)), xy[1], 1e-6);
        assertEquals(50_000.0 * Math.sin(deg(120)), xy[2 * n - 2], 1e-6, "and ends 90 degrees clockwise later");
        assertEquals(50_000.0 * Math.cos(deg(120)), xy[2 * n - 1], 1e-6);
        Collector back = new Collector();
        int m = shapes.arc(deg(59.33), deg(18.07), 50_000.0, deg(30), -deg(90), back);
        assertEquals(50_000.0 * Math.sin(deg(-60)), back.lines.get(0)[2 * m - 2], 1e-6, "a negative sweep goes anticlockwise");
        // a sector: the centre, the arc and back
        Collector s = new Collector();
        int sn = shapes.sector(deg(59.33), deg(18.07), 50_000.0, deg(30), deg(90), s, true);
        assertEquals(1, s.areas.size());
        double[] ring = s.areas.get(0);
        assertEquals(0.0, ring[0], 1e-9, "the first vertex is the centre");
        assertEquals(0.0, ring[1], 1e-9);
        double area = Math.abs(Polygons.signedArea(relative(ring, v), sn)) * 1.0;
        assertEquals(Math.PI / 4 * 50_000.0 * 50_000.0, area, 0.002 * Math.PI / 4 * 2.5e9, "the area of a quarter of a disc");
        assertTrue(Polygons.isSimple(relative(ring, v), sn));
        // no vertex repeats
        for (int i = 0; i < sn; i++) {
            int j = (i + 1) % sn;
            assertTrue(Math.hypot(ring[2 * i] - ring[2 * j], ring[2 * i + 1] - ring[2 * j + 1]) > 1e-6, "no repeated vertex at " + i);
        }
        Collector outline = new Collector();
        shapes.sector(deg(59.33), deg(18.07), 50_000.0, deg(30), deg(90), outline, false);
        assertEquals(1, outline.lines.size());
        assertTrue(outline.closed.get(0));
        assertThrows(IllegalArgumentException.class, () -> shapes.arc(0, 0, 100, 0, 0, new Collector()));
        assertThrows(IllegalArgumentException.class, () -> shapes.arc(0, 0, 100, 0, 7, new Collector()));
        assertThrows(IllegalArgumentException.class, () -> shapes.circle(0, 0, -5, new Collector()));
        assertThrows(IllegalArgumentException.class, () -> shapes.circle(0, 0, 2.1e7, new Collector()));
        assertThrows(IllegalArgumentException.class, () -> new MapShapes(v, 0));
    }

    // ---------------------------------------------------------------- legs and routes

    @Test
    void aGeodesicLegIsOnTheGeodesicAndARhumbLegOnTheRhumbLine() {
        MapProjection p = WebMercatorProjection.INSTANCE;
        MapView2d v = MapView2d.of(p, deg(50), deg(-30), 1200, 800).withMetersPerPixel(8000.0);
        MapShapes shapes = new MapShapes(v, 0.5);
        double latA = deg(51), lonA = deg(-5), latB = deg(40), lonB = deg(-74);   // London to New York, roughly
        Collector g = new Collector(), r = new Collector();
        shapes.geodesicLeg(latA, lonA, latB, lonB, g);
        shapes.rhumbLeg(latA, lonA, latB, lonB, r);
        double[] ab = new double[3];
        Geodesy.inverse(latA, lonA, latB, lonB, ab);
        GeodesicLine line = new GeodesicLine(latA, lonA, ab[1]);
        double[] q = new double[3], pr = new double[2];
        double worst = 0;
        for (int k = 0; k <= 2000; k++) {
            line.positionContinuous(ab[0] * k / 2000, q);
            p.forward(q[0], q[1], pr);
            worst = Math.max(worst, distanceToPolyline(g.lines.get(0), false, pr[0], pr[1]));
        }
        assertTrue(worst <= 0.5 * v.mapUnitsPerPixel() * 1.02, "the geodesic is within " + worst / v.mapUnitsPerPixel() + " px");
        double[] gl = g.lines.get(0), rl = r.lines.get(0);
        p.forward(latA, lonA, pr);
        assertEquals(pr[0], gl[0], 1e-6);
        assertEquals(pr[1], gl[1], 1e-6);
        p.forward(latB, lonB, pr);
        assertEquals(pr[0], gl[gl.length - 2], 1e-6);
        assertEquals(pr[1], gl[gl.length - 1], 1e-6);
        assertTrue(g.lines.get(0).length > 8, "the geodesic is curved on a Mercator map: several points");
        // the rhumb line is almost straight on a Mercator map (exactly so on the ellipsoidal one): collinear to the difference of the two Mercators
        double ax = rl[0], ay = rl[1], bx = rl[rl.length - 2], by = rl[rl.length - 1];
        double maxOff = 0;
        for (int i = 0; i < rl.length / 2; i++) {
            maxOff = Math.max(maxOff, distanceToPolyline(new double[] {ax, ay, bx, by}, false, rl[2 * i], rl[2 * i + 1]));
        }
        assertTrue(maxOff < 0.01 * Math.hypot(bx - ax, by - ay), "the rhumb line is near the straight line of the map: " + maxOff);
        assertTrue(rl.length < gl.length, "and needs fewer points than the geodesic");
    }

    @Test
    void aLegAcrossTheAntimeridianStaysInOnePiece() {
        MapProjection p = WebMercatorProjection.INSTANCE;
        MapView2d v = MapView2d.of(p, 0, deg(180), 1000, 600).withMetersPerPixel(20_000.0);
        Collector c = new Collector();
        new MapShapes(v, 0.5).geodesicLeg(deg(35), deg(170), deg(40), deg(-170), c);
        double[] xy = c.lines.get(0);
        for (int i = 1; i < xy.length / 2; i++) {
            assertTrue(Math.abs(xy[2 * i] - xy[2 * i - 2]) < 5.0e6, "no jump of the width of the world (40 000 km) between successive points");
        }
        assertTrue(xy[xy.length - 2] > 20_037_508.0, "the end is beyond the edge of the world (continued periodically)");
    }

    @Test
    void aRouteJoinsItsLegsWithoutRepeatingTheWaypoints() {
        MapView2d v = aeView(100.0);
        double[] wp = {deg(59.3), deg(18.0), deg(59.5), deg(18.4), deg(59.7), deg(18.3), deg(59.6), deg(17.8)};
        MapShapes shapes = new MapShapes(v, 0.5);
        Collector c = new Collector();
        int n = shapes.route(wp, 4, true, c);
        double[] xy = c.lines.get(0);
        assertEquals(n, xy.length / 2);
        double[] pr = new double[2];
        for (int w = 0; w < 4; w++) {
            v.projection().forward(wp[2 * w], wp[2 * w + 1], pr);
            assertEquals(0.0, distanceToPolyline(xy, false, pr[0], pr[1]), 1e-5, "waypoint " + w + " is on the route");
        }
        for (int i = 1; i < n; i++) {
            assertTrue(Math.hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1]) > 1e-6, "no repeated point");
        }
        Collector rh = new Collector();
        assertTrue(shapes.route(wp, 4, false, rh) >= 4);
        assertThrows(IllegalArgumentException.class, () -> shapes.route(wp, 1, true, new Collector()));
        assertThrows(IllegalArgumentException.class, () -> shapes.route(new double[] {0, 0}, 2, true, new Collector()));
        assertThrows(IllegalArgumentException.class, () -> shapes.route(new double[] {0, 0, 0, 0}, 2, true, new Collector()), "the same point twice");
    }

    // ---------------------------------------------------------------- corridors

    private static double routeDistance(double[] wp, int n, double lat, double lon) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < n; i++) {
            best = Math.min(best, Geodesy.distanceToLeg(wp[2 * i], wp[2 * i + 1], wp[2 * i + 2], wp[2 * i + 3], lat, lon));
        }
        return best;
    }

    @Test
    void theAreasOfACorridorCoverExactlyThePointsWithinHalfTheWidth() {
        MapView2d v = aeView(60.0);
        double[] wp = {deg(59.30), deg(18.00), deg(59.45), deg(18.35), deg(59.60), deg(18.25), deg(59.55), deg(17.80)};
        double width = 6000.0;
        MapShapes shapes = new MapShapes(v, 0.3);
        Collector c = new Collector();
        int polygons = shapes.corridorAreas(wp, 4, width, c);
        assertEquals(3 + 4, polygons, "a strip for each leg and a disc at each waypoint");
        assertEquals(polygons, c.areas.size());
        List<float[]> rel = new ArrayList<>();
        for (double[] a : c.areas) {
            rel.add(relative(a, v));
            assertTrue(Polygons.isSimple(rel.get(rel.size() - 1), a.length / 2), "each piece is a simple polygon");
        }
        double[] pr = new double[2], ll = new double[2];
        int inside = 0, outside = 0;
        for (int i = 0; i < 4000; i++) {
            double x = v.centerX() + (rnd.nextDouble() - 0.5) * 80_000.0, y = v.centerY() + (rnd.nextDouble() - 0.5) * 80_000.0;
            v.projection().inverse(x, y, ll);
            double d = routeDistance(wp, 4, ll[0], ll[1]);
            if (Math.abs(d - width / 2) < 60.0) {
                continue;   // too close to the edge to ask
            }
            boolean covered = false;
            for (int k = 0; k < rel.size() && !covered; k++) {
                covered = Polygons.contains(rel.get(k), c.areas.get(k).length / 2, (float) (x - v.centerX()), (float) (y - v.centerY()));
            }
            assertEquals(d < width / 2, covered, "a point " + d + " m from the route, half the width is " + width / 2);
            if (covered) {
                inside++;
            } else {
                outside++;
            }
        }
        assertTrue(inside > 100 && outside > 100, "both kinds of point were tested: " + inside + ", " + outside);
        assertThrows(IllegalArgumentException.class, () -> shapes.corridorAreas(wp, 4, 0, new Collector()));
        assertThrows(IllegalArgumentException.class, () -> shapes.corridorAreas(wp, 1, 100, new Collector()));
    }

    @Test
    void theOutlineOfACorridorIsARingAtHalfTheWidthWithRoundEnds() {
        MapView2d v = aeView(60.0);
        double[] wp = {deg(59.30), deg(18.00), deg(59.45), deg(18.35), deg(59.60), deg(18.25), deg(59.55), deg(17.80)};
        double width = 6000.0;
        MapShapes shapes = new MapShapes(v, 0.3);
        Collector c = new Collector();
        int n = shapes.corridorOutline(wp, 4, width, c);
        assertEquals(1, c.lines.size());
        assertTrue(c.closed.get(0));
        double[] xy = c.lines.get(0);
        double[] ll = new double[2];
        int onSide = 0;
        for (int i = 0; i < n; i++) {
            v.projection().inverse(xy[2 * i], xy[2 * i + 1], ll);
            double d = routeDistance(wp, 4, ll[0], ll[1]);
            assertTrue(d <= width / 2 + 0.5, "a vertex of the outline is at most half the width from the route: " + d);
            assertTrue(d >= width / 2 * 0.3, "and not far inside");
            if (Math.abs(d - width / 2) < 0.5) {
                onSide++;
            }
        }
        assertTrue(onSide > n * 0.8, "most vertices are exactly half the width away: " + onSide + " of " + n);
        // the ring contains the route and not a point beyond the width
        float[] rel = relative(xy, v);
        double[] pr = new double[2];
        for (int w = 0; w < 4; w++) {
            v.projection().forward(wp[2 * w], wp[2 * w + 1], pr);
            assertTrue(Polygons.contains(rel, n, (float) (pr[0] - v.centerX()), (float) (pr[1] - v.centerY())), "waypoint " + w + " is inside the outline");
        }
        double[] far = new double[3];
        Geodesy.direct(wp[0], wp[1], deg(200), 5000.0, far);   // behind the start, beyond the round end
        v.projection().forward(far[0], far[1], pr);
        assertFalse(Polygons.contains(rel, n, (float) (pr[0] - v.centerX()), (float) (pr[1] - v.centerY())));
    }

    // ---------------------------------------------------------------- sinks

    @Test
    void theSinksFeedALineBatchAndALineSet() {
        MapView2d v = aeView(100.0);
        MapShapes shapes = new MapShapes(v, 0.5);
        LineBatch batch = new LineBatch();
        batch.setOrigin(v.centerX(), v.centerY(), 0.0);
        MapShapes.Sink sink = shapes.lines(batch, LineStyle.pixels(2f));
        shapes.rangeRings(deg(59.33), deg(18.07), new double[] {5_000, 10_000, 20_000}, sink);
        assertEquals(3, batch.polylineCount());
        assertTrue(batch.isClosed(0));
        assertEquals(10_000.0, Math.hypot(batch.coordinate(1, 0, 0), batch.coordinate(1, 0, 1)), 1e-6);
        LineSet set = new LineSet(LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GraphicsCapabilities.baseline()), 10_000);
        List<Long> handles = new ArrayList<>();
        shapes.circle(deg(59.33), deg(18.07), 8000.0, shapes.lines(set, LineStyle.pixels(1f), handles::add));
        assertEquals(1, handles.size());
        assertTrue(set.contains(handles.get(0)));
        assertEquals(v, shapes.view());
        assertEquals(0.5 * v.mapUnitsPerPixel(), shapes.tolerance(), 1e-9);
        assertEquals(Wgs84.A, Wgs84.A);
    }
}
