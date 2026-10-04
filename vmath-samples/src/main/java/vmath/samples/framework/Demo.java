package vmath.samples.framework;

/**
 * One demo: a scene that shows a strength of the library, driven by the {@link DemoRunner}.
 *
 * <p>The runner owns the window, the OpenGL context, the main loop, the input, the timing and the
 * report; the demo owns its scene, its camera and its GPU objects. In every frame the runner
 * polls the input, calls {@link #update} (the CPU work: input, simulation, culling, writing the
 * data the GPU reads), clears the framebuffer and calls {@link #render} (the GL commands), then
 * calls {@link #hud} to let the demo add text, and swaps. The render thread's allocation is
 * measured over {@code update} and {@code render} only, so a demo that is meant to run without
 * allocation shows it in the report.
 *
 * <p>The keys {@code Escape}, {@code Tab}, {@code PageUp}, {@code PageDown}, {@code V}, {@code P}
 * and {@code F1} belong to the runner; a demo uses the others. In scripted mode
 * ({@link FrameInfo#benchmark()}) the demo must not read the input and must follow a path that
 * depends on the frame number only, so that every run produces the same frames.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> All methods are called on the thread that owns the OpenGL context.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * public final class TriangleDemo implements Demo {
 *     public void create(DemoContext ctx) {
 *         // build the GPU objects: ctx.gl(), ctx.arena(), ctx.stats().series(...)
 *     }
 *     public void update(FrameInfo frame) {
 *         // read frame.input() unless frame.benchmark(), then do the CPU work
 *     }
 *     public void render(FrameInfo frame) {
 *         // issue the draw calls; the viewport is set and the framebuffer is cleared
 *     }
 *     public void dispose() {
 *         // delete everything create() made
 *     }
 * }
 * }</pre>
 */
public interface Demo {

    /**
     * Builds the scene and the GPU objects. The OpenGL context is current.
     *
     * @param ctx the services of the runner; must not be {@code null}, and stays valid until
     *     {@link #dispose}
     */
    void create(DemoContext ctx);

    /**
     * Does the CPU work of one frame: reads the input, advances the simulation and the camera, and
     * prepares the data that {@link #render} hands to the GPU.
     *
     * @param frame the time, the input and the size of the window; must not be {@code null}, and
     *     is reused from frame to frame
     */
    void update(FrameInfo frame);

    /**
     * Issues the OpenGL commands of one frame. The viewport covers the window and the colour and
     * depth buffers have been cleared; depth testing and back-face culling are on and blending is
     * off at the start of the call.
     *
     * @param frame the same object that {@link #update} got; must not be {@code null}
     */
    void render(FrameInfo frame);

    /**
     * Adds the demo's own text to the heads-up display: the numbers and switches that it wants to
     * show. The default adds nothing.
     *
     * @param hud the display, whose cursor is below the runner's own lines; must not be
     *     {@code null}
     * @param frame the same object that {@link #update} got; must not be {@code null}
     */
    default void hud(Hud hud, FrameInfo frame) {
    }

    /**
     * Adds the lines that only the demo can supply to the report that closes a scripted run, such
     * as the number of stalls of a ring. The default adds nothing.
     *
     * @param stats the statistics of the run, to which notes can be added; must not be
     *     {@code null}
     */
    default void report(Stats stats) {
    }

    /**
     * Deletes the GPU objects and releases everything that {@link #create} acquired. The context is
     * still current. The demo is not used again after this call.
     */
    void dispose();
}
