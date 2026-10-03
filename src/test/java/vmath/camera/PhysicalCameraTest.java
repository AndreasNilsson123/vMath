package vmath.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

class PhysicalCameraTest {

    private final SplittableRandom rng = new SplittableRandom(Rnd.SEED);

    private static double deg(double radians) {
        return Math.toDegrees(radians);
    }

    @Test
    void exposureValueKnownCases() {
        // the "sunny 16" rule: f/16 at 1/ISO seconds is EV100 15 (14.97 exactly)
        assertEquals(Math.log(16.0 * 16.0 * 125.0) / Math.log(2), PhysicalCamera.ev100(16, 1.0 / 125, 100), 1e-12);
        assertEquals(15.0, PhysicalCamera.ev100(16, 1.0 / 125, 100), 0.04);
        // f/1 for one second at ISO 100 is EV 0, the definition of the scale
        assertEquals(0.0, PhysicalCamera.ev100(1, 1, 100), 1e-12);
        // each stop: halving the shutter time, doubling the ISO, or one stop of aperture changes the value by exactly 1
        double base = PhysicalCamera.ev100(4, 1.0 / 60, 100);
        assertEquals(base + 1, PhysicalCamera.ev100(4, 1.0 / 120, 100), 1e-12);
        assertEquals(base - 1, PhysicalCamera.ev100(4, 1.0 / 60, 200), 1e-12);
        assertEquals(base + 1, PhysicalCamera.ev100(4 * Math.sqrt(2), 1.0 / 60, 100), 1e-12);
        PhysicalCamera c = PhysicalCamera.fullFrame(50, 8, 1.0 / 250, 400, 5);
        assertEquals(PhysicalCamera.ev100(8, 1.0 / 250, 400), c.ev100(), 0.0);
    }

    @Test
    void exposureAndLuminanceAreConsistent() {
        for (int i = 0; i < 200; i++) {
            double ev = rng.nextDouble() * 20 - 5;
            assertEquals(1.0 / (1.2 * Math.pow(2, ev)), PhysicalCamera.exposureForEv100(ev), 1e-15 * PhysicalCamera.exposureForEv100(ev));
            // the luminance that saturates times the exposure is exactly white
            assertEquals(1.0, PhysicalCamera.maximumLuminance(ev) * PhysicalCamera.exposureForEv100(ev), 1e-12);
            double l = Math.pow(10, rng.nextDouble() * 8 - 3);
            assertEquals(l, PhysicalCamera.luminanceForEv100(PhysicalCamera.ev100ForLuminance(l)), 1e-9 * l);
        }
        // middle grey at EV 0 is 0.125 cd/m^2 with the meter constant 12.5
        assertEquals(0.0, PhysicalCamera.ev100ForLuminance(0.125), 1e-12);
        assertEquals(0.125, PhysicalCamera.luminanceForEv100(0.0), 1e-15);
        assertEquals(1.0 / (1.2 * Math.pow(2, PhysicalCamera.fullFrame(35, 4, 1.0 / 30, 100, 3).ev100())), PhysicalCamera.fullFrame(35, 4, 1.0 / 30, 100, 3).exposure(), 1e-15);
        assertThrows(IllegalArgumentException.class, () -> PhysicalCamera.ev100ForLuminance(0));
        assertThrows(IllegalArgumentException.class, () -> PhysicalCamera.ev100ForLuminance(-1));
    }

    @Test
    void solvingForOneSettingGivesTheTargetValue() {
        for (int i = 0; i < 200; i++) {
            double ev = rng.nextDouble() * 18 - 2, n = 1 + rng.nextDouble() * 20, iso = 50 + rng.nextDouble() * 6400, t = 1.0 / (1 + rng.nextDouble() * 4000);
            assertEquals(ev, PhysicalCamera.ev100(n, PhysicalCamera.shutterTimeFor(ev, n, iso), iso), 1e-9);
            assertEquals(ev, PhysicalCamera.ev100(n, t, PhysicalCamera.isoFor(ev, n, t)), 1e-9);
            assertEquals(ev, PhysicalCamera.ev100(PhysicalCamera.fNumberFor(ev, t, iso), t, iso), 1e-9);
        }
        assertEquals(1.0, PhysicalCamera.fNumberOfStops(0), 1e-15);
        assertEquals(Math.sqrt(2), PhysicalCamera.fNumberOfStops(1), 1e-12);
        assertEquals(2.0, PhysicalCamera.fNumberOfStops(2), 1e-12);
        assertEquals(4.0, PhysicalCamera.fNumberOfStops(4), 1e-12);
        assertEquals(16.0, PhysicalCamera.fNumberOfStops(8), 1e-12);
        PhysicalCamera c = PhysicalCamera.fullFrame(50, 4, 1.0 / 100, 100, 3);
        // plus one stop of compensation lengthens the shutter time, which lowers the value of EV by one
        assertEquals(c.ev100() - 1, c.withExposureCompensation(1).ev100(), 1e-12);
        assertEquals(c.ev100() + 2, c.withExposureCompensation(-2).ev100(), 1e-12);
        assertEquals(50.0 / 4.0, c.apertureDiameter(), 1e-12);
    }

    @Test
    void fieldOfViewOfKnownLenses() {
        PhysicalCamera fifty = PhysicalCamera.fullFrame(50, 8, 1.0 / 125, 100, 5);
        // the classic numbers for a 50 mm lens on a full-frame sensor: 39.6 degrees horizontally, 27.0 vertically, 46.8 on the diagonal
        assertEquals(39.598, deg(fifty.horizontalFov()), 1e-3);
        assertEquals(26.992, deg(fifty.verticalFov()), 1e-3);
        assertEquals(46.793, deg(fifty.diagonalFov()), 1e-3);
        assertEquals(73.74, deg(PhysicalCamera.fullFrame(24, 8, 1.0 / 125, 100, 5).horizontalFov()), 1e-2);
        assertEquals(1.5, fifty.aspect(), 0.0);
        assertEquals(1.0, fifty.cropFactor(), 1e-12);
        assertEquals(50.0, fifty.equivalentFocalLength(), 1e-9);
        // APS-C: the crop factor is 1.5 to 1.6 and the equivalent focal length is the real one times it
        PhysicalCamera apsc = new PhysicalCamera(35, PhysicalCamera.APS_C_WIDTH, PhysicalCamera.APS_C_HEIGHT, 4, 1.0 / 125, 100, 5);
        assertEquals(1.534, apsc.cropFactor(), 1e-3);
        assertEquals(35 * apsc.cropFactor(), apsc.equivalentFocalLength(), 1e-9);
        // the field of view of the equivalent focal length on full frame equals the diagonal field of view here
        assertEquals(apsc.diagonalFov(), PhysicalCamera.fovForFocalLength(apsc.equivalentFocalLength(), Math.hypot(36, 24)), 1e-12);
    }

    @Test
    void fieldOfViewAndFocalLengthAreInverses() {
        for (int i = 0; i < 300; i++) {
            double f = 8 + rng.nextDouble() * 400, size = 5 + rng.nextDouble() * 60;
            double fov = PhysicalCamera.fovForFocalLength(f, size);
            assertTrue(fov > 0 && fov < Math.PI);
            assertEquals(f, PhysicalCamera.focalLengthForFov(fov, size), 1e-9 * f);
            double aspect = 0.5 + rng.nextDouble() * 3;
            assertEquals(fov, PhysicalCamera.verticalFovOf(PhysicalCamera.horizontalFovOf(fov, aspect), aspect), 1e-12);
        }
        PhysicalCamera c = PhysicalCamera.fullFrame(35, 8, 1.0 / 125, 100, 5);
        assertEquals(c.horizontalFov(), PhysicalCamera.horizontalFovOf(c.verticalFov(), c.aspect()), 1e-12);
        assertEquals(c.verticalFov(), PhysicalCamera.verticalFovOf(c.horizontalFov(), c.aspect()), 1e-12);
    }

    /** The blur disc of a point by the lens equation: the image distances and the aperture, an oracle that shares no formula with the production code. */
    private static double blurByLensEquation(double f, double n, double focus, double distance) {
        double vFocus = 1.0 / (1.0 / f - 1.0 / focus);
        double vPoint = 1.0 / (1.0 / f - 1.0 / distance);
        return f / n * Math.abs(vPoint - vFocus) / vPoint;
    }

    @Test
    void circleOfConfusionFollowsTheLensEquation() {
        for (int i = 0; i < 300; i++) {
            double f = 20 + rng.nextDouble() * 200, n = 1.2 + rng.nextDouble() * 20, s = 0.5 + rng.nextDouble() * 50, d = 0.3 + rng.nextDouble() * 200;
            if (s * 1000 <= f * 2 || d * 1000 <= f * 1.01) {
                continue;
            }
            PhysicalCamera c = PhysicalCamera.fullFrame(f, n, 1.0 / 125, 100, s);
            double expected = blurByLensEquation(f, n, s * 1000, d * 1000);
            assertEquals(expected, c.circleOfConfusion(d), 1e-9 * (1 + expected));
            assertEquals(0.0, c.circleOfConfusion(s), 1e-12);
            assertEquals(c.circleOfConfusion(d) * 1080 / 24, c.circleOfConfusionPixels(d, 1080), 1e-9);
        }
        assertThrows(IllegalArgumentException.class, () -> PhysicalCamera.fullFrame(50, 4, 1.0 / 125, 100, 0.04).circleOfConfusion(1.0));
    }

    @Test
    void depthOfFieldLimitsAreWhereTheBlurReachesTheAcceptableDisc() {
        for (int i = 0; i < 300; i++) {
            double f = 15 + rng.nextDouble() * 150, n = 1.4 + rng.nextDouble() * 20, s = 1 + rng.nextDouble() * 30;
            PhysicalCamera c = PhysicalCamera.fullFrame(f, n, 1.0 / 125, 100, s);
            double coc = c.acceptableCircleOfConfusion();
            assertEquals(Math.hypot(36, 24) / 1500, coc, 1e-12);
            double near = c.nearFocusLimit(), far = c.farFocusLimit();
            assertTrue(near < s && near > 0);
            assertEquals(coc, c.circleOfConfusion(near), 1e-9 * coc, "near limit, trial " + i);
            if (Double.isFinite(far)) {
                assertTrue(far > s);
                assertEquals(coc, c.circleOfConfusion(far), 1e-9 * coc, "far limit, trial " + i);
                assertEquals(far - near, c.depthOfField(), 1e-12);
            } else {
                assertTrue(s >= c.hyperfocalDistance());
                assertTrue(Double.isInfinite(c.depthOfField()));
            }
            // focusing at the hyperfocal distance puts the near limit at half of it, and everything up to infinity is sharp
            PhysicalCamera atH = PhysicalCamera.fullFrame(f, n, 1.0 / 125, 100, c.hyperfocalDistance());
            assertTrue(Double.isInfinite(atH.farFocusLimit()));
            assertEquals(c.hyperfocalDistance() / 2.0, atH.nearFocusLimit(), 1e-3 * c.hyperfocalDistance() + f / 2000.0);
        }
        // a worked example: 50 mm at f/8 on full frame, focused at 5 m (the figures computed independently in Python from the textbook formulas)
        PhysicalCamera c = PhysicalCamera.fullFrame(50, 8, 1.0 / 125, 100, 5);
        assertEquals(10.8840, c.hyperfocalDistance(), 1e-3);
        assertEquals(3.4320, c.nearFocusLimit(), 1e-3);
        assertEquals(9.2063, c.farFocusLimit(), 1e-3);
    }

    @Test
    void settingsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCamera(0, 36, 24, 4, 0.01, 100, 5));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCamera(50, -36, 24, 4, 0.01, 100, 5));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCamera(50, 36, Double.NaN, 4, 0.01, 100, 5));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCamera(50, 36, 24, 0, 0.01, 100, 5));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCamera(50, 36, 24, 4, 0, 100, 5));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCamera(50, 36, 24, 4, 0.01, -1, 5));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalCamera(50, 36, 24, 4, 0.01, 100, Double.POSITIVE_INFINITY));
    }
}
