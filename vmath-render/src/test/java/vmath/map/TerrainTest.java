package vmath.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.geo.TerrainRgb;

/** {@link TerrainGrid}, {@link Hillshade}, {@link ColorRamp}, {@link TerrainShading} and {@link Viewshed}. */
class TerrainTest {

    /** A plane z = a x + b y + c (x east, y north) over 65 x 65 nodes of 10 m. */
    private static TerrainGrid plane(double a, double b, double c, double groundScale) {
        int n = 65;
        float[] h = new float[n * n];
        for (int r = 0; r < n; r++) {
            for (int col = 0; col < n; col++) {
                double x = col * 10.0, y = (n - 1 - r) * 10.0;
                h[r * n + col] = (float) (a * x + b * y + c);
            }
        }
        return new TerrainGrid(h, n, n, 0, 0, 640, 640, groundScale);
    }

    private static TerrainGrid rough(long seed, int n, double cell, double amplitude) {
        Random rnd = new Random(seed);
        float[] h = new float[n * n];
        // a few octaves of smoothed noise on a lattice
        for (int octave = 0; octave < 4; octave++) {
            int m = 3 << octave;
            float[] lattice = new float[(m + 1) * (m + 1)];
            for (int i = 0; i < lattice.length; i++) {
                lattice[i] = (float) (rnd.nextGaussian() * amplitude / (1 << octave));
            }
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    double fx = (double) c / (n - 1) * m, fy = (double) r / (n - 1) * m;
                    int ix = Math.min(m - 1, (int) fx), iy = Math.min(m - 1, (int) fy);
                    double tx = fx - ix, ty = fy - iy;
                    double v = (lattice[iy * (m + 1) + ix] * (1 - tx) + lattice[iy * (m + 1) + ix + 1] * tx) * (1 - ty)
                            + (lattice[(iy + 1) * (m + 1) + ix] * (1 - tx) + lattice[(iy + 1) * (m + 1) + ix + 1] * tx) * ty;
                    h[r * n + c] += (float) v;
                }
            }
        }
        return new TerrainGrid(h, n, n, 0, 0, cell * (n - 1), cell * (n - 1), 1.0);
    }

    // ---------------------------------------------------------------- grid

    @Test
    void theGridInterpolatesAndKnowsItsGeometry() {
        TerrainGrid g = plane(0.1, 0.2, 5.0, 1.0);
        assertEquals(10.0, g.cellEast());
        assertEquals(0.0, g.xOf(0));
        assertEquals(640.0, g.yOf(0));
        assertEquals(0.1 * 123.4 + 0.2 * 321.0 + 5.0, g.heightAt(123.4, 321.0), 1e-3);
        assertTrue(Double.isNaN(g.heightAt(-1.0, 0.0)));
        assertEquals(5.0f, g.at(0, 64), 1e-4f);
        float[] r = new float[2];
        g.range(r);
        assertEquals(5.0f, r[0], 1e-3f);
        assertEquals(0.1 * 640 + 0.2 * 640 + 5.0, r[1], 1e-2);
        assertThrows(IndexOutOfBoundsException.class, () -> g.at(65, 0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainGrid(new float[3], 2, 2, 0, 0, 1, 1, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainGrid(new float[4], 2, 2, 0, 0, 0, 1, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainGrid(new float[4], 2, 2, 0, 0, 1, 1, 0.0));
    }

    @Test
    void aGridCanBeMadeFromTerrainColours() {
        byte[] rgb = new byte[3 * 4];
        double[] heights = {0.0, 100.5, -20.25, 3000.0};
        for (int i = 0; i < 4; i++) {
            TerrainRgb.encode(TerrainRgb.Encoding.TERRARIUM, heights[i], rgb, 3 * i);
        }
        TerrainGrid g = TerrainGrid.fromTerrainRgb(TerrainRgb.Encoding.TERRARIUM, rgb, 2, 2, 0, 0, 10, 10, 1.0);
        assertEquals(100.5f, g.at(1, 0), 1e-2f);
        assertEquals(-20.25f, g.at(0, 1), 1e-2f);
        assertEquals(3000.0f, g.at(1, 1), 1e-2f);
    }

    // ---------------------------------------------------------------- hillshade

    @Test
    void theGradientOfAPlaneIsItsSlopeOnTheGround() {
        TerrainGrid g = plane(0.1, -0.05, 100.0, 1.0);
        double[] grad = new double[2];
        for (int[] node : new int[][] {{0, 0}, {32, 32}, {64, 64}, {0, 64}, {64, 0}}) {
            Hillshade.gradient(g, node[0], node[1], grad);
            // the edge nodes repeat the nearest node: the gradient there is that of the one-sided Horn stencil, which is half of the plane's on the edge
            double edgeX = node[0] == 0 || node[0] == 64 ? 0.5 : 1.0, edgeY = node[1] == 0 || node[1] == 64 ? 0.5 : 1.0;
            assertEquals(0.1 * edgeX, grad[0], 1e-5, "dz/dx at " + node[0] + "," + node[1]);
            assertEquals(-0.05 * edgeY, grad[1], 1e-5, "dz/dy at " + node[0] + "," + node[1]);
        }
        TerrainGrid half = plane(0.1, 0.0, 0.0, 0.5);
        Hillshade.gradient(half, 32, 32, grad);
        assertEquals(0.2, grad[0], 1e-5, "ground cells are half as large: twice the slope");
    }

    @Test
    void aSlopeFacingTheLightIsAsBrightAsTheGeometrySays() {
        double slope = Math.toRadians(20);
        TerrainGrid g = plane(-Math.tan(slope), 0.0, 1000.0, 1.0);          // falls towards the east: faces east
        float[] shade = new float[65 * 65];
        Hillshade.shade(g, Math.toRadians(90), Math.toRadians(45), 1.0, shade);
        assertEquals(Math.cos(Math.toRadians(45) - slope), shade[32 * 65 + 32], 1e-4);          // sun from the east, 45 up: the angle between the normal and the sun is 25 degrees
        Hillshade.shade(g, Math.toRadians(270), Math.toRadians(45), 1.0, shade);
        assertEquals(Math.cos(Math.toRadians(45) + slope), shade[32 * 65 + 32], 1e-4, "from the west the same slope is in the shade of its own tilt");
        TerrainGrid flat = plane(0, 0, 10.0, 1.0);
        Hillshade.shade(flat, 1.0, Math.toRadians(30), 1.0, shade);
        assertEquals(0.5, shade[1000], 1e-6, "a flat node has the sine of the altitude");
        Hillshade.shade(g, Math.toRadians(90), Math.toRadians(45), 2.0, shade);
        assertEquals(Math.cos(Math.toRadians(45) - Math.atan(2 * Math.tan(slope))), shade[32 * 65 + 32], 1e-4, "the exaggeration multiplies the gradient");
        assertThrows(IllegalArgumentException.class, () -> Hillshade.shade(g, 0, 2.0, 1.0, shade));
        assertThrows(IllegalArgumentException.class, () -> Hillshade.shade(g, 0, 0.5, 0.0, shade));
        assertThrows(IllegalArgumentException.class, () -> Hillshade.shade(g, 0, 0.5, 1.0, new float[3]));
    }

    @Test
    void slopeAndAspectOfAPlane() {
        double slope = Math.toRadians(30);
        TerrainGrid g = plane(0.0, Math.tan(slope), 500.0, 1.0);           // rises towards the north: falls towards the south
        float[] s = new float[65 * 65], a = new float[65 * 65];
        Hillshade.slope(g, s);
        Hillshade.aspect(g, a);
        assertEquals(slope, s[32 * 65 + 20], 1e-4);
        assertEquals(Math.PI, a[32 * 65 + 20], 1e-4, "downhill is south");
        TerrainGrid east = plane(-0.3, 0.0, 500.0, 1.0);
        Hillshade.aspect(east, a);
        assertEquals(Math.PI / 2, a[32 * 65 + 20], 1e-4);
        TerrainGrid west = plane(0.3, 0.0, 500.0, 1.0);
        Hillshade.aspect(west, a);
        assertEquals(3 * Math.PI / 2, a[32 * 65 + 20], 1e-4);
        Hillshade.aspect(plane(0, 0, 3.0, 1.0), a);
        assertTrue(Float.isNaN(a[100]), "a flat node faces nowhere");
    }

    @Test
    void noDataGivesNoShadeAndDoesNotPoisonItsNeighbours() {
        TerrainGrid g = plane(0.1, 0.0, 10.0, 1.0);
        g.heights()[32 * 65 + 32] = Float.NaN;
        float[] shade = new float[65 * 65];
        Hillshade.shade(g, 0.0, 0.7, 1.0, shade);
        assertTrue(Float.isNaN(shade[32 * 65 + 32]));
        assertFalse(Float.isNaN(shade[32 * 65 + 33]));
        assertFalse(Float.isNaN(shade[31 * 65 + 31]));
    }

    // ---------------------------------------------------------------- ramps

    @Test
    void aLinearRampBlendsAndClamps() {
        ColorRamp r = ColorRamp.linear(new double[] {0, 100, 200}, new int[] {0x000000FF, 0xFF0000FF, 0xFFFFFF80});
        assertEquals(0x000000FF, r.colorAt(-50));
        assertEquals(0x800000FF, r.colorAt(50));
        assertEquals(0xFF0000FF, r.colorAt(100));
        assertEquals(0xFF8080C0, r.colorAt(150));
        assertEquals(0xFFFFFF80, r.colorAt(1e9));
        assertEquals(0, r.colorAt(Double.NaN));
        double[] range = new double[2];
        r.range(range);
        assertEquals(200.0, range[1]);
        assertFalse(r.isStepped());
        assertThrows(IllegalArgumentException.class, () -> ColorRamp.linear(new double[] {1, 1}, new int[2]));
        assertThrows(IllegalArgumentException.class, () -> ColorRamp.linear(new double[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> ColorRamp.linear(new double[] {1, 2}, new int[1]));
    }

    @Test
    void aSteppedRampChangesAtItsEdges() {
        ColorRamp r = ColorRamp.steps(new double[] {0, 10}, new int[] {1, 2, 3});
        assertEquals(1, r.colorAt(-0.001));
        assertEquals(2, r.colorAt(0.0), "an edge belongs to the step above it");
        assertEquals(2, r.colorAt(9.999));
        assertEquals(3, r.colorAt(10.0));
        assertTrue(r.isStepped());
        assertThrows(IllegalArgumentException.class, () -> ColorRamp.steps(new double[] {0, 10}, new int[] {1, 2}));
    }

    @Test
    void aRelativeRampMovesWithTheReference() {
        double[] offsets = {-300.0, 0.0};
        int[] colors = {0x00FF00FF, 0xFFFF00FF, 0xFF0000FF};
        ColorRamp low = ColorRamp.relativeSteps(1000.0, offsets, colors);
        ColorRamp high = ColorRamp.relativeSteps(2000.0, offsets, colors);
        assertEquals(0xFFFF00FF, low.colorAt(800.0));
        assertEquals(0x00FF00FF, high.colorAt(800.0), "the same terrain is well below a higher aircraft");
        assertEquals(0xFF0000FF, low.colorAt(1000.0));
        assertEquals(0xFF0000FF, high.colorAt(2500.0));
        ColorRamp blend = ColorRamp.relativeLinear(500.0, new double[] {-100, 100}, new int[] {0x000000FF, 0xFFFFFFFF});
        assertEquals(0x808080FF, blend.colorAt(500.0));
        assertThrows(IllegalArgumentException.class, () -> ColorRamp.relativeSteps(Double.NaN, offsets, colors));
    }

    @Test
    void theBakedRowIsWhatTheShaderLooksUp() {
        ColorRamp r = ColorRamp.steps(new double[] {100, 200}, new int[] {0xAA, 0xBB, 0xCC});
        ColorRamp.Baked b = r.bake(0f, 400f, 4);                  // texels of 100: [0,100) [100,200) [200,300) [300,400]
        assertEquals(100f, b.quantum());
        assertEquals(0, b.indexOf(-5f));
        assertEquals(1, b.indexOf(100f));
        assertEquals(3, b.indexOf(400f));
        assertEquals(3, b.indexOf(9999f));
        assertEquals(0xAA, b.colorAt(99.9f));
        assertEquals(0xBB, b.colorAt(150f));
        assertEquals(0xCC, b.colorAt(350f));
        assertEquals(0, b.colorAt(Float.NaN));
        byte[] row = new byte[16];
        b.writeRgba(row);
        assertEquals((byte) 0xBB, row[7]);
        assertThrows(IllegalArgumentException.class, () -> b.writeRgba(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> r.bake(5f, 5f, 4));
    }

    // ---------------------------------------------------------------- shading

    @Test
    void thePixelIsTheRampDarkenedByTheShade() {
        TerrainGrid g = plane(-0.2, 0.0, 300.0, 1.0);
        ColorRamp.Baked ramp = ColorRamp.linear(new double[] {0, 400}, new int[] {0x204060FF, 0xA0C0E0FF}).bake(0f, 400f, 256);
        byte[] rgba = new byte[4 * 65 * 65];
        TerrainShading.render(g, ramp, Math.toRadians(90), Math.toRadians(40), 1.0, 1.0, rgba);
        int o = 4 * (32 * 65 + 32);
        double shade = Hillshade.shadeOf(-0.2, 0.0, 1.0, Hillshade.sunVector(Math.toRadians(90), Math.toRadians(40)));
        int c = ramp.colorAt(g.at(32, 32));
        assertEquals(Math.round(((c >>> 24) & 255) * shade), rgba[o] & 255);
        assertEquals(Math.round(((c >>> 8) & 255) * shade), rgba[o + 2] & 255);
        assertEquals(255, rgba[o + 3] & 255);
        TerrainShading.render(g, ramp, Math.toRadians(90), Math.toRadians(40), 1.0, 0.0, rgba);
        assertEquals((c >>> 24) & 255, rgba[o] & 255, "no strength: the ramp alone");
        g.heights()[32 * 65 + 32] = Float.NaN;
        TerrainShading.render(g, ramp, 0.0, 0.5, 1.0, 0.5, rgba);
        assertEquals(0, rgba[o + 3]);
        assertThrows(IllegalArgumentException.class, () -> TerrainShading.render(g, ramp, 0.0, 0.5, 1.0, 1.5, rgba));
        assertThrows(IllegalArgumentException.class, () -> TerrainShading.render(g, ramp, 0.0, 0.5, 1.0, 0.5, new byte[10]));
    }

    @Test
    void theShaderSourceNamesItsUniforms() {
        var caps = vmath.gl.GraphicsCapabilities.baseline();
        String fs = TerrainShader.fragmentSource(caps);
        for (String u : new String[] {TerrainShader.U_HEIGHTS, TerrainShader.U_RAMP, TerrainShader.U_RAMP_RANGE, TerrainShader.U_RAMP_TEXELS, TerrainShader.U_SUN, TerrainShader.U_CELL,
                TerrainShader.U_EXAGGERATION, TerrainShader.U_STRENGTH}) {
            assertTrue(fs.contains("uniform") && fs.contains(u), u);
        }
        assertTrue(TerrainShader.vertexSource(caps).startsWith("#version 330"));
        double[] cell = TerrainShader.cell(plane(0, 0, 0, 0.5));
        assertEquals(5.0, cell[0]);
        assertEquals(5.0, cell[1]);
    }

    // ---------------------------------------------------------------- viewshed

    @Test
    void aWallHidesWhatIsBehindIt() {
        int n = 101;
        float[] h = new float[n * n];
        for (int r = 0; r < n; r++) {
            h[r * n + 60] = 200f;                           // a wall, north to south, 20 m wide at column 60
        }
        TerrainGrid g = new TerrainGrid(h, n, n, 0, 0, 1000, 1000, 1.0);
        byte[] seen = new byte[n * n];
        int count = Viewshed.compute(g, 300.0, 500.0, 2.0, 0.0, 600.0, false, seen);
        assertTrue(seen[50 * n + 55] == 1, "in front of the wall");
        assertTrue(seen[50 * n + 60] == 1, "the wall itself is seen");
        assertEquals(0, seen[50 * n + 80], "behind the wall");
        assertEquals(0, seen[45 * n + 90]);
        assertTrue(count > 1000 && count < 0.8 * n * n, "count " + count);
        assertEquals(1, seen[50 * n + 30]);
        // a tall observer sees over the wall
        byte[] over = new byte[n * n];
        Viewshed.compute(g, 300.0, 500.0, 500.0, 0.0, 600.0, false, over);
        assertEquals(1, over[50 * n + 80]);
    }

    @Test
    void curvatureShortensTheReach() {
        int n = 201;
        TerrainGrid g = new TerrainGrid(new float[n * n], n, n, 0, 0, 40_000, 40_000, 1.0);        // flat, 200 m cells
        byte[] flat = new byte[n * n], curved = new byte[n * n];
        int a = Viewshed.compute(g, 20_000.0, 20_000.0, 10.0, 0.0, 20_000.0, false, flat);
        int b = Viewshed.compute(g, 20_000.0, 20_000.0, 10.0, 0.0, 20_000.0, true, curved);
        assertTrue(b < a, "the horizon of an eye 10 m up is about 11 km: " + b + " < " + a);
        // the horizon distance of the model: sqrt(2 R h / (1 - k)) = about 12 km
        double expected = Math.sqrt(2 * Viewshed.EARTH_RADIUS * 10.0 / (1 - Viewshed.REFRACTION));
        assertEquals(1, curved[100 * n + 100 + (int) (expected * 0.9 / 200)]);
        assertEquals(0, curved[100 * n + 100 + (int) (expected * 1.1 / 200)]);
    }

    @Test
    void theSweepAgreesWithTheExactTestOnRoughTerrain() {
        TerrainGrid g = rough(5, 129, 30.0, 150.0);
        byte[] seen = new byte[129 * 129];
        double ox = 64 * 30.0, oy = 64 * 30.0;
        long t0 = System.nanoTime();
        int count = Viewshed.compute(g, ox, oy, 10.0, 2.0, 1800.0, false, seen);
        long t1 = System.nanoTime();
        int agree = 0, total = 0, exactSeen = 0;
        for (int r = 0; r < 129; r++) {
            for (int c = 0; c < 129; c++) {
                double x = g.xOf(c), y = g.yOf(r);
                double d = Math.hypot(x - ox, y - oy);
                if (d > 1700.0 || d < 60.0) {
                    continue;
                }
                boolean exact = Viewshed.isVisible(g, ox, oy, 10.0, x, y, 2.0, false);
                exactSeen += exact ? 1 : 0;
                agree += exact == (seen[r * 129 + c] == 1) ? 1 : 0;
                total++;
            }
        }
        assertTrue(exactSeen > 0.1 * total && exactSeen < 0.95 * total, "the terrain hides some and shows some: " + exactSeen + " of " + total);
        assertTrue(agree > 0.97 * total, "agreement " + agree + " of " + total);
        System.out.printf("viewshed: %d cells in range, %.0f ns per cell, %d visible, agreement %.2f%%%n", total, (t1 - t0) / (double) total, count, 100.0 * agree / total);
    }

    @Test
    void theViewshedChecksItsArguments() {
        TerrainGrid g = plane(0, 0, 0, 1.0);
        byte[] out = new byte[65 * 65];
        assertThrows(IllegalArgumentException.class, () -> Viewshed.compute(g, -5.0, 0.0, 1.0, 0.0, 100.0, false, out));
        assertThrows(IllegalArgumentException.class, () -> Viewshed.compute(g, 5.0, 5.0, -1.0, 0.0, 100.0, false, out));
        assertThrows(IllegalArgumentException.class, () -> Viewshed.compute(g, 5.0, 5.0, 1.0, 0.0, 0.0, false, out));
        assertThrows(IllegalArgumentException.class, () -> Viewshed.compute(g, 5.0, 5.0, 1.0, 0.0, 100.0, false, new byte[3]));
        assertTrue(Viewshed.isVisible(g, 5.0, 5.0, 1.0, 5.0, 5.0, 0.0, false));
        assertThrows(IllegalArgumentException.class, () -> Viewshed.isVisible(g, 5.0, 5.0, 1.0, 9999.0, 5.0, 0.0, false));
    }
}
