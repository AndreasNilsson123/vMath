package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Quatf;
import vmath.core.Rnd;

class SdfsTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    private float coord(double range) {
        return (float) ((rng.nextDouble() * 2 - 1) * range);
    }

    // ------------------------------------------------------------ the primitives against independent references (double)

    private static double boxRef(double px, double py, double pz, double hx, double hy, double hz) {
        double qx = Math.abs(px), qy = Math.abs(py), qz = Math.abs(pz);
        if (qx <= hx && qy <= hy && qz <= hz) { // inside: the distance to the nearest face
            return -Math.min(hx - qx, Math.min(hy - qy, hz - qz));
        }
        double cx = Math.min(qx, hx), cy = Math.min(qy, hy), cz = Math.min(qz, hz); // outside: the distance to the clamped point
        return Math.sqrt((qx - cx) * (qx - cx) + (qy - cy) * (qy - cy) + (qz - cz) * (qz - cz));
    }

    private static double segmentRef(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz) {
        double lo = 0, hi = 1; // the distance to a point of the segment is convex in the parameter: ternary search
        for (int i = 0; i < 100; i++) {
            double m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
            if (dist(px, py, pz, ax + (bx - ax) * m1, ay + (by - ay) * m1, az + (bz - az) * m1) < dist(px, py, pz, ax + (bx - ax) * m2, ay + (by - ay) * m2, az + (bz - az) * m2)) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
        double t = (lo + hi) / 2;
        return dist(px, py, pz, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t);
    }

    private static double dist(double x, double y, double z, double u, double v, double w) {
        return Math.sqrt((x - u) * (x - u) + (y - v) * (y - v) + (z - w) * (z - w));
    }

    @Test
    void theExactPrimitivesMatchIndependentReferences() {
        Sdf sphere = Sdfs.sphere(1, -2, 0.5f, 1.5f);
        Sdf box = Sdfs.box(0.5f, 0, -1, 1, 0.5f, 2);
        Sdf round = Sdfs.roundBox(0.5f, 0, -1, 1, 0.5f, 2, 0.3f);
        Sdf capsule = Sdfs.capsule(-1, 0, 0, 1, 1, 2, 0.4f);
        Sdf plane = Sdfs.plane(2, 0, 0, 1.5f); // the normal (1, 0, 0) after normalising: x <= 1.5
        for (int i = 0; i < 20000; i++) {
            float x = coord(5), y = coord(5), z = coord(5);
            assertEquals(dist(x, y, z, 1, -2, 0.5) - 1.5, sphere.distance(x, y, z), 2e-5);
            assertEquals(boxRef(x - 0.5, y, z + 1, 1, 0.5, 2), box.distance(x, y, z), 2e-5);
            assertEquals(boxRef(x - 0.5, y, z + 1, 1, 0.5, 2) - 0.3, round.distance(x, y, z), 2e-5);
            assertEquals(segmentRef(x, y, z, -1, 0, 0, 1, 1, 2) - 0.4, capsule.distance(x, y, z), 2e-5);
            assertEquals(x - 1.5, plane.distance(x, y, z), 2e-5);
        }
    }

    @Test
    void theCylinderAndTheTorusAgreeWithSampledSurfaces() {
        Sdf cylinder = Sdfs.cylinder(0, 0.5f, 0, 1, 1.5f);
        Sdf torus = Sdfs.torus(0, 0, 0, 2, 0.5f);
        // the distance to a dense sample of the surface is an upper bound that comes close to the true value
        int n = 400;
        double[][] cylinderSurface = new double[3 * n * n][], torusSurface = new double[n * n][];
        int c = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double a = 2 * Math.PI * i / n, u = j / (double) (n - 1);
                cylinderSurface[c++] = new double[] {Math.cos(a), 0.5 - 1.5 + 3 * u, Math.sin(a)}; // the side
                double r = u;
                cylinderSurface[c++] = new double[] {r * Math.cos(a), 0.5 + 1.5, r * Math.sin(a)}; // the top
                cylinderSurface[c++] = new double[] {r * Math.cos(a), 0.5 - 1.5, r * Math.sin(a)}; // the bottom
                double b = 2 * Math.PI * j / n;
                torusSurface[i * n + j] = new double[] {(2 + 0.5 * Math.cos(b)) * Math.cos(a), 0.5 * Math.sin(b), (2 + 0.5 * Math.cos(b)) * Math.sin(a)};
            }
        }
        for (int t = 0; t < 300; t++) {
            float x = coord(4), y = coord(4), z = coord(4);
            double best = Double.POSITIVE_INFINITY, bestT = Double.POSITIVE_INFINITY;
            for (double[] p : cylinderSurface) {
                best = Math.min(best, dist(x, y, z, p[0], p[1], p[2]));
            }
            for (double[] p : torusSurface) {
                bestT = Math.min(bestT, dist(x, y, z, p[0], p[1], p[2]));
            }
            float dc = cylinder.distance(x, y, z), dt = torus.distance(x, y, z);
            assertEquals(Math.abs(dc), best, 0.02, "cylinder at " + x + " " + y + " " + z);
            assertEquals(Math.abs(dt), bestT, 0.02, "torus at " + x + " " + y + " " + z);
        }
    }

    // ------------------------------------------------------------ the guarantees of the interface

    private void assertLipschitz(String name, Sdf f, double range) {
        double worst = 0;
        for (int i = 0; i < 20000; i++) {
            float x = coord(range), y = coord(range), z = coord(range);
            float u = x + coord(0.3), v = y + coord(0.3), w = z + coord(0.3);
            double d = dist(x, y, z, u, v, w);
            double change = Math.abs(f.distance(x, y, z) - f.distance(u, v, w));
            if (d > 1e-3) {
                worst = Math.max(worst, change / d);
            }
        }
        assertTrue(worst <= 1.0 + 2e-3, name + " is not 1-Lipschitz: the slope is " + worst);
    }

    @Test
    void everyFunctionIsOneLipschitzAndExactOnesHaveUnitGradients() {
        Sdf a = Sdfs.sphere(0, 0, 0, 1), b = Sdfs.box(0.7f, 0.2f, 0, 0.8f, 0.5f, 0.6f), c = Sdfs.capsule(-1, 0, 0, 1, 0, 0, 0.3f);
        Sdf[] exact = {a, b, c, Sdfs.roundBox(0, 0, 0, 1, 0.5f, 0.5f, 0.2f), Sdfs.plane(0.3f, 1, -0.2f, 0.4f), Sdfs.cylinder(0, 0, 0, 1, 1), Sdfs.torus(0, 0, 0, 1.5f, 0.4f)};
        for (int i = 0; i < exact.length; i++) {
            assertLipschitz("primitive " + i, exact[i], 4);
        }
        Sdf q = Sdfs.rotate(b, new Quatf(0.2f, 0.5f, -0.3f, 0.8f));
        Sdf[] derived = {Sdfs.union(a, b), Sdfs.intersection(a, b), Sdfs.subtract(a, b), Sdfs.smoothUnion(a, b, 0.5f), Sdfs.smoothIntersection(a, b, 0.5f), Sdfs.smoothSubtract(a, b, 0.5f),
                Sdfs.invert(a), Sdfs.onion(a, 0.2f), Sdfs.round(b, 0.1f), Sdfs.translate(a, 1, 2, 3), q, Sdfs.scale(b, 2.5f), Sdfs.union(a, b, c)};
        for (int i = 0; i < derived.length; i++) {
            assertLipschitz("derived " + i, derived[i], 4);
        }
        // the eikonal equation |grad d| = 1 for the exact ones, away from the surface of a solid's medial axis: checked on points outside the solids where d is the distance
        float[] n = new float[3];
        for (int i = 0; i < exact.length; i++) {
            int checked = 0;
            for (int t = 0; t < 2000 && checked < 200; t++) {
                float x = coord(4), y = coord(4), z = coord(4);
                if (exact[i].distance(x, y, z) < 0.2f) {
                    continue; // inside the solid the nearest point can jump (the medial axis)
                }
                float h = 1e-2f;
                float gx = (exact[i].distance(x + h, y, z) - exact[i].distance(x - h, y, z)) / (2 * h), gy = (exact[i].distance(x, y + h, z) - exact[i].distance(x, y - h, z)) / (2 * h),
                        gz = (exact[i].distance(x, y, z + h) - exact[i].distance(x, y, z - h)) / (2 * h);
                assertEquals(1.0, Math.sqrt(gx * gx + gy * gy + gz * gz), 0.02, "primitive " + i);
                checked++;
            }
            assertTrue(checked > 50, "primitive " + i + ": too few points outside");
        }
    }

    @Test
    void theSignAndTheZeroSetOfTheCsgOperationsAreExact() {
        Sdf a = Sdfs.sphere(0, 0, 0, 1), b = Sdfs.box(0.7f, 0.2f, 0, 0.8f, 0.5f, 0.6f);
        Sdf union = Sdfs.union(a, b), inter = Sdfs.intersection(a, b), diff = Sdfs.subtract(a, b), inv = Sdfs.invert(a);
        for (int i = 0; i < 20000; i++) {
            float x = coord(2), y = coord(2), z = coord(2);
            boolean ia = a.distance(x, y, z) < 0, ib = b.distance(x, y, z) < 0;
            assertEquals(ia || ib, union.distance(x, y, z) < 0);
            assertEquals(ia && ib, inter.distance(x, y, z) < 0);
            assertEquals(ia && !ib, diff.distance(x, y, z) < 0);
            assertEquals(a.distance(x, y, z) > 0, inv.distance(x, y, z) < 0);
        }
        // outside a union of exact solids the result is the true distance to the union: the smaller of the two
        for (int i = 0; i < 2000; i++) {
            float x = coord(3), y = coord(3), z = coord(3);
            if (a.distance(x, y, z) > 0 && b.distance(x, y, z) > 0) {
                assertEquals(Math.min(a.distance(x, y, z), b.distance(x, y, z)), union.distance(x, y, z), 0f);
            }
        }
        // the variadic union is the same as the nested one, and the empty union is the empty solid
        Sdf three = Sdfs.union(a, b, Sdfs.sphere(3, 0, 0, 0.5f));
        Sdf nested = Sdfs.union(Sdfs.union(a, b), Sdfs.sphere(3, 0, 0, 0.5f));
        for (int i = 0; i < 1000; i++) {
            float x = coord(4), y = coord(4), z = coord(4);
            assertEquals(nested.distance(x, y, z), three.distance(x, y, z), 0f);
        }
        assertEquals(Float.POSITIVE_INFINITY, Sdfs.union().distance(0, 0, 0), 0f);
    }

    @Test
    void smoothOperationsStayWithinAQuarterOfTheBlendWidthOfTheHardOnes() {
        Sdf a = Sdfs.sphere(0, 0, 0, 1), b = Sdfs.sphere(1.2f, 0, 0, 0.8f);
        float k = 0.6f;
        Sdf su = Sdfs.smoothUnion(a, b, k), si = Sdfs.smoothIntersection(a, b, k), ss = Sdfs.smoothSubtract(a, b, k);
        Sdf hu = Sdfs.union(a, b), hi = Sdfs.intersection(a, b), hs = Sdfs.subtract(a, b);
        double widest = 0;
        for (int i = 0; i < 20000; i++) {
            float x = coord(3), y = coord(3), z = coord(3);
            float u = su.distance(x, y, z), hu0 = hu.distance(x, y, z);
            assertTrue(u <= hu0 + 1e-6f && u >= hu0 - k / 4 - 1e-6f);
            float in = si.distance(x, y, z), hi0 = hi.distance(x, y, z);
            assertTrue(in >= hi0 - 1e-6f && in <= hi0 + k / 4 + 1e-6f);
            float s = ss.distance(x, y, z), hs0 = hs.distance(x, y, z);
            assertTrue(s >= hs0 - 1e-6f && s <= hs0 + k / 4 + 1e-6f);
            widest = Math.max(widest, hu0 - u);
        }
        assertTrue(widest > 0.9 * k / 4, "the blend reaches its maximum k / 4 where the two distances are equal: " + widest);
        // a blend width of 0 is the hard operation
        Sdf zero = Sdfs.smoothUnion(a, b, 0f);
        for (int i = 0; i < 200; i++) {
            float x = coord(3), y = coord(3), z = coord(3);
            assertEquals(hu.distance(x, y, z), zero.distance(x, y, z), 0f);
        }
    }

    @Test
    void onionRoundTranslateRotateScaleAndRepeat() {
        Sdf sphere = Sdfs.sphere(0, 0, 0, 1);
        Sdf shell = Sdfs.onion(sphere, 0.2f), grown = Sdfs.round(sphere, 0.5f), moved = Sdfs.translate(sphere, 1, 2, 3), scaled = Sdfs.scale(sphere, 3f);
        for (int i = 0; i < 5000; i++) {
            float x = coord(4), y = coord(4), z = coord(4);
            float d = sphere.distance(x, y, z);
            assertEquals(Math.abs(d) - 0.1f, shell.distance(x, y, z), 1e-6f);
            assertEquals(d - 0.5f, grown.distance(x, y, z), 1e-6f);
            assertEquals(sphere.distance(x - 1, y - 2, z - 3), moved.distance(x, y, z), 1e-6f);
            assertEquals(Sdfs.sphere(0, 0, 0, 3).distance(x, y, z), scaled.distance(x, y, z), 1e-5f);
        }
        // a quarter turn about z swaps the x and y extents of a box
        float s = (float) Math.sqrt(0.5);
        Sdf long_ = Sdfs.box(0, 0, 0, 2, 0.5f, 1), turned = Sdfs.rotate(long_, new Quatf(0, 0, s, s)), expected = Sdfs.box(0, 0, 0, 0.5f, 2, 1);
        Sdf unnormalised = Sdfs.rotate(long_, new Quatf(0, 0, 2 * s, 2 * s));
        for (int i = 0; i < 5000; i++) {
            float x = coord(4), y = coord(4), z = coord(4);
            assertEquals(expected.distance(x, y, z), turned.distance(x, y, z), 2e-5f);
            assertEquals(turned.distance(x, y, z), unnormalised.distance(x, y, z), 1e-6f);
        }
        // rotation is about the origin: a sphere off the axis moves with it
        Sdf off = Sdfs.rotate(Sdfs.sphere(1, 0, 0, 0.25f), new Quatf(0, 0, s, s)); // the centre goes to (0, 1, 0)
        assertEquals(-0.25f, off.distance(0, 1, 0), 1e-5f);
        // repeat: copies on a grid of period 4 along x and z, none along y
        Sdf grid = Sdfs.repeat(Sdfs.sphere(0, 0, 0, 1), 4, 0, 4);
        assertEquals(-1f, grid.distance(8, 0, -12), 1e-5f);
        assertEquals(grid.distance(1.3f, 0.7f, 0.2f), grid.distance(1.3f + 12, 0.7f, 0.2f - 8), 1e-4f);
        assertEquals(Sdfs.sphere(0, 0, 0, 1).distance(0.5f, 5f, 0.5f), grid.distance(0.5f + 4, 5f, 0.5f), 1e-5f);
    }

    @Test
    void argumentsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> Sdfs.sphere(0, 0, 0, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.box(0, 0, 0, 1, Float.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.box(0, 0, 0, Float.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.box(0, 0, 0, 1, 1, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.roundBox(0, 0, 0, 1, 1, 1, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.roundBox(0, 0, 0, Float.NaN, 1, 1, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.roundBox(0, 0, 0, 1, Float.NaN, 1, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.roundBox(0, 0, 0, 1, 1, Float.NaN, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.plane(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.plane(Float.POSITIVE_INFINITY, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.capsule(0, 0, 0, 1, 1, 1, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.cylinder(0, 0, 0, Float.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.cylinder(0, 0, 0, 1, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.torus(0, 0, 0, Float.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.torus(0, 0, 0, 1, Float.NaN));
        Sdf a = Sdfs.sphere(0, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> Sdfs.smoothUnion(a, a, -1f));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.smoothIntersection(a, a, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.smoothSubtract(a, a, -0.1f));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.onion(a, -1f));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.round(a, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.rotate(a, new Quatf(0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.scale(a, 0f));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.scale(a, -1f));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.repeat(a, -1f, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.repeat(a, 0, Float.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.repeat(a, 0, 0, -1f));
        assertThrows(IllegalArgumentException.class, () -> Sdfs.raycast(a, 0, 0, 0, 0, 0, 0, 0, 10, 10, 1e-3f, new Sdfs.Hit()));
        // a degenerate capsule is a sphere
        Sdf point = Sdfs.capsule(1, 1, 1, 1, 1, 1, 0.5f);
        assertEquals(0.5f, point.distance(1, 2, 1), 1e-6f);
    }

    // ------------------------------------------------------------ normals, projection

    @Test
    void normalsAndProjection() {
        Sdf sphere = Sdfs.sphere(1, 2, 3, 2);
        float[] n = new float[3], p = new float[3];
        for (int i = 0; i < 2000; i++) {
            float x = 1 + coord(5), y = 2 + coord(5), z = 3 + coord(5);
            double r = dist(x, y, z, 1, 2, 3);
            if (r < 0.2) {
                continue;
            }
            assertTrue(Sdfs.normal(sphere, x, y, z, 1e-3f, n));
            assertEquals((x - 1) / r, n[0], 2e-3);
            assertEquals((y - 2) / r, n[1], 2e-3);
            assertEquals((z - 3) / r, n[2], 2e-3);
            float left = Sdfs.project(sphere, x, y, z, 4, 1e-5f, 1e-3f, p);
            assertEquals(0.0, left, 1e-4);
            assertEquals(2.0, dist(p[0], p[1], p[2], 1, 2, 3), 1e-4);
        }
        // at the centre the gradient vanishes
        Sdf atOrigin = Sdfs.sphere(0, 0, 0, 2);
        assertFalse(Sdfs.normal(atOrigin, 0, 0, 0, 1e-3f, n));
        assertEquals(0f, Math.abs(n[0]) + Math.abs(n[1]) + Math.abs(n[2]), 0f);
        // zero iterations leaves the point and reports its distance; a point already on the surface needs none
        assertEquals(3.0, Sdfs.project(sphere, 1, 7, 3, 0, 1e-5f, 1e-3f, p), 1e-6);
        assertEquals(7f, p[1], 0f);
        assertEquals(0.0, Sdfs.project(sphere, 3, 2, 3, 5, 1e-5f, 1e-3f, p), 1e-6);
        // projecting from the centre cannot work (no gradient): it gives up and reports the distance
        assertEquals(2.0, Sdfs.project(atOrigin, 0, 0, 0, 5, 1e-5f, 1e-3f, p), 1e-6);
    }

    // ------------------------------------------------------------ sphere tracing

    @Test
    void raysAgainstASphereMatchTheAnalyticIntersection() {
        float cx = 1, cy = -1, cz = 2, r = 1.5f;
        Sdf sphere = Sdfs.sphere(cx, cy, cz, r);
        Sdfs.Hit hit = new Sdfs.Hit();
        int hits = 0, misses = 0;
        for (int i = 0; i < 5000; i++) {
            float ox = coord(8), oy = coord(8), oz = coord(8);
            float dx = cx + coord(2.5) - ox, dy = cy + coord(2.5) - oy, dz = cz + coord(2.5) - oz; // aimed near the sphere, so that many rays hit
            double l = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            double ux = dx / l, uy = dy / l, uz = dz / l;
            double mx = ox - cx, my = oy - cy, mz = oz - cz;
            double b = mx * ux + my * uy + mz * uz, c = mx * mx + my * my + mz * mz - (double) r * r, disc = b * b - c;
            if (c <= 0) {
                continue; // started inside: tested separately
            }
            double expected = disc >= 0 && -b - Math.sqrt(disc) >= 0 ? -b - Math.sqrt(disc) : Double.NaN;
            boolean got = Sdfs.raycast(sphere, ox, oy, oz, dx, dy, dz, 0f, 100f, 256, 1e-4f, hit);
            if (Double.isNaN(expected)) {
                misses++;
                // a ray that grazes the sphere may take more than 256 steps to decide; a clean miss must be a miss
                if (disc < -0.05) {
                    assertFalse(got, "ray " + i + " should miss");
                }
            } else if (disc > 0.05) { // not grazing: the error of t is about epsilon over the cosine of the angle of incidence
                hits++;
                assertTrue(got, "ray " + i + " should hit at " + expected);
                assertEquals(expected, hit.t, 1.5e-4 / (Math.sqrt(disc) / r) + 1e-5, "ray " + i);
                assertEquals(0.0, sphere.distance(hit.x, hit.y, hit.z), 2e-4);
                assertEquals((hit.x - cx) / r, hit.nx, 5e-3);
                assertEquals((hit.y - cy) / r, hit.ny, 5e-3);
                assertEquals((hit.z - cz) / r, hit.nz, 5e-3);
                assertFalse(hit.inside);
                assertTrue(hit.steps >= 1 && hit.steps <= 256);
            }
        }
        assertTrue(hits > 800 && misses > 300, hits + " hits, " + misses + " misses");
    }

    @Test
    void raycastLimitsInsideStartsAndAlternativeSolids() {
        Sdfs.Hit hit = new Sdfs.Hit();
        Sdf sphere = Sdfs.sphere(0, 0, 0, 1);
        // starting inside reports the start point
        assertTrue(Sdfs.raycast(sphere, 0.1f, 0, 0, 1, 0, 0, 0f, 10f, 64, 1e-4f, hit));
        assertTrue(hit.inside);
        assertEquals(0f, hit.t, 0f);
        // the range: a hit behind tMax is a miss, one after tMin is found, and tMin past the surface starts inside
        assertFalse(Sdfs.raycast(sphere, -5, 0, 0, 3, 0, 0, 0f, 3.5f, 64, 1e-4f, hit)); // the surface is 4 away
        assertTrue(Sdfs.raycast(sphere, -5, 0, 0, 3, 0, 0, 0f, 4.5f, 64, 1e-4f, hit));
        assertEquals(4.0, hit.t, 1e-3);
        assertEquals(-1.0, hit.x, 1e-3);
        assertEquals(-1.0, hit.nx, 1e-2, "the normal points out of the solid, against the ray");
        // too few steps: the march gives up and says so with a miss
        Sdf slab = Sdfs.box(0, 0, 0, 100, 100, 0.001f); // a grazing march over a huge thin plate takes many steps
        assertFalse(Sdfs.raycast(slab, -50, 0, 0.5f, 1, 0, -0.0005f, 0f, 200f, 4, 1e-6f, hit));
        assertEquals(4, hit.steps);
        // a CSG scene: a box with a spherical hole, a ray through the hole and one beside it
        Sdf scene = Sdfs.subtract(Sdfs.box(0, 0, 0, 2, 2, 2), Sdfs.sphere(0, 0, 0, 1.2f));
        assertTrue(Sdfs.raycast(scene, -5, 1.5f, 0, 1, 0, 0, 0f, 20f, 128, 1e-4f, hit));
        assertEquals(3.0, hit.t, 1e-3, "through the box wall at x = -2 above the hole");
        assertTrue(Sdfs.raycast(scene, -5, 0, 0, 1, 0, 0, 0f, 20f, 128, 1e-4f, hit));
        assertEquals(3.0, hit.t, 1e-3, "the face of the box at x = -2 comes first");
        assertTrue(Sdfs.raycast(scene, 0, 0, 0, 1, 0, 0, 0f, 20f, 128, 1e-4f, hit), "from the middle of the hole the wall of the cavity is hit");
        assertEquals(1.2, hit.t, 1e-3);
        assertEquals(-1.0, hit.nx, 1e-2, "inside the cavity the surface faces the centre (out of the solid)");
    }
}
