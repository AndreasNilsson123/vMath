package vmath.lines;

import vmath.annotations.Experimental;

/**
 * The reference expansion of a {@link LineBatch} into triangles in screen pixels: what every
 * strategy of {@link LineRenderPlan} has to produce, computed straight from the batch with no
 * buffers, no draws and no shaders in between.
 *
 * <p>The polylines come in drawing order (by layer). Joins, caps, widths and dashes are those of
 * {@link LineGeometry}, whose definition the tests check against an independent one (Java 2D
 * strokes). The triangles go to a {@link LineGeometry.Sink}, for instance one that fills a
 * {@link CoverageRaster}.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads, each with its own
 * sink.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * CoverageRaster raster = new CoverageRaster(256, 256);
 * LineExpander.expand(batch, viewProjection, 256f, 256f, worldToPixel,
 *         (x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount, color) -> raster.fill(x0, y0, a0, x1, y1, a1, x2, y2, a2, dash, dashCount));
 * }</pre>
 */
@Experimental("the reference may change")
public final class LineExpander {

    private LineExpander() {
    }

    /**
     * Expands a batch.
     *
     * @param batch the batch; must not be {@code null}
     * @param viewProjection the matrix relative to the origin of the batch, column-major, 16
     *     values ({@link LineBatch#relativeViewProjection})
     * @param viewWidth the width of the viewport in pixels
     * @param viewHeight the height of the viewport in pixels
     * @param worldToPixel pixels per world unit at {@code w = 1}, for lines with a width in world
     *     units: half the viewport height times the element {@code [1][1]} of the projection matrix
     * @param sink receives the triangles; must not be {@code null}
     */
    public static void expand(LineBatch batch, float[] viewProjection, float viewWidth, float viewHeight, float worldToPixel, LineGeometry.Sink sink) {
        LineGeometry.Emitter emitter = new LineGeometry.Emitter();
        SegmentFeed feed = new SegmentFeed();
        for (int polyline : batch.drawOrder()) {
            LineStyle s = batch.style(batch.styleIndexOf(polyline));
            float[] dash = s.dashCount() > 0 ? s.dash() : null;
            int cap = LineGeometry.capCode(s.cap()), join = LineGeometry.joinCode(s.join());
            boolean world = s.unit() == LineStyle.WidthUnit.WORLD;
            feed.begin(batch, polyline);
            while (feed.next()) {
                emitter.emitProjected(sink, viewProjection, viewWidth, viewHeight, worldToPixel, feed.p0, feed.p1, feed.prev, feed.flags, feed.along0, feed.along1, s.width(), world, cap,
                        join, s.miterLimit(), dash, s.dashCount(), s.color());
            }
        }
    }
}
