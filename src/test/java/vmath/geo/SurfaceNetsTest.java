package vmath.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vmath.physics.MassProperties;

class SurfaceNetsTest {

    /** The topology of the mesh: whether it is closed (every directed edge has its reverse) and V - E + F. */
    private record Topology(boolean closed, int euler, int edges, int unusedVertices, int degenerate) {
    }

    private static Topology topology(SurfaceNets m) {
        Map<Long, Integer> directed = new HashMap<>();
        boolean[] used = new boolean[m.vertexCount()];
        int degenerate = 0;
        int[] idx = m.indices();
        for (int t = 0; t < m.triangleCount(); t++) {
            int a = idx[3 * t], b = idx[3 * t + 1], c = idx[3 * t + 2];
            if (a == b || b == c || a == c) {
                degenerate++;
            }
            used[a] = used[b] = used[c] = true;
            for (int[] e : new int[][] {{a, b}, {b, c}, {c, a}}) {
                directed.merge((long) e[0] << 32 | e[1], 1, Integer::sum);
            }
        }
        boolean closed = true;
        int edges = 0;
        for (Map.Entry<Long, Integer> e : directed.entrySet()) {
            long k = e.getKey();
            long reverse = (k & 0xffffffffL) << 32 | (k >>> 32);
            if (e.getValue() != 1 || !Integer.valueOf(1).equals(directed.get(reverse))) {
                closed = false;
            }
            if ((k >>> 32) < (k & 0xffffffffL)) {
                edges++;
            }
        }
        int unused = 0;
        for (boolean u : used) {
            if (!u) {
                unused++;
            }
        }
        return new Topology(closed, m.vertexCount() - edges + m.triangleCount(), edges, unused, degenerate);
    }

    private static double volume(SurfaceNets m) {
        return MassProperties.ofMesh(m.positions(), m.indices(), m.triangleCount(), 1.0).mass(); // the density is 1: the mass is the volume
    }

    private static double maxField(Sdf f, SurfaceNets m) {
        double worst = 0;
        for (int v = 0; v < m.vertexCount(); v++) {
            worst = Math.max(worst, Math.abs(f.distance(m.positions()[3 * v], m.positions()[3 * v + 1], m.positions()[3 * v + 2])));
        }
        return worst;
    }

    @Test
    void aSphereGivesAClosedOutwardWoundSphere() {
        Sdf sphere = Sdfs.sphere(0.1f, -0.2f, 0.3f, 1f);
        SurfaceNets m = new SurfaceNets();
        m.mesh(sphere, -1.6f, -1.6f, -1.6f, 1.6f, 1.6f, 1.6f, 32, 32, 32);
        Topology t = topology(m);
        assertTrue(t.closed, "every edge is shared by exactly two triangles with opposite directions");
        assertEquals(2, t.euler, "the Euler characteristic of a sphere");
        assertEquals(0, t.unusedVertices);
        assertEquals(0, t.degenerate);
        // ofMesh rejects inside-out meshes, so a positive volume proves the winding; the volume is close to 4/3 pi
        double expected = 4.0 / 3.0 * Math.PI;
        assertEquals(expected, volume(m), 0.04 * expected);
        // every vertex is near the surface: well within one cell (0.1)
        assertTrue(maxField(sphere, m) < 0.02, "the vertices are within " + maxField(sphere, m) + " of the surface");
        assertEquals(m.vertexCount() + m.triangleCount() - t.edges, 2);
    }

    @Test
    void projectionPutsTheVerticesOnTheSurface() {
        Sdf sphere = Sdfs.sphere(0, 0, 0, 1f);
        SurfaceNets plain = new SurfaceNets(), projected = new SurfaceNets().projection(2);
        plain.mesh(sphere, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 16, 16, 16);
        projected.mesh(sphere, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 16, 16, 16);
        assertEquals(plain.vertexCount(), projected.vertexCount());
        assertEquals(plain.triangleCount(), projected.triangleCount());
        double before = maxField(sphere, plain), after = maxField(sphere, projected);
        assertTrue(after < before / 5 && after < 1e-3, "the largest distance to the surface goes from " + before + " to " + after);
        Topology t = topology(projected);
        assertTrue(t.closed);
        assertEquals(2, t.euler);
        double expected = 4.0 / 3.0 * Math.PI;
        assertEquals(expected, volume(projected), 0.03 * expected);
        assertThrows(IllegalArgumentException.class, () -> new SurfaceNets().projection(-1));
    }

    @Test
    void theNormalsAreTheGradientOfTheField() {
        Sdf sphere = Sdfs.sphere(0.5f, 0, 0, 1f);
        SurfaceNets m = new SurfaceNets().normals(true).projection(1);
        m.mesh(sphere, -1f, -1.5f, -1.5f, 2f, 1.5f, 1.5f, 24, 24, 24);
        float[] p = m.positions(), n = m.normals();
        for (int v = 0; v < m.vertexCount(); v++) {
            double x = p[3 * v] - 0.5, y = p[3 * v + 1], z = p[3 * v + 2], l = Math.sqrt(x * x + y * y + z * z);
            assertEquals(x / l, n[3 * v], 0.02);
            assertEquals(y / l, n[3 * v + 1], 0.02);
            assertEquals(z / l, n[3 * v + 2], 0.02);
        }
        // the triangles face the same way as the normals: the geometric normal of each triangle agrees with the field's
        int[] idx = m.indices();
        for (int t = 0; t < m.triangleCount(); t++) {
            int a = idx[3 * t], b = idx[3 * t + 1], c = idx[3 * t + 2];
            double ux = p[3 * b] - p[3 * a], uy = p[3 * b + 1] - p[3 * a + 1], uz = p[3 * b + 2] - p[3 * a + 2];
            double vx = p[3 * c] - p[3 * a], vy = p[3 * c + 1] - p[3 * a + 1], vz = p[3 * c + 2] - p[3 * a + 2];
            double gx = uy * vz - uz * vy, gy = uz * vx - ux * vz, gz = ux * vy - uy * vx;
            double dot = gx * (n[3 * a] + n[3 * b] + n[3 * c]) + gy * (n[3 * a + 1] + n[3 * b + 1] + n[3 * c + 1]) + gz * (n[3 * a + 2] + n[3 * b + 2] + n[3 * c + 2]);
            assertTrue(dot > 0, "triangle " + t + " faces inward");
        }
    }

    @Test
    void aTorusHasGenusOneAndABoxWithAHoleHasTheRightVolume() {
        Sdf torus = Sdfs.torus(0, 0, 0, 1f, 0.35f);
        SurfaceNets m = new SurfaceNets().projection(1);
        m.mesh(torus, -1.6f, -0.6f, -1.6f, 1.6f, 0.6f, 1.6f, 64, 24, 64);
        Topology t = topology(m);
        assertTrue(t.closed);
        assertEquals(0, t.euler, "the Euler characteristic of a torus");
        double expected = 2 * Math.PI * Math.PI * 1f * 0.35 * 0.35;
        assertEquals(expected, volume(m), 0.05 * expected);
        // a box with a spherical cavity and a cylindrical hole: genus 1 outside, an inner closed shell: Euler = 2 - 2 g + (shells - 1) * 2 -> two components of the boundary
        Sdf solid = Sdfs.subtract(Sdfs.subtract(Sdfs.box(0, 0, 0, 1, 1, 1), Sdfs.sphere(0, 0, 0, 0.5f)), Sdfs.cylinder(0, 0, 0, 0.2f, 2f));
        m.mesh(solid, -1.25f, -1.25f, -1.25f, 1.25f, 1.25f, 1.25f, 40, 40, 40);
        t = topology(m);
        assertTrue(t.closed);
        // the cylinder opens the cavity to the outside, so the solid is a cube with a bore and a bulge: one boundary of genus 1 (a single hole through it)
        assertEquals(0, t.euler);
        // the volume against a dense random estimate (the exact value needs the intersection of a sphere and a cylinder)
        int inside = 0, total = 0;
        java.util.SplittableRandom rng = new java.util.SplittableRandom(3);
        for (int i = 0; i < 400000; i++) {
            float x = (float) (rng.nextDouble() * 2 - 1), y = (float) (rng.nextDouble() * 2 - 1), z = (float) (rng.nextDouble() * 2 - 1);
            total++;
            if (solid.distance(x, y, z) < 0) {
                inside++;
            }
        }
        double reference = 8.0 * inside / total;
        assertEquals(reference, volume(m), 0.04 * reference);
    }

    @Test
    void anEmptyOrFullFieldGivesNoMeshAndAClippedSurfaceIsOpen() {
        SurfaceNets m = new SurfaceNets();
        m.mesh(Sdfs.sphere(10, 10, 10, 1f), -1, -1, -1, 1, 1, 1, 8, 8, 8);
        assertEquals(0, m.vertexCount());
        assertEquals(0, m.triangleCount());
        m.mesh(Sdfs.sphere(0, 0, 0, 100f), -1, -1, -1, 1, 1, 1, 8, 8, 8);
        assertEquals(0, m.triangleCount(), "the box is entirely inside");
        // a sphere bigger than the box: the surface leaves through the border and the mesh is open there
        m.mesh(Sdfs.sphere(0, 0, 0, 1.2f), -1, -1, -1, 1, 1, 1, 16, 16, 16);
        assertTrue(m.triangleCount() > 0);
        assertTrue(!topology(m).closed);
    }

    @Test
    void theIsoLevelGivesOffsetSurfaces() {
        Sdf sphere = Sdfs.sphere(0, 0, 0, 1f);
        SurfaceNets m = new SurfaceNets().isoLevel(0.5f).projection(2);
        m.mesh(sphere, -2f, -2f, -2f, 2f, 2f, 2f, 32, 32, 32);
        double expected = 4.0 / 3.0 * Math.PI * 1.5 * 1.5 * 1.5;
        assertEquals(expected, volume(m), 0.03 * expected);
        for (int v = 0; v < m.vertexCount(); v++) {
            double r = Math.sqrt(Math.pow(m.positions()[3 * v], 2) + Math.pow(m.positions()[3 * v + 1], 2) + Math.pow(m.positions()[3 * v + 2], 2));
            assertEquals(1.5, r, 2e-3);
        }
        assertThrows(IllegalArgumentException.class, () -> new SurfaceNets().isoLevel(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceNets().isoLevel(Float.POSITIVE_INFINITY));
    }

    @Test
    void theMesherIsReusableAndDeterministic() {
        Sdf shape = Sdfs.smoothUnion(Sdfs.sphere(-0.4f, 0, 0, 0.7f), Sdfs.sphere(0.4f, 0, 0, 0.7f), 0.5f);
        SurfaceNets m = new SurfaceNets().normals(true);
        m.mesh(shape, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 24, 24, 24);
        int v = m.vertexCount(), t = m.triangleCount();
        float[] positions = java.util.Arrays.copyOf(m.positions(), 3 * v);
        int[] indices = java.util.Arrays.copyOf(m.indices(), 3 * t);
        // a different size in between, then the same again
        m.mesh(Sdfs.sphere(0, 0, 0, 1f), -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 40, 40, 40);
        m.mesh(shape, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 24, 24, 24);
        assertEquals(v, m.vertexCount());
        assertEquals(t, m.triangleCount());
        assertTrue(java.util.Arrays.equals(positions, java.util.Arrays.copyOf(m.positions(), 3 * v)));
        assertTrue(java.util.Arrays.equals(indices, java.util.Arrays.copyOf(m.indices(), 3 * t)));
        Topology topology = topology(m);
        assertTrue(topology.closed);
        assertEquals(2, topology.euler, "the two blended spheres are one connected ball");
        // normals switched on after a mesh without them
        SurfaceNets late = new SurfaceNets();
        late.mesh(shape, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 8, 8, 8);
        late.normals(true).mesh(shape, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 8, 8, 8);
        float n0 = late.normals()[0], n1 = late.normals()[1], n2 = late.normals()[2];
        assertEquals(1.0, Math.sqrt(n0 * n0 + n1 * n1 + n2 * n2), 1e-4);
    }

    @Test
    void thePerformanceShapeScalesWithTheSurfaceNotTheVolume() {
        Sdf sphere = Sdfs.sphere(0, 0, 0, 1f);
        SurfaceNets a = new SurfaceNets(), b = new SurfaceNets();
        a.mesh(sphere, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 20, 20, 20);
        b.mesh(sphere, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 40, 40, 40);
        // doubling the resolution quadruples the number of surface cells (about), not multiplies it by 8
        double ratio = (double) b.vertexCount() / a.vertexCount();
        assertEquals(4.0, ratio, 0.6);
    }

    @Test
    void argumentsAreValidated() {
        SurfaceNets m = new SurfaceNets();
        Sdf s = Sdfs.sphere(0, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, -1, -1, -1, 1, 1, 1, 0, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, -1, -1, -1, 1, 1, 1, 4, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, -1, -1, -1, 1, 1, 1, 4, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, 1, -1, -1, 1, 1, 1, 4, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, -1, 1, -1, 1, 1, 1, 4, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, -1, -1, 1, 1, 1, 1, 4, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, Float.NEGATIVE_INFINITY, -1, -1, 1, 1, 1, 4, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, -1, Float.NEGATIVE_INFINITY, -1, 1, 1, 1, 4, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, -1, -1, -3e38f, 1, 1, 3e38f, 4, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> m.mesh(s, -1, -1, -1, 1, 1, 1, 2000, 2000, 2000));
    }
}
