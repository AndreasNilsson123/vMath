package vmath.map;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * The symbols of a map layer: for each, a position in projected metres (double precision), an
 * angle, a size, an atlas rectangle, a colour, flags and a screen offset.
 *
 * <p>It is the data side of {@link SymbolRenderPlan}, which turns it into buffers relative to an
 * origin chosen per frame, and of the declutter ({@link Declutter}), which hides symbols and
 * moves them by an offset. Symbols are drawn in the order they were added.
 *
 * <p><b>Thread safety.</b> Not thread-safe: use one batch per thread or lock around it.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * SymbolBatch batch = new SymbolBatch(64);
 * float[] uv = new float[4];
 * atlas.rect(atlas.indexOf("airport"), uv);
 * int i = batch.add(512_345.0, 6_100_000.0, 0.0, 24f, uv, 0xFFFFFFFF, SymbolGpu.ROTATE_WITH_MAP);
 * batch.setOffset(i, 12f, 8f);
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class SymbolBatch {

    private double[] xy;
    private float[] angle;
    private float[] size;
    private float[] uv;
    private int[] color;
    private int[] flags;
    private float[] offset;
    private int count;

    /**
     * Makes an empty batch.
     *
     * @param initialCapacity the number of symbols to make room for, at least 1
     * @throws IllegalArgumentException if the capacity is below 1
     */
    public SymbolBatch(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("the capacity must be at least 1: " + initialCapacity);
        }
        xy = new double[2 * initialCapacity];
        angle = new float[initialCapacity];
        size = new float[initialCapacity];
        uv = new float[4 * initialCapacity];
        color = new int[initialCapacity];
        flags = new int[initialCapacity];
        offset = new float[2 * initialCapacity];
    }

    /**
     * Adds a symbol.
     *
     * @param x the projected x (east) in metres
     * @param y the projected y (north) in metres
     * @param angle the counter-clockwise angle in radians (see {@link SymbolGpu})
     * @param size the side in pixels, or in metres with {@link SymbolGpu#SIZE_IN_MAP_UNITS}; not negative
     * @param uv the atlas rectangle {@code u0, v0, u1, v1}, 4 values; copied
     * @param color the colour {@code 0xRRGGBBAA}
     * @param flags the flags of {@link SymbolGpu}
     * @return the index of the symbol
     * @throws IllegalArgumentException if a value is not finite, the size is negative or {@code uv} is short
     */
    public int add(double x, double y, double angle, float size, float[] uv, int color, int flags) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(angle) || !Float.isFinite(size) || size < 0 || uv.length < 4) {
            throw new IllegalArgumentException("need a finite position and angle, a size >= 0 and 4 uv values");
        }
        if (count == this.angle.length) {
            grow();
        }
        int i = count++;
        xy[2 * i] = x;
        xy[2 * i + 1] = y;
        this.angle[i] = (float) angle;
        this.size[i] = size;
        System.arraycopy(uv, 0, this.uv, 4 * i, 4);
        this.color[i] = color;
        this.flags[i] = flags;
        this.offset[2 * i] = 0f;
        this.offset[2 * i + 1] = 0f;
        return i;
    }

    private void grow() {
        int n = angle.length * 2;
        xy = Arrays.copyOf(xy, 2 * n);
        angle = Arrays.copyOf(angle, n);
        size = Arrays.copyOf(size, n);
        uv = Arrays.copyOf(uv, 4 * n);
        color = Arrays.copyOf(color, n);
        flags = Arrays.copyOf(flags, n);
        offset = Arrays.copyOf(offset, 2 * n);
    }

    /**
     * Removes all symbols, keeping the room.
     */
    public void clear() {
        count = 0;
    }

    /**
     * Gives the number of symbols.
     *
     * @return the count
     */
    public int count() {
        return count;
    }

    private void check(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("symbol " + i + " of " + count);
        }
    }

    /**
     * Gives the projected x of a symbol.
     *
     * @param i the symbol
     * @return metres
     * @throws IndexOutOfBoundsException if out of range
     */
    public double x(int i) {
        check(i);
        return xy[2 * i];
    }

    /**
     * Gives the projected y of a symbol.
     *
     * @param i the symbol
     * @return metres
     * @throws IndexOutOfBoundsException if out of range
     */
    public double y(int i) {
        check(i);
        return xy[2 * i + 1];
    }

    /**
     * Gives the angle of a symbol.
     *
     * @param i the symbol
     * @return counter-clockwise radians
     * @throws IndexOutOfBoundsException if out of range
     */
    public float angle(int i) {
        check(i);
        return angle[i];
    }

    /**
     * Gives the size of a symbol.
     *
     * @param i the symbol
     * @return the side in pixels, or metres with {@link SymbolGpu#SIZE_IN_MAP_UNITS}
     * @throws IndexOutOfBoundsException if out of range
     */
    public float size(int i) {
        check(i);
        return size[i];
    }

    /**
     * Gives the colour of a symbol.
     *
     * @param i the symbol
     * @return {@code 0xRRGGBBAA}
     * @throws IndexOutOfBoundsException if out of range
     */
    public int color(int i) {
        check(i);
        return color[i];
    }

    /**
     * Gives the flags of a symbol.
     *
     * @param i the symbol
     * @return the flag bits of {@link SymbolGpu}
     * @throws IndexOutOfBoundsException if out of range
     */
    public int flags(int i) {
        check(i);
        return flags[i];
    }

    /**
     * Gives the offset of a symbol.
     *
     * @param i the symbol
     * @param axis 0 for the offset to the right, 1 for upwards
     * @return pixels
     * @throws IndexOutOfBoundsException if out of range
     */
    public float offset(int i, int axis) {
        check(i);
        return offset[2 * i + (axis & 1)];
    }

    /**
     * Copies the atlas rectangle of a symbol.
     *
     * @param i the symbol
     * @param out receives {@code u0, v0, u1, v1} at {@code out[0..3]}
     * @throws IndexOutOfBoundsException if out of range
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void uv(int i, float[] out) {
        check(i);
        if (out.length < 4) {
            throw new IllegalArgumentException("out must have room for 4 values");
        }
        System.arraycopy(uv, 4 * i, out, 0, 4);
    }

    /**
     * Moves a symbol on the screen.
     *
     * @param i the symbol
     * @param right pixels to the right
     * @param up pixels upwards
     * @throws IndexOutOfBoundsException if out of range
     */
    public void setOffset(int i, float right, float up) {
        check(i);
        offset[2 * i] = right;
        offset[2 * i + 1] = up;
    }

    /**
     * Sets or clears the flag bits of a symbol.
     *
     * @param i the symbol
     * @param mask the bits to change
     * @param on {@code true} to set them, {@code false} to clear them
     * @throws IndexOutOfBoundsException if out of range
     */
    public void setFlags(int i, int mask, boolean on) {
        check(i);
        flags[i] = on ? flags[i] | mask : flags[i] & ~mask;
    }

    /**
     * Changes the colour of a symbol.
     *
     * @param i the symbol
     * @param rgba {@code 0xRRGGBBAA}
     * @throws IndexOutOfBoundsException if out of range
     */
    public void setColor(int i, int rgba) {
        check(i);
        color[i] = rgba;
    }

    /**
     * Moves a symbol on the map.
     *
     * @param i the symbol
     * @param x the projected x in metres
     * @param y the projected y in metres
     * @throws IndexOutOfBoundsException if out of range
     * @throws IllegalArgumentException if a value is not finite
     */
    public void setPosition(int i, double x, double y) {
        check(i);
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("the position must be finite");
        }
        xy[2 * i] = x;
        xy[2 * i + 1] = y;
    }

    /**
     * Turns a symbol.
     *
     * @param i the symbol
     * @param angle counter-clockwise radians
     * @throws IndexOutOfBoundsException if out of range
     */
    public void setAngle(int i, double angle) {
        check(i);
        this.angle[i] = (float) angle;
    }
}
