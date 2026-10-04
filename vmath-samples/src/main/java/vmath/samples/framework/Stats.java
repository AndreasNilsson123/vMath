package vmath.samples.framework;

import java.util.ArrayList;
import java.util.List;

/**
 * The numbers of a run: named series that a demo and the runner record once per frame (a time in
 * milliseconds, a count, a size) and that the report averages over the measured frames.
 *
 * <p>A series is registered once, in {@link Demo#create}, and recorded by its index, so
 * recording allocates nothing. The last value of every series is always kept for the heads-up
 * display; the sums that the report averages only grow while {@link #measuring()} is on, which the
 * runner switches on after the warm-up frames.
 *
 * <p>Internal: part of the samples, not of the library. The class does not use OpenGL.
 *
 * <p><b>Thread safety.</b> Not thread-safe: it is written and read by the render thread.
 */
public final class Stats {

    private final List<String> names = new ArrayList<>();
    private final List<String> units = new ArrayList<>();
    private final List<Integer> decimals = new ArrayList<>();
    private final List<String> descriptions = new ArrayList<>();
    private double[] sums = new double[8];
    private long[] counts = new long[8];
    private double[] lasts = new double[8];
    private final List<String> notes = new ArrayList<>();
    private boolean measuring;

    /**
     * Registers a series.
     *
     * @param name the name shown in the report; must not be {@code null}
     * @param unit the unit shown after the value, such as {@code ms} or {@code MB}; must not be
     *     {@code null}
     * @param decimals the number of decimals shown; 0 or more
     * @param description what is measured, in a few words; must not be {@code null}
     * @return the index to pass to {@link #record}
     */
    public int series(String name, String unit, int decimals, String description) {
        names.add(name);
        units.add(unit);
        this.decimals.add(decimals);
        descriptions.add(description);
        int index = names.size() - 1;
        if (index >= sums.length) {
            sums = java.util.Arrays.copyOf(sums, sums.length * 2);
            counts = java.util.Arrays.copyOf(counts, counts.length * 2);
            lasts = java.util.Arrays.copyOf(lasts, lasts.length * 2);
        }
        return index;
    }

    /**
     * Registers a series of times in milliseconds, which {@link #recordNanos} fills.
     *
     * @param name the name shown in the report; must not be {@code null}
     * @param description what is timed, in a few words; must not be {@code null}
     * @return the index to pass to {@link #recordNanos}
     */
    public int timer(String name, String description) {
        return series(name, "ms", 3, description);
    }

    /**
     * Records one value of a series for the current frame.
     *
     * @param series the index from {@link #series}
     * @param value the value in the unit of the series
     */
    public void record(int series, double value) {
        lasts[series] = value;
        if (measuring) {
            sums[series] += value;
            counts[series]++;
        }
    }

    /**
     * Records a duration into a series of milliseconds.
     *
     * @param series the index from {@link #timer}
     * @param nanos the duration in nanoseconds
     */
    public void recordNanos(int series, long nanos) {
        record(series, nanos / 1e6);
    }

    /**
     * Reads the value recorded last, for the heads-up display.
     *
     * @param series the index of the series
     * @return the last value, 0 if none was recorded
     */
    public double last(int series) {
        return lasts[series];
    }

    /**
     * Reads the average of the measured frames.
     *
     * @param series the index of the series
     * @return the mean of the values recorded while measuring, or {@code NaN} if there are none
     */
    public double average(int series) {
        return counts[series] == 0 ? Double.NaN : sums[series] / counts[series];
    }

    /**
     * Reads how many values were recorded while measuring.
     *
     * @param series the index of the series
     * @return the number of samples
     */
    public long samples(int series) {
        return counts[series];
    }

    /**
     * Counts the series.
     *
     * @return the number of registered series
     */
    public int size() {
        return names.size();
    }

    /**
     * Finds a series by its name.
     *
     * @param name the name it was registered with; must not be {@code null}
     * @return the index of the series, or -1 if there is none of that name
     */
    public int indexOf(String name) {
        return names.indexOf(name);
    }

    /**
     * Reads the name of a series.
     *
     * @param series the index of the series
     * @return the name; never {@code null}
     */
    public String name(int series) {
        return names.get(series);
    }

    /**
     * Reads the unit of a series.
     *
     * @param series the index of the series
     * @return the unit; never {@code null}
     */
    public String unit(int series) {
        return units.get(series);
    }

    /**
     * Reads the number of decimals of a series.
     *
     * @param series the index of the series
     * @return the decimals that the report prints
     */
    public int decimals(int series) {
        return decimals.get(series);
    }

    /**
     * Reads the description of a series.
     *
     * @param series the index of the series
     * @return the description; never {@code null}
     */
    public String description(int series) {
        return descriptions.get(series);
    }

    /**
     * Switches the accumulation on or off.
     *
     * @param measuring whether recorded values count towards the averages
     */
    public void setMeasuring(boolean measuring) {
        this.measuring = measuring;
    }

    /**
     * Tells whether recorded values count towards the averages.
     *
     * @return {@code true} after the warm-up
     */
    public boolean measuring() {
        return measuring;
    }

    /**
     * Adds a line of free text to the report, for a fact that is not a series.
     *
     * @param note the text; must not be {@code null}
     */
    public void note(String note) {
        notes.add(note);
    }

    /**
     * Reads the notes in the order they were added.
     *
     * @return an unmodifiable view of the notes
     */
    public List<String> notes() {
        return java.util.Collections.unmodifiableList(notes);
    }
}
