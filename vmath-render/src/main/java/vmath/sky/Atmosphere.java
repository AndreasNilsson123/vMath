package vmath.sky;

/**
 * Light through the earth's atmosphere, with the simple closed-form models used in real-time
 * rendering: how much air the sunlight passes through at a given sun elevation (the air mass), how
 * much of each colour survives it (the transmittance of Rayleigh scattering by the air molecules
 * and of scattering by haze), and the colour and brightness of the sun itself.
 *
 * <ul>
 *   <li>{@link #airMass}: the relative air mass of Kasten and Young (1989), 1 for a sun at the
 *       zenith, about 38 at the horizon.</li>
 *   <li>{@link #rayleighOpticalDepth}: the optical depth of a molecular atmosphere at sea level for
 *       a wavelength, the series of Hansen and Travis as used by Kasten; about 0.097 at 550 nm and
 *       growing as the inverse fourth power of the wavelength, which is why the sky is blue and the
 *       low sun red.</li>
 *   <li>{@link #aerosolOpticalDepth}: the Angstrom law {@code beta * lambda^-alpha} for haze;
 *       {@code beta} is the optical depth at 1 micrometre and {@code alpha} (about 1.3 for a
 *       typical continental aerosol) says how fast it falls with wavelength.</li>
 *   <li>{@link #transmittance} and {@link #sunTransmittanceRgb}: {@code exp(-m (tau_R + tau_a))},
 *       the Beer-Lambert law for the direct sunlight. Absorption by ozone and water vapour is not
 *       included.</li>
 * </ul>
 *
 * <p>Wavelengths are in <b>micrometres</b>, elevations in <b>radians</b>, altitudes above sea level
 * in metres.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double[] rgb = new double[3];
 * Atmosphere.sunTransmittanceRgb(Math.toRadians(30), 0.04, 1.3, 0.0, rgb);   // direct sunlight left after the air and the aerosols
 * double airMass = Atmosphere.airMass(Math.toRadians(30));                    // about 2
 * }</pre>
 */
public final class Atmosphere {

    /**
     * The scale height of the density of air in metres (about 8 km): the pressure at altitude
     * {@code h} is the sea-level pressure times {@code exp(-h / 8000)}.
     */
    public static final double SCALE_HEIGHT = 8000.0;
    /**
     * The Angstrom exponent of a typical continental aerosol.
     */
    public static final double TYPICAL_ANGSTROM_ALPHA = 1.3;
    /**
     * The wavelength in micrometres used for the red channel by {@link #sunTransmittanceRgb}.
     */
    public static final double RED = 0.68;
    /**
     * The wavelength in micrometres used for the green channel by {@link #sunTransmittanceRgb}.
     */
    public static final double GREEN = 0.55;
    /**
     * The wavelength in micrometres used for the blue channel by {@link #sunTransmittanceRgb}.
     */
    public static final double BLUE = 0.44;

    private Atmosphere() {
    }

    /**
     * Computes the relative optical air mass with an empirical formula that stays finite at the
     * horizon, which a simple one-over-sine does not.
     *
     * <p>Elevations below the horizon are treated as the horizon.
     *
     * @param elevation the elevation
     * @return the relative optical air mass for a sun at {@code elevation} radians above the
     *     horizon: the path length of the light through the atmosphere in units of the vertical
     *     path, {@code 1 / (sin h + 0.50572 (h + 6.07995)^-1.6364)} with {@code h} in degrees
     *     (Kasten and Young 1989; it includes the curvature of the atmosphere and the refraction
     *     near the horizon, and reaches 38 at 0 degrees)
     */
    public static double airMass(double elevation) {
        double h = Math.max(elevation, 0.0) * 180.0 / Math.PI;
        return 1.0 / (Math.sin(Math.toRadians(h)) + 0.50572 * Math.pow(h + 6.07995, -1.6364));
    }

    /**
     * Computes the Rayleigh optical depth of the atmosphere from an empirical wavelength-power law,
     * corrected for the observer's altitude; the wavelength is in micrometres.
     *
     * @param wavelength the wavelength
     * @param altitude the altitude
     * @return the Rayleigh optical depth of the whole atmosphere above an observer at
     *     {@code altitude} metres for light of {@code wavelength} micrometres:
     *     {@code 0.008569 l^-4 (1 + 0.0113 l^-2 + 0.00013 l^-4)} at sea level, scaled by the
     *     density of the air above the observer, {@code exp(-altitude / 8000)}
     */
    public static double rayleighOpticalDepth(double wavelength, double altitude) {
        double l2 = wavelength * wavelength, l4 = l2 * l2;
        double seaLevel = 0.008569 / l4 * (1.0 + 0.0113 / l2 + 0.00013 / l4);
        return seaLevel * Math.exp(-altitude / SCALE_HEIGHT);
    }

    /**
     * Computes the aerosol optical depth with the Angstrom power law, whose two parameters describe
     * the amount of haze and its size distribution.
     *
     * @param wavelength the wavelength
     * @param beta the beta
     * @param alpha the alpha
     * @return the aerosol (haze) optical depth by the Angstrom law:
     *     {@code beta * wavelength^-alpha}
     */
    public static double aerosolOpticalDepth(double wavelength, double beta, double alpha) {
        return beta * Math.pow(wavelength, -alpha);
    }

    /**
     * Computes the Beer-Lambert transmittance of direct sunlight through the air and the haze,
     * which is the basis for sun colour at different elevations.
     *
     * @param wavelength the wavelength
     * @param elevation the elevation
     * @param beta the beta
     * @param alpha the alpha
     * @param altitude the altitude
     * @return the fraction of direct sunlight of one wavelength that reaches an observer at
     *     {@code altitude} metres when the sun is at {@code elevation} radians:
     *     {@code exp(-m (tau_R + tau_a))}, with {@code m} the {@link #airMass}, {@code tau_R} the
     *     {@link #rayleighOpticalDepth} and {@code tau_a} the {@link #aerosolOpticalDepth} (also
     *     scaled with the altitude)
     */
    public static double transmittance(double wavelength, double elevation, double beta, double alpha, double altitude) {
        double tau = rayleighOpticalDepth(wavelength, altitude) + aerosolOpticalDepth(wavelength, beta, alpha) * Math.exp(-altitude / SCALE_HEIGHT);
        return Math.exp(-airMass(elevation) * tau);
    }

    /**
     * Computes the transmittance of the direct sunlight in the three channels at the wavelengths
     * {@link #RED}, {@link #GREEN} and {@link #BLUE}, written to {@code out[0 .. 3)}.
     *
     * <p>Multiply the colour of the sun above the atmosphere by it to get the colour of the
     * sunlight on the ground: close to white at the zenith, orange and dim near the horizon.
     *
     * @param elevation the elevation
     * @param beta the beta
     * @param alpha the alpha
     * @param altitude the altitude
     * @param out receives the result in {@code [0, 3)}
     */
    public static void sunTransmittanceRgb(double elevation, double beta, double alpha, double altitude, double[] out) {
        out[0] = transmittance(RED, elevation, beta, alpha, altitude);
        out[1] = transmittance(GREEN, elevation, beta, alpha, altitude);
        out[2] = transmittance(BLUE, elevation, beta, alpha, altitude);
    }
}
