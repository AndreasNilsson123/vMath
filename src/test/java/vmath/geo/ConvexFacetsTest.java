package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

/** The facets (planar polygons) and edges of {@link ConvexPolytope}, checked by Euler's formula and the plane equations. */
class ConvexFacetsTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    private static void check(ConvexPolytope p, String what) {
        float[] v = p.vertices();
        int vertexCount = p.vertexCount();
        double[] plane = new double[4];
        double scale = 0;
        for (float f : v) {
            scale = Math.max(scale, Math.abs(f));
        }
        for (int f = 0; f < p.facetCount(); f++) {
            p.facetPlane(f, plane);
            assertEquals(1.0, Math.sqrt(plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]), 1e-9, what);
            int n = p.facetVertexCount(f);
            assertTrue(n >= 3, what + ": facet " + f + " has " + n + " vertices");
            double nx = 0, ny = 0, nz = 0;
            for (int k = 0; k < n; k++) {
                int a = p.facetVertex(f, k), b = p.facetVertex(f, (k + 1) % n);
                // the vertices of a facet are on its plane
                assertEquals(plane[3], plane[0] * v[3 * a] + plane[1] * v[3 * a + 1] + plane[2] * v[3 * a + 2], 1e-4 * (1 + scale), what + ": facet " + f);
                // Newell's normal of the vertex loop points the way of the plane normal when the loop runs counter-clockwise seen from outside
                nx += ((double) v[3 * a + 1] - v[3 * b + 1]) * ((double) v[3 * a + 2] + v[3 * b + 2]);
                ny += ((double) v[3 * a + 2] - v[3 * b + 2]) * ((double) v[3 * a] + v[3 * b]);
                nz += ((double) v[3 * a] - v[3 * b]) * ((double) v[3 * a + 1] + v[3 * b + 1]);
            }
            assertTrue(nx * plane[0] + ny * plane[1] + nz * plane[2] > 0, what + ": facet " + f + " runs the wrong way round");
            // every vertex of the polytope is behind the plane
            for (int i = 0; i < vertexCount; i++) {
                assertTrue(plane[0] * v[3 * i] + plane[1] * v[3 * i + 1] + plane[2] * v[3 * i + 2] <= plane[3] + 1e-4 * (1 + scale), what + ": vertex " + i + " is outside facet " + f);
            }
        }
        // Euler: V - E + F = 2 for a convex polyhedron whose faces are the facets and whose edges are the edges between facets
        assertEquals(2, vertexCount - p.edgeCount() + p.facetCount(), what + ": V " + vertexCount + " E " + p.edgeCount() + " F " + p.facetCount());
        for (int e = 0; e < p.edgeCount(); e++) {
            assertTrue(p.edgeStart(e) != p.edgeEnd(e) && p.edgeStart(e) >= 0 && p.edgeEnd(e) < vertexCount);
        }
    }

    @Test
    void aBoxHasSixFacetsOfFourVerticesAndTwelveEdges() {
        ConvexPolytope box = ConvexPolytope.of(new Aabbf(-1, -2, -3, 1, 2, 3));
        assertEquals(6, box.facetCount());
        assertEquals(12, box.edgeCount());
        for (int f = 0; f < 6; f++) {
            assertEquals(4, box.facetVertexCount(f));
        }
        check(box, "box");
        // the facet planes of an axis-aligned box are the six faces
        double[] plane = new double[4];
        double sumAbs = 0;
        for (int f = 0; f < 6; f++) {
            box.facetPlane(f, plane);
            sumAbs += Math.abs(plane[3]);
            assertEquals(1.0, Math.abs(plane[0]) + Math.abs(plane[1]) + Math.abs(plane[2]), 1e-9);
        }
        assertEquals(2 * (1 + 2 + 3), sumAbs, 1e-9);
    }

    @Test
    void randomHullsObeyEulersFormula() {
        for (int t = 0; t < 100; t++) {
            int n = 8 + rng.nextInt(60);
            float[] pts = new float[3 * n];
            for (int i = 0; i < pts.length; i++) {
                pts[i] = (float) (rng.nextDouble() * 10 - 5);
            }
            ConvexPolytope p = ConvexPolytope.of(pts, n);
            check(p, "hull " + t);
            // random points give triangular facets, except where the float coordinates happen to be coplanar
            assertTrue(p.facetCount() <= p.triangles().length / 3);
        }
    }

    @Test
    void polyhedraWithPolygonalFacets() {
        // a prism over a hexagon: 2 hexagons and 6 quads
        float[] pts = new float[3 * 12];
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            pts[3 * i] = (float) Math.cos(a);
            pts[3 * i + 1] = 0f;
            pts[3 * i + 2] = (float) Math.sin(a);
            pts[3 * (i + 6)] = (float) Math.cos(a);
            pts[3 * (i + 6) + 1] = 2f;
            pts[3 * (i + 6) + 2] = (float) Math.sin(a);
        }
        ConvexPolytope prism = ConvexPolytope.of(pts, 12);
        check(prism, "prism");
        assertEquals(8, prism.facetCount());
        assertEquals(18, prism.edgeCount());
        int hexagons = 0, quads = 0;
        for (int f = 0; f < 8; f++) {
            if (prism.facetVertexCount(f) == 6) {
                hexagons++;
            } else if (prism.facetVertexCount(f) == 4) {
                quads++;
            }
        }
        assertEquals(2, hexagons);
        assertEquals(6, quads);
        // an octahedron: 8 triangles
        ConvexPolytope octa = ConvexPolytope.of(new float[] {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1}, 6);
        check(octa, "octahedron");
        assertEquals(8, octa.facetCount());
        assertEquals(12, octa.edgeCount());
        // a transformed box keeps the structure
        ConvexPolytope turned = ConvexPolytope.of(new Aabbf(-1, -1, -1, 1, 1, 1)).transformed(vmath.core.Quatf.fromAxisAngle(0.7f, new vmath.core.Vec3f(1, 2, 3).normalize()), new vmath.core.Vec3f(4, 5, 6));
        check(turned, "turned box");
        assertEquals(6, turned.facetCount());
    }
}
