package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The table-driven encoders against Skilling's algorithm written out bit by bit ({@code encode2Reference}, {@code encode3Reference}): the same code for every input. */
class HilbertAutomatonTest {

    @Test
    void allCellsOfSmallGridsMatchTheReference() {
        for (int bits = 1; bits <= 4; bits++) {
            int n = 1 << bits;
            for (int x = 0; x < n; x++) {
                for (int y = 0; y < n; y++) {
                    assertEquals(Hilbert.encode2Reference(x, y, bits), Hilbert.encode2(x, y, bits), "2D bits " + bits + " cell " + x + "," + y);
                    for (int z = 0; z < n; z++) {
                        assertEquals(Hilbert.encode3Reference(x, y, z, bits), Hilbert.encode3(x, y, z, bits), "3D bits " + bits + " cell " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    @Test
    void randomCellsAtEveryWidthMatchTheReference() {
        SplittableRandom r = new SplittableRandom(Rnd.SEED);
        int trials = Math.max(2000, Rnd.N);
        for (int bits = 1; bits <= 32; bits++) {
            for (int i = 0; i < trials; i++) {
                int x = bits == 32 ? r.nextInt() : r.nextInt() >>> (32 - bits);
                int y = bits == 32 ? r.nextInt() : r.nextInt() >>> (32 - bits);
                assertEquals(Hilbert.encode2Reference(x, y, bits), Hilbert.encode2(x, y, bits), "2D bits " + bits + " cell " + x + "," + y);
                if (bits <= Hilbert.MAX_BITS_3D) {
                    int z = r.nextInt() >>> (32 - bits);
                    int x3 = x & ((1 << bits) - 1), y3 = y & ((1 << bits) - 1);
                    assertEquals(Hilbert.encode3Reference(x3, y3, z, bits), Hilbert.encode3(x3, y3, z, bits), "3D bits " + bits + " cell " + x3 + "," + y3 + "," + z);
                }
            }
        }
    }

    @Test
    void extremeCellsMatchTheReference() {
        for (int bits = 1; bits <= Hilbert.MAX_BITS_3D; bits++) {
            int max = (1 << bits) - 1;
            int[] values = {0, 1 % (max + 1), max, max >>> 1, max & 0x15555555, max & 0x2AAAAAAA};
            for (int x : values) {
                for (int y : values) {
                    for (int z : values) {
                        assertEquals(Hilbert.encode3Reference(x, y, z, bits), Hilbert.encode3(x, y, z, bits));
                    }
                }
            }
        }
        for (int bits = 1; bits <= Hilbert.MAX_BITS_2D; bits++) {
            int max = bits == 32 ? -1 : (1 << bits) - 1;
            for (int x : new int[] {0, 1, max, max >>> 1}) {
                for (int y : new int[] {0, 1, max, max >>> 1}) {
                    assertEquals(Hilbert.encode2Reference(x, y, bits), Hilbert.encode2(x, y, bits));
                }
            }
        }
    }
}
