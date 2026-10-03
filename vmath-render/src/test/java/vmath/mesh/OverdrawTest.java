package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OverdrawTest {

    /** {@code a} then {@code b} in one mesh (positions and triangles only). */
    private static Mesh concat(Mesh a, Mesh b) {
        Mesh m = new Mesh(a.vertexCount() + b.vertexCount(), a.triangleCount() + b.triangleCount());
        for (Mesh part : new Mesh[] {a, b}) {
            int base = m.vertexCount();
            float[] p = part.positions();
            for (int i = 0; i < part.vertexCount(); i++) {
                m.addVertex(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
            }
            int[] idx = part.indices();
            for (int t = 0; t < part.indexCount(); t += 3) {
                m.addTriangle(base + idx[t], base + idx[t + 1], base + idx[t + 2]);
            }
        }
        return m;
    }

    private static int[] sortedTriangles(Mesh m) {
        // a triangle as its rotated-to-smallest vertex order, so a comparison ignores the order of triangles but not their orientation
        long[] keys = new long[m.triangleCount()];
        int[] idx = m.indices();
        for (int t = 0; t < keys.length; t++) {
            int a = idx[t * 3], b = idx[t * 3 + 1], c = idx[t * 3 + 2];
            if (b < a && b < c) {
                int x = a; a = b; b = c; c = x;
            } else if (c < a && c < b) {
                int x = a; a = c; c = b; b = x;
            }
            keys[t] = ((long) a * 2_000_003L + b) * 2_000_003L + c;
        }
        Arrays.sort(keys);
        int[] out = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = Long.hashCode(keys[i]);
        }
        return out;
    }

    @Test
    void aSingleQuadFacingAViewHasNoOverdraw() {
        Mesh m = new Mesh();
        m.addVertex(0, 0, 0);
        m.addVertex(1, 0, 0);
        m.addVertex(1, 1, 0);
        m.addVertex(0, 1, 0);
        m.addQuad(0, 1, 2, 3); // counter-clockwise seen from +z
        assertEquals(1f, Overdraw.measure(m, 64), 1e-6f);
    }

    @Test
    void nearFirstBeatsFarFirstForTwoStackedQuads() {
        Mesh far = new Mesh(), near = new Mesh();
        for (Mesh q : new Mesh[] {far, near}) {
            float z = q == far ? 0f : 1f;
            q.addVertex(0, 0, z);
            q.addVertex(1, 0, z);
            q.addVertex(1, 1, z);
            q.addVertex(0, 1, z);
            q.addQuad(0, 1, 2, 3);
        }
        float nearFirst = Overdraw.measure(concat(near, far), 64);
        float farFirst = Overdraw.measure(concat(far, near), 64);
        assertEquals(1f, nearFirst, 1e-6f, "the far quad fails the depth test");
        assertTrue(farFirst > 1.4f, "the far quad is shaded and then overwritten: " + farFirst);
    }

    @Test
    void backFacesAreCulled() {
        Mesh m = Primitives.box(1f, 1f, 1f);
        // a closed outward-wound box seen from any axis shows exactly one face: no overdraw
        assertEquals(1f, Overdraw.measure(m, 64), 0.02f);
    }

    @Test
    void nestedSpheresInnerFirstIsFixed() {
        Mesh m = concat(Primitives.uvSphere(0.5f, 32, 16), Primitives.uvSphere(1f, 32, 16));
        MeshOptimizer.optimizeVertexCache(m, 32);
        int[] before = sortedTriangles(m);
        Overdraw.Result r = Overdraw.optimize(m, 96, 1.05f);
        assertTrue(r.applied(), "an improvement exists: " + r);
        assertTrue(r.overdrawAfter() < r.overdrawBefore() - 0.05f, "overdraw must drop clearly: " + r);
        assertTrue(r.acmrAfter() <= r.acmrBefore() * 1.05f + 1e-6f, "the cache must not suffer: " + r);
        assertEquals(r.overdrawAfter(), Overdraw.measure(m, 96), 1e-6f, "the mesh really has the reported order");
        assertTrue(Arrays.equals(before, sortedTriangles(m)), "the same triangles, only reordered");
    }

    @Test
    void neverMakesAMeshWorse() {
        Mesh[] meshes = {Primitives.torus(1f, 0.3f, 48, 24), Primitives.icoSphere(1f, 3), Primitives.capsule(0.5f, 1f, 24, 8), Primitives.box(1f, 2f, 3f)};
        for (Mesh m : meshes) {
            MeshOptimizer.optimizeVertexCache(m, 32);
            int[] before = sortedTriangles(m);
            Overdraw.Result r = Overdraw.optimize(m, 96, 1.05f);
            assertTrue(r.overdrawAfter() <= r.overdrawBefore() + 1e-6f, r.toString());
            assertTrue(r.acmrAfter() <= r.acmrBefore() * 1.05f + 1e-6f, r.toString());
            assertTrue(Arrays.equals(before, sortedTriangles(m)));
            if (!r.applied()) {
                assertEquals(r.overdrawBefore(), r.overdrawAfter());
            }
        }
    }

    @Test
    void tinyMeshesAreLeftAlone() {
        Mesh m = Primitives.plane(1f, 1f, 1, 1);
        Overdraw.Result r = Overdraw.optimize(m, 32, 1.05f);
        assertFalse(r.applied());
    }
}
