package vmath.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.camera.OrthoCamerad;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.AzimuthalEquidistant;
import vmath.geo.DepthRange;
import vmath.geo.Geodesy;
import vmath.geo.MapProjection;
import vmath.geo.PolarStereographic;
import vmath.geo.Utm;
import vmath.geo.WebMercatorProjection;
import vmath.geo.Ellipsoid;

/**
 * {@link MapView2d}: the placement of the centre, the scale as a pixel size and as a range, every
 * orientation, the round trip between pixels and positions, the bounds (antimeridian and poles
 * included), the camera and the matrix in every clip space, and the scale bar.
 */
class MapView2dTest {

    private final SplittableRandom rnd = new SplittableRandom(3);

    private double r(double lo, double hi) {
        return lo + (hi - lo) * rnd.nextDouble();
    }

    private static double deg(double d) {
        return Math.toRadians(d);
    }

    private static MapView2d stockholm(MapProjection p) {
        return MapView2d.of(p, deg(59.33), deg(18.07), 1920, 1080).withMetersPerPixel(50.0);
    }

    @Test
    void theCentreIsWhereTheOffsetPutsIt() {
        double[] px = new double[2];
        MapView2d v = stockholm(Utm.projection(33, true));
        v.toScreen(deg(59.33), deg(18.07), px);
        assertEquals(960.0, px[0], 1e-6);
        assertEquals(540.0, px[1], 1e-6);
        MapView2d low = v.withCenterOffset(0.1, 0.25);
        low.toScreen(deg(59.33), deg(18.07), px);
        assertEquals(960.0 + 192.0, px[0], 1e-6);
        assertEquals(540.0 + 270.0, px[1], 1e-6);
        assertEquals(810.0, low.centerPixelY(), 1e-9);
        assertEquals(1152.0, low.centerPixelX(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> v.withCenterOffset(0.6, 0));
        assertThrows(IllegalArgumentException.class, () -> v.withCenterOffset(0, Double.NaN));
    }

    @Test
    void distancesOnTheScreenAreGroundDistancesOverThePixelSize() {
        for (MapProjection p : new MapProjection[] {Utm.projection(33, true), new AzimuthalEquidistant(deg(59.33), deg(18.07)), WebMercatorProjection.INSTANCE}) {
            MapView2d v = stockholm(p);
            double[] px = new double[2], g = new double[3], q = new double[3];
            for (int i = 0; i < 50; i++) {
                double az = r(-Math.PI, Math.PI), dist = r(100, 20000);
                Geodesy.direct(deg(59.33), deg(18.07), az, dist, q);
                v.toScreen(q[0], q[1], px);
                double pixels = Math.hypot(px[0] - 960.0, px[1] - 540.0);
                double expected = dist / v.groundMetersPerPixelAt(q[0], q[1]);
                // the scale along the direction is not the point scale for Mercator's two scales: bound by their spread
                assertEquals(expected, pixels, expected * (p instanceof WebMercatorProjection ? 0.0075 : 2e-3), p.name() + " at " + dist + " m");
            }
        }
    }

    @Test
    void northUpCourseUpHeadingUpAndAnyAngleTurnTheMap() {
        MapView2d north = stockholm(new AzimuthalEquidistant(deg(59.33), deg(18.07)));
        double[] px = new double[2], q = new double[3];
        Geodesy.direct(deg(59.33), deg(18.07), 0.0, 5000.0, q);
        north.toScreen(q[0], q[1], px);
        assertEquals(960.0, px[0], 1e-6, "a point 5 km north is straight above");
        assertEquals(540.0 - 5000.0 / 50.0, px[1], 1e-5);
        Geodesy.direct(deg(59.33), deg(18.07), Math.PI / 2, 5000.0, q);
        north.toScreen(q[0], q[1], px);
        assertEquals(960.0 + 100.0, px[0], 1e-5, "and 5 km east is to the right");
        MapView2d course = north.withOrientation(MapView2d.Orientation.COURSE_UP, Math.PI / 2);   // east is up
        course.toScreen(q[0], q[1], px);
        assertEquals(960.0, px[0], 1e-5, "with the course east, a point to the east is straight above");
        assertEquals(540.0 - 100.0, px[1], 1e-5);
        Geodesy.direct(deg(59.33), deg(18.07), Math.PI, 5000.0, q);
        course.toScreen(q[0], q[1], px);
        assertEquals(960.0 + 100.0, px[0], 1e-5, "and south, a quarter turn clockwise from east, is to the right");
        MapView2d heading = north.withOrientation(MapView2d.Orientation.HEADING_UP, deg(225));
        Geodesy.direct(deg(59.33), deg(18.07), deg(225), 5000.0, q);
        heading.toScreen(q[0], q[1], px);
        assertEquals(960.0, px[0], 1e-5);
        assertEquals(440.0, px[1], 1e-5);
        assertEquals(MapView2d.Orientation.HEADING_UP, heading.orientation());
        assertEquals(deg(225) - 2 * Math.PI, heading.upBearing(), 1e-12, "stored in (-pi, pi]");
        assertEquals(0.0, north.withOrientation(MapView2d.Orientation.NORTH_UP, 1.0).upBearing(), 0, "the bearing is ignored for north up");
        assertEquals(deg(30), north.withOrientation(MapView2d.Orientation.ANGLE, deg(30)).upBearing(), 1e-12);
    }

    @Test
    void theConvergenceIsAccountedForAtTheCentre() {
        // UTM zone 33 at 59.33 N 18.07 E: grid north is east of true north by about 3 degrees x sin(lat)
        MapView2d v = stockholm(Utm.projection(33, true));
        assertTrue(v.gridUp() < 0, "true north up means a grid bearing a little west of grid north: " + v.gridUp());
        double[] px = new double[2], q = new double[3];
        Geodesy.direct(deg(59.33), deg(18.07), 0.0, 5000.0, q);
        v.toScreen(q[0], q[1], px);
        assertEquals(960.0, px[0], 0.2, "true north is up at the centre in spite of the convergence of the grid");
    }

    @Test
    void thePixelsAndThePositionsRoundTripInEveryProjection() {
        for (MapProjection p : new MapProjection[] {Utm.projection(33, true), new AzimuthalEquidistant(deg(59.33), deg(18.07)), WebMercatorProjection.INSTANCE,
                PolarStereographic.ups(true)}) {
            MapView2d v = stockholm(p).withOrientation(MapView2d.Orientation.ANGLE, deg(77)).withCenterOffset(0.05, 0.2).withMetersPerPixel(200.0);
            double[] px = new double[2], ll = new double[2], back = new double[2];
            for (int i = 0; i < 200; i++) {
                double x = r(0, 1920), y = r(0, 1080);
                v.toGeographic(x, y, ll);
                v.toScreen(ll[0], ll[1], back);
                assertEquals(x, back[0], 1e-6, p.name());
                assertEquals(y, back[1], 1e-6, p.name());
            }
            v.projectedToScreen(v.centerX(), v.centerY(), px);
            assertEquals(v.centerPixelX(), px[0], 1e-9);
            assertEquals(v.centerPixelY(), px[1], 1e-9);
            v.screenToProjected(px[0], px[1], back);
            assertEquals(v.centerX(), back[0], 1e-6);
            assertEquals(v.centerY(), back[1], 1e-6);
        }
    }

    @Test
    void aRangeIsTheGroundDistanceToTheTopEdge() {
        MapView2d v = stockholm(new AzimuthalEquidistant(deg(59.33), deg(18.07))).withCenterOffset(0.0, 0.25).withRange(30_000.0);
        assertEquals(30_000.0, v.range(), 1e-9);
        assertEquals(30_000.0 / 810.0, v.metersPerPixel(), 1e-12, "the centre is 810 pixels below the top edge");
        double[] q = new double[3], px = new double[2];
        Geodesy.direct(deg(59.33), deg(18.07), 0.0, 30_000.0, q);
        v.toScreen(q[0], q[1], px);
        assertEquals(0.0, px[1], 1e-4, "the point at the range, ahead, is on the top edge");
        assertThrows(IllegalArgumentException.class, () -> v.withRange(0));
        assertThrows(IllegalArgumentException.class, () -> v.withMetersPerPixel(-1));
    }

    @Test
    void theBoundsContainTheWindowAndHandleTheAntimeridianAndThePoles() {
        double[] b = new double[4], ll = new double[2];
        MapView2d v = stockholm(Utm.projection(33, true)).withMetersPerPixel(100.0).withOrientation(MapView2d.Orientation.ANGLE, deg(33));
        assertFalse(v.bounds(0.0, b));
        for (int i = 0; i < 500; i++) {
            v.toGeographic(r(0, 1920), r(0, 1080), ll);
            assertTrue(ll[0] >= b[0] - 1e-12 && ll[0] <= b[2] + 1e-12, "latitude inside");
            assertTrue(ll[1] >= b[1] - 1e-12 && ll[1] <= b[3] + 1e-12, "longitude inside");
        }
        double[] wide = new double[4];
        v.bounds(100.0, wide);
        assertTrue(wide[0] < b[0] && wide[2] > b[2] && wide[1] < b[1] && wide[3] > b[3], "a margin enlarges the bounds");
        // a view centred on the antimeridian
        MapView2d dateLine = MapView2d.of(WebMercatorProjection.INSTANCE, deg(10), deg(179.95), 1000, 1000).withMetersPerPixel(500.0);
        assertFalse(dateLine.bounds(0.0, b));
        assertTrue(b[1] > b[3], "west is above east when the view crosses the antimeridian: " + Math.toDegrees(b[1]) + ", " + Math.toDegrees(b[3]));
        assertTrue(b[1] > deg(170) && b[3] < deg(-170));
        // a polar view: the bounds reach the pole and all longitudes
        MapView2d polar = MapView2d.of(PolarStereographic.ups(true), deg(89.0), 0.0, 800, 800).withMetersPerPixel(500.0);
        // the pole is 111 km from the centre: inside a window of 400 km
        assertTrue(polar.bounds(0.0, b), "the pole is in the window of the polar view");
        assertEquals(Math.PI / 2, b[2], 0, "the north pole is in the view");
        assertEquals(-Math.PI, b[1], 0);
        assertEquals(Math.PI, b[3], 0);
        // a window that covers the world in Mercator
        MapView2d world = MapView2d.of(WebMercatorProjection.INSTANCE, 0.0, 0.0, 1000, 500).withMetersPerPixel(40_000.0);
        assertTrue(world.bounds(0.0, b), "a window of the whole world has all longitudes: " + Math.toDegrees(b[1]) + " to " + Math.toDegrees(b[3]));
        assertThrows(IllegalArgumentException.class, () -> v.bounds(-1, new double[4]));
        assertThrows(IllegalArgumentException.class, () -> v.bounds(0, new double[3]));
    }

    // ---------------------------------------------------------------- the camera and the matrix

    private static Vec3f ndc(float[] m, double dx, double dy) {
        return Mat4f.fromArray(m, 0).transformProject(new Vec3f((float) dx, (float) dy, 0f));
    }

    @Test
    void theMatrixPutsAPositionAtItsPixelInEveryClipSpace() {
        MapView2d v = stockholm(Utm.projection(33, true)).withOrientation(MapView2d.Orientation.HEADING_UP, deg(40)).withCenterOffset(0.0, 0.2).withMetersPerPixel(80.0);
        double ox = v.centerX() + 1234.0, oy = v.centerY() - 4321.0;   // an origin near the view, not at its centre
        double[] px = new double[2], xy = new double[2], ll = new double[2];
        for (ClipSpace space : ClipSpace.values()) {
            for (boolean reversed : new boolean[] {false, true}) {
                if (reversed && !space.zeroToOne()) {
                    float[] m = new float[16];
                    assertThrows(IllegalArgumentException.class, () -> v.viewProjection(space, true, ox, oy, m));
                    continue;
                }
                float[] m = new float[16];
                v.viewProjection(space, reversed, ox, oy, m);
                for (int i = 0; i < 100; i++) {
                    double sx = r(0, 1920), sy = r(0, 1080);
                    v.screenToProjected(sx, sy, xy);
                    Vec3f n = ndc(m, xy[0] - ox, xy[1] - oy);
                    assertEquals(sx / 1920.0 * 2 - 1, n.x(), 2e-4, space + " x");
                    double ny = 1 - sy / 1080.0 * 2;
                    assertEquals(space.yDown() ? -ny : ny, n.y(), 2e-4, space + " y");
                    // a point on the plane z = 0 is at the middle of the depth range
                    float expectedZ = space.zeroToOne() ? 0.5f : 0.0f;
                    assertEquals(expectedZ, n.z(), 1e-4, space + " depth");
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> v.viewProjection(ClipSpace.OPENGL, 0, 0, new float[15]));
    }

    @Test
    void theCameraIsTheOrthographicCameraOfTheWindow() {
        MapView2d v = stockholm(Utm.projection(33, true)).withMetersPerPixel(80.0).withCenterOffset(-0.1, 0.1);
        OrthoCamerad cam = v.camera(v.centerX(), v.centerY(), DepthRange.ZERO_TO_ONE);
        assertEquals(1920 * v.mapUnitsPerPixel(), cam.width(), 1e-6);
        assertEquals(1080 * v.mapUnitsPerPixel(), cam.height(), 1e-6);
        assertEquals(v.mapUnitsPerPixel(), cam.pixelSize(1080), 1e-9, "the pixel size of the camera is the pixel of the view");
        assertEquals(1.0 / v.mapUnitsPerPixel(), v.pixelsPerMapUnit(), 1e-12);
        var ndc = cam.project(new vmath.core.Vec3d(0, 0, 0));   // the centre of the view
        assertEquals(2 * v.centerPixelX() / 1920 - 1, ndc.x(), 1e-9);
        assertEquals(1 - 2 * v.centerPixelY() / 1080, ndc.y(), 1e-9);
    }

    @Test
    void aFarAwayMapKeepsItsPrecisionBecauseTheOriginIsNearTheView() {
        // the map of a place 6 million metres from the projection's origin, drawn relative to the centre
        MapView2d v = MapView2d.of(WebMercatorProjection.INSTANCE, deg(52), deg(-179.99), 1000, 1000).withMetersPerPixel(1.0);
        assertTrue(Math.abs(v.centerY()) > 6.0e6 && Math.abs(v.centerX()) > 2.0e7);
        float[] m = new float[16];
        v.viewProjection(ClipSpace.OPENGL, v.centerX(), v.centerY(), m);
        double[] xy = new double[2];
        v.screenToProjected(500 + 123.5, 500 - 77.25, xy);
        Vec3f n = ndc(m, xy[0] - v.centerX(), xy[1] - v.centerY());
        double expectedX = 2 * 623.5 / 1000 - 1, expectedY = 1 - 2 * 422.75 / 1000;
        assertEquals(expectedX, n.x(), 2e-6, "a pixel of precision at 20 000 km from the origin");
        assertEquals(expectedY, n.y(), 2e-6);
    }

    // ---------------------------------------------------------------- the scale bar and the checks

    @Test
    void theScaleBarIsTheLongestRoundLengthThatFits() {
        MapView2d v = stockholm(Utm.projection(33, true)).withMetersPerPixel(37.0);
        double[] out = new double[2];
        v.scaleBar(200, out);   // 200 px are 7400 m
        assertEquals(5000.0, out[0], 1e-9);
        assertEquals(5000.0 / 37.0, out[1], 1e-9);
        v.withMetersPerPixel(0.4).scaleBar(150, out);   // 60 m
        assertEquals(50.0, out[0], 1e-9);
        v.withMetersPerPixel(1.0).scaleBar(100, out);   // exactly 100 m
        assertEquals(100.0, out[0], 1e-9);
        v.withMetersPerPixel(1.0).scaleBar(199, out);
        assertEquals(100.0, out[0], 1e-9);
        v.withMetersPerPixel(1.0).scaleBar(200, out);
        assertEquals(200.0, out[0], 1e-9);
        assertThrows(IllegalArgumentException.class, () -> v.scaleBar(0, new double[2]));
        assertThrows(IllegalArgumentException.class, () -> v.scaleBar(10, new double[1]));
    }

    @Test
    void theViewIsImmutableAndChecksItsArguments() {
        MapView2d v = stockholm(Utm.projection(33, true));
        MapView2d w = v.withCenter(deg(60), deg(19));
        assertNotSame(v, w);
        assertEquals(deg(59.33), v.centerLatitude(), 0);
        assertEquals(deg(60), w.centerLatitude(), 0);
        assertEquals(1920, v.withViewport(800, 600).width() + 1120);
        assertEquals(600, v.withViewport(800, 600).height());
        assertTrue(v.contains(0, 0) && v.contains(1919.9, 1079.9));
        assertFalse(v.contains(1920, 0) || v.contains(-0.1, 5));
        assertEquals(v.centerX(), v.withProjection(Utm.projection(33, true)).centerX(), 0);
        assertThrows(IllegalArgumentException.class, () -> v.withCenter(2.0, 0));
        assertThrows(IllegalArgumentException.class, () -> v.withViewport(0, 10));
        assertThrows(NullPointerException.class, () -> MapView2d.of(null, 0, 0, 10, 10));
        assertThrows(NullPointerException.class, () -> v.withOrientation(null, 0));
        assertThrows(IllegalArgumentException.class, () -> v.withOrientation(MapView2d.Orientation.ANGLE, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> v.toScreen(0, 0, new double[1]));
        assertThrows(IllegalArgumentException.class, () -> v.toGeographic(0, 0, new double[1]));
        assertThrows(IllegalArgumentException.class, () -> v.projectedToScreen(0, 0, new double[1]));
        assertThrows(IllegalArgumentException.class, () -> v.screenToProjected(0, 0, new double[1]));
        assertEquals(Ellipsoid.WGS84.a(), 6378137.0);
    }
}
