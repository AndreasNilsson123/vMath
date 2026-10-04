package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.core.Vec3d;
import vmath.core.Wgs84;

/**
 * Tests of the Web Mercator projection, the tile addresses and the terrain colour codecs.
 *
 * <p><b>Thread safety.</b> Each test builds its own data; the tests may run in parallel.
 */
class TileMathTest {

    @Test
    void theProjectionRoundTripsAndHasTheKnownLimits() {
        assertEquals(85.0511287798066, Math.toDegrees(WebMercator.MAX_LATITUDE), 1e-9);
        assertEquals(0.0, WebMercator.v(WebMercator.MAX_LATITUDE), 1e-12);
        assertEquals(1.0, WebMercator.v(-WebMercator.MAX_LATITUDE), 1e-12);
        assertEquals(0.5, WebMercator.v(0.0), 1e-15);
        assertEquals(0.0, WebMercator.u(-Math.PI), 0.0);
        assertEquals(1.0, WebMercator.u(Math.PI), 0.0);
        assertEquals(20037508.342789244, WebMercator.HALF_WORLD, 1e-6);
        assertEquals(WebMercator.HALF_WORLD, WebMercator.y(WebMercator.MAX_LATITUDE), 1e-3);
        Random r = new Random(1);
        for (int i = 0; i < 1000; i++) {
            double lat = (r.nextDouble() * 2 - 1) * WebMercator.MAX_LATITUDE, lon = (r.nextDouble() * 2 - 1) * Math.PI;
            assertEquals(lat, WebMercator.latitudeOfV(WebMercator.v(lat)), 1e-12);
            assertEquals(lat, WebMercator.latitudeOfY(WebMercator.y(lat)), 1e-12);
            assertEquals(lon, WebMercator.longitudeOfU(WebMercator.u(lon)), 1e-12);
            assertEquals(lon, WebMercator.longitudeOfX(WebMercator.x(lon)), 1e-12);
        }
        // the published ground resolution at zoom 0 on the equator with 256-pixel tiles
        assertEquals(156543.03392804097, WebMercator.metersPerPixel(0.0, 0, 256), 1e-6);
    }

    @Test
    void tilesRoundTripThroughKeysQuadkeysAndTms() {
        TileId stockholm = TileId.containing(5, Math.toRadians(18.07), Math.toRadians(59.33));
        assertEquals(new TileId(5, 17, 9), stockholm);
        assertEquals("12003", stockholm.quadkey());
        assertEquals(stockholm, TileId.fromQuadkey("12003"));
        assertEquals(new TileId(4, 8, 4), stockholm.parent());
        assertEquals(stockholm, stockholm.parent().child(3));
        assertEquals(stockholm, TileId.fromTms(5, 17, stockholm.tmsY()));
        assertEquals(new TileId(0, 0, 0), TileId.fromQuadkey(""));
        Random r = new Random(2);
        for (int i = 0; i < 2000; i++) {
            int z = r.nextInt(TileId.MAX_ZOOM + 1);
            int n = 1 << z;
            TileId t = new TileId(z, r.nextInt(n), r.nextInt(n));
            assertEquals(t, TileId.fromKey(t.key()));
            assertEquals(t, TileId.fromQuadkey(t.quadkey()));
            assertTrue(t.key() > 0);
            if (z > 0) {
                assertEquals(t, t.parent().child(((t.x() & 1)) | ((t.y() & 1) << 1)));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new TileId(3, 8, 0));
        assertThrows(IllegalArgumentException.class, () -> new TileId(30, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TileId.fromQuadkey("124"));
        assertThrows(IllegalStateException.class, () -> new TileId(0, 0, 0).parent());
    }

    @Test
    void tileEdgesTileTheMapAndContainTheirPosition() {
        TileId t = new TileId(3, 5, 2);
        assertEquals(t.east(), new TileId(3, 6, 2).west(), 0.0);
        assertEquals(t.south(), new TileId(3, 5, 3).north(), 1e-15);
        assertEquals(-Math.PI, new TileId(7, 0, 0).west(), 0.0);
        assertEquals(WebMercator.MAX_LATITUDE, new TileId(7, 0, 0).north(), 1e-12);
        assertTrue(t.contains(t.centerLongitude(), t.centerLatitude()));
        assertEquals(t, TileId.containing(3, t.centerLongitude(), t.centerLatitude()));
        // the poles and the antimeridian land in the edge tiles
        assertEquals(new TileId(2, 3, 0), TileId.containing(2, Math.PI, Math.PI / 2));
        assertEquals(new TileId(2, 0, 3), TileId.containing(2, -Math.PI, -Math.PI / 2));
        // the map wraps east-west and ends at the north and south
        assertEquals(new TileId(3, 7, 2), new TileId(3, 0, 2).neighbour(-1, 0));
        assertNull(new TileId(3, 0, 0).neighbour(0, -1));
        assertEquals(new TileId(3, 1, 3), new TileId(3, 0, 2).neighbour(1, 1));
    }

    @Test
    void theCodecsReturnWhatTheyStoreToHalfAStep() {
        byte[] rgb = new byte[3];
        Random r = new Random(3);
        for (TerrainRgb.Encoding enc : TerrainRgb.Encoding.values()) {
            for (int i = 0; i < 2000; i++) {
                double h = Math.max(-9000.0, Math.min(9000.0, r.nextGaussian() * 2000.0));
                TerrainRgb.encode(enc, h, rgb, 0);
                double back = TerrainRgb.decode(enc, rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);
                assertEquals(h, back, 0.5 * enc.step() + 1e-9);
            }
        }
        // the published formulas at known values
        assertEquals(0.0, TerrainRgb.decode(TerrainRgb.Encoding.TERRARIUM, 128, 0, 0), 0.0);
        assertEquals(1.0 / 256.0, TerrainRgb.decode(TerrainRgb.Encoding.TERRARIUM, 128, 0, 1), 1e-15);
        assertEquals(-10000.0, TerrainRgb.decode(TerrainRgb.Encoding.MAPBOX, 0, 0, 0), 0.0);
        assertEquals(0.0, TerrainRgb.decode(TerrainRgb.Encoding.MAPBOX, 1, 134, 160), 1e-9);
        // out of range heights clamp
        TerrainRgb.encode(TerrainRgb.Encoding.TERRARIUM, 1e9, rgb, 0);
        assertEquals(TerrainRgb.Encoding.TERRARIUM.maximum(), TerrainRgb.decode(TerrainRgb.Encoding.TERRARIUM, rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF), 1e-9);
        TerrainRgb.encode(TerrainRgb.Encoding.MAPBOX, Double.NaN, rgb, 0);
        assertEquals(TerrainRgb.Encoding.MAPBOX.minimum(), TerrainRgb.decode(TerrainRgb.Encoding.MAPBOX, rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF), 0.0);
    }

    @Test
    void theGridOperationsInterpolateAndGiveSlopeNormals() {
        // a plane that rises 2 m per cell to the east and 1 m per cell to the north, 3 x 3 nodes
        int w = 3, h = 3;
        float[] g = new float[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                g[j * w + i] = 2f * i + 1f * (h - 1 - j);
            }
        }
        assertEquals(2f * 1 + 1f * 1, TerrainRgb.sample(g, w, h, 0.5, 0.5), 1e-6);
        assertEquals(6f, TerrainRgb.sample(g, w, h, 1.0, 0.0), 1e-6);
        assertEquals(0f, TerrainRgb.sample(g, w, h, -3.0, 9.0), 1e-6);
        float[] mm = new float[2];
        TerrainRgb.minMax(g, g.length, mm);
        assertEquals(0f, mm[0]);
        assertEquals(6f, mm[1]);
        double[] n = new double[3];
        TerrainRgb.normal(g, w, h, 1, 1, 1.0, 1.0, 1.0, n);
        double len = Math.sqrt(4 + 1 + 1);
        assertEquals(-2 / len, n[0], 1e-12);
        assertEquals(-1 / len, n[1], 1e-12);
        assertEquals(1 / len, n[2], 1e-12);
        TerrainRgb.normal(g, w, h, 0, 0, 1.0, 1.0, 1.0, n);
        assertEquals(-2 / len, n[0], 1e-12);
        assertEquals(-1 / len, n[1], 1e-12);
    }

    @Test
    void aRayFindsTheEllipsoidWhereTheAnalyticSolutionPutsIt() {
        // down the polar axis, along the equator, from inside and away from it
        double up = Ellipsoids.rayWgs84(Rayd.of(new Vec3d(0, 0, 2 * Wgs84.B), new Vec3d(0, 0, -1)), Double.POSITIVE_INFINITY);
        assertEquals(Wgs84.B, up, 1e-6);
        double side = Ellipsoids.rayWgs84(Rayd.of(new Vec3d(3 * Wgs84.A, 0, 0), new Vec3d(-1, 0, 0)), Double.POSITIVE_INFINITY);
        assertEquals(2 * Wgs84.A, side, 1e-6);
        assertEquals(0.0, Ellipsoids.rayWgs84(Rayd.of(new Vec3d(1000, 0, 0), new Vec3d(1, 0, 0)), 1e30));
        assertEquals(Double.POSITIVE_INFINITY, Ellipsoids.rayWgs84(Rayd.of(new Vec3d(3 * Wgs84.A, 0, 0), new Vec3d(1, 0, 0)), 1e30));
        assertEquals(Double.POSITIVE_INFINITY, Ellipsoids.rayWgs84(Rayd.of(new Vec3d(3 * Wgs84.A, 0, 0), new Vec3d(-1, 0, 0)), Wgs84.A));
        // a hit point is on the surface: the geodetic height is zero
        Random r = new Random(4);
        for (int i = 0; i < 500; i++) {
            Vec3d from = Wgs84.toEcef(Math.toRadians(r.nextDouble() * 180 - 90), Math.toRadians(r.nextDouble() * 360 - 180), 1e5 + r.nextDouble() * 3e7);
            Vec3d to = Wgs84.toEcef(Math.toRadians(r.nextDouble() * 180 - 90), Math.toRadians(r.nextDouble() * 360 - 180), 0.0);
            Rayd ray = Rayd.through(from, to);
            double t = Ellipsoids.rayWgs84(ray, Double.POSITIVE_INFINITY);
            if (Double.isFinite(t)) {
                assertEquals(0.0, Wgs84.toGeodetic(ray.pointAt(t)).height(), 1e-3);
            }
        }
    }
}
