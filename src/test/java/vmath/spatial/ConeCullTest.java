package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.bulk.VisibilitySet;
import vmath.core.Rnd;
import vmath.core.Vec3f;

class ConeCullTest {

    final Rnd rnd = Rnd.create();

    /** A patch of triangles, all facing roughly along {@code axis}, with exact geometry to test against. */
    private static final class Patch {
        Vec3f axis;
        float[] verts;    // 9 floats per triangle
        float[] normals;  // 3 per triangle, unit
        int triangles;
        Vec3f center;
        float radius;
        final float[] cone = new float[4];
    }

    private Patch randomPatch(float roughness) {
        Patch p = new Patch();
        p.axis = rnd.nextVec3f().normalize();
        Vec3f helper = Math.abs(p.axis.x()) < 0.9f ? Vec3f.UNIT_X : Vec3f.UNIT_Y;
        Vec3f u = p.axis.cross(helper).normalize();
        Vec3f v = p.axis.cross(u);
        Vec3f origin = rnd.nextVec3f().mul(20f);
        p.triangles = 4 + (int) rnd.range(0, 60);
        p.verts = new float[p.triangles * 9];
        p.normals = new float[p.triangles * 3];
        double sx = 0, sy = 0, sz = 0;
        for (int t = 0; t < p.triangles; t++) {
            Vec3f[] c = new Vec3f[3];
            for (int k = 0; k < 3; k++) {
                float a = (float) rnd.range(-3, 3), b = (float) rnd.range(-3, 3);
                float h = (float) rnd.range(-1, 1) * roughness;
                c[k] = origin.add(u.mul(a)).add(v.mul(b)).add(p.axis.mul(h));
            }
            Vec3f n = c[1].sub(c[0]).cross(c[2].sub(c[0]));
            if (n.length() < 1e-3f) {
                t--; // degenerate: draw again
                continue;
            }
            n = n.normalize();
            if (n.dot(p.axis) < 0f) { // wind the triangle so that it faces along the axis side
                Vec3f tmp = c[1];
                c[1] = c[2];
                c[2] = tmp;
                n = n.negate();
            }
            for (int k = 0; k < 3; k++) {
                p.verts[t * 9 + k * 3] = c[k].x();
                p.verts[t * 9 + k * 3 + 1] = c[k].y();
                p.verts[t * 9 + k * 3 + 2] = c[k].z();
                sx += c[k].x();
                sy += c[k].y();
                sz += c[k].z();
            }
            p.normals[t * 3] = n.x();
            p.normals[t * 3 + 1] = n.y();
            p.normals[t * 3 + 2] = n.z();
        }
        int count = p.triangles * 3;
        p.center = new Vec3f((float) (sx / count), (float) (sy / count), (float) (sz / count));
        float r = 0f;
        for (int i = 0; i < count; i++) {
            r = Math.max(r, new Vec3f(p.verts[i * 3], p.verts[i * 3 + 1], p.verts[i * 3 + 2]).sub(p.center).length());
        }
        p.radius = r * 1.0001f + 1e-4f;
        ConeCull.computeCone(p.normals, 0, p.triangles, p.cone, 0);
        return p;
    }

    /** Ground truth: does any triangle face the eye (its plane's positive side is where the normal points)? */
    private static boolean anyFrontFacing(Patch p, Vec3f eye) {
        for (int t = 0; t < p.triangles; t++) {
            Vec3f v0 = new Vec3f(p.verts[t * 9], p.verts[t * 9 + 1], p.verts[t * 9 + 2]);
            Vec3f n = new Vec3f(p.normals[t * 3], p.normals[t * 3 + 1], p.normals[t * 3 + 2]);
            if (n.dot(eye.sub(v0)) > 0f) {
                return true;
            }
        }
        return false;
    }

    @Test
    void neverCullsAClusterThatHasAFrontFacingTriangle() {
        int culledAtLeastOnce = 0;
        for (int trial = 0; trial < 400; trial++) {
            Patch p = randomPatch((float) rnd.range(0, 0.6));
            for (int s = 0; s < 40; s++) {
                Vec3f eye = rnd.nextVec3f().mul((float) rnd.range(0.5, 60));
                boolean culled = ConeCull.backfacing(p.center.x(), p.center.y(), p.center.z(), p.radius,
                        p.cone[0], p.cone[1], p.cone[2], p.cone[3], eye.x(), eye.y(), eye.z());
                if (culled) {
                    culledAtLeastOnce++;
                    assertFalse(anyFrontFacing(p, eye), "culled a cluster with a visible triangle (trial " + trial + ")");
                }
            }
        }
        assertTrue(culledAtLeastOnce > 200, "the test should cull something, culled " + culledAtLeastOnce);
    }

    @Test
    void orthographicNeverCullsAVisibleCluster() {
        int culled = 0;
        for (int trial = 0; trial < 400; trial++) {
            Patch p = randomPatch((float) rnd.range(0, 0.6));
            for (int s = 0; s < 20; s++) {
                Vec3f view = rnd.nextVec3f().normalize(); // the camera looks along this direction
                if (ConeCull.backfacingOrthographic(p.cone[0], p.cone[1], p.cone[2], p.cone[3], view.x(), view.y(), view.z())) {
                    culled++;
                    for (int t = 0; t < p.triangles; t++) {
                        Vec3f n = new Vec3f(p.normals[t * 3], p.normals[t * 3 + 1], p.normals[t * 3 + 2]);
                        assertTrue(n.dot(view) >= 0f, "a triangle faces the camera but the cluster was culled");
                    }
                }
            }
        }
        assertTrue(culled > 100, "culled " + culled);
    }

    @Test
    void aFlatPatchIsCulledFromBehindAndKeptInFront() {
        float[] normals = {0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f};
        float[] cone = new float[4];
        ConeCull.computeCone(normals, 0, 3, cone, 0);
        assertEquals(1f, cone[2], 1e-6f);
        assertTrue(cone[3] < 0.01f, "a flat patch has a needle cone, cutoff " + cone[3]);
        // patch at the origin facing +z, radius 1
        assertTrue(ConeCull.backfacing(0f, 0f, 0f, 1f, 0f, 0f, 1f, cone[3], 0f, 0f, -10f), "seen from behind");
        assertFalse(ConeCull.backfacing(0f, 0f, 0f, 1f, 0f, 0f, 1f, cone[3], 0f, 0f, 10f), "seen from the front");
        assertFalse(ConeCull.backfacing(0f, 0f, 0f, 1f, 0f, 0f, 1f, cone[3], 0.5f, 0f, -0.2f), "an eye inside the sphere");
    }

    @Test
    void wideOrDegenerateClustersGetNoCone() {
        float[] cone = new float[4];
        float[] opposite = {0f, 0f, 1f, 0f, 0f, -1f};
        ConeCull.computeCone(opposite, 0, 2, cone, 0);
        assertEquals(1f, cone[3], "cancelling normals: no cone");
        float[] wide = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, -1f, 0f, 0f};
        ConeCull.computeCone(wide, 0, 4, cone, 0);
        assertEquals(1f, cone[3], "normals spread over a wide angle: no cone");
        ConeCull.computeCone(wide, 0, 0, cone, 0);
        assertEquals(1f, cone[3], "no triangles: no cone");
        assertFalse(ConeCull.backfacing(0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f, -5f), "cutoff 1 never culls");
        assertFalse(ConeCull.backfacingOrthographic(0f, 0f, 1f, 1f, 0f, 0f, 1f), "cutoff 1 never culls (orthographic)");
    }

    @Test
    void clustersContainerCullsAndGrows() {
        ConeCull.Clusters cl = new ConeCull.Clusters();
        for (int i = 0; i < 100; i++) { // even ones face +z, odd ones face -z, all small spheres on the origin plane
            float z = i % 2 == 0 ? 1f : -1f;
            cl.add(i * 3f, 0f, 0f, 0.5f, 0f, 0f, z, 0.05f);
        }
        assertEquals(100, cl.size());
        VisibilitySet vis = new VisibilitySet(100);
        vis.setAll(100);
        int culled = cl.cull(0f, 0f, 20f, vis); // eye on the +z side: the -z facers are back-facing
        assertEquals(50, culled);
        for (int i = 0; i < 100; i++) {
            assertEquals(i % 2 == 0, vis.get(i), "cluster " + i);
        }
        vis.setAll(100);
        assertEquals(50, cl.cullOrthographic(0f, 0f, -1f, vis)); // looking along -z is the same view
        for (int i = 0; i < 100; i++) {
            assertEquals(i % 2 == 0, vis.get(i), "cluster " + i);
        }
        cl.clear();
        assertEquals(0, cl.size());
    }
}
