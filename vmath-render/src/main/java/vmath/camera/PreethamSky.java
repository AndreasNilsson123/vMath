package vmath.camera;

/**
 * The analytic clear-sky model of Preetham, Shirley and Smits ("A Practical Analytic Model for Daylight", SIGGRAPH 1999): the luminance and colour of the sky in every direction
 * for a given sun position and turbidity, from a handful of formulas. The sky luminance {@code Y} and the chromaticity {@code (x, y)} each follow the distribution of Perez,
 * {@code F(theta, gamma) = (1 + A exp(B / cos theta)) (1 + C exp(D gamma) + E cos^2 gamma)}, where {@code theta} is the angle of the view direction from the zenith and {@code gamma} the
 * angle between the view direction and the sun; the five coefficients are linear functions of the turbidity, and the value at the zenith is a function of turbidity and sun
 * position. The result at a direction is {@code zenith * F(theta, gamma) / F(0, sunTheta)}.
 *
 * <p><b>Turbidity</b> is the ratio of haze scattering to molecular scattering: about 2 for a very clear sky, 6 for a hazy one. The model was fitted for turbidities from 2 to 6
 * and a sun above the horizon; the constructor accepts 1 to 12, the formulas still produce numbers there, but they are extrapolation. The model has no ground and no clouds, and is not defined for a sun below the horizon (see Zotti and Wilkie, "A Critical Review of the Preetham Skylight Model", for its errors against measurements), so the sun zenith angle is clamped to 90 degrees (the sunset sky): fade the result yourself for the night.
 *
 * <p><b>Units.</b> Luminance is in <b>candela per square metre</b> (the paper's kilocandela per square metre times 1000). Directions are unit or non-unit vectors in a frame with the y axis
 * up. Colours are returned as CIE xyY or as <b>linear sRGB</b> (Rec. 709 primaries, D65 white point), not tone-mapped and not clamped.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 */
public final class PreethamSky {

    private final double turbidity;
    private final double sunTheta;
    private final double[][] coefficients = new double[3][5]; // Y, x, y: A B C D E
    private final double zenithY, zenithX, zenithChromaY;
    private final double[] normalization = new double[3]; // F(0, sunTheta) per channel

    /** The sky for the given turbidity (1 to 12) and the angle of the sun from the zenith in radians (clamped to {@code [0, pi / 2]}). */
    public PreethamSky(double turbidity, double sunZenithAngle) {
        if (!(turbidity >= 1.0 && turbidity <= 12.0)) {
            throw new IllegalArgumentException("the turbidity must be in [1, 12]: " + turbidity);
        }
        this.turbidity = turbidity;
        this.sunTheta = Math.max(0.0, Math.min(Math.PI / 2, sunZenithAngle));
        double t = turbidity;
        // Y
        coefficients[0] = new double[] {0.1787 * t - 1.4630, -0.3554 * t + 0.4275, -0.0227 * t + 5.3251, 0.1206 * t - 2.5771, -0.0670 * t + 0.3703};
        // x
        coefficients[1] = new double[] {-0.0193 * t - 0.2592, -0.0665 * t + 0.0008, -0.0004 * t + 0.2125, -0.0641 * t - 0.8989, -0.0033 * t + 0.0452};
        // y
        coefficients[2] = new double[] {-0.0167 * t - 0.2608, -0.0950 * t + 0.0092, -0.0079 * t + 0.2102, -0.0441 * t - 1.6537, -0.0109 * t + 0.0529};
        double chi = (4.0 / 9.0 - t / 120.0) * (Math.PI - 2.0 * sunTheta);
        zenithY = ((4.0453 * t - 4.9710) * Math.tan(chi) - 0.2155 * t + 2.4192) * 1000.0;
        double th = sunTheta, th2 = th * th, th3 = th2 * th;
        zenithX = t * t * (0.00166 * th3 - 0.00375 * th2 + 0.00209 * th) + t * (-0.02903 * th3 + 0.06377 * th2 - 0.03202 * th + 0.00394)
                + (0.11693 * th3 - 0.21196 * th2 + 0.06052 * th + 0.25886);
        zenithChromaY = t * t * (0.00275 * th3 - 0.00610 * th2 + 0.00317 * th) + t * (-0.04214 * th3 + 0.08970 * th2 - 0.04153 * th + 0.00516)
                + (0.15346 * th3 - 0.26756 * th2 + 0.06670 * th + 0.26688);
        for (int c = 0; c < 3; c++) {
            normalization[c] = perez(c, 0.0, sunTheta);
        }
    }

    /** The turbidity. */
    public double turbidity() {
        return turbidity;
    }

    /** The angle of the sun from the zenith in radians, after clamping to {@code [0, pi / 2]}. */
    public double sunZenithAngle() {
        return sunTheta;
    }

    /** The luminance of the sky at the zenith in cd/m^2. */
    public double zenithLuminance() {
        return zenithY;
    }

    /** The CIE x chromaticity of the sky at the zenith. */
    public double zenithX() {
        return zenithX;
    }

    /** The CIE y chromaticity of the sky at the zenith. */
    public double zenithChromaticityY() {
        return zenithChromaY;
    }

    /** The Perez distribution of channel {@code c} (0 luminance, 1 x, 2 y) for the view zenith angle {@code theta} and the angle {@code gamma} to the sun. */
    private double perez(int c, double theta, double gamma) {
        double[] k = coefficients[c];
        double cosTheta = Math.cos(theta);
        // below the horizon the term exp(B / cos theta) goes to zero (B is negative): use the value at the horizon
        double horizon = cosTheta > 1e-6 ? Math.exp(k[1] / cosTheta) : 0.0;
        double cosGamma = Math.cos(gamma);
        return (1.0 + k[0] * horizon) * (1.0 + k[2] * Math.exp(k[3] * gamma) + k[4] * cosGamma * cosGamma);
    }

    /**
     * The sky colour as CIE xyY for a view direction with zenith angle {@code theta} (radians from the zenith, 0 straight up, {@code pi / 2} at the horizon; larger values give the
     * horizon value) and angle {@code gamma} to the sun (radians, 0 looking at the sun). {@code out[0]} receives {@code x}, {@code out[1]} receives {@code y} and {@code out[2]}
     * the luminance in cd/m^2.
     */
    public void xyY(double theta, double gamma, double[] out) {
        out[0] = zenithX * perez(1, theta, gamma) / normalization[1];
        out[1] = zenithChromaY * perez(2, theta, gamma) / normalization[2];
        out[2] = zenithY * perez(0, theta, gamma) / normalization[0];
    }

    /**
     * {@link #xyY(double, double, double[])} for a view direction {@code (vx, vy, vz)} and a sun direction {@code (sx, sy, sz)}, both vectors in a frame with the y axis up (they
     * need not be normalised, but must not be zero). The sun direction should be the one the sun zenith angle of the constructor describes.
     */
    public void xyY(double vx, double vy, double vz, double sx, double sy, double sz, double[] out) {
        double vl = Math.sqrt(vx * vx + vy * vy + vz * vz), sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        double theta = Math.acos(Math.max(-1.0, Math.min(1.0, vy / vl)));
        double gamma = Math.acos(Math.max(-1.0, Math.min(1.0, (vx * sx + vy * sy + vz * sz) / (vl * sl))));
        xyY(theta, gamma, out);
    }

    /**
     * The sky colour in linear sRGB (cd/m^2 per channel, not clamped) for the view and sun directions of {@link #xyY(double, double, double, double, double, double, double[])},
     * written to {@code out[0 .. 3)}.
     */
    public void rgb(double vx, double vy, double vz, double sx, double sy, double sz, double[] out) {
        xyY(vx, vy, vz, sx, sy, sz, out);
        xyYToLinearSrgb(out[0], out[1], out[2], out);
    }

    /**
     * Converts a colour from CIE xyY (chromaticity {@code x, y} and luminance {@code Y}) to linear sRGB (Rec. 709 primaries, D65 white): the XYZ values, then the standard matrix.
     * {@code out} may be the same array that held the xyY values. A zero {@code y} gives black.
     */
    public static void xyYToLinearSrgb(double x, double y, double luminance, double[] out) {
        if (!(y > 0)) {
            out[0] = out[1] = out[2] = 0.0;
            return;
        }
        double bigX = x / y * luminance, bigZ = (1.0 - x - y) / y * luminance;
        out[0] = 3.2404542 * bigX - 1.5371385 * luminance - 0.4985314 * bigZ;
        out[1] = -0.9692660 * bigX + 1.8760108 * luminance + 0.0415560 * bigZ;
        out[2] = 0.0556434 * bigX - 0.2040259 * luminance + 1.0572252 * bigZ;
    }
}
