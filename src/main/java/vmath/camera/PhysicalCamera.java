package vmath.camera;

/**
 * A physical camera: the lens, the sensor and the exposure settings of a real photographic camera, with the quantities a renderer derives from them: the exposure value, the
 * exposure scale to apply to scene luminance, the field of view, and the depth of field.
 *
 * <p>Units: focal length, sensor size and the circle of confusion in <b>millimetres</b>; the focus distance and every other distance in <b>metres</b>; the shutter time in seconds;
 * angles in radians; luminance in candela per square metre. The lens is the ideal thin lens, which is what the formulas of photography assume; real lenses breathe (the focal length
 * changes with the focus distance) and have other imperfections that are not modelled.
 *
 * <p><b>Exposure.</b> {@link #ev100()} is the exposure value normalised to ISO 100, {@code log2(N^2 / t) - log2(S / 100)}, the number the settings of a camera add up to: one step
 * is one stop, a doubling or halving of the light. {@link #exposure()} is the factor that maps scene luminance to the sensor value 1 (white): the saturation-based exposure
 * {@code 1 / (1.2 * 2^EV100)}, which is the one most real-time engines use. {@link #ev100ForLuminance} goes the other way, from an average scene luminance to the EV100 that
 * exposes it to middle grey with the reflected-light calibration constant 12.5.
 *
 * <p><b>Depth of field.</b> The circle of confusion limit is by default the sensor diagonal divided by 1500, which is 0.0288 mm for a full-frame sensor (the common value of 0.03 mm
 * is the rounded one). {@link #hyperfocalDistance()}, {@link #nearFocusLimit()}, {@link #farFocusLimit()} and {@link #circleOfConfusion(double)} follow from the thin-lens equation.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * @param focalLength the focal length in millimetres
 * @param sensorWidth the width of the sensor in millimetres
 * @param sensorHeight the height of the sensor in millimetres
 * @param fNumber the f-number (the aperture), for example 2.8; the aperture diameter is the focal length divided by it
 * @param shutterTime the exposure time in seconds, for example 1/125
 * @param iso the sensitivity, for example 100
 * @param focusDistance the distance in metres of the plane that is in focus
 */
public record PhysicalCamera(double focalLength, double sensorWidth, double sensorHeight, double fNumber, double shutterTime, double iso, double focusDistance) {

    /** The width of a full-frame (35 mm) sensor in millimetres. */
    public static final double FULL_FRAME_WIDTH = 36.0;
    /** The height of a full-frame (35 mm) sensor in millimetres. */
    public static final double FULL_FRAME_HEIGHT = 24.0;
    /** The width of an APS-C sensor of Nikon, Sony and Fujifilm in millimetres (Canon uses 22.3 by 14.9). */
    public static final double APS_C_WIDTH = 23.5;
    /** The height of an APS-C sensor in millimetres. */
    public static final double APS_C_HEIGHT = 15.6;
    /** The reflected-light meter calibration constant {@code K} in {@code EV100 = log2(L * 100 / K)}: 12.5 for the usual Canon, Nikon and Sekonic meters. */
    public static final double METER_CALIBRATION = 12.5;
    /** The saturation-based speed constant: the maximum luminance that does not saturate is {@code 1.2 * 2^EV100}, from {@code 78 / (0.65 * 100)}. */
    public static final double SATURATION_FACTOR = 1.2;

    /** Checks that every setting is positive and finite. */
    public PhysicalCamera {
        check("focal length", focalLength);
        check("sensor width", sensorWidth);
        check("sensor height", sensorHeight);
        check("f-number", fNumber);
        check("shutter time", shutterTime);
        check("iso", iso);
        check("focus distance", focusDistance);
    }

    private static void check(String name, double v) {
        if (!(v > 0) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(name + " must be positive and finite: " + v);
        }
    }

    /**
     * A full-frame camera ({@link #FULL_FRAME_WIDTH} by {@link #FULL_FRAME_HEIGHT}) with the given lens and settings.
     * {@code focusDistance} is in metres.
     */
    public static PhysicalCamera fullFrame(double focalLength, double fNumber, double shutterTime, double iso, double focusDistance) {
        return new PhysicalCamera(focalLength, FULL_FRAME_WIDTH, FULL_FRAME_HEIGHT, fNumber, shutterTime, iso, focusDistance);
    }

    // ------------------------------------------------------------ exposure

    /** The exposure value normalised to ISO 100: {@code log2(N^2 / t) - log2(S / 100)}. */
    public double ev100() {
        return ev100(fNumber, shutterTime, iso);
    }

    /** {@link #ev100()} for the given aperture {@code n} (f-number), shutter time {@code t} in seconds and sensitivity {@code iso}. */
    public static double ev100(double n, double t, double iso) {
        return log2(n * n / t) - log2(iso / 100.0);
    }

    /** The factor that maps scene luminance (cd/m^2) to the sensor value in which 1 is white: {@code 1 / (1.2 * 2^EV100)}. See {@link #exposureForEv100}. */
    public double exposure() {
        return exposureForEv100(ev100());
    }

    /** The exposure factor of an EV100: {@code 1 / (SATURATION_FACTOR * 2^ev100)}. */
    public static double exposureForEv100(double ev100) {
        return 1.0 / (SATURATION_FACTOR * Math.pow(2.0, ev100));
    }

    /** The scene luminance in cd/m^2 that just saturates the sensor at this exposure value: {@code 1.2 * 2^EV100}. */
    public static double maximumLuminance(double ev100) {
        return SATURATION_FACTOR * Math.pow(2.0, ev100);
    }

    /** The EV100 that exposes a scene of the given average luminance (cd/m^2) to middle grey by reflected-light metering: {@code log2(L * 100 / 12.5)}. */
    public static double ev100ForLuminance(double luminance) {
        if (!(luminance > 0)) {
            throw new IllegalArgumentException("the luminance must be positive: " + luminance);
        }
        return log2(luminance * 100.0 / METER_CALIBRATION);
    }

    /** The average scene luminance (cd/m^2) that an EV100 exposes to middle grey: the inverse of {@link #ev100ForLuminance}. */
    public static double luminanceForEv100(double ev100) {
        return METER_CALIBRATION / 100.0 * Math.pow(2.0, ev100);
    }

    /**
     * The shutter time in seconds that gives {@code targetEv100} at the given aperture and sensitivity: the aperture-priority automatic exposure of a camera. The same
     * formula that {@link #ev100(double, double, double)} inverts.
     */
    public static double shutterTimeFor(double targetEv100, double n, double iso) {
        return n * n / (Math.pow(2.0, targetEv100) * (iso / 100.0));
    }

    /** The sensitivity that gives {@code targetEv100} at the given aperture and shutter time. */
    public static double isoFor(double targetEv100, double n, double t) {
        return 100.0 * n * n / (t * Math.pow(2.0, targetEv100));
    }

    /** The f-number that gives {@code targetEv100} at the given shutter time and sensitivity. */
    public static double fNumberFor(double targetEv100, double t, double iso) {
        return Math.sqrt(Math.pow(2.0, targetEv100) * t * (iso / 100.0));
    }

    /** The f-number {@code stops} full stops above 1.0: {@code sqrt(2)^stops} (f/1.4 is 1 stop, f/2 is 2, f/2.8 is 3, f/4 is 4, and so on). */
    public static double fNumberOfStops(double stops) {
        return Math.pow(Math.sqrt(2.0), stops);
    }

    /** A copy with the settings changed to the given exposure compensation in stops relative to this one: positive values brighten (a longer shutter time). */
    public PhysicalCamera withExposureCompensation(double stops) {
        return new PhysicalCamera(focalLength, sensorWidth, sensorHeight, fNumber, shutterTime * Math.pow(2.0, stops), iso, focusDistance);
    }

    /** The diameter of the aperture opening in millimetres: the focal length divided by the f-number. */
    public double apertureDiameter() {
        return focalLength / fNumber;
    }

    // ------------------------------------------------------------ field of view

    /** The horizontal field of view in radians: {@code 2 atan(w / (2 f))}. */
    public double horizontalFov() {
        return fovForFocalLength(focalLength, sensorWidth);
    }

    /** The vertical field of view in radians: {@code 2 atan(h / (2 f))}. This is the {@code fovy} for the projection matrices. */
    public double verticalFov() {
        return fovForFocalLength(focalLength, sensorHeight);
    }

    /** The diagonal field of view in radians. */
    public double diagonalFov() {
        return fovForFocalLength(focalLength, Math.hypot(sensorWidth, sensorHeight));
    }

    /** The aspect ratio of the sensor, width over height. */
    public double aspect() {
        return sensorWidth / sensorHeight;
    }

    /** The angle in radians that a sensor extent of {@code size} millimetres spans through a lens of {@code focalLength}: {@code 2 atan(size / (2 f))}. */
    public static double fovForFocalLength(double focalLength, double size) {
        return 2.0 * Math.atan(size / (2.0 * focalLength));
    }

    /** The focal length in millimetres that makes a sensor extent of {@code size} millimetres span {@code fov} radians: {@code size / (2 tan(fov / 2))}. */
    public static double focalLengthForFov(double fov, double size) {
        return size / (2.0 * Math.tan(fov / 2.0));
    }

    /** The horizontal field of view for a vertical one and an aspect ratio: {@code 2 atan(aspect tan(fovy / 2))}. */
    public static double horizontalFovOf(double verticalFov, double aspect) {
        return 2.0 * Math.atan(aspect * Math.tan(verticalFov / 2.0));
    }

    /** The vertical field of view for a horizontal one and an aspect ratio: the inverse of {@link #horizontalFovOf}. */
    public static double verticalFovOf(double horizontalFov, double aspect) {
        return 2.0 * Math.atan(Math.tan(horizontalFov / 2.0) / aspect);
    }

    /** The crop factor of this sensor: the full-frame diagonal over this sensor's diagonal. */
    public double cropFactor() {
        return Math.hypot(FULL_FRAME_WIDTH, FULL_FRAME_HEIGHT) / Math.hypot(sensorWidth, sensorHeight);
    }

    /** The focal length in millimetres that gives the same field of view on a full-frame sensor: the focal length times the crop factor. */
    public double equivalentFocalLength() {
        return focalLength * cropFactor();
    }

    // ------------------------------------------------------------ depth of field

    /** The default limit of the circle of confusion in millimetres: the sensor diagonal divided by 1500. */
    public double acceptableCircleOfConfusion() {
        return Math.hypot(sensorWidth, sensorHeight) / 1500.0;
    }

    /** The hyperfocal distance in metres: focusing there makes everything from half that distance to infinity acceptably sharp. {@code f^2 / (N c) + f}. */
    public double hyperfocalDistance() {
        double f = focalLength, c = acceptableCircleOfConfusion();
        return (f * f / (fNumber * c) + f) / 1000.0;
    }

    /** The nearest distance in metres that is acceptably sharp when focused at {@link #focusDistance()}. */
    public double nearFocusLimit() {
        double h = hyperfocalDistance() * 1000.0, s = focusDistance * 1000.0, f = focalLength;
        return s * (h - f) / (h + s - 2.0 * f) / 1000.0;
    }

    /** The farthest distance in metres that is acceptably sharp when focused at {@link #focusDistance()}; positive infinity when the focus distance is the hyperfocal distance or more. */
    public double farFocusLimit() {
        double h = hyperfocalDistance() * 1000.0, s = focusDistance * 1000.0, f = focalLength;
        if (s >= h) {
            return Double.POSITIVE_INFINITY;
        }
        return s * (h - f) / (h - s) / 1000.0;
    }

    /** The total depth of the acceptably sharp zone in metres: {@link #farFocusLimit()} minus {@link #nearFocusLimit()}; infinite when the far limit is. */
    public double depthOfField() {
        return farFocusLimit() - nearFocusLimit();
    }

    /**
     * The diameter in millimetres of the blur disc that a point at {@code distance} metres makes on the sensor when the lens is focused at {@link #focusDistance()}:
     * {@code f^2 |d - s| / (N (s - f) d)} from the thin-lens equation, in the same units as the focal length. Zero in the focus plane; it approaches
     * {@code f^2 / (N (s - f))} as the distance grows. The focus distance must exceed the focal length.
     */
    public double circleOfConfusion(double distance) {
        double f = focalLength / 1000.0, s = focusDistance, d = distance;
        if (!(s > f)) {
            throw new IllegalArgumentException("the focus distance " + s + " m must exceed the focal length " + focalLength + " mm");
        }
        return f * f * Math.abs(d - s) / (fNumber * (s - f) * d) * 1000.0;
    }

    /** The blur disc of a point at {@code distance} metres in pixels of an image {@code imageHeightPixels} tall: the circle of confusion scaled by the pixels per millimetre of the sensor. */
    public double circleOfConfusionPixels(double distance, int imageHeightPixels) {
        return circleOfConfusion(distance) * imageHeightPixels / sensorHeight;
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2.0);
    }
}
