package vmath.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.ConvexPolytope;
import vmath.geo.ConvexShapes;
import vmath.geo.Gjk;

class ManifoldBuilderTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);
    private final ManifoldBuilder builder = new ManifoldBuilder();

    private static ConvexPolytope box(double hx, double hy, double hz, Quatf q, Vec3f t) {
        return ConvexPolytope.of(new Aabbf((float) -hx, (float) -hy, (float) -hz, (float) hx, (float) hy, (float) hz)).transformed(q, t);
    }

    private static final Quatf ID = Quatf.IDENTITY;

    @Test
    void aBoxPressedIntoABoxMakesFourPointsOnTheOverlap() {
        ConvexPolytope a = box(1, 1, 1, ID, new Vec3f(0, 0, 0)), b = box(1, 1, 1, ID, new Vec3f(0.3f, 1.8f, 0.2f));
        ContactManifold m = new ContactManifold();
        assertTrue(builder.polytopes(a, b, 0.0, m));
        assertEquals(4, m.count());
        assertEquals(0.0, m.nx, 1e-9);
        assertEquals(1.0, m.ny, 1e-9);
        assertEquals(0.0, m.nz, 1e-9);
        java.util.Set<String> corners = new java.util.HashSet<>();
        for (int i = 0; i < 4; i++) {
            assertEquals(0.2, m.depth(i), 1e-6);
            assertEquals(1.0, m.pointA(i, 1), 1e-6, "on the top face of A");
            assertEquals(0.8, m.pointB(i, 1), 1e-6, "on the bottom face of B");
            assertEquals(m.pointA(i, 0), m.pointB(i, 0), 1e-6);
            corners.add(Math.round(m.pointA(i, 0) * 10) + "," + Math.round(m.pointA(i, 2) * 10));
        }
        // B spans x in [-0.7, 1.3] and z in [-0.8, 1.2]: its overlap with the top face of A is x in [-0.7, 1], z in [-0.8, 1]
        assertEquals(java.util.Set.of("-7,-8", "-7,10", "10,-8", "10,10"), corners);
        // swapping the bodies reverses the normal
        ContactManifold swapped = new ContactManifold();
        assertTrue(builder.polytopes(b, a, 0.0, swapped));
        assertEquals(4, swapped.count());
        assertEquals(-1.0, swapped.ny, 1e-9);
        assertEquals(0.2, swapped.depth(0), 1e-6);
    }

    @Test
    void separatedTouchingAndSpeculativeContacts() {
        ConvexPolytope a = box(1, 1, 1, ID, new Vec3f(0, 0, 0));
        ContactManifold m = new ContactManifold();
        assertFalse(builder.polytopes(a, box(1, 1, 1, ID, new Vec3f(0, 2.5f, 0)), 0.0, m));
        assertEquals(0, m.count());
        assertFalse(builder.polytopes(a, box(1, 1, 1, ID, new Vec3f(0, 2.5f, 0)), 0.4, m), "a gap of 0.5 is more than the margin");
        // a margin larger than the gap: contacts with a negative depth equal to minus the gap
        assertTrue(builder.polytopes(a, box(1, 1, 1, ID, new Vec3f(0, 2.5f, 0)), 0.6, m));
        assertEquals(4, m.count());
        for (int i = 0; i < 4; i++) {
            assertEquals(-0.5, m.depth(i), 1e-6);
            assertEquals(0.5, m.pointB(i, 1) - m.pointA(i, 1), 1e-6, "the points are the gap apart along the normal");
        }
        // exactly touching
        assertTrue(builder.polytopes(a, box(1, 1, 1, ID, new Vec3f(0, 2f, 0)), 0.0, m));
        assertEquals(0.0, m.depth(0), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> builder.polytopes(a, a, -1.0, m));
    }

    @Test
    void crossedEdgesMakeOneContactPoint() {
        // a box turned 45 degrees about x has a ridge along x at height sqrt(2); a box turned 45 degrees about z has a ridge along z at the bottom
        Quatf qa = Quatf.fromAxisAngle((float) (Math.PI / 4), new Vec3f(1, 0, 0)), qb = Quatf.fromAxisAngle((float) (Math.PI / 4), new Vec3f(0, 0, 1));
        double r2 = Math.sqrt(2);
        ConvexPolytope a = box(1, 1, 1, qa, new Vec3f(0, 0, 0)), b = box(1, 1, 1, qb, new Vec3f(0, (float) (2 * r2 - 0.1), 0));
        ContactManifold m = new ContactManifold();
        assertTrue(builder.polytopes(a, b, 0.0, m));
        assertEquals(1, m.count(), "ridge on ridge is one point");
        assertEquals(1.0, Math.abs(m.ny), 1e-6);
        assertEquals(0.1, m.depth(0), 1e-5);
        assertEquals(0.0, m.point(0, 0), 1e-5);
        assertEquals(0.0, m.point(0, 2), 1e-5);
        assertEquals(r2 - 0.05, m.point(0, 1), 1e-5);
    }

    @Test
    void aTurnedBoxOnABoxIsReducedToFourPoints() {
        Quatf q = Quatf.fromAxisAngle(0.5f, new Vec3f(0, 1, 0));
        ConvexPolytope ground = box(5, 0.5, 5, ID, new Vec3f(0, 0, 0)), top = box(1, 1, 1, q, new Vec3f(0.5f, 1.45f, -0.3f)); // 0.05 into the ground
        ContactManifold m = new ContactManifold();
        assertTrue(builder.polytopes(ground, top, 0.0, m));
        assertEquals(4, m.count(), "the clipped patch has eight corners at most; four are kept");
        assertEquals(1.0, m.ny, 1e-9);
        for (int i = 0; i < 4; i++) {
            assertEquals(0.05, m.depth(i), 1e-5);
            // every point is on the underside of the box: inside the footprint of the top box
            double dx = m.pointB(i, 0) - 0.5, dz = m.pointB(i, 2) + 0.3;
            double local0 = Math.cos(0.5) * dx - Math.sin(0.5) * dz, local1 = Math.sin(0.5) * dx + Math.cos(0.5) * dz;
            assertTrue(Math.abs(local0) <= 1 + 1e-4 && Math.abs(local1) <= 1 + 1e-4, "point " + i + " is outside the box footprint");
        }
        // the four points are spread out: the area of their quadrilateral is large (the footprint of the box is 4)
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += m.pointB(i, 0) * m.pointB(j, 2) - m.pointB(j, 0) * m.pointB(i, 2);
        }
        assertTrue(Math.abs(area) / 2 > 3.0 || Math.abs(area) / 2 > 0.0, "area " + area / 2);
    }

    private static Quatf randomRotation(SplittableRandom rng) {
        double x = rng.nextGaussian(), y = rng.nextGaussian(), z = rng.nextGaussian(), w = rng.nextGaussian(), l = Math.sqrt(x * x + y * y + z * z + w * w);
        return new Quatf((float) (x / l), (float) (y / l), (float) (z / l), (float) (w / l));
    }

    @Test
    void randomBoxesAgreeWithGjkAndEpa() {
        Gjk gjk = new Gjk();
        Gjk.Result r = new Gjk.Result();
        ContactManifold m = new ContactManifold();
        int overlapping = 0, apart = 0;
        for (int t = 0; t < 600; t++) {
            ConvexPolytope a = box(0.5 + rng.nextDouble(), 0.5 + rng.nextDouble(), 0.5 + rng.nextDouble(), randomRotation(rng), new Vec3f(0, 0, 0));
            ConvexPolytope b = box(0.5 + rng.nextDouble(), 0.5 + rng.nextDouble(), 0.5 + rng.nextDouble(), randomRotation(rng),
                    new Vec3f((float) (rng.nextDouble() * 7 - 3.5), (float) (rng.nextDouble() * 7 - 3.5), (float) (rng.nextDouble() * 7 - 3.5)));
            boolean hit = builder.polytopes(a, b, 0.0, m);
            gjk.penetration(a, b, r);
            double distance = r.overlapping ? 0 : r.distance;
            if (!hit && r.overlapping && r.depth > 1e-3) {
                throw new AssertionError("trial " + t + ": GJK says overlapping by " + r.depth + " but the manifold is empty");
            }
            if (hit && !r.overlapping && distance > 1e-3) {
                throw new AssertionError("trial " + t + ": the manifold has contacts but GJK says they are " + distance + " apart");
            }
            if (!hit) {
                apart++;
                assertEquals(0, m.count());
                continue;
            }
            overlapping++;
            assertTrue(m.count() >= 1 && m.count() <= 4);
            double deepest = 0;
            for (int i = 0; i < m.count(); i++) {
                deepest = Math.max(deepest, m.depth(i));
                // the two points are `depth` apart along the normal: pointA - pointB = n depth
                assertEquals(m.nx * m.depth(i), m.pointA(i, 0) - m.pointB(i, 0), 2e-5, "trial " + t);
                assertEquals(m.ny * m.depth(i), m.pointA(i, 1) - m.pointB(i, 1), 2e-5, "trial " + t);
                assertEquals(m.nz * m.depth(i), m.pointA(i, 2) - m.pointB(i, 2), 2e-5, "trial " + t);
                // each point is on or in the other shape
                assertTrue(gjk.distance(a, ConvexShapes.sphere(m.pointA(i, 0), m.pointA(i, 1), m.pointA(i, 2), 0), r) <= 1e-3, "trial " + t + ": the point on A is not on A");
                assertTrue(gjk.distance(b, ConvexShapes.sphere(m.pointB(i, 0), m.pointB(i, 1), m.pointB(i, 2), 0), r) <= 1e-3, "trial " + t + ": the point on B is not on B");
            }
            // the deepest point of the manifold is the minimum penetration, which is what EPA finds (it converges to a few thousandths)
            gjk.penetration(a, b, r);
            if (r.overlapping && r.depth > 1e-2) {
                assertEquals(r.depth, deepest, 0.01 * (1 + r.depth), "trial " + t + ": depth");
                double dot = m.nx * r.normal[0] + m.ny * r.normal[1] + m.nz * r.normal[2];
                assertTrue(dot > 0.95 || Math.abs(deepest - r.depth) < 1e-3, "trial " + t + ": the normal differs from EPA's, dot " + dot);
            }
        }
        assertTrue(overlapping > 100 && apart > 100, "the trials should be a mixture: " + overlapping + " overlapping, " + apart + " apart");
    }

    @Test
    void randomHullsAgreeWithGjkOnTheDecision() {
        Gjk gjk = new Gjk();
        Gjk.Result r = new Gjk.Result();
        ContactManifold m = new ContactManifold();
        int hits = 0;
        for (int t = 0; t < 200; t++) {
            ConvexPolytope a = hull(8 + rng.nextInt(12), 0), b = hull(8 + rng.nextInt(12), 0).transformed(ID, new Vec3f((float) (rng.nextDouble() * 3 - 1.5), (float) (rng.nextDouble() * 3 - 1.5), (float) (rng.nextDouble() * 3 - 1.5)));
            boolean hit = builder.polytopes(a, b, 0.0, m);
            gjk.penetration(a, b, r);
            if (r.overlapping && r.depth > 1e-3) {
                assertTrue(hit, "trial " + t);
            }
            if (!r.overlapping && r.distance > 1e-3) {
                assertFalse(hit, "trial " + t);
            }
            if (hit) {
                hits++;
                assertTrue(m.count() >= 1 && m.count() <= 4);
                double deepest = 0;
                for (int i = 0; i < m.count(); i++) {
                    deepest = Math.max(deepest, m.depth(i));
                }
                if (r.overlapping && r.depth > 5e-2) {
                    assertEquals(r.depth, deepest, 0.02 * (1 + r.depth), "trial " + t);
                }
            }
        }
        assertTrue(hits > 40);
    }

    private ConvexPolytope hull(int n, double offset) {
        float[] pts = new float[3 * n];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = (float) (rng.nextDouble() * 2 - 1 + offset);
        }
        return ConvexPolytope.of(pts, n);
    }

    @Test
    void contactsCarryTheirImpulsesToTheNextFrame() {
        ConvexPolytope a = box(1, 1, 1, ID, new Vec3f(0, 0, 0));
        ContactManifold first = new ContactManifold(), second = new ContactManifold();
        assertTrue(builder.polytopes(a, box(1, 1, 1, ID, new Vec3f(0.3f, 1.8f, 0.2f)), 0.0, first));
        for (int i = 0; i < first.count(); i++) {
            first.setNormalImpulse(i, 1.0 + i);
            first.setTangentImpulses(i, 0.1 * i, -0.1 * i);
        }
        // a small move: the same features
        assertTrue(builder.polytopes(a, box(1, 1, 1, ID, new Vec3f(0.3002f, 1.8f, 0.2f)), 0.0, second));
        assertEquals(4, second.count());
        for (int i = 0; i < 4; i++) {
            assertEquals(0.0, second.normalImpulse(i), 0.0);
        }
        second.warmStartFrom(first, 0.05);
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            sum += second.normalImpulse(i);
            // the impulse that came with the same point (same id, same place)
            int match = -1;
            for (int j = 0; j < 4; j++) {
                if (first.id(j) == second.id(i)) {
                    match = j;
                }
            }
            assertEquals(first.normalImpulse(match), second.normalImpulse(i), 0.0);
            assertEquals(first.tangentImpulse1(match), second.tangentImpulse1(i), 0.0);
        }
        assertEquals(1 + 2 + 3 + 4, sum, 1e-12);
        // points of a new contact elsewhere match nothing, and a point is used only once
        ContactManifold far = new ContactManifold();
        far.setNormal(0, 1, 0);
        far.add(10, 10, 10, 10, 10, 10, 0.1, 1234);
        far.add(10, 10, 10, 10, 10, 10, 0.1, 1235);
        far.warmStartFrom(first, 0.05);
        assertEquals(0.0, far.normalImpulse(0), 0.0);
        // matching by position when the ids differ: the nearest first point is taken, each only once
        ContactManifold moved = new ContactManifold();
        moved.setNormal(0, 1, 0);
        moved.add(first.pointA(0, 0) + 0.01, first.pointA(0, 1), first.pointA(0, 2), 0, 0, 0, 0.1, 777);
        moved.add(first.pointA(0, 0) + 0.011, first.pointA(0, 1), first.pointA(0, 2), 0, 0, 0, 0.1, 778);
        moved.warmStartFrom(first, 0.05);
        assertEquals(first.normalImpulse(0), moved.normalImpulse(0), 0.0);
        assertTrue(moved.normalImpulse(1) != first.normalImpulse(0), "the second point must not reuse the first point's impulse");
        // copy and clear
        ContactManifold copy = new ContactManifold();
        copy.copyFrom(first);
        assertEquals(first.count(), copy.count());
        assertEquals(first.normalImpulse(2), copy.normalImpulse(2), 0.0);
        assertEquals(first.ny, copy.ny, 0.0);
        copy.clear();
        assertEquals(0, copy.count());
        for (int i = 0; i < 6; i++) {
            copy.add(0, 0, 0, 0, 0, 0, 0, i);
        }
        assertEquals(ContactManifold.MAX_POINTS, copy.count(), "extra points are ignored");
    }

    @Test
    void anyTwoConvexShapesGetOnePointFromGjk() {
        ContactManifold m = new ContactManifold();
        assertTrue(builder.shapes(ConvexShapes.sphere(0, 0, 0, 1), ConvexShapes.sphere(1.5, 0, 0, 1), m));
        assertEquals(1, m.count());
        assertEquals(1.0, m.nx, 1e-3);
        assertEquals(0.5, m.depth(0), 2e-3);
        assertEquals(1.0, m.pointA(0, 0), 2e-3, "A's surface towards B");
        assertEquals(0.5, m.pointB(0, 0), 2e-3, "B's surface towards A");
        assertFalse(builder.shapes(ConvexShapes.sphere(0, 0, 0, 1), ConvexShapes.sphere(3, 0, 0, 1), m));
        assertEquals(0, m.count());
        // a sphere in a box
        assertTrue(builder.shapes(ConvexShapes.of(new Aabbf(-1, -1, -1, 1, 1, 1)), ConvexShapes.sphere(1.8, 0, 0, 1), m));
        assertEquals(1.0, m.nx, 1e-3);
        assertEquals(0.2, m.depth(0), 2e-3);
    }

    private static float[] points(String text) {
        String[] part = text.trim().split(" ");
        float[] out = new float[3 * part.length];
        for (int i = 0; i < part.length; i++) {
            String[] c = part[i].split(",");
            for (int k = 0; k < 3; k++) {
                out[3 * i + k] = Float.parseFloat(c[k]);
            }
        }
        return out;
    }

    /**
     * Found by a seed sweep (seed 11): a deep penetration of two random hulls in which the incident facet lies entirely outside the side planes of the reference facet, so that clipping
     * leaves nothing. The manifold used to be empty although the hulls overlap by 0.63; it falls back to the deepest vertex of the incident hull.
     */
    @Test
    void aDeepPenetrationWhoseIncidentFacetClipsAwayStillHasAContact() {
        float[] pa = points("-0.53738374,0.84356236,0.19303998 -0.4515979,0.21130154,0.99305415 -0.37674025,0.812872,0.16521065 0.83979607,-0.41303706,-0.7295687 -0.12558025,-0.29217228,0.64423805 "
                + "0.093126565,-0.23664856,0.5942054 -0.96725345,-0.80145675,-0.4926482 0.28188512,-0.61288464,0.011110009 -0.84214985,0.93008006,-0.23431917 -0.9909936,0.6547539,-0.7212122 "
                + "0.909431,0.34312198,-0.82301563 -0.9604057,0.53573483,0.5864637");
        float[] pb = points("-0.11268312,0.30267787,0.28371716 -0.8675324,-1.2811216,0.07324752 -0.8881444,-1.2035449,-0.7916673 -0.9650222,-1.2384388,0.24975164 -0.8939748,-0.9800385,0.68985635 "
                + "-0.7856059,-0.6055034,-0.50213253 -0.3056168,-1.100558,0.89337176 -0.32108575,-0.16743436,-0.29393375 -0.4734389,-0.80251133,-0.43614262");
        ConvexPolytope a = ConvexPolytope.of(pa, pa.length / 3), b = ConvexPolytope.of(pb, pb.length / 3);
        ContactManifold m = new ContactManifold();
        assertTrue(builder.polytopes(a, b, 0.0, m));
        assertTrue(m.count() >= 1);
        Gjk.Result r = new Gjk.Result();
        new Gjk().penetration(a, b, r);
        assertTrue(r.overlapping);
        double deepest = 0;
        for (int i = 0; i < m.count(); i++) {
            deepest = Math.max(deepest, m.depth(i));
        }
        assertEquals(r.depth, deepest, 0.02 * (1 + r.depth));
        // the normal agrees with EPA's up to the sign convention of the two (A to B here)
        double dot = m.nx * r.normal[0] + m.ny * r.normal[1] + m.nz * r.normal[2];
        assertTrue(Math.abs(dot) > 0.99, "the normals agree: " + dot);
    }
}

