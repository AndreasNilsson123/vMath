package vmath.bulk;

import vmath.annotations.Experimental;
import vmath.core.Hilbert;
import vmath.core.Morton;

/**
 * Orders points along a space-filling curve, so that points that are close in space end up close in
 * memory: sort instances, particles or mesh vertices with it before building a BVH or uploading,
 * and neighbouring work touches neighbouring memory.
 *
 * <p>Both curves use 21 bits per axis over the bounding box of the points, and the sort is the
 * stable {@link RadixSorter} (equal codes keep their input order).
 *
 * <p>{@link Curve#HILBERT} has no long jumps and so the better locality; {@link Curve#MORTON} costs
 * less to compute. {@code docs/BULK.md} has the measured difference in path length and in time.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float[] positions = {5f, 0f, 0f, 0f, 0f, 0f, 2f, 0f, 0f};
 * int[] order = LocalityOrder.order(positions, 3, LocalityOrder.Curve.HILBERT);   // the indices in space-filling-curve order
 * }</pre>
 */
@Experimental("the set of helpers may grow")
public final class LocalityOrder {

    /**
     * Which curve to follow.
     */
    public enum Curve {
        /**
         * Z-order: cheap to compute, with larger jumps between neighbouring cells.
         */
        MORTON,
        /**
         * Hilbert order: better locality, at several times the cost of Morton per code.
         */
        HILBERT
    }

    private LocalityOrder() {
    }

    /**
     * Writes to {@code order[0..n)} the permutation that puts the {@code n} points of {@code xyz}
     * (three floats per point, packed) in curve order: {@code order[i]} is the index of the point
     * that comes {@code i}-th.
     *
     * <p>{@code codes} is scratch of at least {@code n} longs; with a reused {@code sorter} (see
     * {@link RadixSorter#reserve}) nothing is allocated.
     *
     * @param xyz the three components
     * @param n the number of elements
     * @param curve the curve; must not be {@code null}
     * @param order the order
     * @param codes the codes
     * @param sorter the sorter; must not be {@code null}
     * @throws IllegalArgumentException if a coordinate is not finite or an array is too short
     */
    public static void order(float[] xyz, int n, Curve curve, int[] order, long[] codes, RadixSorter sorter) {
        if (n < 0 || xyz.length < 3 * n || order.length < n || codes.length < n) {
            throw new IllegalArgumentException("arrays too short for n = " + n);
        }
        if (n == 0) {
            return;
        }
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < n; i++) {
            float x = xyz[3 * i], y = xyz[3 * i + 1], z = xyz[3 * i + 2];
            if (!(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z))) {
                throw new IllegalArgumentException("coordinate of point " + i + " is not finite");
            }
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        int cells = Morton.MAX_3D + 1;
        // a flat axis (all points equal) maps to cell 0; the extent is taken in double so that a huge range cannot overflow
        double sx = scale(minX, maxX, cells), sy = scale(minY, maxY, cells), sz = scale(minZ, maxZ, cells);
        for (int i = 0; i < n; i++) {
            int x = cell(xyz[3 * i], minX, sx, cells), y = cell(xyz[3 * i + 1], minY, sy, cells), z = cell(xyz[3 * i + 2], minZ, sz, cells);
            codes[i] = curve == Curve.HILBERT ? Hilbert.encode3(x, y, z, Hilbert.MAX_BITS_3D) : Morton.encode3(x, y, z);
            order[i] = i;
        }
        sorter.sort(codes, order, n);
    }

    private static double scale(float min, float max, int cells) {
        double extent = (double) max - (double) min;
        return extent > 0 ? cells / extent : 0;
    }

    private static int cell(float v, float min, double scale, int cells) {
        int q = (int) (((double) v - (double) min) * scale);
        return Math.min(Math.max(q, 0), cells - 1);
    }

    /**
     * Computes a spatial sort order of points along a space-filling curve, using its own scratch
     * memory; the overload with caller-supplied scratch avoids the allocation.
     *
     * @param xyz the three components
     * @param n the number of elements
     * @param curve the curve; must not be {@code null}
     * @return as {@link #order(float[], int, Curve, int[], long[], RadixSorter)}, allocating its
     *     own scratch and sorter; returns the order
     */
    public static int[] order(float[] xyz, int n, Curve curve) {
        int[] order = new int[n];
        order(xyz, n, curve, order, new long[n], new RadixSorter());
        return order;
    }
}
