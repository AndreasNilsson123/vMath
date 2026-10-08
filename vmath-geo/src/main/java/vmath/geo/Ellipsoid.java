package vmath.geo;

import vmath.annotations.Experimental;
import vmath.core.Wgs84;

/**
 * A reference ellipsoid of revolution: its equatorial radius and flattening, with the derived
 * constants that the map projections need.
 *
 * <p>The projections of {@code vmath.geo} take an ellipsoid so that published examples on other
 * ellipsoids (Airy 1830 for the British grid, Clarke 1866 for old North American coordinates) can
 * be reproduced; everything else in the library, the geodesy of {@link Geodesy} included, is on
 * {@link #WGS84}.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Ellipsoid airy = Ellipsoid.ofInverseFlattening(6377563.396, 299.3249646);
 * double e2 = airy.e2();
 * }</pre>
 *
 * @param a the equatorial radius in metres, positive
 * @param f the flattening, {@code (a - b) / a}, in {@code [0, 1)}
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public record Ellipsoid(double a, double f) {

    /** The WGS-84 ellipsoid. */
    public static final Ellipsoid WGS84 = new Ellipsoid(Wgs84.A, Wgs84.FLATTENING);

    /** The GRS80 ellipsoid of NAD83 and ETRS89. */
    public static final Ellipsoid GRS80 = ofInverseFlattening(6378137.0, 298.257222101);

    /** The Airy 1830 ellipsoid of the British National Grid. */
    public static final Ellipsoid AIRY_1830 = ofInverseFlattening(6377563.396, 299.3249646);

    /** The Clarke 1866 ellipsoid of NAD27. */
    public static final Ellipsoid CLARKE_1866 = new Ellipsoid(6378206.4, 1.0 - 6356583.8 / 6378206.4);

    /**
     * Checks the arguments.
     *
     * @param a the equatorial radius in metres
     * @param f the flattening
     * @throws IllegalArgumentException if the radius is not positive or the flattening is not in
     *     {@code [0, 1)}
     */
    public Ellipsoid {
        if (!(a > 0) || !Double.isFinite(a) || !(f >= 0 && f < 1)) {
            throw new IllegalArgumentException("need a > 0 and 0 <= f < 1: " + a + ", " + f);
        }
    }

    /**
     * Makes an ellipsoid from the inverse flattening, the way ellipsoids are published.
     *
     * @param a the equatorial radius in metres
     * @param inverseFlattening {@code 1 / f}, above 1; infinity for a sphere
     * @return the ellipsoid
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static Ellipsoid ofInverseFlattening(double a, double inverseFlattening) {
        if (!(inverseFlattening > 1)) {
            throw new IllegalArgumentException("the inverse flattening must be above 1: " + inverseFlattening);
        }
        return new Ellipsoid(a, 1.0 / inverseFlattening);
    }

    /**
     * Gives the polar radius.
     *
     * @return {@code a (1 - f)} in metres
     */
    public double b() {
        return a * (1.0 - f);
    }

    /**
     * Gives the square of the first eccentricity.
     *
     * @return {@code f (2 - f)}
     */
    public double e2() {
        return f * (2.0 - f);
    }

    /**
     * Gives the first eccentricity.
     *
     * @return {@code sqrt(e2)}
     */
    public double e() {
        return Math.sqrt(e2());
    }

    /**
     * Gives the third flattening.
     *
     * @return {@code f / (2 - f)}, the small parameter of the series of the transverse Mercator
     *     projection
     */
    public double n() {
        return f / (2.0 - f);
    }
}
