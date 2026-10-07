package vmath.gl;

import vmath.annotations.Experimental;
import vmath.gl.GraphicsCapabilities.Feature;

/**
 * How a {@link DrawList} is handed to the driver.
 *
 * <p>{@link #choose(GraphicsCapabilities, DrawList)} returns the best way that the capabilities
 * allow for the shape of the list; {@link #chooser(boolean, boolean)} gives the table behind it.
 * The shape matters: a client multi-draw cannot instance, and a base instance needs
 * {@link Feature#BASE_INSTANCE} or per-instance attributes that the caller moves by hand.
 *
 * <p><b>Thread safety.</b> The constants are immutable and the methods stateless: safe to call
 * from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DrawSubmission how = DrawSubmission.choose(GraphicsCapabilities.baseline(), draws);   // MULTI_DRAW_CLIENT or DRAW_LOOP
 * }</pre>
 */
@Experimental("the strategies may change")
public enum DrawSubmission {
    /** One call with the draws in a buffer ({@code glMultiDrawArraysIndirect}, {@code glMultiDrawElementsIndirect}, {@code vkCmdDrawIndexedIndirect}). */
    MULTI_DRAW_INDIRECT,
    /** One call with the counts and offsets in client arrays ({@code glMultiDrawArrays}, {@code glMultiDrawElements}); no instancing. */
    MULTI_DRAW_CLIENT,
    /** One draw call per draw, from a loop; the form that works everywhere. */
    DRAW_LOOP;

    private static final StrategyChooser<DrawSubmission> PLAIN = StrategyChooser.<DrawSubmission>builder("submitting draws without instancing")
            .option(MULTI_DRAW_INDIRECT, "one indirect call, the commands in a buffer", Feature.MULTI_DRAW_INDIRECT)
            .option(MULTI_DRAW_CLIENT, "one call with client arrays", Feature.MULTI_DRAW)
            .option(DRAW_LOOP, "a draw call per draw")
            .build();

    private static final StrategyChooser<DrawSubmission> INSTANCED = StrategyChooser.<DrawSubmission>builder("submitting instanced draws")
            .option(MULTI_DRAW_INDIRECT, "one indirect call, the commands in a buffer", Feature.MULTI_DRAW_INDIRECT)
            .option(DRAW_LOOP, "an instanced draw call per draw", Feature.INSTANCED_DRAWS)
            .build();

    private static final StrategyChooser<DrawSubmission> BASE_INSTANCE = StrategyChooser.<DrawSubmission>builder("submitting instanced draws with a base instance")
            .option(MULTI_DRAW_INDIRECT, "one indirect call, the commands in a buffer", Feature.MULTI_DRAW_INDIRECT, Feature.BASE_INSTANCE)
            .option(DRAW_LOOP, "an instanced draw call per draw; without a base instance the per-instance attributes are moved by hand", Feature.INSTANCED_DRAWS)
            .build();

    /**
     * Gives the table that decides for a shape of list.
     *
     * @param instanced whether some draw has more than one instance
     * @param baseInstance whether some draw has a base instance that is not zero
     * @return the chooser: one of three, for plain draws, instanced draws, and instanced draws with
     *     a base instance
     */
    public static StrategyChooser<DrawSubmission> chooser(boolean instanced, boolean baseInstance) {
        if (baseInstance) {
            return BASE_INSTANCE;
        }
        return instanced ? INSTANCED : PLAIN;
    }

    /**
     * Chooses the best way to submit draws of a shape.
     *
     * @param caps the capabilities; must not be {@code null}
     * @param instanced whether some draw has more than one instance
     * @param baseInstance whether some draw has a base instance that is not zero
     * @return the best possible way
     * @throws UnsupportedOperationException if none is possible (instancing without
     *     {@link Feature#INSTANCED_DRAWS} and without indirect draws)
     */
    public static DrawSubmission choose(GraphicsCapabilities caps, boolean instanced, boolean baseInstance) {
        return chooser(instanced, baseInstance).choose(caps);
    }

    /**
     * Chooses the best way to submit a list.
     *
     * @param caps the capabilities; must not be {@code null}
     * @param draws the list; must not be {@code null}
     * @return the best possible way for its shape
     * @throws UnsupportedOperationException if none is possible
     */
    public static DrawSubmission choose(GraphicsCapabilities caps, DrawList draws) {
        return choose(caps, draws.usesInstancing(), draws.usesBaseInstance());
    }

    /**
     * Checks that a named way is possible for a list and returns it: how to ask for a lower one
     * on purpose.
     *
     * @param wanted the way; must not be {@code null}
     * @param caps the capabilities; must not be {@code null}
     * @param draws the list; must not be {@code null}
     * @return {@code wanted}
     * @throws UnsupportedOperationException if the capabilities do not allow it for this list, or
     *     it cannot express the list (a client multi-draw of instanced draws)
     */
    public static DrawSubmission force(DrawSubmission wanted, GraphicsCapabilities caps, DrawList draws) {
        boolean instanced = draws.usesInstancing();
        boolean baseInstance = draws.usesBaseInstance();
        StrategyChooser<DrawSubmission> c = chooser(instanced, baseInstance);
        if (!c.strategies().contains(wanted)) {
            throw new UnsupportedOperationException(wanted + " cannot submit a list that " + (baseInstance ? "has a base instance" : "has instances"));
        }
        return c.force(wanted, caps);
    }
}
