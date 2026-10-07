package vmath.lines;

import java.util.Arrays;
import vmath.annotations.Experimental;
import vmath.geo.DepthRange;

/**
 * Clips line segments and polylines to a rectangle, a box, or any convex volume given by planes
 * such as a view frustum, in double precision.
 *
 * <p>{@link #clipRectangle} is the Liang-Barsky algorithm in the x and y axes (z follows the
 * parameter), {@link #clipBox} its three-dimensional form, and {@link #clipPlanes} the general
 * form (Cyrus-Beck) for planes {@code a*x + b*y + c*z + d >= 0} (inside is the positive side),
 * with {@link #frustumPlanes} to make the six planes of a view-projection matrix. The frustum
 * form also solves the limit of the line strategies: a segment with an end behind the camera is
 * dropped by them, and clipped to the near plane here it can be drawn.
 *
 * <p>A clipped segment is the part of the segment between the parameters {@code t0} and
 * {@code t1} (0 at the start, 1 at the end). {@link #clipPolyline} cuts a whole polyline into
 * pieces and tells the piece sink the distance along the original polyline at which each starts,
 * so that the pattern of a dash can be continued ({@link LineStyle} dashes are measured from the
 * start of a polyline: a piece of a dashed line added as its own polyline starts its pattern again
 * unless the dash phase is kept by the caller, for example by clipping with a margin so that the
 * cut is outside the screen).
 *
 * <p><b>Allocation.</b> The segment methods allocate nothing. {@link #clipPolyline} allocates its
 * working arrays for each call (the pieces grow with the polyline), so clip when a polyline
 * changes, not every frame.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads on different
 * arrays; a {@link Pieces} receives its calls on the calling thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double[] seg = {-50, 10, 0, 150, 30, 0};
 * double[] out = new double[8];
 * if (LineClipper.clipRectangle(seg, 0, 0, 0, 100, 100, out, 0)) {
 *     // out[0..5] is the part inside; out[6] and out[7] are t0 and t1
 * }
 * }</pre>
 */
@Experimental("the clipper may change")
public final class LineClipper {

    private LineClipper() {
    }

    /**
     * Receives the pieces of a clipped polyline.
     */
    @FunctionalInterface
    public interface Pieces {
        /**
         * Receives one piece.
         *
         * @param xyz the points of the piece as triples, from index 0; the array is reused by the
         *     clipper and must be copied if it is kept
         * @param pointCount the number of points, at least 2
         * @param startDistance the distance along the original polyline, from its first point, at
         *     which the piece starts
         */
        void piece(double[] xyz, int pointCount, double startDistance);
    }

    private static void write(double[] s, int o, double t0, double t1, double[] out, int outOffset) {
        double dx = s[o + 3] - s[o], dy = s[o + 4] - s[o + 1], dz = s[o + 5] - s[o + 2];
        double x0 = s[o] + t0 * dx, y0 = s[o + 1] + t0 * dy, z0 = s[o + 2] + t0 * dz;
        double x1 = s[o] + t1 * dx, y1 = s[o + 1] + t1 * dy, z1 = s[o + 2] + t1 * dz;
        out[outOffset] = x0;
        out[outOffset + 1] = y0;
        out[outOffset + 2] = z0;
        out[outOffset + 3] = x1;
        out[outOffset + 4] = y1;
        out[outOffset + 5] = z1;
        out[outOffset + 6] = t0;
        out[outOffset + 7] = t1;
    }

    private static void checkSegment(double[] s, int o, double[] out, int outOffset) {
        if (s.length < o + 6 || o < 0) {
            throw new IllegalArgumentException("a segment has 6 values");
        }
        if (out.length < outOffset + 8 || outOffset < 0) {
            throw new IllegalArgumentException("the output has room for 8 values: 6 coordinates, t0 and t1");
        }
    }

    /** One Liang-Barsky edge, {@code p*t <= q}: narrows the interval kept at {@code t[o]} and {@code t[o + 1]}; false if it becomes empty. */
    private static boolean edge(double p, double q, double[] t, int o) {
        if (p == 0) {
            return q >= 0;
        }
        double r = q / p;
        if (p < 0) {
            if (r > t[o + 1]) {
                return false;
            }
            if (r > t[o]) {
                t[o] = r;
            }
        } else {
            if (r < t[o]) {
                return false;
            }
            if (r < t[o + 1]) {
                t[o + 1] = r;
            }
        }
        return true;
    }

    /**
     * Clips a segment to a rectangle in x and y (Liang-Barsky).
     *
     * @param segment the segment as {@code x0, y0, z0, x1, y1, z1}
     * @param offset the index in {@code segment} of the first value
     * @param minX the left edge
     * @param minY the bottom edge
     * @param maxX the right edge
     * @param maxY the top edge
     * @param out receives the clipped segment (6 values) and then {@code t0} and {@code t1}
     * @param outOffset the index in {@code out} of the first value
     * @return {@code false} if nothing of the segment is inside ({@code out} is then not meaningful)
     * @throws IllegalArgumentException if an array is too short
     */
    public static boolean clipRectangle(double[] segment, int offset, double minX, double minY, double maxX, double maxY, double[] out, int outOffset) {
        checkSegment(segment, offset, out, outOffset);
        int t = outOffset + 6;                   // the interval is kept in the output: no allocation
        out[t] = 0;
        out[t + 1] = 1;
        double dx = segment[offset + 3] - segment[offset], dy = segment[offset + 4] - segment[offset + 1];
        if (!edge(-dx, segment[offset] - minX, out, t) || !edge(dx, maxX - segment[offset], out, t) || !edge(-dy, segment[offset + 1] - minY, out, t)
                || !edge(dy, maxY - segment[offset + 1], out, t)) {
            return false;
        }
        write(segment, offset, out[t], out[t + 1], out, outOffset);
        return true;
    }

    /**
     * Clips a segment to an axis-aligned box (the three-dimensional Liang-Barsky).
     *
     * @param segment the segment as {@code x0, y0, z0, x1, y1, z1}
     * @param offset the index in {@code segment} of the first value
     * @param box {@code minX, minY, minZ, maxX, maxY, maxZ}
     * @param out receives the clipped segment (6 values) and then {@code t0} and {@code t1}
     * @param outOffset the index in {@code out} of the first value
     * @return {@code false} if nothing of the segment is inside
     * @throws IllegalArgumentException if an array is too short
     */
    public static boolean clipBox(double[] segment, int offset, double[] box, double[] out, int outOffset) {
        checkSegment(segment, offset, out, outOffset);
        if (box.length < 6) {
            throw new IllegalArgumentException("a box has 6 values");
        }
        int t = outOffset + 6;
        out[t] = 0;
        out[t + 1] = 1;
        for (int k = 0; k < 3; k++) {
            double d = segment[offset + 3 + k] - segment[offset + k];
            if (!edge(-d, segment[offset + k] - box[k], out, t) || !edge(d, box[3 + k] - segment[offset + k], out, t)) {
                return false;
            }
        }
        write(segment, offset, out[t], out[t + 1], out, outOffset);
        return true;
    }

    /**
     * Clips a segment to the convex volume inside planes (Cyrus-Beck).
     *
     * @param segment the segment as {@code x0, y0, z0, x1, y1, z1}
     * @param offset the index in {@code segment} of the first value
     * @param planes the planes as {@code a, b, c, d} quadruples; a point is inside when
     *     {@code a*x + b*y + c*z + d >= 0} for all of them
     * @param planeCount the number of planes
     * @param out receives the clipped segment (6 values) and then {@code t0} and {@code t1}
     * @param outOffset the index in {@code out} of the first value
     * @return {@code false} if nothing of the segment is inside
     * @throws IllegalArgumentException if an array is too short
     */
    public static boolean clipPlanes(double[] segment, int offset, double[] planes, int planeCount, double[] out, int outOffset) {
        checkSegment(segment, offset, out, outOffset);
        if (planeCount < 0 || planes.length < 4 * planeCount) {
            throw new IllegalArgumentException("the planes array must hold " + planeCount + " planes");
        }
        int t = outOffset + 6;
        out[t] = 0;
        out[t + 1] = 1;
        double dx = segment[offset + 3] - segment[offset], dy = segment[offset + 4] - segment[offset + 1], dz = segment[offset + 5] - segment[offset + 2];
        for (int i = 0; i < planeCount; i++) {
            double a = planes[4 * i], b = planes[4 * i + 1], c = planes[4 * i + 2], d = planes[4 * i + 3];
            double start = a * segment[offset] + b * segment[offset + 1] + c * segment[offset + 2] + d; // distance at t = 0
            double slope = a * dx + b * dy + c * dz;                                                      // start + slope * t >= 0
            if (!edge(-slope, start, out, t)) {
                return false;
            }
        }
        write(segment, offset, out[t], out[t + 1], out, outOffset);
        return true;
    }

    /**
     * Makes the four planes of a rectangle in x and y, for {@link #clipPolyline}.
     *
     * @param minX the left edge
     * @param minY the bottom edge
     * @param maxX the right edge
     * @param maxY the top edge
     * @param out receives 4 planes (16 values)
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void rectanglePlanes(double minX, double minY, double maxX, double maxY, double[] out) {
        if (out.length < 16) {
            throw new IllegalArgumentException("4 planes are 16 values");
        }
        double[] p = {1, 0, 0, -minX, -1, 0, 0, maxX, 0, 1, 0, -minY, 0, -1, 0, maxY};
        System.arraycopy(p, 0, out, 0, 16);
    }

    /**
     * Makes the six planes of a view frustum from a view-projection matrix (Gribb and Hartmann),
     * in the space the matrix takes: world coordinates for a world matrix, coordinates relative to
     * the origin of a batch for the matrix of {@link LineBatch#relativeViewProjection}.
     *
     * @param viewProjection the matrix, column-major, 16 values
     * @param depth the depth range of the projection
     * @param out receives 6 planes (24 values), the normals pointing into the frustum, in the order
     *     left, right, bottom, top, near, far
     * @throws IllegalArgumentException if an array is too short
     */
    public static void frustumPlanes(double[] viewProjection, DepthRange depth, double[] out) {
        if (viewProjection.length < 16 || out.length < 24) {
            throw new IllegalArgumentException("a matrix has 16 values and 6 planes are 24");
        }
        double[] m = viewProjection;
        double[] row0 = {m[0], m[4], m[8], m[12]}, row1 = {m[1], m[5], m[9], m[13]}, row2 = {m[2], m[6], m[10], m[14]}, row3 = {m[3], m[7], m[11], m[15]};
        for (int k = 0; k < 4; k++) {
            out[k] = row3[k] + row0[k];
            out[4 + k] = row3[k] - row0[k];
            out[8 + k] = row3[k] + row1[k];
            out[12 + k] = row3[k] - row1[k];
        }
        for (int k = 0; k < 4; k++) {
            switch (depth) {
                case NEGATIVE_ONE_TO_ONE -> {
                    out[16 + k] = row3[k] + row2[k];
                    out[20 + k] = row3[k] - row2[k];
                }
                case ZERO_TO_ONE -> {
                    out[16 + k] = row2[k];
                    out[20 + k] = row3[k] - row2[k];
                }
                case REVERSED_ZERO_TO_ONE -> {
                    out[16 + k] = row3[k] - row2[k];
                    out[20 + k] = row2[k];
                }
            }
        }
    }

    /**
     * Clips a polyline to a convex volume and gives the pieces that are inside. Consecutive
     * segments that stay inside are one piece; a piece ends where the polyline leaves the volume.
     *
     * @param xyz the points as triples
     * @param offset the index in {@code xyz} of the first value
     * @param pointCount the number of points
     * @param closed whether the last point is joined to the first
     * @param planes the planes as quadruples, see {@link #clipPlanes}
     * @param planeCount the number of planes
     * @param sink receives the pieces; must not be {@code null}
     * @return the number of pieces
     * @throws IllegalArgumentException if an array is too short
     */
    public static int clipPolyline(double[] xyz, int offset, int pointCount, boolean closed, double[] planes, int planeCount, Pieces sink) {
        if (pointCount < 0 || offset < 0 || xyz.length < offset + 3L * pointCount) {
            throw new IllegalArgumentException("the array has " + xyz.length + " values for " + pointCount + " points at " + offset);
        }
        if (pointCount < 2) {
            return 0;
        }
        double[] piece = new double[3 * 16];
        int pieceSize = 0;
        double pieceStart = 0;
        int pieces = 0;
        double travelled = 0;
        double[] seg = new double[6];
        double[] clipped = new double[8];
        int segments = closed ? pointCount : pointCount - 1;
        for (int i = 0; i < segments; i++) {
            int a = offset + 3 * i, b = offset + 3 * ((i + 1) % pointCount);
            System.arraycopy(xyz, a, seg, 0, 3);
            System.arraycopy(xyz, b, seg, 3, 3);
            double length = Math.sqrt(sq(seg[3] - seg[0]) + sq(seg[4] - seg[1]) + sq(seg[5] - seg[2]));
            boolean inside = clipPlanes(seg, 0, planes, planeCount, clipped, 0);
            if (!inside) {
                if (pieceSize >= 2) {
                    sink.piece(piece, pieceSize, pieceStart);
                    pieces++;
                }
                pieceSize = 0;
                travelled += length;
                continue;
            }
            boolean continues = pieceSize > 0 && clipped[6] == 0;
            if (!continues) {
                if (pieceSize >= 2) {
                    sink.piece(piece, pieceSize, pieceStart);
                    pieces++;
                }
                pieceSize = 0;
                pieceStart = travelled + clipped[6] * length;
                piece = ensure(piece, pieceSize + 2);
                System.arraycopy(clipped, 0, piece, 0, 3);
                pieceSize = 1;
            }
            piece = ensure(piece, pieceSize + 1);
            System.arraycopy(clipped, 3, piece, 3 * pieceSize, 3);
            pieceSize++;
            if (clipped[7] < 1) {
                if (pieceSize >= 2) {
                    sink.piece(piece, pieceSize, pieceStart);
                    pieces++;
                }
                pieceSize = 0;
            }
            travelled += length;
        }
        if (pieceSize >= 2) {
            sink.piece(piece, pieceSize, pieceStart);
            pieces++;
        }
        return pieces;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double[] ensure(double[] a, int points) {
        return a.length >= 3 * points ? a : Arrays.copyOf(a, Math.max(a.length * 2, 3 * points));
    }
}
