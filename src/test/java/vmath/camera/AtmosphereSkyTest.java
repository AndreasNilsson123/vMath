package vmath.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class AtmosphereSkyTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    // ------------------------------------------------------------ atmosphere

    @Test
    void airMassOfKnownElevations() {
        assertEquals(1.0, Atmosphere.airMass(Math.PI / 2), 5e-4);
        assertEquals(1.994, Atmosphere.airMass(Math.toRadians(30)), 0.01); // sec(60 degrees) is 2
        assertEquals(1.414, Atmosphere.airMass(Math.toRadians(45)), 0.01);
        assertEquals(37.9, Atmosphere.airMass(0.0), 0.2); // Kasten and Young: 38 at the horizon
        // below the horizon it is the horizon value
        assertEquals(Atmosphere.airMass(0.0), Atmosphere.airMass(-0.3), 0.0);
        double previous = 0;
        for (int d = 90; d >= 0; d--) {
            double m = Atmosphere.airMass(Math.toRadians(d));
            assertTrue(m > previous, "the air mass grows as the sun gets lower");
            previous = m;
        }
    }

    @Test
    void rayleighOpticalDepth() {
        // the optical depth of the molecular atmosphere at 550 nm is 0.097 at sea level
        assertEquals(0.0973, Atmosphere.rayleighOpticalDepth(0.55, 0.0), 5e-4);
        // it goes as the inverse fourth power of the wavelength, a little less steeply because of the correction terms
        double ratio = Atmosphere.rayleighOpticalDepth(0.44, 0.0) / Atmosphere.rayleighOpticalDepth(0.55, 0.0);
        assertEquals(Math.pow(0.55 / 0.44, 4), ratio, 0.1);
        assertTrue(ratio < Math.pow(0.55 / 0.44, 4) * 1.05);
        // thinner air above a mountain: the density falls by the factor e over the scale height
        assertEquals(Atmosphere.rayleighOpticalDepth(0.55, 0.0) / Math.E, Atmosphere.rayleighOpticalDepth(0.55, Atmosphere.SCALE_HEIGHT), 1e-15);
    }

    @Test
    void aerosolsFollowAngstromsLaw() {
        assertEquals(0.1, Atmosphere.aerosolOpticalDepth(1.0, 0.1, 1.3), 1e-15);
        assertEquals(0.1 * Math.pow(0.5, -1.3), Atmosphere.aerosolOpticalDepth(0.5, 0.1, 1.3), 1e-15);
        assertEquals(0.0, Atmosphere.aerosolOpticalDepth(0.55, 0.0, 1.3), 0.0);
        assertEquals(1.3, Atmosphere.TYPICAL_ANGSTROM_ALPHA);
    }

    @Test
    void transmittanceIsBeerLambert() {
        for (int i = 0; i < 200; i++) {
            double lambda = 0.4 + rng.nextDouble() * 0.3, el = rng.nextDouble() * Math.PI / 2, beta = rng.nextDouble() * 0.3, alt = rng.nextDouble() * 4000;
            double expected = Math.exp(-Atmosphere.airMass(el) * (Atmosphere.rayleighOpticalDepth(lambda, alt) + Atmosphere.aerosolOpticalDepth(lambda, beta, 1.3) * Math.exp(-alt / 8000.0)));
            assertEquals(expected, Atmosphere.transmittance(lambda, el, beta, 1.3, alt), 1e-12);
            assertTrue(expected > 0 && expected < 1);
        }
        double[] rgb = new double[3];
        Atmosphere.sunTransmittanceRgb(Math.PI / 2, 0.05, 1.3, 0.0, rgb);
        assertTrue(rgb[0] > rgb[1] && rgb[1] > rgb[2], "red is transmitted best, blue worst");
        assertTrue(rgb[2] > 0.5, "a clear sky at the zenith still passes most of the blue");
        // a low sun is redder and dimmer
        double[] low = new double[3];
        Atmosphere.sunTransmittanceRgb(Math.toRadians(3), 0.05, 1.3, 0.0, low);
        assertTrue(low[0] < rgb[0] && low[2] < rgb[2]);
        assertTrue(low[0] / low[2] > rgb[0] / rgb[2] * 3, "the ratio of red to blue grows towards the horizon");
        // monotonic in the elevation for each channel
        double prev = 0;
        for (int d = 0; d <= 90; d += 5) {
            Atmosphere.sunTransmittanceRgb(Math.toRadians(d), 0.05, 1.3, 0.0, rgb);
            assertTrue(rgb[1] >= prev);
            prev = rgb[1];
        }
        // no haze and no air above (very high altitude) lets everything through
        Atmosphere.sunTransmittanceRgb(Math.PI / 2, 0.0, 1.3, 200000.0, rgb);
        assertEquals(1.0, rgb[1], 1e-9);
    }

    // ------------------------------------------------------------ Preetham sky

    @Test
    void zenithValuesMatchTheFormulasOfThePaper() {
        // reference values computed independently in Python from the published formulas of Preetham, Shirley and Smits
        double[][] cases = {
                {2, 0.0, 15500.672137416845, 0.26674, 0.2772},
                {2, 0.8, 4406.634145311266, 0.24052431999999996, 0.24326976000000003},
                {3, 0.5, 10769.767163879522, 0.2543125, 0.26081374999999996},
                {4, 1.2, 5084.91562586935, 0.2566641599999999, 0.26986080000000007},
                {6, 1.5, 2205.280301833432, 0.2866662499999999, 0.3088575},
                {5, Math.PI / 2, 1341.7, 0.2881062795996737, 0.31054503450228166}};
        for (double[] c : cases) {
            PreethamSky sky = new PreethamSky(c[0], c[1]);
            assertEquals(c[2], sky.zenithLuminance(), 1e-9 * c[2]);
            assertEquals(c[3], sky.zenithX(), 1e-12);
            assertEquals(c[4], sky.zenithChromaticityY(), 1e-12);
            assertEquals(c[0], sky.turbidity());
            assertEquals(c[1], sky.sunZenithAngle(), 0.0);
        }
    }

    @Test
    void theDistributionIsNormalisedAtTheZenith() {
        double[] out = new double[3];
        for (int i = 0; i < 100; i++) {
            double t = 2 + rng.nextDouble() * 4, sunTheta = rng.nextDouble() * Math.PI / 2;
            PreethamSky sky = new PreethamSky(t, sunTheta);
            // looking straight up, the angle to the sun is the sun's zenith angle: the value is the zenith value
            sky.xyY(0.0, sunTheta, out);
            assertEquals(sky.zenithX(), out[0], 1e-12);
            assertEquals(sky.zenithChromaticityY(), out[1], 1e-12);
            assertEquals(sky.zenithLuminance(), out[2], 1e-9 * sky.zenithLuminance());
        }
    }

    @Test
    void aWorkedSkyValue() {
        // reference values from an independent Python evaluation of the formulas: T = 3, sun zenith angle 0.9, view zenith angle 0.7, angle to the sun 0.5
        PreethamSky sky = new PreethamSky(3, 0.9);
        double[] out = new double[3];
        sky.xyY(0.7, 0.5, out);
        assertEquals(0.2622572943831105, out[0], 1e-12);
        assertEquals(0.2682388218043205, out[1], 1e-12);
        assertEquals(11857.069793650282, out[2], 1e-9 * 11857.069793650282);
    }

    @Test
    void theSkyIsBrighterTowardsTheSunAndTheHorizonValueExtends() {
        PreethamSky sky = new PreethamSky(3, 0.8);
        double[] near = new double[3], far = new double[3];
        sky.xyY(0.8, 0.05, near);
        sky.xyY(0.8, 2.5, far);
        assertTrue(near[2] > far[2], "the circumsolar region is brighter");
        // below the horizon the value is the horizon value
        double[] horizon = new double[3], below = new double[3];
        sky.xyY(Math.PI / 2, 1.0, horizon);
        sky.xyY(Math.PI / 2 + 0.5, 1.0, below);
        assertEquals(horizon[2], below[2], 1e-9 * horizon[2]);
        assertEquals(horizon[0], below[0], 1e-12);
        assertTrue(horizon[2] > 0 && Double.isFinite(horizon[2]));
        // a sun below the horizon is clamped to the horizon
        PreethamSky night = new PreethamSky(3, 2.0);
        assertEquals(Math.PI / 2, night.sunZenithAngle(), 0.0);
        assertEquals(new PreethamSky(3, Math.PI / 2).zenithLuminance(), night.zenithLuminance(), 0.0);
        assertEquals(new PreethamSky(3, 0.0).sunZenithAngle(), new PreethamSky(3, -1.0).sunZenithAngle(), 0.0);
    }

    @Test
    void directionsAndAnglesGiveTheSameSky() {
        PreethamSky sky = new PreethamSky(2.5, 0.6);
        double[] a = new double[3], b = new double[3], rgb = new double[3];
        for (int i = 0; i < 200; i++) {
            double vx = rng.nextDouble() * 2 - 1, vy = rng.nextDouble() * 2 - 1, vz = rng.nextDouble() * 2 - 1;
            double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vl < 0.1) {
                continue;
            }
            double sx = Math.sin(0.6), sy = Math.cos(0.6), sz = 0.0;
            sky.xyY(vx * 7, vy * 7, vz * 7, sx * 3, sy * 3, sz * 3, a); // vectors need not be unit
            double theta = Math.acos(vy / vl), gamma = Math.acos((vx * sx + vy * sy + vz * sz) / vl);
            sky.xyY(theta, gamma, b);
            for (int k = 0; k < 3; k++) {
                assertEquals(b[k], a[k], 1e-9 * (1 + Math.abs(b[k])));
            }
            sky.rgb(vx, vy, vz, sx, sy, sz, rgb);
            double[] check = new double[3];
            PreethamSky.xyYToLinearSrgb(b[0], b[1], b[2], check);
            for (int k = 0; k < 3; k++) {
                assertEquals(check[k], rgb[k], 1e-6 * (1 + Math.abs(check[k])));
            }
        }
    }

    @Test
    void theColourConversionIsStandard() {
        double[] rgb = new double[3];
        // the white point D65 (x = 0.3127, y = 0.3290) at luminance 1 is white
        PreethamSky.xyYToLinearSrgb(0.3127, 0.3290, 1.0, rgb);
        assertEquals(1.0, rgb[0], 2e-3);
        assertEquals(1.0, rgb[1], 2e-3);
        assertEquals(1.0, rgb[2], 2e-3);
        // the sRGB primaries: red at (0.64, 0.33), green at (0.30, 0.60), blue at (0.15, 0.06)
        PreethamSky.xyYToLinearSrgb(0.64, 0.33, 0.2126, rgb);
        assertEquals(1.0, rgb[0], 2e-3);
        assertEquals(0.0, rgb[1], 2e-3);
        assertEquals(0.0, rgb[2], 2e-3);
        PreethamSky.xyYToLinearSrgb(0.30, 0.60, 0.7152, rgb);
        assertEquals(1.0, rgb[1], 2e-3);
        assertEquals(0.0, rgb[0], 2e-3);
        PreethamSky.xyYToLinearSrgb(0.15, 0.06, 0.0722, rgb);
        assertEquals(1.0, rgb[2], 2e-3);
        assertEquals(0.0, rgb[0], 2e-3);
        // luminance scales linearly, and y = 0 gives black
        PreethamSky.xyYToLinearSrgb(0.3127, 0.3290, 5.0, rgb);
        assertEquals(5.0, rgb[1], 1e-2);
        PreethamSky.xyYToLinearSrgb(0.3, 0.0, 5.0, rgb);
        assertEquals(0.0, rgb[0] + rgb[1] + rgb[2], 0.0);
        // the zenith of a clear sky is blue: more blue than red
        PreethamSky sky = new PreethamSky(2, 0.3);
        sky.rgb(0, 1, 0, Math.sin(0.3), Math.cos(0.3), 0, rgb);
        assertTrue(rgb[2] > rgb[0] && rgb[2] > rgb[1] * 0.9, "a blue zenith: " + rgb[0] + " " + rgb[1] + " " + rgb[2]);
    }

    @Test
    void turbidityMustBeInRange() {
        assertThrows(IllegalArgumentException.class, () -> new PreethamSky(0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new PreethamSky(13, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new PreethamSky(Double.NaN, 0.5));
        new PreethamSky(1, 0.5);
        new PreethamSky(12, 0.5);
    }
}
