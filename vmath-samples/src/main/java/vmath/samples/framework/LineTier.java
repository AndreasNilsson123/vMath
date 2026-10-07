package vmath.samples.framework;

import java.util.List;
import vmath.gl.GraphicsCapabilities;
import vmath.lines.LineRenderPlan;
import vmath.lines.LineStrategy;

/**
 * The tiers of the line renderer that the line demos draw side by side: a strategy of
 * {@code vmath.lines} with the capabilities (and so the GLSL version and the way of submitting the
 * draws) of a context that would have chosen it. The demos run in an OpenGL 4.5 context and pass
 * these capabilities to the library, which is how the older tiers are drawn on one machine; what
 * that cannot show is that a driver of that version accepts the text ({@code docs/LINES.md}).
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share.
 */
public enum LineTier {
    /** The best: an indirect multi-draw with the style read through the draw index (GLSL 450 and the draw-parameters extension). */
    DRAW_ID("4.5 draw id", "INDIRECT_DRAW_ID on OpenGL 4.5 with ARB_shader_draw_parameters", LineStrategy.INDIRECT_DRAW_ID, 4, 5, List.of("GL_ARB_shader_draw_parameters")),
    /** An indirect multi-draw with the style in each segment (GLSL 430). */
    INSTANCE_STYLE("4.3 indirect", "INDIRECT_INSTANCE_STYLE on OpenGL 4.3", LineStrategy.INDIRECT_INSTANCE_STYLE, 4, 3, List.of()),
    /** An instanced draw per run, with a base instance (GLSL 420). */
    LOOP_42("4.2 loop", "INSTANCED_LOOP on OpenGL 4.2: a draw per run with a base instance", LineStrategy.INSTANCED_LOOP, 4, 2, List.of()),
    /** Vertex pulling from a texture buffer and one glMultiDrawArrays (GLSL 330). */
    PULL_33("3.3 pulling", "EXPANDED_MULTIDRAW on OpenGL 3.3: segments in a texture buffer, one glMultiDrawArrays", LineStrategy.EXPANDED_MULTIDRAW, 3, 3, List.of()),
    /** An instanced draw per run, the attributes moved by hand (GLSL 330). */
    LOOP_33("3.3 loop", "INSTANCED_LOOP on OpenGL 3.3: a draw per run, the attributes moved by hand", LineStrategy.INSTANCED_LOOP, 3, 3, List.of()),
    /** One-pixel line strips (GLSL 330). */
    HAIRLINE_33("3.3 hairline", "HAIRLINE on OpenGL 3.3: one-pixel line strips", LineStrategy.HAIRLINE, 3, 3, List.of());

    private final String shortName;
    private final String label;
    private final LineStrategy strategy;
    private final GraphicsCapabilities caps;

    LineTier(String shortName, String label, LineStrategy strategy, int major, int minor, List<String> extensions) {
        this.shortName = shortName;
        this.label = label;
        this.strategy = strategy;
        this.caps = GraphicsCapabilities.openGl(major, minor, extensions);
    }

    /**
     * Gives a short name for the HUD and the series.
     *
     * @return the name
     */
    public String shortName() {
        return shortName;
    }

    /**
     * Describes the tier.
     *
     * @return the strategy and the context it stands for
     */
    public String label() {
        return label;
    }

    /**
     * Gives the strategy of the tier.
     *
     * @return the strategy
     */
    public LineStrategy strategy() {
        return strategy;
    }

    /**
     * Gives the capabilities that select the strategy.
     *
     * @return the capabilities
     */
    public GraphicsCapabilities caps() {
        return caps;
    }

    /**
     * Makes the plan of the tier.
     *
     * @return the plan, with the text and the submission of the tier
     */
    public LineRenderPlan plan() {
        return LineRenderPlan.force(strategy, caps);
    }
}
