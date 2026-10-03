package vmath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.util.SphericalHarmonics.Radiance;

class SphericalHarmonicsTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);
    private final Rnd rnd = Rnd.create();

    private double[] randomDirection() {
        double z = rng.nextDouble() * 2 - 1, a = rng.nextDouble() * 2 * Math.PI, r = Math.sqrt(1 - z * z);
        return new double[] {r * Math.cos(a), z, r * Math.sin(a)};
    }

    private float[] randomCoefficients(double scale) {
        float[] c = new float[SphericalHarmonics.LENGTH];
        for (int i = 0; i < c.length; i++) {
            c[i] = (float) ((rng.nextDouble() * 2 - 1) * scale);
        }
        return c;
    }

    /** The integral over the sphere of f, by a fine product quadrature in the azimuth and the cosine (midpoint), an oracle independent of the Gauss-Legendre code. */
    private static void integrate(Radiance f, int n, double[] out) {
        double[] rgb = new double[3];
        out[0] = out[1] = out[2] = 0;
        for (int i = 0; i < n; i++) {
            double z = -1 + 2 * (i + 0.5) / n, s = Math.sqrt(1 - z * z);
            for (int j = 0; j < 2 * n; j++) {
                double phi = Math.PI * (j + 0.5) / n;
                f.radiance(s * Math.cos(phi), z, s * Math.sin(phi), rgb);
                double w = (2.0 / n) * (Math.PI / n);
                out[0] += rgb[0] * w;
                out[1] += rgb[1] * w;
                out[2] += rgb[2] * w;
            }
        }
    }

    private static Radiance fromCoefficients(float[] c) {
        return (x, y, z, out) -> SphericalHarmonics.evaluate(c, x, y, z, out);
    }

    @Test
    void theBasisIsOrthonormal() {
        for (int a = 0; a < 9; a++) {
            for (int b = a; b < 9; b++) {
                final int ia = a, ib = b;
                double[] ya = new double[9], yb = new double[9], out = new double[3];
                integrate((x, y, z, o) -> {
                    SphericalHarmonics.basis(x, y, z, ya);
                    double va = ya[ia];
                    SphericalHarmonics.basis(x, y, z, yb);
                    o[0] = va * yb[ib];
                    o[1] = 0;
                    o[2] = 0;
                }, 120, out);
                assertEquals(a == b ? 1.0 : 0.0, out[0], 1e-3, "<Y" + a + ", Y" + b + ">");
            }
        }
        double[] y = new double[9];
        SphericalHarmonics.basis(0, 3, 0, y); // not unit: it is normalised
        assertEquals(0.4886025119029199, y[1], 1e-12);
        assertEquals(0.28209479177387814, y[0], 1e-15);
        assertEquals(-0.31539156525252005, y[6], 1e-12);
    }

    @Test
    void aConstantEnvironmentHasOnlyTheFirstCoefficient() {
        float[] c = new float[27];
        SphericalHarmonics.project((x, y, z, out) -> {
            out[0] = 0.5;
            out[1] = 1.0;
            out[2] = 2.0;
        }, 8, c);
        double s = 2 * Math.sqrt(Math.PI); // 1 / Y_00
        assertEquals(0.5 * s, c[0], 1e-5);
        assertEquals(1.0 * s, c[1], 1e-5);
        assertEquals(2.0 * s, c[2], 1e-5);
        for (int i = 3; i < 27; i++) {
            assertEquals(0.0, c[i], 1e-5, "coefficient " + i);
        }
        double[] v = new double[3];
        SphericalHarmonics.evaluate(c, 0.3, -0.5, 0.8, v);
        assertEquals(0.5, v[0], 1e-5);
        assertEquals(2.0, v[2], 1e-5);
        // addConstant builds the same thing
        float[] d = new float[27];
        SphericalHarmonics.addConstant(d, 0.5, 1.0, 2.0);
        assertEquals(c[0], d[0], 1e-5);
        assertEquals(c[2], d[2], 1e-5);
        // and the irradiance of a constant environment is pi times it
        SphericalHarmonics.irradiance(c, 0.2, 0.9, -0.3, v);
        assertEquals(Math.PI * 0.5, v[0], 1e-5);
        assertEquals(Math.PI * 2.0, v[2], 1e-5);
    }

    @Test
    void projectionRecoversBandLimitedFunctionsExactly() {
        for (int t = 0; t < 30; t++) {
            float[] c = randomCoefficients(2.0);
            float[] back = new float[27];
            SphericalHarmonics.project(fromCoefficients(c), 6, back);
            for (int i = 0; i < 27; i++) {
                assertEquals(c[i], back[i], 2e-5, "coefficient " + i + " trial " + t);
            }
        }
    }

    @Test
    void theClampedCosineHasTheKnownCoefficients() {
        // the clamped cosine max(0, z) is zonal about the z axis: only Y_00, Y_10 and Y_20 are non-zero, with sqrt(pi) / 2, sqrt(pi / 3) and sqrt(5 pi) / 8 (Ramamoorthi and Hanrahan)
        float[] c = new float[27];
        SphericalHarmonics.project((x, y, z, out) -> {
            out[0] = Math.max(0.0, z);
            out[1] = out[0];
            out[2] = out[0];
        }, 400, c);
        assertEquals(Math.sqrt(Math.PI) / 2, c[0], 1e-3);
        assertEquals(Math.sqrt(Math.PI / 3), c[3 * 2], 1e-3);
        assertEquals(Math.sqrt(5 * Math.PI) / 8, c[3 * 6], 1e-3);
        for (int i : new int[] {1, 3, 4, 5, 7, 8}) {
            assertEquals(0.0, c[3 * i], 1e-3, "coefficient " + i);
        }
        // and the irradiance of the clamped cosine environment facing the axis: the kernel applied twice
        double[] e = new double[3];
        SphericalHarmonics.irradiance(c, 0, 0, 1, e);
        double expected = Math.PI * Math.sqrt(Math.PI) / 2 * 0.28209479177387814 + 2 * Math.PI / 3 * Math.sqrt(Math.PI / 3) * 0.4886025119029199
                + Math.PI / 4 * Math.sqrt(5 * Math.PI) / 8 * 0.6307831305050401;
        assertEquals(expected, e[0], 2e-3);
    }

    @Test
    void irradianceMatchesDirectIntegrationForBandLimitedEnvironments() {
        double[] numeric = new double[3], viaSh = new double[3];
        for (int t = 0; t < 10; t++) {
            float[] c = randomCoefficients(1.0);
            double[] n = randomDirection();
            final double nx = n[0], ny = n[1], nz = n[2];
            Radiance env = fromCoefficients(c);
            double[] rgb = new double[3];
            integrate((x, y, z, out) -> {
                env.radiance(x, y, z, rgb);
                double cos = Math.max(0.0, x * nx + y * ny + z * nz);
                out[0] = rgb[0] * cos;
                out[1] = rgb[1] * cos;
                out[2] = rgb[2] * cos;
            }, 500, numeric);
            SphericalHarmonics.irradiance(c, nx, ny, nz, viaSh);
            for (int k = 0; k < 3; k++) {
                assertEquals(numeric[k], viaSh[k], 3e-3, "channel " + k + " trial " + t);
            }
            // convolving once and evaluating gives the same
            float[] conv = new float[27];
            SphericalHarmonics.convolveWithCosine(c, conv);
            double[] viaConv = new double[3];
            SphericalHarmonics.evaluate(conv, nx, ny, nz, viaConv);
            for (int k = 0; k < 3; k++) {
                assertEquals(viaSh[k], viaConv[k], 1e-5);
            }
            // the in-place form
            SphericalHarmonics.convolveWithCosine(c, c);
            for (int i = 0; i < 27; i++) {
                assertEquals(conv[i], c[i], 0.0);
            }
        }
    }

    @Test
    void aDirectionalLightGivesAnApproximateClampedCosine() {
        float[] c = new float[27];
        double[] l = {0.0, 1.0, 0.0};
        SphericalHarmonics.addDirectionalLight(c, l[0], l[1], l[2], 1.0, 2.0, 4.0);
        double[] e = new double[3];
        SphericalHarmonics.irradiance(c, 0, 1, 0, e);
        // facing the light the band-limited series gives 1.0625 (0.25 + 0.5 + 0.3125); the exact value is 1
        assertEquals(1.0625, e[0], 1e-5);
        assertEquals(2.125, e[1], 1e-5);
        assertEquals(4.25, e[2], 1e-5);
        for (double angle = 0; angle <= Math.PI / 2; angle += 0.1) {
            SphericalHarmonics.irradiance(c, Math.sin(angle), Math.cos(angle), 0, e);
            assertEquals(Math.cos(angle), e[0], 0.07, "angle " + angle);
        }
        // a surface facing away still sees a small negative value of the truncated series: a known artefact of two bands
        SphericalHarmonics.irradiance(c, 0, -1, 0, e);
        assertTrue(e[0] > -0.1 && e[0] < 0.1);
        // the light adds up: two lights equal the sum
        SphericalHarmonics.addDirectionalLight(c, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        double[] sum = new double[3];
        SphericalHarmonics.irradiance(c, 0.6, 0.8, 0, sum);
        float[] a = new float[27], b = new float[27];
        SphericalHarmonics.addDirectionalLight(a, 0.0, 1.0, 0.0, 1.0, 2.0, 4.0);
        SphericalHarmonics.addDirectionalLight(b, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        double[] ea = new double[3], eb = new double[3];
        SphericalHarmonics.irradiance(a, 0.6, 0.8, 0, ea);
        SphericalHarmonics.irradiance(b, 0.6, 0.8, 0, eb);
        assertEquals(ea[0] + eb[0], sum[0], 1e-5);
        assertEquals(ea[1] + eb[1], sum[1], 1e-5);
    }

    @Test
    void rotationMatchesProjectingTheRotatedFunction() {
        for (int t = 0; t < 30; t++) {
            float[] c = randomCoefficients(1.5);
            Quatf q = rnd.nextUnitQuatf();
            float[] rotated = new float[27];
            SphericalHarmonics.rotate(c, q, rotated);
            // the rotated environment is f'(w) = f(q^-1 w): project it directly
            Quatf inverse = q.conjugate();
            Radiance original = fromCoefficients(c);
            float[] direct = new float[27];
            SphericalHarmonics.project((x, y, z, out) -> {
                Vec3f p = inverse.transform(new Vec3f((float) x, (float) y, (float) z));
                original.radiance(p.x(), p.y(), p.z(), out);
            }, 8, direct);
            for (int i = 0; i < 27; i++) {
                assertEquals(direct[i], rotated[i], 3e-4, "coefficient " + i + " trial " + t);
            }
        }
    }

    @Test
    void rotationIsAGroupActionThatKeepsTheNorm() {
        float[] c = randomCoefficients(1.0);
        float[] same = new float[27];
        SphericalHarmonics.rotate(c, Quatf.IDENTITY, same);
        for (int i = 0; i < 27; i++) {
            assertEquals(c[i], same[i], 2e-5);
        }
        Quatf q1 = rnd.nextUnitQuatf(), q2 = rnd.nextUnitQuatf();
        float[] step1 = new float[27], step2 = new float[27], both = new float[27];
        SphericalHarmonics.rotate(c, q1, step1);
        SphericalHarmonics.rotate(step1, q2, step2);
        SphericalHarmonics.rotate(c, q2.mul(q1), both);
        double n0 = 0, n1 = 0;
        for (int i = 0; i < 27; i++) {
            assertEquals(both[i], step2[i], 2e-4);
            n0 += c[i] * c[i];
            n1 += step1[i] * step1[i];
        }
        assertEquals(n0, n1, 1e-4 * n0);
        // a light in the direction d ends up in the direction q d
        float[] light = new float[27], moved = new float[27];
        double[] d = randomDirection();
        SphericalHarmonics.addDirectionalLight(light, d[0], d[1], d[2], 1, 1, 1);
        Quatf q = rnd.nextUnitQuatf();
        SphericalHarmonics.rotate(light, q, moved);
        Vec3f qd = q.transform(new Vec3f((float) d[0], (float) d[1], (float) d[2]));
        float[] expected = new float[27];
        SphericalHarmonics.addDirectionalLight(expected, qd.x(), qd.y(), qd.z(), 1, 1, 1);
        for (int i = 0; i < 27; i++) {
            assertEquals(expected[i], moved[i], 2e-4);
        }
        assertThrows(IllegalArgumentException.class, () -> SphericalHarmonics.rotate(c, q1, c));
    }

    @Test
    void projectionChecksItsArguments() {
        Radiance one = (x, y, z, out) -> out[0] = out[1] = out[2] = 1.0;
        assertThrows(IllegalArgumentException.class, () -> SphericalHarmonics.project(one, 0, new float[27]));
        assertThrows(IllegalArgumentException.class, () -> SphericalHarmonics.project(one, 5000, new float[27]));
        assertThrows(IllegalArgumentException.class, () -> SphericalHarmonics.project(one, 4, new float[26]));
        assertEquals(9, SphericalHarmonics.COEFFICIENTS);
        assertEquals(27, SphericalHarmonics.LENGTH);
    }
}
