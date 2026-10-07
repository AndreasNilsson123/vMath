package vmath.gl;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * A list of draws that does not depend on how they will be submitted, with encoders that turn it
 * into what a driver can take.
 *
 * <p>One draw is a count (vertices or indices), a first vertex or first index, a base vertex (for
 * indexed draws), an instance count, a base instance and a value of the caller's, for example a
 * style index. The list is filled once and then written as
 *
 * <ul>
 *   <li>indirect commands in a buffer ({@link #writeIndirect}), for one multi-draw-indirect call;
 *   <li>the arrays of {@code glMultiDrawArrays}, {@code glMultiDrawElements} and
 *       {@code glMultiDrawElementsBaseVertex} ({@link #copyCounts}, {@link #copyFirsts},
 *       {@link #copyIndexOffsets}, {@link #copyBaseVertices}), which work without instancing; or
 *   <li>a plain loop ({@link #forEach}), the only form that needs nothing and the one that
 *       supports instancing without indirect draws.
 * </ul>
 *
 * <p>{@link DrawSubmission} chooses among them from the {@link GraphicsCapabilities}. The list
 * reuses its arrays, so filling and encoding allocate nothing once it has grown to size.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one writer, or any number of readers while nobody
 * writes.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DrawList draws = new DrawList(DrawList.Kind.ELEMENTS, 64);
 * draws.addElements(36, 0, 0, 100, 0, 7);                      // 100 instances of a cube, style 7
 * DrawSubmission how = DrawSubmission.choose(caps, draws);
 * if (how == DrawSubmission.MULTI_DRAW_INDIRECT) {
 *     draws.writeIndirect(commandBuffer);                      // then glMultiDrawElementsIndirect
 * } else {
 *     draws.forEach((count, first, baseVertex, instances, baseInstance, user) -> { });   // one draw call each
 * }
 * }</pre>
 */
@Experimental("the draw list may change")
public final class DrawList {

    /**
     * What the draws of a list draw.
     */
    public enum Kind {
        /** Non-indexed draws: a count of vertices from a first vertex. */
        ARRAYS,
        /** Indexed draws: a count of indices from a first index, with a base vertex. */
        ELEMENTS
    }

    /**
     * Receives the draws of a list one by one.
     */
    @FunctionalInterface
    public interface Visitor {
        /**
         * Receives one draw.
         *
         * @param count the vertices or indices
         * @param first the first vertex, or the first index of an indexed draw
         * @param baseVertex the base vertex (0 for non-indexed draws)
         * @param instanceCount the instance count
         * @param baseInstance the base instance; where {@link GraphicsCapabilities.Feature#BASE_INSTANCE}
         *     is missing the caller moves its per-instance attributes by this many instances
         * @param user the value of the caller
         */
        void draw(int count, int first, int baseVertex, int instanceCount, int baseInstance, int user);
    }

    private final Kind kind;
    private int size;
    private int[] count;
    private int[] first;
    private int[] baseVertex;
    private int[] instanceCount;
    private int[] baseInstance;
    private int[] user;

    /**
     * Creates an empty list.
     *
     * @param kind the kind of draws; must not be {@code null}
     * @param initialCapacity the number of draws to make room for, at least 1
     * @throws IllegalArgumentException if {@code initialCapacity} is below 1
     */
    public DrawList(Kind kind, int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("initialCapacity must be at least 1: " + initialCapacity);
        }
        this.kind = kind;
        count = new int[initialCapacity];
        first = new int[initialCapacity];
        baseVertex = new int[initialCapacity];
        instanceCount = new int[initialCapacity];
        baseInstance = new int[initialCapacity];
        user = new int[initialCapacity];
    }

    /**
     * Names the kind of the draws.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Counts the draws.
     *
     * @return the number of draws
     */
    public int size() {
        return size;
    }

    /**
     * Removes all draws, keeping the memory.
     */
    public void clear() {
        size = 0;
    }

    private int next() {
        if (size == count.length) {
            int n = size * 2;
            count = Arrays.copyOf(count, n);
            first = Arrays.copyOf(first, n);
            baseVertex = Arrays.copyOf(baseVertex, n);
            instanceCount = Arrays.copyOf(instanceCount, n);
            baseInstance = Arrays.copyOf(baseInstance, n);
            user = Arrays.copyOf(user, n);
        }
        return size++;
    }

    private static void nonNegative(String what, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(what + " must not be negative: " + value);
        }
    }

    /**
     * Appends a non-indexed draw.
     *
     * @param vertexCount the number of vertices
     * @param firstVertex the first vertex
     * @param instanceCount the number of instances; 0 draws nothing
     * @param baseInstance the base instance
     * @param user a value of the caller, kept with the draw
     * @return the index of the draw
     * @throws IllegalStateException if the list is of the kind {@link Kind#ELEMENTS}
     * @throws IllegalArgumentException if a count or the first vertex is negative
     */
    public int addArrays(int vertexCount, int firstVertex, int instanceCount, int baseInstance, int user) {
        if (kind != Kind.ARRAYS) {
            throw new IllegalStateException("this list holds " + kind + " draws");
        }
        return add(vertexCount, firstVertex, 0, instanceCount, baseInstance, user);
    }

    /**
     * Appends an indexed draw.
     *
     * @param indexCount the number of indices
     * @param firstIndex the first index
     * @param baseVertex the number added to every index
     * @param instanceCount the number of instances; 0 draws nothing
     * @param baseInstance the base instance
     * @param user a value of the caller, kept with the draw
     * @return the index of the draw
     * @throws IllegalStateException if the list is of the kind {@link Kind#ARRAYS}
     * @throws IllegalArgumentException if a count or the first index is negative
     */
    public int addElements(int indexCount, int firstIndex, int baseVertex, int instanceCount, int baseInstance, int user) {
        if (kind != Kind.ELEMENTS) {
            throw new IllegalStateException("this list holds " + kind + " draws");
        }
        return add(indexCount, firstIndex, baseVertex, instanceCount, baseInstance, user);
    }

    private int add(int c, int f, int bv, int ic, int bi, int u) {
        nonNegative("the count", c);
        nonNegative("the first vertex or index", f);
        nonNegative("the instance count", ic);
        nonNegative("the base instance", bi);
        int i = next();
        count[i] = c;
        first[i] = f;
        baseVertex[i] = bv;
        instanceCount[i] = ic;
        baseInstance[i] = bi;
        user[i] = u;
        return i;
    }

    private void check(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("draw " + i + " of " + size);
        }
    }

    /**
     * Reads the count of a draw.
     *
     * @param i the index of the draw
     * @return the vertices or indices
     * @throws IndexOutOfBoundsException if {@code i} is not a draw of the list
     */
    public int count(int i) {
        check(i);
        return count[i];
    }

    /**
     * Reads the first vertex or index of a draw.
     *
     * @param i the index of the draw
     * @return the first vertex, or the first index of an indexed draw
     * @throws IndexOutOfBoundsException if {@code i} is not a draw of the list
     */
    public int first(int i) {
        check(i);
        return first[i];
    }

    /**
     * Reads the base vertex of a draw.
     *
     * @param i the index of the draw
     * @return the base vertex (0 for non-indexed draws)
     * @throws IndexOutOfBoundsException if {@code i} is not a draw of the list
     */
    public int baseVertex(int i) {
        check(i);
        return baseVertex[i];
    }

    /**
     * Reads the instance count of a draw.
     *
     * @param i the index of the draw
     * @return the instance count
     * @throws IndexOutOfBoundsException if {@code i} is not a draw of the list
     */
    public int instanceCount(int i) {
        check(i);
        return instanceCount[i];
    }

    /**
     * Reads the base instance of a draw.
     *
     * @param i the index of the draw
     * @return the base instance
     * @throws IndexOutOfBoundsException if {@code i} is not a draw of the list
     */
    public int baseInstance(int i) {
        check(i);
        return baseInstance[i];
    }

    /**
     * Reads the value of the caller that is kept with a draw.
     *
     * @param i the index of the draw
     * @return the value
     * @throws IndexOutOfBoundsException if {@code i} is not a draw of the list
     */
    public int user(int i) {
        check(i);
        return user[i];
    }

    /**
     * Changes the instance count of a draw, for example after culling.
     *
     * @param i the index of the draw
     * @param instanceCount the new instance count
     * @throws IndexOutOfBoundsException if {@code i} is not a draw of the list
     * @throws IllegalArgumentException if {@code instanceCount} is negative
     */
    public void setInstanceCount(int i, int instanceCount) {
        check(i);
        nonNegative("the instance count", instanceCount);
        this.instanceCount[i] = instanceCount;
    }

    /**
     * Changes the base instance of a draw.
     *
     * @param i the index of the draw
     * @param baseInstance the new base instance
     * @throws IndexOutOfBoundsException if {@code i} is not a draw of the list
     * @throws IllegalArgumentException if {@code baseInstance} is negative
     */
    public void setBaseInstance(int i, int baseInstance) {
        check(i);
        nonNegative("the base instance", baseInstance);
        this.baseInstance[i] = baseInstance;
    }

    /**
     * Tells whether any draw has more than one instance.
     *
     * @return {@code true} if a client multi-draw cannot express the list
     */
    public boolean usesInstancing() {
        for (int i = 0; i < size; i++) {
            if (instanceCount[i] > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether any draw has a base instance that is not zero.
     *
     * @return {@code true} if submitting the list needs {@link GraphicsCapabilities.Feature#BASE_INSTANCE},
     *     or per-instance attributes that are moved by hand
     */
    public boolean usesBaseInstance() {
        for (int i = 0; i < size; i++) {
            if (baseInstance[i] != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends the draws to a command buffer, in order, zero-instance draws included, so that the
     * index of a command is the index of its draw (a culling pass that writes instance counts
     * relies on that).
     *
     * @param target the buffer; its kind must match the kind of this list, and it must have room
     *     for the draws; must not be {@code null}
     * @return the number of commands written
     * @throws IllegalStateException if the buffer holds the other kind of command or is full
     */
    public int writeIndirect(DrawCommandBuffer target) {
        for (int i = 0; i < size; i++) {
            if (kind == Kind.ARRAYS) {
                target.addArrays(count[i], instanceCount[i], first[i], baseInstance[i]);
            } else {
                target.addElements(count[i], instanceCount[i], first[i], baseVertex[i], baseInstance[i]);
            }
        }
        return size;
    }

    private void requireClientMultiDraw() {
        if (usesInstancing() || usesBaseInstance()) {
            throw new IllegalStateException("a client multi-draw cannot instance: use writeIndirect or forEach for a list with instances or a base instance");
        }
    }

    /**
     * Copies the counts of the draws that draw something into an array for
     * {@code glMultiDrawArrays} or {@code glMultiDrawElements}.
     *
     * <p>Draws with an instance count of 0 are left out, so that the arrays of {@link #copyFirsts},
     * {@link #copyIndexOffsets} and {@link #copyBaseVertices} line up with this one.
     *
     * @param dst the array; must have room for {@link #size()} values
     * @return the number of values written
     * @throws IllegalStateException if a draw has more than one instance or a base instance
     * @throws IllegalArgumentException if {@code dst} is too short
     */
    public int copyCounts(int[] dst) {
        requireClientMultiDraw();
        return copy(count, dst);
    }

    /**
     * Copies the first vertices of the draws that draw something, for {@code glMultiDrawArrays}.
     *
     * @param dst the array; must have room for {@link #size()} values
     * @return the number of values written
     * @throws IllegalStateException if a draw has more than one instance or a base instance
     * @throws IllegalArgumentException if {@code dst} is too short
     */
    public int copyFirsts(int[] dst) {
        requireClientMultiDraw();
        return copy(first, dst);
    }

    /**
     * Copies the base vertices of the draws that draw something, for
     * {@code glMultiDrawElementsBaseVertex}.
     *
     * @param dst the array; must have room for {@link #size()} values
     * @return the number of values written
     * @throws IllegalStateException if a draw has more than one instance or a base instance
     * @throws IllegalArgumentException if {@code dst} is too short
     */
    public int copyBaseVertices(int[] dst) {
        requireClientMultiDraw();
        return copy(baseVertex, dst);
    }

    /**
     * Copies the offsets in bytes into the index buffer of the draws that draw something, for the
     * {@code indices} argument of {@code glMultiDrawElements}.
     *
     * @param dst the array; must have room for {@link #size()} values
     * @param indexSizeBytes the size of an index: 1, 2 or 4
     * @return the number of values written
     * @throws IllegalStateException if a draw has more than one instance or a base instance
     * @throws IllegalArgumentException if {@code dst} is too short or {@code indexSizeBytes} is
     *     not 1, 2 or 4
     */
    public int copyIndexOffsets(long[] dst, int indexSizeBytes) {
        requireClientMultiDraw();
        if (indexSizeBytes != 1 && indexSizeBytes != 2 && indexSizeBytes != 4) {
            throw new IllegalArgumentException("the index size must be 1, 2 or 4 bytes: " + indexSizeBytes);
        }
        if (dst.length < size) {
            throw new IllegalArgumentException("dst has " + dst.length + " places for " + size + " draws");
        }
        int n = 0;
        for (int i = 0; i < size; i++) {
            if (instanceCount[i] > 0) {
                dst[n++] = (long) first[i] * indexSizeBytes;
            }
        }
        return n;
    }

    private int copy(int[] src, int[] dst) {
        if (dst.length < size) {
            throw new IllegalArgumentException("dst has " + dst.length + " places for " + size + " draws");
        }
        int n = 0;
        for (int i = 0; i < size; i++) {
            if (instanceCount[i] > 0) {
                dst[n++] = src[i];
            }
        }
        return n;
    }

    /**
     * Hands the draws that draw something to a visitor, in order: one draw call each.
     *
     * @param visitor the visitor; must not be {@code null}
     */
    public void forEach(Visitor visitor) {
        for (int i = 0; i < size; i++) {
            if (instanceCount[i] > 0) {
                visitor.draw(count[i], first[i], baseVertex[i], instanceCount[i], baseInstance[i], user[i]);
            }
        }
    }
}
