package vmath.lines;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * Removes the points of a polyline that a given tolerance makes invisible: the Douglas-Peucker
 * algorithm, which keeps the outline, and the Visvalingam-Whyatt algorithm, which removes the
 * point that changes the area least and gives smoother results.
 *
 * <p>Both work in two steps so that a map can follow its zoom for free. {@link
 * #douglasPeuckerImportance} and {@link #visvalingamImportance} give every point an
 * <b>importance</b>, once, when the polyline is made or changed. {@link #select} then keeps the
 * points whose importance is above a threshold, for any threshold, in one pass over the points
 * without allocation: the result is exactly what running the algorithm with that tolerance gives.
 * The threshold for a zoom is the size of the tolerance in world units: {@link #worldTolerance}
 * turns pixels into it.
 *
 * <p>The importance of a Douglas-Peucker point is the distance (in world units, in three
 * dimensions) at which it stops being needed; that of a Visvalingam point is the area (in squared
 * world units) of the triangle it makes with its neighbours at the moment it is removed, never
 * smaller than that of a point removed before it. The ends have an infinite importance and are
 * always kept. A closed polyline is simplified as the open path that starts and ends at its first
 * point (repeat the first point at the end).
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads on different arrays.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double[] importance = new double[n];
 * LineSimplifier.douglasPeuckerImportance(xyz, 0, n, importance);       // once per polyline
 * double tolerance = LineSimplifier.worldTolerance(1.5, pixelsPerWorldUnit);
 * int kept = LineSimplifier.copySelected(xyz, 0, n, importance, tolerance, out, 0);   // every frame the zoom changes
 * }</pre>
 */
@Experimental("the simplification may change")
public final class LineSimplifier {

    private LineSimplifier() {
    }

    /**
     * Converts a tolerance in pixels to world units.
     *
     * @param pixels the tolerance on the screen, in pixels
     * @param pixelsPerWorldUnit the scale of the view, more than 0
     * @return the tolerance in world units
     * @throws IllegalArgumentException if the scale is not more than 0
     */
    public static double worldTolerance(double pixels, double pixelsPerWorldUnit) {
        if (!(pixelsPerWorldUnit > 0)) {
            throw new IllegalArgumentException("the scale must be more than 0: " + pixelsPerWorldUnit);
        }
        return pixels / pixelsPerWorldUnit;
    }

    private static void check(double[] xyz, int offset, int n, double[] importance) {
        if (n < 0 || offset < 0 || xyz.length < offset + 3L * n) {
            throw new IllegalArgumentException("the array has " + xyz.length + " values for " + n + " points at " + offset);
        }
        if (importance.length < n) {
            throw new IllegalArgumentException("the importance array has " + importance.length + " values for " + n + " points");
        }
    }

    private static double distanceToSegment(double[] p, int a, int b, int k) {
        double ax = p[a], ay = p[a + 1], az = p[a + 2];
        double dx = p[b] - ax, dy = p[b + 1] - ay, dz = p[b + 2] - az;
        double px = p[k] - ax, py = p[k + 1] - ay, pz = p[k + 2] - az;
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 > 0 ? Math.max(0, Math.min(1, (px * dx + py * dy + pz * dz) / len2)) : 0;
        double ex = px - t * dx, ey = py - t * dy, ez = pz - t * dz;
        return Math.sqrt(ex * ex + ey * ey + ez * ez);
    }

    /**
     * Gives every point its Douglas-Peucker importance. Allocates a working stack of about the
     * size of the polyline.
     *
     * @param xyz the points as {@code x, y, z} triples
     * @param offset the index in {@code xyz} of the first value
     * @param n the number of points
     * @param importance receives the importance of each of the {@code n} points
     * @throws IllegalArgumentException if an array is too short
     */
    public static void douglasPeuckerImportance(double[] xyz, int offset, int n, double[] importance) {
        check(xyz, offset, n, importance);
        if (n == 0) {
            return;
        }
        importance[0] = Double.POSITIVE_INFINITY;
        importance[n - 1] = Double.POSITIVE_INFINITY;
        if (n < 3) {
            return;
        }
        int[] stack = new int[2 * n + 4];
        double[] parent = new double[n + 2];
        int top = 0;
        stack[top++] = 0;
        stack[top++] = n - 1;
        int depth = 0;
        parent[depth++] = Double.POSITIVE_INFINITY;
        while (top > 0) {
            int hi = stack[--top], lo = stack[--top];
            double inherited = parent[--depth];
            if (hi - lo < 2) {
                continue;
            }
            int a = offset + 3 * lo, b = offset + 3 * hi;
            int far = -1;
            double best = -1;
            for (int k = lo + 1; k < hi; k++) {
                double d = distanceToSegment(xyz, a, b, offset + 3 * k);
                if (d > best) {
                    best = d;
                    far = k;
                }
            }
            double effective = Math.min(best, inherited); // a point is kept only if the points that decided its range are
            importance[far] = effective;
            stack[top++] = lo;
            stack[top++] = far;
            parent[depth++] = effective;
            stack[top++] = far;
            stack[top++] = hi;
            parent[depth++] = effective;
        }
    }

    /**
     * Gives every point its Visvalingam-Whyatt importance. Allocates working arrays of about the
     * size of the polyline.
     *
     * @param xyz the points as {@code x, y, z} triples
     * @param offset the index in {@code xyz} of the first value
     * @param n the number of points
     * @param importance receives the importance of each of the {@code n} points
     * @throws IllegalArgumentException if an array is too short
     */
    public static void visvalingamImportance(double[] xyz, int offset, int n, double[] importance) {
        check(xyz, offset, n, importance);
        if (n == 0) {
            return;
        }
        importance[0] = Double.POSITIVE_INFINITY;
        importance[n - 1] = Double.POSITIVE_INFINITY;
        if (n < 3) {
            return;
        }
        int[] prev = new int[n], next = new int[n], version = new int[n];
        double[] area = new double[n];
        boolean[] removed = new boolean[n];
        Heap heap = new Heap(3 * n);
        for (int i = 0; i < n; i++) {
            prev[i] = i - 1;
            next[i] = i + 1;
        }
        for (int i = 1; i < n - 1; i++) {
            area[i] = triangle(xyz, offset, i - 1, i, i + 1);
            heap.push(area[i], i, 0);
        }
        double floor = 0;
        int remaining = n - 2;
        while (remaining > 0 && heap.size > 0) {
            int i = heap.topItem(), v = heap.topVersion();
            double a = heap.topKey();
            heap.pop();
            if (removed[i] || version[i] != v) {
                continue; // an out-of-date entry
            }
            floor = Math.max(floor, a);
            importance[i] = floor;
            removed[i] = true;
            remaining--;
            int p = prev[i], q = next[i];
            next[p] = q;
            prev[q] = p;
            if (p > 0) {
                area[p] = triangle(xyz, offset, prev[p], p, q);
                heap.push(area[p], p, ++version[p]);
            }
            if (q < n - 1) {
                area[q] = triangle(xyz, offset, p, q, next[q]);
                heap.push(area[q], q, ++version[q]);
            }
        }
    }

    private static double triangle(double[] p, int offset, int i, int j, int k) {
        int a = offset + 3 * i, b = offset + 3 * j, c = offset + 3 * k;
        double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
        double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
        double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** A binary min-heap of (key, item, version) triples. */
    private static final class Heap {
        double[] key;
        int[] item;
        int[] ver;
        int size;

        Heap(int capacity) {
            key = new double[capacity];
            item = new int[capacity];
            ver = new int[capacity];
        }

        double topKey() {
            return key[0];
        }

        int topItem() {
            return item[0];
        }

        int topVersion() {
            return ver[0];
        }

        void push(double k, int i, int v) {
            if (size == key.length) {
                key = Arrays.copyOf(key, size * 2);
                item = Arrays.copyOf(item, size * 2);
                ver = Arrays.copyOf(ver, size * 2);
            }
            int at = size++;
            while (at > 0) {
                int parent = (at - 1) >>> 1;
                if (key[parent] <= k) {
                    break;
                }
                key[at] = key[parent];
                item[at] = item[parent];
                ver[at] = ver[parent];
                at = parent;
            }
            key[at] = k;
            item[at] = i;
            ver[at] = v;
        }

        void pop() {
            size--;
            if (size == 0) {
                return;
            }
            double k = key[size];
            int i = item[size], v = ver[size];
            int at = 0;
            while (true) {
                int child = 2 * at + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && key[child + 1] < key[child]) {
                    child++;
                }
                if (key[child] >= k) {
                    break;
                }
                key[at] = key[child];
                item[at] = item[child];
                ver[at] = ver[child];
                at = child;
            }
            key[at] = k;
            item[at] = i;
            ver[at] = v;
        }
    }

    /**
     * Lists the points to keep for a threshold.
     *
     * @param importance the importance of {@code n} points, from one of the importance methods
     * @param n the number of points
     * @param threshold the tolerance: points whose importance is above it are kept (in world
     *     units for Douglas-Peucker, squared world units for Visvalingam)
     * @param indices receives the indices of the points to keep, in ascending order; must have
     *     room for {@code n} values
     * @return the number of points kept
     * @throws IllegalArgumentException if an array is too short
     */
    public static int select(double[] importance, int n, double threshold, int[] indices) {
        if (importance.length < n || indices.length < n) {
            throw new IllegalArgumentException("the arrays must hold " + n + " values");
        }
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (importance[i] > threshold) {
                indices[kept++] = i;
            }
        }
        return kept;
    }

    /**
     * Copies the points to keep for a threshold.
     *
     * @param xyz the points as triples
     * @param offset the index in {@code xyz} of the first value
     * @param n the number of points
     * @param importance the importance of the points
     * @param threshold see {@link #select}
     * @param out receives the kept points as triples; must have room for the result, at most
     *     {@code n} points
     * @param outOffset the index in {@code out} of the first value
     * @return the number of points copied
     * @throws IllegalArgumentException if an array is too short
     */
    public static int copySelected(double[] xyz, int offset, int n, double[] importance, double threshold, double[] out, int outOffset) {
        check(xyz, offset, n, importance);
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (importance[i] > threshold) {
                if (out.length < outOffset + 3 * (kept + 1)) {
                    throw new IllegalArgumentException("the output array is too short");
                }
                System.arraycopy(xyz, offset + 3 * i, out, outOffset + 3 * kept, 3);
                kept++;
            }
        }
        return kept;
    }

    /**
     * Simplifies a polyline with Douglas-Peucker for one tolerance (the one-shot form: it computes
     * the importance and discards it).
     *
     * @param xyz the points as triples
     * @param offset the index in {@code xyz} of the first value
     * @param n the number of points
     * @param tolerance the largest distance in world units that the simplified line may be from a
     *     removed point
     * @param out receives the kept points; must have room for {@code n} points
     * @param outOffset the index in {@code out} of the first value
     * @return the number of points copied
     * @throws IllegalArgumentException if an array is too short or the tolerance is negative
     */
    public static int douglasPeucker(double[] xyz, int offset, int n, double tolerance, double[] out, int outOffset) {
        if (!(tolerance >= 0)) {
            throw new IllegalArgumentException("the tolerance must not be negative: " + tolerance);
        }
        double[] importance = new double[n];
        douglasPeuckerImportance(xyz, offset, n, importance);
        return copySelected(xyz, offset, n, importance, tolerance, out, outOffset);
    }
}
