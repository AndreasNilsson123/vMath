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

/**
 * {@link ManifoldBuilder#boxes} against {@link ManifoldBuilder#polytopes} on the same boxes as polytopes, which is the oracle (PERF-2): the same
 * answer to "do they touch", the same normal, and the same contact points.
 */
class BoxBoxTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);
    private final ManifoldBuilder builder = new ManifoldBuilder();
    private final ManifoldBuilder oracleBuilder = new ManifoldBuilder();

    private record Pose(float hx, float hy, float hz, Quatf q, Vec3f t) {
        ConvexPolytope polytope() {
            return ConvexPolytope.of(new Aabbf(-hx, -hy, -hz, hx, hy, hz)).transformed(q, t);
        }

        OrientedBox box() {
            return new OrientedBox().set(t.x(), t.y(), t.z(), q.x(), q.y(), q.z(), q.w(), hx, hy, hz);
        }
    }

    private Pose randomPose(double spread) {
        Quatf q = new Quatf((float) rng.nextDouble(-1, 1), (float) rng.nextDouble(-1, 1), (float) rng.nextDouble(-1, 1), (float) rng.nextDouble(-1, 1));
        q = q.normalize();
        return new Pose((float) rng.nextDouble(0.2, 1.2), (float) rng.nextDouble(0.2, 1.2), (float) rng.nextDouble(0.2, 1.2), q,
                new Vec3f((float) rng.nextDouble(-spread, spread), (float) rng.nextDouble(-spread, spread), (float) rng.nextDouble(-spread, spread)));
    }

    private static boolean samePoint(ContactManifold m, int i, ContactManifold o, int k, double tolerance) {
        for (int c = 0; c < 3; c++) {
            if (Math.abs(m.pointA(i, c) - o.pointA(k, c)) > tolerance || Math.abs(m.pointB(i, c) - o.pointB(k, c)) > tolerance) {
                return false;
            }
        }
        return Math.abs(m.depth(i) - o.depth(k)) <= tolerance;
    }

    /** Whether every point of {@code m} is a point of {@code o} and the other way round. */
    private static boolean sameSet(ContactManifold m, ContactManifold o, double tolerance) {
        if (m.count() != o.count()) {
            return false;
        }
        for (int i = 0; i < m.count(); i++) {
            boolean found = false;
            for (int k = 0; k < o.count() && !found; k++) {
                found = samePoint(m, i, o, k, tolerance);
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    @Test
    void theBoxPathGivesTheContactsOfThePolytopePathOnRandomPoses() {
        int touching = 0, disagree = 0, differentSet = 0, total = 0;
        StringBuilder log = new StringBuilder();
        for (double margin : new double[] {0.0, 0.02, 0.2}) {
            for (int trial = 0; trial < 6000; trial++) {
                Pose pa = randomPose(trial % 3 == 0 ? 3.0 : 1.6), pb = randomPose(trial % 3 == 0 ? 3.0 : 1.6);
                ContactManifold mine = new ContactManifold(), oracle = new ContactManifold();
                boolean got = builder.boxes(pa.box(), pb.box(), margin, mine);
                boolean expected = oracleBuilder.polytopes(pa.polytope(), pb.polytope(), margin, oracle);
                total++;
                if (got != expected) {
                    disagree++;
                    log.append("touching differs, margin ").append(margin).append(": ").append(pa).append(" / ").append(pb).append('\n');
                    continue;
                }
                if (!got) {
                    continue;
                }
                touching++;
                double dot = mine.nx * oracle.nx + mine.ny * oracle.ny + mine.nz * oracle.nz;
                assertTrue(dot > 1 - 1e-5, "the normals differ: " + dot + " " + pa + " / " + pb);
                if (!sameSet(mine, oracle, 5e-4)) {
                    differentSet++;
                    if (differentSet < 5) {
                        log.append("points differ (").append(mine.count()).append(" against ").append(oracle.count()).append("): ").append(pa).append(" / ").append(pb).append('\n');
                    }
                }
            }
        }
        if (Boolean.getBoolean("vmath.verbose")) {
            System.out.println("BoxBoxTest: " + total + " pairs, " + touching + " touching, " + disagree + " disagree about touching, " + differentSet + " with other points");
        }
        assertTrue(touching > 3000, "the poses must touch often enough to mean something: " + touching + " of " + total);
        // The two paths round differently (double against float vertices). The points that differ are the third and fourth point of a reduction of five or more
        // candidates to four, where the candidates are the same and two of them lie at nearly the same distance from the line that the reduction measures from
        // (a tie of 1e-4 in the area decides which is taken); a pose on a boundary may also differ. Random poses must almost never.
        assertTrue(disagree <= 5, disagree + " of " + total + " disagree about touching:\n" + log);
        assertTrue(differentSet <= touching / 100, differentSet + " of " + touching + " touching pairs have other points:\n" + log);
    }

    @Test
    void aBoxRestingOnABoxMakesFourPointsAndWarmStartsByIds() {
        OrientedBox ground = new OrientedBox().set(0, -0.5, 0, 0, 0, 0, 1, 5, 0.5, 5);
        OrientedBox cube = new OrientedBox().set(0.3, 0.495, -0.2, 0, 0, 0, 1, 0.5, 0.5, 0.5);
        ContactManifold m = new ContactManifold();
        assertTrue(builder.boxes(ground, cube, 0.02, m));
        assertEquals(4, m.count());
        assertEquals(0.0, m.nx, 1e-12);
        assertEquals(1.0, m.ny, 1e-12);
        int[] ids = new int[4];
        for (int i = 0; i < 4; i++) {
            assertEquals(0.005, m.depth(i), 1e-9);
            assertEquals(0.0, m.pointA(i, 1), 1e-9, "on the top of the ground");
            ids[i] = m.id(i);
        }
        // a small move keeps the ids, so that the impulses can be carried over
        OrientedBox moved = new OrientedBox().set(0.31, 0.494, -0.19, 0, 0, 0, 1, 0.5, 0.5, 0.5);
        ContactManifold next = new ContactManifold();
        assertTrue(builder.boxes(ground, moved, 0.02, next));
        assertEquals(4, next.count());
        for (int i = 0; i < 4; i++) {
            boolean found = false;
            for (int k = 0; k < 4; k++) {
                found |= next.id(k) == ids[i];
            }
            assertTrue(found, "the contact id " + ids[i] + " is gone after a small move");
        }
        for (int i = 0; i < 4; i++) {
            m.setNormalImpulse(i, 1.0 + i);
        }
        next.warmStartFrom(m, 0.05);
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            sum += next.normalImpulse(i);
        }
        assertEquals(10.0, sum, 1e-12, "every impulse was carried over");
    }

    @Test
    void anEdgeOnAnEdgeAndACornerOnAFaceMakeOnePointAndTheNormalPointsFromAToB() {
        // two long bars crossing at right angles, B above A and turned 90 degrees about y: the edges that touch cross
        OrientedBox a = new OrientedBox().set(0, 0, 0, 0, 0, 0, 1, 2, 0.5, 0.3);
        double s = Math.sin(Math.PI / 4);
        OrientedBox b = new OrientedBox().set(0, 0.79, 0, 0, s, 0, s, 2, 0.5, 0.3);
        ContactManifold m = new ContactManifold();
        assertTrue(builder.boxes(a, b, 0.0, m));
        assertTrue(m.ny > 0.99, "B is above A: " + m.ny);
        assertTrue(m.count() >= 1);
        // a cube turned onto its corner on a plane: one point
        double t = Math.atan(Math.sqrt(2)) / 2; // half the angle between the cube's diagonal and an axis
        OrientedBox floor = new OrientedBox().set(0, -1, 0, 0, 0, 0, 1, 5, 1, 5);
        OrientedBox corner = new OrientedBox().set(0, Math.sqrt(3) * 0.5 - 0.01, 0, Math.sin(t) * Math.sqrt(0.5), 0, -Math.sin(t) * Math.sqrt(0.5), Math.cos(t), 0.5, 0.5, 0.5);
        ContactManifold c = new ContactManifold();
        assertTrue(builder.boxes(floor, corner, 0.0, c));
        assertEquals(1, c.count(), "a corner on a face");
        assertEquals(0.01, c.depth(0), 5e-3);
    }

    @Test
    void separatedBoxesNaNAndBadArgumentsGiveNoContactOrAnException() {
        OrientedBox a = new OrientedBox().set(0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        ContactManifold m = new ContactManifold();
        assertFalse(builder.boxes(a, new OrientedBox().set(0, 3, 0, 0, 0, 0, 1, 1, 1, 1), 0.5, m), "1 apart and a margin of 0.5");
        assertEquals(0, m.count());
        assertTrue(builder.boxes(a, new OrientedBox().set(0, 2.3, 0, 0, 0, 0, 1, 1, 1, 1), 0.5, m), "0.3 apart and a margin of 0.5");
        assertEquals(1, m.count() > 0 ? 1 : 0);
        assertTrue(m.depth(0) < 0, "speculative: a negative depth");
        assertFalse(builder.boxes(a, new OrientedBox().set(Double.NaN, 0, 0, 0, 0, 0, 1, 1, 1, 1), 0.5, m));
        assertThrows(IllegalArgumentException.class, () -> builder.boxes(a, a, -1.0, m));
        assertThrows(IllegalArgumentException.class, () -> new OrientedBox().set(0, 0, 0, 0, 0, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrientedBox().set(0, 0, 0, 0, 0, 0, 1, -1, 1, 1));
        assertEquals(0.5, new OrientedBox().halfExtent(2), 0.0);
        assertThrows(IllegalArgumentException.class, () -> new OrientedBox().center(3));
        assertThrows(IllegalArgumentException.class, () -> new OrientedBox().axis(0, 3));
    }

    @Test
    void theBuilderAllocatesNothingForBoxes() {
        OrientedBox a = new OrientedBox().set(0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        OrientedBox b = new OrientedBox().set(0.2, 1.9, 0.1, 0.1, 0.2, 0.3, 0.9, 0.8, 0.7, 0.9);
        ContactManifold m = new ContactManifold();
        for (int i = 0; i < 20000; i++) { // warm up
            builder.boxes(a, b, 0.02, m);
        }
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 10000; i++) {
            builder.boxes(a, b, 0.02, m);
        }
        long perCall = (mx.getThreadAllocatedBytes(id) - before) / 10000;
        assertTrue(perCall < 16, "allocated " + perCall + " B per call");
    }
}
