package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

/**
 * QA-1: {@link Gjk} on the shapes that make its closest-point routines take their special branches: points, segments, triangles and squares (no volume, so the
 * simplex is flat or collinear), and axis-aligned boxes at whole coordinates, where the closest feature is exactly a vertex, an edge or a face and every
 * comparison of the routines meets a tie.
 */
class GjkFlatShapesTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);
    private final Gjk gjk = new Gjk();
    private final Gjk.Result r = new Gjk.Result();

    /** A shape given by its points: the support point is the one furthest along the direction. */
    private static ConvexShape hull(double... xyz) {
        return (dx, dy, dz, out) -> {
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < xyz.length; i += 3) {
                double d = xyz[i] * dx + xyz[i + 1] * dy + xyz[i + 2] * dz;
                if (d > best) {
                    best = d;
                    out[0] = xyz[i];
                    out[1] = xyz[i + 1];
                    out[2] = xyz[i + 2];
                }
            }
        };
    }

    private static double[] box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[] {x0, y0, z0, x1, y0, z0, x0, y1, z0, x1, y1, z0, x0, y0, z1, x1, y0, z1, x0, y1, z1, x1, y1, z1};
    }

    private static double[] square(double z, double x0, double y0, double x1, double y1) {
        return new double[] {x0, y0, z, x1, y0, z, x0, y1, z, x1, y1, z};
    }

    @Test
    void boxesAtWholeCoordinatesAreApartByTheDistanceOfTheirClosestFeatures() {
        ConvexShape unit = hull(box(0, 0, 0, 1, 1, 1));
        // a face, an edge and a vertex of the other box is the closest feature
        double[][] offsets = {{3, 0, 0}, {0, -3, 0}, {0, 0, 4}, {3, 2, 0}, {0, 3, 4}, {-2, 0, 5}, {3, 2, 2}, {-3, -4, 2}, {-2, 3, 2.5}};
        for (double[] o : offsets) {
            ConvexShape other = hull(box(o[0], o[1], o[2], o[0] + 1, o[1] + 1, o[2] + 1));
            double gx = Math.max(0, Math.max(o[0] - 1, -1 - o[0])), gy = Math.max(0, Math.max(o[1] - 1, -1 - o[1])), gz = Math.max(0, Math.max(o[2] - 1, -1 - o[2]));
            double expected = Math.sqrt(gx * gx + gy * gy + gz * gz);
            assertEquals(expected, gjk.distance(unit, other, r), 1e-9, java.util.Arrays.toString(o));
            assertFalse(r.overlapping);
            assertEquals(expected, Math.sqrt(sq(r.pointA[0] - r.pointB[0]) + sq(r.pointA[1] - r.pointB[1]) + sq(r.pointA[2] - r.pointB[2])), 1e-9);
            assertEquals(1.0, ((r.pointB[0] - r.pointA[0]) * r.normal[0] + (r.pointB[1] - r.pointA[1]) * r.normal[1] + (r.pointB[2] - r.pointA[2]) * r.normal[2]) / expected, 1e-9, "the normal runs from A to B");
            // the same, the other way round: the normal flips and the distance stays
            assertEquals(expected, gjk.distance(other, unit, r), 1e-9);
            assertFalse(gjk.intersects(unit, other));
        }
    }

    @Test
    void touchingBoxesOverlapAndOverlappingBoxesHaveTheDepthOfTheLeastOverlap() {
        ConvexShape a = hull(box(0, 0, 0, 2, 2, 2));
        assertTrue(gjk.intersects(a, hull(box(2, 0, 0, 3, 1, 1))), "touching along a face");
        assertTrue(gjk.intersects(a, hull(box(2, 2, 2, 3, 3, 3))), "touching at a corner");
        assertFalse(gjk.intersects(a, hull(box(2.001, 0, 0, 3, 1, 1))));
        // overlaps of 0.5 along x, 1 along y, 1.5 along z: the least is x
        assertTrue(gjk.penetration(a, hull(box(1.5, 1, 0.5, 4, 4, 4)), r));
        assertEquals(0.5, r.depth, 1e-9);
        assertEquals(1.0, r.normal[0], 1e-9);
        assertEquals(0.0, r.normal[1], 1e-9);
        assertEquals(0.0, r.normal[2], 1e-9);
        // B on the other side: the normal still points from A to B
        assertTrue(gjk.penetration(a, hull(box(-3, 0.25, -4, 0.25, 1, 5)), r));
        assertEquals(0.25, r.depth, 1e-9);
        assertEquals(-1.0, r.normal[0], 1e-9);
        // deep inside: a small box in the middle of a large one, depth to the nearest face of the large one plus the small one
        assertTrue(gjk.penetration(hull(box(-5, -5, -5, 5, 5, 5)), hull(box(3.5, -0.5, -0.5, 4.5, 0.5, 0.5)), r));
        assertEquals(1.5, r.depth, 1e-9);
        assertEquals(1.0, r.normal[0], 1e-9);
        // one inside the other along an axis: moving the inner box along -y is the shortest way out
        assertTrue(gjk.penetration(hull(box(0, 0, 0, 10, 10, 10)), hull(box(4, 0.5, 4, 6, 2.5, 6)), r));
        assertEquals(2.5, r.depth, 1e-9);
        assertEquals(-1.0, r.normal[1], 1e-9);
    }

    @Test
    void shapesWithoutVolumeGiveTheExactDistance() {
        // a point and a point
        assertEquals(5.0, gjk.distance(hull(0, 0, 0), hull(3, 4, 0), r), 1e-12);
        assertEquals(0.6, r.normal[0], 1e-12);
        assertEquals(0.8, r.normal[1], 1e-12);
        // a point and a segment, nearest to its middle, to an end, and beyond an end
        ConvexShape segment = hull(0, 0, 0, 4, 0, 0);
        assertEquals(3.0, gjk.distance(segment, hull(2, 3, 0), r), 1e-12);
        assertEquals(2.0, r.pointA[0], 1e-12);
        assertEquals(5.0, gjk.distance(segment, hull(7, 4, 0), r), 1e-12);
        assertEquals(4.0, r.pointA[0], 1e-12);
        assertEquals(3.0, gjk.distance(segment, hull(-3, 0, 0), r), 1e-12);
        assertEquals(0.0, r.pointA[0], 1e-12);
        // two segments: crossing at a gap, parallel, collinear apart
        assertEquals(2.0, gjk.distance(segment, hull(2, -1, 2, 2, 1, 2), r), 1e-12);
        assertEquals(1.0, gjk.distance(segment, hull(1, 1, 0, 3, 1, 0), r), 1e-12);
        assertEquals(2.0, gjk.distance(segment, hull(6, 0, 0, 9, 0, 0), r), 1e-12);
        // a triangle and a point above its interior, above an edge, and beyond a corner
        ConvexShape tri = hull(0, 0, 0, 4, 0, 0, 0, 4, 0);
        assertEquals(2.0, gjk.distance(tri, hull(1, 1, 2), r), 1e-12);
        assertEquals(1.0, r.pointA[0], 1e-12);
        assertEquals(Math.sqrt(2.0), gjk.distance(tri, hull(3, 3, 0), r), 1e-12, "beside the long edge");
        assertEquals(Math.sqrt(1.5 * 1.5 + 2 * 2), gjk.distance(tri, hull(-1.5, -2, 0), r), 1e-12, "beyond the corner");
        // a square and a square in parallel planes, offset so that corner meets corner, edge meets edge, and face meets face
        ConvexShape sq0 = hull(square(0, 0, 0, 2, 2));
        assertEquals(Math.sqrt(1 + 1 + 9), gjk.distance(sq0, hull(square(3, 3, 3, 5, 5)), r), 1e-12);
        assertEquals(Math.sqrt(1 + 9), gjk.distance(sq0, hull(square(3, 3, 0.5, 5, 1.5)), r), 1e-12);
        assertEquals(3.0, gjk.distance(sq0, hull(square(3, 0.5, 0.5, 1.5, 1.5)), r), 1e-12);
        assertEquals(3.0, gjk.distance(sq0, hull(square(3, 0, 0, 2, 2)), r), 1e-12);
    }

    @Test
    void randomSegmentsAndTrianglesAgreeWithTheClosedForms() {
        for (int trial = 0; trial < 400; trial++) {
            double[] s1 = rnd(6, 0), s2 = rnd(6, 4);
            double expected = segDist(s1, s2);
            assertEquals(expected, gjk.distance(hull(s1), hull(s2), r), 1e-9, "segments " + trial);
            double[] t = rnd(9, 0), p = rnd(3, 3);
            double e2 = ptTri(p, t);
            assertEquals(e2, gjk.distance(hull(t), hull(p), r), 1e-9, "point and triangle " + trial);
            double[] u = rnd(9, 2);
            // two triangles that do not touch (random in different regions): the distance is a vertex-triangle or an edge-edge distance
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 3; i++) {
                best = Math.min(best, ptTri(new double[] {t[3 * i], t[3 * i + 1], t[3 * i + 2]}, u));
                best = Math.min(best, ptTri(new double[] {u[3 * i], u[3 * i + 1], u[3 * i + 2]}, t));
                for (int j = 0; j < 3; j++) {
                    best = Math.min(best, segDist(new double[] {t[3 * i], t[3 * i + 1], t[3 * i + 2], t[3 * ((i + 1) % 3)], t[3 * ((i + 1) % 3) + 1], t[3 * ((i + 1) % 3) + 2]},
                            new double[] {u[3 * j], u[3 * j + 1], u[3 * j + 2], u[3 * ((j + 1) % 3)], u[3 * ((j + 1) % 3) + 1], u[3 * ((j + 1) % 3) + 2]}));
                }
            }
            double got = gjk.distance(hull(t), hull(u), r);
            if (best > 1e-6 && !r.overlapping) {
                assertTrue(got <= best + 1e-9, "triangles " + trial + ": " + got + " against " + best);
                assertEquals(best, got, 1e-8, "triangles " + trial);
            }
        }
    }

    private double[] rnd(int n, double shift) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = rng.nextDouble(-2, 2) + (i % 3 == 0 ? shift : 0);
        }
        return a;
    }

    private static double sq(double x) {
        return x * x;
    }

    private static double ptTri(double[] p, double[] t) {
        // Ericson's closest point on a triangle, by clamping the barycentric solution of the plane projection and trying the edges
        double best = Double.POSITIVE_INFINITY;
        double[] a = {t[0], t[1], t[2]}, b = {t[3], t[4], t[5]}, c = {t[6], t[7], t[8]};
        double[] n = cross(sub(b, a), sub(c, a));
        double nn = dot(n, n);
        if (nn > 1e-18) {
            double h = dot(sub(p, a), n) / nn;
            double[] q = sub(p, new double[] {n[0] * h, n[1] * h, n[2] * h});
            double[] v0 = sub(b, a), v1 = sub(c, a), v2 = sub(q, a);
            double d00 = dot(v0, v0), d01 = dot(v0, v1), d11 = dot(v1, v1), d20 = dot(v2, v0), d21 = dot(v2, v1), den = d00 * d11 - d01 * d01;
            double v = (d11 * d20 - d01 * d21) / den, w = (d00 * d21 - d01 * d20) / den;
            if (v >= 0 && w >= 0 && v + w <= 1) {
                best = Math.abs(h) * Math.sqrt(nn);
            }
        }
        best = Math.min(best, ptSeg(p, a, b));
        best = Math.min(best, ptSeg(p, b, c));
        best = Math.min(best, ptSeg(p, c, a));
        return best;
    }

    private static double ptSeg(double[] p, double[] a, double[] b) {
        double[] ab = sub(b, a);
        double t = Math.max(0, Math.min(1, dot(sub(p, a), ab) / dot(ab, ab)));
        double[] q = {a[0] + ab[0] * t, a[1] + ab[1] * t, a[2] + ab[2] * t};
        return Math.sqrt(sq(p[0] - q[0]) + sq(p[1] - q[1]) + sq(p[2] - q[2]));
    }

    private static double segDist(double[] s1, double[] s2) {
        double[] p1 = {s1[0], s1[1], s1[2]}, q1 = {s1[3], s1[4], s1[5]}, p2 = {s2[0], s2[1], s2[2]}, q2 = {s2[3], s2[4], s2[5]};
        double[] d1 = sub(q1, p1), d2 = sub(q2, p2), r0 = sub(p1, p2);
        double a = dot(d1, d1), e = dot(d2, d2), f = dot(d2, r0), c = dot(d1, r0), b = dot(d1, d2), den = a * e - b * b;
        double s = den > 1e-14 * a * e ? Math.max(0, Math.min(1, (b * f - c * e) / den)) : 0;
        double t = (b * s + f) / e;
        if (t < 0) {
            t = 0;
            s = Math.max(0, Math.min(1, -c / a));
        } else if (t > 1) {
            t = 1;
            s = Math.max(0, Math.min(1, (b - c) / a));
        }
        double[] c1 = {p1[0] + d1[0] * s, p1[1] + d1[1] * s, p1[2] + d1[2] * s}, c2 = {p2[0] + d2[0] * t, p2[1] + d2[1] * t, p2[2] + d2[2] * t};
        return Math.sqrt(sq(c1[0] - c2[0]) + sq(c1[1] - c2[1]) + sq(c1[2] - c2[2]));
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
