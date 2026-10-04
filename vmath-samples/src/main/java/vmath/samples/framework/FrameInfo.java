package vmath.samples.framework;

/**
 * What a demo needs to know about the current frame: its number, the time, the size of the
 * window and the input.
 *
 * <p>One instance lives for the whole run of a demo and is updated by the runner before every
 * frame, so reading it allocates nothing. In scripted mode the time step is fixed at 1/60 s and
 * the time is the frame number times that step, which is what makes a scripted run repeatable.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: the runner writes it and the demo reads it, both on
 * the render thread.
 */
public final class FrameInfo {

    /**
     * The fixed time step of a scripted run, in seconds.
     */
    public static final float SCRIPTED_DT = 1f / 60f;

    private final Input input;
    private int frame;
    private float dt;
    private double time;
    private int width = 1;
    private int height = 1;
    private boolean benchmark;

    /**
     * Creates the frame description for an input.
     *
     * @param input the input of the window; must not be {@code null}
     */
    FrameInfo(Input input) {
        this.input = input;
    }

    /**
     * Sets the values for the next frame.
     *
     * @param frame the number of the frame, counted from 0 at the first frame of the demo
     * @param dt the time since the previous frame in seconds
     * @param time the time since the demo started in seconds
     * @param width the width of the framebuffer in pixels; at least 1
     * @param height the height of the framebuffer in pixels; at least 1
     * @param benchmark whether this is a scripted run
     */
    void set(int frame, float dt, double time, int width, int height, boolean benchmark) {
        this.frame = frame;
        this.dt = dt;
        this.time = time;
        this.width = width;
        this.height = height;
        this.benchmark = benchmark;
    }

    /**
     * Reads the number of the frame.
     *
     * @return the frame number, 0 for the first frame of the demo (warm-up frames included)
     */
    public int frame() {
        return frame;
    }

    /**
     * Reads the time step.
     *
     * @return the seconds since the previous frame, at most 0.1 so that a pause does not throw the
     *     simulation; exactly {@link #SCRIPTED_DT} in a scripted run
     */
    public float dt() {
        return dt;
    }

    /**
     * Reads the time since the demo started.
     *
     * @return the seconds since the first frame
     */
    public double time() {
        return time;
    }

    /**
     * Reads the width of the framebuffer.
     *
     * @return the width in pixels; at least 1
     */
    public int width() {
        return width;
    }

    /**
     * Reads the height of the framebuffer.
     *
     * @return the height in pixels; at least 1
     */
    public int height() {
        return height;
    }

    /**
     * Reads the aspect ratio of the framebuffer.
     *
     * @return the width divided by the height
     */
    public float aspect() {
        return (float) width / height;
    }

    /**
     * Tells whether the run is scripted: the demo then follows its fixed path and ignores the
     * input.
     *
     * @return {@code true} in a scripted (benchmark) run
     */
    public boolean benchmark() {
        return benchmark;
    }

    /**
     * Reads the input.
     *
     * @return the keyboard and mouse state of this frame; never {@code null}
     */
    public Input input() {
        return input;
    }
}
