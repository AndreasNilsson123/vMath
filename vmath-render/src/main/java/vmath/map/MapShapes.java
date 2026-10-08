package vmath.map;

import java.util.Arrays;
import vmath.annotations.Experimental;
import vmath.core.Wgs84;
import vmath.geo.GeodesicLine;
import vmath.geo.Geodesy;
import vmath.geo.MapProjection;

/**
 * Shapes on the ellipsoid, sampled into polylines and polygons in the <em>projected</em> coordinates
 * of a {@link MapView2d} to a tolerance in pixels of that view: circles (range rings, threat domes),
 * arcs and sectors, great-circle and rhumb legs, routes and route corridors with a width.
 *
 * <p>A circle is the set of points at a geodesic distance from a centre, an arc a part of it, a leg
 * the geodesic or the rhumb line between two points, and a corridor the set of points within a
 * ground distance of a route, with round joins at the corners and round ends: all of them <em>on the
 * ellipsoid</em>, so a radius of 20 nautical miles is 20 nautical miles whatever the projection
 * draws it as (a circle in an azimuthal equidistant view about its centre, an oval in a Mercator
 * view). The curves are sampled <b>adaptively</b>: an interval is split until its midpoint is within
 * the tolerance of the chord, so a ring of 5 km needs a handful of points and a ring of 2000 km many
 * more, whatever the projection does to it, and zooming in refines the sampling (make a new instance for
 * the new view).
 *
 * <p>The results go to a {@link Sink}: {@link Sink#line} for polylines (and closed rings), and
 * {@link Sink#area} for the simple polygons of a filled shape. The array passed to the sink belongs
 * to the sampler and is valid during the call only. {@link #lines(vmath.lines.LineBatch, vmath.lines.LineStyle)}
 * and {@link AreaBatch#sink} give sinks that feed a {@code LineBatch} and the polygons of an {@code AreaBatch}.
 *
 * <p>The coordinates are projected metres, with longitudes continued across the antimeridian for
 * the projections that are periodic (a Mercator map): a shape that crosses it is one piece that
 * extends beyond the edge of the world, to be drawn again shifted by the width of the world when the
 * view straddles the edge.
 *
 * <p><b>Thread safety.</b> Not thread-safe: an instance has working buffers. Make one per thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MapShapes shapes = new MapShapes(view, 0.5);                         // half a pixel of tolerance
 * LineBatch batch = new LineBatch();
 * batch.setOrigin(view.centerX(), view.centerY(), 0.0);
 * MapShapes.Sink rings = shapes.lines(batch, LineStyle.pixels(1.5f).withColor(0x00FF00FF));
 * for (double nm = 5; nm <= 20; nm += 5) {
 *     shapes.circle(ownLat, ownLon, nm * 1852.0, rings);               // range rings
 * }
 * shapes.route(waypoints, 4, true, rings);                             // a route of great-circle legs
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class MapShapes {

    /** Receives the shapes in projected metres. */
    public interface Sink {
        /**
         * Receives a polyline or a closed ring.
         *
         * @param xy the points as {@code x, y} pairs, valid during the call only
         * @param pointCount the number of points; a closed ring does not repeat its first point
         * @param closed whether the last point is joined to the first
         */
        void line(double[] xy, int pointCount, boolean closed);

        /**
         * Receives one simple polygon of a filled shape (a shape that is not simple is made of several).
         *
         * @param xy the vertices as {@code x, y} pairs, valid during the call only, in no
         *     particular winding
         * @param pointCount the number of vertices
         */
        default void area(double[] xy, int pointCount) {
            line(xy, pointCount, true);
        }
    }

    private interface Curve {
        void at(double t, double[] xy);
    }

    private final MapView2d view;
    private final MapProjection projection;
    private final double tolerance;
    private double[] buffer = new double[256];
    private int count;

    /**
     * Makes a sampler for a view.
     *
     * @param view the view; must not be {@code null}
     * @param tolerancePixels the largest distance of the true curve from the polyline in pixels of
     *     the view, positive; half a pixel is invisible
     * @throws IllegalArgumentException if the tolerance is not positive
     */
    public MapShapes(MapView2d view, double tolerancePixels) {
        if (!(tolerancePixels > 0) || !Double.isFinite(tolerancePixels)) {
            throw new IllegalArgumentException("the tolerance must be positive: " + tolerancePixels);
        }
        this.view = java.util.Objects.requireNonNull(view);
        this.projection = view.projection();
        this.tolerance = tolerancePixels * view.mapUnitsPerPixel();
    }

    /**
     * Gives the tolerance in projected metres.
     *
     * @return the tolerance in map units
     */
    public double tolerance() {
        return tolerance;
    }

    // ---------------------------------------------------------------- sampling

    private void push(double x, double y) {
        if (2 * count + 2 > buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[2 * count] = x;
        buffer[2 * count + 1] = y;
        count++;
    }

    private static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        double t = len2 > 0 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2)) : 0;
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** Samples a curve between two parameters into the buffer: the first point is not added, the last is. */
    private void subdivide(Curve c, double t0, double[] p0, double t1, double[] p1, int depth) {
        double tm = 0.5 * (t0 + t1);
        double[] pm = new double[2];
        c.at(tm, pm);
        // two probes, a quarter and three quarters of the way, so that a curve that returns to the chord at the middle is not taken for straight
        double[] pq = new double[2], pr = new double[2];
        c.at(0.5 * (t0 + tm), pq);
        c.at(0.5 * (tm + t1), pr);
        double dev = Math.max(distanceToSegment(pm[0], pm[1], p0[0], p0[1], p1[0], p1[1]),
                Math.max(distanceToSegment(pq[0], pq[1], p0[0], p0[1], p1[0], p1[1]), distanceToSegment(pr[0], pr[1], p0[0], p0[1], p1[0], p1[1])));
        if (dev > tolerance && depth < 18) {
            subdivide(c, t0, p0, tm, pm, depth + 1);
            subdivide(c, tm, pm, t1, p1, depth + 1);
        } else {
            push(p1[0], p1[1]);
        }
    }

    /** Samples the curve over [t0, t1] with a number of first pieces, into a fresh buffer. */
    private void sample(Curve c, double t0, double t1, int pieces) {
        count = 0;
        double[] a = new double[2], b = new double[2];
        c.at(t0, a);
        push(a[0], a[1]);
        for (int i = 1; i <= pieces; i++) {
            double ta = t0 + (t1 - t0) * (i - 1) / pieces, tb = t0 + (t1 - t0) * i / pieces;
            double[] pa = new double[2], pb = new double[2];
            c.at(ta, pa);
            c.at(tb, pb);
            subdivide(c, ta, pa, tb, pb, 0);
        }
    }

    private double unwrapNear(double lon, double ref) {
        return ref + Geodesy.wrapPi(lon - ref);
    }

    // ---------------------------------------------------------------- circles, arcs, sectors

    private Curve circleCurve(double lat, double lon, double radius, double startBearing) {
        return (t, xy) -> {
            double[] p = new double[3];
            Geodesy.direct(lat, lon, startBearing + t, radius, p);
            projection.forward(p[0], unwrapNear(p[1], lon), xy);
        };
    }

    private static void checkRadius(double radius) {
        if (!(radius > 0) || !Double.isFinite(radius) || radius > Math.PI * Wgs84.A) {
            throw new IllegalArgumentException("the radius must be positive and at most half the circumference of the Earth: " + radius);
        }
    }

    /**
     * Samples a circle of constant geodesic radius as a closed ring (a line).
     *
     * @param lat the latitude of the centre in radians
     * @param lon the longitude of the centre in radians
     * @param radius the radius in metres, positive and less than half the circumference
     * @param sink receives the ring; must not be {@code null}
     * @return the number of points of the ring
     * @throws IllegalArgumentException if the radius is invalid or a point is outside the domain of the projection
     */
    public int circle(double lat, double lon, double radius, Sink sink) {
        checkRadius(radius);
        sample(circleCurve(lat, lon, radius, 0.0), 0.0, 2 * Math.PI, 16);
        count--;   // the last point repeats the first
        sink.line(buffer, count, true);
        return count;
    }

    /**
     * Samples a circle as a filled disc (one simple polygon).
     *
     * @param lat the latitude of the centre in radians
     * @param lon the longitude of the centre in radians
     * @param radius the radius in metres
     * @param sink receives the polygon
     * @return the number of vertices
     * @throws IllegalArgumentException as {@link #circle}
     */
    public int disc(double lat, double lon, double radius, Sink sink) {
        checkRadius(radius);
        sample(circleCurve(lat, lon, radius, 0.0), 0.0, 2 * Math.PI, 16);
        count--;
        sink.area(buffer, count);
        return count;
    }

    /**
     * Samples an arc of a circle as an open polyline.
     *
     * @param lat the latitude of the centre in radians
     * @param lon the longitude of the centre in radians
     * @param radius the radius in metres
     * @param startBearing the bearing from the centre of the start of the arc, in radians
     * @param sweep the angle swept clockwise from the start, in radians; negative for anticlockwise
     * @param sink receives the polyline
     * @return the number of points
     * @throws IllegalArgumentException if the radius is invalid or the sweep is zero or more than a full turn
     */
    public int arc(double lat, double lon, double radius, double startBearing, double sweep, Sink sink) {
        checkRadius(radius);
        if (!(Math.abs(sweep) > 0) || Math.abs(sweep) > 2 * Math.PI) {
            throw new IllegalArgumentException("the sweep must be nonzero and at most a full turn: " + sweep);
        }
        sample(circleCurve(lat, lon, radius, startBearing), 0.0, sweep, Math.max(2, (int) Math.ceil(Math.abs(sweep) / (Math.PI / 8))));
        sink.line(buffer, count, false);
        return count;
    }

    /** Appends the geodesic from the centre to the point at the radius on a bearing ({@code outward}) or back, without its first point. */
    private void radius(double lat, double lon, double bearing, double radius, boolean outward) {
        double[] p = new double[3];
        Curve c = (t, xy) -> {
            Geodesy.direct(lat, lon, bearing, t * radius, p);
            projection.forward(p[0], unwrapNear(p[1], lon), xy);
        };
        double t0 = outward ? 0.0 : 1.0, t1 = outward ? 1.0 : 0.0;
        for (int i = 1; i <= 4; i++) {
            double ta = t0 + (t1 - t0) * (i - 1) / 4, tb = t0 + (t1 - t0) * i / 4;
            double[] pa = new double[2], pb = new double[2];
            c.at(ta, pa);
            c.at(tb, pb);
            subdivide(c, ta, pa, tb, pb, 0);
        }
    }

    /**
     * Samples a sector (a pie slice): the centre, the arc and back to the centre, as a closed ring.
     *
     * @param lat the latitude of the centre in radians
     * @param lon the longitude of the centre in radians
     * @param radius the radius in metres
     * @param startBearing the bearing of the first radius in radians
     * @param sweep the angle swept clockwise in radians, nonzero, at most a full turn
     * @param sink receives the ring
     * @param filled whether the ring goes to {@link Sink#area} (a polygon to fill) or {@link Sink#line} (an outline)
     * @return the number of vertices
     * @throws IllegalArgumentException as {@link #arc}
     */
    public int sector(double lat, double lon, double radius, double startBearing, double sweep, Sink sink, boolean filled) {
        checkRadius(radius);
        if (!(Math.abs(sweep) > 0) || Math.abs(sweep) > 2 * Math.PI) {
            throw new IllegalArgumentException("the sweep must be nonzero and at most a full turn: " + sweep);
        }
        sample(circleCurve(lat, lon, radius, startBearing), 0.0, sweep, Math.max(2, (int) Math.ceil(Math.abs(sweep) / (Math.PI / 8))));
        double[] arc = Arrays.copyOf(buffer, 2 * count);
        int arcCount = count;
        double[] centre = new double[2];
        projection.forward(lat, lon, centre);
        count = 0;
        push(centre[0], centre[1]);
        radius(lat, lon, startBearing, radius, true);
        // the arc after its first point (the end of the first radius)
        for (int i = 1; i < arcCount; i++) {
            push(arc[2 * i], arc[2 * i + 1]);
        }
        // and the second radius back to the centre: its points after the end of the arc, without the centre that starts the ring
        radius(lat, lon, startBearing + sweep, radius, false);
        count -= 1;
        if (filled) {
            sink.area(buffer, count);
        } else {
            sink.line(buffer, count, true);
        }
        return count;
    }

    // ---------------------------------------------------------------- legs and routes

    /**
     * Samples the geodesic from one point to another as an open polyline.
     *
     * @param latA the latitude of the start in radians
     * @param lonA the longitude of the start in radians
     * @param latB the latitude of the end in radians
     * @param lonB the longitude of the end in radians
     * @param sink receives the polyline
     * @return the number of points
     * @throws IllegalArgumentException if the two points are the same or a point is outside the domain of the projection
     */
    public int geodesicLeg(double latA, double lonA, double latB, double lonB, Sink sink) {
        sampleGeodesic(latA, lonA, latB, lonB);
        sink.line(buffer, count, false);
        return count;
    }

    private void sampleGeodesic(double latA, double lonA, double latB, double lonB) {
        double[] ab = new double[3];
        Geodesy.inverse(latA, lonA, latB, lonB, ab);
        if (!(ab[0] > 0)) {
            throw new IllegalArgumentException("the two ends of a leg are the same point");
        }
        GeodesicLine line = new GeodesicLine(latA, lonA, ab[1]);
        double s = ab[0];
        double[] p = new double[3];
        Curve c = (t, xy) -> {
            line.positionContinuous(t * s, p);
            projection.forward(p[0], p[1], xy);
        };
        sample(c, 0.0, 1.0, Math.max(2, (int) Math.ceil(s / 1.0e6)));
    }

    /**
     * Samples the rhumb line (constant bearing) from one point to another as an open polyline.
     *
     * @param latA the latitude of the start in radians
     * @param lonA the longitude of the start in radians
     * @param latB the latitude of the end in radians
     * @param lonB the longitude of the end in radians
     * @param sink receives the polyline
     * @return the number of points
     * @throws IllegalArgumentException if the two points are the same or a point is outside the domain of the projection
     */
    public int rhumbLeg(double latA, double lonA, double latB, double lonB, Sink sink) {
        sampleRhumb(latA, lonA, latB, lonB);
        sink.line(buffer, count, false);
        return count;
    }

    private void sampleRhumb(double latA, double lonA, double latB, double lonB) {
        double[] rb = new double[2];
        Geodesy.rhumbInverse(latA, lonA, latB, lonB, rb);
        if (!(rb[0] > 0)) {
            throw new IllegalArgumentException("the two ends of a leg are the same point");
        }
        double s = rb[0], bearing = rb[1];
        double[] p = new double[2];
        Curve c = (t, xy) -> {
            Geodesy.rhumbDirect(latA, lonA, bearing, t * s, p);
            projection.forward(p[0], p[1], xy);
        };
        sample(c, 0.0, 1.0, 2);
    }

    /**
     * Samples a route of legs as one open polyline.
     *
     * @param latLon the waypoints as {@code latitude, longitude} pairs in radians
     * @param waypoints the number of waypoints, at least 2
     * @param geodesic {@code true} for geodesic legs, {@code false} for rhumb lines
     * @param sink receives the polyline
     * @return the number of points
     * @throws IllegalArgumentException if there are fewer than two waypoints, an array is too short
     *     or two successive waypoints are the same point
     */
    public int route(double[] latLon, int waypoints, boolean geodesic, Sink sink) {
        if (waypoints < 2 || latLon.length < 2 * waypoints) {
            throw new IllegalArgumentException("a route needs at least 2 waypoints, as latitude and longitude pairs: " + waypoints);
        }
        double[] all = new double[0];
        int total = 0;
        for (int i = 0; i + 1 < waypoints; i++) {
            if (geodesic) {
                sampleGeodesic(latLon[2 * i], latLon[2 * i + 1], latLon[2 * i + 2], latLon[2 * i + 3]);
            } else {
                sampleRhumb(latLon[2 * i], latLon[2 * i + 1], latLon[2 * i + 2], latLon[2 * i + 3]);
            }
            int skip = i == 0 ? 0 : 1;
            all = Arrays.copyOf(all, 2 * (total + count - skip));
            System.arraycopy(buffer, 2 * skip, all, 2 * total, 2 * (count - skip));
            total += count - skip;
        }
        sink.line(all, total, false);
        return total;
    }

    // ---------------------------------------------------------------- corridors

    /** The pieces of a corridor: strips along the legs and discs at the waypoints; returns the number of polygons. */
    private void checkCorridor(double[] latLon, int waypoints, double width) {
        if (waypoints < 2 || latLon.length < 2 * waypoints || !(width > 0) || !Double.isFinite(width)) {
            throw new IllegalArgumentException("a corridor needs 2 or more waypoints and a positive width: " + waypoints + ", " + width);
        }
    }

    /**
     * Samples the corridor around a route of geodesic legs as simple polygons for filling: a strip
     * along each leg (its sides are the points at half the width on either side, perpendicular to
     * the leg) and a disc of half the width at every waypoint, which makes the round joins and the
     * round ends. The polygons overlap, which is invisible for an opaque fill and visible for a
     * translucent one (draw the corridor into a layer that is blended once, or use the outline).
     *
     * @param latLon the waypoints as {@code latitude, longitude} pairs in radians
     * @param waypoints the number of waypoints, at least 2
     * @param width the width of the corridor in metres, positive
     * @param sink receives the polygons through {@link Sink#area}
     * @return the number of polygons
     * @throws IllegalArgumentException if the route or the width is invalid
     */
    public int corridorAreas(double[] latLon, int waypoints, double width, Sink sink) {
        checkCorridor(latLon, waypoints, width);
        int polygons = 0;
        double half = 0.5 * width;
        for (int i = 0; i + 1 < waypoints; i++) {
            double[] ab = new double[3];
            Geodesy.inverse(latLon[2 * i], latLon[2 * i + 1], latLon[2 * i + 2], latLon[2 * i + 3], ab);
            if (!(ab[0] > 0)) {
                throw new IllegalArgumentException("two successive waypoints are the same point: " + i);
            }
            Curve left = offsetCurve(latLon[2 * i], latLon[2 * i + 1], ab[1], ab[0], -half), right = offsetCurve(latLon[2 * i], latLon[2 * i + 1], ab[1], ab[0], half);
            sample(left, 0.0, 1.0, Math.max(2, (int) Math.ceil(ab[0] / 1.0e6)));
            double[] l = Arrays.copyOf(buffer, 2 * count);
            int ln = count;
            sample(right, 0.0, 1.0, Math.max(2, (int) Math.ceil(ab[0] / 1.0e6)));
            double[] strip = new double[2 * (ln + count)];
            System.arraycopy(l, 0, strip, 0, 2 * ln);
            for (int k = 0; k < count; k++) {
                strip[2 * (ln + k)] = buffer[2 * (count - 1 - k)];
                strip[2 * (ln + k) + 1] = buffer[2 * (count - 1 - k) + 1];
            }
            sink.area(strip, ln + count);
            polygons++;
        }
        for (int i = 0; i < waypoints; i++) {
            polygons += disc(latLon[2 * i], latLon[2 * i + 1], half, sink) > 0 ? 1 : 0;
        }
        return polygons;
    }

    private Curve offsetCurve(double lat, double lon, double azimuth, double length, double offset) {
        GeodesicLine line = new GeodesicLine(lat, lon, azimuth);
        double[] p = new double[3], q = new double[3];
        return (t, xy) -> {
            line.positionContinuous(t * length, p);
            Geodesy.direct(p[0], p[1], p[2] + Math.PI / 2, offset, q);
            projection.forward(q[0], unwrapNear(q[1], p[1]), xy);
        };
    }

    /**
     * Samples the outline of the corridor around a route of geodesic legs as one closed ring: the
     * left side forward, a round end, the right side back and a round start, with round joins
     * on the outside of the corners and the inside of the corners cut where the two offset
     * lines cross.
     *
     * <p>For a corner sharper than the width allows (a leg shorter than the width at a
     * hairpin) the inside lines may not cross and are joined through the waypoint's offset points
     * instead, which is still a closed ring but not a simple one; {@link #corridorAreas} is the
     * robust form for filling.
     *
     * @param latLon the waypoints as {@code latitude, longitude} pairs in radians
     * @param waypoints the number of waypoints, at least 2
     * @param width the width of the corridor in metres, positive
     * @param sink receives the closed ring through {@link Sink#line}
     * @return the number of points of the ring
     * @throws IllegalArgumentException if the route or the width is invalid
     */
    public int corridorOutline(double[] latLon, int waypoints, double width, Sink sink) {
        checkCorridor(latLon, waypoints, width);
        double half = 0.5 * width;
        double[] side = sideLine(latLon, waypoints, half, -1);        // the left side, forward
        int ln = side.length / 2;
        double[] other = sideLine(latLon, waypoints, half, 1);        // the right side, forward: used backwards
        int rn = other.length / 2;
        double[] ring = new double[0];
        int total = 0;
        double[][] parts = new double[4][];
        int[] counts = new int[4];
        parts[0] = side;
        counts[0] = ln;
        // the round end at the last waypoint: from the left offset to the right offset around the end, an arc of half a turn
        double[] ab = new double[3];
        int last = waypoints - 1;
        Geodesy.inverse(latLon[2 * last - 2], latLon[2 * last - 1], latLon[2 * last], latLon[2 * last + 1], ab);
        double endBearing = ab[2];
        parts[1] = capArc(latLon[2 * last], latLon[2 * last + 1], half, endBearing - Math.PI / 2, Math.PI);
        counts[1] = parts[1].length / 2;
        parts[2] = new double[2 * rn];
        for (int k = 0; k < rn; k++) {
            parts[2][2 * k] = other[2 * (rn - 1 - k)];
            parts[2][2 * k + 1] = other[2 * (rn - 1 - k) + 1];
        }
        counts[2] = rn;
        Geodesy.inverse(latLon[0], latLon[1], latLon[2], latLon[3], ab);
        parts[3] = capArc(latLon[0], latLon[1], half, ab[1] + Math.PI / 2, Math.PI);
        counts[3] = parts[3].length / 2;
        for (int p = 0; p < 4; p++) {
            ring = Arrays.copyOf(ring, 2 * (total + counts[p]));
            System.arraycopy(parts[p], 0, ring, 2 * total, 2 * counts[p]);
            total += counts[p];
        }
        // the caps repeat the end points of the sides: drop the duplicates
        double[] clean = new double[2 * total];
        int m = 0;
        for (int i = 0; i < total; i++) {
            if (m > 0 && Math.abs(ring[2 * i] - clean[2 * (m - 1)]) < 1e-9 && Math.abs(ring[2 * i + 1] - clean[2 * (m - 1) + 1]) < 1e-9) {
                continue;
            }
            clean[2 * m] = ring[2 * i];
            clean[2 * m + 1] = ring[2 * i + 1];
            m++;
        }
        if (m > 1 && Math.abs(clean[0] - clean[2 * (m - 1)]) < 1e-9 && Math.abs(clean[1] - clean[2 * (m - 1) + 1]) < 1e-9) {
            m--;
        }
        sink.line(clean, m, true);
        return m;
    }

    private double[] capArc(double lat, double lon, double radius, double startBearing, double sweep) {
        sample(circleCurve(lat, lon, radius, startBearing), 0.0, sweep, 4);
        return Arrays.copyOf(buffer, 2 * count);
    }

    /** One side of a corridor as a polyline: offset curves of the legs, round joins outside the corners and trimmed insides. */
    private double[] sideLine(double[] latLon, int waypoints, double half, int sign) {
        double[] result = new double[0];
        int total = 0;
        double[] previous = null;
        double previousBearingEnd = 0;
        for (int i = 0; i + 1 < waypoints; i++) {
            double[] ab = new double[3];
            Geodesy.inverse(latLon[2 * i], latLon[2 * i + 1], latLon[2 * i + 2], latLon[2 * i + 3], ab);
            if (!(ab[0] > 0)) {
                throw new IllegalArgumentException("two successive waypoints are the same point: " + i);
            }
            sample(offsetCurve(latLon[2 * i], latLon[2 * i + 1], ab[1], ab[0], sign * half), 0.0, 1.0, Math.max(2, (int) Math.ceil(ab[0] / 1.0e6)));
            double[] leg = Arrays.copyOf(buffer, 2 * count);
            int legCount = count;
            if (previous != null) {
                // the corner at waypoint i: turning towards this side makes it the inside
                double turn = Geodesy.wrapPi(ab[1] - previousBearingEnd);
                boolean inside = sign * turn > 0;     // a right turn (turn > 0) makes the right side (sign = +1) the inside
                if (inside) {
                    double[] cross = intersect(result, total, leg, legCount);
                    if (cross != null) {
                        // keep the part of the previous side up to the crossing and this leg from it
                        int cut = (int) cross[2];
                        total = cut + 1;
                        int from = (int) cross[3];
                        result = Arrays.copyOf(result, 2 * (total + 1 + legCount - from));
                        result[2 * total] = cross[0];
                        result[2 * total + 1] = cross[1];
                        total++;
                        System.arraycopy(leg, 2 * from, result, 2 * total, 2 * (legCount - from));
                        total += legCount - from;
                        previous = leg;
                        previousBearingEnd = ab[2];
                        continue;
                    }
                } else {
                    // the outside: a round join around the waypoint from the end of the previous side to the start of this one
                    double a0 = previousBearingEnd + sign * Math.PI / 2, sweep = Geodesy.wrapPi(ab[1] - previousBearingEnd);
                    double[] join = capArc(latLon[2 * i], latLon[2 * i + 1], half, a0, sweep);
                    result = Arrays.copyOf(result, 2 * (total + join.length / 2));
                    System.arraycopy(join, 0, result, 2 * total, join.length);
                    total += join.length / 2;
                }
            }
            result = Arrays.copyOf(result, 2 * (total + legCount));
            System.arraycopy(leg, 0, result, 2 * total, 2 * legCount);
            total += legCount;
            previous = leg;
            previousBearingEnd = ab[2];
        }
        return Arrays.copyOf(result, 2 * total);
    }

    /** The first crossing of the tail of the first polyline with the head of the second: {x, y, index in the first, index in the second}, or null. */
    private static double[] intersect(double[] a, int an, double[] b, int bn) {
        int aFrom = Math.max(0, an - 40), bTo = Math.min(bn - 1, 40);
        for (int i = aFrom; i + 1 < an; i++) {
            for (int j = 0; j < bTo; j++) {
                double[] x = segmentCross(a[2 * i], a[2 * i + 1], a[2 * i + 2], a[2 * i + 3], b[2 * j], b[2 * j + 1], b[2 * j + 2], b[2 * j + 3]);
                if (x != null) {
                    return new double[] {x[0], x[1], i, j + 1};
                }
            }
        }
        return null;
    }

    private static double[] segmentCross(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double r1 = bx - ax, r2 = by - ay, s1 = dx - cx, s2 = dy - cy;
        double den = r1 * s2 - r2 * s1;
        if (den == 0) {
            return null;
        }
        double t = ((cx - ax) * s2 - (cy - ay) * s1) / den, u = ((cx - ax) * r2 - (cy - ay) * r1) / den;
        if (t < 0 || t > 1 || u < 0 || u > 1) {
            return null;
        }
        return new double[] {ax + t * r1, ay + t * r2};
    }

    // ---------------------------------------------------------------- range rings

    /**
     * Samples a set of range rings about a centre.
     *
     * @param lat the latitude of the centre in radians
     * @param lon the longitude of the centre in radians
     * @param radii the radii in metres, each positive
     * @param sink receives one closed ring per radius
     * @throws IllegalArgumentException if a radius is invalid
     */
    public void rangeRings(double lat, double lon, double[] radii, Sink sink) {
        for (double r : radii) {
            circle(lat, lon, r, sink);
        }
    }

    // ---------------------------------------------------------------- sinks

    /**
     * Makes a sink that adds every line to a {@code LineBatch} with a style; areas are added as
     * closed lines.
     *
     * @param batch the batch; must not be {@code null}; its origin should be near the view
     * @param style the style; must not be {@code null}
     * @return the sink
     */
    public Sink lines(vmath.lines.LineBatch batch, vmath.lines.LineStyle style) {
        return (xy, n, closed) -> {
            double[] xyz = new double[3 * n];
            for (int i = 0; i < n; i++) {
                xyz[3 * i] = xy[2 * i];
                xyz[3 * i + 1] = xy[2 * i + 1];
            }
            try {
                batch.addPolyline(xyz, 0, n, closed, style);
            } catch (IllegalArgumentException degenerate) {
                // fewer than two distinct points at this tolerance: nothing to draw
            }
        };
    }

    /**
     * Makes a sink that adds every line to a {@code LineSet} with a style.
     *
     * @param set the set; must not be {@code null}
     * @param style the style; must not be {@code null}
     * @param handles receives the handle of every polyline added, in order; may be {@code null}
     * @return the sink
     */
    public Sink lines(vmath.lines.LineSet set, vmath.lines.LineStyle style, java.util.function.LongConsumer handles) {
        return (xy, n, closed) -> {
            double[] xyz = new double[3 * n];
            for (int i = 0; i < n; i++) {
                xyz[3 * i] = xy[2 * i];
                xyz[3 * i + 1] = xy[2 * i + 1];
            }
            try {
                long h = set.add(xyz, 0, n, closed, style);
                if (handles != null) {
                    handles.accept(h);
                }
            } catch (IllegalArgumentException degenerate) {
                // fewer than two distinct points at this tolerance
            }
        };
    }

    /**
     * Gives the view the shapes are sampled for.
     *
     * @return the view
     */
    public MapView2d view() {
        return view;
    }
}
