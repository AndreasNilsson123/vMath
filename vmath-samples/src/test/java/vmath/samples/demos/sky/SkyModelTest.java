package vmath.samples.demos.sky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.camera.PhysicalCamera;

/**
 * Tests of the light model of the sky demo with the library's sun, sky and atmosphere: a bright
 * noon, a dark night, a red sunset, and the exposure that goes with them.
 *
 * <p><b>Thread safety.</b> Each test builds its own model; the tests may run in parallel.
 */
class SkyModelTest {

    private static float maxRadiance(SkyModel m) {
        float max = 0f;
        for (float v : m.radiance()) {
            max = Math.max(max, v);
        }
        return max;
    }

    @Test
    void noonIsBrightAndTheSunIsHighAndWhite() {
        SkyModel m = new SkyModel();
        m.update(12.0, 80, 0.0, 3.0);
        assertTrue(Math.toDegrees(m.elevation()) > 80.0, "the sun is nearly overhead at the equator at the equinox: " + Math.toDegrees(m.elevation()));
        double[] e = m.sunIlluminance();
        assertTrue(e[1] > 80_000 && e[1] < 127_500, "direct sun at noon is about 100,000 lux: " + e[1]);
        assertTrue(e[2] / e[0] > 0.7, "close to white: blue over red " + e[2] / e[0]);
        assertTrue(m.zenithLuminance() > 2000.0, "a clear noon sky has thousands of cd/m2 at the zenith: " + m.zenithLuminance());
        double sky = m.skyIrradiance()[1];
        assertTrue(sky > 5000 && sky < 40_000, "the sky lights an upward surface with about ten thousand lux: " + sky);
        assertEquals(1.0, m.fade(), 1e-9);
    }

    @Test
    void theNightSkyIsAThousandthOfACandelaAndTheSunHasNoLight() {
        SkyModel m = new SkyModel();
        m.update(0.0, 80, 0.0, 3.0);
        assertTrue(Math.toDegrees(m.elevation()) < -80.0);
        assertEquals(0.0, m.sunIlluminance()[1], 1e-9);
        assertTrue(maxRadiance(m) < 0.01f, "night: " + maxRadiance(m));
        assertEquals(0.0, m.fade(), 1e-9);
        assertTrue(m.cardLuminance() < 0.001);
    }

    @Test
    void aLowSunIsRedderAndDimmerThanAHighOne() {
        SkyModel high = new SkyModel(), low = new SkyModel();
        high.update(12.0, 80, 0.0, 3.0);
        low.update(17.8, 80, 0.0, 3.0); // about 5 degrees above the horizon at the equator
        assertTrue(Math.toDegrees(low.elevation()) > 0.0 && Math.toDegrees(low.elevation()) < 15.0, "low sun " + Math.toDegrees(low.elevation()));
        double highRatio = high.sunIlluminance()[2] / high.sunIlluminance()[0], lowRatio = low.sunIlluminance()[2] / low.sunIlluminance()[0];
        assertTrue(lowRatio < highRatio * 0.6, "the low sun is redder: blue over red " + lowRatio + " against " + highRatio);
        assertTrue(low.sunIlluminance()[1] < high.sunIlluminance()[1] * 0.5);
    }

    @Test
    void aHazierSkyDimsTheDirectSunMore() {
        SkyModel clear = new SkyModel(), hazy = new SkyModel();
        clear.update(10.0, 172, 59.3, 2.0);
        hazy.update(10.0, 172, 59.3, 8.0);
        assertTrue(hazy.sunIlluminance()[1] < clear.sunIlluminance()[1]);
    }

    @Test
    void theAutomaticExposureOfNoonIsNearSunnySixteenAndOfNightMuchHigher() {
        SkyModel noon = new SkyModel(), night = new SkyModel();
        noon.update(12.0, 80, 0.0, 3.0);
        night.update(0.0, 80, 0.0, 3.0);
        double evNoon = PhysicalCamera.ev100ForLuminance(noon.cardLuminance());
        double evNight = PhysicalCamera.ev100ForLuminance(night.cardLuminance());
        assertTrue(evNoon > 13.0 && evNoon < 17.5, "a sunny day needs about EV 15: " + evNoon);
        assertTrue(evNight < evNoon - 10.0, "night needs far more light: EV " + evNight);
    }

    @Test
    void theSameInputGivesTheSameLight() {
        SkyModel a = new SkyModel(), b = new SkyModel();
        a.update(9.5, 200, 40.0, 4.0);
        b.update(9.5, 200, 40.0, 4.0);
        assertEquals(a.zenithLuminance(), b.zenithLuminance(), 0.0);
        for (int i = 0; i < a.radiance().length; i += 97) {
            assertEquals(a.radiance()[i], b.radiance()[i], 0f);
        }
    }
}
