package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class MeshToolsTest {

    final Rnd rnd = Rnd.create();

    /** The triangles as position triples with orientation kept (rotated so the smallest corner is first). */
    private static Set<String> triangleSet(Mesh m) {
        Set<String> out = new HashSet<>();
        float[] p = m.positions();
        int[] idx = m.indices();
        for (int t = 0; t < m.indexCount(); t += 3) {
            String[] c = new String[3];
            for (int k = 0; k < 3; k++) {
                int v = idx[t + k];
                c[k] = p[v * 3] + "," + p[v * 3 + 1] + "," + p[v * 3 + 2];
            }
            int first = 0;
            for (int k = 1; k < 3; k++) {
                if (c[k].compareTo(c[first]) < 0) {
                    first = k;
                }
            }
            out.add(c[first] + "|" + c[(first + 1) % 3] + "|" + c[(first + 2) % 3]);
        }
        return out;
    }

    private static void assertFiniteUnit(Mesh m, String what) {
        float[] n = m.normals();
        for (int v = 0; v < m.vertexCount(); v++) {
            double len = Math.sqrt(n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2]);
            assertEquals(1.0, len, 1e-5, what + ": normal " + v + " must be finite and unit length");
        }
    }

    // ------------------------------------------------------------ normals

    @Test
    void smoothNormalsOfASphereAreRadial() {
        Mesh m = Primitives.icoSphere(3f, 3);
        float[] reference = m.normals().clone();
        java.util.Arrays.fill(m.normals(), 0f);
        MeshTools.computeSmoothNormals(m);
        assertFiniteUnit(m, "ico sphere");
        for (int v = 0; v < m.vertexCount(); v++) {
            double dot = m.normals()[v * 3] * reference[v * 3] + m.normals()[v * 3 + 1] * reference[v * 3 + 1]
                    + m.normals()[v * 3 + 2] * reference[v * 3 + 2];
            assertTrue(dot > 0.9995, "vertex " + v + " normal is off the radial direction (dot " + dot + ")");
        }
    }

    @Test
    void angleWeightingMakesNormalsIndependentOfTheTriangulation() {
        // a flat fan around the origin, then the same square with the diagonals cut the other way plus an extra split
        Mesh a = new Mesh();
        a.addVertex(0f, 0f, 0f);
        for (int i = 0; i < 4; i++) {
            a.addVertex((float) Math.cos(i * Math.PI / 2), 0.5f * ((i & 1) == 0 ? 1 : -1), (float) Math.sin(i * Math.PI / 2));
        }
        for (int i = 0; i < 4; i++) {
            a.addTriangle(0, 1 + i, 1 + (i + 1) % 4);
        }
        // the same fan with the corner between two triangles split in two smaller ones (same surface, same angles in total)
        Mesh b = new Mesh();
        for (int v = 0; v < a.vertexCount(); v++) {
            b.addVertex(a.positions()[v * 3], a.positions()[v * 3 + 1], a.positions()[v * 3 + 2]);
        }
        int mid = b.addVertex((a.positions()[3] + a.positions()[6]) / 2, (a.positions()[4] + a.positions()[7]) / 2, (a.positions()[5] + a.positions()[8]) / 2);
        b.addTriangle(0, 1, mid);
        b.addTriangle(0, mid, 2);
        for (int i = 1; i < 4; i++) {
            b.addTriangle(0, 1 + i, 1 + (i + 1) % 4);
        }
        MeshTools.computeSmoothNormals(a);
        MeshTools.computeSmoothNormals(b);
        for (int k = 0; k < 3; k++) {
            assertEquals(a.normals()[k], b.normals()[k], 1e-6f, "the centre normal must not depend on how one wedge is split");
        }
    }

    @Test
    void creaseAngleKeepsCubeEdgesHardAndSphereSmooth() {
        Mesh cube = MeshTest.sharedCube();
        Set<String> before = triangleSet(cube);
        int[] remap = MeshTools.computeNormalsWithCrease(cube, (float) Math.toRadians(30));
        assertEquals(24, cube.vertexCount(), "each corner is split into three: one per face");
        assertEquals(24, remap.length);
        for (int v = 0; v < 8; v++) {
            assertEquals(v, remap[v]);
        }
        assertEquals(before, triangleSet(cube), "splitting must not change the surface");
        assertFiniteUnit(cube, "cube");
        for (int v = 0; v < cube.vertexCount(); v++) {
            float[] n = cube.normals();
            int axes = 0;
            for (int k = 0; k < 3; k++) {
                if (Math.abs(Math.abs(n[v * 3 + k]) - 1f) < 1e-5f) {
                    axes++;
                }
            }
            assertEquals(1, axes, "flat shading: the normal of split vertex " + v + " is a face axis");
            for (int k = 0; k < 3; k++) {
                assertEquals(cube.positions()[remap[v] * 3 + k], cube.positions()[v * 3 + k], "copies keep their position");
            }
        }
        Mesh smooth = MeshTest.sharedCube();
        int[] noSplit = MeshTools.computeNormalsWithCrease(smooth, (float) Math.PI);
        assertEquals(8, smooth.vertexCount());
        assertEquals(8, noSplit.length);
        for (int v = 0; v < 8; v++) { // pointing away from the cube's centre
            float dot = (smooth.positions()[v * 3] - 0.5f) * smooth.normals()[v * 3] + (smooth.positions()[v * 3 + 1] - 0.5f) * smooth.normals()[v * 3 + 1]
                    + (smooth.positions()[v * 3 + 2] - 0.5f) * smooth.normals()[v * 3 + 2];
            assertTrue(dot > 0.5f, "corner normal points outward");
        }
        Mesh sphere = Primitives.icoSphere(1f, 3);
        int count = sphere.vertexCount();
        MeshTools.computeNormalsWithCrease(sphere, (float) Math.toRadians(60));
        assertEquals(count, sphere.vertexCount(), "a finely tessellated sphere has no creases");
        assertThrows(IllegalArgumentException.class, () -> MeshTools.computeNormalsWithCrease(sphere, -1f));
    }

    @Test
    void remapCarriesExtraStreamsAlong() {
        Mesh cube = MeshTest.sharedCube();
        cube.enableUvs(0);
        for (int v = 0; v < 8; v++) {
            cube.setUv(0, v, v, 10f * v);
        }
        int[] remap = MeshTools.computeNormalsWithCrease(cube, 0.1f);
        for (int v = 0; v < cube.vertexCount(); v++) {
            assertEquals((float) remap[v], cube.uvs(0)[v * 2], "the copy of vertex " + remap[v] + " keeps its uv");
        }
    }

    // ------------------------------------------------------------ tangents

    @Test
    void tangentFollowsUAndHandednessFollowsMirroring() {
        for (int trial = 0; trial < 200; trial++) {
            // a planar patch in XZ with UV = A * (x, z) + c for a random invertible A (rotation, scale, shear, mirroring)
            double a00 = rnd.range(-2, 2), a01 = rnd.range(-2, 2), a10 = rnd.range(-2, 2), a11 = rnd.range(-2, 2);
            double det = a00 * a11 - a01 * a10;
            if (Math.abs(det) < 0.3) {
                continue;
            }
            Mesh m = new Mesh();
            m.enableNormals();
            m.enableUvs(0);
            float[][] pts = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}, {0, 0}};
            for (float[] q : pts) {
                int v = m.addVertex(q[0], 0f, q[1]);
                m.setNormal(v, 0f, 1f, 0f);
                m.setUv(0, v, (float) (a00 * q[0] + a01 * q[1] + 0.3), (float) (a10 * q[0] + a11 * q[1] - 0.7));
            }
            // wind the fan so that its face normal is +Y whatever the UVs do
            m.addTriangle(4, 0, 1);
            m.addTriangle(4, 1, 2);
            m.addTriangle(4, 2, 3);
            m.addTriangle(4, 3, 0);
            double[] f = MeshToolsTest.faceNormalOf(m, 0);
            if (f[1] < 0) {
                int[] idx = m.indices();
                for (int t = 0; t < m.indexCount(); t += 3) {
                    int tmp = idx[t + 1];
                    idx[t + 1] = idx[t + 2];
                    idx[t + 2] = tmp;
                }
            }
            MeshTools.computeTangents(m, 0);
            // du/d(x,z) = row 0 of A, dv/d(x,z) = row 1: dP/du is column 0 of A^-1, dP/dv is column 1
            double i00 = a11 / det, i10 = -a10 / det, i01 = -a01 / det, i11 = a00 / det;
            double tx = i00, tz = i10, tl = Math.hypot(tx, tz);
            double bx = i01, bz = i11;
            // cross(n = +Y, t) = (t.z, 0, -t.x)
            double expectedW = ((tz / tl) * bx + (-tx / tl) * bz) < 0 ? -1 : 1;
            float[] t = m.tangents();
            for (int v = 0; v < 5; v++) {
                assertEquals(tx / tl, t[v * 4], 1e-4, "tangent x (trial " + trial + ")");
                assertEquals(0.0, t[v * 4 + 1], 1e-4);
                assertEquals(tz / tl, t[v * 4 + 2], 1e-4, "tangent z (trial " + trial + ")");
                assertEquals(expectedW, t[v * 4 + 3], "handedness (trial " + trial + ", det " + det + ")");
            }
        }
    }

    static double[] faceNormalOf(Mesh m, int tri) {
        float[] p = m.positions();
        int[] idx = m.indices();
        int a = idx[tri * 3], b = idx[tri * 3 + 1], c = idx[tri * 3 + 2];
        double ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
        double vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
        return new double[] {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
    }

    @Test
    void tangentsAreOrthonormalOnCurvedSurfaces() {
        for (Mesh m : new Mesh[] {Primitives.uvSphere(1f, 24, 12), Primitives.torus(2f, 0.5f, 24, 12), Primitives.capsule(1f, 2f, 16, 4)}) {
            float[] n = m.normals(), t = m.tangents();
            for (int v = 0; v < m.vertexCount(); v++) {
                double d = n[v * 3] * t[v * 4] + n[v * 3 + 1] * t[v * 4 + 1] + n[v * 3 + 2] * t[v * 4 + 2];
                assertEquals(0.0, d, 2e-4);
                double len = Math.sqrt(t[v * 4] * t[v * 4] + t[v * 4 + 1] * t[v * 4 + 1] + t[v * 4 + 2] * t[v * 4 + 2]);
                assertEquals(1.0, len, 1e-4);
            }
        }
    }

    @Test
    void tangentsNeedNormalsAndUvs() {
        Mesh m = new Mesh();
        m.addVertex(0f, 0f, 0f);
        assertThrows(IllegalStateException.class, () -> MeshTools.computeTangents(m, 0));
        m.enableNormals();
        assertThrows(IllegalStateException.class, () -> MeshTools.computeTangents(m, 0));
    }

    // ------------------------------------------------------------ degenerate input

    @Test
    void degenerateMeshesNeverProduceNaN() {
        for (int trial = 0; trial < 200; trial++) {
            Mesh m = new Mesh();
            m.enableUvs(0);
            int n = 4 + (int) rnd.range(0, 12);
            for (int i = 0; i < n; i++) {
                float x = (float) rnd.range(-1, 1), y = (float) rnd.range(-1, 1), z = (float) rnd.range(-1, 1);
                switch ((int) rnd.range(0, 4)) {
                    case 0 -> { /* random point */ }
                    case 1 -> { // coincident with an earlier vertex
                        if (m.vertexCount() > 0) {
                            int o = (int) rnd.range(0, m.vertexCount());
                            x = m.positions()[o * 3];
                            y = m.positions()[o * 3 + 1];
                            z = m.positions()[o * 3 + 2];
                        }
                    }
                    case 2 -> { // huge and tiny magnitudes
                        x *= 1e6f;
                        y *= 1e-6f;
                    }
                    default -> { // collinear with the origin axis
                        y = 0f;
                        z = 0f;
                    }
                }
                int v = m.addVertex(x, y, z);
                boolean sameUv = rnd.range(0, 1) < 0.4;
                m.setUv(0, v, sameUv ? 0.5f : (float) rnd.range(0, 1), sameUv ? 0.5f : (float) rnd.range(0, 1));
            }
            for (int t = 0; t < 2 * n; t++) {
                int a = (int) rnd.range(0, n), b = (int) rnd.range(0, n), c = (int) rnd.range(0, n);
                m.addTriangle(a, b, c); // may repeat vertices: collapsed triangles are part of the corpus
            }
            m.addVertex(0f, 0f, 0f); // an unreferenced vertex
            MeshTools.computeSmoothNormals(m);
            assertFiniteUnit(m, "smooth, trial " + trial);
            MeshTools.computeTangents(m, 0);
            checkFinite(m, "tangents, trial " + trial);
            Mesh copy = new Mesh();
            copy.enableUvs(0);
            for (int v = 0; v < m.vertexCount(); v++) {
                int c = copy.addVertex(m.positions()[v * 3], m.positions()[v * 3 + 1], m.positions()[v * 3 + 2]);
                copy.setUv(0, c, m.uvs(0)[v * 2], m.uvs(0)[v * 2 + 1]);
            }
            for (int i = 0; i < m.indexCount(); i += 3) {
                copy.addTriangle(m.indices()[i], m.indices()[i + 1], m.indices()[i + 2]);
            }
            MeshTools.computeNormalsWithCrease(copy, (float) rnd.range(0, 3));
            assertFiniteUnit(copy, "crease, trial " + trial);
            MeshTools.computeTangents(copy, 0);
            checkFinite(copy, "crease tangents, trial " + trial);
        }
    }

    private static void checkFinite(Mesh m, String what) {
        for (int v = 0; v < m.vertexCount(); v++) {
            for (int k = 0; k < 4; k++) {
                assertTrue(Float.isFinite(m.tangents()[v * 4 + k]), what + ": tangent " + v);
            }
            assertEquals(1.0, Math.sqrt(m.tangents()[v * 4] * m.tangents()[v * 4] + m.tangents()[v * 4 + 1] * m.tangents()[v * 4 + 1]
                    + m.tangents()[v * 4 + 2] * m.tangents()[v * 4 + 2]), 1e-4, what + ": tangent " + v + " is unit length");
        }
    }
}
