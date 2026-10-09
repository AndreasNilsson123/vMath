package vmath.core;

import java.nio.IntBuffer;
import vmath.annotations.ValueType;

/**
 * Immutable integer 2-vector: pixel, texel and tile coordinates.
 *
 * <p>See {@link Vec3i} for the conventions.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Vec2i cell = Vec2i.floor(new Vec2f(3.7f, -0.2f));         // (3, -1)
 * Vec2i next = cell.add(1, 0);
 * long steps = cell.manhattan(next);                        // 1
 * long key = cell.pack();                                   // a key for a hash map
 * Vec2i same = Vec2i.unpack(key);
 * }</pre>
 *
 * @param x the x component
 * @param y the y component
 */
@ValueType
public record Vec2i(int x, int y) {

    /**
     * The zero vector.
     */
    public static final Vec2i ZERO = new Vec2i(0, 0);
    /**
     * The vector with every component 1.
     */
    public static final Vec2i ONE = new Vec2i(1, 1);
    /**
     * The unit vector along +X.
     */
    public static final Vec2i UNIT_X = new Vec2i(1, 0);
    /**
     * The unit vector along +Y.
     */
    public static final Vec2i UNIT_Y = new Vec2i(0, 1);

    /**
     * Broadcasts a scalar into every component.
     *
     * @param v the value of every component
     * @return a vector with every component equal to {@code v}
     */
    public static Vec2i splat(int v) {
        return new Vec2i(v, v);
    }

    /**
     * Quantizes a position to the integer cell that contains it by flooring each component;
     * coordinates beyond the {@code int} range saturate.
     *
     * @param p the vector; must not be {@code null}
     * @return the cell containing {@code p}: component-wise floor
     */
    public static Vec2i floor(Vec2f p) {
        return new Vec2i((int) Math.floor(p.x()), (int) Math.floor(p.y()));
    }

    /**
     * Rounds half up, component-wise.
     *
     * @param p the vector; must not be {@code null}
     * @return the rounded vector, never {@code null}
     */
    public static Vec2i round(Vec2f p) {
        return new Vec2i(Math.round(p.x()), Math.round(p.y()));
    }

    /**
     * Adds the vectors component-wise; overflow wraps around as for {@code int}.
     *
     * @param o the other vector; must not be {@code null}
     * @return the sum {@code this + o}
     */
    public Vec2i add(Vec2i o) {
        return new Vec2i(x + o.x, y + o.y);
    }

    /**
     * Adds a vector given as separate components, component-wise; overflow wraps around as for
     * {@code int}.
     *
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @return the sum of this and the vector with the given components
     */
    public Vec2i add(int ox, int oy) {
        return new Vec2i(x + ox, y + oy);
    }

    /**
     * Subtracts the vectors component-wise; overflow wraps around as for {@code int}.
     *
     * @param o the other vector; must not be {@code null}
     * @return the difference {@code this - o}
     */
    public Vec2i sub(Vec2i o) {
        return new Vec2i(x - o.x, y - o.y);
    }

    /**
     * Scales the vector by an integer factor; overflow wraps around as for {@code int}.
     *
     * @param s the factor for every component
     * @return every component multiplied by {@code s}
     */
    public Vec2i mul(int s) {
        return new Vec2i(x * s, y * s);
    }

    /**
     * Multiplies the vectors component-wise (the Hadamard product, not a dot product); overflow
     * wraps around as for {@code int}.
     *
     * @param o the other vector; must not be {@code null}
     * @return component-wise product
     */
    public Vec2i mul(Vec2i o) {
        return new Vec2i(x * o.x, y * o.y);
    }

    /**
     * Negates every component; {@code Integer.MIN_VALUE} stays unchanged because it has no positive
     * counterpart.
     *
     * @return every component negated
     */
    public Vec2i negate() {
        return new Vec2i(-x, -y);
    }

    /**
     * Divides component-wise, rounding towards negative infinity instead of towards zero, which is
     * what cell addressing of negative coordinates needs; a zero divisor throws.
     *
     * @param d the divisor; must not be zero
     * @return component-wise floor division: rounds toward negative infinity
     */
    public Vec2i floorDiv(int d) {
        return new Vec2i(Math.floorDiv(x, d), Math.floorDiv(y, d));
    }

    /**
     * Takes the component-wise modulus with the sign of the divisor, so that cell coordinates wrap
     * into a non-negative range; a zero divisor throws.
     *
     * @param d the divisor; must not be zero
     * @return component-wise floor modulus, in {@code [0, d)} for positive {@code d}
     */
    public Vec2i floorMod(int d) {
        return new Vec2i(Math.floorMod(x, d), Math.floorMod(y, d));
    }

    /**
     * Takes the smaller of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise minimum
     */
    public Vec2i min(Vec2i o) {
        return new Vec2i(Math.min(x, o.x), Math.min(y, o.y));
    }

    /**
     * Takes the larger of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise maximum
     */
    public Vec2i max(Vec2i o) {
        return new Vec2i(Math.max(x, o.x), Math.max(y, o.y));
    }

    /**
     * Takes the absolute value per component; {@code Integer.MIN_VALUE} stays negative.
     *
     * @return the absolute value of every component
     */
    public Vec2i abs() {
        return new Vec2i(Math.abs(x), Math.abs(y));
    }

    /**
     * Restricts each component to a per-component range; the lower bound is expected not to exceed
     * the upper bound.
     *
     * @param lo the vector; must not be {@code null}
     * @param hi the vector; must not be {@code null}
     * @return every component clamped component-wise to {@code [lo, hi]}
     */
    public Vec2i clamp(Vec2i lo, Vec2i hi) {
        return new Vec2i(Math.min(Math.max(x, lo.x), hi.x), Math.min(Math.max(y, lo.y), hi.y));
    }

    /**
     * Computes the dot product in {@code long} arithmetic, so that no {@code int} overflow can
     * occur.
     *
     * @param o the other vector; must not be {@code null}
     * @return the dot product (as a {@code long}, so that it cannot overflow)
     */
    public long dot(Vec2i o) {
        return (long) x * o.x + (long) y * o.y;
    }

    /**
     * Computes the squared length in {@code long} arithmetic, which avoids both the square root and
     * any {@code int} overflow; the right choice for comparing lengths.
     *
     * @return the squared length (as a {@code long}, so that it cannot overflow)
     */
    public long lengthSquared() {
        return dot(this);
    }

    /**
     * Computes the squared distance in {@code long} arithmetic, which avoids both the square root
     * and any {@code int} overflow; the right choice for comparing distances.
     *
     * @param o the other vector; must not be {@code null}
     * @return the squared distance to {@code o} (as a {@code long}, so that it cannot overflow)
     */
    public long distanceSquared(Vec2i o) {
        long dx = (long) x - o.x, dy = (long) y - o.y;
        return dx * dx + dy * dy;
    }

    /**
     * Measures the distance on the Manhattan (L1) metric, which is the length of a shortest path on
     * a four-connected grid.
     *
     * @param o the other vector; must not be {@code null}
     * @return the sum of the absolute component differences: the number of axis-aligned steps
     *     between two cells
     */
    public long manhattan(Vec2i o) {
        return Math.abs((long) x - o.x) + Math.abs((long) y - o.y);
    }

    /**
     * Measures the distance on the Chebyshev (L-infinity) metric, which is the length of a shortest
     * path on a grid that allows diagonal steps.
     *
     * @param o the other vector; must not be {@code null}
     * @return the largest absolute component difference: the number of steps when diagonal moves
     *     are allowed
     */
    public long chebyshev(Vec2i o) {
        return Math.max(Math.abs((long) x - o.x), Math.abs((long) y - o.y));
    }

    /**
     * Counts the cells of a rectangular grid of this size, in {@code long} arithmetic so that large
     * extents do not overflow.
     *
     * @return number of cells in an {@code x * y} grid; a negative extent counts as zero
     */
    public long area() {
        return (long) Math.max(x, 0) * Math.max(y, 0);
    }

    /**
     * Selects the smallest component.
     *
     * @return the smallest component
     */
    public int minComponent() {
        return Math.min(x, y);
    }

    /**
     * Selects the largest component.
     *
     * @return the largest component
     */
    public int maxComponent() {
        return Math.max(x, y);
    }

    /**
     * Reads a component by index, for loops that treat the vector as an array.
     *
     * @param i the index
     * @return component {@code i} (0 is x, 1 is y, and so on); {@link IndexOutOfBoundsException}
     *     for any other index
     * @throws IndexOutOfBoundsException if {@code i} is not a component index
     */
    public int get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    /**
     * Packs both coordinates into one {@code long}, 32 bits each with x in the high half.
     *
     * <p>Lossless.
     *
     * @return the packed key, with x in the high 32 bits and y in the low 32 bits
     */
    public long pack() {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /**
     * Decodes a key made by {@link #pack()} back into a vector.
     *
     * @param key the key
     * @return inverse of {@link #pack()}
     */
    public static Vec2i unpack(long key) {
        return new Vec2i((int) (key >> 32), (int) key);
    }

    /**
     * Converts the components to {@code float}, which rounds large values.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    public Vec2f toFloat() {
        return new Vec2f(x, y);
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    public Vec2d toDouble() {
        return new Vec2d(x, y);
    }

    /**
     * Writes the components to {@code dst[off ..]} in order.
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeTo(int[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
    }

    /**
     * Writes the vector at the absolute position {@code index}; does not change the buffer
     * position.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(IntBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y);
    }

    /**
     * Reads a vector from text in any of the forms the types print or people write: the {@code toString} of the record
     * ({@code Vec2i[x=1, y=1]}), the compact form of {@link #toCompactString()}, or a bare list such as {@code 1 2 3}.
     * Brackets and bars are ignored and the numbers are separated by commas, semicolons or white space. Labelled numbers may come in any
     * order; without labels they are taken in the order of the components.
     *
     * @param text the text; must not be {@code null}
     * @return the vector with the 2 numbers of the text
     * @throws IllegalArgumentException if the text does not hold exactly 2 numbers, mixes labelled and unlabelled ones, names a component
     *     twice or not at all, starts with the name of another type, or holds a number that is not an {@code int}
     */
    public static Vec2i parse(CharSequence text) {
        String[] t = Text.tokens(text, "Vec2i", Text.VEC2, null);
        return new Vec2i(Integer.parseInt(t[0]), Integer.parseInt(t[1]));
    }

    /**
     * Gives the compact text of the vector, such as {@code (1, 2, 3)}.
     *
     * @return the components in order between parentheses; {@link #parse(CharSequence)} gives back an equal vector
     */
    public String toCompactString() {
        return "(" + x + ", " + y + ")";
    }
}
