package vmath.lines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vmath.annotations.Experimental;

/**
 * Many polylines with their styles: the data model that every line strategy draws, whichever
 * graphics API features it uses.
 *
 * <p>Positions are kept in <b>double</b> precision in world coordinates, so a map or a planet can be
 * described exactly. What reaches the GPU is relative to the batch's <b>origin</b>: a
 * {@code float} of {@code position - origin}, so the origin should be near what is on screen. When the
 * camera has moved far from it, {@link #originTooFar} says so and {@link #setOrigin} moves it; the
 * view-projection matrix of a frame is then made with {@link #relativeViewProjection}, which does the
 * product in double precision.
 *
 * <p>A polyline has at least two points (three if it is closed); consecutive equal points are
 * removed, and a point that is not finite is refused. Polylines are drawn in the order of the layer
 * of their style, and in the order they were added within a layer. The batch also keeps a bounding
 * box per polyline, for culling.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one writer, or any number of readers while nobody
 * writes.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * LineBatch batch = new LineBatch();
 * batch.setOrigin(4_000_000.0, 3_000_000.0, 0.0);
 * double[] route = {4_000_000.0, 3_000_000.0, 0.0, 4_000_500.0, 3_000_200.0, 0.0, 4_001_000.0, 3_000_100.0, 0.0};
 * batch.addPolyline(route, 0, 3, false, LineStyle.pixels(2f).withColor(0x00FF00FF));
 * }</pre>
 */
@Experimental("the data model may change")
public final class LineBatch {

    private double originX;
    private double originY;
    private double originZ;

    private double[] points = new double[64];
    private int pointsUsed;
    private int[] start = new int[16];
    private int[] count = new int[16];
    private boolean[] closed = new boolean[16];
    private int[] styleOf = new int[16];
    private double[] bounds = new double[16 * 6];
    private int polylines;
    private int segments;

    private final List<LineStyle> styles = new ArrayList<>();
    private final Map<LineStyle, Integer> styleIndex = new HashMap<>();

    private int[] order;
    private int orderFor = -1;

    /**
     * Creates an empty batch with its origin at zero.
     */
    public LineBatch() {
    }

    /**
     * Moves the origin that the positions are made relative to; nothing else changes, the next
     * frame is written relative to the new one.
     *
     * @param x the x coordinate of the origin
     * @param y the y coordinate of the origin
     * @param z the z coordinate of the origin
     * @throws IllegalArgumentException if a coordinate is not finite
     */
    public void setOrigin(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("the origin must be finite");
        }
        originX = x;
        originY = y;
        originZ = z;
    }

    /**
     * Gives the x coordinate of the origin.
     *
     * @return the origin x
     */
    public double originX() {
        return originX;
    }

    /**
     * Gives the y coordinate of the origin.
     *
     * @return the origin y
     */
    public double originY() {
        return originY;
    }

    /**
     * Gives the z coordinate of the origin.
     *
     * @return the origin z
     */
    public double originZ() {
        return originZ;
    }

    /**
     * Tells whether the camera is so far from the origin that the positions lose precision as
     * {@code float}s and the origin should be moved ({@link #setOrigin}, to the camera for
     * example).
     *
     * @param cameraX the x coordinate of the camera
     * @param cameraY the y coordinate of the camera
     * @param cameraZ the z coordinate of the camera
     * @param limit the largest distance to accept, in world units; a float has about seven
     *     digits, so a limit of a hundred thousand times the smallest size that has to be
     *     right is a place to start
     * @return {@code true} if the distance from the origin to the camera is above the limit
     */
    public boolean originTooFar(double cameraX, double cameraY, double cameraZ, double limit) {
        double dx = cameraX - originX, dy = cameraY - originY, dz = cameraZ - originZ;
        return dx * dx + dy * dy + dz * dz > limit * limit;
    }

    /**
     * Makes the view-projection matrix of a frame relative to the origin: {@code viewProjection *
     * translate(origin)}, computed in double precision and rounded once, which is what the
     * relative positions need.
     *
     * @param viewProjection the matrix of the camera in world coordinates, column-major, 16
     *     values; must not be {@code null}
     * @param out receives the result, column-major; must have room for 16 values
     * @throws IllegalArgumentException if an array is too short
     */
    public void relativeViewProjection(double[] viewProjection, float[] out) {
        relativeMatrix(viewProjection, out, originX, originY, originZ);
    }

    static void relativeMatrix(double[] viewProjection, float[] out, double originX, double originY, double originZ) {
        if (viewProjection.length < 16 || out.length < 16) {
            throw new IllegalArgumentException("a matrix has 16 values");
        }
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 4; r++) {
                out[c * 4 + r] = (float) viewProjection[c * 4 + r];
            }
        }
        for (int r = 0; r < 4; r++) {
            out[12 + r] = (float) (viewProjection[r] * originX + viewProjection[4 + r] * originY + viewProjection[8 + r] * originZ + viewProjection[12 + r]);
        }
    }

    /**
     * Adds a polyline.
     *
     * @param xyz the points as {@code x, y, z} triples in world coordinates; copied; must not be
     *     {@code null}
     * @param offset the index in {@code xyz} of the first value
     * @param pointCount the number of points
     * @param isClosed whether the last point is joined to the first
     * @param style the style; must not be {@code null}
     * @return the index of the polyline
     * @throws IllegalArgumentException if there are too few distinct points (two, or three for a
     *     closed polyline), a coordinate is not finite, or the array is too short
     */
    public int addPolyline(double[] xyz, int offset, int pointCount, boolean isClosed, LineStyle style) {
        if (pointCount < 0 || offset < 0 || xyz.length < offset + 3L * pointCount) {
            throw new IllegalArgumentException("the array has " + xyz.length + " values for " + pointCount + " points at " + offset);
        }
        if (points.length < pointsUsed + 3 * pointCount) {
            points = Arrays.copyOf(points, Math.max(points.length * 2, pointsUsed + 3 * pointCount));
        }
        int first = pointsUsed;
        int kept = copyDistinct(xyz, offset, pointCount, isClosed, points, first);
        if (polylines == start.length) {
            int n = polylines * 2;
            start = Arrays.copyOf(start, n);
            count = Arrays.copyOf(count, n);
            closed = Arrays.copyOf(closed, n);
            styleOf = Arrays.copyOf(styleOf, n);
            bounds = Arrays.copyOf(bounds, n * 6);
        }
        boundsOf(points, first, kept, bounds, polylines * 6);
        Integer existing = styleIndex.get(style);
        int si;
        if (existing == null) {
            si = styles.size();
            styles.add(style);
            styleIndex.put(style, si);
        } else {
            si = existing;
        }
        start[polylines] = first / 3;
        count[polylines] = kept;
        closed[polylines] = isClosed;
        styleOf[polylines] = si;
        pointsUsed = first + 3 * kept;
        segments += isClosed ? kept : kept - 1;
        orderFor = -1;
        return polylines++;
    }

    /**
     * Counts the polylines.
     *
     * @return the number of polylines
     */
    public int polylineCount() {
        return polylines;
    }

    /**
     * Counts the segments of all polylines: a closed polyline has as many as points, an open one
     * one fewer.
     *
     * @return the number of segments
     */
    public int segmentCount() {
        return segments;
    }

    /**
     * Counts the distinct styles.
     *
     * @return the number of entries a style table needs
     */
    public int styleCount() {
        return styles.size();
    }

    /**
     * Gives a style of the table.
     *
     * @param index the index in the style table
     * @return the style
     * @throws IndexOutOfBoundsException if {@code index} is not in the table
     */
    public LineStyle style(int index) {
        return styles.get(index);
    }

    /**
     * Gives the index in the style table of a polyline's style.
     *
     * @param polyline the polyline
     * @return the index
     * @throws IndexOutOfBoundsException if there is no such polyline
     */
    public int styleIndexOf(int polyline) {
        check(polyline);
        return styleOf[polyline];
    }

    /**
     * Counts the points of a polyline, after the removal of repeated points.
     *
     * @param polyline the polyline
     * @return the number of points
     * @throws IndexOutOfBoundsException if there is no such polyline
     */
    public int pointCount(int polyline) {
        check(polyline);
        return count[polyline];
    }

    /**
     * Tells whether a polyline is closed.
     *
     * @param polyline the polyline
     * @return {@code true} if its last point is joined to its first
     * @throws IndexOutOfBoundsException if there is no such polyline
     */
    public boolean isClosed(int polyline) {
        check(polyline);
        return closed[polyline];
    }

    /**
     * Reads a coordinate of a point.
     *
     * @param polyline the polyline
     * @param point the index of the point in the polyline
     * @param axis 0 for x, 1 for y, 2 for z
     * @return the world coordinate
     * @throws IndexOutOfBoundsException if the polyline, the point or the axis does not exist
     */
    public double coordinate(int polyline, int point, int axis) {
        check(polyline);
        if (point < 0 || point >= count[polyline] || axis < 0 || axis > 2) {
            throw new IndexOutOfBoundsException("point " + point + ", axis " + axis);
        }
        return points[3 * (start[polyline] + point) + axis];
    }

    /**
     * Copies the bounding box of a polyline (of its points, not of its width).
     *
     * @param polyline the polyline
     * @param out receives {@code minX, minY, minZ, maxX, maxY, maxZ}; must have room for 6 values
     * @throws IndexOutOfBoundsException if there is no such polyline
     */
    public void bounds(int polyline, double[] out) {
        check(polyline);
        System.arraycopy(bounds, polyline * 6, out, 0, 6);
    }

    /**
     * Validates the points of a polyline and copies them without repeated points.
     *
     * @return the number of points copied to {@code dst} at {@code dstOffset}
     */
    static int copyDistinct(double[] xyz, int offset, int pointCount, boolean isClosed, double[] dst, int dstOffset) {
        if (pointCount < 0 || offset < 0 || xyz.length < offset + 3L * pointCount) {
            throw new IllegalArgumentException("the array has " + xyz.length + " values for " + pointCount + " points at " + offset);
        }
        for (int i = 0; i < 3 * pointCount; i++) {
            if (!Double.isFinite(xyz[offset + i])) {
                throw new IllegalArgumentException("point " + i / 3 + " is not finite");
            }
        }
        int first = dstOffset;
        int kept = 0;
        for (int i = 0; i < pointCount; i++) {
            double x = xyz[offset + 3 * i], y = xyz[offset + 3 * i + 1], z = xyz[offset + 3 * i + 2];
            if (kept > 0 && dst[first + 3 * (kept - 1)] == x && dst[first + 3 * (kept - 1) + 1] == y && dst[first + 3 * (kept - 1) + 2] == z) {
                continue;
            }
            dst[first + 3 * kept] = x;
            dst[first + 3 * kept + 1] = y;
            dst[first + 3 * kept + 2] = z;
            kept++;
        }
        if (isClosed && kept > 1 && dst[first] == dst[first + 3 * (kept - 1)] && dst[first + 1] == dst[first + 3 * (kept - 1) + 1]
                && dst[first + 2] == dst[first + 3 * (kept - 1) + 2]) {
            kept--; // the first point repeated at the end: the closing segment is implicit
        }
        int needed = isClosed ? 3 : 2;
        if (kept < needed) {
            throw new IllegalArgumentException("a " + (isClosed ? "closed " : "") + "polyline needs " + needed + " distinct points, has " + kept);
        }
        return kept;
    }

    static void boundsOf(double[] pts, int firstValue, int count, double[] out, int outOffset) {
        for (int k = 0; k < 3; k++) {
            out[outOffset + k] = Double.POSITIVE_INFINITY;
            out[outOffset + 3 + k] = Double.NEGATIVE_INFINITY;
        }
        for (int i = 0; i < count; i++) {
            for (int k = 0; k < 3; k++) {
                double v = pts[firstValue + 3 * i + k];
                out[outOffset + k] = Math.min(out[outOffset + k], v);
                out[outOffset + 3 + k] = Math.max(out[outOffset + 3 + k], v);
            }
        }
    }

    double[] pointArray() {
        return points;
    }

    int firstPoint(int polyline) {
        return start[polyline];
    }

    private void check(int polyline) {
        if (polyline < 0 || polyline >= polylines) {
            throw new IndexOutOfBoundsException("polyline " + polyline + " of " + polylines);
        }
    }

    /**
     * Gives the order in which the polylines are drawn: by the layer of their style, then in the
     * order they were added.
     *
     * @return the polyline indices in drawing order; the array belongs to the batch and must not
     *     be changed
     */
    public int[] drawOrder() {
        if (orderFor != polylines || order == null) {
            Integer[] boxed = new Integer[polylines];
            for (int i = 0; i < polylines; i++) {
                boxed[i] = i;
            }
            Arrays.sort(boxed, (a, b) -> Integer.compare(styles.get(styleOf[a]).layer(), styles.get(styleOf[b]).layer()));
            order = new int[polylines];
            for (int i = 0; i < polylines; i++) {
                order[i] = boxed[i];
            }
            orderFor = polylines;
        }
        return order;
    }

    /**
     * Removes everything, keeping the origin and the memory.
     */
    public void clear() {
        pointsUsed = 0;
        polylines = 0;
        segments = 0;
        styles.clear();
        styleIndex.clear();
        orderFor = -1;
    }

    /**
     * Writes a point relative to the origin as {@code float}s.
     *
     * @param polyline the polyline
     * @param point the index of the point in the polyline
     * @param out receives {@code x, y, z} minus the origin, at {@code offset}
     * @param offset the index in {@code out} of the first value
     * @throws IndexOutOfBoundsException if the polyline or the point does not exist
     */
    public void relativePoint(int polyline, int point, float[] out, int offset) {
        check(polyline);
        if (point < 0 || point >= count[polyline]) {
            throw new IndexOutOfBoundsException("point " + point);
        }
        int i = 3 * (start[polyline] + point);
        out[offset] = (float) (points[i] - originX);
        out[offset + 1] = (float) (points[i + 1] - originY);
        out[offset + 2] = (float) (points[i + 2] - originZ);
    }
}
