package vmath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.util.SphericalHarmonics.Radiance;

class IblTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    @Test
    void theDistributionIntegratesToOneOverTheProjectedHemisphere() {
        // the defining property of a normal distribution function: the integral of D(h) (n.h) over the hemisphere is 1
        for (double roughness : new double[] {0.3, 0.5, 0.8, 1.0}) {
            int n = 20000;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double c = (i + 0.5) / n; // cos theta uniform in (0, 1): d(omega) = 2 pi d(cos theta)
                sum += Ibl.ggxDistribution(c, roughness) * c * 2 * Math.PI / n;
            }
            assertEquals(1.0, sum, 2e-3, "roughness " + roughness);
        }
        assertEquals(1.0 / Math.PI, Ibl.ggxDistribution(1.0, 1.0), 1e-12); // alpha = 1: D = 1 / pi everywhere
        assertEquals(1.0 / Math.PI, Ibl.ggxDistribution(0.3, 1.0), 1e-12);
    }

    @Test
    void visibilityTermsHaveTheirLimits() {
        // both geometry terms are 1 for a smooth surface, and the visibility is G / (4 nv nl)
        assertEquals(1.0, Ibl.smithGgxIbl(0.7, 0.4, 0.0), 1e-12);
        assertEquals(1.0 / (4 * 0.7 * 0.4), Ibl.smithGgxVisibility(0.7, 0.4, 0.0), 1e-12);
        for (int i = 0; i < 200; i++) {
            double nv = 0.05 + rng.nextDouble() * 0.95, nl = 0.05 + rng.nextDouble() * 0.95, r = rng.nextDouble();
            double g = Ibl.smithGgxIbl(nv, nl, r);
            assertTrue(g > 0 && g <= 1.0);
            double v = Ibl.smithGgxVisibility(nv, nl, r);
            assertTrue(v > 0);
            assertTrue(v * 4 * nv * nl <= 1.0 + 1e-12, "the correlated geometry term is at most 1");
            assertEquals(Ibl.smithGgxVisibility(nv, nl, r), Ibl.smithGgxVisibility(nl, nv, r), 1e-12, "symmetric");
        }
    }

    @Test
    void sampledHalfVectorsFollowTheLobe() {
        double[] h = new double[3];
        for (double roughness : new double[] {0.3, 0.6, 1.0}) {
            int n = 100_000;
            double sumCos = 0;
            for (int i = 0; i < n; i++) {
                double u1 = rng.nextDouble(), u2 = rng.nextDouble();
                Ibl.importanceSampleGgx(u1, u2, roughness, 0.0, 0.0, 1.0, h);
                assertEquals(1.0, Math.sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]), 1e-12);
                assertTrue(h[2] >= 0);
                sumCos += h[2];
            }
            // the expected cosine of the sampled half vector is the integral of D (n.h)^2 over the hemisphere
            int m = 40000;
            double expected = 0;
            for (int i = 0; i < m; i++) {
                double c = (i + 0.5) / m;
                expected += Ibl.ggxDistribution(c, roughness) * c * c * 2 * Math.PI / m;
            }
            assertEquals(expected, sumCos / n, 5e-3, "roughness " + roughness);
        }
        // u2 = 0 is exactly the normal, for any normal
        for (int i = 0; i < 100; i++) {
            double z = rng.nextDouble() * 2 - 1, a = rng.nextDouble() * 6.28, r = Math.sqrt(1 - z * z);
            double nx = r * Math.cos(a), ny = r * Math.sin(a);
            Ibl.importanceSampleGgx(0.3, 0.0, 0.5, nx, ny, z, h);
            assertEquals(nx, h[0], 1e-9);
            assertEquals(ny, h[1], 1e-9);
            assertEquals(z, h[2], 1e-9);
            Ibl.importanceSampleGgx(0.3, 0.7, 0.5, nx, ny, z, h);
            assertEquals(1.0, Math.sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]), 1e-9);
            assertTrue(h[0] * nx + h[1] * ny + h[2] * z > 0);
        }
    }

    @Test
    void theDfgTermOfAMirrorIsTheFresnelWeight() {
        // for a (nearly) perfectly smooth surface the half vector is the normal and the geometry term is 1: A = 1 - (1 - n.v)^5, B = (1 - n.v)^5 exactly
        double[] ab = new double[2];
        for (double nv : new double[] {0.1, 0.3, 0.5, 0.8, 1.0}) {
            Ibl.dfg(nv, 1e-3, 64, ab);
            double fc = Math.pow(1.0 - nv, 5.0);
            assertEquals(1.0 - fc, ab[0], 5e-3, "A at n.v = " + nv);
            assertEquals(fc, ab[1], 5e-3, "B at n.v = " + nv);
        }
    }

    @Test
    void theDfgTableIsEnergyConservingAndConverges() {
        double[] ab = new double[2], reference = new double[2];
        for (int t = 0; t < 40; t++) {
            double nv = 0.02 + rng.nextDouble() * 0.98, r = 0.05 + rng.nextDouble() * 0.95;
            Ibl.dfg(nv, r, 512, ab);
            assertTrue(ab[0] >= 0 && ab[1] >= 0, "non-negative");
            assertTrue(ab[0] + ab[1] <= 1.0 + 1e-3, "A + B is the directional albedo of a white microfacet surface and cannot exceed 1: " + (ab[0] + ab[1]));
            Ibl.dfg(nv, r, 40000, reference);
            assertEquals(reference[0], ab[0], 0.01, "A, nv = " + nv + " r = " + r);
            assertEquals(reference[1], ab[1], 0.01, "B, nv = " + nv + " r = " + r);
        }
        // rougher surfaces lose energy (single scattering) at grazing angles: the albedo decreases with the roughness
        double[] smooth = new double[2], rough = new double[2];
        Ibl.dfg(0.5, 0.2, 4096, smooth);
        Ibl.dfg(0.5, 1.0, 4096, rough);
        assertTrue(smooth[0] + smooth[1] > rough[0] + rough[1]);
        assertThrows(IllegalArgumentException.class, () -> Ibl.dfg(0.5, 0.5, 0, new double[2]));
    }

    @Test
    void theLookupTableIsFilledAndMatchesTheDirectEvaluation() {
        int size = 16;
        float[] lut = new float[size * size * 2];
        java.util.Arrays.fill(lut, Float.NaN);
        Ibl.brdfLut(size, 256, lut);
        double[] ab = new double[2];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                Ibl.dfg((i + 0.5) / size, (j + 0.5) / size, 256, ab);
                assertEquals((float) ab[0], lut[2 * (j * size + i)]);
                assertEquals((float) ab[1], lut[2 * (j * size + i) + 1]);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Ibl.brdfLut(16, 64, new float[16 * 16 * 2 - 1]));
        assertThrows(IllegalArgumentException.class, () -> Ibl.brdfLut(0, 64, new float[2]));
    }

    @Test
    void theDfgTermHasTheClosedFormAtFullRoughnessAndNormalIncidence() {
        // for alpha = 1 and n.v = 1 the estimate is the mean of G1(n.l) over the lobe, and n.l is uniform in (-1, 1), so A = integral of 2x / (x + 1) / 2 over (0, 1) = 1 - ln 2
        double[] ab = new double[2];
        Ibl.dfg(1.0, 1.0, 100000, ab);
        assertEquals(1.0 - Math.log(2.0), ab[0], 2e-3);
        assertTrue(ab[1] >= 0 && ab[1] < 0.01, "B is small but not zero: the Fresnel weight depends on the half vector, which varies over the lobe: " + ab[1]);
    }

    private static Radiance smooth(double ax, double ay, double az) {
        return (x, y, z, out) -> {
            out[0] = 1.0 + ax * x + 0.5 * y * y;
            out[1] = 2.0 + ay * y;
            out[2] = 0.5 + az * z * z;
        };
    }

    @Test
    void prefilteringKeepsConstantsAndMirrorsTheEnvironmentAtZeroRoughness() {
        double[] out = new double[3];
        Radiance constant = (x, y, z, o) -> {
            o[0] = 0.3;
            o[1] = 0.6;
            o[2] = 0.9;
        };
        for (int t = 0; t < 20; t++) {
            double z = rng.nextDouble() * 2 - 1, a = rng.nextDouble() * 6.28, r = Math.sqrt(1 - z * z);
            Ibl.prefilterGgx(constant, r * Math.cos(a), r * Math.sin(a), z, rng.nextDouble(), 64, out);
            assertEquals(0.3, out[0], 1e-12);
            assertEquals(0.9, out[2], 1e-12);
            Radiance env = smooth(0.4, 0.3, 0.2);
            double[] direct = new double[3];
            env.radiance(r * Math.cos(a), r * Math.sin(a), z, direct);
            Ibl.prefilterGgx(env, 5 * r * Math.cos(a), 5 * r * Math.sin(a), 5 * z, 0.0, 64, out); // not unit: normalised
            for (int k = 0; k < 3; k++) {
                assertEquals(direct[k], out[k], 1e-12);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Ibl.prefilterGgx(constant, 0, 0, 1, 0.5, 0, out));
    }

    /** The prefiltered value by direct integration: the integral of env(l) (n.l) D(n.h) over the hemisphere divided by the integral of (n.l) D(n.h), with the half vector of n = v. */
    private static void integratePrefilter(Radiance env, double[] n, double roughness, int res, double[] out) {
        double[] rgb = new double[3];
        double[] num = new double[3];
        double den = 0;
        // an orthonormal basis around n
        double[] t = Math.abs(n[2]) < 0.9 ? new double[] {0, 0, 1} : new double[] {1, 0, 0};
        double[] b1 = {n[1] * t[2] - n[2] * t[1], n[2] * t[0] - n[0] * t[2], n[0] * t[1] - n[1] * t[0]};
        double bl = Math.sqrt(b1[0] * b1[0] + b1[1] * b1[1] + b1[2] * b1[2]);
        b1[0] /= bl;
        b1[1] /= bl;
        b1[2] /= bl;
        double[] b2 = {n[1] * b1[2] - n[2] * b1[1], n[2] * b1[0] - n[0] * b1[2], n[0] * b1[1] - n[1] * b1[0]};
        for (int i = 0; i < res; i++) {
            double cosL = (i + 0.5) / res; // the light direction: cos of the angle to n, uniform in (0, 1)
            double sinL = Math.sqrt(1 - cosL * cosL);
            double noh = Math.sqrt((1 + cosL) / 2); // the half vector between l and v = n has n.h = cos(theta / 2)
            double weight = cosL * Ibl.ggxDistribution(noh, roughness);
            for (int j = 0; j < 2 * res; j++) {
                double phi = Math.PI * (j + 0.5) / res;
                double lx = sinL * (Math.cos(phi) * b1[0] + Math.sin(phi) * b2[0]) + cosL * n[0];
                double ly = sinL * (Math.cos(phi) * b1[1] + Math.sin(phi) * b2[1]) + cosL * n[1];
                double lz = sinL * (Math.cos(phi) * b1[2] + Math.sin(phi) * b2[2]) + cosL * n[2];
                env.radiance(lx, ly, lz, rgb);
                num[0] += rgb[0] * weight;
                num[1] += rgb[1] * weight;
                num[2] += rgb[2] * weight;
                den += weight;
            }
        }
        out[0] = num[0] / den;
        out[1] = num[1] / den;
        out[2] = num[2] / den;
    }

    @Test
    void prefilteringConvergesToTheDirectIntegral() {
        Radiance env = smooth(0.8, 0.6, 0.4);
        double[] viaSamples = new double[3], viaIntegral = new double[3];
        for (int t = 0; t < 20; t++) {
            double z = rng.nextDouble() * 2 - 1, a = rng.nextDouble() * 6.28, r = Math.sqrt(1 - z * z);
            double[] n = {r * Math.cos(a), r * Math.sin(a), z};
            double roughness = 0.3 + rng.nextDouble() * 0.7;
            Ibl.prefilterGgx(env, n[0], n[1], n[2], roughness, 8192, viaSamples);
            integratePrefilter(env, n, roughness, 300, viaIntegral);
            for (int k = 0; k < 3; k++) {
                assertEquals(viaIntegral[k], viaSamples[k], 0.02, "channel " + k + ", roughness " + roughness);
            }
        }
        // a rougher lobe averages over more of the environment: it differs more from the direct value
        double[] n = {0, 1, 0};
        double[] direct = new double[3], r1 = new double[3], r2 = new double[3];
        Radiance peak = (x, y, z, o) -> o[0] = o[1] = o[2] = Math.pow(Math.max(0, y), 20);
        peak.radiance(0, 1, 0, direct);
        Ibl.prefilterGgx(peak, n[0], n[1], n[2], 0.2, 4096, r1);
        Ibl.prefilterGgx(peak, n[0], n[1], n[2], 0.9, 4096, r2);
        assertTrue(direct[0] > r1[0] && r1[0] > r2[0], direct[0] + " > " + r1[0] + " > " + r2[0]);
    }
}
