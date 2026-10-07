package vmath.lines;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;
import vmath.gl.DrawList;
import vmath.gl.GpuWriter;

/**
 * What the shaders of a {@link LineRenderPlan} compute, run on the CPU from the buffers and the
 * draws that {@link LineRenderPlan#write} produced.
 *
 * <p>For every draw it does what the GPU does for the strategy of the plan: the instanced
 * strategies run the {@value LineGeometry#VERTICES_PER_SEGMENT} vertices of every instance from
 * the base instance of the draw on and read the segment as an instance attribute;
 * {@link LineStrategy#EXPANDED_MULTIDRAW} finds the segment of a vertex by dividing its index; and
 * the style comes from the draw index for {@link LineStrategy#INDIRECT_DRAW_ID} and from the
 * segment record for the rest. The vertex arithmetic is {@link LineGeometry}'s, so comparing the
 * triangles with the {@link LineExpander}'s checks the writers, the layouts, the draws and the
 * style plumbing, which are everything except the text of the shader.
 *
 * <p>Draws with an instance count of 0 are skipped, as a GPU does; the draw index of
 * {@link LineStrategy#INDIRECT_DRAW_ID} is the index in the list, zero-instance draws included, as
 * in an indirect multi-draw.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads, each with its own
 * sink.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * LineShaderModel.evaluate(plan, data, styles, draws, viewProjection, 256f, 256f, worldToPixel, sink);
 * }</pre>
 */
@Experimental("a test tool")
public final class LineShaderModel {

    private LineShaderModel() {
    }

    /**
     * Receives the line segments of the hairline strategy.
     */
    @FunctionalInterface
    public interface HairlineSink {
        /**
         * Receives one segment of a line strip.
         *
         * @param x0 the x coordinate of the start, relative to the origin of the batch
         * @param y0 the y coordinate of the start
         * @param z0 the z coordinate of the start
         * @param color0 the colour at the start as {@code 0xRRGGBBAA}
         * @param x1 the x coordinate of the end
         * @param y1 the y coordinate of the end
         * @param z1 the z coordinate of the end
         * @param color1 the colour at the end
         * @param draw the index of the draw
         */
        void line(float x0, float y0, float z0, int color0, float x1, float y1, float z1, int color1, int draw);
    }

    /**
     * Runs the vertex stage of a plan over its draws.
     *
     * @param plan the plan; its strategy must not be {@link LineStrategy#HAIRLINE}
     * @param data the segment records written by {@link LineRenderPlan#write}
     * @param styles the style table written by it
     * @param draws the draws written by it
     * @param viewProjection the matrix relative to the origin of the batch, column-major
     * @param viewWidth the width of the viewport in pixels
     * @param viewHeight the height of the viewport in pixels
     * @param worldToPixel pixels per world unit at {@code w = 1}
     * @param sink receives the triangles; must not be {@code null}
     * @throws IllegalArgumentException if the strategy is the hairline strategy
     */
    public static void evaluate(LineRenderPlan plan, MemorySegment data, MemorySegment styles, DrawList draws, float[] viewProjection, float viewWidth, float viewHeight,
                                float worldToPixel, LineGeometry.Sink sink) {
        LineStrategy strategy = plan.strategy();
        if (strategy == LineStrategy.HAIRLINE) {
            throw new IllegalArgumentException("the hairline strategy has no triangles: use evaluateHairlines");
        }
        LineGeometry.Emitter emitter = new LineGeometry.Emitter();
        float[] p0 = new float[3], p1 = new float[3], prev = new float[3], dash = new float[8], scalars = new float[2];
        for (int draw = 0; draw < draws.size(); draw++) {
            int instances = draws.instanceCount(draw);
            if (instances == 0) {
                continue;
            }
            long firstSegment;
            long segmentCount;
            if (strategy == LineStrategy.EXPANDED_MULTIDRAW) {
                firstSegment = draws.first(draw) / LineGeometry.VERTICES_PER_SEGMENT;
                segmentCount = draws.count(draw) / LineGeometry.VERTICES_PER_SEGMENT;
            } else {
                firstSegment = draws.baseInstance(draw);
                segmentCount = instances;
            }
            for (long s = firstSegment; s < firstSegment + segmentCount; s++) {
                long record = s * LineGpu.SEGMENT_BYTES;
                int packed = LineGpu.packed(data, record);
                int flags = packed >>> LineGpu.FLAGS_SHIFT;
                int styleIndex = strategy == LineStrategy.INDIRECT_DRAW_ID ? draw : packed & LineGpu.MAX_STYLES;
                int sf = LineGpu.readStyle(styles, styleIndex * LineGpu.STYLE_BYTES, dash, scalars);
                int dashCount = sf >>> 16;
                int styleFlags = sf & 0xFFFF;
                for (int k = 0; k < 3; k++) {
                    p0[k] = LineGpu.position(data, record, 0, k);
                    p1[k] = LineGpu.position(data, record, 1, k);
                    prev[k] = LineGpu.position(data, record, 2, k);
                }
                emitter.emitProjected(sink, viewProjection, viewWidth, viewHeight, worldToPixel, p0, p1, prev, flags, LineGpu.along(data, record, 0), LineGpu.along(data, record, 1),
                        scalars[0], ((styleFlags >> 4) & 1) == 1, styleFlags & 3, (styleFlags >> 2) & 3, scalars[1], dashCount > 0 ? dash : null, dashCount,
                        LineGpu.colorOf(styles, styleIndex * LineGpu.STYLE_BYTES));
            }
        }
    }

    /**
     * Reads the line strips of the hairline strategy back from its vertex buffer.
     *
     * @param plan the plan; its strategy must be {@link LineStrategy#HAIRLINE}
     * @param data the vertices written by {@link LineRenderPlan#write}
     * @param draws the draws written by it
     * @param sink receives every segment of every strip; must not be {@code null}
     * @throws IllegalArgumentException if the strategy is not the hairline strategy
     */
    public static void evaluateHairlines(LineRenderPlan plan, MemorySegment data, DrawList draws, HairlineSink sink) {
        if (plan.strategy() != LineStrategy.HAIRLINE) {
            throw new IllegalArgumentException("only the hairline strategy has line strips: " + plan.strategy());
        }
        for (int draw = 0; draw < draws.size(); draw++) {
            int first = draws.first(draw), count = draws.count(draw);
            for (int v = first; v + 1 < first + count; v++) {
                long a = v * LineGpu.HAIRLINE_VERTEX_BYTES, b = (v + 1) * LineGpu.HAIRLINE_VERTEX_BYTES;
                sink.line(GpuWriter.getFloat(data, a), GpuWriter.getFloat(data, a + 4), GpuWriter.getFloat(data, a + 8), GpuWriter.getInt(data, a + 12),
                        GpuWriter.getFloat(data, b), GpuWriter.getFloat(data, b + 4), GpuWriter.getFloat(data, b + 8), GpuWriter.getInt(data, b + 12), draw);
            }
        }
    }
}
