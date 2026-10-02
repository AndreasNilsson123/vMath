package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.core.Quatf;

/** Degenerate inputs and argument checks of the polygon, hull and shape classes. */
class GeometryEdgeCasesTest {

    @Test
    void polygonSimplicityWithTouchingAndOverlappingEdges() {
        // a bow tie crosses itself; a vertex that touches another edge, and two collinear overlapping edges, are not simple either
        assertFalse(Polygons.isSimple(new float[] {0, 0, 2, 2, 2, 0, 0, 2}, 4));
        assertFalse(Polygons.isSimple(new float[] {0, 0, 4, 0, 4, 4, 2, 0, 0, 4}, 5)); // vertex (2,0) lies on edge (0,0)-(4,0)
        assertFalse(Polygons.isSimple(new float[] {0, 0, 3, 0, 1, 0, 1, 2}, 4)); // collinear overlap
        assertTrue(Polygons.isSimple(new float[] {0, 0, 4, 0, 4, 4, 0, 4}, 4));
        assertFalse(Polygons.isSimple(new float[] {0, 0, 1, 1, 1, 1, 0, 2}, 4)); // repeated vertex
    }

    @Test
    void degeneratePolygons() {
        assertEquals(0, Polygons.winding(new float[] {0, 0, 1, 1, 2, 2}, 3));
        assertEquals(0.0, Polygons.signedArea(new float[] {0, 0, 1, 1}, 2));
        assertEquals(0, Polygons.winding(new float[] {0, 0, 0, 0, 0, 0}, 3));
        assertTrue(Polygons.contains(new float[] {0, 0, 4, 0, 4, 4, 0, 4}, 4, 2f, 0f), "on an edge");
        assertTrue(Polygons.contains(new float[] {0, 0, 4, 0, 4, 4, 0, 4}, 4, 0f, 0f), "on a vertex");
        assertFalse(Polygons.contains(new float[] {0, 0, 4, 0, 4, 4, 0, 4}, 4, 5f, 0f));
        assertThrows(IllegalArgumentException.class, () -> Polygons.signedArea(new float[4], 3));
        assertThrows(IllegalArgumentException.class, () -> Polygons.signedArea(new float[4], -1));
    }

    @Test
    void triangulationWithCollinearAndDuplicateVertices() {
        int[] out = new int[3 * 8];
        // a square with a vertex in the middle of each side and one duplicated corner
        float[] xy = {0, 0, 2, 0, 4, 0, 4, 2, 4, 4, 2, 4, 0, 4, 0, 2, 0, 2};
        int tris = Polygons.triangulate(xy, 9, out);
        assertTrue(tris >= 0);
        float[] line = {0, 0, 1, 0, 2, 0};
        assertEquals(0, Math.max(0, Polygons.triangulate(line, 3, out)), "a line has no area");
    }

    @Test
    void clippingAgainstDegenerateClippers() {
        float[] out = new float[64];
        float[] square = {0, 0, 4, 0, 4, 4, 0, 4};
        assertEquals(0, Polygons.clipHalfPlane(square, 4, 1f, 0f, -10f, out), "everything outside");
        assertEquals(4, Polygons.clipHalfPlane(square, 4, 1f, 0f, 10f, out), "everything inside");
        assertEquals(0, Polygons.clipConvex(square, 4, new float[] {10, 10, 12, 10, 12, 12, 10, 12}, 4, out), "disjoint");
        assertEquals(4, Polygons.clipPlane3(new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}, 4, 0f, 0f, 1f, 5f, out));
        assertEquals(0, Polygons.clipPlane3(new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}, 4, 0f, 0f, 1f, -5f, out));
    }

    @Test
    void shapesHandleZeroDirectionsAndBadArguments() {
        double[] out = new double[3];
        ConvexShapes.sphere(1, 2, 3, 2).support(0, 0, 0, out);
        assertEquals(1.0, out[0]);
        ConvexShapes.inflated(ConvexShapes.sphere(0, 0, 0, 1), 0.5).support(0, 0, 0, out);
        assertEquals(0.0, out[0]);
        ConvexShapes.translated(ConvexShapes.sphere(0, 0, 0, 1), 1, 0, 0).support(1, 0, 0, out);
        assertEquals(2.0, out[0], 1e-12);
        ConvexShapes.transformed(ConvexShapes.sphere(0, 0, 0, 1), Quatf.IDENTITY, 0, 0, 0).support(0, 1, 0, out);
        assertEquals(1.0, out[1], 1e-12);
        assertThrows(IllegalArgumentException.class, () -> ConvexShapes.sphere(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ConvexShapes.points(new float[3], 0));
        assertThrows(IllegalArgumentException.class, () -> ConvexShapes.points(new float[3], 2));
    }

    @Test
    void hullOfDegenerateSets() {
        assertEquals(0, ConvexHull.of(new float[] {1, 1, 1, 1, 1, 1}, 2).dimension());
        assertEquals(1, ConvexHull.of(new float[] {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3}, 4).dimension());
        assertEquals(2, ConvexHull.of(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0, 0.5f, 0.5f, 0}, 5).dimension());
        assertEquals(3, ConvexHull.of(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1}, 4).dimension());
        // three collinear points first, then one off the line, then one off the plane: the initial simplex has to skip the collinear ones
        assertEquals(3, ConvexHull.of(new float[] {0, 0, 0, 1, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 1}, 5).dimension());
        assertThrows(IllegalArgumentException.class, () -> ConvexHull.of(new float[3], 2));
        assertThrows(IllegalArgumentException.class, () -> ConvexPolytope.of(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, 3));
    }
}
