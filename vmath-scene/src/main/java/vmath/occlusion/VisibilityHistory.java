package vmath.occlusion;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * What is known about each object from the frames before: whether it was drawn in the last frame,
 * for how many frames in a row it has been drawn or has been hidden, and when it was last drawn.
 *
 * <p>This is the per-object visibility history that temporal coherence uses: an object that was
 * visible in the last frame is very likely visible in this one, and one that has been hidden for
 * many frames is cheap to leave out and worth a test only now and then. {@link CoherentCulling}
 * keeps one up to date; an engine can also use it for the other half of the idea, such as drawing
 * what was visible last frame first to fill the depth buffer, or fading an object in only after
 * several frames of visibility.
 *
 * <p>A frame is {@link #beginFrame}, any number of {@link #markVisible}, and {@link #endFrame}. The
 * queries refer to the frame that ended last until the next {@code beginFrame}.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one thread updates it; reads between frames from other
 * threads need the engine's own synchronisation.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * VisibilityHistory history = new VisibilityHistory(10_000);
 * history.beginFrame();
 * history.markVisible(42);
 * history.endFrame();
 * boolean again = history.wasVisible(42);                  // true
 * int frames = history.visibleStreak(42);                  // 1
 * }</pre>
 */
@Experimental("new in 0.2: the temporal culling may change")
public final class VisibilityHistory {

    private static final int NEVER = Integer.MIN_VALUE / 2;

    private int[] lastVisible;
    private int[] visibleStreak;
    private int[] hiddenStreak;
    private int[] markedIn;
    private int capacity;
    private int frame = -1;
    private boolean open;

    /**
     * Makes a history for a number of objects, all hidden for ever so far.
     *
     * @param capacity the number of objects, at least 1
     * @throws IllegalArgumentException if the capacity is below 1
     */
    public VisibilityHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("the capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
        lastVisible = new int[capacity];
        visibleStreak = new int[capacity];
        hiddenStreak = new int[capacity];
        markedIn = new int[capacity];
        Arrays.fill(lastVisible, NEVER);
        Arrays.fill(markedIn, NEVER);
    }

    /**
     * Gives the number of objects.
     *
     * @return the capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Makes room for more objects; the new ones have never been visible.
     *
     * @param newCapacity the new number of objects; a smaller value changes nothing
     */
    public void ensureCapacity(int newCapacity) {
        if (newCapacity <= capacity) {
            return;
        }
        lastVisible = Arrays.copyOf(lastVisible, newCapacity);
        visibleStreak = Arrays.copyOf(visibleStreak, newCapacity);
        hiddenStreak = Arrays.copyOf(hiddenStreak, newCapacity);
        markedIn = Arrays.copyOf(markedIn, newCapacity);
        Arrays.fill(lastVisible, capacity, newCapacity, NEVER);
        Arrays.fill(markedIn, capacity, newCapacity, NEVER);
        capacity = newCapacity;
    }

    /**
     * Gives the number of the current or last frame.
     *
     * @return the frame number, -1 before the first frame
     */
    public int frame() {
        return frame;
    }

    /**
     * Starts a frame.
     *
     * @throws IllegalStateException if the previous frame was not ended
     */
    public void beginFrame() {
        if (open) {
            throw new IllegalStateException("the previous frame has not been ended");
        }
        frame++;
        open = true;
    }

    /**
     * Records that an object is drawn in the current frame (more than once is the same as once).
     *
     * @param object the object, 0 to {@code capacity() - 1}
     * @throws IllegalStateException if no frame is open
     * @throws IndexOutOfBoundsException if the object is out of range
     */
    public void markVisible(int object) {
        if (!open) {
            throw new IllegalStateException("no frame is open");
        }
        markedIn[object] = frame;
    }

    /**
     * Ends the frame: the streaks of every object are updated.
     *
     * @throws IllegalStateException if no frame is open
     */
    public void endFrame() {
        if (!open) {
            throw new IllegalStateException("no frame is open");
        }
        for (int i = 0; i < capacity; i++) {
            if (markedIn[i] == frame) {
                lastVisible[i] = frame;
                visibleStreak[i]++;
                hiddenStreak[i] = 0;
            } else {
                visibleStreak[i] = 0;
                hiddenStreak[i]++;
            }
        }
        open = false;
    }

    /**
     * Tells whether an object was drawn in the last ended frame.
     *
     * @param object the object
     * @return {@code true} if it was
     * @throws IndexOutOfBoundsException if the object is out of range
     */
    public boolean wasVisible(int object) {
        return !open && lastVisible[object] == frame || open && lastVisible[object] == frame - 1;
    }

    /**
     * Gives the number of frames in a row, up to the last ended one, in which an object was drawn.
     *
     * @param object the object
     * @return 0 if it was not drawn in the last frame
     * @throws IndexOutOfBoundsException if the object is out of range
     */
    public int visibleStreak(int object) {
        return visibleStreak[object];
    }

    /**
     * Gives the number of frames in a row, up to the last ended one, in which an object was not drawn.
     *
     * @param object the object
     * @return 0 if it was drawn in the last frame; the number of frames so far if it never was
     * @throws IndexOutOfBoundsException if the object is out of range
     */
    public int hiddenStreak(int object) {
        return hiddenStreak[object];
    }

    /**
     * Gives the last frame in which an object was drawn.
     *
     * @param object the object
     * @return the frame number, or a very negative number if it never was
     * @throws IndexOutOfBoundsException if the object is out of range
     */
    public int lastVisibleFrame(int object) {
        return lastVisible[object];
    }

    /**
     * Lists the objects that were drawn in the last ended frame.
     *
     * @param out receives their indices, in increasing order, from {@code out[0]}
     * @return the number of objects; at most {@code out.length} are written
     */
    public int listVisible(int[] out) {
        int n = 0;
        for (int i = 0; i < capacity; i++) {
            if (wasVisible(i)) {
                if (n < out.length) {
                    out[n] = i;
                }
                n++;
            }
        }
        return n;
    }

    /** Forgets everything: every object is as if it had never been drawn. */
    public void clear() {
        Arrays.fill(lastVisible, NEVER);
        Arrays.fill(markedIn, NEVER);
        Arrays.fill(visibleStreak, 0);
        Arrays.fill(hiddenStreak, 0);
        frame = -1;
        open = false;
    }
}
