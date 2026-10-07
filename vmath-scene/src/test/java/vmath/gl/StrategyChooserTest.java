package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.GraphicsCapabilities.Feature;

/**
 * {@link StrategyChooser}: the first possible option wins, a ceiling skips the better ones, forcing
 * checks instead of guessing, and every refusal says what is missing.
 */
class StrategyChooserTest {

    private enum Way { BEST, MIDDLE, PLAIN }

    private static StrategyChooser<Way> chooser() {
        return StrategyChooser.<Way>builder("testing")
                .optionGlsl(Way.BEST, GlslVersion.V450, "uses everything", Feature.COMPUTE_SHADERS, Feature.STORAGE_BUFFERS)
                .option(Way.MIDDLE, "uses a buffer", Feature.STORAGE_BUFFERS)
                .option(Way.PLAIN, "needs nothing")
                .build();
    }

    private static final GraphicsCapabilities GL46 = GraphicsCapabilities.openGl(4, 6, List.of());
    private static final GraphicsCapabilities GL43 = GraphicsCapabilities.openGl(4, 3, List.of());
    private static final GraphicsCapabilities GL33 = GraphicsCapabilities.baseline();

    @Test
    void theFirstPossibleOptionWins() {
        StrategyChooser<Way> c = chooser();
        assertEquals(Way.BEST, c.choose(GL46));
        assertEquals(Way.MIDDLE, c.choose(GL43), "4.3 has the features but not GLSL 4.50");
        assertEquals(Way.PLAIN, c.choose(GL33));
        assertEquals(Way.MIDDLE, c.choose(GL46.without(Feature.COMPUTE_SHADERS)));
        assertEquals(Way.BEST, c.choose(GraphicsCapabilities.vulkan(false, false, false)), "Vulkan has compute, storage buffers and GLSL 4.50");
    }

    @Test
    void aCeilingSkipsTheBetterOnes() {
        StrategyChooser<Way> c = chooser();
        assertEquals(Way.MIDDLE, c.chooseAtMost(GL46, Way.MIDDLE));
        assertEquals(Way.PLAIN, c.chooseAtMost(GL46, Way.PLAIN));
        assertEquals(Way.BEST, c.chooseAtMost(GL46, Way.BEST));
        assertEquals(Way.PLAIN, c.chooseAtMost(GL33, Way.MIDDLE));
    }

    @Test
    void forcingChecksTheNeeds() {
        StrategyChooser<Way> c = chooser();
        assertEquals(Way.PLAIN, c.force(Way.PLAIN, GL46));
        assertEquals(Way.BEST, c.force(Way.BEST, GL46));
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> c.force(Way.BEST, GL43));
        assertTrue(e.getMessage().contains("GLSL 4.50") && e.getMessage().contains("GLSL 4.30"), e.getMessage());
        e = assertThrows(UnsupportedOperationException.class, () -> c.force(Way.MIDDLE, GL33));
        assertTrue(e.getMessage().contains("STORAGE_BUFFERS") && e.getMessage().contains("MIDDLE"), e.getMessage());
    }

    @Test
    void whenNothingIsPossibleTheMessageListsWhatEachOptionLacks() {
        StrategyChooser<Way> c = StrategyChooser.<Way>builder("drawing")
                .option(Way.BEST, "x", Feature.COMPUTE_SHADERS)
                .option(Way.MIDDLE, "y", Feature.STORAGE_BUFFERS, Feature.PERSISTENT_MAPPING)
                .build();
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> c.choose(GL33));
        assertTrue(e.getMessage().contains("drawing") && e.getMessage().contains("BEST lacks COMPUTE_SHADERS") && e.getMessage().contains("MIDDLE lacks STORAGE_BUFFERS, PERSISTENT_MAPPING"),
                e.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> c.chooseAtMost(GL33, Way.MIDDLE));
    }

    @Test
    void availableAndSupportsFollowTheOrder() {
        StrategyChooser<Way> c = chooser();
        assertEquals(List.of(Way.BEST, Way.MIDDLE, Way.PLAIN), c.available(GL46));
        assertEquals(List.of(Way.MIDDLE, Way.PLAIN), c.available(GL43));
        assertEquals(List.of(Way.PLAIN), c.available(GL33));
        assertTrue(c.supports(Way.MIDDLE, GL43));
        assertFalse(c.supports(Way.BEST, GL43));
        assertEquals(List.of(Way.BEST, Way.MIDDLE, Way.PLAIN), c.strategies());
        assertEquals(EnumSet.of(Feature.COMPUTE_SHADERS, Feature.STORAGE_BUFFERS), c.needs(Way.BEST));
        assertTrue(c.needs(Way.PLAIN).isEmpty());
    }

    @Test
    void theTableListsTheOptionsInOrder() {
        String t = chooser().markdownTable();
        String[] rows = t.split("\n");
        assertEquals(5, rows.length);
        assertTrue(rows[2].startsWith("| 1 | `BEST` | `STORAGE_BUFFERS`, `COMPUTE_SHADERS`, GLSL 4.50 | uses everything |"), rows[2]);
        assertTrue(rows[4].startsWith("| 3 | `PLAIN` | nothing | needs nothing |"), rows[4]);
    }

    @Test
    void mistakesInTheBuilderAreRefused() {
        assertThrows(IllegalStateException.class, () -> StrategyChooser.<Way>builder("empty").build());
        assertThrows(IllegalArgumentException.class, () -> StrategyChooser.<Way>builder("twice").option(Way.BEST, "a").option(Way.BEST, "b"));
        StrategyChooser<Way> c = StrategyChooser.<Way>builder("one").option(Way.BEST, "a").build();
        assertThrows(IllegalArgumentException.class, () -> c.force(Way.PLAIN, GL33));
        assertThrows(IllegalArgumentException.class, () -> c.chooseAtMost(GL33, Way.PLAIN));
        assertThrows(IllegalArgumentException.class, () -> c.supports(Way.PLAIN, GL33));
        assertThrows(IllegalArgumentException.class, () -> c.needs(Way.PLAIN));
    }
}
