package vmath.map;

import vmath.annotations.Experimental;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.GraphicsCapabilities.Feature;
import vmath.gl.StrategyChooser;

/**
 * The ways of drawing textured symbols, from the newest to the oldest, and the table that picks one.
 *
 * <p>All of them draw every symbol as a quad of two triangles that a shader places on the screen
 * with a size in pixels, and none uses a geometry shader; they differ in how the symbol record
 * reaches the shader:
 *
 * <ul>
 *   <li>{@link #INSTANCED}: one instanced draw of 6 vertices; the records are per-instance vertex
 *       attributes (OpenGL 3.30 has them).
 *   <li>{@link #TEXTURE_FETCH}: one plain draw of 6 vertices per symbol and no instancing; the shader
 *       finds its record in a texture buffer from {@code gl_VertexID}.
 *   <li>{@link #EXPANDED}: the CPU writes the record 6 times, once per vertex, and the shader reads
 *       ordinary vertex attributes; it needs nothing of the context and is the floor.
 * </ul>
 *
 * <p><b>Thread safety.</b> The constants are immutable and the methods stateless: safe to call
 * from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * SymbolStrategy best = SymbolStrategy.choose(GraphicsCapabilities.openGl(3, 3, List.of()));
 * SymbolStrategy floor = SymbolStrategy.choose(GraphicsCapabilities.baseline());
 * }</pre>
 */
@Experimental("the strategies may change")
public enum SymbolStrategy {
    /** One instanced draw, the records as per-instance attributes. */
    INSTANCED,
    /** One plain draw, the records in a texture buffer read from the vertex index. */
    TEXTURE_FETCH,
    /** One plain draw, the records written once per vertex. */
    EXPANDED;

    private static final StrategyChooser<SymbolStrategy> CHOOSER = StrategyChooser.<SymbolStrategy>builder("drawing map symbols")
            .option(INSTANCED, "one instanced draw; the symbol records as per-instance attributes", Feature.INSTANCED_DRAWS, Feature.INSTANCED_ARRAYS)
            .option(TEXTURE_FETCH, "one plain draw, 6 vertices per symbol; the records in a texture buffer read from the vertex index", Feature.TEXTURE_BUFFERS)
            .option(EXPANDED, "one plain draw; the records repeated for each of the 6 vertices by the CPU")
            .build();

    /**
     * Gives the decision table.
     *
     * @return the chooser, whose {@code markdownTable()} the documentation embeds
     */
    public static StrategyChooser<SymbolStrategy> chooser() {
        return CHOOSER;
    }

    /**
     * Chooses the best strategy that the capabilities allow.
     *
     * @param caps the capabilities; must not be {@code null}
     * @return the first strategy of the table whose needs are met
     */
    public static SymbolStrategy choose(GraphicsCapabilities caps) {
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
    public static SymbolStrategy chooseAtMost(GraphicsCapabilities caps, SymbolStrategy ceiling) {
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
    public static SymbolStrategy force(SymbolStrategy wanted, GraphicsCapabilities caps) {
        return CHOOSER.force(wanted, caps);
    }
}
