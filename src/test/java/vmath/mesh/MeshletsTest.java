package vmath.mesh;

import vmath.Report;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.bulk.VisibilitySet;
import vmath.core.Rnd;
import vmath.gl.GpuWriter;
import vmath.spatial.ConeCull;

class MeshletsTest {

    final Rnd rnd = Rnd.create();

    /** A triangle as its corner indices rotated so the smallest comes first (orientation kept), as a sortable string. */
    private static String canonical(int a, int b, int c) {
        if (b < a && b < c) {
            return b + "," + c + "," + a;
        }
        if (c < a && c < b) {
            return c + "," + a + "," + b;
        }
        return a + "," + b + "," + c;
    }

    private static List<String> sourceTriangles(Mesh m) {
        List<String> out = new ArrayList<>();
        int[] idx = m.indices();
        for (int t = 0; t < m.triangleCount(); t++) {
            out.add(canonical(idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2]));
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> meshletTriangles(Meshlets ml) {
        List<String> out = new ArrayList<>();
        int[] tri = new int[3];
        for (int m = 0; m < ml.count(); m++) {
            for (int t = 0; t < ml.triangleCount(m); t++) {
                ml.triangle(m, t, tri);
                out.add(canonical(tri[0], tri[1], tri[2]));
            }
        }
        Collections.sort(out);
        return out;
    }

    private static Mesh[] shapes() {
        return new Mesh[] {Primitives.uvSphere(1f, 48, 24), Primitives.icoSphere(1f, 4), Primitives.torus(1f, 0.3f, 48, 24), Primitives.box(1f, 2f, 3f),
                Primitives.capsule(0.5f, 2f, 32, 8), Primitives.plane(4f, 4f, 30, 30)};
    }

    @Test
    void everyTriangleLandsInExactlyOneMeshletAndLimitsHold() {
        int[][] limits = {{64, 124}, {32, 64}, {255, 512}, {3, 1}, {8, 4}, {100, 10}};
        for (Mesh mesh : shapes()) {
            for (int[] lim : limits) {
                Meshlets ml = Meshlets.build(mesh, lim[0], lim[1]);
                String what = mesh.triangleCount() + " triangles, limits " + lim[0] + "/" + lim[1];
                assertEquals(sourceTriangles(mesh), meshletTriangles(ml), what + ": the same triangles, orientation included");
                int verts = 0, tris = 0;
                for (int m = 0; m < ml.count(); m++) {
                    assertTrue(ml.vertexCount(m) <= lim[0] && ml.vertexCount(m) >= 1, what + ": vertex limit");
                    assertTrue(ml.triangleCount(m) <= lim[1] && ml.triangleCount(m) >= 1, what + ": triangle limit");
                    Set<Integer> unique = new HashSet<>();
                    for (int k = 0; k < ml.vertexCount(m); k++) {
                        assertTrue(unique.add(ml.vertices()[ml.vertexOffset(m) + k]), what + ": a vertex is listed once per meshlet");
                    }
                    for (int t = 0; t < ml.triangleCount(m) * 3; t++) {
                        assertTrue((ml.triangles()[ml.triangleOffset(m) * 3 + t] & 0xFF) < ml.vertexCount(m), what + ": local index in range");
                    }
                    verts += ml.vertexCount(m);
                    tris += ml.triangleCount(m);
                }
                assertEquals(mesh.triangleCount(), tris);
                assertEquals(verts, ml.vertices().length);
            }
        }
    }

    @Test
    void spheresContainTheirVerticesAndConesContainTheirNormals() {
        for (Mesh mesh : shapes()) {
            Meshlets ml = Meshlets.build(mesh);
            float[] p = mesh.positions();
            int[] tri = new int[3];
            for (int m = 0; m < ml.count(); m++) {
                for (int k = 0; k < ml.vertexCount(m); k++) {
                    int v = ml.vertices()[ml.vertexOffset(m) + k];
                    double dx = p[v * 3] - ml.sphereX(m), dy = p[v * 3 + 1] - ml.sphereY(m), dz = p[v * 3 + 2] - ml.sphereZ(m);
                    assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) <= ml.sphereRadius(m), "vertex inside the sphere");
                }
                if (ml.coneCutoff(m) < 1f) {
                    double axisLen = Math.sqrt(ml.coneAxisX(m) * ml.coneAxisX(m) + ml.coneAxisY(m) * ml.coneAxisY(m) + ml.coneAxisZ(m) * ml.coneAxisZ(m));
                    assertEquals(1.0, axisLen, 1e-4, "unit axis");
                    double minDot = Math.sqrt(1 - ml.coneCutoff(m) * ml.coneCutoff(m));
                    for (int t = 0; t < ml.triangleCount(m); t++) {
                        ml.triangle(m, t, tri);
                        double[] n = normal(p, tri);
                        if (n == null) {
                            continue;
                        }
                        double dot = n[0] * ml.coneAxisX(m) + n[1] * ml.coneAxisY(m) + n[2] * ml.coneAxisZ(m);
                        assertTrue(dot >= minDot - 1e-4, "every normal inside the cone: " + dot + " vs " + minDot);
                    }
                }
            }
        }
    }

    private static double[] normal(float[] p, int[] tri) {
        int a = tri[0] * 3, b = tri[1] * 3, c = tri[2] * 3;
        double ex = p[b] - p[a], ey = p[b + 1] - p[a + 1], ez = p[b + 2] - p[a + 2];
        double fx = p[c] - p[a], fy = p[c + 1] - p[a + 1], fz = p[c + 2] - p[a + 2];
        double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return l > 0 ? new double[] {nx / l, ny / l, nz / l} : null;
    }

    @Test
    void coneCullingNeverHidesAVisibleTriangle() {
        for (Mesh mesh : shapes()) {
            Meshlets ml = Meshlets.build(mesh);
            float[] p = mesh.positions();
            int[] tri = new int[3];
            int culled = 0, total = 0;
            for (int trial = 0; trial < 40; trial++) {
                float ex = rnd.nextVec3f().x() * 0.5f, ey = rnd.nextVec3f().y() * 0.5f, ez = rnd.nextVec3f().z() * 0.5f;
                for (int m = 0; m < ml.count(); m++) {
                    total++;
                    boolean back = ConeCull.backfacing(ml.sphereX(m), ml.sphereY(m), ml.sphereZ(m), ml.sphereRadius(m), ml.coneAxisX(m), ml.coneAxisY(m),
                            ml.coneAxisZ(m), ml.coneCutoff(m), ex, ey, ez);
                    if (!back) {
                        continue;
                    }
                    culled++;
                    for (int t = 0; t < ml.triangleCount(m); t++) {
                        ml.triangle(m, t, tri);
                        double[] n = normal(p, tri);
                        if (n == null) {
                            continue;
                        }
                        int a = tri[0] * 3;
                        double facing = n[0] * (ex - p[a]) + n[1] * (ey - p[a + 1]) + n[2] * (ez - p[a + 2]);
                        assertTrue(facing <= 1e-4, "a culled meshlet has a front-facing triangle (" + facing + ")");
                    }
                }
            }
        }
    }

    @Test
    void aSphereSeenFromOutsideHasManyMeshletsCulled() {
        Mesh mesh = Primitives.icoSphere(1f, 5);
        Meshlets ml = Meshlets.build(mesh, 64, 124);
        int culled = 0, total = 0;
        for (int trial = 0; trial < 200; trial++) {
            vmath.core.Vec3f d = rnd.nextVec3f();
            d = d.mul(6f / d.length());
            for (int m = 0; m < ml.count(); m++) {
                total++;
                if (ConeCull.backfacing(ml.sphereX(m), ml.sphereY(m), ml.sphereZ(m), ml.sphereRadius(m), ml.coneAxisX(m), ml.coneAxisY(m), ml.coneAxisZ(m),
                        ml.coneCutoff(m), d.x(), d.y(), d.z())) {
                    culled++;
                }
            }
        }
        Report.println("MESHLET-CULL fraction " + (double) culled / total + " of " + ml.count() + " meshlets");
        assertTrue(culled > total / 4, "a sphere seen from 6 radii away: " + culled + " of " + total);
    }

    @Test
    void fillAndDuplicationAreReasonable() {
        Mesh mesh = Primitives.icoSphere(1f, 5); // 20480 triangles
        Meshlets ml = Meshlets.build(mesh, 64, 124);
        double avgTris = (double) mesh.triangleCount() / ml.count();
        double duplication = (double) ml.vertices().length / mesh.vertexCount();
        assertTrue(avgTris > 60, "average triangles per meshlet " + avgTris + " of a possible 124");
        assertTrue(duplication < 1.8, "vertex duplication factor " + duplication);
    }

    @Test
    void theClustersPlugIntoConeCull() {
        Mesh mesh = Primitives.uvSphere(1f, 48, 24);
        Meshlets ml = Meshlets.build(mesh);
        ConeCull.Clusters clusters = new ConeCull.Clusters();
        ml.addTo(clusters);
        assertEquals(ml.count(), clusters.size());
        VisibilitySet visible = new VisibilitySet(ml.count());
        visible.setAll(ml.count());
        int n = clusters.cull(5f, 0.3f, 0.2f, visible);
        assertEquals(ml.count() - visible.count(), n);
        assertTrue(n > 0 && n < ml.count());
    }

    @Test
    void gpuRecordsRoundTrip() {
        Mesh mesh = Primitives.torus(1f, 0.3f, 24, 12);
        Meshlets ml = Meshlets.build(mesh, 32, 50);
        MemorySegment desc = MemorySegment.ofArray(new byte[ml.count() * Meshlets.DESCRIPTOR_BYTES + 8]);
        MemorySegment bounds = MemorySegment.ofArray(new byte[ml.count() * Meshlets.BOUNDS_BYTES + 8]);
        ml.writeDescriptors(desc, 8);
        ml.writeBounds(bounds, 8);
        for (int m = 0; m < ml.count(); m++) {
            long d = 8 + (long) m * Meshlets.DESCRIPTOR_BYTES, b = 8 + (long) m * Meshlets.BOUNDS_BYTES;
            assertEquals(ml.vertexOffset(m), GpuWriter.getInt(desc, d));
            assertEquals(ml.vertexCount(m), GpuWriter.getInt(desc, d + 4));
            assertEquals(ml.triangleOffset(m), GpuWriter.getInt(desc, d + 8));
            assertEquals(ml.triangleCount(m), GpuWriter.getInt(desc, d + 12));
            assertEquals(ml.sphereX(m), GpuWriter.getFloat(bounds, b));
            assertEquals(ml.sphereRadius(m), GpuWriter.getFloat(bounds, b + 12));
            assertEquals(ml.coneAxisZ(m), GpuWriter.getFloat(bounds, b + 24));
            assertEquals(ml.coneCutoff(m), GpuWriter.getFloat(bounds, b + 28));
        }
    }

    @Test
    void edgeCases() {
        assertEquals(0, Meshlets.build(new Mesh()).count());
        assertThrows(IllegalArgumentException.class, () -> Meshlets.build(new Mesh(), 2, 10));
        assertThrows(IllegalArgumentException.class, () -> Meshlets.build(new Mesh(), 256, 10));
        assertThrows(IllegalArgumentException.class, () -> Meshlets.build(new Mesh(), 64, 0));
        Mesh m = new Mesh();
        int a = m.addVertex(0, 0, 0), b = m.addVertex(1, 0, 0), c = m.addVertex(0, 1, 0);
        m.addTriangle(a, b, c);
        m.addTriangle(a, a, b); // degenerate
        Meshlets ml = Meshlets.build(m);
        assertEquals(1, ml.count());
        assertEquals(2, ml.triangleCount(0));
        assertEquals(1f, ml.coneCutoff(0), "a degenerate triangle makes the cone useless but never wrong");
    }
}
