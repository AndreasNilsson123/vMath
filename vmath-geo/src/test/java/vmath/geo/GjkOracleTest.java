package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

/**
 * QA-1: {@link Gjk} against exact references on random convex polytopes. The distance of two separated polytopes is the least distance between a vertex of one
 * and a triangle of the other, or between two edges; the penetration depth of two overlapping ones is the least overlap along the facet normals and the cross
 * products of the edges (the separating axis test), which is exact for polytopes. The first found hundreds of surviving mutants in the closest-point routines
 * that the older tests did not constrain.
 */
class GjkOracleTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);
    private final Gjk gjk = new Gjk();

    private ConvexPolytope randomPolytope(double cx, double cy, double cz, double radius, int points) {
        float[] p = new float[3 * points];
        for (int i = 0; i < points; i++) {
            double x, y, z;
            do {
                x = rng.nextDouble(-1, 1);
                y = rng.nextDouble(-1, 1);
                z = rng.nextDouble(-1, 1);
            } while (x * x + y * y + z * z > 1);
            p[3 * i] = (float) (cx + radius * x);
            p[3 * i + 1] = (float) (cy + radius * y);
            p[3 * i + 2] = (float) (cz + radius * z);
        }
        return ConvexPolytope.of(p, points);
    }

    private static double[] vertex(ConvexPolytope p, int i) {
        return new double[] {p.vertex(i, 0), p.vertex(i, 1), p.vertex(i, 2)};
    }

    /** The distance from {@code p} to the triangle {@code a b c} (Ericson 5.1.5). */
    private static double pointTriangle(double[] p, double[] a, double[] b, double[] c) {
        double[] cp = closestOnTriangle(p, a, b, c);
        return Math.sqrt(sq(p[0] - cp[0]) + sq(p[1] - cp[1]) + sq(p[2] - cp[2]));
    }

    private static double sq(double x) {
        return x * x;
    }

    private static double[] closestOnTriangle(double[] p, double[] a, double[] b, double[] c) {
        double[] ab = sub(b, a), ac = sub(c, a), ap = sub(p, a);
        double d1 = dot(ab, ap), d2 = dot(ac, ap);
        if (d1 <= 0 && d2 <= 0) {
            return a;
        }
        double[] bp = sub(p, b);
        double d3 = dot(ab, bp), d4 = dot(ac, bp);
        if (d3 >= 0 && d4 <= d3) {
            return b;
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            return add(a, scale(ab, d1 / (d1 - d3)));
        }
        double[] cpv = sub(p, c);
        double d5 = dot(ab, cpv), d6 = dot(ac, cpv);
        if (d6 >= 0 && d5 <= d6) {
            return c;
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            return add(a, scale(ac, d2 / (d2 - d6)));
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            return add(b, scale(sub(c, b), (d4 - d3) / ((d4 - d3) + (d5 - d6))));
        }
        double denom = 1.0 / (va + vb + vc);
        return add(a, add(scale(ab, vb * denom), scale(ac, vc * denom)));
    }

    private static double segmentSegment(double[] p1, double[] q1, double[] p2, double[] q2) {
        double[] d1 = sub(q1, p1), d2 = sub(q2, p2), r = sub(p1, p2);
        double a = dot(d1, d1), e = dot(d2, d2), f = dot(d2, r);
        double s, t;
        double c = dot(d1, r), b = dot(d1, d2), denom = a * e - b * b;
        s = denom > 1e-14 * a * e ? clamp((b * f - c * e) / denom) : 0;
        t = (b * s + f) / e;
        if (t < 0) {
            t = 0;
            s = clamp(-c / a);
        } else if (t > 1) {
            t = 1;
            s = clamp((b - c) / a);
        }
        double[] c1 = add(p1, scale(d1, s)), c2 = add(p2, scale(d2, t));
        return Math.sqrt(sq(c1[0] - c2[0]) + sq(c1[1] - c2[1]) + sq(c1[2] - c2[2]));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] scale(double[] a, double s) {
        return new double[] {a[0] * s, a[1] * s, a[2] * s};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** The exact distance of two separated polytopes: the least over vertex-triangle and edge-edge pairs. */
    private static double referenceDistance(ConvexPolytope a, ConvexPolytope b) {
        double best = Double.POSITIVE_INFINITY;
        int[] ta = a.triangles(), tb = b.triangles();
        for (int i = 0; i < a.vertexCount(); i++) {
            for (int t = 0; t < tb.length; t += 3) {
                best = Math.min(best, pointTriangle(vertex(a, i), vertex(b, tb[t]), vertex(b, tb[t + 1]), vertex(b, tb[t + 2])));
            }
        }
        for (int i = 0; i < b.vertexCount(); i++) {
            for (int t = 0; t < ta.length; t += 3) {
                best = Math.min(best, pointTriangle(vertex(b, i), vertex(a, ta[t]), vertex(a, ta[t + 1]), vertex(a, ta[t + 2])));
            }
        }
        for (int s = 0; s < ta.length; s++) {
            double[] p1 = vertex(a, ta[s]), q1 = vertex(a, ta[s % 3 == 2 ? s - 2 : s + 1]);
            for (int u = 0; u < tb.length; u++) {
                best = Math.min(best, segmentSegment(p1, q1, vertex(b, tb[u]), vertex(b, tb[u % 3 == 2 ? u - 2 : u + 1])));
            }
        }
        return best;
    }

    /** The exact penetration depth of two overlapping polytopes: the least overlap along the facet normals and the edge cross products. */
    private static double referenceDepth(ConvexPolytope a, ConvexPolytope b) {
        double best = Double.POSITIVE_INFINITY;
        java.util.List<double[]> axes = new java.util.ArrayList<>();
        double[] plane = new double[4];
        for (ConvexPolytope p : new ConvexPolytope[] {a, b}) {
            for (int f = 0; f < p.facetCount(); f++) {
                p.facetPlane(f, plane);
                axes.add(new double[] {plane[0], plane[1], plane[2]});
            }
        }
        for (int i = 0; i < a.edgeCount(); i++) {
            double[] ea = sub(vertex(a, a.edgeEnd(i)), vertex(a, a.edgeStart(i)));
            for (int j = 0; j < b.edgeCount(); j++) {
                double[] eb = sub(vertex(b, b.edgeEnd(j)), vertex(b, b.edgeStart(j)));
                double[] c = {ea[1] * eb[2] - ea[2] * eb[1], ea[2] * eb[0] - ea[0] * eb[2], ea[0] * eb[1] - ea[1] * eb[0]};
                double l = Math.sqrt(dot(c, c));
                if (l > 1e-9) {
                    axes.add(scale(c, 1 / l));
                }
            }
        }
        for (double[] n : axes) {
            double minA = Double.POSITIVE_INFINITY, maxA = Double.NEGATIVE_INFINITY, minB = Double.POSITIVE_INFINITY, maxB = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < a.vertexCount(); i++) {
                double d = dot(n, vertex(a, i));
                minA = Math.min(minA, d);
                maxA = Math.max(maxA, d);
            }
            for (int i = 0; i < b.vertexCount(); i++) {
                double d = dot(n, vertex(b, i));
                minB = Math.min(minB, d);
                maxB = Math.max(maxB, d);
            }
            best = Math.min(best, Math.min(maxA - minB, maxB - minA));
        }
        return best;
    }

    private static boolean onPolytope(ConvexPolytope p, double[] x, double tolerance) {
        double[] plane = new double[4];
        for (int f = 0; f < p.facetCount(); f++) {
            p.facetPlane(f, plane);
            if (plane[0] * x[0] + plane[1] * x[1] + plane[2] * x[2] > plane[3] + tolerance) {
                return false;
            }
        }
        return true;
    }

    @Test
    void theDistanceOfSeparatedPolytopesIsTheLeastVertexTriangleOrEdgeEdgeDistance() {
        Gjk.Result r = new Gjk.Result();
        int checked = 0;
        for (int trial = 0; trial < 500; trial++) {
            int na = 4 + rng.nextInt(10), nb = 4 + rng.nextInt(10);
            // distances from touching range to several radii, in random directions, so that every kind of closest feature (vertex, edge, face) is met
            double gap = rng.nextDouble(0.05, 2.5), theta = rng.nextDouble(0, Math.PI), phi = rng.nextDouble(0, 2 * Math.PI);
            double dx = Math.sin(theta) * Math.cos(phi) * (1 + gap), dy = Math.cos(theta) * (1 + gap), dz = Math.sin(theta) * Math.sin(phi) * (1 + gap);
            ConvexPolytope a = randomPolytope(0, 0, 0, 1, na), b = randomPolytope(dx + 1, dy, dz, 1, nb);
            if (!(referenceDepth(a, b) < -1e-6)) {
                continue; // overlapping or touching (the surface distance below is only the distance of separated shapes): the SAT overlap decides
            }
            double expected = referenceDistance(a, b);
            double d = gjk.distance(a, b, r);
            checked++;
            assertEquals(expected, d, 1e-7, "trial " + trial);
            assertFalse(r.overlapping);
            assertEquals(expected, r.distance, 1e-7);
            double[] pa = r.pointA.clone(), pb = r.pointB.clone();
            assertTrue(onPolytope(a, pa, 1e-6), "pointA is on A, trial " + trial);
            assertTrue(onPolytope(b, pb, 1e-6), "pointB is on B, trial " + trial);
            assertEquals(expected, Math.sqrt(sq(pa[0] - pb[0]) + sq(pa[1] - pb[1]) + sq(pa[2] - pb[2])), 1e-6, "the witness points are the distance apart");
            // the normal points from A to B along the line of the witness points
            double nx = (pb[0] - pa[0]) / expected, ny = (pb[1] - pa[1]) / expected, nz = (pb[2] - pa[2]) / expected;
            assertEquals(1.0, nx * r.normal[0] + ny * r.normal[1] + nz * r.normal[2], 1e-6, "normal, trial " + trial);
            assertFalse(gjk.intersects(a, b));
            assertFalse(gjk.penetration(a, b, r));
            assertEquals(expected, r.distance, 1e-7);
        }
        assertTrue(checked > 300, "enough separated pairs: " + checked);
    }

    @Test
    void theDepthOfOverlappingPolytopesIsTheLeastOverlapOnTheSeparatingAxes() {
        Gjk.Result r = new Gjk.Result();
        int checked = 0;
        for (int trial = 0; trial < 500; trial++) {
            int na = 4 + rng.nextInt(10), nb = 4 + rng.nextInt(10);
            double off = rng.nextDouble(0.1, 1.4), theta = rng.nextDouble(0, Math.PI), phi = rng.nextDouble(0, 2 * Math.PI);
            ConvexPolytope a = randomPolytope(0, 0, 0, 1, na), b = randomPolytope(Math.sin(theta) * Math.cos(phi) * off, Math.cos(theta) * off, Math.sin(theta) * Math.sin(phi) * off, 1, nb);
            double expected = referenceDepth(a, b);
            if (!(expected > 1e-4)) {
                continue; // not overlapping, or touching: no depth to compare
            }
            checked++;
            assertTrue(gjk.intersects(a, b));
            assertTrue(gjk.penetration(a, b, r), "trial " + trial);
            assertTrue(r.overlapping);
            assertEquals(0.0, r.distance);
            assertEquals(expected, r.depth, 1e-5 * (1 + expected), "depth, trial " + trial);
            assertEquals(1.0, Math.sqrt(sq(r.normal[0]) + sq(r.normal[1]) + sq(r.normal[2])), 1e-9);
            // moving B along the normal by the depth separates them: the support extents along the normal just touch
            double maxA = Double.NEGATIVE_INFINITY, minB = Double.POSITIVE_INFINITY;
            double[] n = r.normal.clone();
            for (int i = 0; i < a.vertexCount(); i++) {
                maxA = Math.max(maxA, dot(n, vertex(a, i)));
            }
            for (int i = 0; i < b.vertexCount(); i++) {
                minB = Math.min(minB, dot(n, vertex(b, i)));
            }
            assertEquals(r.depth, maxA - minB, 1e-5 * (1 + expected), "the normal is the axis of the depth, trial " + trial);
            assertTrue(onPolytope(a, r.pointA.clone(), 1e-5), "pointA on A, trial " + trial);
            assertTrue(onPolytope(b, r.pointB.clone(), 1e-5), "pointB on B, trial " + trial);
        }
        assertTrue(checked > 300, "enough overlapping pairs: " + checked);
    }

    @Test
    void shapesFarFromTheOriginAndOfVeryDifferentSizesAgreeToo() {
        Gjk.Result r = new Gjk.Result();
        int checked = 0;
        for (int trial = 0; trial < 150; trial++) {
            ConvexPolytope a = randomPolytope(500, -300, 800, 0.05 + rng.nextDouble(0, 3), 5 + rng.nextInt(6));
            ConvexPolytope b = randomPolytope(500 + rng.nextDouble(-6, 6), -300 + rng.nextDouble(-6, 6), 800 + rng.nextDouble(-6, 6), 0.05 + rng.nextDouble(0, 3), 5 + rng.nextInt(6));
            double expected = referenceDistance(a, b);
            if (referenceDepth(a, b) < -1e-3) {
                checked++;
                assertEquals(expected, gjk.distance(a, b, r), 1e-5, "far away, trial " + trial);
            } else if (referenceDepth(a, b) > 1e-3) {
                checked++;
                assertTrue(gjk.penetration(a, b, r));
                assertEquals(referenceDepth(a, b), r.depth, 1e-4, "far away, trial " + trial);
            }
        }
        assertTrue(checked > 100, "enough pairs: " + checked);
    }
}
