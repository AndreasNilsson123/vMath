package vmath.geo;

/**
 * A discrete oriented polytope (k-DOP): the volume between {@code k / 2} pairs of parallel planes with fixed, shared directions. With the three coordinate axes it is the
 * axis-aligned box ({@code k = 6}); adding the four space diagonals gives {@code k = 14}, adding the six face diagonals {@code k = 18}, and both {@code k = 26}. More directions
 * cut off the corners of the box, so a k-DOP is a tighter bound than an AABB for rounded or diagonal shapes, and two of them are tested for overlap by comparing the
 * {@code k / 2} slab intervals, which is as cheap as a box test (a few more compares).
 *
 * <p>The directions are the usual unnormalised ones: the axes {@code (1, 0, 0)} and so on, the diagonals {@code (+-1, +-1, +-1)} and the face diagonals {@code (1, +-1, 0)},
 * {@code (1, 0, +-1)}, {@code (0, 1, +-1)}. A slab stores the smallest and largest value of {@code d . p} over the contents, with {@code d} the unnormalised direction, so
 * slab widths of different directions are not distances.
 *
 * <p>{@link #overlaps} is exact for the polytopes these slabs define only in the sense of the separating axis test over these directions: it never reports a miss for volumes
 * that touch (conservative), and may report an overlap for two shapes that are separated along some other direction. The bounds are rounded outward, so every point used to
 * build a k-DOP is inside it.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 */
public final class KDop {

    /** All thirteen directions: 0..2 the axes, 3..6 the space diagonals, 7..12 the face diagonals. A k-DOP uses the axes plus the diagonals ({@code k = 14}), the face diagonals ({@code 18}) or both ({@code 26}). */
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {0, 1, 0}, {0, 0, 1},
            {1, 1, 1}, {1, 1, -1}, {1, -1, 1}, {-1, 1, 1},
            {1, 1, 0}, {1, -1, 0}, {1, 0, 1}, {1, 0, -1}, {0, 1, 1}, {0, 1, -1}};

    private final int slabs;
    private final int[] dirs; // the index into DIRECTIONS of each slab
    private final float[] min;
    private final float[] max;

    private KDop(int slabs, float[] min, float[] max) {
        this.slabs = slabs;
        this.dirs = setOf(slabs);
        this.min = min;
        this.max = max;
    }

    private static int[] setOf(int slabs) {
        int[] d = new int[slabs];
        for (int i = 0; i < slabs; i++) {
            d[i] = slabs == 9 && i >= 3 ? i + 4 : i;
        }
        return d;
    }

    /** The number of slabs for {@code k}: 3, 7, 9 or 13; {@link IllegalArgumentException} for any other {@code k}. */
    private static int slabsOf(int k) {
        switch (k) {
            case 6:
                return 3;
            case 14:
                return 7;
            case 18:
                return 9;
            case 26:
                return 13;
            default:
                throw new IllegalArgumentException("k must be 6, 14, 18 or 26: " + k);
        }
    }

    /** The empty k-DOP: contains nothing, overlaps nothing, and is the identity of {@link #union}. {@code k} is 6, 14, 18 or 26. */
    public static KDop empty(int k) {
        int s = slabsOf(k);
        float[] lo = new float[s], hi = new float[s];
        java.util.Arrays.fill(lo, Float.POSITIVE_INFINITY);
        java.util.Arrays.fill(hi, Float.NEGATIVE_INFINITY);
        return new KDop(s, lo, hi);
    }

    /**
     * The smallest k-DOP with these directions around the {@code vertexCount} points ({@code x, y, z} triples) of {@code xyz} starting at float index {@code offset}. {@code k} is 6,
     * 14, 18 or 26. No points gives the empty k-DOP.
     */
    public static KDop of(int k, float[] xyz, int offset, int vertexCount) {
        int s = slabsOf(k);
        if (vertexCount < 0 || offset < 0 || (long) offset + 3L * vertexCount > xyz.length) {
            throw new IllegalArgumentException(vertexCount + " points at offset " + offset + " do not fit in an array of " + xyz.length);
        }
        int[] dirs = setOf(s);
        double[] lo = new double[s], hi = new double[s];
        java.util.Arrays.fill(lo, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(hi, Double.NEGATIVE_INFINITY);
        for (int i = 0; i < vertexCount; i++) {
            double x = xyz[offset + 3 * i], y = xyz[offset + 3 * i + 1], z = xyz[offset + 3 * i + 2];
            for (int d = 0; d < s; d++) {
                double v = DIRECTIONS[dirs[d]][0] * x + DIRECTIONS[dirs[d]][1] * y + DIRECTIONS[dirs[d]][2] * z;
                lo[d] = Math.min(lo[d], v);
                hi[d] = Math.max(hi[d], v);
            }
        }
        float[] flo = new float[s], fhi = new float[s];
        for (int d = 0; d < s; d++) {
            flo[d] = roundDown(lo[d]);
            fhi[d] = roundUp(hi[d]);
        }
        return new KDop(s, flo, fhi);
    }

    /** {@link #of(int, float[], int, int)} for points starting at index 0. */
    public static KDop of(int k, float[] xyz, int vertexCount) {
        return of(k, xyz, 0, vertexCount);
    }

    /** The k-DOP of a box: exact (its corners decide every slab). An empty box gives the empty k-DOP. */
    public static KDop of(int k, Aabbf box) {
        if (box.isEmpty()) {
            return empty(k);
        }
        float[] c = new float[24];
        for (int i = 0; i < 8; i++) {
            c[3 * i] = box.corner(i).x();
            c[3 * i + 1] = box.corner(i).y();
            c[3 * i + 2] = box.corner(i).z();
        }
        return of(k, c, 0, 8);
    }

    private static float roundDown(double v) {
        float f = (float) v;
        return f > v ? Math.nextDown(f) : f;
    }

    private static float roundUp(double v) {
        float f = (float) v;
        return f < v ? Math.nextUp(f) : f;
    }

    /** The number of directions {@code k}: 6, 14, 18 or 26 (twice the number of slabs, counting each plane of a pair). */
    public int k() {
        return slabs == 3 ? 6 : slabs == 7 ? 14 : slabs == 9 ? 18 : 26;
    }

    /** The number of slabs, {@code k / 2}. */
    public int slabCount() {
        return slabs;
    }

    /** The smallest value of slab {@code i} ({@code d . p} for the unnormalised direction {@code d}); positive infinity for the empty k-DOP. */
    public float min(int i) {
        return min[i];
    }

    /** The largest value of slab {@code i}; negative infinity for the empty k-DOP. */
    public float max(int i) {
        return max[i];
    }

    /** Writes the unnormalised direction of slab {@code i} to {@code out[0 .. 3)}; slabs 0 to 2 are the coordinate axes. */
    public void direction(int i, float[] out) {
        if (i < 0 || i >= slabs) {
            throw new IndexOutOfBoundsException(i);
        }
        out[0] = DIRECTIONS[dirs[i]][0];
        out[1] = DIRECTIONS[dirs[i]][1];
        out[2] = DIRECTIONS[dirs[i]][2];
    }

    /** True when the k-DOP contains nothing. */
    public boolean isEmpty() {
        return min[0] > max[0];
    }

    /** True when the point is inside every slab. */
    public boolean contains(float x, float y, float z) {
        for (int d = 0; d < slabs; d++) {
            double v = DIRECTIONS[dirs[d]][0] * (double) x + DIRECTIONS[dirs[d]][1] * (double) y + DIRECTIONS[dirs[d]][2] * (double) z;
            if (v < min[d] || v > max[d]) {
                return false;
            }
        }
        return true;
    }

    /** True when every slab of {@code o} lies within the matching slab of this one: {@code o} is inside this k-DOP. The two must have the same {@code k}. */
    public boolean contains(KDop o) {
        checkSame(o);
        if (o.isEmpty()) {
            return true;
        }
        for (int d = 0; d < slabs; d++) {
            if (o.min[d] < min[d] || o.max[d] > max[d]) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when no slab separates the two k-DOPs: the intervals of every pair of slabs overlap (touching counts). Conservative as described in the class comment. The two must
     * have the same {@code k}; an empty k-DOP overlaps nothing.
     */
    public boolean overlaps(KDop o) {
        checkSame(o);
        if (isEmpty() || o.isEmpty()) {
            return false;
        }
        for (int d = 0; d < slabs; d++) {
            if (min[d] > o.max[d] || o.min[d] > max[d]) {
                return false;
            }
        }
        return true;
    }

    /** The smallest k-DOP with these directions that contains both. The two must have the same {@code k}. */
    public KDop union(KDop o) {
        checkSame(o);
        float[] lo = new float[slabs], hi = new float[slabs];
        for (int d = 0; d < slabs; d++) {
            lo[d] = Math.min(min[d], o.min[d]);
            hi[d] = Math.max(max[d], o.max[d]);
        }
        return new KDop(slabs, lo, hi);
    }

    /** This k-DOP grown to contain the point (bounds rounded outward). */
    public KDop expand(float x, float y, float z) {
        float[] lo = min.clone(), hi = max.clone();
        for (int d = 0; d < slabs; d++) {
            double v = DIRECTIONS[dirs[d]][0] * (double) x + DIRECTIONS[dirs[d]][1] * (double) y + DIRECTIONS[dirs[d]][2] * (double) z;
            lo[d] = Math.min(lo[d], roundDown(v));
            hi[d] = Math.max(hi[d], roundUp(v));
        }
        return new KDop(slabs, lo, hi);
    }

    /** The axis-aligned box of the first three slabs, which are the coordinate axes; the empty box for the empty k-DOP. */
    public Aabbf aabb() {
        if (isEmpty()) {
            return Aabbf.EMPTY;
        }
        return new Aabbf(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private void checkSame(KDop o) {
        if (o.slabs != slabs) {
            throw new IllegalArgumentException("k-DOPs of different k: " + k() + " and " + o.k());
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KDop other && other.slabs == slabs && java.util.Arrays.equals(min, other.min) && java.util.Arrays.equals(max, other.max);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Arrays.hashCode(min) + java.util.Arrays.hashCode(max);
    }

    @Override
    public String toString() {
        return "KDop[k=" + k() + ", min=" + java.util.Arrays.toString(min) + ", max=" + java.util.Arrays.toString(max) + "]";
    }
}
