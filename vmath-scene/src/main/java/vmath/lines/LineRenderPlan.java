package vmath.lines;

import java.lang.foreign.MemorySegment;
import java.util.function.IntUnaryOperator;
import vmath.annotations.Experimental;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GlslVersion;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.ShaderHeader;
import vmath.gl.StructArrayAccess;
import vmath.gl.VertexBufferLayout;
import vmath.gl.VertexFormat;

/**
 * Everything an engine needs to draw a {@link LineBatch} with one {@link LineStrategy}, without
 * knowing the strategies: the shaders, the layouts of the buffers, the filled buffers and the
 * draws.
 *
 * <p>Make a plan once per context with {@link #choose} (the best strategy the capabilities allow)
 * or {@link #force} (a named one, to test it, to save a feature or to work around a driver).
 * Then, whenever the batch changes:
 *
 * <ol>
 *   <li>make the buffers {@link #dataBytes} and {@link #styleBytes} large enough and call
 *       {@link #write}, which fills them and a {@link DrawList};
 *   <li>per frame, set the three uniforms ({@value #U_VIEW_PROJECTION}, {@value #U_VIEWPORT},
 *       {@value #U_WORLD_TO_PIXEL}), bind the buffers as {@link #describe()} says, and submit the
 *       draw list the way {@link #submission} names, with depth writes and face culling off (the
 *       triangles have no fixed winding).
 * </ol>
 *
 * <p>The shaders are for OpenGL (loose uniforms, {@code in}/{@code out} without locations
 * between the stages); the Vulkan form of the text is roadmap item GPU-13. They have been
 * checked against {@link LineGeometry} as a model, not compiled or run: no GLSL compiler was
 * available when they were written, and {@code ShaderCompileTest} compiles them where one is
 * installed.
 *
 * <p>A segment that has an end at or behind the camera is dropped by the shader; clip the line
 * first (roadmap LINE-6) or keep it in front of the camera.
 *
 * <p><b>Thread safety.</b> Immutable once made; {@link #write} reads the batch and writes the
 * buffers it is given, so use one writer per batch.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * LineRenderPlan plan = LineRenderPlan.choose(caps);
 * MemorySegment data = arena.allocate(plan.dataBytes(batch)), styles = arena.allocate(Math.max(plan.styleBytes(batch), 1));
 * DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 64);
 * plan.write(batch, data, styles, draws);
 * DrawSubmission how = plan.submission(draws);
 * }</pre>
 */
@Experimental("the plan may change")
public final class LineRenderPlan {

    /** The primitive that the draws of a plan draw. */
    public enum Primitive {
        /** Triangles ({@code GL_TRIANGLES}). */
        TRIANGLES,
        /** Line strips ({@code GL_LINE_STRIP}). */
        LINE_STRIP
    }

    /** The name of the view-projection uniform ({@code mat4}, relative to the origin of the batch). */
    public static final String U_VIEW_PROJECTION = "u_viewProjection";
    /** The name of the viewport uniform ({@code vec2}, pixels). */
    public static final String U_VIEWPORT = "u_viewport";
    /** The name of the uniform with the pixels per world unit at {@code w = 1} ({@code float}). */
    public static final String U_WORLD_TO_PIXEL = "u_worldToPixel";

    /** The first vertex attribute location of the segment attributes of the instanced strategies. */
    public static final int SEGMENT_LOCATION = 0;
    /** The binding or texture unit of the segment texture buffer of {@link LineStrategy#EXPANDED_MULTIDRAW}. */
    public static final int SEGMENT_SLOT = 0;
    /** The binding or texture unit of the style table. */
    public static final int STYLE_SLOT = 1;
    /** The length of the style table if it is a uniform block, the only mode with a fixed size. */
    public static final int STYLE_TABLE_UNIFORM_LENGTH = 256;

    private final LineStrategy strategy;
    private final GraphicsCapabilities caps;
    private final StructArrayAccess segments;
    private final StructArrayAccess styles;
    private final VertexBufferLayout hairlineLayout;
    private final String vertexShader;
    private final String fragmentShader;

    private LineRenderPlan(LineStrategy strategy, GraphicsCapabilities caps, StructArrayAccess segments, StructArrayAccess styles, VertexBufferLayout hairlineLayout,
                           String vertexShader, String fragmentShader) {
        this.strategy = strategy;
        this.caps = caps;
        this.segments = segments;
        this.styles = styles;
        this.hairlineLayout = hairlineLayout;
        this.vertexShader = vertexShader;
        this.fragmentShader = fragmentShader;
    }

    /**
     * Makes the plan of the best strategy that the capabilities allow.
     *
     * @param caps the capabilities of the context; must not be {@code null}
     * @return the plan
     */
    public static LineRenderPlan choose(GraphicsCapabilities caps) {
        return of(LineStrategy.choose(caps), caps);
    }

    /**
     * Makes the plan of a named strategy.
     *
     * @param wanted the strategy; must not be {@code null}
     * @param caps the capabilities of the context; must not be {@code null}
     * @return the plan
     * @throws UnsupportedOperationException if the capabilities do not allow it; the message says
     *     what is missing
     */
    public static LineRenderPlan force(LineStrategy wanted, GraphicsCapabilities caps) {
        return of(LineStrategy.force(wanted, caps), caps);
    }

    private static LineRenderPlan of(LineStrategy s, GraphicsCapabilities caps) {
        if (s == LineStrategy.HAIRLINE) {
            VertexBufferLayout layout = VertexBufferLayout.builder().attributeAt("a_position", 0, VertexFormat.FLOAT32X3, 0).attributeAt("a_color", 1, VertexFormat.UINT32, 12)
                    .build((int) LineGpu.HAIRLINE_VERTEX_BYTES);
            String vs = caps.glsl().versionLine() + "\n" + layout.glslInputs() + "uniform mat4 " + U_VIEW_PROJECTION + ";\nout vec4 v_color;\n\nvoid main() {\n"
                    + "    gl_Position = " + U_VIEW_PROJECTION + " * vec4(a_position, 1.0);\n"
                    + "    v_color = vec4(float(a_color >> 24u), float((a_color >> 16u) & 255u), float((a_color >> 8u) & 255u), float(a_color & 255u)) / 255.0;\n}\n";
            String fs = caps.glsl().versionLine() + "\nin vec4 v_color;\nout vec4 o_color;\n\nvoid main() {\n    o_color = v_color;\n}\n";
            return new LineRenderPlan(s, caps, null, null, layout, vs, fs);
        }
        StructArrayAccess seg;
        if (s == LineStrategy.EXPANDED_MULTIDRAW) {
            seg = StructArrayAccess.of(LineGpu.SEGMENT, StructArrayAccess.Mode.TEXTURE_BUFFER, "segments", 0, SEGMENT_SLOT, caps);
        } else {
            seg = StructArrayAccess.of(LineGpu.SEGMENT, StructArrayAccess.Mode.VERTEX_ATTRIBUTE, "segments", 0, SEGMENT_LOCATION, caps);
        }
        StructArrayAccess.Mode styleMode = StructArrayAccess.choose(caps, StructArrayAccess.Need.RANDOM_ACCESS);
        StructArrayAccess sty = StructArrayAccess.of(LineGpu.STYLE, styleMode, "styles", STYLE_TABLE_UNIFORM_LENGTH, STYLE_SLOT, caps);
        String header = ShaderHeader.builder("VMATH_LINES").constant("VERTS_PER_SEGMENT", LineGeometry.VERTICES_PER_SEGMENT).constant("ROUND_STEPS", LineGeometry.ROUND_STEPS)
                .access(seg).access(sty).build(ShaderHeader.Language.GLSL);
        String drawId = caps.api() == GraphicsCapabilities.Api.VULKAN || caps.glsl().atLeast(GlslVersion.V460) ? "gl_DrawID" : "gl_DrawIDARB";
        boolean fromDrawId = s == LineStrategy.INDIRECT_DRAW_ID;
        String extension = fromDrawId && drawId.equals("gl_DrawIDARB") ? "#extension GL_ARB_shader_draw_parameters : require\n" : "";
        String segmentIndex = s == LineStrategy.EXPANDED_MULTIDRAW ? "gl_VertexID / VERTS_PER_SEGMENT" : "0";
        String styleIndex = fromDrawId ? drawId : "int(seg.flagsAndStyle & 0xFFFFFFu)";
        String vs = caps.glsl().versionLine() + "\n" + extension + header + VERTEX_BODY.replace("@SEGMENT_INDEX@", segmentIndex).replace("@STYLE_INDEX@", styleIndex);
        String fs = caps.glsl().versionLine() + "\n" + FRAGMENT;
        return new LineRenderPlan(s, caps, seg, sty, null, vs, fs);
    }

    private static final String VERTEX_BODY = """

            uniform mat4 u_viewProjection;
            uniform vec2 u_viewport;
            uniform float u_worldToPixel;

            out vec4 v_color;
            out float v_along;
            flat out vec4 v_dash0;
            flat out vec4 v_dash1;
            flat out uint v_dashCount;

            const float EPS = 1e-6;
            const float STRAIGHT = 1e-4;
            const float PI = 3.14159265358979;

            vec2 rot(vec2 v, float a) {
                float c = cos(a);
                float s = sin(a);
                return vec2(v.x * c - v.y * s, v.x * s + v.y * c);
            }

            vec2 screenOf(vec4 c) {
                return (c.xy / c.w * 0.5 + 0.5) * u_viewport;
            }

            // the vertex vid of a segment on the screen, in pixels: the same arithmetic as LineGeometry.vertex
            vec2 lineVertex(int vid, vec2 s0, vec2 s1, vec2 sp, int flags, float h, int cap, int join, float miterLimit, float along0, float along1, out float along) {
                vec2 d = s1 - s0;
                float len = length(d);
                along = along0;
                if (!(len > EPS)) {
                    return s0;
                }
                vec2 t = d / len;
                vec2 n = vec2(-t.y, t.x);
                if (vid < 6) {
                    bool atEnd = vid == 2 || vid == 4 || vid == 5;
                    float side = (vid == 0 || vid == 2 || vid == 5) ? 1.0 : -1.0;
                    along = atEnd ? along1 : along0;
                    return (atEnd ? s1 : s0) + side * n * h;
                }
                int local = vid - 6;
                bool endPiece = local >= 3 * ROUND_STEPS;
                if (endPiece) {
                    local -= 3 * ROUND_STEPS;
                }
                int tri = local / 3;
                int corner = local - tri * 3;
                vec2 c = endPiece ? s1 : s0;
                along = endPiece ? along1 : along0;
                int kind = 0;
                vec2 o1 = vec2(0.0);
                vec2 dir = vec2(0.0);
                float phi = 0.0;
                float scale = 1.0;
                if (!endPiece && (flags & 1) != 0) {
                    vec2 q = s0 - sp;
                    float plen = length(q);
                    if (plen > EPS) {
                        vec2 tp = q / plen;
                        float cr = tp.x * t.y - tp.y * t.x;
                        float dt = dot(tp, t);
                        float outer = cr > 0.0 ? -1.0 : 1.0;
                        o1 = outer * h * vec2(-tp.y, tp.x);
                        vec2 o2 = outer * h * n;
                        phi = atan(o1.x * o2.y - o1.y * o2.x, o1.x * o2.x + o1.y * o2.y);
                        if (abs(cr) < EPS && dt < 0.0) {
                            phi = (-o1.y * tp.x + o1.x * tp.y) > 0.0 ? PI : -PI;
                        }
                        if (abs(phi) > STRAIGHT) {
                            if (join == 2) {
                                kind = 1;
                            } else if (join == 0) {
                                float cosHalf = cos(phi * 0.5);
                                if (cosHalf > STRAIGHT && 1.0 / cosHalf <= miterLimit) {
                                    kind = 3;
                                    scale = 1.0 / cosHalf;
                                } else {
                                    kind = 2;
                                }
                            } else {
                                kind = 2;
                            }
                        }
                    }
                } else if (!endPiece && (flags & 2) != 0 && cap != 0) {
                    o1 = n * h;
                    dir = -t;
                    kind = cap == 2 ? 1 : 4;
                    phi = PI;
                } else if (endPiece && (flags & 4) != 0 && cap != 0) {
                    o1 = -n * h;
                    dir = t;
                    kind = cap == 2 ? 1 : 4;
                    phi = PI;
                }
                int steps = kind == 1 ? ROUND_STEPS : (kind == 2 ? 1 : (kind == 3 ? 2 : (kind == 4 ? 3 : 0)));
                if (tri >= steps || corner == 0) {
                    return c;
                }
                int k = tri + (corner == 2 ? 1 : 0);
                vec2 v;
                if (kind == 1) {
                    v = rot(o1, phi * float(k) / float(ROUND_STEPS));
                } else if (kind == 2) {
                    v = rot(o1, phi * float(k));
                } else if (kind == 3) {
                    v = rot(o1, phi * float(k) * 0.5) * (k == 1 ? scale : 1.0);
                } else {
                    float sgn = k <= 1 ? 1.0 : -1.0;
                    float push = (k == 1 || k == 2) ? h : 0.0;
                    v = sgn * o1 + dir * push;
                }
                return c + v;
            }

            void main() {
                LineSegment seg = fetch_segments(@SEGMENT_INDEX@);
                LineStyle st = fetch_styles(@STYLE_INDEX@);
                int vid = gl_VertexID % VERTS_PER_SEGMENT;
                int flags = int(seg.flagsAndStyle >> 24u);
                vec4 c0 = u_viewProjection * vec4(seg.p0, 1.0);
                vec4 c1 = u_viewProjection * vec4(seg.p1, 1.0);
                vec4 cp = u_viewProjection * vec4(seg.prev, 1.0);
                if (!(c0.w > 0.0) || !(c1.w > 0.0)) {
                    gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
                    v_color = vec4(0.0);
                    v_along = 0.0;
                    v_dash0 = vec4(0.0);
                    v_dash1 = vec4(0.0);
                    v_dashCount = 0u;
                    return;
                }
                if ((flags & 1) != 0 && !(cp.w > 0.0)) {
                    flags &= ~1;
                }
                float widthPx = ((st.flags >> 4u) & 1u) == 1u ? st.width * u_worldToPixel / c0.w : st.width;
                float along;
                vec2 pos = lineVertex(vid, screenOf(c0), screenOf(c1), screenOf(cp), flags, 0.5 * widthPx, int(st.flags & 3u), int((st.flags >> 2u) & 3u), st.miterLimit,
                        seg.along0, seg.along1, along);
                bool atEnd = vid >= 6 + 3 * ROUND_STEPS || vid == 2 || vid == 4 || vid == 5;
                float z = atEnd ? c1.z / c1.w : c0.z / c0.w;
                gl_Position = vec4(pos / u_viewport * 2.0 - 1.0, z, 1.0);
                v_color = st.color;
                v_along = along;
                v_dash0 = st.dash0;
                v_dash1 = st.dash1;
                v_dashCount = st.dashCount;
            }
            """;

    private static final String FRAGMENT = """
            in vec4 v_color;
            in float v_along;
            flat in vec4 v_dash0;
            flat in vec4 v_dash1;
            flat in uint v_dashCount;
            out vec4 o_color;

            void main() {
                if (v_dashCount > 0u) {
                    float d[8] = float[8](v_dash0.x, v_dash0.y, v_dash0.z, v_dash0.w, v_dash1.x, v_dash1.y, v_dash1.z, v_dash1.w);
                    int count = int(v_dashCount);
                    float total = 0.0;
                    for (int i = 0; i < count; i++) {
                        total += d[i];
                    }
                    float m = v_along - total * floor(v_along / total);
                    bool on = true;
                    for (int i = 0; i < count; i += 2) {
                        if (m < d[i]) {
                            on = true;
                            break;
                        }
                        m -= d[i];
                        if (m < d[i + 1]) {
                            on = false;
                            break;
                        }
                        m -= d[i + 1];
                    }
                    if (!on) {
                        discard;
                    }
                }
                o_color = v_color;
            }
            """;

    /**
     * Names the strategy of the plan.
     *
     * @return the strategy
     */
    public LineStrategy strategy() {
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
     * Names the primitive that the draws draw.
     *
     * @return {@link Primitive#LINE_STRIP} for the hairline strategy, else {@link Primitive#TRIANGLES}
     */
    public Primitive primitive() {
        return strategy == LineStrategy.HAIRLINE ? Primitive.LINE_STRIP : Primitive.TRIANGLES;
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
     * Describes how the segments reach the shader.
     *
     * @return the access to the segment records, or {@code null} for the hairline strategy
     */
    public StructArrayAccess segmentAccess() {
        return segments;
    }

    /**
     * Describes how the shader reads the style table.
     *
     * @return the access to the style records, or {@code null} for the hairline strategy
     */
    public StructArrayAccess styleAccess() {
        return styles;
    }

    /**
     * Describes the vertices of the hairline strategy.
     *
     * @return the vertex layout: {@code a_position} (3 floats) and {@code a_color} (an unsigned
     *     integer, {@code 0xRRGGBBAA}), 16 bytes per vertex
     * @throws IllegalStateException if the strategy is not {@link LineStrategy#HAIRLINE}
     */
    public VertexBufferLayout hairlineLayout() {
        if (hairlineLayout == null) {
            throw new IllegalStateException("only the hairline strategy has hairline vertices: " + strategy);
        }
        return hairlineLayout;
    }

    // ---------------------------------------------------------------- sizes and writing

    private int runs(LineBatch batch) {
        int n = 0, last = -1;
        for (int p : batch.drawOrder()) {
            int s = batch.styleIndexOf(p);
            if (n == 0 || s != last) {
                n++;
                last = s;
            }
        }
        return n;
    }

    /**
     * Gives the size of the data buffer for a batch.
     *
     * @param batch the batch; must not be {@code null}
     * @return the bytes of the segment records, or of the hairline vertices
     */
    public long dataBytes(LineBatch batch) {
        if (strategy != LineStrategy.HAIRLINE) {
            return batch.segmentCount() * LineGpu.SEGMENT_BYTES;
        }
        long vertices = 0;
        for (int p = 0; p < batch.polylineCount(); p++) {
            vertices += batch.pointCount(p) + (batch.isClosed(p) ? 1 : 0);
        }
        return vertices * LineGpu.HAIRLINE_VERTEX_BYTES;
    }

    /**
     * Gives the size of the style buffer for a batch.
     *
     * @param batch the batch; must not be {@code null}
     * @return the bytes of the style table: one record per run of polylines of equal style for
     *     {@link LineStrategy#INDIRECT_DRAW_ID} (it is read by the draw index), one per distinct
     *     style for the others, 0 for the hairline strategy
     */
    public long styleBytes(LineBatch batch) {
        if (strategy == LineStrategy.HAIRLINE) {
            return 0;
        }
        return (strategy == LineStrategy.INDIRECT_DRAW_ID ? runs(batch) : batch.styleCount()) * LineGpu.STYLE_BYTES;
    }

    /**
     * Fills the buffers and the draw list for a batch.
     *
     * <p>Polylines are written in drawing order, and consecutive polylines of equal style form a
     * run that is one draw (instanced strategies) or one range of vertices ({@link
     * LineStrategy#EXPANDED_MULTIDRAW}); the hairline strategy has a draw per polyline. The
     * {@code user} value of every draw is the index of its style in the batch.
     *
     * @param batch the batch; must not be {@code null}
     * @param data receives the segment records or hairline vertices; at least {@link #dataBytes}
     *     long
     * @param styleBuffer receives the style table; at least {@link #styleBytes} long; may be
     *     {@code null} for the hairline strategy
     * @param draws receives the draws, after being cleared; must be of the kind
     *     {@link DrawList.Kind#ARRAYS}
     * @return the number of draws
     * @throws IllegalArgumentException if a buffer is too small, the draw list has the wrong kind,
     *     or the style table of a uniform block is too short for the styles
     */
    public int write(LineBatch batch, MemorySegment data, MemorySegment styleBuffer, DrawList draws) {
        return write(batch, data, styleBuffer, draws, null);
    }

    /**
     * Fills the buffers and the draw list for a batch, with every colour passed through a colour map:
     * a display palette (day, night, night vision) applied where the colours are written.
     *
     * <p>Switching the palette later is {@link #rewriteStyles}: one small buffer, not the segments
     * (the hairline strategy keeps the colour in its vertices and needs this method again).
     *
     * @param batch the batch; must not be {@code null}
     * @param data receives the segment records or hairline vertices; at least {@link #dataBytes} long
     * @param styleBuffer receives the style table; at least {@link #styleBytes} long; may be {@code null} for the hairline strategy
     * @param draws receives the draws, after being cleared; must be of the kind {@link DrawList.Kind#ARRAYS}
     * @param colorMap maps a colour {@code 0xRRGGBBAA} to the colour to show; may be {@code null} for none
     * @return the number of draws
     * @throws IllegalArgumentException as for {@link #write(LineBatch, MemorySegment, MemorySegment, DrawList)}
     */
    public int write(LineBatch batch, MemorySegment data, MemorySegment styleBuffer, DrawList draws, IntUnaryOperator colorMap) {
        if (draws.kind() != DrawList.Kind.ARRAYS) {
            throw new IllegalArgumentException("the draw list must hold ARRAYS draws");
        }
        if (data.byteSize() < dataBytes(batch)) {
            throw new IllegalArgumentException("the data buffer has " + data.byteSize() + " bytes, " + dataBytes(batch) + " are needed");
        }
        draws.clear();
        if (strategy == LineStrategy.HAIRLINE) {
            return writeHairlines(batch, data, draws, colorMap);
        }
        if (styleBuffer == null || styleBuffer.byteSize() < styleBytes(batch)) {
            throw new IllegalArgumentException("the style buffer needs " + styleBytes(batch) + " bytes");
        }
        if (styles.mode() == StructArrayAccess.Mode.UNIFORM_BLOCK && (strategy == LineStrategy.INDIRECT_DRAW_ID ? runs(batch) : batch.styleCount()) > STYLE_TABLE_UNIFORM_LENGTH) {
            throw new IllegalArgumentException("a uniform block holds " + STYLE_TABLE_UNIFORM_LENGTH + " styles: use a context with storage buffers or texture buffers");
        }
        if (strategy != LineStrategy.INDIRECT_DRAW_ID) {
            for (int i = 0; i < batch.styleCount(); i++) {
                LineGpu.writeStyle(styleBuffer, i * LineGpu.STYLE_BYTES, mapped(batch.style(i), colorMap));
            }
        }
        SegmentFeed feed = new SegmentFeed();
        long segment = 0;
        long runFirst = 0;
        long runSegments = 0;
        int runStyle = -1;
        for (int p : batch.drawOrder()) {
            int si = batch.styleIndexOf(p);
            if (runSegments > 0 && si != runStyle) {
                closeRun(mapped(batch.style(runStyle), colorMap), styleBuffer, draws, runStyle, runFirst, runSegments);
                runSegments = 0;
            }
            if (runSegments == 0) {
                runStyle = si;
                runFirst = segment;
            }
            feed.begin(batch, p);
            while (feed.next()) {
                LineGpu.writeSegment(data, segment * LineGpu.SEGMENT_BYTES, feed.p0, feed.p1, feed.prev, feed.along0, feed.along1, si, feed.flags);
                segment++;
                runSegments++;
            }
        }
        if (runSegments > 0) {
            closeRun(mapped(batch.style(runStyle), colorMap), styleBuffer, draws, runStyle, runFirst, runSegments);
        }
        return draws.size();
    }

    /** The style with its colour passed through a colour map. */
    static LineStyle mapped(LineStyle style, IntUnaryOperator colorMap) {
        return colorMap == null ? style : style.withColor(colorMap.applyAsInt(style.color()));
    }

    /**
     * Rewrites the style table for the draws that {@link #write} produced, with the colours passed
     * through a colour map: a palette switch is this call and one upload of the small style buffer.
     *
     * @param batch the batch the draws were written for; must not be {@code null}
     * @param draws the draws written by {@link #write} for it; must not be {@code null}
     * @param styleBuffer receives the style table; at least {@link #styleBytes} long
     * @param colorMap maps a colour {@code 0xRRGGBBAA} to the colour to show; may be {@code null} for none
     * @throws IllegalStateException if the strategy is {@link LineStrategy#HAIRLINE}, whose colours are in the vertices
     * @throws IllegalArgumentException if the buffer is too small
     */
    public void rewriteStyles(LineBatch batch, DrawList draws, MemorySegment styleBuffer, IntUnaryOperator colorMap) {
        if (strategy == LineStrategy.HAIRLINE) {
            throw new IllegalStateException("the hairline strategy keeps the colours in its vertices: write the batch again");
        }
        if (styleBuffer.byteSize() < styleBytes(batch)) {
            throw new IllegalArgumentException("the style buffer needs " + styleBytes(batch) + " bytes");
        }
        if (strategy == LineStrategy.INDIRECT_DRAW_ID) {
            for (int d = 0; d < draws.size(); d++) {
                LineGpu.writeStyle(styleBuffer, d * LineGpu.STYLE_BYTES, mapped(batch.style(draws.user(d)), colorMap));
            }
        } else {
            for (int i = 0; i < batch.styleCount(); i++) {
                LineGpu.writeStyle(styleBuffer, i * LineGpu.STYLE_BYTES, mapped(batch.style(i), colorMap));
            }
        }
    }

    private void closeRun(LineStyle runStyle, MemorySegment styleBuffer, DrawList draws, int style, long firstSegment, long segmentCount) {
        if (strategy == LineStrategy.INDIRECT_DRAW_ID) {
            LineGpu.writeStyle(styleBuffer, draws.size() * LineGpu.STYLE_BYTES, runStyle);
        }
        if (strategy == LineStrategy.EXPANDED_MULTIDRAW) {
            draws.addArrays((int) (segmentCount * LineGeometry.VERTICES_PER_SEGMENT), (int) (firstSegment * LineGeometry.VERTICES_PER_SEGMENT), 1, 0, style);
        } else {
            draws.addArrays(LineGeometry.VERTICES_PER_SEGMENT, 0, (int) segmentCount, (int) firstSegment, style);
        }
    }

    // ---------------------------------------------------------------- pieces for LineSet

    /** The size in bytes of what a polyline occupies per unit: a segment record, or a hairline vertex. */
    long unitBytes() {
        return strategy == LineStrategy.HAIRLINE ? LineGpu.HAIRLINE_VERTEX_BYTES : LineGpu.SEGMENT_BYTES;
    }

    /** The units (segment records or hairline vertices) of a polyline. */
    int unitsOf(int pointCount, boolean closed) {
        if (strategy == LineStrategy.HAIRLINE) {
            return pointCount + (closed ? 1 : 0);
        }
        return closed ? pointCount : pointCount - 1;
    }

    /** Writes the records of one polyline from {@code firstUnit}. */
    void writeUnits(MemorySegment data, long firstUnit, double[] xyz, int firstPoint, int pointCount, boolean closed, LineStyle style, int styleIndex, double ox, double oy, double oz,
                    SegmentFeed feed) {
        if (strategy == LineStrategy.HAIRLINE) {
            int color = style.color();
            for (int i = 0; i < pointCount + (closed ? 1 : 0); i++) {
                int at = 3 * (firstPoint + i % pointCount);
                long o = (firstUnit + i) * LineGpu.HAIRLINE_VERTEX_BYTES;
                vmath.gl.GpuWriter.putFloat(data, o, (float) (xyz[at] - ox));
                vmath.gl.GpuWriter.putFloat(data, o + 4, (float) (xyz[at + 1] - oy));
                vmath.gl.GpuWriter.putFloat(data, o + 8, (float) (xyz[at + 2] - oz));
                vmath.gl.GpuWriter.putInt(data, o + 12, color);
            }
            return;
        }
        feed.begin(xyz, firstPoint, pointCount, closed, style.dashPeriod(), ox, oy, oz);
        long segment = firstUnit;
        while (feed.next()) {
            LineGpu.writeSegment(data, segment++ * LineGpu.SEGMENT_BYTES, feed.p0, feed.p1, feed.prev, feed.along0, feed.along1, styleIndex, feed.flags);
        }
    }

    /** Adds the draw of a range of units (a run of equal style, or one polyline of the hairline strategy). */
    void addDraw(DrawList draws, MemorySegment styleBuffer, LineStyle style, int styleIndex, long firstUnit, long units) {
        if (strategy == LineStrategy.HAIRLINE) {
            draws.addArrays((int) units, (int) firstUnit, 1, 0, styleIndex);
        } else {
            closeRun(style, styleBuffer, draws, styleIndex, firstUnit, units);
        }
    }

    StructArrayAccess.Mode styleMode() {
        return styles == null ? null : styles.mode();
    }

    private int writeHairlines(LineBatch batch, MemorySegment data, DrawList draws, IntUnaryOperator colorMap) {
        float[] p = new float[3];
        long vertex = 0;
        for (int polyline : batch.drawOrder()) {
            int n = batch.pointCount(polyline);
            boolean closed = batch.isClosed(polyline);
            int si = batch.styleIndexOf(polyline);
            int color = mapped(batch.style(si), colorMap).color();
            long first = vertex;
            for (int i = 0; i < n + (closed ? 1 : 0); i++) {
                batch.relativePoint(polyline, i % n, p, 0);
                long o = vertex * LineGpu.HAIRLINE_VERTEX_BYTES;
                for (int k = 0; k < 3; k++) {
                    vmath.gl.GpuWriter.putFloat(data, o + 4L * k, p[k]);
                }
                vmath.gl.GpuWriter.putInt(data, o + 12, color);
                vertex++;
            }
            draws.addArrays((int) (vertex - first), (int) first, 1, 0, si);
        }
        return draws.size();
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
     * Describes in words how to bind and issue what the plan has written, for the strategy of
     * this plan.
     *
     * @return a few lines of text
     */
    public String describe() {
        StringBuilder sb = new StringBuilder("strategy ").append(strategy).append(" on ").append(caps).append('\n');
        sb.append("primitive: ").append(primitive() == Primitive.TRIANGLES ? "GL_TRIANGLES" : "GL_LINE_STRIP").append(", no face culling, no depth writes\n");
        if (strategy == LineStrategy.HAIRLINE) {
            sb.append("vertices: ").append(hairlineLayout.stride()).append(" bytes: a_position at location 0, a_color (integer attribute) at location 1\n");
            sb.append("uniform: ").append(U_VIEW_PROJECTION).append('\n');
            return sb.toString();
        }
        sb.append("uniforms: ").append(U_VIEW_PROJECTION).append(", ").append(U_VIEWPORT).append(", ").append(U_WORLD_TO_PIXEL).append('\n');
        sb.append("segments: ").append(segments.mode()).append(", ").append(segments.elementStride()).append(" bytes per segment");
        if (segments.mode() == StructArrayAccess.Mode.VERTEX_ATTRIBUTE) {
            sb.append(", attributes from location ").append(SEGMENT_LOCATION).append(", divisor 1");
        } else {
            sb.append(", texture buffer (RGBA32UI) on unit ").append(SEGMENT_SLOT);
        }
        sb.append('\n');
        sb.append("styles: ").append(styles.mode()).append(", ").append(styles.elementStride()).append(" bytes per style, slot ").append(STYLE_SLOT).append(strategy == LineStrategy.INDIRECT_DRAW_ID
                ? ", one entry per draw (read by the draw index)" : ", one entry per distinct style").append('\n');
        sb.append("draws: ").append(strategy == LineStrategy.EXPANDED_MULTIDRAW ? LineGeometry.VERTICES_PER_SEGMENT + " vertices per segment, one range per run of equal style, no instancing"
                : LineGeometry.VERTICES_PER_SEGMENT + " vertices per instance, one draw per run of equal style, the segments of the run as instances").append('\n');
        return sb.toString();
    }
}
