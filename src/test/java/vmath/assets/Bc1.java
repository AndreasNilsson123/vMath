package vmath.assets;

/**
 * A small BC1 (DXT1) block encoder and decoder for test data. The encoder picks the two colours of a block that are furthest apart along the
 * brightest-to-darkest direction and assigns each texel the nearest of the four palette colours: good enough for images made of a few flat colours, and exact for them.
 */
public final class Bc1 {

    private Bc1() {
    }

    private static int r5(int c) {
        return Math.min(31, (c * 31 + 127) / 255);
    }

    private static int g6(int c) {
        return Math.min(63, (c * 63 + 127) / 255);
    }

    private static int pack565(int argb) {
        return (r5((argb >> 16) & 255) << 11) | (g6((argb >> 8) & 255) << 5) | r5(argb & 255);
    }

    private static int unpack565(int c) {
        int r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
        return 0xFF000000 | ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
    }

    private static int luma(int argb) {
        return 299 * ((argb >> 16) & 255) + 587 * ((argb >> 8) & 255) + 114 * (argb & 255);
    }

    private static int blend(int a, int b, int wa, int wb) {
        int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / (wa + wb);
        int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / (wa + wb);
        int bl = ((a & 255) * wa + (b & 255) * wb) / (wa + wb);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static long dist(int a, int b) {
        long dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return dr * dr + dg * dg + db * db;
    }

    /** Encodes an image into 8-byte blocks, row of blocks after row of blocks; edges are padded by repeating the last texel. */
    public static byte[] encode(int width, int height, int[] argb) {
        int bw = (width + 3) / 4, bh = (height + 3) / 4;
        Bin out = new Bin();
        int[] block = new int[16];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        block[y * 4 + x] = argb[Math.min(height - 1, by * 4 + y) * width + Math.min(width - 1, bx * 4 + x)];
                    }
                }
                int hi = block[0], lo = block[0];
                for (int p : block) {
                    if (luma(p) > luma(hi)) {
                        hi = p;
                    }
                    if (luma(p) < luma(lo)) {
                        lo = p;
                    }
                }
                int c0 = pack565(hi), c1 = pack565(lo);
                if (c0 < c1) {
                    int t = c0;
                    c0 = c1;
                    c1 = t;
                }
                long indices = 0;
                if (c0 != c1) {
                    int[] palette = {unpack565(c0), unpack565(c1), blend(unpack565(c0), unpack565(c1), 2, 1), blend(unpack565(c0), unpack565(c1), 1, 2)};
                    for (int i = 0; i < 16; i++) {
                        int best = 0;
                        for (int k = 1; k < 4; k++) {
                            if (dist(block[i], palette[k]) < dist(block[i], palette[best])) {
                                best = k;
                            }
                        }
                        indices |= (long) best << (2 * i);
                    }
                }
                out.u16(c0, c1).u32(indices);
            }
        }
        return out.toBytes();
    }

    /** Decodes {@code width x height} texels from blocks written by {@link #encode} (or any four-colour BC1 data). */
    public static int[] decode(int width, int height, byte[] blocks, int offset) {
        int bw = (width + 3) / 4;
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = offset + ((y / 4) * bw + (x / 4)) * 8;
                int c0 = (blocks[p] & 255) | (blocks[p + 1] & 255) << 8, c1 = (blocks[p + 2] & 255) | (blocks[p + 3] & 255) << 8;
                long idx = (blocks[p + 4] & 255L) | (blocks[p + 5] & 255L) << 8 | (blocks[p + 6] & 255L) << 16 | (blocks[p + 7] & 255L) << 24;
                int k = (int) ((idx >> (2 * ((y % 4) * 4 + (x % 4)))) & 3);
                int a = unpack565(c0), b = unpack565(c1);
                int color;
                if (c0 > c1) {
                    color = k == 0 ? a : k == 1 ? b : k == 2 ? blend(a, b, 2, 1) : blend(a, b, 1, 2);
                } else {
                    color = k == 0 ? a : k == 1 ? b : k == 2 ? blend(a, b, 1, 1) : 0;
                }
                out[y * width + x] = color;
            }
        }
        return out;
    }
}
