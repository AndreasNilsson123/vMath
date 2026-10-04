package vmath.samples.demos.terrain;

import vmath.geo.ArcLengthTable;
import vmath.geo.Curves;

/**
 * A closed camera rail over the terrain: a centripetal Catmull-Rom spline through points that
 * circle the terrain at a height above the ground, and a table of its arc length so that the
 * camera can move along it at a constant speed in metres per second.
 *
 * <p>The spline is {@code Curves.catmullRom} with {@code alpha = 0.5}; its parameter is not
 * proportional to the distance, so moving it by equal steps makes the camera speed up and slow
 * down where the control points are unevenly spaced ({@link #positionAtParameter}). {@code
 * ArcLengthTable} measures the curve, and {@link #positionAtDistance} asks it for the parameter at
 * a distance, which gives a constant speed. The class does not use OpenGL and allocates nothing in
 * the position methods.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> The positions methods use no shared scratch memory except the control
 * points, which are not modified, but they write the result array that the caller passes; the
 * class may be shared between threads that use their own arrays.
 */
final class Rail {

    private final float[] points;
    private final int count;
    private final int segments;
    private final ArcLengthTable table;

    /**
     * Builds a rail around the origin.
     *
     * @param count the number of control points; at least 4
     * @param radius the mean radius of the loop in metres
     * @param clearance the height of the rail above the terrain at the control points, in metres
     */
    Rail(int count, float radius, float clearance) {
        if (count < 4) {
            throw new IllegalArgumentException("a closed rail needs at least 4 control points: " + count);
        }
        this.count = count;
        this.points = new float[count * 3];
        for (int i = 0; i < count; i++) {
            double a = 2.0 * Math.PI * i / count;
            // the spacing of the points is deliberately uneven: radius and angle wobble, so the parameter speed varies
            double r = radius * (0.8 + 0.2 * Math.sin(a * 3.0 + 0.7));
            double an = a + 0.18 * Math.sin(a * 2.0);
            float x = (float) (r * Math.cos(an)), z = (float) (r * Math.sin(an));
            points[i * 3] = x;
            points[i * 3 + 1] = Terrain.height(x, z) + clearance;
            points[i * 3 + 2] = z;
        }
        this.segments = Curves.segments(count, true, false);
        float[] scratch = new float[3];
        this.table = new ArcLengthTable((u, out) -> {
            Curves.catmullRom(points, 0, count, 3, true, 0.5, u, scratch, 0);
            out[0] = scratch[0];
            out[1] = scratch[1];
            out[2] = scratch[2];
        }, 3, 0.0, segments, 4096);
    }

    /**
     * Reads the length of the rail.
     *
     * @return the length in metres
     */
    float length() {
        return (float) table.length();
    }

    /**
     * Reads the largest value of the spline parameter.
     *
     * @return the number of spline segments, which is the parameter at which the loop closes
     */
    int segments() {
        return segments;
    }

    /**
     * Gives the point at a spline parameter, which is what a naive animation would move by equal
     * steps.
     *
     * @param u the parameter, wrapped into the loop
     * @param out receives the position, three floats
     */
    void positionAtParameter(double u, float[] out) {
        double w = u % segments;
        Curves.catmullRom(points, 0, count, 3, true, 0.5, w < 0 ? w + segments : w, out, 0);
    }

    /**
     * Gives the point at a distance along the rail, measured with the arc-length table.
     *
     * @param distance the distance from the start in metres, wrapped into the loop
     * @param out receives the position, three floats
     */
    void positionAtDistance(double distance, float[] out) {
        double len = table.length();
        double d = distance % len;
        positionAtParameter(table.parameterAt(d < 0 ? d + len : d), out);
    }
}
