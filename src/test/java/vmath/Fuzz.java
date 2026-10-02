package vmath;

import java.util.SplittableRandom;

/** Byte-level damage for the fuzz tests: the same mutations for every loader (see {@code LoaderFuzzTest}, {@code Ktx2FuzzTest}). */
public final class Fuzz {

    private Fuzz() {
    }

    /**
     * A damaged copy of {@code original}: bit flips, a byte overwritten, truncation, insertion, deletion, a 32-bit word replaced by an edge value, or scattered byte changes,
     * all inside {@code [lo, hi)} (truncation, insertion and deletion move the tail). {@code how} receives a description of what was done, so a failure can be replayed.
     */
    public static byte[] mutate(byte[] original, SplittableRandom r, StringBuilder how, int lo, int hi) {
        byte[] b = original.clone();
        int kind = r.nextInt(7);
        how.append("[").append(lo).append(",").append(hi).append(") ");
        switch (kind) {
            case 0 -> {
                int flips = 1 + r.nextInt(4);
                for (int i = 0; i < flips; i++) {
                    int at = lo + r.nextInt(hi - lo), bit = r.nextInt(8);
                    b[at] ^= (byte) (1 << bit);
                    how.append("flip byte ").append(at).append(" bit ").append(bit).append("; ");
                }
            }
            case 1 -> {
                int at = lo + r.nextInt(hi - lo);
                byte v = switch (r.nextInt(3)) {
                    case 0 -> 0;
                    case 1 -> (byte) 0xFF;
                    default -> (byte) r.nextInt(256);
                };
                b[at] = v;
                how.append("set byte ").append(at).append(" to ").append(v & 0xFF);
            }
            case 2 -> {
                int len = lo + r.nextInt(hi - lo);
                how.append("truncate to ").append(len);
                return java.util.Arrays.copyOf(b, len);
            }
            case 3 -> {
                int at = lo + r.nextInt(hi - lo + 1), n = 1 + r.nextInt(32);
                byte[] out = new byte[b.length + n];
                System.arraycopy(b, 0, out, 0, at);
                for (int i = 0; i < n; i++) {
                    out[at + i] = (byte) r.nextInt(256);
                }
                System.arraycopy(b, at, out, at + n, b.length - at);
                how.append("insert ").append(n).append(" bytes at ").append(at);
                return out;
            }
            case 4 -> {
                int at = lo + r.nextInt(hi - lo), n = Math.min(hi - at, 1 + r.nextInt(32));
                byte[] out = new byte[b.length - n];
                System.arraycopy(b, 0, out, 0, at);
                System.arraycopy(b, at + n, out, at, b.length - at - n);
                how.append("delete ").append(n).append(" bytes at ").append(at);
                return out;
            }
            case 5 -> {
                if (hi - lo >= 4) {
                    int at = lo + r.nextInt((hi - lo) / 4) * 4;
                    int[] edge = {0, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 1, 255, 65536, 0x7FFFFFF0};
                    int v = edge[r.nextInt(edge.length)];
                    for (int i = 0; i < 4; i++) {
                        b[at + i] = (byte) (v >>> (8 * i));
                    }
                    how.append("word at ").append(at).append(" := ").append(v);
                }
            }
            default -> {
                // several scattered byte changes
                int n = 2 + r.nextInt(6);
                for (int i = 0; i < n; i++) {
                    b[lo + r.nextInt(hi - lo)] = (byte) r.nextInt(256);
                }
                how.append("scatter ").append(n).append(" bytes");
            }
        }
        return b;
    }
}
