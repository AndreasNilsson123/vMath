package vmath.map;

import java.lang.foreign.MemorySegment;
import vmath.annotations.Experimental;
import vmath.gl.DrawList;

/**
 * The vertex stage of {@link SymbolRenderPlan} on the CPU: the same arithmetic as its shader, in
 * single precision, run over the buffer and the draws that {@link SymbolRenderPlan#write} produced.
 *
 * <p>It is the reference that tests and the GPU check compare the real shader against, and a
 * software path for tools that need the screen rectangle of a symbol (hit testing, declutter).
 * The triangles come out in window pixels with the origin at the bottom left of a clip space with y
 * up (the y is mirrored for {@code handedness = -1}, exactly as the shader does).
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * SymbolShaderModel.evaluate(plan, data, draws, viewProjection, 800f, 600f, SymbolRenderPlan.mapRotation(view), 1f / (float) view.mapUnitsPerPixel(), 1f,
 *         (symbol, x, y, u, v, color) -> System.out.println(symbol + " " + x[0] + " " + y[0]));
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class SymbolShaderModel {

    private static final float[] CORNER_X = {-0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f};
    private static final float[] CORNER_Y = {-0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f};

    private SymbolShaderModel() {
    }

    /** Receives the triangles of the symbols. */
    public interface Sink {
        /**
         * Receives one triangle.
         *
         * @param symbol the index of the symbol in the batch
         * @param x the window x of the three vertices in pixels
         * @param y the window y of the three vertices in pixels
         * @param u the texture u of the three vertices
         * @param v the texture v of the three vertices
         * @param color the colour {@code 0xRRGGBBAA} that multiplies the texture
         */
        void triangle(int symbol, float[] x, float[] y, float[] u, float[] v, int color);
    }

    /**
     * Runs the vertex stage.
     *
     * @param plan the plan; must not be {@code null}
     * @param data the records written by {@link SymbolRenderPlan#write}
     * @param draws the draws written by it
     * @param viewProjection the matrix, column-major, relative to the same origin as the buffer
     * @param viewWidth the width of the viewport in pixels
     * @param viewHeight the height of the viewport in pixels
     * @param mapRotation the {@value SymbolRenderPlan#U_MAP_ROTATION} uniform
     * @param pixelsPerUnit the {@value SymbolRenderPlan#U_PIXELS_PER_UNIT} uniform
     * @param handedness +1 for a clip space with y up, -1 for one with y down
     * @param sink receives the triangles of every visible symbol; must not be {@code null}
     */
    public static void evaluate(SymbolRenderPlan plan, MemorySegment data, DrawList draws, float[] viewProjection, float viewWidth, float viewHeight, float mapRotation,
                                float pixelsPerUnit, float handedness, Sink sink) {
        SymbolStrategy strategy = plan.strategy();
        float[] x = new float[3], y = new float[3], u = new float[3], v = new float[3];
        for (int draw = 0; draw < draws.size(); draw++) {
            int first;
            int symbols;
            if (strategy == SymbolStrategy.INSTANCED) {
                first = draws.baseInstance(draw);
                symbols = draws.instanceCount(draw);
            } else {
                first = draws.first(draw) / SymbolRenderPlan.VERTICES_PER_SYMBOL;
                symbols = draws.count(draw) / SymbolRenderPlan.VERTICES_PER_SYMBOL;
            }
            for (int s = first; s < first + symbols; s++) {
                long record = (strategy == SymbolStrategy.EXPANDED ? (long) s * SymbolRenderPlan.VERTICES_PER_SYMBOL : s) * SymbolGpu.BYTES;
                int flags = SymbolGpu.flags(data, record);
                float px = SymbolGpu.read(data, record, 0), py = SymbolGpu.read(data, record, 1);
                float cx = viewProjection[0] * px + viewProjection[4] * py + viewProjection[12];
                float cy = viewProjection[1] * px + viewProjection[5] * py + viewProjection[13];
                float cw = viewProjection[3] * px + viewProjection[7] * py + viewProjection[15];
                if ((flags & SymbolGpu.HIDDEN) != 0 || !(cw > 0f)) {
                    continue;
                }
                float size = SymbolGpu.read(data, record, 3);
                if ((flags & SymbolGpu.SIZE_IN_MAP_UNITS) != 0) {
                    size *= pixelsPerUnit;
                }
                float a = SymbolGpu.read(data, record, 2) + ((flags & SymbolGpu.ROTATE_WITH_MAP) != 0 ? mapRotation : 0f);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                float u0 = SymbolGpu.read(data, record, 4), v0 = SymbolGpu.read(data, record, 5), u1 = SymbolGpu.read(data, record, 6), v1 = SymbolGpu.read(data, record, 7);
                float ox = SymbolGpu.read(data, record, 8), oy = SymbolGpu.read(data, record, 9);
                float centerX = (cx / cw * 0.5f + 0.5f) * viewWidth, centerY = (cy / cw * 0.5f + 0.5f) * viewHeight;
                float[] vx = new float[6], vy = new float[6], vu = new float[6], vv = new float[6];
                for (int k = 0; k < 6; k++) {
                    float lx = CORNER_X[k] * size, ly = CORNER_Y[k] * size;
                    float dx = lx * ca - ly * sa + ox, dy = lx * sa + ly * ca + oy;
                    vx[k] = centerX + dx;
                    vy[k] = centerY + dy * handedness;
                    vu[k] = u0 + (u1 - u0) * (CORNER_X[k] + 0.5f);
                    vv[k] = v1 + (v0 - v1) * (CORNER_Y[k] + 0.5f);
                }
                for (int t = 0; t < 2; t++) {
                    for (int k = 0; k < 3; k++) {
                        x[k] = vx[3 * t + k];
                        y[k] = vy[3 * t + k];
                        u[k] = vu[3 * t + k];
                        v[k] = vv[3 * t + k];
                    }
                    sink.triangle(s, x, y, u, v, SymbolGpu.color(data, record));
                }
            }
        }
    }
}
