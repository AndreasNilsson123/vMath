package vmath.tex;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.Fuzz;
import vmath.assets.AssetFactory;
import vmath.core.Rnd;

/**
 * The KTX2 header parser takes untrusted input: damaged copies of the generated KTX2 files may only be refused with {@link Ktx2.FormatException}, and a header that is
 * accepted must describe levels that really lie inside the data and a layout whose sizes can be computed without overflow. Seeded; {@code -Dvmath.trials} scales the count.
 */
class Ktx2FuzzTest {

    @Test
    void damagedKtx2HeadersFailOnlyWithFormatException() {
        List<String> failures = new ArrayList<>();
        int cases = Math.max(2000, 5 * Rnd.N);
        int[] counts = new int[2]; // refused, accepted
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            for (Map.Entry<String, byte[]> e : AssetFactory.all().entrySet()) {
                if (!e.getKey().endsWith(".ktx2")) {
                    continue;
                }
                SplittableRandom r = new SplittableRandom(Rnd.SEED ^ e.getKey().hashCode());
                for (int i = 0; i < cases; i++) {
                    StringBuilder how = new StringBuilder("seed " + Rnd.SEED + ", " + e.getKey() + ", case " + i + ": ");
                    // most of the interesting fields are in the header and the level index, so damage that region more often
                    int hi = r.nextInt(3) == 0 ? e.getValue().length : Math.min(e.getValue().length, 80 + 24 * 16);
                    byte[] damaged = Fuzz.mutate(e.getValue(), r, how, 0, hi);
                    try {
                        Ktx2.Header h = Ktx2.parse(ByteBuffer.wrap(damaged));
                        counts[1]++;
                        TextureLayout layout = h.layout(); // null for a format that TextureFormat does not know: documented
                        if (layout != null) {
                            long total = layout.totalBytes();
                            assertTrue(total >= 0, "negative total size");
                            for (int level = 0; level < layout.levels(); level++) {
                                assertTrue(layout.levelBytes(level) >= 0 && layout.levelOffset(level) >= 0, "negative level size or offset at level " + level);
                            }
                        }
                        h.format();
                        h.isCube();
                        h.layers();
                    } catch (Ktx2.FormatException expected) {
                        counts[0]++;
                    } catch (Throwable t) {
                        failures.add(how + ": " + t);
                    }
                }
            }
        });
        vmath.Report.printf("ktx2 fuzz: %d refused, %d accepted%n", counts[0], counts[1]);
        assertTrue(counts[1] > 50, "damage that leaves a valid header must happen sometimes, or the test only tests the identifier check: " + counts[1]);
        if (!failures.isEmpty()) {
            fail(failures.size() + " fuzz cases broke the contract (only Ktx2.FormatException is allowed):\n" + String.join("\n", failures.subList(0, Math.min(failures.size(), 12))));
        }
    }
}
