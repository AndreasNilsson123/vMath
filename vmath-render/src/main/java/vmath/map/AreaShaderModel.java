package vmath.map;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;
import vmath.gl.DrawList;
import vmath.gl.GpuWriter;

/**
 * The shaders of {@link AreaRenderPlan} on the CPU: the vertex stage over the buffers and draws
 * that {@link AreaRenderPlan#write} produced, and the fragment stage, {@link #colorAt}, for a pixel.
 *
 * <p>It is the reference that tests and the GPU check compare the real shaders against. Coordinates
 * are window pixels with the origin at the bottom left of a clip space with y up, the same as
 * {@code gl_FragCoord} (whose pixel centres are at {@code x + 0.5}).
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * AreaShaderModel.evaluate(plan, vertices, indices, styles, draws, viewProjection, 800f, 600f,
 *         (x, y, style) -> System.out.println(AreaShaderModel.colorAt(style, x[0], y[0], 0f, 0f)));
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class AreaShaderModel {

    private AreaShaderModel() {
    }

    /** Receives the triangles. */
    public interface Sink {
        /**
         * Receives one triangle.
         *
         * @param x the window x of the three vertices in pixels, valid during the call only
         * @param y the window y of the three vertices in pixels
         * @param style the style the fragments are painted with
         */
        void triangle(float[] x, float[] y, AreaStyle style);
    }

    /**
     * Runs the vertex stage.
     *
     * @param plan the plan; must not be {@code null}
     * @param vertices the vertices written by {@link AreaRenderPlan#write}
     * @param indices the indices written by it
     * @param styles the style table written by it; may be {@code null} for {@link AreaStrategy#VERTEX_COLOR}
     * @param draws the draws written by it
     * @param viewProjection the matrix, column-major, relative to the same origin as the buffer
     * @param viewWidth the width of the viewport in pixels
     * @param viewHeight the height of the viewport in pixels
     * @param sink receives the triangles; must not be {@code null}
     */
    public static void evaluate(AreaRenderPlan plan, MemorySegment vertices, MemorySegment indices, MemorySegment styles, DrawList draws, float[] viewProjection, float viewWidth,
                                float viewHeight, Sink sink) {
        float[] x = new float[3], y = new float[3];
        for (int d = 0; d < draws.size(); d++) {
            if (draws.instanceCount(d) == 0) {
                continue;
            }
            int first = draws.first(d), count = draws.count(d), base = draws.baseVertex(d);
            for (int t = 0; t + 2 < count; t += 3) {
                AreaStyle style = null;
                boolean visible = true;
                for (int k = 0; k < 3; k++) {
                    long vi = (long) GpuWriter.getInt(indices, 4L * (first + t + k)) + base;
                    long o = vi * AreaRenderPlan.VERTEX_BYTES;
                    float px = GpuWriter.getFloat(vertices, o), py = GpuWriter.getFloat(vertices, o + 4);
                    int word = GpuWriter.getInt(vertices, o + 8);
                    float cx = viewProjection[0] * px + viewProjection[4] * py + viewProjection[12];
                    float cy = viewProjection[1] * px + viewProjection[5] * py + viewProjection[13];
                    float cw = viewProjection[3] * px + viewProjection[7] * py + viewProjection[15];
                    if (!(cw > 0f)) {
                        visible = false;
                    }
                    x[k] = (cx / cw * 0.5f + 0.5f) * viewWidth;
                    y[k] = (cy / cw * 0.5f + 0.5f) * viewHeight;
                    if (k == 0) {
                        style = plan.strategy() == AreaStrategy.STYLE_TABLE ? AreaRenderPlan.readStyle(styles, (long) word * AreaRenderPlan.STYLE_BYTES) : AreaStyle.solid(word);
                    }
                }
                if (visible) {
                    sink.triangle(x, y, style);
                }
            }
        }
    }

    /**
     * Runs the fragment stage for one pixel.
     *
     * @param style the style; must not be {@code null}
     * @param fragX the window x of the pixel centre ({@code gl_FragCoord.x}), usually {@code column + 0.5}
     * @param fragY the window y of the pixel centre
     * @param offsetX the first value of the {@value AreaRenderPlan#U_PATTERN_OFFSET} uniform
     * @param offsetY the second value
     * @return the colour {@code 0xRRGGBBAA}: the pattern colour where the pattern is on, else the fill
     */
    public static int colorAt(AreaStyle style, float fragX, float fragY, float offsetX, float offsetY) {
        float px = fragX + offsetX, py = fragY + offsetY;
        boolean on = switch (style.pattern()) {
            case SOLID -> false;
            case HATCH -> lineOn(px, py, style.angle(), style.spacing(), style.width());
            case CROSSHATCH -> lineOn(px, py, style.angle(), style.spacing(), style.width()) || lineOn(px, py, style.angle() + 1.5707964f, style.spacing(), style.width());
            case DOTS -> {
                float c = (float) Math.cos(style.angle()), s = (float) Math.sin(style.angle());
                float qx = c * px + s * py, qy = -s * px + c * py;
                float cx = qx - style.spacing() * (float) Math.floor(qx / style.spacing()) - 0.5f * style.spacing();
                float cy = qy - style.spacing() * (float) Math.floor(qy / style.spacing()) - 0.5f * style.spacing();
                yield Math.sqrt(cx * cx + cy * cy) < 0.5f * style.width();
            }
        };
        return on ? style.patternColor() : style.fill();
    }

    private static boolean lineOn(float px, float py, float angle, float spacing, float width) {
        float t = px * -(float) Math.sin(angle) + py * (float) Math.cos(angle);
        float m = t - spacing * (float) Math.floor(t / spacing);
        return m < width;
    }
}
