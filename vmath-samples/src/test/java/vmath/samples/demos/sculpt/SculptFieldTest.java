package vmath.samples.demos.sculpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.geo.Sdf;
import vmath.geo.Sdfs;
import vmath.geo.SurfaceNets;

/**
 * Tests of the sculpt field, the mesh check and the options: the grid reproduces the shape that it
 * samples, a stamp adds or removes material, and the surface nets of an untouched field are closed.
 *
 * <p><b>Thread safety.</b> Each test builds its own field; the tests may run in parallel.
 */
class SculptFieldTest {

    private static int inside(SculptField f) {
        int count = 0;
        for (int k = 0; k <= f.cells(); k++) {
            for (int j = 0; j <= f.cells(); j++) {
                for (int i = 0; i <= f.cells(); i++) {
                    if (f.sample(i, j, k) < 0f) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @Test
    void theGridReproducesTheSphereItSamples() {
        Sdf sphere = Sdfs.sphere(0.1f, -0.2f, 0.3f, 1f);
        SculptField f = new SculptField(32, 2f, sphere);
        assertEquals(0.125f, f.cellSize(), 1e-6f);
        for (int i = 0; i <= 32; i += 5) {
            float x = -2f + i * f.cellSize();
            assertEquals(sphere.distance(x, 0.25f, -0.25f), f.distance(x, 0.25f, -0.25f), 1e-4f, "exact at a sample");
        }
        // between samples the interpolation of a smooth field is close
        assertEquals(sphere.distance(0.37f, 0.11f, 0.53f), f.distance(0.37f, 0.11f, 0.53f), 0.02f);
    }

    @Test
    void outsideTheGridTheFieldGrowsWithTheDistanceFromIt() {
        SculptField f = new SculptField(16, 2f, Sdfs.sphere(0f, 0f, 0f, 1f));
        float atEdge = f.distance(2f, 0f, 0f);
        assertEquals(atEdge + 3f, f.distance(5f, 0f, 0f), 1e-4f);
        assertTrue(f.distance(0f, 9f, 0f) > 6f);
    }

    @Test
    void anAddStampAddsMaterialAndACarveStampRemovesIt() {
        SculptField f = new SculptField(48, 2f, Sdfs.sphere(0f, 0f, 0f, 0.8f));
        int before = inside(f);
        int written = f.stamp(true, 0.9f, 0f, 0f, 0.4f, 0.1f);
        assertTrue(written > 0 && written < 49 * 49 * 49, "a stamp rewrites the samples near the brush only: " + written);
        int added = inside(f);
        assertTrue(added > before, "material was added: " + before + " -> " + added);
        f.stamp(false, -0.6f, 0f, 0f, 0.4f, 0.1f);
        int carved = inside(f);
        assertTrue(carved < added, "material was removed: " + added + " -> " + carved);
        assertTrue(f.distance(0.9f, 0f, 0f) < 0f, "the added blob is solid at its centre");
        assertTrue(f.distance(-0.6f, 0f, 0f) > 0f, "the carved spot is empty at its centre");
    }

    @Test
    void aStampFarFromTheShapeLeavesTheSamplesFarFromItAlone() {
        SculptField f = new SculptField(32, 2f, Sdfs.sphere(0f, 0f, 0f, 0.5f));
        float far = f.sample(0, 0, 0);
        f.stamp(true, 0.3f, 0f, 0f, 0.2f, 0.1f);
        assertEquals(far, f.sample(0, 0, 0), 0f);
    }

    @Test
    void theSurfaceNetsOfAnUntouchedFieldAreClosed() {
        SculptField f = new SculptField(40, 2.4f, Sdfs.smoothUnion(Sdfs.sphere(-0.7f, 0f, 0f, 0.85f), Sdfs.sphere(0.7f, 0f, 0f, 0.85f), 0.4f));
        SurfaceNets nets = new SurfaceNets().normals(true);
        nets.mesh(f, -2.4f, -2.4f, -2.4f, 2.4f, 2.4f, 2.4f, 40, 40, 40);
        MeshCheck check = new MeshCheck();
        assertEquals(0, check.openEdges(nets.indices(), nets.triangleCount(), nets.vertexCount()));
        assertTrue(nets.triangleCount() > 1000);
    }

    @Test
    void theMeshCheckSeesAHoleAndARepeatedEdge() {
        SculptField f = new SculptField(24, 2f, Sdfs.sphere(0f, 0f, 0f, 1f));
        SurfaceNets nets = new SurfaceNets();
        nets.mesh(f, -2f, -2f, -2f, 2f, 2f, 2f, 24, 24, 24);
        MeshCheck check = new MeshCheck();
        int[] indices = nets.indices().clone();
        int triangles = nets.triangleCount();
        assertEquals(0, check.openEdges(indices, triangles, nets.vertexCount()));
        assertTrue(check.openEdges(indices, triangles - 1, nets.vertexCount()) > 0, "a missing triangle leaves unpaired edges");
        assertTrue(check.unpaired() > 0);
        // the same triangle twice gives repeated directed edges
        int[] doubled = new int[(triangles + 1) * 3];
        System.arraycopy(indices, 0, doubled, 0, triangles * 3);
        System.arraycopy(indices, 0, doubled, triangles * 3, 3);
        check.openEdges(doubled, triangles + 1, nets.vertexCount());
        assertEquals(3, check.repeated());
    }

    @Test
    void theRayCastFindsTheSurfaceOfTheSampledField() {
        SculptField f = new SculptField(48, 2.4f, Sdfs.sphere(0f, 0f, 0f, 1f));
        Sdfs.Hit hit = new Sdfs.Hit();
        assertTrue(Sdfs.raycast(f, 0f, 0f, 5f, 0f, 0f, -1f, 0f, 20f, 256, 1e-3f, hit));
        assertEquals(1f, hit.z, 0.02f);
        assertFalse(Sdfs.raycast(f, 0f, 3f, 5f, 0f, 0f, -1f, 0f, 20f, 256, 1e-3f, hit), "a ray beside the sphere misses");
    }

    @Test
    void aFieldNeedsSomeCellsAndTheOptionsParse() {
        assertThrows(IllegalArgumentException.class, () -> new SculptField(2, 1f, Sdfs.sphere(0f, 0f, 0f, 0.5f)));
        assertThrows(IllegalArgumentException.class, () -> new SculptField(8, 0f, Sdfs.sphere(0f, 0f, 0f, 0.5f)));
        SculptOptions d = SculptOptions.parse(List.of());
        assertEquals(64, d.grid());
        assertTrue(d.normals());
        assertEquals(0, d.projection());
        assertFalse(d.verify());
        assertFalse(d.manifold());
        SculptOptions o = SculptOptions.parse(List.of("--grid", "32", "--no-normals", "--projection", "2", "--verify", "--manifold"));
        assertEquals(32, o.grid());
        assertFalse(o.normals());
        assertEquals(2, o.projection());
        assertTrue(o.verify());
        assertTrue(o.manifold());
        assertThrows(IllegalArgumentException.class, () -> SculptOptions.parse(List.of("--grid", "4")));
        assertThrows(IllegalArgumentException.class, () -> SculptOptions.parse(List.of("--bogus")));
    }
}
