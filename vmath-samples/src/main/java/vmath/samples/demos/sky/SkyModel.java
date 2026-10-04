package vmath.samples.demos.sky;

import vmath.camera.Atmosphere;
import vmath.camera.PreethamSky;
import vmath.camera.SolarPosition;

/**
 * The light of one moment at one place, worked out with the library's models: where the sun is
 * ({@code SolarPosition}), what the sky looks like in every direction ({@code PreethamSky}), and how
 * much of the sunlight and in what colour reaches the ground ({@code Atmosphere}).
 *
 * <p>{@link #update} takes the hour of the day (UT, at longitude 0, so it is local solar time up to
 * the equation of time), the day of the year of 2026, the latitude and the turbidity. It fills:
 * <ul>
 *   <li>a sky table of {@value #WIDTH} by {@value #HEIGHT} texels of linear RGB radiance in
 *       cd/m<sup>2</sup>, azimuth along the first axis and zenith angle (0 to 90 degrees, row 0 at
 *       the zenith) along the second, which the shader samples as a texture;</li>
 *   <li>the direction of the sun, its transmittance through the air, its illuminance on a surface
 *       that faces it (127,500 lux above the atmosphere times the transmittance), and the radiance
 *       of the disc, which is that illuminance over the solid angle of the disc;</li>
 *   <li>the irradiance of the sky on an upward-facing surface, summed from the table with cosine
 *       weights, and the luminance of a mid-grey card lit by both, which the automatic exposure
 *       aims at.</li>
 * </ul>
 *
 * <p><b>What is added to the library's models.</b> The Preetham model is not defined for a sun below
 * the horizon, so the sun is clamped to the horizon for the sky and the result fades out over the
 * six degrees of civil twilight into a small constant night sky. The haze optical depth of the
 * transmittance is {@code 0.02 (T - 1)} for a turbidity {@code T}, a rough choice of the demo (the
 * library has no mapping from turbidity to the Angstrom coefficient). Neither is a measurement.
 *
 * <p>The class does not use OpenGL; {@link #update} allocates one small {@code PreethamSky} and fills
 * preallocated tables.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: {@link #update} overwrites the tables that the getters
 * return.
 */
final class SkyModel {

    static final int WIDTH = 256;
    static final int HEIGHT = 64;
    /**
     * The illuminance of the sun on a surface facing it above the atmosphere, in lux.
     */
    static final double SOLAR_ILLUMINANCE = 127_500.0;
    /**
     * The angular radius of the sun's disc in radians.
     */
    static final double SUN_RADIUS = 0.00465;
    private static final double[] NIGHT = {0.0006, 0.0008, 0.0014};

    private final double[] directions = new double[WIDTH * HEIGHT * 3];
    private final double[] rowWeight = new double[HEIGHT];
    private final float[] radiance = new float[WIDTH * HEIGHT * 3];
    private final double[] rgb = new double[3];
    private final double[] sunDirection = new double[3];
    private final double[] skyDirection = new double[3];
    private final double[] transmittance = new double[3];
    private final double[] sunIlluminance = new double[3];
    private final double[] sunRadiance = new double[3];
    private final double[] skyIrradiance = new double[3];
    private double azimuth;
    private double elevation;
    private double zenithLuminance;
    private double cardLuminance;
    private double fade;

    /**
     * Precomputes the view directions of the sky table.
     */
    SkyModel() {
        double dTheta = Math.PI / 2.0 / HEIGHT, dPhi = 2.0 * Math.PI / WIDTH;
        for (int j = 0; j < HEIGHT; j++) {
            double theta = (j + 0.5) * dTheta;
            rowWeight[j] = Math.cos(theta) * Math.sin(theta) * dTheta * dPhi;
            for (int i = 0; i < WIDTH; i++) {
                double phi = (i + 0.5) * dPhi;
                int o = (j * WIDTH + i) * 3;
                directions[o] = Math.sin(theta) * Math.sin(phi);
                directions[o + 1] = Math.cos(theta);
                directions[o + 2] = -Math.sin(theta) * Math.cos(phi);
            }
        }
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - a) / (b - a)));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Works out the light of a moment.
     *
     * @param hour the hour of the day in UT, 0 to 24; at longitude 0 it is the local solar time
     * @param dayOfYear the day of 2026, 1 to 365
     * @param latitude the latitude in degrees, positive north
     * @param turbidity the turbidity of the sky, 2 for a very clear sky and 6 for a hazy one
     */
    void update(double hour, int dayOfYear, double latitude, double turbidity) {
        double jd = SolarPosition.julianDay(2026, 1, 1.0 + (dayOfYear - 1) + hour / 24.0);
        SolarPosition.Sun sun = SolarPosition.position(jd, latitude, 0.0);
        azimuth = sun.azimuth();
        elevation = sun.elevation();
        SolarPosition.direction(azimuth, elevation, sunDirection);
        double skyElevation = Math.max(elevation, 0.0);
        SolarPosition.direction(azimuth, skyElevation, skyDirection);
        PreethamSky sky = new PreethamSky(turbidity, Math.PI / 2.0 - skyElevation);
        zenithLuminance = sky.zenithLuminance();
        fade = smoothstep(Math.toRadians(-6.0), 0.0, elevation);
        double sunFade = smoothstep(-0.02, 0.03, elevation);

        skyIrradiance[0] = skyIrradiance[1] = skyIrradiance[2] = 0.0;
        for (int j = 0; j < HEIGHT; j++) {
            for (int i = 0; i < WIDTH; i++) {
                int o = (j * WIDTH + i) * 3;
                sky.rgb(directions[o], directions[o + 1], directions[o + 2], skyDirection[0], skyDirection[1], skyDirection[2], rgb);
                for (int c = 0; c < 3; c++) {
                    double l = Math.max(0.0, rgb[c]) * fade + NIGHT[c];
                    radiance[o + c] = (float) l;
                    skyIrradiance[c] += l * rowWeight[j];
                }
            }
        }

        double beta = 0.02 * (turbidity - 1.0);
        Atmosphere.sunTransmittanceRgb(Math.max(elevation, 0.02), beta, Atmosphere.TYPICAL_ANGSTROM_ALPHA, 0.0, transmittance);
        double solidAngle = Math.PI * SUN_RADIUS * SUN_RADIUS;
        for (int c = 0; c < 3; c++) {
            sunIlluminance[c] = SOLAR_ILLUMINANCE * transmittance[c] * sunFade;
            sunRadiance[c] = sunIlluminance[c] / solidAngle;
        }
        double sunLuminance = 0.2126 * sunIlluminance[0] + 0.7152 * sunIlluminance[1] + 0.0722 * sunIlluminance[2];
        double skyLuminance = 0.2126 * skyIrradiance[0] + 0.7152 * skyIrradiance[1] + 0.0722 * skyIrradiance[2];
        cardLuminance = 0.18 * (sunLuminance * Math.max(Math.sin(elevation), 0.0) + skyLuminance) / Math.PI;
    }

    float[] radiance() {
        return radiance;
    }

    double[] sunDirection() {
        return sunDirection;
    }

    double[] sunIlluminance() {
        return sunIlluminance;
    }

    double[] sunRadiance() {
        return sunRadiance;
    }

    double[] skyIrradiance() {
        return skyIrradiance;
    }

    double[] transmittance() {
        return transmittance;
    }

    /**
     * Reads the azimuth of the sun.
     *
     * @return the azimuth in radians from north through east
     */
    double azimuth() {
        return azimuth;
    }

    /**
     * Reads the elevation of the sun.
     *
     * @return the elevation in radians; negative below the horizon
     */
    double elevation() {
        return elevation;
    }

    double zenithLuminance() {
        return zenithLuminance;
    }

    /**
     * Reads the luminance of a mid-grey card (albedo 0.18) lying flat and lit by the sun and the sky,
     * the quantity that the automatic exposure maps to middle grey.
     *
     * @return the luminance in cd/m<sup>2</sup>
     */
    double cardLuminance() {
        return cardLuminance;
    }

    /**
     * Reads how much of the day sky is left at this sun elevation.
     *
     * @return 1 for a sun above the horizon, falling to 0 at six degrees below it
     */
    double fade() {
        return fade;
    }
}
