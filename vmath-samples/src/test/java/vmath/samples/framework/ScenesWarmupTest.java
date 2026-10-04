package vmath.samples.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;

/**
 * Tests of the shared scene generators and of the warm-up helper.
 *
 * <p><b>Thread safety.</b> Each test builds its own values; the tests may run in parallel.
 */
class ScenesWarmupTest {

    @Test
    void theBlocksAreBuildingsThenPropsThenTheGround() {
        Scenes.Blocks b = Scenes.blocks(6, 10);
        assertEquals(36, b.buildings());
        assertEquals(360, b.props());
        assertEquals(36 + 360 + 1, b.bounds().size());
        BoundsArray bounds = b.bounds();
        for (int i = 0; i < b.buildings(); i++) {
            assertEquals(Scenes.BLOCK_FOOTPRINT, bounds.maxX(i) - bounds.minX(i), 1e-3f);
            assertTrue(bounds.maxY(i) >= 8f, "buildings are at least 8 high");
        }
        assertEquals(0f, bounds.maxY(bounds.size() - 1), "the last box is the flat ground");
    }

    @Test
    void thePropsStandInTheStreetsAndNotInsideABuilding() {
        Scenes.Blocks b = Scenes.blocks(8, 30);
        BoundsArray bounds = b.bounds();
        for (int p = b.buildings(); p < b.buildings() + b.props(); p++) {
            float cx = (bounds.minX(p) + bounds.maxX(p)) * 0.5f, cz = (bounds.minZ(p) + bounds.maxZ(p)) * 0.5f;
            boolean inside = false;
            for (int i = 0; i < b.buildings() && !inside; i++) {
                inside = cx > bounds.minX(i) && cx < bounds.maxX(i) && cz > bounds.minZ(i) && cz < bounds.maxZ(i);
            }
            assertTrue(!inside, "prop " + p + " is inside a building");
        }
    }

    @Test
    void theScenesAreTheSameEveryTime() {
        BoundsArray a = Scenes.blocks(5, 7).bounds(), b = Scenes.blocks(5, 7).bounds();
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i), b.get(i));
        }
    }

    @Test
    void warmupStopsAfterTwentyQuietRoundsOfWorkThatAllocatesNothing() {
        int[] counter = new int[1];
        int rounds = Warmup.untilQuiet(() -> counter[0]++, 5000);
        assertEquals(20, rounds);
        assertEquals(20, counter[0]);
    }

    @Test
    void warmupGivesUpAtTheTimeLimitWhenTheWorkKeepsAllocating() {
        long start = System.nanoTime();
        int rounds = Warmup.untilQuiet(() -> {
            byte[] garbage = new byte[100_000];
            garbage[0] = 1;
            if (garbage[0] != 1) {
                throw new AssertionError();
            }
        }, 50);
        assertTrue(rounds >= 1);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000, "the limit ends the loop");
    }
}
