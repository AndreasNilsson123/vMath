package vmath.util;

import vmath.annotations.Experimental;

/**
 * Statistics of the last {@code window} samples of a stream: the minimum, maximum, mean, standard
 * deviation and exact percentiles, for frame times, durations and any other number that a
 * diagnostic overlay or a benchmark wants a summary of.
 *
 * <p>A fixed ring holds the samples, so adding one never allocates and the oldest sample falls out
 * when the window is full. The queries work on the window as it is: {@link #min}, {@link #max} and
 * {@link #mean} take one pass, {@link #stdDev} two, and a percentile selects from a copy of the
 * window made in a scratch array that the object owns (a quickselect, on average linear in the window; asking for several percentiles with
 * {@link #percentiles} sorts the copy once). Nothing is cached, so a query is right after any
 * sequence of additions, and cost a few microseconds for a window of a few hundred samples.
 *
 * <p>Percentiles use linear interpolation between the closest ranks (the method that NumPy calls
 * {@code linear}, and Excel's {@code PERCENTILE.INC}): the 50th percentile of 1, 2, 3, 4 is 2.5, the
 * 0th is the minimum and the 100th the maximum. A window with one sample gives that sample for
 * every percentile. An empty window gives {@code NaN} for every statistic.
 *
 * <p>Samples must be finite: a NaN or an infinity would make every later statistic meaningless, so
 * {@link #add} refuses it.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one writer, and queries from the same thread (or with
 * your own lock).
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * RollingStats frameMillis = new RollingStats(240);                    // the last 240 frames
 * frameMillis.add(16.4);
 * frameMillis.add(16.9);
 * double typical = frameMillis.percentile(50), bad = frameMillis.percentile(99);
 * }</pre>
 */
@Experimental("the set of statistics may grow")
public final class RollingStats {

    private final double[] ring;
    private final double[] scratch;
    private int next;
    private int size;
    private long total;

    /**
     * Creates empty statistics over the last {@code window} samples.
     *
     * @param window how many samples to keep; at least 1
     * @throws IllegalArgumentException if {@code window} is below 1
     */
    public RollingStats(int window) {
        if (window < 1) {
            throw new IllegalArgumentException("the window must hold at least one sample: " + window);
        }
        ring = new double[window];
        scratch = new double[window];
    }

    /**
     * Adds a sample, dropping the oldest one if the window is full.
     *
     * @param value the sample; must be finite
     * @throws IllegalArgumentException if {@code value} is NaN or infinite
     */
    public void add(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("a sample must be finite: " + value);
        }
        ring[next] = value;
        next = next + 1 == ring.length ? 0 : next + 1;
        if (size < ring.length) {
            size++;
        }
        total++;
    }

    /**
     * Forgets every sample.
     */
    public void reset() {
        next = 0;
        size = 0;
        total = 0;
    }

    /**
     * Counts the samples in the window.
     *
     * @return the number of samples held, at most {@link #window()}
     */
    public int size() {
        return size;
    }

    /**
     * Gives the size of the window.
     *
     * @return the most samples held
     */
    public int window() {
        return ring.length;
    }

    /**
     * Counts every sample added since the creation or the last {@link #reset}, including those
     * that have left the window.
     *
     * @return the number of samples ever added
     */
    public long totalCount() {
        return total;
    }

    /**
     * Reads a sample of the window.
     *
     * @param i 0 for the oldest sample in the window, {@code size() - 1} for the newest
     * @return the sample
     * @throws IndexOutOfBoundsException if {@code i} is not below {@link #size()}
     */
    public double get(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("sample " + i + " of " + size);
        }
        int first = size < ring.length ? 0 : next;
        int at = first + i;
        return ring[at >= ring.length ? at - ring.length : at];
    }

    /**
     * Finds the smallest sample in the window.
     *
     * @return the minimum, or {@code NaN} if the window is empty
     */
    public double min() {
        if (size == 0) {
            return Double.NaN;
        }
        double m = ring[0];
        for (int i = 1; i < size; i++) {
            m = Math.min(m, ring[i]);
        }
        return m;
    }

    /**
     * Finds the largest sample in the window.
     *
     * @return the maximum, or {@code NaN} if the window is empty
     */
    public double max() {
        if (size == 0) {
            return Double.NaN;
        }
        double m = ring[0];
        for (int i = 1; i < size; i++) {
            m = Math.max(m, ring[i]);
        }
        return m;
    }

    /**
     * Averages the samples in the window.
     *
     * @return the arithmetic mean, or {@code NaN} if the window is empty
     */
    public double mean() {
        if (size == 0) {
            return Double.NaN;
        }
        double sum = 0, comp = 0; // Kahan: the window may hold thousands of samples of very different size
        for (int i = 0; i < size; i++) {
            double y = ring[i] - comp;
            double t = sum + y;
            comp = (t - sum) - y;
            sum = t;
        }
        return sum / size;
    }

    /**
     * Measures the spread of the samples in the window.
     *
     * @return the standard deviation of the window as a population (divided by the number of
     *     samples), or {@code NaN} if the window is empty; 0 for one sample
     */
    public double stdDev() {
        if (size == 0) {
            return Double.NaN;
        }
        double mean = mean(), sum = 0;
        for (int i = 0; i < size; i++) {
            double d = ring[i] - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / size);
    }

    /**
     * Finds a percentile of the window by linear interpolation between the closest ranks.
     *
     * @param p the percentage, from 0 (the minimum) to 100 (the maximum)
     * @return the percentile, or {@code NaN} if the window is empty
     * @throws IllegalArgumentException if {@code p} is NaN or outside 0 to 100
     */
    public double percentile(double p) {
        checkPercent(p);
        if (size == 0) {
            return Double.NaN;
        }
        System.arraycopy(ring, 0, scratch, 0, size);
        double rank = p / 100.0 * (size - 1);
        int lo = (int) Math.floor(rank);
        double frac = rank - lo;
        double a = select(scratch, size, lo);
        if (frac == 0.0 || lo + 1 >= size) {
            return a;
        }
        // after the selection everything above position lo is at least a: the next order statistic is the smallest of those
        double b = scratch[lo + 1];
        for (int i = lo + 2; i < size; i++) {
            b = Math.min(b, scratch[i]);
        }
        return a + frac * (b - a);
    }

    /**
     * Finds several percentiles with one sort of the window.
     *
     * @param percents the percentages, each from 0 to 100, in any order; must not be {@code null}
     * @param out receives the percentiles in the same order, at least as long as {@code percents};
     *     {@code NaN} everywhere if the window is empty; must not be {@code null}
     * @throws IllegalArgumentException if a percentage is NaN or outside 0 to 100, or {@code out} is
     *     shorter than {@code percents}
     */
    public void percentiles(double[] percents, double[] out) {
        if (out.length < percents.length) {
            throw new IllegalArgumentException("out holds " + out.length + " values, " + percents.length + " are asked for");
        }
        for (double p : percents) {
            checkPercent(p);
        }
        if (size == 0) {
            for (int i = 0; i < percents.length; i++) {
                out[i] = Double.NaN;
            }
            return;
        }
        System.arraycopy(ring, 0, scratch, 0, size);
        sort(scratch, size);
        for (int i = 0; i < percents.length; i++) {
            double rank = percents[i] / 100.0 * (size - 1);
            int lo = (int) Math.floor(rank);
            double frac = rank - lo;
            out[i] = frac == 0.0 || lo + 1 >= size ? scratch[lo] : scratch[lo] + frac * (scratch[lo + 1] - scratch[lo]);
        }
    }

    /**
     * Counts the samples in the window that are greater than a limit.
     *
     * @param limit the limit
     * @return the number of samples above {@code limit}
     */
    public int countAbove(double limit) {
        int n = 0;
        for (int i = 0; i < size; i++) {
            if (ring[i] > limit) {
                n++;
            }
        }
        return n;
    }

    private static void checkPercent(double p) {
        if (!(p >= 0 && p <= 100)) {
            throw new IllegalArgumentException("a percentage is from 0 to 100: " + p);
        }
    }

    /**
     * Quickselect (Hoare partition, median of three): rearranges {@code a[0, n)} so that position
     * {@code k} holds the value it would have in sorted order, everything before it is not greater
     * and everything after it is not smaller. Deterministic, no allocation.
     */
    private static double select(double[] a, int n, int k) {
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            // median of three to the middle
            if (a[mid] < a[lo]) {
                swap(a, mid, lo);
            }
            if (a[hi] < a[lo]) {
                swap(a, hi, lo);
            }
            if (a[hi] < a[mid]) {
                swap(a, hi, mid);
            }
            double pivot = a[mid];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) {
                    i++;
                }
                while (a[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    swap(a, i, j);
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                hi = j;
            } else if (k >= i) {
                lo = i;
            } else {
                return a[k];
            }
        }
        return a[k];
    }

    private static void sort(double[] a, int n) {
        for (int root = n / 2 - 1; root >= 0; root--) {
            siftDown(a, root, n);
        }
        for (int end = n - 1; end > 0; end--) {
            swap(a, 0, end);
            siftDown(a, 0, end);
        }
    }

    private static void siftDown(double[] a, int root, int end) {
        while (root * 2 + 1 < end) {
            int child = root * 2 + 1;
            if (child + 1 < end && Double.compare(a[child], a[child + 1]) < 0) {
                child++;
            }
            if (Double.compare(a[root], a[child]) >= 0) {
                return;
            }
            swap(a, root, child);
            root = child;
        }
    }

    private static void swap(double[] a, int i, int j) {
        double t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
