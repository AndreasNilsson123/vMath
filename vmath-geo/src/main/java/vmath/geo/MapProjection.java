package vmath.geo;

import vmath.annotations.Experimental;

/**
 * A map projection: the mapping between latitude and longitude on the ellipsoid and the
 * coordinates {@code (x, y)} in metres on a flat map, with the two numbers that say how the map
 * distorts the ground at a point, the <b>scale</b> and the <b>convergence</b>.
 *
 * <p>All angles are in radians and all lengths in metres; {@code x} points east and {@code y}
 * north on the map ("grid north"). The projections of the library: {@link WebMercatorProjection}
 * (the tile maps), {@link AzimuthalEquidistant} (true range and bearing from a centre),
 * {@link PolarStereographic} (the polar caps), {@link TransverseMercator} with {@link Utm} and
 * {@link Mgrs} (the military and survey grids) and {@link LambertConformalConic} (aeronautical
 * charts of mid latitudes).
 *
 * <p><b>Scale.</b> {@link #scales} gives the ratio of the length on the map to the length on the
 * ground along the meridian and along the parallel, {@code h} and {@code k}. In a <em>conformal</em>
 * projection ({@link #isConformal()}) they are equal and angles are kept, so one number, {@link
 * #scale}, is the scale of the point. {@code scale} is the geometric mean {@code sqrt(h k)}, the
 * square root of the ratio of areas. The size of a pixel on the ground is the size of a pixel on
 * the map divided by it.
 *
 * <p><b>Convergence.</b> {@link #convergence} is the angle between true north and grid north: a
 * true bearing minus the bearing of the same direction on the map. It is positive when grid north
 * lies to the east of true north (east of the central meridian in the northern hemisphere, for a
 * transverse Mercator projection). A map that is to have true north up is rotated by it.
 *
 * <p><b>Longitudes</b> may be outside {@code [-pi, pi]}: the projections of a cylinder (Mercator)
 * continue periodically, which is how a line that crosses the antimeridian stays in one piece; the
 * others are periodic by their formulas.
 *
 * <p>The default {@code scales} and {@code convergence} differentiate {@code forward} numerically
 * (central differences with Richardson extrapolation, accurate to about 1e-10 away from the
 * poles); the projections that have closed forms override them.
 *
 * <p><b>Thread safety.</b> The projections are immutable and every method may be called from any
 * number of threads; the arrays passed in are written only by the call.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MapProjection utm = Utm.projection(33, true);                  // zone 33, northern hemisphere
 * double[] xy = new double[2];
 * utm.forward(Math.toRadians(59.33), Math.toRadians(18.07), xy);   // metres east and north
 * double k = utm.scale(Math.toRadians(59.33), Math.toRadians(18.07));
 * }</pre>
 */
@Experimental("new in 0.2: the projections of the map layer may change")
public interface MapProjection {

    /**
     * Names the projection.
     *
     * @return a short name for messages
     */
    String name();

    /**
     * Gives the ellipsoid that the projection is defined on, which the scale is measured on.
     *
     * @return the ellipsoid, WGS-84 unless the projection was made with another
     */
    default Ellipsoid ellipsoid() {
        return Ellipsoid.WGS84;
    }

    /**
     * Tells whether the projection preserves angles.
     *
     * @return {@code true} if the scale is the same in every direction at a point
     */
    boolean isConformal();

    /**
     * Projects a point.
     *
     * @param latitude the latitude in radians
     * @param longitude the longitude in radians
     * @param xy receives {@code x} east and {@code y} north in metres at {@code xy[0..1]}
     * @throws IllegalArgumentException if an argument is not finite, the point is outside the
     *     domain of the projection, or {@code xy} is too short
     */
    void forward(double latitude, double longitude, double[] xy);

    /**
     * Unprojects a point of the map.
     *
     * @param x the {@code x} coordinate in metres
     * @param y the {@code y} coordinate in metres
     * @param latLon receives the latitude and the longitude (in {@code [-pi, pi]}) in radians at
     *     {@code latLon[0..1]}
     * @throws IllegalArgumentException if an argument is not finite, the point is outside the
     *     domain of the projection, or {@code latLon} is too short
     */
    void inverse(double x, double y, double[] latLon);

    /**
     * Gives the scale along the meridian and along the parallel.
     *
     * @param latitude the latitude in radians
     * @param longitude the longitude in radians
     * @param out receives {@code h} (along the meridian) and {@code k} (along the parallel) at
     *     {@code out[0..1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    default void scales(double latitude, double longitude, double[] out) {
        MapProjections.numericScales(this, latitude, longitude, out);
    }

    /**
     * Gives the scale at a point: the geometric mean of the scales along the meridian and the
     * parallel, which is the scale of a conformal projection.
     *
     * @param latitude the latitude in radians
     * @param longitude the longitude in radians
     * @return map length over ground length
     */
    default double scale(double latitude, double longitude) {
        double[] s = new double[2];
        scales(latitude, longitude, s);
        return Math.sqrt(s[0] * s[1]);
    }

    /**
     * Gives the convergence of the meridians at a point: true north minus grid north.
     *
     * @param latitude the latitude in radians
     * @param longitude the longitude in radians
     * @return the angle in radians, positive when grid north is east of true north
     */
    default double convergence(double latitude, double longitude) {
        return MapProjections.numericConvergence(this, latitude, longitude);
    }
}
