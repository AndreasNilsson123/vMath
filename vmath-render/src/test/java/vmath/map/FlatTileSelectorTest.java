package vmath.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.geo.MapProjection;
import vmath.geo.Polygons;
import vmath.geo.TileId;
import vmath.geo.Utm;
import vmath.geo.WebMercator;
import vmath.geo.WebMercatorProjection;

/**
 * {@link TileGrid} and {@link FlatTileSelector}: the geometry of the pyramids (XYZ, TMS and a UTM
 * grid), the zoom that a view needs with slack and hysteresis, the cover of a view turned to any angle
 * against an independent polygon-overlap oracle, the world copies, the budget and the order.
 */
class FlatTileSelectorTest {

    private final SplittableRandom rnd = new SplittableRandom(21);

    private static double deg(double d) {
        return Math.toRadians(d);
    }

    private static MapView2d view(MapProjection p, double latDeg, double lonDeg, double mpp, int w, int h) {
        return MapView2d.of(p, deg(latDeg), deg(lonDeg), w, h).withMetersPerPixel(mpp);
    }

    // ---------------------------------------------------------------- the grids

    @Test
    void theWebGridMatchesTheTileIdsOfTheLibrary() {
        TileGrid xyz = TileGrid.webMercator(256, TileGrid.Scheme.XYZ), tms = TileGrid.webMercator(256, TileGrid.Scheme.TMS);
        double[] b = new double[4], t = new double[4];
        for (int z = 0; z <= 12; z++) {
            for (int i = 0; i < 20; i++) {
                int x = rnd.nextInt(1 << z), y = rnd.nextInt(1 << z);
                xyz.tileBounds(z, x, y, b);
                TileId id = new TileId(z, x, y);
                assertEquals(WebMercator.x(id.west()), b[0], 1e-6, "west of " + id);
                assertEquals(WebMercator.x(id.east()), b[2], 1e-6);
                assertEquals(WebMercator.y(id.north()), b[3], 1e-3, "north of " + id);
                assertEquals(WebMercator.y(id.south()), b[1], 1e-3);
                tms.tileBounds(z, x, (1 << z) - 1 - y, t);
                for (int k = 0; k < 4; k++) {
                    assertEquals(b[k], t[k], 1e-6, "TMS row " + ((1 << z) - 1 - y) + " is XYZ row " + y);
                }
                assertEquals(x, xyz.columnOf(z, 0.5 * (b[0] + b[2])));
                assertEquals(y, xyz.rowOf(z, 0.5 * (b[1] + b[3])));
                assertEquals((1 << z) - 1 - y, tms.rowOf(z, 0.5 * (b[1] + b[3])));
            }
            assertEquals(1L << z, xyz.columns(z));
            assertEquals(1L << z, xyz.rows(z));
            assertEquals(xyz.tileSide(z) / 256.0, xyz.resolution(z), 0);
        }
        assertEquals(156543.03392804097, xyz.resolution(0), 1e-6, "the well-known 156543 m per pixel at zoom 0");
        assertEquals(5.0, xyz.zoomFor(xyz.resolution(5)), 1e-12);
        assertEquals(5.5, xyz.zoomFor(xyz.resolution(5) / Math.sqrt(2)), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> xyz.tileSide(30));
        assertThrows(IllegalArgumentException.class, () -> xyz.zoomFor(0));
        assertThrows(IllegalArgumentException.class, () -> xyz.tileBounds(1, 0, 0, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> TileGrid.webMercator(0, TileGrid.Scheme.XYZ));
        assertThrows(IllegalArgumentException.class, () -> TileGrid.of(WebMercatorProjection.INSTANCE, 1, 0, 1, 1, 256, TileGrid.Scheme.XYZ, false));
    }

    @Test
    void aGridOverARectangleThatIsNotSquareContinuesItsRows() {
        TileGrid g = TileGrid.of(Utm.projection(33, true), 100_000, 0, 900_000, 9_300_000, 256, TileGrid.Scheme.XYZ, false);
        assertEquals(2, g.columns(1));
        assertEquals(Math.ceil(9_300_000.0 / 400_000), g.rows(1), 0, "rows of 400 km");
        double[] b = new double[4];
        g.tileBounds(2, 3, 5, b);
        assertEquals(100_000 + 3 * 200_000, b[0], 1e-6);
        assertEquals(9_300_000 - 5 * 200_000, b[3], 1e-6, "row 0 is the northern one and the grid starts at the top");
        assertEquals(b[3] - 200_000, b[1], 1e-6);
    }

    // ---------------------------------------------------------------- zoom, slack and hysteresis

    @Test
    void theZoomIsTheOneWhoseTilePixelIsNotLargerThanAScreenPixelWithTheSlack() {
        TileGrid grid = TileGrid.webMercator(256, TileGrid.Scheme.XYZ);
        for (double slack : new double[] {0.0, 0.25}) {
            FlatTileSelector sel = new FlatTileSelector(grid, 0, 22, 4096, slack, 0.0, 0.0);
            for (int i = 0; i < 100; i++) {
                double upp = grid.resolution(rnd.nextInt(18)) * Math.pow(2, rnd.nextDouble() * 0.98);   // between two resolutions
                MapView2d v = MapView2d.of(WebMercatorProjection.INSTANCE, 0.0, 0.0, 400, 300).withMetersPerPixel(upp);
                // the pixel size in map units differs from metres by the scale at the centre (about 1 at the equator)
                sel.select(v);
                double tilePixel = grid.resolution(sel.zoom());
                double screenPixel = v.mapUnitsPerPixel();
                assertTrue(tilePixel <= screenPixel * Math.pow(2, slack) * (1 + 1e-9), "the tile pixel is not larger than the screen pixel beyond the slack: " + tilePixel + " vs " + screenPixel);
                assertTrue(tilePixel > screenPixel * Math.pow(2, slack) / 2 * (1 - 1e-9) || sel.zoom() == 0, "and is the coarsest that satisfies it");
            }
        }
    }

    @Test
    void hysteresisKeepsTheZoomUntilTheViewMovesBeyondTheBand() {
        TileGrid grid = TileGrid.webMercator(256, TileGrid.Scheme.XYZ);
        FlatTileSelector sel = new FlatTileSelector(grid, 0, 22, 4096, 0.0, 0.3, 0.0);
        MapView2d v = MapView2d.of(WebMercatorProjection.INSTANCE, 0.0, 0.0, 400, 300);
        // start at exact zoom 6.5 (zoom 7 wanted), then wobble around the boundary between 6 and 7
        sel.select(v.withMetersPerPixel(grid.resolution(6) / Math.pow(2, 0.5) / 1.0033));
        assertEquals(7, sel.zoom());
        int changes = 0, last = sel.zoom();
        for (double z : new double[] {6.9, 6.1, 5.9, 6.05, 5.8, 5.75, 5.6, 5.95, 6.2, 6.5, 6.8}) {
            sel.select(v.withMetersPerPixel(grid.resolution(0) / Math.pow(2, z) / 1.0033));
            if (sel.zoom() != last) {
                changes++;
                last = sel.zoom();
            }
        }
        // 7 holds down to an exact zoom of 5.7 (band 6 to 7, 0.3 of hysteresis), then the zoom is 6 and holds up to 7.3
        assertTrue(changes <= 2, "no flicker between neighbouring zooms: " + changes + " changes");
        FlatTileSelector plain = new FlatTileSelector(grid, 0, 22, 4096, 0.0, 0.0, 0.0);
        int plainChanges = 0;
        last = -1;
        for (double z : new double[] {6.9, 7.1, 6.9, 7.1, 6.9, 7.1}) {
            plain.select(v.withMetersPerPixel(grid.resolution(0) / Math.pow(2, z) / 1.0033));
            if (plain.zoom() != last) {
                plainChanges++;
                last = plain.zoom();
            }
        }
        assertEquals(6, plainChanges, "without hysteresis the zoom follows every wobble");
        FlatTileSelector held = new FlatTileSelector(grid, 0, 22, 4096, 0.0, 0.3, 0.0);
        int heldChanges = 0;
        last = -1;
        for (double z : new double[] {6.9, 7.1, 6.9, 7.1, 6.9, 7.1}) {
            held.select(v.withMetersPerPixel(grid.resolution(0) / Math.pow(2, z) / 1.0033));
            if (held.zoom() != last) {
                heldChanges++;
                last = held.zoom();
            }
        }
        assertEquals(1, heldChanges, "with it the zoom stays where it was chosen");
    }

    // ---------------------------------------------------------------- the cover

    /** The oracle: every tile of the zoom whose rectangle overlaps the window polygon with a positive area, by convex polygon clipping. */
    private java.util.Map<String, Double> oracle(MapView2d v, TileGrid grid, int z, double overscan) {
        double[] p = new double[2];
        double w = v.width(), h = v.height();
        double[][] px = {{-overscan, -overscan}, {w + overscan, -overscan}, {w + overscan, h + overscan}, {-overscan, h + overscan}};
        float[] window = new float[8];
        double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            v.screenToProjected(px[i][0], px[i][1], p);
            window[2 * i] = (float) (p[0] - v.centerX());
            window[2 * i + 1] = (float) (p[1] - v.centerY());
            x0 = Math.min(x0, p[0]);
            x1 = Math.max(x1, p[0]);
            y0 = Math.min(y0, p[1]);
            y1 = Math.max(y1, p[1]);
        }
        if (Polygons.signedArea(window, 4) < 0) {
            float[] r = new float[8];
            for (int i = 0; i < 4; i++) {
                r[2 * i] = window[2 * (3 - i)];
                r[2 * i + 1] = window[2 * (3 - i) + 1];
            }
            window = r;
        }
        java.util.Map<String, Double> tiles = new java.util.HashMap<>();
        double[] b = new double[4];
        long c0 = grid.columnOf(z, x0) - 1, c1 = grid.columnOf(z, x1) + 1;
        long r0 = Math.max(0, Math.min(grid.rowOf(z, y0), grid.rowOf(z, y1)) - 1), r1 = Math.min(grid.rows(z) - 1, Math.max(grid.rowOf(z, y0), grid.rowOf(z, y1)) + 1);
        float[] clipped = new float[64];
        for (long r = r0; r <= r1; r++) {
            for (long c = c0; c <= c1; c++) {
                if (!grid.wrapX() && (c < 0 || c >= grid.columns(z))) {
                    continue;
                }
                grid.tileBounds(z, c, r, b);
                float[] rect = {(float) (b[0] - v.centerX()), (float) (b[1] - v.centerY()), (float) (b[2] - v.centerX()), (float) (b[1] - v.centerY()), (float) (b[2] - v.centerX()),
                        (float) (b[3] - v.centerY()), (float) (b[0] - v.centerX()), (float) (b[3] - v.centerY())};
                int n = Polygons.clipConvex(rect, 4, window, 4, clipped);
                double fraction = n >= 3 ? Math.abs(Polygons.signedArea(clipped, n)) / ((b[2] - b[0]) * (b[2] - b[0])) : 0.0;
                long cw = grid.wrapX() ? Math.floorMod(c, grid.columns(z)) : c;
                int world = grid.wrapX() ? (int) Math.floorDiv(c, grid.columns(z)) : 0;
                tiles.put(cw + "/" + r + "/" + world, fraction);
            }
        }
        return tiles;
    }

    @Test
    void theCoverIsExactlyTheTilesThatTouchTheWindowForAnyAngle() {
        TileGrid grid = TileGrid.webMercator(256, TileGrid.Scheme.XYZ);
        for (int i = 0; i < 60; i++) {
            double lat = rnd.nextDouble() * 120 - 60, lon = rnd.nextDouble() * 360 - 180;
            MapView2d v = MapView2d.of(WebMercatorProjection.INSTANCE, deg(lat), deg(lon), 400 + rnd.nextInt(800), 300 + rnd.nextInt(600))
                    .withMetersPerPixel(grid.resolution(3 + rnd.nextInt(12)) * Math.pow(2, rnd.nextDouble()) * 0.99)
                    .withOrientation(MapView2d.Orientation.ANGLE, rnd.nextDouble() * 2 * Math.PI).withCenterOffset(rnd.nextDouble() * 0.4 - 0.2, rnd.nextDouble() * 0.4 - 0.2);
            double overscan = rnd.nextInt(3) * 40.0;
            FlatTileSelector sel = new FlatTileSelector(grid, 0, 22, 20000, 0.0, 0.0, overscan);
            int n = sel.select(v);
            Set<String> got = new HashSet<>();
            for (int k = 0; k < n; k++) {
                assertTrue(got.add(sel.x(k) + "/" + sel.y(k) + "/" + sel.world(k)), "no tile twice");
            }
            assertFalse(sel.truncated());
            assertCover(oracle(v, grid, sel.zoom(), overscan), got, "view " + i + " at zoom " + sel.zoom() + " turned " + Math.toDegrees(v.upBearing()) + " degrees");
        }
    }

    @Test
    void aTurnedViewNeedsMoreTilesAndTheNearestComeFirst() {
        TileGrid grid = TileGrid.webMercator(256, TileGrid.Scheme.XYZ);
        MapView2d north = view(WebMercatorProjection.INSTANCE, 48, 11, 150.0, 1280, 720);
        FlatTileSelector sel = new FlatTileSelector(grid, 0, 22, 2000, 0.0, 0.0, 0.0);
        int straight = sel.select(north);
        int turned = sel.select(north.withOrientation(MapView2d.Orientation.ANGLE, deg(45)));
        assertTrue(turned >= straight - 2 && turned <= 2 * straight, "a window turned 45 degrees covers about the same area: " + straight + " against " + turned);
        double[] b = new double[4];
        double previous = -1;
        sel.select(north);
        for (int i = 0; i < sel.count(); i++) {
            grid.tileBounds(sel.zoom(), sel.x(i), sel.y(i), b);
            double d = Math.hypot(0.5 * (b[0] + b[2]) - north.centerX(), 0.5 * (b[1] + b[3]) - north.centerY());
            assertTrue(d >= previous - 1e-6, "nearest first");
            previous = d;
        }
        // the tile that holds the centre is the first
        grid.tileBounds(sel.zoom(), sel.x(0), sel.y(0), b);
        assertTrue(north.centerX() >= b[0] && north.centerX() <= b[2] && north.centerY() >= b[1] && north.centerY() <= b[3]);
        TileId id = sel.tileId(0);
        assertEquals(sel.zoom(), id.zoom());
        assertTrue(id.contains(deg(11), deg(48)));
    }

    @Test
    void aViewAcrossTheAntimeridianSeesTilesOfTheNextWorld() {
        TileGrid grid = TileGrid.webMercator(256, TileGrid.Scheme.XYZ);
        MapView2d v = view(WebMercatorProjection.INSTANCE, 10, 179.9, 400.0, 1000, 600);
        FlatTileSelector sel = new FlatTileSelector(grid, 0, 22, 500, 0.0, 0.0, 0.0);
        int n = sel.select(v);
        Set<Integer> worlds = new HashSet<>();
        long maxColumn = 0, minColumn = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            worlds.add(sel.world(i));
            maxColumn = Math.max(maxColumn, sel.x(i));
            minColumn = Math.min(minColumn, sel.x(i));
            assertTrue(sel.x(i) >= 0 && sel.x(i) < grid.columns(sel.zoom()), "the column is taken modulo the world");
        }
        assertEquals(Set.of(0, 1), worlds, "tiles of this world and of the next one to the east");
        assertEquals(0, minColumn, "the first column of the next world");
        assertEquals(grid.columns(sel.zoom()) - 1, maxColumn);
    }

    @Test
    void theBudgetLowersTheZoomAndAtTheCoarsestDropsTheFarthest() {
        TileGrid grid = TileGrid.webMercator(256, TileGrid.Scheme.XYZ);
        MapView2d v = view(WebMercatorProjection.INSTANCE, 0, 0, 1000.0, 1920, 1080);
        FlatTileSelector wide = new FlatTileSelector(grid, 0, 22, 100000, 0.0, 0.0, 0.0);
        int full = wide.select(v);
        assertTrue(full > 30);
        FlatTileSelector tight = new FlatTileSelector(grid, 0, 22, 12, 0.0, 0.0, 0.0);
        int n = tight.select(v);
        assertTrue(n <= 12 && !tight.truncated(), "it fits at a coarser zoom: " + n);
        assertTrue(tight.zoom() < wide.zoom());
        assertEquals(wide.zoom() - tight.zoom(), tight.budgetReductions());
        // the coarsest zoom allowed leaves no room to go coarser: the tiles are dropped
        FlatTileSelector stuck = new FlatTileSelector(grid, wide.zoom(), wide.zoom(), 10, 0.0, 0.0, 0.0);
        assertEquals(10, stuck.select(v));
        assertTrue(stuck.truncated());
        assertEquals(0, stuck.budgetReductions());
        // the ten kept are the ten nearest
        double[] b = new double[4];
        double worstKept = 0;
        for (int i = 0; i < 10; i++) {
            grid.tileBounds(stuck.zoom(), stuck.x(i), stuck.y(i), b);
            worstKept = Math.max(worstKept, Math.hypot(0.5 * (b[0] + b[2]) - v.centerX(), 0.5 * (b[1] + b[3]) - v.centerY()));
        }
        for (int i = 0; i < wide.count(); i++) {
            grid.tileBounds(wide.zoom(), wide.x(i), wide.y(i), b);
            double d = Math.hypot(0.5 * (b[0] + b[2]) - v.centerX(), 0.5 * (b[1] + b[3]) - v.centerY());
            if (i >= 10) {
                assertTrue(d >= worstKept - 1e-6, "every dropped tile is at least as far as the farthest kept");
            }
        }
    }

    @Test
    void otherGridsAndTheTmsSchemeAreCoveredToo() {
        TileGrid utm = TileGrid.of(Utm.projection(33, true), 166_000, 0, 834_000, 9_330_000, 256, TileGrid.Scheme.TMS, false);
        MapView2d v = MapView2d.of(Utm.projection(33, true), deg(59.33), deg(18.07), 1000, 800).withMetersPerPixel(40.0);
        FlatTileSelector sel = new FlatTileSelector(utm, 0, 18, 500, 0.0, 0.0, 0.0);
        int n = sel.select(v);
        assertTrue(n > 0);
        assertCover(oracle(v, utm, sel.zoom(), 0.0), toSet(sel, n), "UTM grid");
        assertThrows(IllegalStateException.class, () -> sel.tileId(0), "not a Web Mercator grid");
        TileGrid tms = TileGrid.webMercator(256, TileGrid.Scheme.TMS);
        FlatTileSelector tmsSel = new FlatTileSelector(tms, 0, 18, 500, 0.0, 0.0, 0.0);
        MapView2d w = view(WebMercatorProjection.INSTANCE, 40, -100, 500.0, 800, 600);
        tmsSel.select(w);
        assertCover(oracle(w, tms, tmsSel.zoom(), 0.0), toSet(tmsSel, tmsSel.count()), "TMS grid");
        assertThrows(IllegalStateException.class, () -> tmsSel.tileId(0));
        assertThrows(IndexOutOfBoundsException.class, () -> tmsSel.x(tmsSel.count()));
    }

    /** Every tile that the window covers by a visible part is selected, and a selected tile that the oracle does not know as covered is at most a sliver. */
    private static void assertCover(java.util.Map<String, Double> oracle, Set<String> got, String what) {
        for (java.util.Map.Entry<String, Double> e : oracle.entrySet()) {
            if (e.getValue() > 1e-4) {
                assertTrue(got.contains(e.getKey()), what + ": the tile " + e.getKey() + " is covered by " + e.getValue() + " of its area and was not selected");
            }
        }
        for (String t : got) {
            Double f = oracle.get(t);
            assertTrue(f != null, what + ": a tile that is not a candidate of the oracle: " + t);
        }
        int core = 0;
        for (double f : oracle.values()) {
            core += f > 1e-4 ? 1 : 0;
        }
        assertTrue(got.size() >= core && got.size() <= core + 8, what + ": " + got.size() + " selected, " + core + " clearly covered");
    }

    private static Set<String> toSet(FlatTileSelector sel, int n) {
        Set<String> s = new HashSet<>();
        for (int i = 0; i < n; i++) {
            s.add(sel.x(i) + "/" + sel.y(i) + "/" + sel.world(i));
        }
        return s;
    }

    @Test
    void theArgumentsAreChecked() {
        TileGrid g = TileGrid.webMercator(256, TileGrid.Scheme.XYZ);
        assertThrows(NullPointerException.class, () -> new FlatTileSelector(null, 0, 1, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FlatTileSelector(g, 5, 4, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FlatTileSelector(g, 0, 30, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FlatTileSelector(g, 0, 4, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FlatTileSelector(g, 0, 4, 1, 1.0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FlatTileSelector(g, 0, 4, 1, 0, -0.1, 0));
        assertThrows(IllegalArgumentException.class, () -> new FlatTileSelector(g, 0, 4, 1, 0, 0, -1));
    }
}
