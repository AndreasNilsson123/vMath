package vmath.lines;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vmath.annotations.Experimental;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.gl.DrawList;
import vmath.gl.StructArrayAccess;
import vmath.mem.FreeListAllocator;

/**
 * Polylines that are added, edited and removed while the program runs, written to the buffers of a
 * {@link LineRenderPlan} so that only what changed has to be uploaded.
 *
 * <p>Every polyline has a <b>handle</b>, a {@code long} that stays valid until the polyline is removed
 * (a handle of a removed polyline is never valid again, even when its slot is reused). The records of
 * a polyline (a segment record per segment, or a vertex per point for the hairline strategy) live in a
 * <b>slot</b> of the data buffer that a {@link FreeListAllocator} hands out, so removing a polyline
 * frees its slot and the next one can take it. {@link #update} writes the records of the polylines
 * that changed since the last update and reports the byte ranges it wrote ({@link #dirtyRangeCount}):
 * those are the ranges to upload. It also rebuilds the draw list and the style table, which are small
 * (a few numbers per run of polylines, 64 bytes per style) and are written completely every time.
 *
 * <p>The data buffer is the one of the plan's strategy and holds {@link #capacityUnits} records.
 * When the free space is split into pieces too small for a new polyline, {@link #compact} moves
 * the polylines together (it is done by itself when that would let an addition succeed); {@link
 * #grow} makes room for more. Both rewrite what moved, and only that. Consecutive polylines of equal
 * style that sit next to each other in the buffer are one draw, as in {@link LineRenderPlan#write},
 * so compaction also lowers the number of draws.
 *
 * <p>The behaviour is the same in every strategy: the tests compare what each strategy draws after
 * any sequence of additions, edits and removals with the reference expansion of the same
 * polylines ({@link #toBatch}).
 *
 * <p>Polylines are drawn by the layer of their style and, within a layer, in the order they were
 * added. Positions are in double precision with an origin as in {@link LineBatch}; moving the origin
 * rewrites everything.
 *
 * <p><b>Allocation.</b> Steady state (edits that keep the number of points, updates, removals)
 * allocates nothing; additions allocate when a table has to grow or a polyline needs more room than
 * its slot of coordinates had.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one thread at a time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * LineRenderPlan plan = LineRenderPlan.choose(caps);
 * LineSet set = new LineSet(plan, 100_000);                     // room for 100000 segments
 * MemorySegment data = allocate(set.dataBytes()), styles = allocate(set.styleBytes());
 * DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 256);
 *
 * long track = set.add(xyz, 0, 3, false, LineStyle.pixels(2f).withColor(0x00FF00FF));
 * set.update(data, styles, draws);                              // writes the new records
 * for (int i = 0; i < set.dirtyRangeCount(); i++) {
 *     upload(data, set.dirtyOffset(i), set.dirtyLength(i));     // only what was written
 * }
 * set.remove(track);
 * }</pre>
 */
@Experimental("the dynamic set may change")
public final class LineSet {

    /** A value that is not the handle of any polyline. */
    public static final long NONE = 0L;

    private final LineRenderPlan plan;
    private final long unitBytes;
    private FreeListAllocator allocator;
    private int capacity;
    private double originX;
    private double originY;
    private double originZ;

    // per slot
    private double[][] pts = new double[16][];
    private int[] pointCounts = new int[16];
    private boolean[] closedFlags = new boolean[16];
    private int[] styleOf = new int[16];
    private int[] generation = new int[16];
    private boolean[] live = new boolean[16];
    private boolean[] dirtyFlag = new boolean[16];
    private long[] unitOffset = new long[16];
    private int[] unitCount = new int[16];
    private long[] sequence = new long[16];
    private double[] bounds = new double[16 * 6];
    private int slots;
    private int[] freeSlots = new int[16];
    private int freeSlotCount;
    private int liveCount;
    private long nextSequence = 1;
    private long usedUnits;

    // draw order: slots by layer, then by sequence
    private int[] order = new int[16];
    private int orderSize;

    // styles
    private final List<LineStyle> styleTable = new ArrayList<>();
    private final Map<LineStyle, Integer> styleIndex = new HashMap<>();
    private int[] styleRefs = new int[8];
    private int[] freeStyles = new int[8];
    private int freeStyleCount;

    // what changed
    private int[] dirtySlots = new int[16];
    private int dirtyCount;
    private boolean everythingDirty = true;
    private long[] ranges = new long[16];
    private int rangeCount;

    private double[] scratch = new double[48];
    private final SegmentFeed feed = new SegmentFeed();

    /**
     * Creates an empty set for a plan.
     *
     * @param plan the plan whose strategy decides the records; must not be {@code null}
     * @param capacityUnits the number of records the data buffer holds: segments (for a closed
     *     polyline as many as points, for an open one one fewer) or, for the hairline strategy,
     *     vertices (the points, and one more for a closed polyline); at least 1
     * @throws IllegalArgumentException if the capacity is below 1
     */
    public LineSet(LineRenderPlan plan, int capacityUnits) {
        if (capacityUnits < 1) {
            throw new IllegalArgumentException("the capacity must be at least 1: " + capacityUnits);
        }
        this.plan = java.util.Objects.requireNonNull(plan);
        this.unitBytes = plan.unitBytes();
        this.capacity = capacityUnits;
        this.allocator = new FreeListAllocator(capacityUnits, FreeListAllocator.Strategy.BEST_FIT);
    }

    // ---------------------------------------------------------------- origin

    /**
     * Moves the origin that positions are made relative to; every record is rewritten by the next
     * update.
     *
     * @param x the x coordinate of the origin
     * @param y the y coordinate of the origin
     * @param z the z coordinate of the origin
     * @throws IllegalArgumentException if a coordinate is not finite
     */
    public void setOrigin(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("the origin must be finite");
        }
        if (x != originX || y != originY || z != originZ) {
            originX = x;
            originY = y;
            originZ = z;
            everythingDirty = true;
        }
    }

    /**
     * Gives the x coordinate of the origin.
     *
     * @return the origin x
     */
    public double originX() {
        return originX;
    }

    /**
     * Gives the y coordinate of the origin.
     *
     * @return the origin y
     */
    public double originY() {
        return originY;
    }

    /**
     * Gives the z coordinate of the origin.
     *
     * @return the origin z
     */
    public double originZ() {
        return originZ;
    }

    /**
     * Tells whether the camera is so far from the origin that the origin should be moved.
     *
     * @param cameraX the x coordinate of the camera
     * @param cameraY the y coordinate of the camera
     * @param cameraZ the z coordinate of the camera
     * @param limit the largest distance to accept, in world units
     * @return {@code true} if the distance from the origin to the camera is above the limit
     */
    public boolean originTooFar(double cameraX, double cameraY, double cameraZ, double limit) {
        double dx = cameraX - originX, dy = cameraY - originY, dz = cameraZ - originZ;
        return dx * dx + dy * dy + dz * dz > limit * limit;
    }

    /**
     * Makes the view-projection matrix of a frame relative to the origin, in double precision (see
     * {@link LineBatch#relativeViewProjection}).
     *
     * @param viewProjection the matrix in world coordinates, column-major, 16 values
     * @param out receives the result, 16 values
     * @throws IllegalArgumentException if an array is too short
     */
    public void relativeViewProjection(double[] viewProjection, float[] out) {
        LineBatch.relativeMatrix(viewProjection, out, originX, originY, originZ);
    }

    // ---------------------------------------------------------------- handles

    private int slotOf(long handle) {
        int index = (int) handle - 1;
        int gen = (int) (handle >>> 32);
        if (handle == NONE || index < 0 || index >= slots || !live[index] || generation[index] != gen) {
            throw new IllegalArgumentException("not the handle of a polyline of this set (removed, or from another set): " + handle);
        }
        return index;
    }

    /**
     * Tells whether a handle belongs to a polyline that is in the set.
     *
     * @param handle a handle
     * @return {@code true} until the polyline is removed
     */
    public boolean contains(long handle) {
        int index = (int) handle - 1;
        return handle != NONE && index >= 0 && index < slots && live[index] && generation[index] == (int) (handle >>> 32);
    }

    // ---------------------------------------------------------------- styles

    private int acquireStyle(LineStyle style) {
        Integer existing = styleIndex.get(style);
        if (existing != null) {
            styleRefs[existing]++;
            return existing;
        }
        int index;
        if (freeStyleCount > 0) {
            index = freeStyles[--freeStyleCount];
            styleTable.set(index, style);
        } else {
            index = styleTable.size();
            if (index > LineGpu.MAX_STYLES) {
                throw new IllegalStateException("too many distinct styles: " + index);
            }
            styleTable.add(style);
            if (index == styleRefs.length) {
                styleRefs = Arrays.copyOf(styleRefs, index * 2);
            }
        }
        styleRefs[index] = 1;
        styleIndex.put(style, index);
        return index;
    }

    private void releaseStyle(int index) {
        if (--styleRefs[index] == 0) {
            styleIndex.remove(styleTable.get(index));
            if (freeStyleCount == freeStyles.length) {
                freeStyles = Arrays.copyOf(freeStyles, freeStyleCount * 2);
            }
            freeStyles[freeStyleCount++] = index;
        }
    }

    // ---------------------------------------------------------------- draw order

    private int layerOf(int slot) {
        return styleTable.get(styleOf[slot]).layer();
    }

    private int compare(int a, int b) {
        int c = Integer.compare(layerOf(a), layerOf(b));
        return c != 0 ? c : Long.compare(sequence[a], sequence[b]);
    }

    /** The position of the first entry of the order that sorts after the slot (or the slot itself when it is in the order). */
    private int search(int slot) {
        int lo = 0, hi = orderSize;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(order[mid], slot) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void orderInsert(int slot) {
        int at = search(slot);
        if (orderSize == order.length) {
            order = Arrays.copyOf(order, orderSize * 2);
        }
        System.arraycopy(order, at, order, at + 1, orderSize - at);
        order[at] = slot;
        orderSize++;
    }

    private void orderRemove(int slot) {
        int at = search(slot);
        System.arraycopy(order, at + 1, order, at, orderSize - at - 1);
        orderSize--;
    }

    // ---------------------------------------------------------------- dirty

    private void markDirty(int slot) {
        if (!dirtyFlag[slot]) {
            dirtyFlag[slot] = true;
            if (dirtyCount == dirtySlots.length) {
                dirtySlots = Arrays.copyOf(dirtySlots, dirtyCount * 2);
            }
            dirtySlots[dirtyCount++] = slot;
        }
    }

    /**
     * Makes the next update rewrite every record, for example because the data buffer is a new
     * one.
     */
    public void invalidate() {
        everythingDirty = true;
    }

    // ---------------------------------------------------------------- space

    private long allocateUnits(int units) {
        long offset = allocator.allocate(units, 1);
        if (offset == FreeListAllocator.NONE && allocator.freeBytes() >= units) {
            compact();
            offset = allocator.allocate(units, 1);
        }
        if (offset == FreeListAllocator.NONE) {
            throw new IllegalStateException("the set is full: " + units + " more records are needed, " + allocator.freeBytes() + " of " + capacity + " are free; call grow()");
        }
        usedUnits += units;
        return offset;
    }

    /**
     * Counts the records that the data buffer holds.
     *
     * @return the capacity, in segments (or in vertices for the hairline strategy)
     */
    public int capacityUnits() {
        return capacity;
    }

    /**
     * Counts the records in use.
     *
     * @return the records of all polylines
     */
    public long usedUnits() {
        return usedUnits;
    }

    /**
     * Gives the size of the largest piece of free space, which is the longest polyline that can be
     * added without a compaction.
     *
     * @return the largest free piece, in records
     */
    public long largestFreeUnits() {
        return allocator.largestFree();
    }

    /**
     * Moves the polylines together so that the free space is one piece, in drawing order. Only the
     * polylines that moved are rewritten by the next update.
     */
    public void compact() {
        repack(capacity);
    }

    /**
     * Makes room for more records: the polylines are moved to a bigger range, in drawing order, and
     * the data buffer has to be at least {@link #dataBytes} long again.
     *
     * @param newCapacityUnits the new capacity, more than the present one
     * @throws IllegalArgumentException if it is not more than {@link #capacityUnits}
     */
    public void grow(int newCapacityUnits) {
        if (newCapacityUnits <= capacity) {
            throw new IllegalArgumentException("the new capacity " + newCapacityUnits + " must be more than " + capacity);
        }
        repack(newCapacityUnits);
        everythingDirty = true; // the buffer is a new one
    }

    private void repack(int newCapacity) {
        FreeListAllocator fresh = newCapacity == capacity ? allocator : new FreeListAllocator(newCapacity, FreeListAllocator.Strategy.BEST_FIT);
        if (fresh == allocator) {
            allocator.reset();
        }
        for (int i = 0; i < orderSize; i++) {
            int slot = order[i];
            if (unitCount[slot] == 0) {
                continue; // being resized: it gets its new place from the caller
            }
            long offset = fresh.allocate(unitCount[slot], 1);
            if (offset != unitOffset[slot]) {
                unitOffset[slot] = offset;
                markDirty(slot);
            }
        }
        allocator = fresh;
        capacity = newCapacity;
    }

    // ---------------------------------------------------------------- the polylines

    private void ensureSlot() {
        if (freeSlotCount == 0 && slots == pts.length) {
            int n = slots * 2;
            pts = Arrays.copyOf(pts, n);
            pointCounts = Arrays.copyOf(pointCounts, n);
            closedFlags = Arrays.copyOf(closedFlags, n);
            styleOf = Arrays.copyOf(styleOf, n);
            generation = Arrays.copyOf(generation, n);
            live = Arrays.copyOf(live, n);
            dirtyFlag = Arrays.copyOf(dirtyFlag, n);
            unitOffset = Arrays.copyOf(unitOffset, n);
            unitCount = Arrays.copyOf(unitCount, n);
            sequence = Arrays.copyOf(sequence, n);
            bounds = Arrays.copyOf(bounds, n * 6);
        }
    }

    private int copyPoints(double[] xyz, int offset, int pointCount, boolean closed) {
        if (pointCount < 0 || offset < 0 || xyz.length < offset + 3L * pointCount) {
            throw new IllegalArgumentException("the array has " + xyz.length + " values for " + pointCount + " points at " + offset);
        }
        if (scratch.length < 3 * pointCount) {
            scratch = new double[Math.max(scratch.length * 2, 3 * pointCount)];
        }
        return LineBatch.copyDistinct(xyz, offset, pointCount, closed, scratch, 0);
    }

    private void store(int slot, int kept) {
        if (pts[slot] == null || pts[slot].length < 3 * kept) {
            pts[slot] = new double[3 * kept];
        }
        System.arraycopy(scratch, 0, pts[slot], 0, 3 * kept);
        pointCounts[slot] = kept;
        LineBatch.boundsOf(scratch, 0, kept, bounds, slot * 6);
    }

    /**
     * Adds a polyline.
     *
     * @param xyz the points as {@code x, y, z} triples in world coordinates; copied; must not be
     *     {@code null}
     * @param offset the index in {@code xyz} of the first value
     * @param pointCount the number of points
     * @param closed whether the last point is joined to the first
     * @param style the style; must not be {@code null}
     * @return the handle of the polyline, which is never {@link #NONE}
     * @throws IllegalArgumentException if there are too few distinct points, a coordinate is not
     *     finite, or the array is too short
     * @throws IllegalStateException if there is no room for the polyline even after a compaction:
     *     call {@link #grow}
     */
    public long add(double[] xyz, int offset, int pointCount, boolean closed, LineStyle style) {
        java.util.Objects.requireNonNull(style);
        int kept = copyPoints(xyz, offset, pointCount, closed);
        int units = plan.unitsOf(kept, closed);
        long where = allocateUnits(units);
        ensureSlot();
        int slot = freeSlotCount > 0 ? freeSlots[--freeSlotCount] : slots++;
        store(slot, kept);
        closedFlags[slot] = closed;
        styleOf[slot] = acquireStyle(style);
        live[slot] = true;
        unitOffset[slot] = where;
        unitCount[slot] = units;
        sequence[slot] = nextSequence++;
        orderInsert(slot);
        markDirty(slot);
        liveCount++;
        return (long) generation[slot] << 32 | (slot + 1);
    }

    /**
     * Replaces the points of a polyline, keeping its handle and its style. If the number of
     * records stays the same the polyline is rewritten where it is.
     *
     * @param handle the handle
     * @param xyz the new points as triples; copied
     * @param offset the index in {@code xyz} of the first value
     * @param pointCount the number of points
     * @param closed whether the last point is joined to the first
     * @throws IllegalArgumentException if the handle is not valid, the points are too few or not
     *     finite, or the array is too short; nothing changes
     * @throws IllegalStateException if there is no room for the longer polyline: nothing changes
     */
    public void set(long handle, double[] xyz, int offset, int pointCount, boolean closed) {
        int slot = slotOf(handle);
        int kept = copyPoints(xyz, offset, pointCount, closed);
        int units = plan.unitsOf(kept, closed);
        if (units != unitCount[slot]) {
            if (allocator.freeBytes() + unitCount[slot] < units) {
                throw new IllegalStateException("the set is full: " + units + " records are needed, " + (allocator.freeBytes() + unitCount[slot]) + " are available; call grow()");
            }
            allocator.free(unitOffset[slot]);
            usedUnits -= unitCount[slot];
            unitCount[slot] = 0;
            long where = allocateUnits(units);
            unitOffset[slot] = where;
            unitCount[slot] = units;
        }
        store(slot, kept);
        closedFlags[slot] = closed;
        markDirty(slot);
    }

    /**
     * Gives a polyline another style, which can move it to another layer (it keeps its place in
     * the order of adding within the layer).
     *
     * @param handle the handle
     * @param style the new style; must not be {@code null}
     * @throws IllegalArgumentException if the handle is not valid
     */
    public void setStyle(long handle, LineStyle style) {
        java.util.Objects.requireNonNull(style);
        int slot = slotOf(handle);
        if (styleTable.get(styleOf[slot]).equals(style)) {
            return;
        }
        orderRemove(slot);
        int old = styleOf[slot];
        styleOf[slot] = acquireStyle(style);
        releaseStyle(old);
        orderInsert(slot);
        markDirty(slot);
    }

    /**
     * Removes a polyline and frees its records.
     *
     * @param handle the handle
     * @return {@code true} if it was removed, {@code false} if the handle was not valid (already
     *     removed, for example)
     */
    public boolean remove(long handle) {
        if (!contains(handle)) {
            return false;
        }
        int slot = (int) handle - 1;
        orderRemove(slot);
        allocator.free(unitOffset[slot]);
        usedUnits -= unitCount[slot];
        releaseStyle(styleOf[slot]);
        live[slot] = false;
        dirtyFlag[slot] = false;
        generation[slot]++;
        if (generation[slot] == 0) {
            generation[slot] = 1;
        }
        if (freeSlotCount == freeSlots.length) {
            freeSlots = Arrays.copyOf(freeSlots, freeSlotCount * 2);
        }
        freeSlots[freeSlotCount++] = slot;
        liveCount--;
        return true;
    }

    /**
     * Removes everything; the origin and the memory stay.
     */
    public void clear() {
        for (int i = orderSize - 1; i >= 0; i--) {
            remove((long) generation[order[i]] << 32 | (order[i] + 1));
        }
        everythingDirty = true;
    }

    /**
     * Counts the polylines.
     *
     * @return the number of polylines in the set
     */
    public int size() {
        return liveCount;
    }

    /**
     * Counts the distinct styles in use.
     *
     * @return the number of styles
     */
    public int styleCount() {
        return styleTable.size() - freeStyleCount;
    }

    /**
     * Copies the bounding box of a polyline (of its points, not of its width).
     *
     * @param handle the handle
     * @param out receives {@code minX, minY, minZ, maxX, maxY, maxZ}; must have room for 6 values
     * @throws IllegalArgumentException if the handle is not valid
     */
    public void bounds(long handle, double[] out) {
        int slot = slotOf(handle);
        System.arraycopy(bounds, slot * 6, out, 0, 6);
    }

    /**
     * Counts the points of a polyline, after the removal of repeated points.
     *
     * @param handle the handle
     * @return the number of points
     * @throws IllegalArgumentException if the handle is not valid
     */
    public int pointCount(long handle) {
        return pointCounts[slotOf(handle)];
    }

    /**
     * Gives the number of slots, the range of the indices that {@link #slot} returns and of the
     * bits of a visibility set; slots of removed polylines are included until they are reused.
     *
     * @return the number of slots
     */
    public int slotCount() {
        return slots;
    }

    /**
     * Gives the slot of a polyline, a small index that stays the same while it is in the set.
     *
     * @param handle the handle
     * @return the slot, 0 to {@link #slotCount} exclusive
     * @throws IllegalArgumentException if the handle is not valid
     */
    public int slot(long handle) {
        return slotOf(handle);
    }

    /**
     * Tells whether a slot holds a polyline.
     *
     * @param slot the slot
     * @return {@code true} if a polyline is in it
     * @throws IndexOutOfBoundsException if the slot is not below {@link #slotCount}
     */
    public boolean isSlotLive(int slot) {
        if (slot < 0 || slot >= slots) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + slots);
        }
        return live[slot];
    }

    /**
     * Writes the bounding boxes of the polylines for culling, indexed by slot and relative to the
     * origin as {@code float}s (rounded outwards by the margin, which should cover half the width of
     * the line in world units). An empty slot gets an empty box at the origin.
     *
     * @param out receives {@link #slotCount} boxes, after being cleared; must not be {@code null}
     * @param margin the distance to add on every side, at least 0
     * @return the number of boxes
     * @throws IllegalArgumentException if the margin is negative or not finite
     */
    public int fillBounds(BoundsArray out, float margin) {
        if (!(margin >= 0f) || Float.isInfinite(margin)) {
            throw new IllegalArgumentException("the margin must be finite and not negative: " + margin);
        }
        out.clear();
        for (int slot = 0; slot < slots; slot++) {
            if (!live[slot]) {
                out.add(0f, 0f, 0f, 0f, 0f, 0f);
                continue;
            }
            int b = slot * 6;
            out.add((float) (bounds[b] - originX) - margin, (float) (bounds[b + 1] - originY) - margin, (float) (bounds[b + 2] - originZ) - margin,
                    (float) (bounds[b + 3] - originX) + margin, (float) (bounds[b + 4] - originY) + margin, (float) (bounds[b + 5] - originZ) + margin);
        }
        return slots;
    }

    /**
     * Copies the set into a {@link LineBatch}, polylines in drawing order: what the reference
     * expansion and the tests compare the strategies with.
     *
     * @return a new batch with the same origin
     */
    public LineBatch toBatch() {
        LineBatch batch = new LineBatch();
        batch.setOrigin(originX, originY, originZ);
        for (int i = 0; i < orderSize; i++) {
            int slot = order[i];
            batch.addPolyline(pts[slot], 0, pointCounts[slot], closedFlags[slot], styleTable.get(styleOf[slot]));
        }
        return batch;
    }

    // ---------------------------------------------------------------- the buffers

    /**
     * Gives the size of the data buffer.
     *
     * @return {@link #capacityUnits} times the size of a record
     */
    public long dataBytes() {
        return capacity * unitBytes;
    }

    /**
     * Gives the size the style buffer needs for the polylines in the set now.
     *
     * @return the bytes of the style table: one entry per draw for {@link
     *     LineStrategy#INDIRECT_DRAW_ID}, one per style slot for the others, 0 for the hairline
     *     strategy; a buffer for as many polylines as the set can hold is always enough
     */
    public long styleBytes() {
        if (plan.strategy() == LineStrategy.HAIRLINE) {
            return 0;
        }
        return (plan.strategy() == LineStrategy.INDIRECT_DRAW_ID ? runCount() : styleTable.size()) * LineGpu.STYLE_BYTES;
    }

    private boolean continues(int previous, int slot) {
        return plan.strategy() != LineStrategy.HAIRLINE && styleOf[previous] == styleOf[slot] && unitOffset[previous] + unitCount[previous] == unitOffset[slot];
    }

    private int runCount() {
        int runs = 0;
        int previous = -1;
        for (int i = 0; i < orderSize; i++) {
            int slot = order[i];
            if (previous < 0 || !continues(previous, slot)) {
                runs++;
            }
            previous = slot;
        }
        return runs;
    }

    /**
     * Counts the byte ranges of the data buffer that the last {@link #update} wrote.
     *
     * @return the number of ranges; they are sorted, do not overlap and are not adjacent
     */
    public int dirtyRangeCount() {
        return rangeCount;
    }

    /**
     * Gives where a range written by the last update starts.
     *
     * @param i the range, 0 to {@link #dirtyRangeCount} exclusive
     * @return the byte offset in the data buffer
     * @throws IndexOutOfBoundsException if there is no such range
     */
    public long dirtyOffset(int i) {
        if (i < 0 || i >= rangeCount) {
            throw new IndexOutOfBoundsException("range " + i + " of " + rangeCount);
        }
        return (ranges[i] >>> 32) * unitBytes;
    }

    /**
     * Gives the length of a range written by the last update.
     *
     * @param i the range, 0 to {@link #dirtyRangeCount} exclusive
     * @return the length in bytes
     * @throws IndexOutOfBoundsException if there is no such range
     */
    public long dirtyLength(int i) {
        if (i < 0 || i >= rangeCount) {
            throw new IndexOutOfBoundsException("range " + i + " of " + rangeCount);
        }
        return (ranges[i] & 0xFFFFFFFFL) * unitBytes;
    }

    /**
     * Writes what changed since the last update and rebuilds the draws and the style table.
     *
     * <p>The data buffer is a mirror of what is on the GPU: the polylines that changed are written
     * into it, and {@link #dirtyOffset} and {@link #dirtyLength} give the ranges to copy over. Use
     * the same buffer every time, or call {@link #invalidate} when it is a different one.
     *
     * @param data the data buffer, at least {@link #dataBytes} long
     * @param styleBuffer the style buffer, at least {@link #styleBytes} long; may be {@code null}
     *     for the hairline strategy
     * @param draws receives the draws, after being cleared; must be of the kind {@link
     *     DrawList.Kind#ARRAYS}
     * @return the number of draws
     * @throws IllegalArgumentException if a buffer is too small, the draw list has the wrong kind,
     *     or the style table of a uniform block is too short for the styles
     */
    public int update(MemorySegment data, MemorySegment styleBuffer, DrawList draws) {
        return update(data, styleBuffer, draws, null);
    }

    /**
     * Writes what changed like {@link #update(MemorySegment, MemorySegment, DrawList)}, and draws
     * only the polylines that are visible. Every polyline is still written, so that one that
     * becomes visible needs no upload; only the draws leave the others out.
     *
     * @param data the data buffer, at least {@link #dataBytes} long
     * @param styleBuffer the style buffer, at least {@link #styleBytes} long; may be {@code null}
     *     for the hairline strategy
     * @param draws receives the draws, after being cleared
     * @param visible the polylines to draw, by {@link #slot slot}, as {@link LineCulling} makes it;
     *     {@code null} for all
     * @return the number of draws
     * @throws IllegalArgumentException as the update without a visibility set does
     */
    public int update(MemorySegment data, MemorySegment styleBuffer, DrawList draws, VisibilitySet visible) {
        if (draws.kind() != DrawList.Kind.ARRAYS) {
            throw new IllegalArgumentException("the draw list must hold ARRAYS draws");
        }
        if (data.byteSize() < dataBytes()) {
            throw new IllegalArgumentException("the data buffer has " + data.byteSize() + " bytes, " + dataBytes() + " are needed");
        }
        boolean hairline = plan.strategy() == LineStrategy.HAIRLINE;
        if (!hairline) {
            long needed = styleBytes();
            if (styleBuffer == null || styleBuffer.byteSize() < needed) {
                throw new IllegalArgumentException("the style buffer needs " + needed + " bytes");
            }
            long entries = needed / LineGpu.STYLE_BYTES;
            if (plan.styleMode() == StructArrayAccess.Mode.UNIFORM_BLOCK && entries > LineRenderPlan.STYLE_TABLE_UNIFORM_LENGTH) {
                throw new IllegalArgumentException("a uniform block holds " + LineRenderPlan.STYLE_TABLE_UNIFORM_LENGTH + " styles: use a context with storage buffers or texture buffers");
            }
        }
        if (everythingDirty) {
            for (int i = 0; i < orderSize; i++) {
                markDirty(order[i]);
            }
            everythingDirty = false;
        }
        rangeCount = 0;
        for (int i = 0; i < dirtyCount; i++) {
            int slot = dirtySlots[i];
            dirtyFlag[slot] = false;
            if (!live[slot]) {
                continue;
            }
            plan.writeUnits(data, unitOffset[slot], pts[slot], 0, pointCounts[slot], closedFlags[slot], styleTable.get(styleOf[slot]), styleOf[slot], originX, originY, originZ, feed);
            if (rangeCount == ranges.length) {
                ranges = Arrays.copyOf(ranges, rangeCount * 2);
            }
            ranges[rangeCount++] = unitOffset[slot] << 32 | unitCount[slot];
        }
        dirtyCount = 0;
        mergeRanges();

        draws.clear();
        if (!hairline && plan.strategy() != LineStrategy.INDIRECT_DRAW_ID) {
            for (int i = 0; i < styleTable.size(); i++) {
                if (styleRefs[i] > 0) {
                    LineGpu.writeStyle(styleBuffer, i * LineGpu.STYLE_BYTES, styleTable.get(i));
                }
            }
        }
        int runSlot = -1;
        long runFirst = 0;
        long runUnits = 0;
        for (int i = 0; i < orderSize; i++) {
            int slot = order[i];
            if (visible != null && !visible.get(slot)) {
                continue;
            }
            if (runSlot >= 0 && !continues(runSlot, slot)) {
                plan.addDraw(draws, styleBuffer, styleTable.get(styleOf[runSlot]), styleOf[runSlot], runFirst, runUnits);
                runSlot = -1;
            }
            if (runSlot < 0) {
                runFirst = unitOffset[slot];
                runUnits = 0;
            }
            runSlot = slot;
            runUnits += unitCount[slot];
        }
        if (runSlot >= 0) {
            plan.addDraw(draws, styleBuffer, styleTable.get(styleOf[runSlot]), styleOf[runSlot], runFirst, runUnits);
        }
        return draws.size();
    }

    /** Sorts the ranges (in place, no allocation) and joins the ones that touch or overlap. */
    private void mergeRanges() {
        int n = rangeCount;
        for (int i = n / 2 - 1; i >= 0; i--) {
            sift(i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            long t = ranges[0];
            ranges[0] = ranges[end];
            ranges[end] = t;
            sift(0, end);
        }
        int out = 0;
        for (int i = 0; i < n; i++) {
            long start = ranges[i] >>> 32, length = ranges[i] & 0xFFFFFFFFL;
            if (out > 0) {
                long pStart = ranges[out - 1] >>> 32, pEnd = pStart + (ranges[out - 1] & 0xFFFFFFFFL);
                if (start <= pEnd) {
                    long end = Math.max(pEnd, start + length);
                    ranges[out - 1] = pStart << 32 | (end - pStart);
                    continue;
                }
            }
            ranges[out++] = ranges[i];
        }
        rangeCount = out;
    }

    private void sift(int root, int n) {
        while (true) {
            int child = 2 * root + 1;
            if (child >= n) {
                return;
            }
            if (child + 1 < n && ranges[child + 1] > ranges[child]) {
                child++;
            }
            if (ranges[root] >= ranges[child]) {
                return;
            }
            long t = ranges[root];
            ranges[root] = ranges[child];
            ranges[child] = t;
            root = child;
        }
    }
}
