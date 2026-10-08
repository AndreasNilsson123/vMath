package vmath.map;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import vmath.annotations.Experimental;
import vmath.bulk.IntList;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.spatial.UniformGrid;

/**
 * Greedy placement of labels and symbols so that they do not overlap on the screen.
 *
 * <p>Each item has an anchor on the screen, a size, a priority and a list of <em>candidates</em>:
 * offsets of its centre from the anchor, tried in order (the first is usually {@code 0, 0}, the
 * others the places around the anchor, farther each time). The items are visited in order of
 * priority, mandatory ones first, and each takes the first candidate whose rectangle (plus the
 * {@link #padding}) overlaps no item placed before; an item with no free candidate is hidden. A
 * {@link UniformGrid} of the placed rectangles keeps this near linear for the thousands of items of
 * a busy map. The result is greedy, not optimal: a high priority item can force two lesser ones
 * away where moving it would have saved both.
 *
 * <p><b>Stability.</b> Between frames the same item should not jump from candidate to candidate or
 * flicker in and out as the map pans. Items are identified by a caller-chosen {@code key}; an item that was
 * shown in the previous {@link #solve} (i) tries its previous candidate first and (ii) has its
 * priority raised by {@link #stickiness} for the ordering, so a new item displaces a shown one only
 * if it is more important by more than that. Call {@link #solve} once per frame, or once per change
 * of the data; {@link #forget} drops the history (after a jump of the view).
 *
 * <p>The positions are screen pixels, the same unit and orientation as the anchors given (the
 * offsets use that frame too: pass {@code yDown} to {@link #apply} for window coordinates that
 * grow downwards). It is data only: it draws nothing and knows nothing of fonts; the size of a label
 * is the caller's measurement.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one declutter per thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Declutter declutter = new Declutter(64f, 256);
 * declutter.setCandidates(new float[] {0f, 20f, -20f}, new float[] {0f, 0f, 0f});    // in place, right, left
 * declutter.begin();
 * declutter.add(17, 310f, 200f, 24f, 24f, 5, false);                                  // key, anchor, size, priority, mandatory
 * declutter.add(18, 315f, 202f, 24f, 24f, 1, false);
 * declutter.solve();
 * boolean shown = declutter.chosen(0) >= 0;
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class Declutter {

    private static final long PRIORITY_LIMIT = 1L << 30;

    private final UniformGrid grid;
    private final IntList found = new IntList(16);
    private final UniformGrid.Query query;
    private float[] candidateX = {0f};
    private float[] candidateY = {0f};
    private float padding = 2f;
    private int stickiness = 1;
    private boolean clipped;
    private float clipMinX, clipMinY, clipMaxX, clipMaxY;

    private int[] key;
    private float[] anchorX, anchorY, width, height;
    private int[] priority;
    private boolean[] mandatory;
    private int[] chosen;
    private float[] rectMinX, rectMinY, rectMaxX, rectMaxY;
    private int count;

    private Map<Integer, Integer> previous = new HashMap<>();
    private Map<Integer, Integer> next = new HashMap<>();

    /**
     * Makes a declutter.
     *
     * @param cellSize the cell size of the grid in pixels, near the typical item size
     * @param capacity the number of items to make room for, at least 1
     * @throws IllegalArgumentException if the cell size is not positive or the capacity is below 1
     */
    public Declutter(float cellSize, int capacity) {
        if (!(cellSize > 0f) || capacity < 1) {
            throw new IllegalArgumentException("need a positive cell size and a capacity of at least 1");
        }
        grid = new UniformGrid(cellSize, capacity);
        query = grid.newQuery();
        allocate(capacity);
    }

    private void allocate(int n) {
        key = new int[n];
        anchorX = new float[n];
        anchorY = new float[n];
        width = new float[n];
        height = new float[n];
        priority = new int[n];
        mandatory = new boolean[n];
        chosen = new int[n];
        rectMinX = new float[n];
        rectMinY = new float[n];
        rectMaxX = new float[n];
        rectMaxY = new float[n];
    }

    /**
     * Sets the candidate offsets, tried in order.
     *
     * @param dx the offsets of the centre to the right of the anchor, in pixels
     * @param dy the offsets of the centre in the direction of the anchors' y, in pixels
     * @throws IllegalArgumentException if the arrays are empty or of different lengths
     */
    public void setCandidates(float[] dx, float[] dy) {
        if (dx.length == 0 || dx.length != dy.length) {
            throw new IllegalArgumentException("need as many y as x offsets, and at least one");
        }
        candidateX = dx.clone();
        candidateY = dy.clone();
    }

    /**
     * Makes the usual candidates: in place, then the four sides and the four corners at growing distances.
     *
     * @param distance the distance of the first ring in pixels, positive
     * @param rings the number of rings, at least 0
     * @throws IllegalArgumentException if the distance is not positive or the rings are negative
     */
    public void useRingCandidates(float distance, int rings) {
        if (!(distance > 0f) || rings < 0) {
            throw new IllegalArgumentException("need a positive distance and rings >= 0");
        }
        float[] dx = new float[1 + 8 * rings], dy = new float[1 + 8 * rings];
        int n = 1;
        for (int r = 1; r <= rings; r++) {
            float d = distance * r;
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                float ux = (float) Math.round(Math.cos(a)), uy = (float) Math.round(Math.sin(a));
                dx[n] = ux * d;
                dy[n] = uy * d;
                n++;
            }
        }
        candidateX = dx;
        candidateY = dy;
    }

    /**
     * Gives the number of candidates.
     *
     * @return the count, at least 1
     */
    public int candidateCount() {
        return candidateX.length;
    }

    /**
     * Gives the horizontal offset of a candidate.
     *
     * @param candidate the candidate, 0 to {@link #candidateCount()} - 1
     * @return pixels to the right of the anchor
     * @throws IndexOutOfBoundsException if out of range
     */
    public float candidateX(int candidate) {
        return candidateX[candidate];
    }

    /**
     * Gives the vertical offset of a candidate.
     *
     * @param candidate the candidate, 0 to {@link #candidateCount()} - 1
     * @return pixels in the direction of the anchors' y
     * @throws IndexOutOfBoundsException if out of range
     */
    public float candidateY(int candidate) {
        return candidateY[candidate];
    }

    /**
     * Sets the gap that must stay free between placed rectangles.
     *
     * @param pixels the gap, not negative; 2 by default
     * @throws IllegalArgumentException if negative
     */
    public void setPadding(float pixels) {
        if (!(pixels >= 0f)) {
            throw new IllegalArgumentException("the padding must not be negative: " + pixels);
        }
        padding = pixels;
    }

    /**
     * Gives the gap.
     *
     * @return pixels
     */
    public float padding() {
        return padding;
    }

    /**
     * Sets how much an item that was shown last time is worth in the ordering.
     *
     * @param bonus added to the priority of such items, not negative; 1 by default
     * @throws IllegalArgumentException if negative
     */
    public void setStickiness(int bonus) {
        if (bonus < 0) {
            throw new IllegalArgumentException("the bonus must not be negative: " + bonus);
        }
        stickiness = bonus;
    }

    /**
     * Gives the stickiness.
     *
     * @return the bonus
     */
    public int stickiness() {
        return stickiness;
    }

    /**
     * Restricts the placement to a rectangle of the screen: an item whose chosen rectangle would
     * leave it is tried at the next candidate, and hidden if none stays inside.
     *
     * @param minX the left edge
     * @param minY the lower edge (the smaller y)
     * @param maxX the right edge
     * @param maxY the upper edge
     * @throws IllegalArgumentException if the rectangle is empty
     */
    public void setClip(float minX, float minY, float maxX, float maxY) {
        if (!(maxX > minX) || !(maxY > minY)) {
            throw new IllegalArgumentException("the clip rectangle must not be empty");
        }
        clipped = true;
        clipMinX = minX;
        clipMinY = minY;
        clipMaxX = maxX;
        clipMaxY = maxY;
    }

    /** Removes the clip rectangle. */
    public void clearClip() {
        clipped = false;
    }

    /** Starts a frame: removes the items, keeping the history of the previous result. */
    public void begin() {
        count = 0;
        grid.clear();
    }

    /** Drops the history, so that nothing is treated as shown before. */
    public void forget() {
        previous.clear();
    }

    /**
     * Adds an item.
     *
     * @param key the identity of the item across frames; use the id of the feature
     * @param anchorX the anchor x in pixels
     * @param anchorY the anchor y in pixels
     * @param width the width of the item in pixels, not negative
     * @param height the height of the item in pixels, not negative
     * @param priority the importance: larger is placed first; 0 to 2^30
     * @param mandatory {@code true} for an item that is always shown: it is placed first, at the
     *     first candidate that is free or, if none, at the first one (it may overlap)
     * @return the index of the item, in the order of adding
     * @throws IllegalArgumentException if a value is not finite, a size is negative or the priority is out of range
     */
    public int add(int key, float anchorX, float anchorY, float width, float height, int priority, boolean mandatory) {
        if (!Float.isFinite(anchorX) || !Float.isFinite(anchorY) || !(width >= 0f) || !(height >= 0f) || !Float.isFinite(width) || !Float.isFinite(height)
                || priority < 0 || priority > PRIORITY_LIMIT) {
            throw new IllegalArgumentException("need a finite anchor, sizes >= 0 and a priority of 0 to 2^30");
        }
        if (count == this.key.length) {
            int n = count * 2;
            this.key = Arrays.copyOf(this.key, n);
            this.anchorX = Arrays.copyOf(this.anchorX, n);
            this.anchorY = Arrays.copyOf(this.anchorY, n);
            this.width = Arrays.copyOf(this.width, n);
            this.height = Arrays.copyOf(this.height, n);
            this.priority = Arrays.copyOf(this.priority, n);
            this.mandatory = Arrays.copyOf(this.mandatory, n);
            this.chosen = Arrays.copyOf(this.chosen, n);
            this.rectMinX = Arrays.copyOf(this.rectMinX, n);
            this.rectMinY = Arrays.copyOf(this.rectMinY, n);
            this.rectMaxX = Arrays.copyOf(this.rectMaxX, n);
            this.rectMaxY = Arrays.copyOf(this.rectMaxY, n);
        }
        int i = count++;
        this.key[i] = key;
        this.anchorX[i] = anchorX;
        this.anchorY[i] = anchorY;
        this.width[i] = width;
        this.height[i] = height;
        this.priority[i] = priority;
        this.mandatory[i] = mandatory;
        this.chosen[i] = -1;
        return i;
    }

    /**
     * Gives the number of items.
     *
     * @return the count
     */
    public int count() {
        return count;
    }

    private boolean fits(int i, int c) {
        float cx = anchorX[i] + candidateX[c], cy = anchorY[i] + candidateY[c];
        float hw = 0.5f * width[i], hh = 0.5f * height[i];
        if (clipped && (cx - hw < clipMinX || cx + hw > clipMaxX || cy - hh < clipMinY || cy + hh > clipMaxY)) {
            return false;
        }
        float h = 0.5f * padding;
        float minX = cx - hw - h, minY = cy - hh - h, maxX = cx + hw + h, maxY = cy + hh + h;
        found.clear();
        query.overlapAabb(Aabbf.of(new Vec3f(minX, minY, 0f), new Vec3f(maxX, maxY, 0f)), found);
        for (int k = 0; k < found.size(); k++) {
            int j = found.get(k);
            if (minX < rectMaxX[j] && rectMinX[j] < maxX && minY < rectMaxY[j] && rectMinY[j] < maxY) {
                return false;
            }
        }
        return true;
    }

    private void place(int i, int c) {
        chosen[i] = c;
        float cx = anchorX[i] + candidateX[c], cy = anchorY[i] + candidateY[c];
        float hw = 0.5f * width[i] + 0.5f * padding, hh = 0.5f * height[i] + 0.5f * padding;
        rectMinX[i] = cx - hw;
        rectMinY[i] = cy - hh;
        rectMaxX[i] = cx + hw;
        rectMaxY[i] = cy + hh;
        grid.insert(rectMinX[i], rectMinY[i], 0f, rectMaxX[i], rectMaxY[i], 0f, i);
        next.put(key[i], c);
    }

    /**
     * Places the items.
     *
     * <p>Afterwards {@link #chosen}, {@link #offsetX}, {@link #offsetY} and {@link #shown} tell the
     * result, and the history for the next call is the set of shown items.
     *
     * @return the number of items shown
     */
    public int solve() {
        long[] order = new long[count];
        for (int i = 0; i < count; i++) {
            boolean was = previous.containsKey(key[i]);
            long effective = Math.min(PRIORITY_LIMIT, (long) priority[i] + (was ? stickiness : 0));
            order[i] = (mandatory[i] ? 0L : 1L << 62) | (PRIORITY_LIMIT - effective) << 31 | i;
        }
        Arrays.sort(order);
        next.clear();
        int shown = 0;
        for (long o : order) {
            int i = (int) (o & 0x7FFFFFFFL);
            Integer before = previous.get(key[i]);
            int placed = -1;
            if (before != null && before < candidateX.length && fits(i, before)) {
                placed = before;
            } else {
                for (int c = 0; c < candidateX.length; c++) {
                    if (fits(i, c)) {
                        placed = c;
                        break;
                    }
                }
            }
            if (placed < 0 && mandatory[i]) {
                placed = before != null && before < candidateX.length ? before : 0;
            }
            if (placed >= 0) {
                place(i, placed);
                shown++;
            }
        }
        Map<Integer, Integer> t = previous;
        previous = next;
        next = t;
        return shown;
    }

    /**
     * Gives the candidate an item took.
     *
     * @param item the item, from {@link #add}
     * @return the candidate index, or -1 if the item is hidden
     * @throws IndexOutOfBoundsException if out of range
     */
    public int chosen(int item) {
        check(item);
        return chosen[item];
    }

    /**
     * Tells whether an item is shown.
     *
     * @param item the item
     * @return {@code true} if it took a candidate
     * @throws IndexOutOfBoundsException if out of range
     */
    public boolean shown(int item) {
        return chosen(item) >= 0;
    }

    /**
     * Gives the horizontal offset of an item from its anchor.
     *
     * @param item the item
     * @return pixels to the right, or 0 if hidden
     * @throws IndexOutOfBoundsException if out of range
     */
    public float offsetX(int item) {
        int c = chosen(item);
        return c < 0 ? 0f : candidateX[c];
    }

    /**
     * Gives the vertical offset of an item from its anchor.
     *
     * @param item the item
     * @return pixels in the direction of the anchors' y, or 0 if hidden
     * @throws IndexOutOfBoundsException if out of range
     */
    public float offsetY(int item) {
        int c = chosen(item);
        return c < 0 ? 0f : candidateY[c];
    }

    /**
     * Tells whether an item needs a leader line: it is shown away from its anchor.
     *
     * @param item the item
     * @return {@code true} if shown at an offset other than zero
     * @throws IndexOutOfBoundsException if out of range
     */
    public boolean needsLeader(int item) {
        int c = chosen(item);
        return c >= 0 && (candidateX[c] != 0f || candidateY[c] != 0f);
    }

    private void check(int item) {
        if (item < 0 || item >= count) {
            throw new IndexOutOfBoundsException("item " + item + " of " + count);
        }
    }

    /**
     * Applies the result to consecutive symbols of a batch: hides those not shown and gives the
     * others their offset.
     *
     * @param batch the batch; must not be {@code null}
     * @param firstSymbol the symbol that item 0 stands for; item {@code i} is symbol {@code firstSymbol + i}
     * @param yDown {@code true} if the y of the anchors grows downwards (window coordinates), so that the offsets are mirrored
     * @throws IndexOutOfBoundsException if a symbol is out of range
     */
    public void apply(SymbolBatch batch, int firstSymbol, boolean yDown) {
        for (int i = 0; i < count; i++) {
            int s = firstSymbol + i;
            boolean show = chosen[i] >= 0;
            batch.setFlags(s, SymbolGpu.HIDDEN, !show);
            batch.setOffset(s, offsetX(i), yDown ? 0f - offsetY(i) : offsetY(i));
        }
    }
}
