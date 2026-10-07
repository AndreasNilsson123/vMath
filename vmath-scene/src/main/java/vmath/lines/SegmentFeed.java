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

    private double[] pts;
    private int first;
    private int points;
    private boolean closed;
    private int next;
    private int segments;
    private double cumulative;
    private double period;
    private double ox;
    private double oy;
    private double oz;

    /**
     * Starts the walk over a polyline.
     *
     * @param b the batch
     * @param polylineIndex the polyline
     * @return the number of segments of the polyline
     */
    int begin(LineBatch b, int polylineIndex) {
        return begin(b.pointArray(), b.firstPoint(polylineIndex), b.pointCount(polylineIndex), b.isClosed(polylineIndex), b.style(b.styleIndexOf(polylineIndex)).dashPeriod(),
                b.originX(), b.originY(), b.originZ());
    }

    /**
     * Starts the walk over points that are not in a batch.
     *
     * @param xyz the coordinates as triples
     * @param firstPoint the index of the first point (not of its first value)
     * @param pointCount the number of points, at least two
     * @param isClosed whether the last point is joined to the first
     * @param dashPeriod the length of the dash pattern, 0 for none
     * @param originX the x coordinate of the origin
     * @param originY the y coordinate of the origin
     * @param originZ the z coordinate of the origin
     * @return the number of segments
     */
    int begin(double[] xyz, int firstPoint, int pointCount, boolean isClosed, double dashPeriod, double originX, double originY, double originZ) {
        pts = xyz;
        first = firstPoint;
        points = pointCount;
        closed = isClosed;
        segments = closed ? points : points - 1;
        next = 0;
        cumulative = 0;
        period = dashPeriod;
        ox = originX;
        oy = originY;
        oz = originZ;
        return segments;
    }

    private void relative(int point, float[] out) {
        int i = 3 * (first + point);
        out[0] = (float) (pts[i] - ox);
        out[1] = (float) (pts[i + 1] - oy);
        out[2] = (float) (pts[i + 2] - oz);
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
        relative(i, p0);
        relative(j, p1);
        int a = 3 * (first + i), b = 3 * (first + j);
        double dx = pts[b] - pts[a];
        double dy = pts[b + 1] - pts[a + 1];
        double dz = pts[b + 2] - pts[a + 2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double base = period > 0 ? cumulative % period : cumulative;
        along0 = (float) base;
        along1 = (float) (base + length);
        cumulative += length;
        flags = 0;
        if (closed || i > 0) {
            flags |= LineGeometry.FLAG_HAS_PREV;
            relative((i + points - 1) % points, prev);
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
