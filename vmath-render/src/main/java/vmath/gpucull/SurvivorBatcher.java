package vmath.gpucull;

import java.util.Arrays;
import vmath.annotations.Experimental;
import vmath.bulk.VisibilitySet;
import vmath.gl.DrawList;

/**
 * The output stage of CPU culling: groups the objects that survived by the draw they belong to.
 *
 * <p>The compute shaders of {@link GpuCullGlsl} write, for every draw, the count of its surviving
 * instances and a list of their object indices. This class does the same on the CPU from a
 * {@link VisibilitySet} (what the kernels of {@code vmath.spatial} produce) and the draw of every
 * object: {@link #batch} sets the instance count and the base instance of every draw of a
 * {@link DrawList} so that its instances are consecutive, and {@link #survivors()} holds the
 * object indices in that order, the buffer that the vertex shader reads per instance. A counting
 * sort, so the objects of a draw stay in ascending order and the cost is two passes over the
 * survivors.
 *
 * <p>An optional capacity per draw limits the instances of a draw like the capacity of the GPU
 * path does: the first ones in object order are kept and the rest are counted in
 * {@link #overflow()}.
 *
 * <p>The arrays are reused, so a call allocates nothing once they have grown to size.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one call at a time per instance.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * SurvivorBatcher batcher = new SurvivorBatcher();
 * batcher.batch(visible, objectCount, drawOfObject, draws, null);
 * int[] objects = batcher.survivors();          // upload the first batcher.survivorCount() values
 * }</pre>
 */
@Experimental("the CPU culling path may change")
public final class SurvivorBatcher {

    private int[] survivors = new int[16];
    private int survivorCount;
    private int overflow;
    private int[] count = new int[16];
    private int[] cursor = new int[16];

    /**
     * Creates a batcher.
     */
    public SurvivorBatcher() {
    }

    /**
     * Groups the visible objects by draw and sets the instance counts and base instances of the
     * draws.
     *
     * @param visible the objects that survived; must not be {@code null}
     * @param objectCount the number of objects: bits at this index and above are ignored
     * @param drawOfObject the index of the draw of every object, at least {@code objectCount}
     *     values; only the entries of visible objects are read; must not be {@code null}
     * @param draws the draws; their instance counts and base instances are overwritten; must not
     *     be {@code null}
     * @param capacity the most instances of each draw, one value per draw, or {@code null} for no
     *     limit
     * @return the number of survivors that were kept
     * @throws IllegalArgumentException if a visible object has a draw index outside the list, an
     *     array is too short, or a capacity is negative
     */
    public int batch(VisibilitySet visible, int objectCount, int[] drawOfObject, DrawList draws, int[] capacity) {
        int drawCount = draws.size();
        if (drawOfObject.length < objectCount) {
            throw new IllegalArgumentException("drawOfObject has " + drawOfObject.length + " entries for " + objectCount + " objects");
        }
        if (capacity != null && capacity.length < drawCount) {
            throw new IllegalArgumentException("capacity has " + capacity.length + " entries for " + drawCount + " draws");
        }
        if (count.length < drawCount) {
            count = new int[Math.max(drawCount, count.length * 2)];
            cursor = new int[count.length];
        }
        Arrays.fill(count, 0, drawCount, 0);
        overflow = 0;
        // pass 1: how many survivors each draw keeps
        for (int i = visible.nextSetBit(0); i >= 0 && i < objectCount; i = visible.nextSetBit(i + 1)) {
            int d = drawOfObject[i];
            if (d < 0 || d >= drawCount) {
                throw new IllegalArgumentException("object " + i + " belongs to draw " + d + " of " + drawCount);
            }
            if (capacity != null && count[d] >= capacity[d]) {
                overflow++;
            } else {
                count[d]++;
            }
        }
        int total = 0;
        for (int d = 0; d < drawCount; d++) {
            cursor[d] = total;
            draws.setBaseInstance(d, total);
            draws.setInstanceCount(d, count[d]);
            total += count[d];
        }
        if (survivors.length < total) {
            survivors = new int[Math.max(total, survivors.length * 2)];
        }
        // pass 2: the same decision again, now writing; the first ones in object order are kept
        for (int d = 0; d < drawCount; d++) {
            count[d] = 0;
        }
        for (int i = visible.nextSetBit(0); i >= 0 && i < objectCount; i = visible.nextSetBit(i + 1)) {
            int d = drawOfObject[i];
            int kept = count[d];
            int limit = draws.instanceCount(d);
            if (kept < limit) {
                survivors[cursor[d] + kept] = i;
                count[d] = kept + 1;
            }
        }
        survivorCount = total;
        return total;
    }

    /**
     * Gives the surviving objects, draw after draw. Only the first {@link #survivorCount()} values
     * are meaningful, and the array is replaced when it has to grow.
     *
     * @return the object indices; draw {@code d} owns the values from its base instance on, as
     *     many as its instance count
     */
    public int[] survivors() {
        return survivors;
    }

    /**
     * Counts the survivors that the last call kept.
     *
     * @return the number of values to use in {@link #survivors()}
     */
    public int survivorCount() {
        return survivorCount;
    }

    /**
     * Counts the survivors that the last call dropped because their draw was full.
     *
     * @return the number of dropped survivors
     */
    public int overflow() {
        return overflow;
    }
}
