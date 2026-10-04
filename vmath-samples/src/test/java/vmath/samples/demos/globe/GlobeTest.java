package vmath.samples.demos.globe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.core.Vec3d;
import vmath.core.Wgs84;
import vmath.geo.TerrainRgb;
import vmath.geo.TileId;

/**
 * Tests of the parts of the globe demo that need no window: the planet, the tiles that are made
 * from it, the camera, the cache of tiles, the floating-origin measurement and the options.
 *
 * <p><b>Thread safety.</b> Each test builds its own data; the tests may run in parallel.
 */
class GlobeTest {

    private static float[] heights(TileSource source, TileId id) {
        int n = source.cells() + 1;
        float[] h = new float[n * n];
        TerrainRgb.decode(TerrainRgb.Encoding.TERRARIUM, source.load(id).terrarium(), n * n, h);
        return h;
    }

    @Test
    void thePlanetIsDeterministicSeamlessAndBounded() {
        int land = 0, total = 0;
        double[] rgb = new double[3];
        for (int la = -80; la <= 80; la += 4) {
            for (int lo = -180; lo < 180; lo += 4) {
                double lon = Math.toRadians(lo), lat = Math.toRadians(la);
                double h = Planet.height(lon, lat);
                assertEquals(h, Planet.height(lon, lat), 0.0);
                assertTrue(h >= Planet.MIN_HEIGHT && h <= Planet.MAX_HEIGHT, "height " + h);
                total++;
                if (h > 0) {
                    land++;
                }
                Planet.albedo(lon, lat, h, 0.1, rgb);
                for (double c : rgb) {
                    assertTrue(c >= 0.0 && c <= 1.0, "colour " + c);
                }
            }
        }
        double fraction = (double) land / total;
        assertTrue(fraction > 0.2 && fraction < 0.6, "land fraction " + fraction);
        // the antimeridian and the poles are not seams
        for (int la = -80; la <= 80; la += 10) {
            double lat = Math.toRadians(la);
            assertEquals(Planet.height(-Math.PI, lat), Planet.height(Math.PI, lat), 1e-6);
        }
        assertTrue(Planet.height(0.3, Math.toRadians(88)) <= 0.0);
        assertTrue(Planet.height(2.1, Math.toRadians(-88)) <= 0.0);
    }

    @Test
    void neighbouringTilesShareTheirEdgeHeightsAndAParentAgreesWithItsChild() {
        ProceduralTileSource source = new ProceduralTileSource(16, 16);
        int n = 17;
        float[] west = heights(source, new TileId(6, 20, 25)), east = heights(source, new TileId(6, 21, 25));
        for (int j = 0; j < n; j++) {
            assertEquals(west[j * n + 16], east[j * n], 0.0, "east edge of the first tile, row " + j);
        }
        float[] north = heights(source, new TileId(6, 20, 24)), south = heights(source, new TileId(6, 20, 25));
        for (int i = 0; i < n; i++) {
            assertEquals(north[16 * n + i], south[i], 0.0, "south edge of the first tile, column " + i);
        }
        // across the antimeridian the last column meets the first
        float[] last = heights(source, new TileId(4, 15, 7)), first = heights(source, new TileId(4, 0, 7));
        for (int j = 0; j < n; j++) {
            assertEquals(last[j * n + 16], first[j * n], 0.01, "antimeridian, row " + j);
        }
        // a parent's node is the child's node at twice the index (up to the step of the encoding)
        float[] parent = heights(source, new TileId(5, 10, 12)), child = heights(source, new TileId(6, 20, 24));
        for (int j = 0; j <= 8; j++) {
            for (int i = 0; i <= 8; i++) {
                assertEquals(parent[j * n + i], child[2 * j * n + 2 * i], 0.01, "node " + i + "," + j);
            }
        }
    }

    @Test
    void aTileMeshPlacesEveryVertexOnTheEllipsoidAndHangsASkirt() {
        int cells = 8, n = cells + 1;
        ProceduralTileSource source = new ProceduralTileSource(cells, 16);
        TileId id = new TileId(9, 410, 190);
        float[] h = heights(source, id);
        float[] v = new float[TileMeshes.vertexCount(cells) * TileMeshes.FLOATS];
        double[] ref = new double[3];
        TileMeshes.build(id, h, cells, 16, v, ref);
        assertEquals(Wgs84.toEcef(id.centerLatitude(), id.centerLongitude(), 0.0).x(), ref[0], 0.0);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int o = (j * n + i) * TileMeshes.FLOATS;
                Vec3d expected = Wgs84.toEcef(ProceduralTileSource.latitudeAt(id, (double) j / cells), ProceduralTileSource.longitudeAt(id, (double) i / cells), Math.max(0f, h[j * n + i]));
                double dx = v[o] + ref[0] - expected.x(), dy = v[o + 1] + ref[1] - expected.y(), dz = v[o + 2] + ref[2] - expected.z();
                assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) < 1e-2, "vertex " + i + "," + j);
                double len = Math.sqrt(v[o + 3] * (double) v[o + 3] + v[o + 4] * (double) v[o + 4] + v[o + 5] * (double) v[o + 5]);
                assertEquals(1.0, len, 1e-5);
            }
        }
        // the skirt hangs below the edge vertex, along the ellipsoid normal
        double depth = TileMeshes.skirtDepth(id.zoom(), id.y(), cells);
        for (int e = 0; e < n; e++) {
            int top = e * TileMeshes.FLOATS, skirt = (n * n + e) * TileMeshes.FLOATS;
            double dx = v[top] - v[skirt], dy = v[top + 1] - v[skirt + 1], dz = v[top + 2] - v[skirt + 2];
            assertEquals(depth, Math.sqrt(dx * dx + dy * dy + dz * dz), 1e-3 * depth + 1e-3);
        }
        int[] ix = TileMeshes.indices(cells);
        assertEquals(TileMeshes.indexCount(cells), ix.length);
        for (int i : ix) {
            assertTrue(i >= 0 && i < TileMeshes.vertexCount(cells));
        }
    }

    @Test
    void theSharedEdgeOfTwoTilesCoincidesInSpace() {
        int cells = 8;
        ProceduralTileSource source = new ProceduralTileSource(cells, 16);
        TileId a = new TileId(8, 100, 90), b = new TileId(8, 101, 90);
        float[] va = new float[TileMeshes.vertexCount(cells) * TileMeshes.FLOATS], vb = new float[va.length];
        double[] ra = new double[3], rb = new double[3];
        TileMeshes.build(a, heights(source, a), cells, 16, va, ra);
        TileMeshes.build(b, heights(source, b), cells, 16, vb, rb);
        int n = cells + 1;
        for (int j = 0; j < n; j++) {
            int oa = (j * n + cells) * TileMeshes.FLOATS, ob = (j * n) * TileMeshes.FLOATS;
            for (int k = 0; k < 3; k++) {
                assertEquals(va[oa + k] + ra[k], vb[ob + k] + rb[k], 0.05, "row " + j);
            }
        }
    }

    @Test
    void theScriptedFlightIsRepeatableAndDescendsToTheGround() {
        double[] summit = GlobeCamera.findSummit();
        assertTrue(summit[2] > 0.0 && summit[2] <= 1500.0);
        GlobeCamera a = new GlobeCamera(), b = new GlobeCamera();
        double previous = Double.MAX_VALUE;
        for (int f = 0; f <= 600; f += 20) {
            a.scripted(f / 60.0, 10.0, summit);
            b.scripted(f / 60.0, 10.0, summit);
            assertEquals(a.position().x(), b.position().x(), 0.0);
            assertEquals(a.position().z(), b.position().z(), 0.0);
            assertTrue(a.altitude() <= previous + 1e-6);
            previous = a.altitude();
            assertTrue(a.near() < a.far());
            assertEquals(1.0, a.forward().length(), 1e-12);
        }
        assertEquals(summit[2] + GlobeCamera.END_ALTITUDE, a.altitude(), 1e-3);
        assertEquals(GlobeCamera.END_ALTITUDE, a.aboveGround(), 1e-3);
        a.scripted(0.0, 10.0, summit);
        assertEquals(summit[2] + GlobeCamera.START_ALTITUDE, a.altitude(), 1.0);
        // a point 10,000 km ahead of the camera, which looks down from orbit, is in its frustum
        assertTrue(a.frustum(16.0 / 9.0).contains(a.position().add(a.forward().mul(1.0e7))));
    }

    @Test
    void aFloatingOriginRemovesTheErrorNearTheCameraButNotFarAway() {
        double[] near = Jitter.maxError(100.0);
        assertTrue(near[0] > 0.05, "naive " + near[0]);
        assertTrue(near[1] < 0.001, "rebased " + near[1]);
        double[] mid = Jitter.maxError(10_000.0);
        assertTrue(mid[1] < mid[0] / 10.0);
    }

    @Test
    void theCachePlacesEvictsTheLeastRecentlyUsedAndKeepsPinnedTiles() {
        TileCache cache = new TileCache(new ProceduralTileSource(4, 16), 4, 2);
        try {
            int[] slots = new int[6];
            TileId[] ids = new TileId[6];
            for (int i = 0; i < 6; i++) {
                ids[i] = new TileId(5, i, 3);
            }
            for (int i = 0; i < 4; i++) {
                TileCache.Loaded t = cache.loadNow(ids[i]);
                slots[i] = cache.place(t, i);
                assertTrue(slots[i] >= 0);
                assertEquals(slots[i], cache.slotOf(ids[i].key()));
            }
            assertEquals(4, cache.resident());
            cache.pin(slots[0]);
            cache.touch(slots[3], 10);
            // frame 10: slots used in frames 9 and 10 are safe, so the oldest unpinned (tile 1) goes
            int s = cache.place(cache.loadNow(ids[4]), 10);
            assertEquals(slots[1], s);
            assertEquals(-1, cache.slotOf(ids[1].key()));
            assertEquals(1, cache.evicted());
            assertTrue(cache.slotOf(ids[0].key()) >= 0, "the pinned tile stays");
            // everything used in this frame: nothing can be evicted and the tile is dropped
            for (int i : new int[] {0, 2, 3, 4}) {
                cache.touch(cache.slotOf(ids[i].key()), 20);
            }
            assertEquals(-1, cache.place(cache.loadNow(ids[5]), 20));
            assertEquals(1, cache.dropped());
            assertEquals(4, cache.resident());
        } finally {
            cache.close();
        }
    }

    @Test
    void theWorkersLoadRequestedTilesOnce() {
        TileCache cache = new TileCache(new ProceduralTileSource(4, 16), 8, 2);
        try {
            TileId id = new TileId(3, 2, 2);
            assertTrue(cache.request(id));
            assertFalse(cache.request(id), "already running");
            TileCache.Loaded t = cache.take();
            assertEquals(id, t.id());
            assertEquals(0, cache.inFlight());
            assertEquals(TileMeshes.vertexCount(4) * TileMeshes.FLOATS, t.vertices().length);
            assertTrue(t.maxHeight() >= t.minHeight());
            int slot = cache.place(t, 1);
            assertFalse(cache.request(id), "resident");
            assertEquals(slot, cache.slotOf(id.key()));
        } finally {
            cache.close();
        }
    }

    @Test
    void theOptionsHaveDefaultsAndRejectWhatIsWrong() {
        GlobeOptions o = GlobeOptions.parse(List.of());
        assertEquals(17, o.maxZoom());
        assertEquals(2, o.minZoom());
        assertFalse(o.naive());
        GlobeOptions p = GlobeOptions.parse(List.of("--max-zoom", "9", "--naive", "--pixels", "3.5", "--verify"));
        assertEquals(9, p.maxZoom());
        assertTrue(p.naive() && p.verify());
        assertEquals(3.5, p.pixels(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> GlobeOptions.parse(List.of("--bogus")));
        assertThrows(IllegalArgumentException.class, () -> GlobeOptions.parse(List.of("--max-zoom")));
        assertThrows(IllegalArgumentException.class, () -> GlobeOptions.parse(List.of("--max-zoom", "1")));
        assertThrows(IllegalArgumentException.class, () -> GlobeOptions.parse(List.of("--resident", "5000")));
    }
}
