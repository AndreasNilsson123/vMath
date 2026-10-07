package vmath.lines;

/**
 * Walks the segments of one polyline of a {@link LineBatch} and gives, for each, what a segment
 * record and the reference expansion need.
 *
 * <p>Internal: positions are relative to the origin of the batch as {@code float}s, the distance
 * along the polyline is accumulated in double precision and, for a dashed style, reduced modulo
 * the length of the dash pattern before it is rounded, so that the phase of the dashes stays
 * exact on long polylines.
 *
 * <p><b>Thread safety.</b> Not thread-safe.
 */
final class SegmentFeed {

    final float[] p0 = new float[3];
    final float[] p1 = new float[3];
    final float[] prev = new float[3];
    float along0;
    float along1;
    int flags;

    private LineBatch batch;
    private int polyline;
    private int points;
    private boolean closed;
    private int next;
    private int segments;
    private double cumulative;
    private double period;

    /**
     * Starts the walk over a polyline.
     *
     * @param b the batch
     * @param polylineIndex the polyline
     * @return the number of segments of the polyline
     */
    int begin(LineBatch b, int polylineIndex) {
        batch = b;
        polyline = polylineIndex;
        points = b.pointCount(polylineIndex);
        closed = b.isClosed(polylineIndex);
        segments = closed ? points : points - 1;
        next = 0;
        cumulative = 0;
        period = b.style(b.styleIndexOf(polylineIndex)).dashPeriod();
        return segments;
    }

    /**
     * Moves to the next segment.
     *
     * @return {@code false} when the polyline has no more
     */
    boolean next() {
        if (next >= segments) {
            return false;
        }
        int i = next++;
        int j = (i + 1) % points;
        batch.relativePoint(polyline, i, p0, 0);
        batch.relativePoint(polyline, j, p1, 0);
        double dx = batch.coordinate(polyline, j, 0) - batch.coordinate(polyline, i, 0);
        double dy = batch.coordinate(polyline, j, 1) - batch.coordinate(polyline, i, 1);
        double dz = batch.coordinate(polyline, j, 2) - batch.coordinate(polyline, i, 2);
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double base = period > 0 ? cumulative % period : cumulative;
        along0 = (float) base;
        along1 = (float) (base + length);
        cumulative += length;
        flags = 0;
        if (closed || i > 0) {
            flags |= LineGeometry.FLAG_HAS_PREV;
            batch.relativePoint(polyline, (i + points - 1) % points, prev, 0);
        } else {
            flags |= LineGeometry.FLAG_START_CAP;
            prev[0] = p0[0];
            prev[1] = p0[1];
            prev[2] = p0[2];
        }
        if (!closed && i == segments - 1) {
            flags |= LineGeometry.FLAG_END_CAP;
        }
        return true;
    }
}
