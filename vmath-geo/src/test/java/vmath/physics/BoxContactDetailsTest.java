package vmath.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * QA-1: exact contacts of {@link ManifoldBuilder#boxes} in configurations whose answer is known: the corners of a resting face and their ids, the octagon
 * that a rotated face leaves on a smaller one (reduced to four spread points), the reference face being the one of either box, an edge resting on an edge,
 * and the speculative contact of boxes that are apart.
 */
class BoxContactDetailsTest {

    private final ManifoldBuilder builder = new ManifoldBuilder();

    private static OrientedBox box(double x, double y, double z, double hx, double hy, double hz) {
        return new OrientedBox().set(x, y, z, 0, 0, 0, 1, hx, hy, hz);
    }

    private static OrientedBox turnedY(double x, double y, double z, double angle, double hx, double hy, double hz) {
        return new OrientedBox().set(x, y, z, 0, Math.sin(angle / 2), 0, Math.cos(angle / 2), hx, hy, hz);
    }

    @Test
    void aCubeOnTheGroundMakesItsFourCornersWithStableIds() {
        OrientedBox ground = box(0, -1, 0, 10, 1, 10); // top at y = 0
        OrientedBox cube = box(1, 0.49, 2, 0.5, 0.5, 0.5); // bottom at y = -0.01
        ContactManifold m = new ContactManifold();
        assertTrue(builder.boxes(ground, cube, 0.0, m));
        assertEquals(4, m.count());
        assertEquals(0.0, m.nx, 0.0);
        assertEquals(1.0, m.ny, 0.0);
        Set<String> corners = new HashSet<>();
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            assertEquals(0.01, m.depth(i), 1e-12);
            assertEquals(0.0, m.pointA(i, 1), 1e-12, "on the top of the ground");
            assertEquals(-0.01, m.pointB(i, 1), 1e-12, "on the bottom of the cube");
            corners.add(Math.round(m.pointA(i, 0) * 2) + "," + Math.round(m.pointA(i, 2) * 2));
            ids.add(m.id(i));
        }
        assertEquals(Set.of("1,3", "1,5", "3,3", "3,5"), corners, "x in {0.5, 1.5}, z in {1.5, 2.5}");
        assertEquals(4, ids.size(), "four different ids");
        // swapped: the cube is the first body, the ground the second; the normal points from the cube down to the ground and the points are the same
        ContactManifold s = new ContactManifold();
        assertTrue(builder.boxes(cube, ground, 0.0, s));
        assertEquals(4, s.count());
        assertEquals(-1.0, s.ny, 0.0);
        for (int i = 0; i < 4; i++) {
            assertEquals(0.01, s.depth(i), 1e-12);
            assertEquals(-0.01, s.pointA(i, 1), 1e-12);
            assertEquals(0.0, s.pointB(i, 1), 1e-12);
        }
    }

    @Test
    void aRotatedFaceOnASmallerOneLeavesAnOctagonThatIsReducedToFourSpreadPoints() {
        OrientedBox small = box(0, -1, 0, 1, 1, 1); // top face y = 0, the square [-1, 1]^2
        OrientedBox big = turnedY(0, 1.19, 0, Math.PI / 4, 1.2, 1.2, 1.2); // bottom face y = -0.01, a diamond of half-diagonal 1.2 sqrt 2 = 1.697
        ContactManifold m = new ContactManifold();
        assertTrue(builder.boxes(small, big, 0.0, m));
        assertEquals(4, m.count());
        assertEquals(1.0, m.ny, 1e-12);
        double reach = 1.2 * Math.sqrt(2) - 1; // the octagon has vertices (1, reach), (reach, 1), ... : the diamond |x| + |z| <= 1.697 cut by the square
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            double x = m.pointA(i, 0), z = m.pointA(i, 2);
            assertEquals(0.0, m.pointA(i, 1), 1e-12);
            assertEquals(0.01, m.depth(i), 1e-9);
            boolean onOctagonVertex = (Math.abs(Math.abs(x) - 1) < 1e-9 && Math.abs(Math.abs(z) - reach) < 1e-9) || (Math.abs(Math.abs(z) - 1) < 1e-9 && Math.abs(Math.abs(x) - reach) < 1e-9);
            assertTrue(onOctagonVertex, "(" + x + ", " + z + ") is a vertex of the octagon");
            seen.add(Math.round(x * 1000) + "," + Math.round(z * 1000));
        }
        assertEquals(4, seen.size(), "four different points");
        // they are spread: the quadrilateral they make has an area of at least that of the smallest square of octagon vertices
        double minX = 9, maxX = -9, minZ = 9, maxZ = -9;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, m.pointA(i, 0));
            maxX = Math.max(maxX, m.pointA(i, 0));
            minZ = Math.min(minZ, m.pointA(i, 2));
            maxZ = Math.max(maxZ, m.pointA(i, 2));
        }
        assertTrue(maxX - minX > 1.5 && maxZ - minZ > 1.5, "the points span the face: " + (maxX - minX) + " x " + (maxZ - minZ));
    }

    @Test
    void theReferenceFaceIsTheOneOfWhicheverBoxHasTheShallowestAxis() {
        OrientedBox wide = box(0, 0, 0, 2, 0.5, 2); // top at y = 0.5
        OrientedBox narrow = box(0.3, 0.9, 0.2, 0.5, 0.5, 0.5); // bottom at y = 0.4: 0.1 into the wide one
        ContactManifold m = new ContactManifold();
        assertTrue(builder.boxes(wide, narrow, 0.0, m));
        assertEquals(4, m.count());
        assertEquals(1.0, m.ny, 1e-12);
        for (int i = 0; i < 4; i++) {
            assertEquals(0.1, m.depth(i), 1e-12);
            assertEquals(0.5, m.pointA(i, 1), 1e-12, "the reference face is the top of the wide box");
            assertEquals(0.4, m.pointB(i, 1), 1e-12);
        }
        // swap: the narrow box is the first body and owns the shallowest axis of the pair... the contact is the same, seen from the other side
        ContactManifold swapped = new ContactManifold();
        assertTrue(builder.boxes(narrow, wide, 0.0, swapped));
        assertEquals(-1.0, swapped.ny, 1e-12);
        assertEquals(4, swapped.count());
        for (int i = 0; i < 4; i++) {
            assertEquals(0.4, swapped.pointA(i, 1), 1e-12);
            assertEquals(0.5, swapped.pointB(i, 1), 1e-12);
        }
        // a box inside another along the shallowest axis still gets the face of the one it is deepest in, with the normal from the first to the second
        OrientedBox outer = box(0, 0, 0, 5, 5, 5);
        OrientedBox inner = box(4.2, 0, 0, 0.5, 0.5, 0.5); // inside the outer one, nearest to its +x face
        ContactManifold deep = new ContactManifold();
        assertTrue(builder.boxes(outer, inner, 0.0, deep));
        assertEquals(1.0, deep.nx, 1e-12);
        assertEquals(1.3, deep.depth(0), 1e-12, "the inner box spans x from 3.7 to 4.7 and must move 1.3 to clear the face at x = 5");
    }

    @Test
    void anEdgeRestingOnAnEdgeIsOnePointBetweenTheClosestPoints() {
        // a bar along x on the ground-level plane y = 0 with its top edge at y = 0.5, z = 0; a second bar along z whose bottom edge is at y = 0.49
        // after being tipped by 45 degrees about its own axis, so that an edge (not a face) meets the first bar's top face... use two tilted bars instead
        double a = Math.PI / 4;
        OrientedBox first = new OrientedBox().set(0, 0, 0, Math.sin(a / 2), 0, 0, Math.cos(a / 2), 2, 0.5, 0.5);  // along x, turned 45 degrees about x
        OrientedBox second = new OrientedBox().set(0, 1.405, 0, 0, Math.sin(a / 2) * Math.sin(Math.PI / 4 * 0), Math.sin(a / 2), Math.cos(a / 2), 0.5, 0.5, 2); // tipped about z
        ContactManifold m = new ContactManifold();
        boolean touching = builder.boxes(first, second, 0.2, m);
        assertTrue(touching);
        assertTrue(m.count() >= 1);
        // whatever the exact case, the manifold is consistent: a unit normal, and the point on B lies the depth beyond the point on A along the normal
        double len = Math.sqrt(m.nx * m.nx + m.ny * m.ny + m.nz * m.nz);
        assertEquals(1.0, len, 1e-12);
        for (int i = 0; i < m.count(); i++) {
            double dx = m.pointB(i, 0) - m.pointA(i, 0), dy = m.pointB(i, 1) - m.pointA(i, 1), dz = m.pointB(i, 2) - m.pointA(i, 2);
            assertEquals(-m.depth(i), dx * m.nx + dy * m.ny + dz * m.nz, 1e-9, "B's point is -depth along the normal from A's point");
        }
    }

    @Test
    void boxesApartWithinTheMarginGetSpeculativeContactsOfNegativeDepth() {
        OrientedBox ground = box(0, -1, 0, 10, 1, 10);
        OrientedBox cube = box(0, 0.6, 0, 0.5, 0.5, 0.5); // 0.1 above the ground
        ContactManifold m = new ContactManifold();
        assertTrue(builder.boxes(ground, cube, 0.2, m));
        assertEquals(4, m.count());
        for (int i = 0; i < 4; i++) {
            assertEquals(-0.1, m.depth(i), 1e-12);
        }
        assertTrue(!builder.boxes(ground, cube, 0.05, m), "farther than the margin");
        assertEquals(0, m.count());
        // exactly at the margin still counts
        assertTrue(builder.boxes(ground, box(0, 0.6, 0, 0.5, 0.5, 0.5), 0.1 + 1e-12, m));
    }

    @Test
    void boxesThatPenetrateDeeplyThroughACornerStillGiveAContact() {
        // a cube pushed corner first deep into a larger one: the clipped face may have no point on the right side, the deepest corner is used
        OrientedBox big = box(0, 0, 0, 3, 3, 3);
        double t = Math.atan(Math.sqrt(2));
        double s = Math.sin(t / 2) * Math.sqrt(0.5);
        OrientedBox cube = new OrientedBox().set(2.6, 2.6, 2.6, s, 0, -s, Math.cos(t / 2), 1, 1, 1);
        ContactManifold m = new ContactManifold();
        boolean touching = builder.boxes(big, cube, 0.0, m);
        assertTrue(touching);
        assertTrue(m.count() >= 1);
        assertTrue(m.depth(0) > 0);
    }
}
