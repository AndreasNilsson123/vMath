package vmath.map;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.function.IntUnaryOperator;
import vmath.annotations.Experimental;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GlslType;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;
import vmath.gl.GpuLayout;
import vmath.gl.GpuWriter;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.ShaderHeader;
import vmath.gl.StructArrayAccess;
import vmath.gl.StructLayout;
import vmath.gl.VertexBufferLayout;
import vmath.gl.VertexFormat;

/**
 * Everything an engine needs to draw an {@link AreaBatch} with one {@link AreaStrategy}: the
 * shaders, the layouts of the buffers, the filled buffers and the indexed draws.
 *
 * <p>Make a plan once per context with {@link #choose} or {@link #force}. Then, whenever the batch
 * changes or the view moves far:
 *
 * <ol>
 *   <li>make the buffers {@link #vertexBytes}, {@link #indexBytes} and {@link #styleBytes} large
 *       enough and call {@link #write}, which fills them and a {@link DrawList} of
 *       {@link DrawList.Kind#ELEMENTS ELEMENTS};
 *   <li>per frame set {@value #U_VIEW_PROJECTION} (the matrix of {@link MapView2d#viewProjection}
 *       for the same origin) and {@value #U_PATTERN_OFFSET}, bind the buffers as {@link #describe()}
 *       says, enable blending and submit the draw list the way {@link #submission} names, with
 *       depth writes and face culling off.
 * </ol>
 *
 * <p>The vertex is {@value #VERTEX_BYTES} bytes: the position relative to the origin as two floats
 * and one unsigned integer, the style index ({@link AreaStrategy#STYLE_TABLE}) or the colour
 * ({@link AreaStrategy#VERTEX_COLOR}); the indices are 32 bits and absolute (the draws have base
 * vertex 0). The patterns are evaluated per fragment in window pixels from {@code gl_FragCoord},
 * with {@link AreaShaderModel} as the CPU reference; the GPU check in the samples compares the pixels
 * a real context draws with the model.
 *
 * <p><b>Thread safety.</b> Immutable once made; {@link #write} reads the batch and writes the
 * buffers it is given, so use one writer per batch.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * AreaRenderPlan plan = AreaRenderPlan.choose(caps);
 * MemorySegment vertices = arena.allocate(Math.max(1, plan.vertexBytes(batch)));
 * MemorySegment indices = arena.allocate(Math.max(1, plan.indexBytes(batch)));
 * MemorySegment styles = arena.allocate(Math.max(1, plan.styleBytes(batch)));
 * DrawList draws = new DrawList(DrawList.Kind.ELEMENTS, 16);
 * plan.write(batch, view.centerX(), view.centerY(), vertices, indices, styles, draws);
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class AreaRenderPlan {

    /** The name of the view-projection uniform ({@code mat4}, relative to the origin of the buffer). */
    public static final String U_VIEW_PROJECTION = "u_viewProjection";
    /** The name of the uniform ({@code vec2}) added to the window position before the pattern is evaluated, in pixels. */
    public static final String U_PATTERN_OFFSET = "u_patternOffset";
    /** The size of a vertex in bytes. */
    public static final int VERTEX_BYTES = 12;
    /** The binding or texture unit of the style table. */
    public static final int STYLE_SLOT = 0;
    /** The length of the style table if it is a uniform block, the only mode with a fixed size. */
    public static final int STYLE_TABLE_UNIFORM_LENGTH = 256;

    /** The element type of the style table. */
    public static final Struct STYLE = new Struct("AreaStyle", List.of(
            new Member("fill", GlslType.VEC4), new Member("pattern", GlslType.VEC4),
            new Member("spacing", GlslType.FLOAT), new Member("width", GlslType.FLOAT), new Member("angle", GlslType.FLOAT), new Member("kind", GlslType.UINT)));
    /** The layout of a style record. */
    public static final StructLayout STYLE_LAYOUT = STYLE.layout(GpuLayout.STD430);
    /** The size of a style record in bytes. */
    public static final long STYLE_BYTES = STYLE_LAYOUT.size();

    private static final long O_FILL = STYLE_LAYOUT.offsetOf("fill");
    private static final long O_PATTERN = STYLE_LAYOUT.offsetOf("pattern");
    private static final long O_SPACING = STYLE_LAYOUT.offsetOf("spacing");
    private static final long O_WIDTH = STYLE_LAYOUT.offsetOf("width");
    private static final long O_ANGLE = STYLE_LAYOUT.offsetOf("angle");
    private static final long O_KIND = STYLE_LAYOUT.offsetOf("kind");

    private final AreaStrategy strategy;
    private final GraphicsCapabilities caps;
    private final StructArrayAccess styles;
    private final VertexBufferLayout vertexLayout;
    private final String vertexShader;
    private final String fragmentShader;

    private AreaRenderPlan(AreaStrategy strategy, GraphicsCapabilities caps, StructArrayAccess styles, VertexBufferLayout vertexLayout, String vertexShader, String fragmentShader) {
        this.strategy = strategy;
        this.caps = caps;
        this.styles = styles;
        this.vertexLayout = vertexLayout;
        this.vertexShader = vertexShader;
        this.fragmentShader = fragmentShader;
    }

    /**
     * Makes the plan of the best strategy that the capabilities allow.
     *
     * @param caps the capabilities of the context; must not be {@code null}
     * @return the plan
     */
    public static AreaRenderPlan choose(GraphicsCapabilities caps) {
        return of(AreaStrategy.choose(caps), caps);
    }

    /**
     * Makes the plan of a named strategy.
     *
     * @param wanted the strategy; must not be {@code null}
     * @param caps the capabilities of the context; must not be {@code null}
     * @return the plan
     * @throws UnsupportedOperationException if the capabilities do not allow it; the message says what is missing
     */
    public static AreaRenderPlan force(AreaStrategy wanted, GraphicsCapabilities caps) {
        return of(AreaStrategy.force(wanted, caps), caps);
    }

    private static AreaRenderPlan of(AreaStrategy s, GraphicsCapabilities caps) {
        VertexBufferLayout layout = VertexBufferLayout.builder().attributeAt("a_position", 0, VertexFormat.FLOAT32X2, 0).attributeAt("a_word", 1, VertexFormat.UINT32, 8).build(VERTEX_BYTES);
        String version = caps.glsl().versionLine() + "\n";
        if (s == AreaStrategy.VERTEX_COLOR) {
            String vs = version + layout.glslInputs() + """
                    uniform mat4 u_viewProjection;
                    flat out vec4 v_fill;
                    flat out vec4 v_pattern;
                    flat out vec4 v_params;
                    flat out uint v_kind;

                    void main() {
                        gl_Position = u_viewProjection * vec4(a_position, 0.0, 1.0);
                        v_fill = vec4(float(a_word >> 24u), float((a_word >> 16u) & 255u), float((a_word >> 8u) & 255u), float(a_word & 255u)) / 255.0;
                        v_pattern = vec4(0.0);
                        v_params = vec4(0.0);
                        v_kind = 0u;
                    }
                    """;
            return new AreaRenderPlan(s, caps, null, layout, vs, version + FRAGMENT);
        }
        StructArrayAccess.Mode mode = StructArrayAccess.choose(caps, StructArrayAccess.Need.RANDOM_ACCESS);
        StructArrayAccess access = StructArrayAccess.of(STYLE, mode, "styles", STYLE_TABLE_UNIFORM_LENGTH, STYLE_SLOT, caps);
        String header = ShaderHeader.builder("VMATH_AREAS").access(access).build(ShaderHeader.Language.GLSL);
        String vs = version + header + layout.glslInputs() + """
                uniform mat4 u_viewProjection;
                flat out vec4 v_fill;
                flat out vec4 v_pattern;
                flat out vec4 v_params;
                flat out uint v_kind;

                void main() {
                    AreaStyle s = fetch_styles(int(a_word));
                    gl_Position = u_viewProjection * vec4(a_position, 0.0, 1.0);
                    v_fill = s.fill;
                    v_pattern = s.pattern;
                    v_params = vec4(s.spacing, s.width, s.angle, 0.0);
                    v_kind = s.kind;
                }
                """;
        return new AreaRenderPlan(s, caps, access, layout, vs, version + FRAGMENT);
    }

    private static final String FRAGMENT = """
            flat in vec4 v_fill;
            flat in vec4 v_pattern;
            flat in vec4 v_params;
            flat in uint v_kind;
            uniform vec2 u_patternOffset;
            out vec4 o_color;

            float lineOn(vec2 p, float angle, float spacing, float width) {
                float t = dot(p, vec2(-sin(angle), cos(angle)));
                float m = t - spacing * floor(t / spacing);
                return m < width ? 1.0 : 0.0;
            }

            void main() {
                float on = 0.0;
                if (v_kind != 0u) {
                    vec2 p = gl_FragCoord.xy + u_patternOffset;
                    float spacing = v_params.x;
                    float width = v_params.y;
                    float angle = v_params.z;
                    if (v_kind == 1u) {
                        on = lineOn(p, angle, spacing, width);
                    } else if (v_kind == 2u) {
                        on = max(lineOn(p, angle, spacing, width), lineOn(p, angle + 1.5707963267948966, spacing, width));
                    } else {
                        float c = cos(angle);
                        float s = sin(angle);
                        vec2 q = vec2(c * p.x + s * p.y, -s * p.x + c * p.y);
                        vec2 cell = q - spacing * floor(q / spacing) - 0.5 * spacing;
                        on = length(cell) < 0.5 * width ? 1.0 : 0.0;
                    }
                }
                o_color = on > 0.5 ? v_pattern : v_fill;
            }
            """;

    /**
     * Gives the offset that fixes the patterns to the map instead of the window.
     *
     * <p>The patterns are evaluated at {@code gl_FragCoord + offset}; with the offset
     * {@code -(window position of an anchor)} the pattern is the same relative to the anchor at
     * any pan. (They do not turn with a rotating map.)
     *
     * @param view the view; must not be {@code null}
     * @param anchorX the projected x of the anchor in metres
     * @param anchorY the projected y of the anchor in metres
     * @param out receives the two values of the uniform at {@code out[0..1]}
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void patternOffset(MapView2d view, double anchorX, double anchorY, float[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have room for 2 values");
        }
        double[] p = new double[2];
        view.projectedToScreen(anchorX, anchorY, p);
        out[0] = (float) -p[0];
        out[1] = (float) -(view.height() - p[1]);             // gl_FragCoord counts from the bottom
    }

    /**
     * Names the strategy of the plan.
     *
     * @return the strategy
     */
    public AreaStrategy strategy() {
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
     * Describes the vertices.
     *
     * @return the layout: {@code a_position} (2 floats) at location 0 and {@code a_word} (an unsigned integer) at location 1
     */
    public VertexBufferLayout vertexLayout() {
        return vertexLayout;
    }

    /**
     * Describes how the shader reads the style table.
     *
     * @return the access to the style records, or {@code null} for {@link AreaStrategy#VERTEX_COLOR}
     */
    public StructArrayAccess styleAccess() {
        return styles;
    }

    /**
     * Gives the size of the vertex buffer for a batch.
     *
     * @param batch the batch; must not be {@code null}
     * @return the bytes
     */
    public long vertexBytes(AreaBatch batch) {
        return (long) batch.vertexCount() * VERTEX_BYTES;
    }

    /**
     * Gives the size of the index buffer for a batch.
     *
     * @param batch the batch; must not be {@code null}
     * @return the bytes
     */
    public long indexBytes(AreaBatch batch) {
        return 3L * batch.triangleCount() * 4;
    }

    /**
     * Gives the size of the style buffer for a batch.
     *
     * @param batch the batch; must not be {@code null}
     * @return the bytes of the style table, 0 for {@link AreaStrategy#VERTEX_COLOR}
     */
    public long styleBytes(AreaBatch batch) {
        return strategy == AreaStrategy.VERTEX_COLOR ? 0 : batch.styleCount() * STYLE_BYTES;
    }

    /**
     * Fills the buffers and the draw list for a batch.
     *
     * <p>Polygons are written in the order they were added; consecutive polygons of equal style are
     * one draw. The {@code user} value of every draw is the style index.
     *
     * @param batch the batch; must not be {@code null}
     * @param originX the projected x of the origin in metres
     * @param originY the projected y of the origin in metres
     * @param vertices receives the vertices; at least {@link #vertexBytes} long
     * @param indices receives the 32-bit indices; at least {@link #indexBytes} long
     * @param styleBuffer receives the style table; at least {@link #styleBytes} long; may be {@code null} for {@link AreaStrategy#VERTEX_COLOR}
     * @param draws receives the draws, after being cleared; must be of the kind {@link DrawList.Kind#ELEMENTS}
     * @return the number of draws
     * @throws IllegalArgumentException if a buffer is too small, the draw list has the wrong kind, or a uniform block cannot hold the styles
     */
    public int write(AreaBatch batch, double originX, double originY, MemorySegment vertices, MemorySegment indices, MemorySegment styleBuffer, DrawList draws) {
        return write(batch, originX, originY, vertices, indices, styleBuffer, draws, null);
    }

    /**
     * Fills the buffers and the draw list for a batch, with every colour passed through a colour map:
     * a display palette applied where the colours are written.
     *
     * <p>Switching the palette later is {@link #rewriteStyles} for {@link AreaStrategy#STYLE_TABLE}
     * (the small style buffer only); {@link AreaStrategy#VERTEX_COLOR} keeps the colour in the
     * vertices and needs this method again.
     *
     * @param batch the batch; must not be {@code null}
     * @param originX the projected x of the origin in metres
     * @param originY the projected y of the origin in metres
     * @param vertices receives the vertices; at least {@link #vertexBytes} long
     * @param indices receives the 32-bit indices; at least {@link #indexBytes} long
     * @param styleBuffer receives the style table; at least {@link #styleBytes} long; may be {@code null} for {@link AreaStrategy#VERTEX_COLOR}
     * @param draws receives the draws, after being cleared; must be of the kind {@link DrawList.Kind#ELEMENTS}
     * @param colorMap maps a colour {@code 0xRRGGBBAA} to the colour to show; may be {@code null} for none
     * @return the number of draws
     * @throws IllegalArgumentException as for the method without a colour map
     */
    public int write(AreaBatch batch, double originX, double originY, MemorySegment vertices, MemorySegment indices, MemorySegment styleBuffer, DrawList draws,
                     IntUnaryOperator colorMap) {
        if (draws.kind() != DrawList.Kind.ELEMENTS) {
            throw new IllegalArgumentException("the draw list must hold ELEMENTS draws");
        }
        if (vertices.byteSize() < vertexBytes(batch) || indices.byteSize() < indexBytes(batch)) {
            throw new IllegalArgumentException("the vertex buffer needs " + vertexBytes(batch) + " bytes and the index buffer " + indexBytes(batch));
        }
        if (strategy == AreaStrategy.STYLE_TABLE) {
            if (styleBuffer == null || styleBuffer.byteSize() < styleBytes(batch)) {
                throw new IllegalArgumentException("the style buffer needs " + styleBytes(batch) + " bytes");
            }
            if (styles.mode() == StructArrayAccess.Mode.UNIFORM_BLOCK && batch.styleCount() > STYLE_TABLE_UNIFORM_LENGTH) {
                throw new IllegalArgumentException("a uniform block holds " + STYLE_TABLE_UNIFORM_LENGTH + " styles: use a context with storage buffers or texture buffers");
            }
            for (int i = 0; i < batch.styleCount(); i++) {
                writeStyle(styleBuffer, i * STYLE_BYTES, mapped(batch.styleAt(i), colorMap));
            }
        }
        draws.clear();
        int runStyle = -1;
        int runFirst = 0, runCount = 0;
        for (int p = 0; p < batch.polygonCount(); p++) {
            int style = batch.styleOf(p);
            int word = strategy == AreaStrategy.STYLE_TABLE ? style : colorMap == null ? batch.styleAt(style).fill() : colorMap.applyAsInt(batch.styleAt(style).fill());
            int first = batch.firstVertex(p);
            for (int v = 0; v < batch.vertexCountOf(p); v++) {
                long o = (long) (first + v) * VERTEX_BYTES;
                GpuWriter.putFloat(vertices, o, (float) (batch.x(first + v) - originX));
                GpuWriter.putFloat(vertices, o + 4, (float) (batch.y(first + v) - originY));
                GpuWriter.putInt(vertices, o + 8, word);
            }
            int fi = batch.firstIndex(p), ic = batch.indexCountOf(p);
            for (int k = 0; k < ic; k++) {
                GpuWriter.putInt(indices, 4L * (fi + k), batch.index(fi + k));
            }
            boolean mergeable = runCount > 0 && (strategy == AreaStrategy.STYLE_TABLE ? style == runStyle : batch.styleAt(style).fill() == batch.styleAt(runStyle).fill());
            if (runCount > 0 && !mergeable) {
                draws.addElements(runCount, runFirst, 0, 1, 0, runStyle);
                runCount = 0;
            }
            if (runCount == 0) {
                runStyle = style;
                runFirst = fi;
            }
            runCount += ic;
        }
        if (runCount > 0) {
            draws.addElements(runCount, runFirst, 0, 1, 0, runStyle);
        }
        return draws.size();
    }

    private static AreaStyle mapped(AreaStyle style, IntUnaryOperator colorMap) {
        return colorMap == null ? style : new AreaStyle(colorMap.applyAsInt(style.fill()), style.pattern(), colorMap.applyAsInt(style.patternColor()), style.spacing(), style.width(), style.angle());
    }

    /**
     * Rewrites the style table with the colours passed through a colour map: a palette switch is this
     * call and one upload of the small style buffer.
     *
     * @param batch the batch the buffers were written for; must not be {@code null}
     * @param styleBuffer receives the style table; at least {@link #styleBytes} long
     * @param colorMap maps a colour {@code 0xRRGGBBAA} to the colour to show; may be {@code null} for none
     * @throws IllegalStateException if the strategy is {@link AreaStrategy#VERTEX_COLOR}, whose colours are in the vertices
     * @throws IllegalArgumentException if the buffer is too small
     */
    public void rewriteStyles(AreaBatch batch, MemorySegment styleBuffer, IntUnaryOperator colorMap) {
        if (strategy == AreaStrategy.VERTEX_COLOR) {
            throw new IllegalStateException("the vertex colour strategy keeps the colours in its vertices: write the batch again");
        }
        if (styleBuffer.byteSize() < styleBytes(batch)) {
            throw new IllegalArgumentException("the style buffer needs " + styleBytes(batch) + " bytes");
        }
        for (int i = 0; i < batch.styleCount(); i++) {
            writeStyle(styleBuffer, i * STYLE_BYTES, mapped(batch.styleAt(i), colorMap));
        }
    }

    /**
     * Writes a style record.
     *
     * @param dst the buffer; must not be {@code null}
     * @param offset the byte offset of the record
     * @param style the style; must not be {@code null}
     */
    public static void writeStyle(MemorySegment dst, long offset, AreaStyle style) {
        colour(dst, offset + O_FILL, style.fill());
        colour(dst, offset + O_PATTERN, style.patternColor());
        GpuWriter.putFloat(dst, offset + O_SPACING, style.spacing());
        GpuWriter.putFloat(dst, offset + O_WIDTH, style.width());
        GpuWriter.putFloat(dst, offset + O_ANGLE, style.angle());
        GpuWriter.putInt(dst, offset + O_KIND, style.pattern().ordinal());
    }

    /**
     * Reads a style record back, for the CPU model of the shaders.
     *
     * @param src the buffer
     * @param offset the byte offset of the record
     * @return the style (colours are rounded to 8 bits)
     */
    public static AreaStyle readStyle(MemorySegment src, long offset) {
        int kind = GpuWriter.getInt(src, offset + O_KIND);
        return new AreaStyle(unpack(src, offset + O_FILL), AreaStyle.Pattern.values()[kind], unpack(src, offset + O_PATTERN), GpuWriter.getFloat(src, offset + O_SPACING),
                GpuWriter.getFloat(src, offset + O_WIDTH), GpuWriter.getFloat(src, offset + O_ANGLE));
    }

    private static void colour(MemorySegment dst, long at, int rgba) {
        for (int k = 0; k < 4; k++) {
            GpuWriter.putFloat(dst, at + 4L * k, ((rgba >>> (24 - 8 * k)) & 255) / 255f);
        }
    }

    private static int unpack(MemorySegment src, long at) {
        int r = 0;
        for (int k = 0; k < 4; k++) {
            r |= Math.round(GpuWriter.getFloat(src, at + 4L * k) * 255f) << (24 - 8 * k);
        }
        return r;
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
        sb.append("primitive: GL_TRIANGLES from 32-bit indices, base vertex 0, no face culling, no depth writes, alpha blending (straight alpha)\n");
        sb.append("vertices: ").append(VERTEX_BYTES).append(" bytes: a_position at location 0 (2 floats), a_word at location 1 (integer attribute: ")
                .append(strategy == AreaStrategy.STYLE_TABLE ? "the style index" : "the colour 0xRRGGBBAA").append(")\n");
        sb.append("uniforms: ").append(U_VIEW_PROJECTION).append(", ").append(U_PATTERN_OFFSET).append('\n');
        if (styles != null) {
            sb.append("styles: ").append(styles.mode()).append(", ").append(styles.elementStride()).append(" bytes per style, slot ").append(STYLE_SLOT).append('\n');
        }
        sb.append("draws: one per run of polygons of equal ").append(strategy == AreaStrategy.STYLE_TABLE ? "style" : "colour").append('\n');
        return sb.toString();
    }
}
