package vmath.util;

import vmath.annotations.Experimental;

/**
 * Frame-time statistics for an overlay or a benchmark: the frame rate, the typical and the worst
 * frame time and the hitches over the last frames, with the percentiles that a mean hides.
 *
 * <p>Call {@link #tick()} once per frame, at the same place (the start of the frame, or after the
 * buffer swap); the time between two calls is the frame time. With {@link #record(long)} the caller
 * gives the duration itself (a GPU timer, a time measured by another clock). The times are kept in
 * milliseconds in a {@link RollingStats} over the last {@code window} frames.
 *
 * <p>A mean frame rate of 60 can come from a smooth 16.7 ms or from mostly 10 ms with a stall of
 * half a second now and then, which feels very different. {@link #percentileMillis} shows the
 * difference ({@link #lowFps} is the figure that game reviews call "1% low": the frame rate at the
 * 99th percentile of the frame time) and {@link #hitches} counts the frames that took much longer
 * than the typical one.
 *
 * <p>Everything allocates nothing after construction. {@code tick()} reads
 * {@link System#nanoTime()}; {@link #tick(long)} takes the time from the caller, so a test or a
 * replay can drive it with its own clock.
 *
 * <p><b>Thread safety.</b> Not thread-safe: use it from the thread that draws the frames, or lock.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * FrameTimer timer = new FrameTimer(240);
 * // once per frame:
 * timer.tick();
 * String hud = String.format("%.0f fps, 1%% low %.0f, worst %.1f ms", timer.fps(), timer.lowFps(1), timer.maxMillis());
 * }</pre>
 */
@Experimental("the set of figures may grow")
public final class FrameTimer {

    private static final double NANOS_PER_MILLI = 1e6;

    private final RollingStats millis;
    private long previous;
    private boolean started;

    /**
     * Creates a timer over the last {@code window} frames.
     *
     * @param window how many frames to keep; at least 1
     * @throws IllegalArgumentException if {@code window} is below 1
     */
    public FrameTimer(int window) {
        millis = new RollingStats(window);
    }

    /**
     * Marks the start of a frame (or the end of the last one) at the current time. The first call
     * only starts the clock; every later call records the time since the one before.
     */
    public void tick() {
        tick(System.nanoTime());
    }

    /**
     * Marks a frame boundary at a time given by the caller, in nanoseconds on any clock that only
     * goes forward.
     *
     * @param nowNanos the time, in nanoseconds
     * @throws IllegalArgumentException if the time is before that of the previous call
     */
    public void tick(long nowNanos) {
        if (started) {
            if (nowNanos < previous) {
                throw new IllegalArgumentException("the clock went backwards: " + nowNanos + " after " + previous);
            }
            millis.add((nowNanos - previous) / NANOS_PER_MILLI);
        }
        previous = nowNanos;
        started = true;
    }

    /**
     * Records the duration of one frame.
     *
     * @param nanos the duration in nanoseconds; not negative
     * @throws IllegalArgumentException if {@code nanos} is negative
     */
    public void record(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("a duration cannot be negative: " + nanos);
        }
        millis.add(nanos / NANOS_PER_MILLI);
    }

    /**
     * Forgets the frames and the clock.
     */
    public void reset() {
        millis.reset();
        started = false;
    }

    /**
     * Counts the frames in the window.
     *
     * @return the number of frames held, at most the window
     */
    public int frames() {
        return millis.size();
    }

    /**
     * Counts every frame recorded since the creation or the last {@link #reset}.
     *
     * @return the number of frames ever recorded
     */
    public long totalFrames() {
        return millis.totalCount();
    }

    /**
     * Gives the average frame rate over the window, as the number of frames divided by their total
     * time (so a long frame weighs as much as it lasted).
     *
     * @return frames per second, or {@code NaN} if no frame has been recorded
     */
    public double fps() {
        double mean = millis.mean();
        return mean > 0 ? 1000.0 / mean : Double.NaN;
    }

    /**
     * Gives the average frame time over the window.
     *
     * @return milliseconds, or {@code NaN} if no frame has been recorded
     */
    public double averageMillis() {
        return millis.mean();
    }

    /**
     * Gives the median frame time, the typical frame.
     *
     * @return milliseconds, or {@code NaN} if no frame has been recorded
     */
    public double medianMillis() {
        return millis.percentile(50);
    }

    /**
     * Gives a percentile of the frame time over the window.
     *
     * @param p the percentage, from 0 to 100; 99 is the frame time that 99% of the frames beat
     * @return milliseconds, or {@code NaN} if no frame has been recorded
     * @throws IllegalArgumentException if {@code p} is outside 0 to 100
     */
    public double percentileMillis(double p) {
        return millis.percentile(p);
    }

    /**
     * Gives the shortest frame time in the window.
     *
     * @return milliseconds, or {@code NaN} if no frame has been recorded
     */
    public double minMillis() {
        return millis.min();
    }

    /**
     * Gives the longest frame time in the window.
     *
     * @return milliseconds, or {@code NaN} if no frame has been recorded
     */
    public double maxMillis() {
        return millis.max();
    }

    /**
     * Gives the frame rate of the slowest frames: the frame rate that corresponds to the
     * {@code 100 - percent} percentile of the frame time, so {@code lowFps(1)} is the "1% low"
     * (the rate of the frame that 99% of the frames beat) and {@code lowFps(0.1)} the "0.1% low".
     *
     * @param percent the share of the slowest frames, above 0 and at most 100
     * @return frames per second, or {@code NaN} if no frame has been recorded
     * @throws IllegalArgumentException if {@code percent} is not above 0 and at most 100
     */
    public double lowFps(double percent) {
        if (!(percent > 0 && percent <= 100)) {
            throw new IllegalArgumentException("the share of frames is above 0 and at most 100: " + percent);
        }
        double t = millis.percentile(100 - percent);
        return t > 0 ? 1000.0 / t : Double.NaN;
    }

    /**
     * Counts the frames in the window that took longer than {@code factor} times the median frame.
     *
     * @param factor the multiple of the median, above 1; 2 counts the frames that took twice the
     *     typical time
     * @return the number of such frames (0 for an empty window)
     * @throws IllegalArgumentException if {@code factor} is not above 1
     */
    public int hitches(double factor) {
        if (!(factor > 1)) {
            throw new IllegalArgumentException("the factor must be above 1: " + factor);
        }
        if (millis.size() == 0) {
            return 0;
        }
        return millis.countAbove(millis.percentile(50) * factor);
    }

    /**
     * Gives access to the frame times, in milliseconds, for percentiles in bulk or a graph.
     *
     * @return the statistics of the frame times; the timer's own object, not a copy
     */
    public RollingStats stats() {
        return millis;
    }
}
