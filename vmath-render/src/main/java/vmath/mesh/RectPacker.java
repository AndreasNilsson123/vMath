package vmath.mesh;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * Packs axis-aligned integer rectangles into a bin without overlap: the MaxRects algorithm with the
 * best-short-side-fit rule (Jukka Jylanki, "A Thousand Ways to Pack the Bin"), rectangles placed
 * largest first.
 *
 * <p>It is what atlas building (lightmaps, glyphs, sprites, shadow-map pages) needs, and it is
 * exact in the sense that a successful result never overlaps and never leaves the bin; it is a
 * heuristic in the sense that it can fail on a set that some packing would fit.
 *
 * <p>Rectangles are given as parallel arrays of widths and heights in texels. Padding is the
 * caller's business: add it to the sizes.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * int[] widths = {64, 32, 128};
 * int[] heights = {64, 64, 32};
 * int[] x = new int[3];
 * int[] y = new int[3];
 * boolean[] rotated = new boolean[3];
 * boolean fits = RectPacker.pack(widths, heights, 256, 256, true, x, y, rotated);
 * }</pre>
 */
@Experimental("the heuristic and the result type may change")
public final class RectPacker {

    private RectPacker() {
    }

    /**
     * A packing: the bin size used and, per input rectangle, its position and whether it was
     * rotated by 90 degrees (width and height swapped).
     *
     * <p>The arrays are the result's own and are not copied, because they can be as large as
     * the mesh: treat them as read-only, and note that two results are equal only if they hold
     * the same arrays.
     *
     * @param width the width
     * @param height the height
     * @param x the x
     * @param y the y
     * @param rotated whether rotated
     */
    public record Result(int width, int height, int[] x, int[] y, boolean[] rotated) {
    }

    /**
     * Tries to pack into a {@code binWidth} x {@code binHeight} bin.
     *
     * <p>On success fills {@code outX}, {@code outY} and {@code outRotated} (each of length at
     * least the rectangle count) and returns true; on failure returns false and the outputs are
     * undefined.
     *
     * @param w the widths of the rectangles
     * @param h the heights of the rectangles
     * @param binWidth the bin width
     * @param binHeight the bin height
     * @param allowRotation permit turning a rectangle by 90 degrees; a rotated rectangle occupies
     *     {@code h x w}
     * @param outX the out x
     * @param outY the out y
     * @param outRotated whether out rotated
     * @return {@code true} if every rectangle fits into the bin; {@code false} otherwise
     * @throws IllegalArgumentException if {@code w} and {@code h} differ in length or a rectangle
     *     is smaller than 1 in a dimension
     */
    public static boolean pack(int[] w, int[] h, int binWidth, int binHeight, boolean allowRotation, int[] outX, int[] outY, boolean[] outRotated) {
        int n = w.length;
        if (h.length != n) {
            throw new IllegalArgumentException("w and h differ in length");
        }
        for (int i = 0; i < n; i++) {
            if (w[i] < 1 || h[i] < 1) {
                throw new IllegalArgumentException("rectangle " + i + " has a size below 1: " + w[i] + " x " + h[i]);
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        // largest first: by the longer side, then by area
        Arrays.sort(order, (a, b) -> {
            int la = Math.max(w[a], h[a]), lb = Math.max(w[b], h[b]);
            if (la != lb) {
                return Integer.compare(lb, la);
            }
            return Long.compare((long) w[b] * h[b], (long) w[a] * h[a]);
        });
        FreeRects free = new FreeRects(binWidth, binHeight);
        for (int oi = 0; oi < n; oi++) {
            int r = order[oi];
            int found = free.bestFit(w[r], h[r], allowRotation);
            if (found < 0) {
                return false;
            }
            int bi = found >> 1;
            boolean bestRot = (found & 1) == 1;
            int pw = bestRot ? h[r] : w[r], ph = bestRot ? w[r] : h[r];
            int px = free.x[bi], py = free.y[bi];
            outX[r] = px;
            outY[r] = py;
            outRotated[r] = bestRot;
            free.place(px, py, pw, ph);
        }
        return true;
    }

    /**
     * The free rectangles of the bin in the maximal-rectangles method: after a rectangle is
     * placed, every free rectangle it overlaps is split around it, and the ones that lie inside
     * another are dropped.
     */
    private static final class FreeRects {
        int[] x = new int[16], y = new int[16], w = new int[16], h = new int[16];
        int count = 1;

        FreeRects(int binWidth, int binHeight) {
            w[0] = binWidth;
            h[0] = binHeight;
        }

        // the free rectangle with the smallest leftover on its shorter side (then on the longer), for a rectangle of rw x rh or, if allowed, turned; returns index * 2 + (turned ? 1 : 0), or -1
        int bestFit(int rw0, int rh0, boolean allowRotation) {
            int bestShort = Integer.MAX_VALUE, bestLong = Integer.MAX_VALUE, bi = -1;
            boolean bestRot = false;
            for (int pass = 0; pass < (allowRotation ? 2 : 1); pass++) {
                int rw = pass == 0 ? rw0 : rh0, rh = pass == 0 ? rh0 : rw0;
                for (int f = 0; f < count; f++) {
                    if (rw <= w[f] && rh <= h[f]) {
                        int dw = w[f] - rw, dh = h[f] - rh;
                        int shortSide = Math.min(dw, dh), longSide = Math.max(dw, dh);
                        if (shortSide < bestShort || (shortSide == bestShort && longSide < bestLong)) {
                            bestShort = shortSide;
                            bestLong = longSide;
                            bi = f;
                            bestRot = pass == 1;
                        }
                    }
                }
            }
            return bi < 0 ? -1 : bi * 2 + (bestRot ? 1 : 0);
        }

        // keeps the free rectangles the placed one does not touch and splits each one it does touch into up to four, then prunes
        void place(int px, int py, int pw, int ph) {
            int cap = count * 5 + 4;
            int[] nx = new int[cap], ny = new int[cap], nw = new int[cap], nh = new int[cap];
            int m = 0;
            for (int f = 0; f < count; f++) {
                int ox = x[f], oy = y[f], ow = w[f], oh = h[f];
                if (px >= ox + ow || px + pw <= ox || py >= oy + oh || py + ph <= oy) {
                    nx[m] = ox;
                    ny[m] = oy;
                    nw[m] = ow;
                    nh[m] = oh;
                    m++;
                    continue;
                }
                if (px > ox) { // left part
                    nx[m] = ox;
                    ny[m] = oy;
                    nw[m] = px - ox;
                    nh[m] = oh;
                    m++;
                }
                if (px + pw < ox + ow) { // right part
                    nx[m] = px + pw;
                    ny[m] = oy;
                    nw[m] = ox + ow - (px + pw);
                    nh[m] = oh;
                    m++;
                }
                if (py > oy) { // top part
                    nx[m] = ox;
                    ny[m] = oy;
                    nw[m] = ow;
                    nh[m] = py - oy;
                    m++;
                }
                if (py + ph < oy + oh) { // bottom part
                    nx[m] = ox;
                    ny[m] = py + ph;
                    nw[m] = ow;
                    nh[m] = oy + oh - (py + ph);
                    m++;
                }
            }
            x = nx;
            y = ny;
            w = nw;
            h = nh;
            count = m;
            prune();
        }

        // drops every free rectangle that lies inside another
        private void prune() {
            for (int a = 0; a < count; a++) {
                for (int b = 0; b < count; b++) {
                    if (a != b && x[a] >= x[b] && y[a] >= y[b] && x[a] + w[a] <= x[b] + w[b] && y[a] + h[a] <= y[b] + h[b]) {
                        count--;
                        x[a] = x[count];
                        y[a] = y[count];
                        w[a] = w[count];
                        h[a] = h[count];
                        a--;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Packs rectangles into the smallest power-of-two atlas that fits them, by trying growing sizes
     * with the same packer; useful for texture atlases that must be power-of-two sized.
     *
     * @param w the widths of the rectangles
     * @param h the heights of the rectangles
     * @param maxSize the max size
     * @param allowRotation {@code true} to permit turning a rectangle by 90 degrees, as for
     *     {@link #pack}
     * @return the smallest power-of-two bin (width and height each a power of two, not larger than
     *     {@code maxSize}) that {@link #pack} fits everything into, trying the smaller areas first;
     *     {@code null} when even {@code maxSize} x {@code maxSize} is not enough
     */
    public static Result packSmallestPowerOfTwo(int[] w, int[] h, int maxSize, boolean allowRotation) {
        int n = w.length;
        long area = 0;
        for (int i = 0; i < n; i++) {
            area += (long) w[i] * h[i];
        }
        int levels = 32 - Integer.numberOfLeadingZeros(Math.max(1, maxSize));
        long[] candidates = new long[levels * levels];
        int c = 0;
        for (int i = 0; i < levels; i++) {
            for (int j = 0; j < levels; j++) {
                int bw = 1 << i, bh = 1 << j;
                if (bw <= maxSize && bh <= maxSize && (long) bw * bh >= area) {
                    candidates[c++] = ((long) bw * bh << 20) | ((long) i << 10) | j;
                }
            }
        }
        Arrays.sort(candidates, 0, c);
        int[] x = new int[n], y = new int[n];
        boolean[] rot = new boolean[n];
        for (int k = 0; k < c; k++) {
            int i = (int) ((candidates[k] >> 10) & 1023), j = (int) (candidates[k] & 1023);
            if (pack(w, h, 1 << i, 1 << j, allowRotation, x, y, rot)) {
                return new Result(1 << i, 1 << j, x, y, rot);
            }
        }
        return null;
    }
}
