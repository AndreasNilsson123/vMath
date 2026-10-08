package vmath.map;

import java.lang.foreign.MemorySegment;
import java.util.function.IntUnaryOperator;
import vmath.annotations.Experimental;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.ShaderHeader;
import vmath.gl.StructArrayAccess;

/**
 * Everything an engine needs to draw a {@link SymbolBatch} with one {@link SymbolStrategy}, without
 * knowing the strategies: the shaders, the layout of the buffer, the filled buffer and the draw.
 *
 * <p>Make a plan once per context with {@link #choose} or {@link #force}. Then, whenever the
 * batch changes or the view moves (the buffer is relative to an origin, so a pan beyond what a
 * float resolves needs a new one):
 *
 * <ol>
 *   <li>make a buffer {@link #dataBytes} long and call {@link #write}, which fills it and a
 *       {@link DrawList};
 *   <li>per frame set the uniforms ({@value #U_VIEW_PROJECTION}, {@value #U_VIEWPORT},
 *       {@value #U_MAP_ROTATION}, {@value #U_PIXELS_PER_UNIT}, {@value #U_HANDEDNESS}) and the
 *       atlas on {@value #U_ATLAS}, bind the buffer as {@link #describe()} says, enable blending
 *       and submit the draw list with {@link #submission}.
 * </ol>
 *
 * <p>The view-projection is {@link MapView2d#viewProjection} for the same origin. The shader works
 * in screen pixels, so the symbols keep their size however the map is scaled and rotated, and a
 * symbol with {@link SymbolGpu#ROTATE_WITH_MAP} turns with the map by {@link #mapRotation}.
 *
 * <p>The shaders are for OpenGL (loose uniforms); the CPU model {@link SymbolShaderModel} follows
 * the same arithmetic, and the GPU check in the samples compares the pixels that a real context
 * draws with the model for every strategy the context allows.
 *
 * <p><b>Thread safety.</b> Immutable once made; {@link #write} reads the batch and writes the
 * buffer it is given, so use one writer per batch.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * SymbolRenderPlan plan = SymbolRenderPlan.choose(caps);
 * MemorySegment data = arena.allocate(Math.max(1, plan.dataBytes(batch)));
 * DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1);
 * plan.write(batch, view.centerX(), view.centerY(), data, draws);
 * float rotation = SymbolRenderPlan.mapRotation(view);
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class SymbolRenderPlan {

    /** The name of the view-projection uniform ({@code mat4}, relative to the origin of the buffer). */
    public static final String U_VIEW_PROJECTION = "u_viewProjection";
    /** The name of the viewport uniform ({@code vec2}, pixels). */
    public static final String U_VIEWPORT = "u_viewport";
    /** The name of the map rotation uniform ({@code float}, radians, see {@link #mapRotation}). */
    public static final String U_MAP_ROTATION = "u_mapRotation";
    /** The name of the uniform with the pixels per projected metre ({@code float}). */
    public static final String U_PIXELS_PER_UNIT = "u_pixelsPerUnit";
    /** The name of the uniform that is +1 for a clip space with y up and -1 for one with y down ({@code float}). */
    public static final String U_HANDEDNESS = "u_handedness";
    /** The name of the atlas sampler ({@code sampler2D}). */
    public static final String U_ATLAS = "u_atlas";

    /** The first vertex attribute location of the attribute strategies. */
    public static final int SYMBOL_LOCATION = 0;
    /** The texture unit of the texture buffer of {@link SymbolStrategy#TEXTURE_FETCH}. */
    public static final int SYMBOL_SLOT = 0;
    /** The vertices of a symbol: two triangles. */
    public static final int VERTICES_PER_SYMBOL = 6;

    private final SymbolStrategy strategy;
    private final GraphicsCapabilities caps;
    private final StructArrayAccess symbols;
    private final String vertexShader;
    private final String fragmentShader;

    private SymbolRenderPlan(SymbolStrategy strategy, GraphicsCapabilities caps, StructArrayAccess symbols, String vertexShader, String fragmentShader) {
        this.strategy = strategy;
        this.caps = caps;
        this.symbols = symbols;
        this.vertexShader = vertexShader;
        this.fragmentShader = fragmentShader;
    }

    /**
     * Makes the plan of the best strategy that the capabilities allow.
     *
     * @param caps the capabilities of the context; must not be {@code null}
     * @return the plan
     */
    public static SymbolRenderPlan choose(GraphicsCapabilities caps) {
        return of(SymbolStrategy.choose(caps), caps);
    }

    /**
     * Makes the plan of a named strategy.
     *
     * @param wanted the strategy; must not be {@code null}
     * @param caps the capabilities of the context; must not be {@code null}
     * @return the plan
     * @throws UnsupportedOperationException if the capabilities do not allow it; the message says what is missing
     */
    public static SymbolRenderPlan force(SymbolStrategy wanted, GraphicsCapabilities caps) {
        return of(SymbolStrategy.force(wanted, caps), caps);
    }

    private static SymbolRenderPlan of(SymbolStrategy s, GraphicsCapabilities caps) {
        StructArrayAccess access = s == SymbolStrategy.TEXTURE_FETCH
                ? StructArrayAccess.of(SymbolGpu.SYMBOL, StructArrayAccess.Mode.TEXTURE_BUFFER, "symbols", 0, SYMBOL_SLOT, caps)
                : StructArrayAccess.of(SymbolGpu.SYMBOL, StructArrayAccess.Mode.VERTEX_ATTRIBUTE, "symbols", 0, SYMBOL_LOCATION, caps);
        String header = ShaderHeader.builder("VMATH_SYMBOLS").constant("ROTATE_WITH_MAP", (long) SymbolGpu.ROTATE_WITH_MAP)
                .constant("SIZE_IN_MAP_UNITS", (long) SymbolGpu.SIZE_IN_MAP_UNITS).constant("HIDDEN", (long) SymbolGpu.HIDDEN)
                .access(access).build(ShaderHeader.Language.GLSL);
        String index = s == SymbolStrategy.TEXTURE_FETCH ? "gl_VertexID / 6" : "0";
        String vs = caps.glsl().versionLine() + "\n" + header + VERTEX_BODY.replace("@SYMBOL_INDEX@", index);
        String fs = caps.glsl().versionLine() + "\n" + FRAGMENT;
        return new SymbolRenderPlan(s, caps, access, vs, fs);
    }

    private static final String VERTEX_BODY = """

            uniform mat4 u_viewProjection;
            uniform vec2 u_viewport;
            uniform float u_mapRotation;
            uniform float u_pixelsPerUnit;
            uniform float u_handedness;

            out vec4 v_color;
            out vec2 v_uv;

            const vec2 CORNERS[6] = vec2[6](vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(-0.5, 0.5), vec2(-0.5, 0.5), vec2(0.5, -0.5), vec2(0.5, 0.5));

            void main() {
                MapSymbol s = fetch_symbols(@SYMBOL_INDEX@);
                vec4 c = u_viewProjection * vec4(s.position, 0.0, 1.0);
                if ((s.flags & HIDDEN) != 0u || !(c.w > 0.0)) {
                    gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
                    v_color = vec4(0.0);
                    v_uv = vec2(0.0);
                    return;
                }
                int corner = gl_VertexID % 6;
                vec2 q = CORNERS[corner];
                float size = (s.flags & SIZE_IN_MAP_UNITS) != 0u ? s.size * u_pixelsPerUnit : s.size;
                float a = s.angle + ((s.flags & ROTATE_WITH_MAP) != 0u ? u_mapRotation : 0.0);
                float ca = cos(a);
                float sa = sin(a);
                vec2 local = q * size;
                vec2 d = vec2(local.x * ca - local.y * sa, local.x * sa + local.y * ca) + s.offsetPixels;
                vec2 center = (c.xy / c.w * 0.5 + 0.5) * u_viewport;
                vec2 pos = center + vec2(d.x, d.y * u_handedness);
                gl_Position = vec4(pos / u_viewport * 2.0 - 1.0, c.z / c.w, 1.0);
                v_color = vec4(float(s.color >> 24u), float((s.color >> 16u) & 255u), float((s.color >> 8u) & 255u), float(s.color & 255u)) / 255.0;
                v_uv = vec2(mix(s.uv.x, s.uv.z, q.x + 0.5), mix(s.uv.w, s.uv.y, q.y + 0.5));
            }
            """;

    private static final String FRAGMENT = """
            in vec4 v_color;
            in vec2 v_uv;
            uniform sampler2D u_atlas;
            out vec4 o_color;

            void main() {
                o_color = texture(u_atlas, v_uv) * v_color;
            }
            """;

    /**
     * Gives the rotation of the map that the {@value #U_MAP_ROTATION} uniform takes.
     *
     * @param view the view; must not be {@code null}
     * @return radians, {@code gridUp}: the counter-clockwise turn from the map frame (east right, north up) to
     *     the window, which is what a sprite whose top points to grid north needs
     */
    public static float mapRotation(MapView2d view) {
        return (float) view.gridUp();
    }

    /**
     * Names the strategy of the plan.
     *
     * @return the strategy
     */
    public SymbolStrategy strategy() {
        return strategy;
    }

    /**
     * Names the capabilities the plan was made for.
     *
     * @return the capabilities
     */
    public GraphicsCapabilities capabilities() {
        return caps;
    }

    /**
     * Gives the vertex shader.
     *
     * @return the GLSL source, starting with its {@code #version} line
     */
    public String vertexShader() {
        return vertexShader;
    }

    /**
     * Gives the fragment shader.
     *
     * @return the GLSL source, starting with its {@code #version} line
     */
    public String fragmentShader() {
        return fragmentShader;
    }

    /**
     * Describes how the records reach the shader.
     *
     * @return the access to the symbol records
     */
    public StructArrayAccess symbolAccess() {
        return symbols;
    }

    /**
     * Gives the size of the buffer for a batch.
     *
     * @param batch the batch; must not be {@code null}
     * @return the bytes: one record per symbol, or six for {@link SymbolStrategy#EXPANDED}
     */
    public long dataBytes(SymbolBatch batch) {
        return batch.count() * SymbolGpu.BYTES * (strategy == SymbolStrategy.EXPANDED ? VERTICES_PER_SYMBOL : 1);
    }

    /**
     * Fills the buffer and the draw list for a batch.
     *
     * <p>The positions are written relative to {@code (originX, originY)} as floats, which is what
     * keeps a map at the scale of a country exact to the pixel: choose the origin near the centre
     * of the view. Symbols are drawn in the order they were added. The draw list gets one draw
     * (none for an empty batch) whose {@code user} value is 0.
     *
     * @param batch the batch; must not be {@code null}
     * @param originX the projected x of the origin in metres
     * @param originY the projected y of the origin in metres
     * @param data receives the records; at least {@link #dataBytes} long
     * @param draws receives the draw, after being cleared; must be of the kind {@link DrawList.Kind#ARRAYS}
     * @return the number of draws, 0 or 1
     * @throws IllegalArgumentException if the buffer is too small or the draw list has the wrong kind
     */
    public int write(SymbolBatch batch, double originX, double originY, MemorySegment data, DrawList draws) {
        return write(batch, originX, originY, data, draws, null);
    }

    /**
     * Fills the buffer and the draw list for a batch, with every colour passed through a colour map:
     * a display palette applied where the colours are written (they are in the records, so a palette
     * switch writes the buffer again; the sprites of the atlas are mapped with {@link SymbolAtlas#mapped}).
     *
     * @param batch the batch; must not be {@code null}
     * @param originX the projected x of the origin in metres
     * @param originY the projected y of the origin in metres
     * @param data receives the records; at least {@link #dataBytes} long
     * @param draws receives the draw, after being cleared; must be of the kind {@link DrawList.Kind#ARRAYS}
     * @param colorMap maps a colour {@code 0xRRGGBBAA} to the colour to show; may be {@code null} for none
     * @return the number of draws, 0 or 1
     * @throws IllegalArgumentException as for the method without a colour map
     */
    public int write(SymbolBatch batch, double originX, double originY, MemorySegment data, DrawList draws, IntUnaryOperator colorMap) {
        if (draws.kind() != DrawList.Kind.ARRAYS) {
            throw new IllegalArgumentException("the draw list must hold ARRAYS draws");
        }
        if (data.byteSize() < dataBytes(batch)) {
            throw new IllegalArgumentException("the buffer has " + data.byteSize() + " bytes, " + dataBytes(batch) + " are needed");
        }
        draws.clear();
        int n = batch.count();
        if (n == 0) {
            return 0;
        }
        float[] uv = new float[4];
        int copies = strategy == SymbolStrategy.EXPANDED ? VERTICES_PER_SYMBOL : 1;
        long at = 0;
        for (int i = 0; i < n; i++) {
            batch.uv(i, uv);
            for (int c = 0; c < copies; c++) {
                SymbolGpu.write(data, at, (float) (batch.x(i) - originX), (float) (batch.y(i) - originY), batch.angle(i), batch.size(i), uv, colorMap == null ? batch.color(i) : colorMap.applyAsInt(batch.color(i)), batch.flags(i),
                        batch.offset(i, 0), batch.offset(i, 1));
                at += SymbolGpu.BYTES;
            }
        }
        if (strategy == SymbolStrategy.INSTANCED) {
            draws.addArrays(VERTICES_PER_SYMBOL, 0, n, 0, 0);
        } else {
            draws.addArrays(VERTICES_PER_SYMBOL * n, 0, 1, 0, 0);
        }
        return 1;
    }

    /**
     * Chooses how to submit the draws of a written batch.
     *
     * @param draws the list filled by {@link #write}; must not be {@code null}
     * @return the best way the capabilities of the plan allow for this list
     */
    public DrawSubmission submission(DrawList draws) {
        return DrawSubmission.choose(caps, draws);
    }

    /**
     * Describes in words how to bind and issue what the plan has written.
     *
     * @return a few lines of text
     */
    public String describe() {
        StringBuilder sb = new StringBuilder("strategy ").append(strategy).append(" on ").append(caps).append('\n');
        sb.append("primitive: GL_TRIANGLES, 6 vertices per symbol, no face culling, no depth writes, alpha blending (straight alpha)\n");
        sb.append("uniforms: ").append(U_VIEW_PROJECTION).append(", ").append(U_VIEWPORT).append(", ").append(U_MAP_ROTATION).append(", ").append(U_PIXELS_PER_UNIT).append(", ")
                .append(U_HANDEDNESS).append("; sampler ").append(U_ATLAS).append(" on a unit of your choice\n");
        sb.append("symbols: ").append(symbols.mode()).append(", ").append(symbols.elementStride()).append(" bytes per record");
        switch (strategy) {
            case INSTANCED -> sb.append(", attributes from location ").append(SYMBOL_LOCATION).append(", divisor 1; one instanced draw of 6 vertices\n");
            case TEXTURE_FETCH -> sb.append(", texture buffer (RGBA32UI) on unit ").append(SYMBOL_SLOT).append("; one draw of 6 vertices per symbol\n");
            case EXPANDED -> sb.append(", attributes from location ").append(SYMBOL_LOCATION).append(", divisor 0; the record is repeated 6 times per symbol; one draw\n");
        }
        return sb.toString();
    }
}
