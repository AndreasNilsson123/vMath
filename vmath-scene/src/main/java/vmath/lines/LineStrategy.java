package vmath.lines;

import vmath.annotations.Experimental;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.GraphicsCapabilities.Feature;
import vmath.gl.StrategyChooser;

/**
 * The ways of drawing thick lines, from the newest to the oldest, and the table that picks one.
 *
 * <p>All but the last draw every segment of a polyline as a fixed number of vertices that a shader
 * places on the screen ({@link LineGeometry}); they differ in how a segment reaches the shader
 * and how the draws are issued:
 *
 * <ul>
 *   <li>{@link #INDIRECT_DRAW_ID}: one indirect multi-draw; the segments are per-instance vertex
 *       attributes and the <em>style</em> of a draw is read from a table by the draw index
 *       ({@code gl_DrawID}).
 *   <li>{@link #INDIRECT_INSTANCE_STYLE}: one indirect multi-draw; the style index is stored in every
 *       segment, so no draw index is needed.
 *   <li>{@link #EXPANDED_MULTIDRAW}: one {@code glMultiDrawArrays}; no instancing at all: the
 *       shader finds its segment in a texture buffer from {@code gl_VertexID}, and the vertices are
 *       {@value LineGeometry#VERTICES_PER_SEGMENT} per segment.
 *   <li>{@link #INSTANCED_LOOP}: one instanced draw per run of polylines of equal style, with
 *       the segments as per-instance attributes whose buffer offset is moved per draw where the
 *       context has no base instance.
 *   <li>{@link #HAIRLINE}: one-pixel line strips, one draw per polyline; the width, the caps, the
 *       joins and the dashes are ignored and only the colour is used. The last resort and a
 *       debugging aid.
 * </ul>
 *
 * <p>No strategy uses a geometry shader: every OpenGL from 3.30 has instancing, and geometry
 * shaders are slower on most drivers.
 *
 * <p><b>Thread safety.</b> The constants are immutable and the methods stateless: safe to call
 * from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * LineStrategy best = LineStrategy.choose(GraphicsCapabilities.openGl(4, 6, List.of()));   // INDIRECT_DRAW_ID
 * LineStrategy floor = LineStrategy.choose(GraphicsCapabilities.baseline());               // EXPANDED_MULTIDRAW
 * }</pre>
 */
@Experimental("the strategies may change")
public enum LineStrategy {
    /** One indirect multi-draw, the style read through the draw index. */
    INDIRECT_DRAW_ID,
    /** One indirect multi-draw, the style index stored in every segment. */
    INDIRECT_INSTANCE_STYLE,
    /** One instanced draw per run of equal style, the segments as per-instance attributes. */
    INSTANCED_LOOP,
    /** One client multi-draw with no instancing, the segments in a texture buffer. */
    EXPANDED_MULTIDRAW,
    /** One-pixel line strips, colour only. */
    HAIRLINE;

    private static final StrategyChooser<LineStrategy> CHOOSER = StrategyChooser.<LineStrategy>builder("drawing thick lines")
            .option(INDIRECT_DRAW_ID, "one indirect multi-draw; instanced segments; the style table indexed by the draw index",
                    Feature.MULTI_DRAW_INDIRECT, Feature.BASE_INSTANCE, Feature.SHADER_DRAW_PARAMETERS, Feature.INSTANCED_ARRAYS)
            .option(INDIRECT_INSTANCE_STYLE, "one indirect multi-draw; instanced segments; the style index in every segment",
                    Feature.MULTI_DRAW_INDIRECT, Feature.BASE_INSTANCE, Feature.INSTANCED_ARRAYS)
            .option(EXPANDED_MULTIDRAW, "one glMultiDrawArrays, no instancing; segments in a texture buffer read from the vertex index", Feature.MULTI_DRAW, Feature.TEXTURE_BUFFERS)
            .option(INSTANCED_LOOP, "an instanced draw per run of equal style; instanced attributes moved by hand without a base instance", Feature.INSTANCED_DRAWS, Feature.INSTANCED_ARRAYS)
            .option(HAIRLINE, "one-pixel line strips, colour only")
            .build();

    /**
     * Gives the decision table.
     *
     * @return the chooser, whose {@code markdownTable()} the documentation embeds
     */
    public static StrategyChooser<LineStrategy> chooser() {
        return CHOOSER;
    }

    /**
     * Chooses the best strategy that the capabilities allow.
     *
     * @param caps the capabilities; must not be {@code null}
     * @return the first strategy of the table whose needs are met
     */
    public static LineStrategy choose(GraphicsCapabilities caps) {
        return CHOOSER.choose(caps);
    }

    /**
     * Chooses the best strategy at or below a ceiling.
     *
     * @param caps the capabilities; must not be {@code null}
     * @param ceiling the best strategy that may be used; must not be {@code null}
     * @return the first strategy from {@code ceiling} on whose needs are met
     * @throws UnsupportedOperationException if none is possible
     */
    public static LineStrategy chooseAtMost(GraphicsCapabilities caps, LineStrategy ceiling) {
        return CHOOSER.chooseAtMost(caps, ceiling);
    }

    /**
     * Checks that a named strategy is possible and returns it.
     *
     * @param wanted the strategy; must not be {@code null}
     * @param caps the capabilities; must not be {@code null}
     * @return {@code wanted}
     * @throws UnsupportedOperationException if the capabilities do not allow it; the message says
     *     what is missing
     */
    public static LineStrategy force(LineStrategy wanted, GraphicsCapabilities caps) {
        return CHOOSER.force(wanted, caps);
    }
}
