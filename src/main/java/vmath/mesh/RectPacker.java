package vmath.mesh;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * Packs axis-aligned integer rectangles into a bin without overlap: the MaxRects algorithm with the best-short-side-fit rule (Jukka Jylanki, "A Thousand Ways
 * to Pack the Bin"), rectangles placed largest first. It is what atlas building (lightmaps, glyphs, sprites, shadow-map pages) needs, and it is exact in
 * the sense that a successful result never overlaps and never leaves the bin; it is a heuristic in the sense that it can fail on a set that some packing
 * would fit.
 *
 * <p>Rectangles are given as parallel arrays of widths and heights in texels. Padding is the caller's business: add it to the sizes.
 */
@Experimental("the heuristic and the result type may change")
public final class RectPacker {

    private RectPacker() {
    }

    /** A packing: the bin size used and, per input rectangle, its position and whether it was rotated by 90 degrees (width and height swapped). */
    public record Result(int width, int height, int[] x, int[] y, boolean[] rotated) {
    }

    /**
     * Tries to pack into a {@code binWidth} x {@code binHeight} bin. On success fills {@code outX}, {@code outY} and {@code outRotated} (each of length at
     * least the rectangle count) and returns true; on failure returns false and the outputs are undefined.
     *
     * @param allowRotation permit turning a rectangle by 90 degrees; a rotated rectangle occupies {@code h x w}
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
        int[] fx = new int[16], fy = new int[16], fw = new int[16], fh = new int[16];
        int free = 1;
        fx[0] = 0;
        fy[0] = 0;
        fw[0] = binWidth;
        fh[0] = binHeight;
        for (int oi = 0; oi < n; oi++) {
            int r = order[oi];
            int bestShort = Integer.MAX_VALUE, bestLong = Integer.MAX_VALUE, bi = -1;
            boolean bestRot = false;
            for (int pass = 0; pass < (allowRotation ? 2 : 1); pass++) {
                int rw = pass == 0 ? w[r] : h[r], rh = pass == 0 ? h[r] : w[r];
                for (int f = 0; f < free; f++) {
                    if (rw <= fw[f] && rh <= fh[f]) {
                        int dw = fw[f] - rw, dh = fh[f] - rh;
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
            if (bi < 0) {
                return false;
            }
            int pw = bestRot ? h[r] : w[r], ph = bestRot ? w[r] : h[r];
            int px = fx[bi], py = fy[bi];
            outX[r] = px;
            outY[r] = py;
            outRotated[r] = bestRot;
            // keep the free rectangles the placed one does not touch; split each one it does touch into up to four
            int cap = free * 5 + 4;
            int[] nx = new int[cap], ny = new int[cap], nw = new int[cap], nh = new int[cap];
            int m = 0;
            for (int f = 0; f < free; f++) {
                int ox = fx[f], oy = fy[f], ow = fw[f], oh = fh[f];
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
            fx = nx;
            fy = ny;
            fw = nw;
            fh = nh;
            free = m;
            // prune
            for (int a = 0; a < free; a++) {
                for (int b = 0; b < free; b++) {
                    if (a != b && fx[a] >= fx[b] && fy[a] >= fy[b] && fx[a] + fw[a] <= fx[b] + fw[b] && fy[a] + fh[a] <= fy[b] + fh[b]) {
                        free--;
                        fx[a] = fx[free];
                        fy[a] = fy[free];
                        fw[a] = fw[free];
                        fh[a] = fh[free];
                        a--;
                        break;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The smallest power-of-two bin (width and height each a power of two, not larger than {@code maxSize}) that {@link #pack} fits everything into, trying
     * the smaller areas first; {@code null} when even {@code maxSize} x {@code maxSize} is not enough.
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
