package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.Report;
import vmath.core.Predicates;
import vmath.core.Rnd;

/**
 * {@link ConvexHull}. A hull is checked without a second hull algorithm: the mesh must be a closed oriented 2-manifold (every directed edge once, its reverse once, Euler's
 * formula), no input point may be above any face (exact predicate), the vertices must be input points, and for small random sets the faces must equal those found by brute
 * force over all triples (in general position the triangulation of the hull is unique). Input that is exactly degenerate (lattices, spheres, planes, duplicates) is
 * where quickhull implementations fail, so most of the cases are that.
 */
class ConvexHullTest {

    private final SplittableRandom r = new SplittableRandom(Rnd.SEED);

    private static float[] flat(double[][] pts) {
        float[] a = new float[3 * pts.length];
        for (int i = 0; i < pts.length; i++) {
            for (int k = 0; k < 3; k++) {
                a[3 * i + k] = (float) pts[i][k];
            }
        }
        return a;
    }

    private static double orient(float[] p, int a, int b, int c, int d) {
        return Predicates.orient3d(p[3 * a], p[3 * a + 1], p[3 * a + 2], p[3 * b], p[3 * b + 1], p[3 * b + 2], p[3 * c], p[3 * c + 1], p[3 * c + 2], p[3 * d], p[3 * d + 1], p[3 * d + 2]);
    }

    /** The structural checks of a solid hull. */
    private static void checkSolid(float[] p, int n, ConvexHull h, String what) {
        assertEquals(3, h.dimension(), what);
        int[] t = h.triangles();
        int f = t.length / 3;
        Map<Long, Integer> directed = new HashMap<>();
        for (int i = 0; i < f; i++) {
            for (int k = 0; k < 3; k++) {
                long key = ((long) t[3 * i + k] << 32) | t[3 * i + (k + 1) % 3];
                assertTrue(directed.put(key, i) == null, what + ": a directed edge appears twice");
            }
        }
        for (long key : directed.keySet()) {
            long rev = ((key & 0xFFFFFFFFL) << 32) | (key >>> 32);
            assertTrue(directed.containsKey(rev), what + ": an edge has no opposite edge, the surface is not closed");
        }
        assertEquals(3 * f, directed.size(), what + ": three directed edges per triangle");
        int v = h.vertexCount();
        assertEquals(2, v - directed.size() / 2 + f, what + ": Euler's formula V - E + F = 2");
        assertTrue(h.volume() > 0, what + ": the volume must be positive");
        // no point above any face, exactly
        for (int i = 0; i < f; i++) {
            int a = t[3 * i], b = t[3 * i + 1], c = t[3 * i + 2];
            for (int q = 0; q < n; q++) {
                assertTrue(orient(p, a, b, c, q) >= 0.0, what + ": point " + q + " is above face " + i);
            }
        }
        Set<Integer> used = new HashSet<>();
        for (int x : t) {
            used.add(x);
        }
        assertEquals(used.size(), v, what + ": the vertex list is the set of face corners");
    }

    @Test
    void randomPointsInACubeAndABall() {
        int cases = Math.max(60, Rnd.N / 100);
        for (int i = 0; i < cases; i++) {
            int n = 4 + r.nextInt(i % 5 == 0 ? 1500 : 150);
            double[][] pts = new double[n][3];
            for (int k = 0; k < n; k++) {
                if (i % 2 == 0) {
                    pts[k] = new double[] {r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1};
                } else {
                    double[] g = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()};
                    double len = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]) / Math.cbrt(r.nextDouble());
                    pts[k] = new double[] {g[0] / len, g[1] / len, g[2] / len};
                }
            }
            float[] p = flat(pts);
            ConvexHull h = ConvexHull.of(p, n);
            checkSolid(p, n, h, "random " + i);
        }
    }

    @Test
    void facesEqualTheBruteForceFacesInGeneralPosition() {
        for (int i = 0; i < 300; i++) {
            int n = 5 + r.nextInt(9);
            double[][] pts = new double[n][3];
            for (int k = 0; k < n; k++) {
                pts[k] = new double[] {r.nextDouble(), r.nextDouble(), r.nextDouble()};
            }
            float[] p = flat(pts);
            ConvexHull h = ConvexHull.of(p, n);
            Set<String> got = new HashSet<>();
            int[] t = h.triangles();
            for (int k = 0; k < t.length; k += 3) {
                got.add(sorted(t[k], t[k + 1], t[k + 2]));
            }
            Set<String> expected = new HashSet<>();
            for (int a = 0; a < n; a++) {
                for (int b = a + 1; b < n; b++) {
                    for (int c = b + 1; c < n; c++) {
                        boolean allBelow = true, allAbove = true;
                        for (int q = 0; q < n; q++) {
                            if (q == a || q == b || q == c) {
                                continue;
                            }
                            double o = orient(p, a, b, c, q);
                            allBelow &= o > 0;
                            allAbove &= o < 0;
                        }
                        if (allBelow || allAbove) {
                            expected.add(sorted(a, b, c));
                        }
                    }
                }
            }
            assertEquals(expected, got, "case " + i + ": the faces of the hull");
        }
    }

    private static String sorted(int a, int b, int c) {
        int[] s = {a, b, c};
        Arrays.sort(s);
        return s[0] + "," + s[1] + "," + s[2];
    }

    @Test
    void pointsOnASphereAreAllVertices() {
        for (int i = 0; i < 30; i++) {
            int n = 8 + r.nextInt(400);
            double[][] pts = new double[n][3];
            for (int k = 0; k < n; k++) {
                double[] g = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()};
                double len = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
                pts[k] = new double[] {g[0] / len, g[1] / len, g[2] / len};
            }
            float[] p = flat(pts);
            ConvexHull h = ConvexHull.of(p, n);
            checkSolid(p, n, h, "sphere " + i);
            assertEquals(n, h.vertexCount(), "points on a sphere (rounded to float, so slightly off it) are all extreme or nearly: allow the few that round inside");
        }
    }

    @Test
    void latticePointsGiveTheExactCube() {
        // a 5 x 5 x 5 grid: thousands of exactly coplanar and collinear points, and a hull that is a cube with 8 vertices and 12 triangles
        int g = 5;
        float[] p = new float[3 * g * g * g];
        int n = 0;
        for (int x = 0; x < g; x++) {
            for (int y = 0; y < g; y++) {
                for (int z = 0; z < g; z++) {
                    p[3 * n] = x;
                    p[3 * n + 1] = y;
                    p[3 * n + 2] = z;
                    n++;
                }
            }
        }
        ConvexHull h = ConvexHull.of(p, n);
        checkSolid(p, n, h, "lattice");
        assertEquals(8, h.vertexCount());
        assertEquals(12, h.triangleCount());
        assertEquals(64.0, h.volume(), 1e-9);
        assertEquals(6 * 16.0, h.surfaceArea(), 1e-9);
        // shuffled order and a scaled, shifted copy give the same shape
        for (int rep = 0; rep < 20; rep++) {
            float[] q = p.clone();
            for (int i = n - 1; i > 0; i--) {
                int j = r.nextInt(i + 1);
                for (int k = 0; k < 3; k++) {
                    float t = q[3 * i + k];
                    q[3 * i + k] = q[3 * j + k];
                    q[3 * j + k] = t;
                }
            }
            ConvexHull s = ConvexHull.of(q, n);
            assertEquals(8, s.vertexCount());
            assertEquals(64.0, s.volume(), 1e-9);
        }
    }

    @Test
    void nearlyDegenerateSetsStillGiveAValidHull() {
        for (int i = 0; i < 60; i++) {
            int n = 20 + r.nextInt(200);
            double[][] pts = new double[n][3];
            for (int k = 0; k < n; k++) {
                switch (i % 4) {
                    case 0 -> pts[k] = new double[] {r.nextDouble(), r.nextDouble(), 1e-6 * r.nextDouble()}; // a thin slab
                    case 1 -> { // points on a circle plus one apex
                        double a = r.nextDouble() * 2 * Math.PI;
                        pts[k] = k == 0 ? new double[] {0, 0, 1} : new double[] {Math.cos(a), Math.sin(a), 0};
                    }
                    case 2 -> pts[k] = new double[] {r.nextInt(3), r.nextInt(3), r.nextInt(3) + (k % 7 == 0 ? 1e-5 : 0)}; // a lattice with a few points nudged off it
                    default -> { // a thin needle
                        double t = r.nextDouble();
                        pts[k] = new double[] {t, t * 0.5 + 1e-7 * r.nextGaussian(), t * 0.25 + 1e-7 * r.nextGaussian()};
                    }
                }
            }
            float[] p = flat(pts);
            ConvexHull h = ConvexHull.of(p, n);
            if (h.dimension() == 3) {
                checkSolid(p, n, h, "degenerate " + i);
            }
        }
    }

    @Test
    void duplicatesAndLowerDimensions() {
        ConvexHull point = ConvexHull.of(new float[] {1, 2, 3, 1, 2, 3, 1, 2, 3}, 3);
        assertEquals(0, point.dimension());
        assertEquals(1, point.vertexCount());
        ConvexHull one = ConvexHull.of(new float[] {1, 2, 3}, 1);
        assertEquals(0, one.dimension());

        float[] line = {0, 0, 0, 1, 1, 1, 2, 2, 2, 0.5f, 0.5f, 0.5f, 3, 3, 3, 1, 1, 1};
        ConvexHull seg = ConvexHull.of(line, 6);
        assertEquals(1, seg.dimension());
        assertEquals(2, seg.vertexCount());
        assertEquals(0, seg.vertices()[0]);
        assertEquals(4, seg.vertices()[1]);
        assertEquals(0, seg.triangleCount());

        // coplanar, in the plane z = 5, a square with interior points and duplicates: area 4
        float[] flatSet = {0, 0, 5, 2, 0, 5, 2, 2, 5, 0, 2, 5, 1, 1, 5, 1, 0, 5, 0, 0, 5, 0.5f, 1.5f, 5};
        ConvexHull plane = ConvexHull.of(flatSet, 8);
        assertEquals(2, plane.dimension());
        assertEquals(4, plane.vertexCount());
        assertEquals(2, plane.triangleCount());
        assertEquals(4.0, plane.surfaceArea(), 1e-12);
        int[] t = plane.triangles();
        for (int k = 0; k < t.length; k += 3) { // counter-clockwise seen from +z for points listed counter-clockwise first
            assertTrue(Predicates.orient2d(flatSet[3 * t[k]], flatSet[3 * t[k] + 1], flatSet[3 * t[k + 1]], flatSet[3 * t[k + 1] + 1], flatSet[3 * t[k + 2]], flatSet[3 * t[k + 2] + 1]) > 0);
        }
        // the same plane tilted so that z is the dominant axis of no projection: a plane x + y + z = 3
        float[] tilted = {3, 0, 0, 0, 3, 0, 0, 0, 3, 1, 1, 1, 2, 1, 0, 0, 1, 2};
        ConvexHull tri = ConvexHull.of(tilted, 6);
        assertEquals(2, tri.dimension());
        assertEquals(3, tri.vertexCount());
        assertEquals(4.5 * Math.sqrt(3), tri.surfaceArea(), 1e-9); // an equilateral triangle with side 3 sqrt 2

        assertThrows(IllegalArgumentException.class, () -> ConvexHull.of(new float[0], 0));
        assertThrows(IllegalArgumentException.class, () -> ConvexHull.of(new float[] {0, 0, Float.NaN}, 1));
        assertThrows(IllegalArgumentException.class, () -> ConvexHull.of(new float[] {0, 0, 0}, 2));
        Report.printf("ConvexHull: degenerate input checked%n");
    }

    @Test
    void volumeAndAreaOfKnownSolids() {
        // a regular tetrahedron with edge length 2 sqrt 2: vertices on alternate corners of a cube of side 2
        float[] tet = {0, 0, 0, 2, 2, 0, 2, 0, 2, 0, 2, 2};
        ConvexHull h = ConvexHull.of(tet, 4);
        assertEquals(4, h.triangleCount());
        assertEquals(8.0 / 3.0, h.volume(), 1e-12);
        assertEquals(4 * Math.sqrt(3) / 4 * 8, h.surfaceArea(), 1e-9);
        // an octahedron
        float[] oct = {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};
        ConvexHull o = ConvexHull.of(oct, 6);
        assertEquals(8, o.triangleCount());
        assertEquals(4.0 / 3.0, o.volume(), 1e-12);
    }
}
