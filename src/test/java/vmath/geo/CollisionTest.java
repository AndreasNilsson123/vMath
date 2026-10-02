package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.Report;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;

/**
 * {@link ConvexPolytope}, {@link Sat}, {@link ConvexShapes} and {@link Gjk}. Each result is checked against an independent computation: analytic formulas for spheres, boxes and
 * capsules; for polytopes a brute-force distance (every vertex against every face of the other polytope, every edge against every edge) and the exact penetration depth from
 * the separating axis test; and for the contact points a distance query that must report them on the shapes.
 */
class CollisionTest {

    private final SplittableRandom r = new SplittableRandom(Rnd.SEED);
    private final Gjk gjk = new Gjk();
    private final Gjk.Result res = new Gjk.Result();

    // ---------------------------------------------------------------- generators and oracles

    private ConvexPolytope randomPolytope(int n, double radius, double cx, double cy, double cz) {
        float[] p = new float[3 * n];
        for (int i = 0; i < n; i++) {
            double[] g = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()};
            double len = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]) / Math.cbrt(r.nextDouble());
            p[3 * i] = (float) (cx + g[0] / len * radius);
            p[3 * i + 1] = (float) (cy + g[1] / len * radius);
            p[3 * i + 2] = (float) (cz + g[2] / len * radius);
        }
        return ConvexPolytope.of(p, n);
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
            return add(a, mul(ab, d1 / (d1 - d3)));
        }
        double[] cp = sub(p, c);
        double d5 = dot(ab, cp), d6 = dot(ac, cp);
        if (d6 >= 0 && d5 <= d6) {
            return c;
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            return add(a, mul(ac, d2 / (d2 - d6)));
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            return add(b, mul(sub(c, b), (d4 - d3) / ((d4 - d3) + (d5 - d6))));
        }
        double denom = 1.0 / (va + vb + vc);
        return add(a, add(mul(ab, vb * denom), mul(ac, vc * denom)));
    }

    /** The squared distance between two segments (Ericson 5.1.9). */
    private static double segmentSegment2(double[] p1, double[] q1, double[] p2, double[] q2) {
        double[] d1 = sub(q1, p1), d2 = sub(q2, p2), rr = sub(p1, p2);
        double a = dot(d1, d1), e = dot(d2, d2), f = dot(d2, rr);
        double s, t;
        if (a <= 1e-30 && e <= 1e-30) {
            return dot(rr, rr);
        }
        if (a <= 1e-30) {
            s = 0;
            t = Math.max(0, Math.min(1, f / e));
        } else {
            double c = dot(d1, rr);
            if (e <= 1e-30) {
                t = 0;
                s = Math.max(0, Math.min(1, -c / a));
            } else {
                double b = dot(d1, d2), denom = a * e - b * b;
                s = denom != 0 ? Math.max(0, Math.min(1, (b * f - c * e) / denom)) : 0;
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = Math.max(0, Math.min(1, -c / a));
                } else if (t > 1) {
                    t = 1;
                    s = Math.max(0, Math.min(1, (b - c) / a));
                }
            }
        }
        double[] c1 = add(p1, mul(d1, s)), c2 = add(p2, mul(d2, t));
        double[] d = sub(c1, c2);
        return dot(d, d);
    }

    private static double[] v(ConvexPolytope p, int i) {
        float[] a = p.vertices();
        return new double[] {a[3 * i], a[3 * i + 1], a[3 * i + 2]};
    }

    /** The distance between two disjoint polytopes, by brute force over all vertex-face and edge-edge pairs. */
    private static double bruteForceDistance(ConvexPolytope a, ConvexPolytope b) {
        double best = Double.POSITIVE_INFINITY;
        for (int pass = 0; pass < 2; pass++) {
            ConvexPolytope pts = pass == 0 ? a : b, faces = pass == 0 ? b : a;
            int[] t = faces.triangles();
            for (int i = 0; i < pts.vertexCount(); i++) {
                double[] p = v(pts, i);
                for (int f = 0; f < t.length; f += 3) {
                    double[] q = closestOnTriangle(p, v(faces, t[f]), v(faces, t[f + 1]), v(faces, t[f + 2]));
                    double[] d = sub(p, q);
                    best = Math.min(best, dot(d, d));
                }
            }
        }
        int[] ta = a.triangles(), tb = b.triangles();
        for (int i = 0; i < ta.length; i++) {
            for (int j = 0; j < tb.length; j++) {
                best = Math.min(best, segmentSegment2(v(a, ta[i]), v(a, ta[i - i % 3 + (i % 3 + 1) % 3]), v(b, tb[j]), v(b, tb[j - j % 3 + (j % 3 + 1) % 3])));
            }
        }
        return Math.sqrt(best);
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] mul(double[] a, double s) {
        return new double[] {a[0] * s, a[1] * s, a[2] * s};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    // ---------------------------------------------------------------- polytopes and SAT

    @Test
    void aBoxAsAPolytope() {
        ConvexPolytope box = ConvexPolytope.of(new Aabbf(0, 0, 0, 2, 3, 4));
        assertEquals(8, box.vertexCount());
        assertEquals(12, box.triangles().length / 3);
        assertEquals(24.0, box.volume(), 1e-9);
        assertEquals(3, box.faceDirectionCount(), "a box has three face directions however its facets are triangulated");
        assertEquals(3, box.edgeDirections().length / 3, "and three edge directions");
        assertTrue(box.contains(1, 1, 1) && box.contains(0, 0, 0) && box.contains(2, 3, 4) && box.contains(1, 3, 2));
        assertFalse(box.contains(2.0001f, 1, 1));
        assertFalse(box.contains(-0.0001f, 1, 1));
        ConvexPolytope moved = box.transformed(Quatf.fromAxisAngle(0.7f, new Vec3f(1, 2, 3)), new Vec3f(5, -1, 2));
        assertEquals(24.0, moved.volume(), 1e-4);
        assertEquals(3, moved.faceDirectionCount());
        assertThrows(IllegalArgumentException.class, () -> ConvexPolytope.of(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0}, 4), "four coplanar points have no volume");
        assertThrows(IllegalArgumentException.class, () -> ConvexPolytope.of(new float[] {0, 0, 0, 1, 1, 1}, 2));
    }

    @Test
    void satOnBoxesIsExact() {
        ConvexPolytope a = ConvexPolytope.of(new Aabbf(0, 0, 0, 2, 2, 2));
        double[] axis = new double[3];
        // apart along x by 1.5, y overlapping: the best axis is x, positive separation, pointing from a to b
        ConvexPolytope b = ConvexPolytope.of(new Aabbf(3.5f, 0.5f, 0.5f, 5, 2.5f, 2.5f));
        assertEquals(1.5, Sat.separation(a, b, axis), 1e-6);
        assertEquals(1.0, axis[0], 1e-9);
        assertFalse(Sat.intersects(a, b));
        // overlapping by 0.5 in x, 1.5 in y and z: the depth is the smallest overlap, 0.5, and moving b along the axis frees them
        ConvexPolytope c = ConvexPolytope.of(new Aabbf(1.5f, 0.5f, 0.5f, 3.5f, 3.5f, 3.5f));
        double s = Sat.separation(a, c, axis);
        assertEquals(-0.5, s, 1e-6);
        assertEquals(1.0, axis[0], 1e-9);
        assertTrue(Sat.intersects(a, c));
        assertTrue(Sat.intersects(a, ConvexPolytope.of(new Aabbf(2, 0, 0, 3, 2, 2))), "touching counts as overlapping");
    }

    // ---------------------------------------------------------------- analytic shapes

    @Test
    void spheres() {
        for (int i = 0; i < 300; i++) {
            double r1 = 0.1 + r.nextDouble() * 2, r2 = 0.1 + r.nextDouble() * 2;
            double[] c1 = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()}, c2 = {r.nextGaussian() * 3, r.nextGaussian() * 3, r.nextGaussian() * 3};
            ConvexShape a = ConvexShapes.sphere(c1[0], c1[1], c1[2], r1), b = ConvexShapes.sphere(c2[0], c2[1], c2[2], r2);
            double d = len(sub(c2, c1));
            double gap = d - r1 - r2;
            if (Math.abs(gap) < 1e-3) {
                continue;
            }
            double got = gjk.distance(a, b, res);
            assertEquals(Math.max(gap, 0), got, 1e-6, "sphere distance");
            assertEquals(gap <= 0, gjk.intersects(a, b));
            if (gap > 0) {
                assertEquals(gap, len(sub(new double[] {res.pointB[0], res.pointB[1], res.pointB[2]}, new double[] {res.pointA[0], res.pointA[1], res.pointA[2]})), 1e-6);
                for (int k = 0; k < 3; k++) {
                    assertEquals((c2[k] - c1[k]) / d, res.normal[k], 1e-6, "the normal points from the first sphere to the second");
                }
            } else {
                assertTrue(gjk.penetration(a, b, res));
                assertEquals(-gap, res.depth, 1e-3, "sphere depth (EPA converges slowly on smooth shapes that are almost concentric)");
                for (int k = 0; k < 3; k++) {
                    assertEquals((c2[k] - c1[k]) / d, res.normal[k], 2e-3, "the normal of a smooth shape converges more slowly than the depth");
                    assertEquals(res.normal[k] * res.depth, res.pointA[k] - res.pointB[k], 1e-5, "pointA - pointB = normal * depth");
                }
            }
        }
    }

    @Test
    void axisAlignedBoxesMatchTheFormulas() {
        for (int i = 0; i < 400; i++) {
            Aabbf a = Aabbf.fromCenterHalfExtent(new Vec3f(0, 0, 0), new Vec3f((float) (0.2 + r.nextDouble()), (float) (0.2 + r.nextDouble()), (float) (0.2 + r.nextDouble())));
            Aabbf b = Aabbf.fromCenterHalfExtent(new Vec3f((float) (r.nextGaussian() * 1.5), (float) (r.nextGaussian() * 1.5), (float) (r.nextGaussian() * 1.5)),
                    new Vec3f((float) (0.2 + r.nextDouble()), (float) (0.2 + r.nextDouble()), (float) (0.2 + r.nextDouble())));
            double[] gaps = {Math.max(a.minX() - b.maxX(), b.minX() - a.maxX()), Math.max(a.minY() - b.maxY(), b.minY() - a.maxY()), Math.max(a.minZ() - b.maxZ(), b.minZ() - a.maxZ())};
            double sumsq = 0;
            for (double g : gaps) {
                sumsq += g > 0 ? g * g : 0;
            }
            double expected = Math.sqrt(sumsq);
            double got = gjk.distance(ConvexShapes.of(a), ConvexShapes.of(b), res);
            assertEquals(expected, got, 1e-5, "box distance, case " + i);
            if (expected == 0 && Math.max(gaps[0], Math.max(gaps[1], gaps[2])) < -1e-3) {
                // overlapping: the depth is the smallest overlap along an axis, the normal that axis (towards b)
                assertTrue(gjk.penetration(ConvexShapes.of(a), ConvexShapes.of(b), res));
                int axis = gaps[0] >= gaps[1] && gaps[0] >= gaps[2] ? 0 : gaps[1] >= gaps[2] ? 1 : 2;
                if (Math.abs(gaps[axis] - sortedSecond(gaps)) > 1e-3) {
                    assertEquals(-gaps[axis], res.depth, 1e-5, "box depth, case " + i);
                    double centreB = axis == 0 ? (b.minX() + b.maxX()) / 2 : axis == 1 ? (b.minY() + b.maxY()) / 2 : (b.minZ() + b.maxZ()) / 2;
                    double centreA = axis == 0 ? (a.minX() + a.maxX()) / 2 : axis == 1 ? (a.minY() + a.maxY()) / 2 : (a.minZ() + a.maxZ()) / 2;
                    assertEquals(Math.signum(centreB - centreA), res.normal[axis], 1e-6, "the normal points towards the second box along the axis of least overlap");
                }
            }
        }
    }

    private static double sortedSecond(double[] g) {
        double[] c = g.clone();
        java.util.Arrays.sort(c);
        return c[1];
    }

    @Test
    void capsulesAndInflatedShapes() {
        for (int i = 0; i < 200; i++) {
            Capsulef cap = Capsulef.of(new Vec3f((float) r.nextGaussian(), (float) r.nextGaussian(), (float) r.nextGaussian()),
                    new Vec3f((float) r.nextGaussian(), (float) r.nextGaussian(), (float) r.nextGaussian()), (float) (0.1 + r.nextDouble() * 0.5));
            double[] c = {r.nextGaussian() * 3, r.nextGaussian() * 3, r.nextGaussian() * 3};
            double sr = 0.1 + r.nextDouble();
            double gap = Math.sqrt(segmentSegment2(new double[] {cap.ax(), cap.ay(), cap.az()}, new double[] {cap.bx(), cap.by(), cap.bz()}, c, c)) - cap.radius() - sr;
            if (Math.abs(gap) < 1e-3) {
                continue;
            }
            double got = gjk.distance(ConvexShapes.of(cap), ConvexShapes.sphere(c[0], c[1], c[2], sr), res);
            assertEquals(Math.max(gap, 0), got, 1e-5, "capsule against sphere");
            // a point inflated by the radius is the sphere
            double viaInflate = gjk.distance(ConvexShapes.of(cap), ConvexShapes.inflated(ConvexShapes.sphere(c[0], c[1], c[2], 0), sr), res);
            assertEquals(got, viaInflate, 1e-9);
            // moving and rotating a shape moves and rotates its distance query
            Quatf q = Quatf.fromAxisAngle((float) (r.nextDouble() * 6), new Vec3f(1, 2, 3));
            ConvexShape moved = ConvexShapes.translated(ConvexShapes.sphere(0, 0, 0, sr), c[0], c[1], c[2]);
            assertEquals(got, gjk.distance(ConvexShapes.of(cap), moved, res), 1e-9);
            Vec3f rotated = q.transform(new Vec3f((float) c[0], (float) c[1], (float) c[2]));
            ConvexShape turned = ConvexShapes.transformed(ConvexShapes.sphere(c[0], c[1], c[2], sr), q, 0, 0, 0);
            double[] out = new double[3];
            turned.support(1, 0, 0, out);
            assertEquals(rotated.x() + sr, out[0], 1e-5, "a rotated sphere is centred on the rotated centre");
        }
        assertThrows(IllegalArgumentException.class, () -> ConvexShapes.sphere(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ConvexShapes.inflated(ConvexShapes.sphere(0, 0, 0, 1), -0.1));
    }

    // ---------------------------------------------------------------- polytopes through GJK and EPA

    @Test
    void distanceBetweenPolytopesMatchesBruteForce() {
        int cases = Math.max(150, Rnd.N / 20);
        int separated = 0;
        for (int i = 0; i < cases; i++) {
            ConvexPolytope a = randomPolytope(4 + r.nextInt(40), 1 + r.nextDouble(), 0, 0, 0);
            ConvexPolytope b = randomPolytope(4 + r.nextInt(40), 0.5 + r.nextDouble(), r.nextGaussian() * 3, r.nextGaussian() * 3, r.nextGaussian() * 3);
            double[] axis = new double[3];
            double sep = Sat.separation(a, b, axis);
            if (Math.abs(sep) < 1e-3) {
                continue;
            }
            double got = gjk.distance(a, b, res);
            if (sep > 0) {
                separated++;
                double expected = bruteForceDistance(a, b);
                assertEquals(expected, got, 1e-6 * Math.max(1, expected), "distance, case " + i);
                // the closest points are on the polytopes
                double[] pa = {res.pointA[0], res.pointA[1], res.pointA[2]}, pb = {res.pointB[0], res.pointB[1], res.pointB[2]};
                assertEquals(expected, len(sub(pb, pa)), 1e-6 * Math.max(1, expected));
                assertEquals(0.0, gjk.distance(a, ConvexShapes.sphere(pa[0], pa[1], pa[2], 0), new Gjk.Result()), 1e-6, "pointA is on the first polytope");
                assertEquals(0.0, gjk.distance(b, ConvexShapes.sphere(pb[0], pb[1], pb[2], 0), new Gjk.Result()), 1e-6, "pointB is on the second polytope");
                assertTrue(got >= sep - 1e-9, "the distance is at least the best separating gap");
            } else {
                assertEquals(0.0, got, 1e-9, "overlapping polytopes have distance 0");
            }
            assertEquals(sep <= 0, gjk.intersects(a, b), "overlap test against SAT, case " + i);
        }
        assertTrue(separated > cases / 5, "enough separated cases: " + separated);
        Report.printf("Collision: %d separated polytope pairs checked against brute force%n", separated);
    }

    @Test
    void penetrationMatchesTheSeparatingAxisDepth() {
        int cases = Math.max(150, Rnd.N / 20);
        int checked = 0;
        for (int i = 0; i < cases; i++) {
            ConvexPolytope a = randomPolytope(4 + r.nextInt(30), 1 + r.nextDouble(), 0, 0, 0);
            ConvexPolytope b = randomPolytope(4 + r.nextInt(30), 0.5 + r.nextDouble(), r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
            double[] axis = new double[3];
            double sep = Sat.separation(a, b, axis);
            if (sep > -1e-3) {
                continue;
            }
            assertTrue(gjk.penetration(a, b, res), "overlapping polytopes, case " + i);
            checked++;
            assertEquals(-sep, res.depth, 1e-6 * Math.max(1, -sep), "the depth is the smallest overlap, case " + i);
            double nl = Math.sqrt(res.normal[0] * res.normal[0] + res.normal[1] * res.normal[1] + res.normal[2] * res.normal[2]);
            assertEquals(1.0, nl, 1e-9);
            for (int k = 0; k < 3; k++) {
                assertEquals(res.normal[k] * res.depth, res.pointA[k] - res.pointB[k], 1e-6, "pointA - pointB = normal * depth");
            }
            // moving b along the normal by the depth (plus a hair) separates them; by less than the depth does not
            ConvexShape freed = ConvexShapes.translated(b, res.normal[0] * (res.depth + 1e-6), res.normal[1] * (res.depth + 1e-6), res.normal[2] * (res.depth + 1e-6));
            Gjk.Result after = new Gjk.Result();
            assertTrue(!new Gjk().penetration(a, freed, after) || after.depth < 1e-4, "moved by the depth they are free (touching at most)");
            ConvexShape notEnough = ConvexShapes.translated(b, res.normal[0] * res.depth * 0.9, res.normal[1] * res.depth * 0.9, res.normal[2] * res.depth * 0.9);
            assertTrue(new Gjk().intersects(a, notEnough), "moved by less than the depth they still overlap");
            // the contact points are on the shapes
            assertEquals(0.0, gjk.distance(a, ConvexShapes.sphere(res.pointA[0], res.pointA[1], res.pointA[2], 0), new Gjk.Result()), 1e-5, "pointA is on the first polytope");
        }
        assertTrue(checked > cases / 5, "enough overlapping cases: " + checked);
    }

    @Test
    void orientedBoxesAgreeWithTheirPolytopes() {
        for (int i = 0; i < 300; i++) {
            Quatf qa = Quatf.fromAxisAngle((float) (r.nextDouble() * 6), new Vec3f((float) r.nextGaussian(), (float) r.nextGaussian(), (float) r.nextGaussian()));
            Quatf qb = Quatf.fromAxisAngle((float) (r.nextDouble() * 6), new Vec3f((float) r.nextGaussian(), (float) r.nextGaussian(), (float) r.nextGaussian()));
            Obbf a = Obbf.of(new Vec3f(0, 0, 0), new Vec3f((float) (0.3 + r.nextDouble()), (float) (0.3 + r.nextDouble()), (float) (0.3 + r.nextDouble())), qa);
            Obbf b = Obbf.of(new Vec3f((float) (r.nextGaussian() * 1.5), (float) (r.nextGaussian() * 1.5), (float) (r.nextGaussian() * 1.5)),
                    new Vec3f((float) (0.3 + r.nextDouble()), (float) (0.3 + r.nextDouble()), (float) (0.3 + r.nextDouble())), qb);
            ConvexPolytope pa = ConvexPolytope.of(corners(a), 8), pb = ConvexPolytope.of(corners(b), 8);
            double[] axis = new double[3];
            double sep = Sat.separation(pa, pb, axis);
            if (Math.abs(sep) < 1e-3) {
                continue;
            }
            double viaShapes = gjk.distance(ConvexShapes.of(a), ConvexShapes.of(b), res);
            double viaPolytopes = gjk.distance(pa, pb, res);
            assertEquals(viaPolytopes, viaShapes, 1e-5, "the Obb support function and the hull of its corners agree");
            assertEquals(sep <= 0, gjk.intersects(ConvexShapes.of(a), ConvexShapes.of(b)));
            if (sep < 0) {
                assertTrue(gjk.penetration(ConvexShapes.of(a), ConvexShapes.of(b), res));
                assertEquals(-sep, res.depth, 1e-5, "box depth against the separating axis test");
            }
        }
    }

    private static float[] corners(Obbf b) {
        float[] c = new float[24];
        for (int i = 0; i < 8; i++) {
            Vec3f local = new Vec3f((i & 1) == 0 ? -b.hx() : b.hx(), (i & 2) == 0 ? -b.hy() : b.hy(), (i & 4) == 0 ? -b.hz() : b.hz());
            Vec3f w = b.rotation().transform(local).add(b.center());
            c[3 * i] = w.x();
            c[3 * i + 1] = w.y();
            c[3 * i + 2] = w.z();
        }
        return c;
    }

    @Test
    void touchingAndIdenticalShapes() {
        ConvexShape unit = ConvexShapes.of(new Aabbf(0, 0, 0, 1, 1, 1));
        assertTrue(gjk.intersects(unit, unit));
        assertTrue(gjk.penetration(unit, unit, res));
        assertEquals(1.0, res.depth, 1e-6, "a shape against itself: the depth is its smallest width");
        ConvexShape touching = ConvexShapes.of(new Aabbf(1, 0, 0, 2, 1, 1));
        assertTrue(gjk.intersects(unit, touching), "face contact counts as overlap");
        assertTrue(gjk.penetration(unit, touching, res));
        assertEquals(0.0, res.depth, 1e-6);
        ConvexShape apart = ConvexShapes.of(new Aabbf(1.001f, 0, 0, 2, 1, 1));
        assertFalse(gjk.intersects(unit, apart));
        assertEquals(0.001, gjk.distance(unit, apart, res), 1e-6);
        ConvexShape point = ConvexShapes.sphere(0.5, 0.5, 0.5, 0);
        assertTrue(gjk.penetration(unit, point, res));
        assertEquals(0.5, res.depth, 1e-6, "a point at the centre of a unit cube is 0.5 from the nearest face");
        ConvexShape cloud = ConvexShapes.points(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1}, 4);
        assertEquals(0.0, gjk.distance(cloud, ConvexShapes.sphere(0.1, 0.1, 0.1, 0), res), 1e-9);
        assertTrue(gjk.distance(cloud, ConvexShapes.sphere(2, 2, 2, 0), res) > 1.0);
    }
}
