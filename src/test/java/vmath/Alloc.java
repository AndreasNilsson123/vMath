package vmath;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;

/**
 * Measures how many bytes a piece of code allocates on the calling thread, to enforce the library's "hot paths allocate nothing" contract in
 * ordinary unit tests (JMH's {@code -prof gc} measures the same thing but is run by hand).
 *
 * <p>The figure is the thread's allocated bytes before and after a loop of calls, read from {@code com.sun.management.ThreadMXBean}. The
 * code is run {@code warmup} times first so that the JIT has compiled it (and escape analysis has removed short-lived temporaries): an
 * allocation that only disappears after compilation is fine, one that stays is what this catches.
 */
public final class Alloc {

    /** Average bytes per call allowed before a path counts as allocating: a few stray bytes over a whole run are measurement noise. */
    public static final double SLACK_BYTES_PER_CALL = 0.25;

    private static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private Alloc() {
    }

    /** Average bytes allocated per call of {@code r}, after {@code warmup} untimed calls, over {@code calls} calls. */
    public static double bytesPerCall(Runnable r, int warmup, int calls) {
        long id = Thread.currentThread().threadId();
        for (int i = 0; i < warmup; i++) {
            r.run();
        }
        long before = MX.getThreadAllocatedBytes(id);
        for (int i = 0; i < calls; i++) {
            r.run();
        }
        long after = MX.getThreadAllocatedBytes(id);
        return (double) (after - before) / calls;
    }

    /** True when the tests run on the {@code -Pvalhalla} build (value records on a JDK with JEP 401), see {@link #assertNoAllocationPerElement}. */
    public static final boolean VALHALLA = Boolean.getBoolean("vmath.valhalla");

    /**
     * The contract for paths that pass a value record into a call the JIT does not inline. On a plain JVM that costs nothing and this is the strict
     * {@link #assertNoAllocation}. On the Valhalla early-access build a value record read from a flattened field is buffered (copied to the heap) when it
     * crosses such a call, which costs a small constant per call; what must still hold there is that the allocation <b>does not grow with the amount of data</b>.
     *
     * @param small the path over a small data set
     * @param large the same path over a data set at least four times bigger
     */
    public static void assertNoAllocationPerElement(String name, int warmup, int calls, Runnable small, Runnable large) {
        if (!VALHALLA) {
            assertNoAllocation(name + " (small data)", warmup, calls, small);
            assertNoAllocation(name + " (large data)", warmup, calls, large);
            return;
        }
        double s = bytesPerCall(small, warmup, calls);
        double l = bytesPerCall(large, warmup, calls);
        assertTrue(s <= 1024.0, name + " allocates " + s + " bytes per call on the value-record build: more than a per-call buffer");
        assertTrue(l <= s + 8.0, name + " allocates " + l + " bytes per call over four times the data but " + s + " over the small set: it grows with the data");
    }

    /**
     * Fails if {@code r} allocates more than {@link #SLACK_BYTES_PER_CALL} on average. The message names the path and the measured figure,
     * so a failure says which call started allocating and by how much.
     *
     * @param warmup calls before measuring (use at least ~20 000 for cheap paths so that C2 has compiled them)
     * @param calls  calls measured; must be at least 2 000 so that the slack allows no real allocation
     */
    public static void assertNoAllocation(String name, int warmup, int calls, Runnable r) {
        if (calls < 2_000) {
            throw new IllegalArgumentException("measure at least 2000 calls, or the slack would hide a real allocation");
        }
        double bytes = bytesPerCall(r, warmup, calls);
        assertTrue(bytes <= SLACK_BYTES_PER_CALL, name + " allocates " + bytes + " bytes per call (allowed " + SLACK_BYTES_PER_CALL + ")");
    }
}
