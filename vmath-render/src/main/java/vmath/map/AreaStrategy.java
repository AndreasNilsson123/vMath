package vmath.map;

import vmath.annotations.Experimental;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.GraphicsCapabilities.Feature;
import vmath.gl.StrategyChooser;

/**
 * The ways of drawing filled areas, from the richer to the plainer, and the table that picks one.
 *
 * <ul>
 *   <li>{@link #STYLE_TABLE}: every vertex carries a style index; the vertex shader reads the style
 *       (fill, pattern, spacing, width, angle) from a table that is a storage block, a uniform block
 *       or a texture buffer, whichever the context has ({@link vmath.gl.StructArrayAccess}), and the
 *       fragment shader paints the pattern.
 *   <li>{@link #VERTEX_COLOR}: every vertex carries its colour; solid fills only, the patterns of the
 *       styles are ignored. It needs nothing of the context and is the floor.
 * </ul>
 *
 * <p>Both draw indexed triangles from one vertex buffer and one index buffer, as one draw per run
 * of equal style, submitted the way {@link vmath.gl.DrawSubmission} picks for the capabilities.
 *
 * <p><b>Thread safety.</b> The constants are immutable and the methods stateless: safe to call
 * from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * AreaStrategy best = AreaStrategy.choose(GraphicsCapabilities.baseline());                                 // STYLE_TABLE
 * AreaStrategy floor = AreaStrategy.choose(GraphicsCapabilities.baseline().without(Feature.UNIFORM_BLOCKS, Feature.TEXTURE_BUFFERS));   // VERTEX_COLOR
 * }</pre>
 */
@Experimental("the strategies may change")
public enum AreaStrategy {
    /** A style index per vertex and a style table; patterns are painted. */
    STYLE_TABLE,
    /** A colour per vertex; solid fills only. */
    VERTEX_COLOR;

    private static final StrategyChooser<AreaStrategy> CHOOSER = StrategyChooser.<AreaStrategy>builder("drawing filled areas")
            .option(STYLE_TABLE, "a style index per vertex and a table of styles (storage block, uniform block or texture buffer); patterns in the fragment shader", Feature.UNIFORM_BLOCKS)
            .option(VERTEX_COLOR, "a colour per vertex; solid fills only")
            .build();

    /**
     * Gives the decision table.
     *
     * @return the chooser, whose {@code markdownTable()} the documentation embeds
     */
    public static StrategyChooser<AreaStrategy> chooser() {
        return CHOOSER;
    }

    /**
     * Chooses the best strategy that the capabilities allow.
     *
     * @param caps the capabilities; must not be {@code null}
     * @return the first strategy of the table whose needs are met
     */
    public static AreaStrategy choose(GraphicsCapabilities caps) {
        return CHOOSER.choose(caps);
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
    public static AreaStrategy force(AreaStrategy wanted, GraphicsCapabilities caps) {
        return CHOOSER.force(wanted, caps);
    }
}
