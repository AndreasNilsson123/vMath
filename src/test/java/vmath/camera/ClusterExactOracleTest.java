package vmath.camera;

import vmath.Report;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.core.Vec3f;
import vmath.geo.Intersectionf;
import vmath.geo.Trianglef;

/**
 * An exact oracle for point lights: whether a sphere touches the frustum slice of a cluster (a convex polyhedron with eight corners), by the distance from the centre to
 * it (zero inside, otherwise the distance to the nearest of its twelve face triangles). The assignment must list every light the oracle says touches a cluster;
 * what it lists beyond that is the cost of using the box and plane tests, and is measured here.
 */
class ClusterExactOracleTest {

    private static boolean sphereTouchesSlice(ClusterGrid g, int col, int row, int slice, float cx, float cy, float cz, float r) {
        float[] sl = new float[4];
        g.slopes(col, row, sl, 0);
        float dNear = g.sliceBoundary(slice), dFar = g.sliceBoundary(slice + 1);
        // eight corners: slope * depth at the near and far depth
        Vec3f[] v = new Vec3f[8];
        int i = 0;
        for (float d : new float[] {dNear, dFar}) {
            for (int yy = 0; yy < 2; yy++) {
                for (int xx = 0; xx < 2; xx++) {
                    v[i++] = new Vec3f(sl[xx] * d, sl[2 + yy] * d, -d);
                }
            }
        }
        // inside?
        float depth = -cz;
        boolean inside = depth >= dNear && depth <= dFar && cx >= sl[0] * depth && cx <= sl[1] * depth && cy >= sl[2] * depth && cy <= sl[3] * depth;
        if (inside) {
            return true;
        }
        // corners are indexed (depth, y, x): faces as quads of corner indices
        int[][] quads = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        Vec3f p = new Vec3f(cx, cy, cz);
        double best = Double.MAX_VALUE;
        for (int[] q : quads) {
            best = Math.min(best, Intersectionf.pointTriangleDistanceSquared(p, Trianglef.of(v[q[0]], v[q[1]], v[q[2]])));
            best = Math.min(best, Intersectionf.pointTriangleDistanceSquared(p, Trianglef.of(v[q[0]], v[q[2]], v[q[3]])));
        }
        return best <= (double) r * r;
    }

    @Test
    void everyClusterTheSphereReallyTouchesIsListedAndTheExtrasAreFew() {
        long exact = 0, listed = 0, missing = 0;
        for (boolean yDown : new boolean[] {false, true}) {
            ClusterGrid g = ClusterGrid.of(1.0f, 16f / 9f, 0.1f, 200f, 1920, 1080, 64, 24, yDown);
            Random rnd = new Random(77);
            ClusterLights lights = new ClusterLights();
            int n = 120;
            float[] x = new float[n], y = new float[n], z = new float[n], r = new float[n];
            for (int i = 0; i < n; i++) {
                float d = (float) Math.exp(Math.log(0.2) + rnd.nextDouble() * (Math.log(150) - Math.log(0.2)));
                x[i] = (rnd.nextFloat() * 2.6f - 1.3f) * d * g.tanHalfFovX();
                y[i] = (rnd.nextFloat() * 2.6f - 1.3f) * d * g.tanHalfFovY();
                z[i] = -d;
                r[i] = (float) Math.exp(Math.log(0.2) + rnd.nextDouble() * (Math.log(40) - Math.log(0.2)));
                lights.addPoint(x[i], y[i], z[i], r[i]);
            }
            lights.assign(g);
            for (int s = 0; s < g.slices(); s++) {
                for (int row = 0; row < g.tilesY(); row++) {
                    for (int col = 0; col < g.tilesX(); col++) {
                        int c = g.index(col, row, s);
                        for (int l = 0; l < n; l++) {
                            boolean really = sphereTouchesSlice(g, col, row, s, x[l], y[l], z[l], r[l]);
                            boolean in = lights.contains(c, l);
                            if (really) {
                                exact++;
                                if (!in) {
                                    missing++;
                                    if (missing < 5) {
                                        Report.println("MISSING light " + l + " cluster " + c + " (" + col + "," + row + "," + s + ") yDown " + yDown);
                                    }
                                }
                            }
                            if (in) {
                                listed++;
                            }
                        }
                    }
                }
            }
        }
        Report.printf("CLEXACT point lights: %d pairs touch a cluster exactly, %d are listed (%.1f%% extra), %d missing%n", exact, listed, 100.0 * (listed - exact) / exact, missing);
        assertTrue(missing == 0, missing + " pairs that the exact test says touch are not listed");
        assertTrue(listed <= exact * 1.5, "the extras should be a minority: " + listed + " listed for " + exact + " exact");
    }
}
