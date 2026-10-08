package vmath.map;

import vmath.annotations.Experimental;
import vmath.camera.OrthoCamerad;
import vmath.core.ClipSpace;
import vmath.core.Mat4d;
import vmath.core.Quatd;
import vmath.core.Vec3d;
import vmath.geo.DepthRange;
import vmath.geo.Geodesy;
import vmath.geo.MapProjection;

/**
 * A 2D view of a map: which place is in the middle (or where in the window the own position
 * sits), how large a pixel is on the ground, which way is up, and what the window is; and from those
 * the conversions between geographic positions and pixels, the geographic bounds of the view, and
 * the matrix that a renderer draws the map with, for any {@link ClipSpace}.
 *
 * <p>It is a view on top of an orthographic camera ({@link OrthoCamerad}): everything is drawn in the
 * <em>projected</em> coordinates of a {@link MapProjection} (metres on the map), the lines, symbols
 * and tiles are written relative to an origin that is near the view, and {@link #viewProjection}
 * makes the float matrix for that origin from double precision values, so that a map of a continent
 * or of the world is as steady as one of a town.
 *
 * <p><b>Scale.</b> {@link #metersPerPixel()} is the size of a pixel on the <em>ground</em> at the
 * centre of the view; the size of a pixel in the units of the map is that divided by the scale of the
 * projection there ({@link MapProjection#scale}), and a different one away from the centre in a
 * projection that is not conformal ({@link #groundMetersPerPixelAt}). The scale can be given as a range
 * instead: {@link #withRange} is the ground distance from the centre to the top edge of the window,
 * the number a moving map shows as its range.
 *
 * <p><b>Orientation.</b> {@link Orientation#NORTH_UP} has true north up at the centre;
 * {@link Orientation#COURSE_UP} and {@link Orientation#HEADING_UP} have the given course or heading
 * up, and {@link Orientation#ANGLE} any other true bearing. The rotation is the same everywhere on the
 * window (a map does not bend), so a true bearing other than the one at the centre is off by the
 * difference of the convergence of the projection, which the projection and the view account for at the
 * centre.
 *
 * <p><b>Window.</b> Pixels count from the top left corner, x to the right and y down. The centre
 * sits at the middle of the window shifted by {@code centerOffsetX} times the width and
 * {@code centerOffsetY} times the height: an offset of {@code (0, 0.3)} puts the own position in
 * the lower part of the window, with more of the map ahead of it.
 *
 * <p>The view is immutable; the {@code with} methods return a changed copy and cost a projection of
 * the centre (microseconds).
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MapView2d view = MapView2d.of(Utm.projection(33, true), Math.toRadians(59.33), Math.toRadians(18.07), 1920, 1080)
 *         .withRange(25_000.0)                                  // 25 km to the top edge
 *         .withCenterOffset(0.0, 0.25)                          // the own position in the lower part
 *         .withOrientation(MapView2d.Orientation.HEADING_UP, Math.toRadians(40));
 * double[] px = new double[2];
 * view.toScreen(Math.toRadians(59.40), Math.toRadians(18.20), px);       // where a place is, in window pixels
 * float[] vp = new float[16];
 * view.viewProjection(ClipSpace.OPENGL, view.centerX(), view.centerY(), vp);   // for geometry written relative to the centre
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class MapView2d {

    /**
     * What points up on the window.
     */
    public enum Orientation {
        /** True north is up at the centre. */
        NORTH_UP,
        /** The course of the own position is up. */
        COURSE_UP,
        /** The heading of the own position is up. */
        HEADING_UP,
        /** Any fixed true bearing is up. */
        ANGLE
    }

    private final MapProjection projection;
    private final double centerLat;
    private final double centerLon;
    private final double metersPerPixel;
    private final Orientation orientation;
    private final double upBearing;
    private final double offsetX;
    private final double offsetY;
    private final int width;
    private final int height;
    // derived at the centre
    private final double centerX;
    private final double centerY;
    private final double scaleAtCenter;
    private final double gridUp;          // the grid bearing that points up the window
    private final double unitsPerPixel;   // map units (projected metres) per pixel
    private final double sinUp;
    private final double cosUp;

    private MapView2d(MapProjection projection, double centerLat, double centerLon, double metersPerPixel, Orientation orientation, double upBearing, double offsetX, double offsetY, int width,
                      int height) {
        if (projection == null || orientation == null) {
            throw new NullPointerException("projection and orientation must not be null");
        }
        if (!Double.isFinite(centerLat) || !Double.isFinite(centerLon) || Math.abs(centerLat) > Math.PI / 2 + 1e-12) {
            throw new IllegalArgumentException("the centre must be a latitude in [-pi/2, pi/2] and a longitude: " + centerLat + ", " + centerLon);
        }
        if (!(metersPerPixel > 0) || !Double.isFinite(metersPerPixel)) {
            throw new IllegalArgumentException("the size of a pixel on the ground must be positive: " + metersPerPixel);
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("the window must be at least one pixel: " + width + " x " + height);
        }
        if (!Double.isFinite(offsetX) || !Double.isFinite(offsetY) || !Double.isFinite(upBearing) || Math.abs(offsetX) > 0.5 || Math.abs(offsetY) > 0.5) {
            throw new IllegalArgumentException("the offsets must be within half a window and every number finite: " + offsetX + ", " + offsetY + ", " + upBearing);
        }
        this.projection = projection;
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.metersPerPixel = metersPerPixel;
        this.orientation = orientation;
        this.upBearing = orientation == Orientation.NORTH_UP ? 0.0 : Geodesy.wrapPi(upBearing);
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.width = width;
        this.height = height;
        double[] xy = new double[2];
        projection.forward(centerLat, centerLon, xy);
        this.centerX = xy[0];
        this.centerY = xy[1];
        this.scaleAtCenter = projection.scale(centerLat, centerLon);
        this.unitsPerPixel = metersPerPixel * scaleAtCenter;
        this.gridUp = this.upBearing - projection.convergence(centerLat, centerLon);
        this.sinUp = Math.sin(gridUp);
        this.cosUp = Math.cos(gridUp);
    }

    /**
     * Makes a north-up view with the centre in the middle of the window and a pixel of 100 m on
     * the ground.
     *
     * @param projection the projection; must not be {@code null}
     * @param centerLat the latitude of the centre in radians
     * @param centerLon the longitude of the centre in radians
     * @param width the width of the window in pixels, at least 1
     * @param height the height of the window in pixels, at least 1
     * @return the view
     * @throws IllegalArgumentException if the centre or the window is invalid
     */
    public static MapView2d of(MapProjection projection, double centerLat, double centerLon, int width, int height) {
        return new MapView2d(projection, centerLat, centerLon, 100.0, Orientation.NORTH_UP, 0.0, 0.0, 0.0, width, height);
    }

    // ---------------------------------------------------------------- changes

    /**
     * Gives a view with another centre.
     *
     * @param lat the latitude in radians
     * @param lon the longitude in radians
     * @return the changed view
     * @throws IllegalArgumentException if the centre is invalid
     */
    public MapView2d withCenter(double lat, double lon) {
        return new MapView2d(projection, lat, lon, metersPerPixel, orientation, upBearing, offsetX, offsetY, width, height);
    }

    /**
     * Gives a view with another size of a pixel on the ground at the centre.
     *
     * @param metersPerPixel the ground distance of a pixel in metres, positive
     * @return the changed view
     * @throws IllegalArgumentException if it is not positive
     */
    public MapView2d withMetersPerPixel(double metersPerPixel) {
        return new MapView2d(projection, centerLat, centerLon, metersPerPixel, orientation, upBearing, offsetX, offsetY, width, height);
    }

    /**
     * Gives a view with a range: the ground distance from the centre to the top edge of the
     * window.
     *
     * @param range the distance in metres, positive
     * @return the changed view
     * @throws IllegalArgumentException if it is not positive
     */
    public MapView2d withRange(double range) {
        if (!(range > 0)) {
            throw new IllegalArgumentException("the range must be positive: " + range);
        }
        return withMetersPerPixel(range / centerPixelY());
    }

    /**
     * Gives a view with a different orientation.
     *
     * @param orientation what points up; must not be {@code null}
     * @param bearing the true bearing in radians that points up: the course, the heading or the
     *     angle; ignored for {@link Orientation#NORTH_UP}
     * @return the changed view
     * @throws IllegalArgumentException if the bearing is not finite
     */
    public MapView2d withOrientation(Orientation orientation, double bearing) {
        return new MapView2d(projection, centerLat, centerLon, metersPerPixel, orientation, bearing, offsetX, offsetY, width, height);
    }

    /**
     * Gives a view with the centre somewhere else in the window.
     *
     * @param offsetX the shift of the centre to the right as a fraction of the width, in {@code [-0.5, 0.5]}
     * @param offsetY the shift of the centre downwards as a fraction of the height, in {@code [-0.5, 0.5]}
     * @return the changed view
     * @throws IllegalArgumentException if an offset is out of range
     */
    public MapView2d withCenterOffset(double offsetX, double offsetY) {
        return new MapView2d(projection, centerLat, centerLon, metersPerPixel, orientation, upBearing, offsetX, offsetY, width, height);
    }

    /**
     * Gives a view for another window.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @return the changed view
     * @throws IllegalArgumentException if a size is below 1
     */
    public MapView2d withViewport(int width, int height) {
        return new MapView2d(projection, centerLat, centerLon, metersPerPixel, orientation, upBearing, offsetX, offsetY, width, height);
    }

    /**
     * Gives a view in another projection with the same centre, scale on the ground and
     * orientation.
     *
     * @param projection the projection; must not be {@code null}
     * @return the changed view
     */
    public MapView2d withProjection(MapProjection projection) {
        return new MapView2d(projection, centerLat, centerLon, metersPerPixel, orientation, upBearing, offsetX, offsetY, width, height);
    }

    // ---------------------------------------------------------------- readings

    /**
     * Gives the projection.
     *
     * @return the projection of the view
     */
    public MapProjection projection() {
        return projection;
    }

    /**
     * Gives the latitude of the centre.
     *
     * @return the latitude in radians
     */
    public double centerLatitude() {
        return centerLat;
    }

    /**
     * Gives the longitude of the centre.
     *
     * @return the longitude in radians
     */
    public double centerLongitude() {
        return centerLon;
    }

    /**
     * Gives the projected x coordinate of the centre, the natural origin of what is drawn.
     *
     * @return the x coordinate in metres
     */
    public double centerX() {
        return centerX;
    }

    /**
     * Gives the projected y coordinate of the centre.
     *
     * @return the y coordinate in metres
     */
    public double centerY() {
        return centerY;
    }

    /**
     * Gives the size of a pixel on the ground at the centre.
     *
     * @return metres on the ground per pixel
     */
    public double metersPerPixel() {
        return metersPerPixel;
    }

    /**
     * Gives the size of a pixel in the units of the map (the projected metres).
     *
     * @return projected metres per pixel, the same everywhere on the window
     */
    public double mapUnitsPerPixel() {
        return unitsPerPixel;
    }

    /**
     * Gives the number of pixels per projected metre, the number that a line renderer takes as the
     * pixels per world unit for widths in the units of the map.
     *
     * @return the reciprocal of {@link #mapUnitsPerPixel}
     */
    public double pixelsPerMapUnit() {
        return 1.0 / unitsPerPixel;
    }

    /**
     * Gives the size of a pixel on the ground at another place: the pixel in map units divided by
     * the scale of the projection there.
     *
     * @param lat the latitude in radians
     * @param lon the longitude in radians
     * @return metres on the ground per pixel
     */
    public double groundMetersPerPixelAt(double lat, double lon) {
        return unitsPerPixel / projection.scale(lat, lon);
    }

    /**
     * Gives the range: the ground distance from the centre to the top edge of the window.
     *
     * @return the range in metres
     */
    public double range() {
        return metersPerPixel * centerPixelY();
    }

    /**
     * Gives what points up.
     *
     * @return the orientation mode
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * Gives the true bearing that points up the window at the centre.
     *
     * @return the bearing in radians, 0 for north up
     */
    public double upBearing() {
        return upBearing;
    }

    /**
     * Gives the angle that the map is turned by on the window: the grid bearing that points up.
     *
     * @return the grid bearing in radians (the true bearing up minus the convergence at the centre)
     */
    public double gridUp() {
        return gridUp;
    }

    /**
     * Gives the width of the window.
     *
     * @return pixels
     */
    public int width() {
        return width;
    }

    /**
     * Gives the height of the window.
     *
     * @return pixels
     */
    public int height() {
        return height;
    }

    /**
     * Gives the window x of the centre.
     *
     * @return pixels from the left edge
     */
    public double centerPixelX() {
        return width * (0.5 + offsetX);
    }

    /**
     * Gives the window y of the centre, which is also the number of pixels from the centre to the
     * top edge.
     *
     * @return pixels from the top edge
     */
    public double centerPixelY() {
        return height * (0.5 + offsetY);
    }

    // ---------------------------------------------------------------- conversions

    /**
     * Converts projected coordinates to window pixels.
     *
     * @param x the projected x in metres
     * @param y the projected y in metres
     * @param out receives the pixel x and y (y down from the top) at {@code out[0..1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void projectedToScreen(double x, double y, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double dx = x - centerX, dy = y - centerY;
        double right = dx * cosUp - dy * sinUp, up = dx * sinUp + dy * cosUp;
        out[0] = centerPixelX() + right / unitsPerPixel;
        out[1] = centerPixelY() - up / unitsPerPixel;
    }

    /**
     * Converts window pixels to projected coordinates.
     *
     * @param px the pixel x
     * @param py the pixel y (down from the top)
     * @param out receives the projected x and y in metres at {@code out[0..1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void screenToProjected(double px, double py, double[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double right = (px - centerPixelX()) * unitsPerPixel, up = (centerPixelY() - py) * unitsPerPixel;
        out[0] = centerX + right * cosUp + up * sinUp;
        out[1] = centerY - right * sinUp + up * cosUp;
    }

    /**
     * Converts a geographic position to window pixels.
     *
     * @param lat the latitude in radians
     * @param lon the longitude in radians
     * @param out receives the pixel x and y at {@code out[0..1]}
     * @throws IllegalArgumentException if the position is outside the domain of the projection or
     *     {@code out} is too short
     */
    public void toScreen(double lat, double lon, double[] out) {
        double[] xy = new double[2];
        projection.forward(lat, lon, xy);
        projectedToScreen(xy[0], xy[1], out);
    }

    /**
     * Converts window pixels to a geographic position.
     *
     * @param px the pixel x
     * @param py the pixel y
     * @param out receives the latitude and longitude in radians at {@code out[0..1]}
     * @throws IllegalArgumentException if the point is outside the domain of the projection or
     *     {@code out} is too short
     */
    public void toGeographic(double px, double py, double[] out) {
        double[] xy = new double[2];
        screenToProjected(px, py, xy);
        projection.inverse(xy[0], xy[1], out);
    }

    /**
     * Tells whether a pixel is inside the window.
     *
     * @param px the pixel x
     * @param py the pixel y
     * @return {@code true} if {@code 0 <= px < width} and {@code 0 <= py < height}
     */
    public boolean contains(double px, double py) {
        return px >= 0 && px < width && py >= 0 && py < height;
    }

    // ---------------------------------------------------------------- bounds

    /**
     * Gives the geographic bounds of the view, sampled along its edges and inside it, enlarged by a
     * margin.
     *
     * <p>The bounds of a view that contains a pole reach the pole and span all longitudes; those of
     * a view that crosses the antimeridian have {@code west > east} (as the clipping of
     * {@code Geodesy} takes them). Points of the window outside the domain of the projection (the
     * opposite pole of a polar projection, the far side of an azimuthal map) are skipped.
     *
     * @param marginPixels the margin to add around the window in pixels, at least 0
     * @param out receives {@code south, west, north, east} in radians at {@code out[0..3]}
     * @return {@code true} if the bounds are the whole range of longitudes (the view contains a
     *     pole or spans the world)
     * @throws IllegalArgumentException if the margin is negative or {@code out} is too short
     */
    public boolean bounds(double marginPixels, double[] out) {
        if (!(marginPixels >= 0) || out.length < 4) {
            throw new IllegalArgumentException("the margin must not be negative and out needs room for 4 values: " + marginPixels);
        }
        double south = Double.POSITIVE_INFINITY, north = Double.NEGATIVE_INFINITY;
        int n = 32;
        double[] ll = new double[2];
        double x0 = -marginPixels, x1 = width + marginPixels, y0 = -marginPixels, y1 = height + marginPixels;
        boolean any = false;
        // the longitudes along the boundary of the window, unwrapped as the boundary is walked once round: the extremes of the longitude are on the boundary, and a boundary that
        // encloses a pole does not come back to its first longitude
        double unwrapped = 0, firstLon = 0, west = Double.POSITIVE_INFINITY, east = Double.NEGATIVE_INFINITY, lastLon = Double.NaN;
        boolean winds = false;
        for (int k = 0; k <= 4 * n; k++) {
            int side = (k % (4 * n)) / n, i = k % n;
            double t = (double) i / n;
            double px = side == 0 ? x0 + (x1 - x0) * t : side == 1 ? x1 : side == 2 ? x1 - (x1 - x0) * t : x0;
            double py = side == 0 ? y0 : side == 1 ? y0 + (y1 - y0) * t : side == 2 ? y1 : y1 - (y1 - y0) * t;
            if (k == 4 * n) {
                px = x0;
                py = y0;
            }
            if (!sample(px, py, ll)) {
                continue;
            }
            any = true;
            south = Math.min(south, ll[0]);
            north = Math.max(north, ll[0]);
            if (Double.isNaN(lastLon)) {
                firstLon = ll[1];
                unwrapped = 0;
            } else {
                unwrapped += Geodesy.wrapPi(ll[1] - lastLon);
            }
            lastLon = ll[1];
            west = Math.min(west, unwrapped);
            east = Math.max(east, unwrapped);
            if (k == 4 * n && Math.abs(unwrapped) > Math.PI) {
                winds = true;   // back at the first point with a whole turn of longitude in between: a pole is inside
            }
        }
        if (!any) {
            out[0] = out[1] = out[2] = out[3] = Double.NaN;
            return false;
        }
        for (int i = 1; i < n; i++) {
            for (int j = 1; j < n; j += 4) {
                if (sample(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * j / n, ll)) {
                    south = Math.min(south, ll[0]);
                    north = Math.max(north, ll[0]);
                }
            }
        }
        boolean allLongitudes = winds || east - west >= 2 * Math.PI - 1e-9;
        double[] xy = new double[2], px = new double[2];
        for (double pole : new double[] {Math.PI / 2, -Math.PI / 2}) {
            try {
                projection.forward(pole, 0.0, xy);
                projectedToScreen(xy[0], xy[1], px);
                if (px[0] >= x0 && px[0] <= x1 && px[1] >= y0 && px[1] <= y1) {
                    allLongitudes = true;
                    if (pole > 0) {
                        north = Math.PI / 2;
                    } else {
                        south = -Math.PI / 2;
                    }
                }
            } catch (IllegalArgumentException notProjectable) {
                // the pole has no image in this projection
            }
        }
        out[0] = Math.max(-Math.PI / 2, south);
        out[2] = Math.min(Math.PI / 2, north);
        if (allLongitudes) {
            out[1] = -Math.PI;
            out[3] = Math.PI;
        } else {
            out[1] = Geodesy.wrapPi(firstLon + west);
            out[3] = Geodesy.wrapPi(firstLon + east);
        }
        return allLongitudes;
    }

    /** Unprojects a pixel; false if the point is outside the domain of the projection. */
    private boolean sample(double px, double py, double[] ll) {
        try {
            toGeographic(px, py, ll);
        } catch (IllegalArgumentException outside) {
            return false;
        }
        return Double.isFinite(ll[0]) && Double.isFinite(ll[1]);
    }

    // ---------------------------------------------------------------- the camera and the matrix

    /**
     * Gives the orthographic camera of the view, in coordinates relative to an origin: its
     * position is the centre of the view minus the origin, it is turned by the orientation, and
     * its box is the window in map units.
     *
     * @param originX the x of the origin in projected metres, usually the centre or a point near it
     * @param originY the y of the origin in projected metres
     * @param depth the depth convention of the camera
     * @return the camera, looking down the -z axis at the plane {@code z = 0} with a depth range of
     *     {@code -10000..10000} map units
     */
    public OrthoCamerad camera(double originX, double originY, DepthRange depth) {
        double left = -centerPixelX() * unitsPerPixel, right = (width - centerPixelX()) * unitsPerPixel;
        double top = centerPixelY() * unitsPerPixel, bottom = -(height - centerPixelY()) * unitsPerPixel;
        // the camera's up axis is the grid direction gridUp: a rotation about +z by -gridUp turns +y to (sin(gridUp), cos(gridUp))
        Quatd q = Quatd.fromAxisAngle(-gridUp, new Vec3d(0, 0, 1));
        return new OrthoCamerad(new Vec3d(centerX - originX, centerY - originY, 0.0), q, left, right, bottom, top, -10000.0, 10000.0, depth);
    }

    /**
     * Writes the view-projection matrix for geometry whose coordinates are the projected
     * coordinates minus an origin: it takes {@code (x - originX, y - originY, z)} to clip space.
     *
     * <p>The matrix is computed in double precision and narrowed once. For {@link ClipSpace#VULKAN}
     * the {@code y} of clip space points down, as that convention has it. The window pixel
     * {@code (px, py)} is at NDC {@code (2 px / width - 1, 1 - 2 py / height)} ({@code y} flipped
     * for Vulkan).
     *
     * @param space the clip space of the graphics API
     * @param originX the x of the origin in projected metres
     * @param originY the y of the origin in projected metres
     * @param out receives 16 values, column-major
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void viewProjection(ClipSpace space, double originX, double originY, float[] out) {
        viewProjection(space, false, originX, originY, out);
    }

    /**
     * As {@link #viewProjection(ClipSpace, double, double, float[])} with the option of reversed
     * depth, for a floating-point depth buffer and a greater-than depth test.
     *
     * @param space the clip space; must have a depth range of 0 to 1 when {@code reversedZ} is set
     * @param reversedZ whether the near plane maps to depth 1 and the far plane to 0
     * @param originX the x of the origin in projected metres
     * @param originY the y of the origin in projected metres
     * @param out receives 16 values, column-major
     * @throws IllegalArgumentException if reversed depth is asked of a clip space with depth -1 to 1,
     *     or {@code out} is too short
     */
    public void viewProjection(ClipSpace space, boolean reversedZ, double originX, double originY, float[] out) {
        if (out.length < 16) {
            throw new IllegalArgumentException("a matrix has 16 values");
        }
        if (reversedZ && !space.zeroToOne()) {
            throw new IllegalArgumentException("reversed depth needs a clip space with a depth range of 0 to 1: " + space);
        }
        DepthRange depth = reversedZ ? DepthRange.REVERSED_ZERO_TO_ONE : DepthRange.of(space);
        OrthoCamerad cam = camera(originX, originY, depth);
        Mat4d p = cam.projection();
        if (space.yDown()) {
            p = p.flipY();
        }
        p.mul(cam.view()).toFloat().writeTo(out, 0);
    }

    // ---------------------------------------------------------------- scale bar

    /**
     * Chooses the length of a scale bar: the longest round distance (1, 2 or 5 times a power of
     * ten) that is no longer than a number of pixels on the ground at the centre.
     *
     * @param maxPixels the longest bar in pixels, at least 1
     * @param out receives the length on the ground in metres and the length of the bar in pixels at
     *     {@code out[0..1]}
     * @throws IllegalArgumentException if {@code maxPixels} is below 1 or {@code out} is too short
     */
    public void scaleBar(int maxPixels, double[] out) {
        if (maxPixels < 1 || out.length < 2) {
            throw new IllegalArgumentException("maxPixels must be at least 1 and out needs room for 2 values");
        }
        double limit = maxPixels * metersPerPixel;
        double exponent = Math.pow(10, Math.floor(Math.log10(limit)));
        double best = exponent;
        for (double m : new double[] {1, 2, 5, 10}) {
            if (m * exponent <= limit * (1 + 1e-12)) {
                best = m * exponent;
            }
        }
        out[0] = best;
        out[1] = best / metersPerPixel;
    }
}
