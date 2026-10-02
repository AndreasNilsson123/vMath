package vmath.mem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.mem.FreeListAllocator.Strategy;

/**
 * {@link FreeListAllocator} against a plain list-of-blocks model with the documented rules (blocks tile the range, first fit takes the lowest address that fits,
 * best fit the least waste and, on ties, the lowest address, alignment splits off the padding in front, a freed block merges with free neighbours): the same offsets
 * for every request, through thousands of blocks, which also moves the free-block bitmap across many 64-bit words.
 */
class FreeListReferenceTest {

    private static final class Model {
        private final List<long[]> blocks = new ArrayList<>(); // start, length, free (1 or 0)
        private final Strategy strategy;

        Model(long capacity, Strategy strategy) {
            this.strategy = strategy;
            blocks.add(new long[] {0, capacity, 1});
        }

        long allocate(long size, long alignment) {
            int chosen = -1;
            long chosenWaste = Long.MAX_VALUE;
            for (int i = 0; i < blocks.size(); i++) {
                long[] b = blocks.get(i);
                if (b[2] == 0 || b[1] < size) {
                    continue;
                }
                long aligned = (b[0] + alignment - 1) & -alignment;
                long padding = aligned - b[0];
                if (padding > b[1] - size) {
                    continue;
                }
                long waste = b[1] - padding - size;
                if (strategy == Strategy.FIRST_FIT) {
                    chosen = i;
                    break;
                }
                if (waste < chosenWaste) {
                    chosen = i;
                    chosenWaste = waste;
                    if (waste == 0) {
                        break;
                    }
                }
            }
            if (chosen < 0) {
                return FreeListAllocator.NONE;
            }
            long[] b = blocks.get(chosen);
            long aligned = (b[0] + alignment - 1) & -alignment;
            long padding = aligned - b[0];
            long tail = b[1] - padding - size;
            int at = chosen;
            if (padding > 0) {
                b[1] = padding;
                blocks.add(at + 1, new long[] {aligned, size + tail, 1});
                at++;
            }
            long[] used = blocks.get(at);
            used[2] = 0;
            if (tail > 0) {
                used[1] = size;
                blocks.add(at + 1, new long[] {aligned + size, tail, 1});
            }
            return aligned;
        }

        void free(long offset) {
            int i = 0;
            while (blocks.get(i)[0] != offset) {
                i++;
            }
            blocks.get(i)[2] = 1;
            if (i + 1 < blocks.size() && blocks.get(i + 1)[2] == 1) {
                blocks.get(i)[1] += blocks.get(i + 1)[1];
                blocks.remove(i + 1);
            }
            if (i > 0 && blocks.get(i - 1)[2] == 1) {
                blocks.get(i - 1)[1] += blocks.get(i)[1];
                blocks.remove(i);
            }
        }

        int count() {
            return blocks.size();
        }
    }

    @Test
    void sameOffsetsAsTheModelForEveryStrategy() {
        for (Strategy strategy : Strategy.values()) {
            SplittableRandom r = new SplittableRandom(Rnd.SEED + 31 * strategy.ordinal());
            long capacity = 4_000_000;
            FreeListAllocator a = new FreeListAllocator(capacity, strategy);
            Model m = new Model(capacity, strategy);
            List<Long> live = new ArrayList<>();
            int maxBlocks = 0;
            for (int step = 0; step < 40_000; step++) {
                // grow towards a few thousand blocks first, then churn
                boolean allocate = live.isEmpty() || r.nextInt(100) < (step < 6000 ? 85 : 50);
                if (allocate) {
                    long size = 1 + r.nextInt(r.nextInt(8) == 0 ? 4000 : 300);
                    long align = 1L << r.nextInt(8);
                    long expected = m.allocate(size, align);
                    assertEquals(expected, a.allocate(size, align), strategy + " step " + step + ": request " + size + " aligned " + align);
                    if (expected != FreeListAllocator.NONE) {
                        live.add(expected);
                    }
                } else {
                    long offset = live.remove(r.nextInt(live.size()));
                    m.free(offset);
                    a.free(offset);
                }
                assertEquals(m.count(), a.blockCount(), strategy + " step " + step);
                maxBlocks = Math.max(maxBlocks, m.count());
                if (step % 211 == 0) {
                    assertNull(a.validate(), a.validate());
                }
            }
            assertTrue(maxBlocks > 700, "the test must get past many 64-bit words of the free-block bitmap: " + maxBlocks);
            assertNull(a.validate());
        }
    }
}
