package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.core.ClipSpace;
import vmath.core.Geodetic;
import vmath.core.Mat4d;
import vmath.core.Vec3d;
import vmath.core.Wgs84;

/**
 * Tests of the tile bounding volumes, the horizon culling and the tile selector against brute-force
 * computations: dense sampling of the volumes, rays against the ellipsoid, and pairwise checks of
 * the selected tiles.
 *
 * <p><b>Thread safety.</b> Each test builds its own data; the tests may run in parallel.
 */
class TileCullingTest {

    private static Frustumd looking(Vec3d eye, Vec3d target, double fovY, double aspect) {
        Vec3d up = Wgs84.up(Wgs84.toGeodetic(eye).latitude(), Wgs84.toGeodetic(eye).longitude());
        Vec3d forward = target.sub(eye).normalize();
        if (Math.abs(forward.dot(up)) > 0.999) {
            up = Wgs84.north(Wgs84.toGeodetic(eye).latitude(), Wgs84.toGeodetic(eye).longitude());
        }
        double dist = eye.sub(target).length();
        Mat4d view = Mat4d.lookAt(eye, target, up);
        Mat4d proj = Mat4d.perspective(fovY, aspect, Math.max(1.0, dist * 1e-4), dist * 4 + 2e7, ClipSpace.OPENGL);
        return Frustumd.fromViewProjection(proj.mul(view), DepthRange.NEGATIVE_ONE_TO_ONE);
    }

    @Test
    void theBoxOfATileContainsEveryPointOfItsVolume() {
        Random r = new Random(5);
        int checked = 0;
        for (int i = 0; i < 400; i++) {
            int z = i < 40 ? i % 5 : r.nextInt(16);
            int n = 1 << z;
            // include the polar rows, the antimeridian columns and the equator
            int x = i % 7 == 0 ? n - 1 : r.nextInt(n);
            int y = i % 5 == 0 ? (i % 2 == 0 ? 0 : n - 1) : r.nextInt(n);
            TileId id = new TileId(z, x, y);
            double lo = -r.nextDouble() * 500.0, hi = r.nextDouble() * 4000.0;
            Obbd box = TileBounds.obb(id, lo, hi);
            Sphered ball = TileBounds.sphere(id, lo, hi);
            Aabbd aabb = TileBounds.aabb(id, lo, hi);
            for (int j = 0; j <= 24; j++) {
                double lat = WebMercator.latitudeOfV((id.y() + j / 24.0) / n);
                for (int k = 0; k <= 24; k++) {
                    double lon = id.west() + (id.east() - id.west()) * k / 24.0;
                    for (double h : new double[] {lo, 0.5 * (lo + hi), hi}) {
                        Vec3d p = Wgs84.toEcef(lat, lon, h);
                        assertTrue(box.contains(p), "box of " + id + " at " + j + "," + k);
                        assertTrue(ball.contains(p), "sphere of " + id);
                        assertTrue(aabb.contains(p), "aabb of " + id);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 200_000);
    }

    @Test
    void aBoxIsReasonablyTightForASmallTile() {
        TileId id = TileId.containing(12, Math.toRadians(18.07), Math.toRadians(59.33));
        Obbd box = TileBounds.obb(id, 0.0, 100.0);
        double width = 2 * Math.PI * Wgs84.A * Math.cos(Math.toRadians(59.33)) / 4096;
        assertEquals(width, 2 * box.hx(), 0.02 * width);
        assertTrue(2 * box.hz() < 150.0, "height " + 2 * box.hz());
    }

    @Test
    void theHorizonNeverHidesWhatTheEllipsoidDoesNotCover() {
        Random r = new Random(6);
        int hidden = 0, visible = 0;
        for (int i = 0; i < 4000; i++) {
            double alt = Math.pow(10.0, 1.5 + r.nextDouble() * 6.0);
            Vec3d camera = Wgs84.toEcef(Math.toRadians(r.nextDouble() * 180 - 90), Math.toRadians(r.nextDouble() * 360 - 180), alt);
            HorizonCuller horizon = new HorizonCuller(camera);
            int z = 2 + r.nextInt(11), n = 1 << z;
            TileId id = new TileId(z, r.nextInt(n), r.nextInt(n));
            Sphered ball = TileBounds.sphere(id, -500.0, 9000.0);
            if (!horizon.isHidden(ball)) {
                visible++;
                continue;
            }
            hidden++;
            for (int k = 0; k < 60; k++) {
                Vec3d p = new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().mul(ball.radius() * Math.cbrt(r.nextDouble())).add(ball.center());
                double t = Ellipsoids.rayWgs84(Rayd.through(camera, p), 1.0);
                assertTrue(t < 1.0, "a point of a tile that was culled is in view: tile " + id + " camera altitude " + alt);
            }
        }
        assertTrue(hidden > 300 && visible > 300, "hidden " + hidden + " visible " + visible);
    }

    @Test
    void theHorizonKnowsTheFarSideAndTheGroundUnderTheCamera() {
        Vec3d camera = Wgs84.toEcef(0.0, 0.0, 400_000.0);
        HorizonCuller horizon = new HorizonCuller(camera);
        assertTrue(horizon.isHidden(TileBounds.sphere(TileId.containing(6, Math.PI, 0.0), -100.0, 500.0)));
        assertFalse(horizon.isHidden(TileBounds.sphere(TileId.containing(6, 0.0, 0.0), -100.0, 500.0)));
        assertFalse(new HorizonCuller(Vec3d.ZERO).isHidden(TileBounds.sphere(TileId.containing(6, Math.PI, 0.0), -100.0, 500.0)));
    }

    private static boolean overlap(int z1, int x1, int y1, int z2, int x2, int y2) {
        int z = Math.max(z1, z2);
        long a0x = (long) x1 << (z - z1), a1x = (long) (x1 + 1) << (z - z1), a0y = (long) y1 << (z - z1), a1y = (long) (y1 + 1) << (z - z1);
        long b0x = (long) x2 << (z - z2), b1x = (long) (x2 + 1) << (z - z2), b0y = (long) y2 << (z - z2), b1y = (long) (y2 + 1) << (z - z2);
        return a0x < b1x && b0x < a1x && a0y < b1y && b0y < a1y;
    }

    private static boolean touch(int z1, int x1, int y1, int z2, int x2, int y2) {
        int z = Math.max(z1, z2);
        long w = 1L << z;
        long a0x = (long) x1 << (z - z1), a1x = (long) (x1 + 1) << (z - z1), a0y = (long) y1 << (z - z1), a1y = (long) (y1 + 1) << (z - z1);
        long b0x = (long) x2 << (z - z2), b1x = (long) (x2 + 1) << (z - z2), b0y = (long) y2 << (z - z2), b1y = (long) (y2 + 1) << (z - z2);
        boolean yTouch = a0y <= b1y && b0y <= a1y;
        for (long shift = -w; shift <= w; shift += w) {
            if (a0x <= b1x + shift && b0x + shift <= a1x && yTouch) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theSelectedTilesDoNotOverlapAndNeighboursDifferByAtMostOneLevel() {
        TileSelector selector = new TileSelector(1, 18, -500.0, 9000.0, 32, 4096);
        Random r = new Random(7);
        int maxDiff = 0;
        for (int round = 0; round < 12; round++) {
            Vec3d eye = Wgs84.toEcef(Math.toRadians(r.nextDouble() * 140 - 70), Math.toRadians(r.nextDouble() * 360 - 180), Math.pow(10.0, 2.5 + r.nextDouble() * 4.5));
            Vec3d target = Wgs84.toEcef(Math.toRadians(r.nextDouble() * 140 - 70), Math.toRadians(r.nextDouble() * 360 - 180), 0.0);
            if (round % 3 == 0) {
                Geodetic g = Wgs84.toGeodetic(eye);
                target = Wgs84.toEcef(g.latitude() - 0.0001, g.longitude() + 0.0001, 0.0);
            }
            Frustumd frustum = looking(eye, target, Math.toRadians(60), 16.0 / 9.0);
            int n = selector.select(frustum, eye, Math.toRadians(60), 1080, 8.0, true);
            assertTrue(n > 0 && !selector.overflowed(), "round " + round);
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    assertFalse(overlap(selector.zoom(i), selector.x(i), selector.y(i), selector.zoom(j), selector.x(j), selector.y(j)), "overlap in round " + round);
                    if (touch(selector.zoom(i), selector.x(i), selector.y(i), selector.zoom(j), selector.x(j), selector.y(j))) {
                        maxDiff = Math.max(maxDiff, Math.abs(selector.zoom(i) - selector.zoom(j)));
                        assertTrue(Math.abs(selector.zoom(i) - selector.zoom(j)) <= 1, "round " + round + ": " + selector.zoom(i) + " next to " + selector.zoom(j));
                    }
                }
            }
        }
        assertTrue(maxDiff == 1, "the test must see neighbouring levels, saw " + maxDiff);
    }

    @Test
    void theSelectionIsDeterministicAndFollowsTheCamera() {
        Geodetic place = Geodetic.ofDegrees(59.33, 18.07, 0.0);
        Vec3d ground = Wgs84.toEcef(place);
        Vec3d low = Wgs84.toEcef(Geodetic.ofDegrees(59.33, 18.07, 3.0));
        Vec3d lookAhead = Wgs84.toEcef(Geodetic.ofDegrees(59.3304, 18.0704, 0.0));
        Frustumd view = looking(low, lookAhead, Math.toRadians(60), 16.0 / 9.0);
        TileSelector a = new TileSelector(1, 17, -500.0, 9000.0, 32, 4096);
        TileSelector b = new TileSelector(1, 17, -500.0, 9000.0, 32, 4096);
        int na = a.select(view, low, Math.toRadians(60), 1080, 8.0, true);
        a.select(view, low, Math.toRadians(60), 1080, 8.0, true);
        int nb = b.select(view, low, Math.toRadians(60), 1080, 8.0, true);
        assertEquals(na, nb);
        assertEquals(na, a.count());
        int deepest = 0;
        boolean underCamera = false;
        for (int i = 0; i < na; i++) {
            assertEquals(a.zoom(i), b.zoom(i));
            assertEquals(a.x(i), b.x(i));
            assertEquals(a.y(i), b.y(i));
            deepest = Math.max(deepest, a.zoom(i));
            underCamera |= a.zoom(i) == 17 && new TileId(17, a.x(i), a.y(i)).contains(place.longitude(), place.latitude());
        }
        assertEquals(17, deepest);
        assertTrue(underCamera, "the tile under the camera is at the finest level");
        // a coarser tolerance chooses fewer tiles
        int coarse = a.select(view, low, Math.toRadians(60), 1080, 64.0, true);
        assertTrue(coarse < na, coarse + " against " + na);
        // from orbit the horizon and the frustum remove most of the world
        Vec3d orbit = Wgs84.toEcef(Geodetic.ofDegrees(20.0, 10.0, 8_000_000.0));
        Frustumd far = looking(orbit, ground, Math.toRadians(45), 16.0 / 9.0);
        int withHorizon = a.select(far, orbit, Math.toRadians(45), 1080, 8.0, true);
        int horizonCulled = a.horizonCulled();
        int withoutHorizon = a.select(far, orbit, Math.toRadians(45), 1080, 8.0, false);
        assertTrue(withHorizon <= withoutHorizon);
        assertEquals(0, a.horizonCulled());
        assertTrue(horizonCulled >= 0 && withHorizon > 0);
    }

    @Test
    void aSmallCapacityStopsTheRefinementAndSaysSo() {
        TileSelector s = new TileSelector(1, 18, -500.0, 9000.0, 32, 64);
        Vec3d low = Wgs84.toEcef(Geodetic.ofDegrees(59.33, 18.07, 3.0));
        Frustumd view = looking(low, Wgs84.toEcef(Geodetic.ofDegrees(59.3304, 18.0704, 0.0)), Math.toRadians(60), 16.0 / 9.0);
        int n = s.select(view, low, Math.toRadians(60), 1080, 1.0, true);
        assertTrue(n <= 64);
        assertTrue(s.truncated() || s.overflowed());
    }
}
