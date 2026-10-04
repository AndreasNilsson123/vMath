package vmath.samples;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.samples.framework.DemoEntry;
import vmath.samples.framework.DemoInfo;

/**
 * Tests that the registry of the demos agrees with the documentation and the run configurations:
 * every demo has a card in {@code docs/DEMOS.md} and a {@code Demo - <id>} run configuration, and
 * the ids are unique.
 *
 * <p>The tests read the repository through the system property {@code vmath.repoRoot}, which the
 * build sets; without it (a test started outside Gradle) they look two directories up from the
 * working directory.
 *
 * <p><b>Thread safety.</b> The tests only read files and may run in parallel.
 */
class DemosRegistryTest {

    private static Path root() {
        String property = System.getProperty("vmath.repoRoot");
        return property != null ? Path.of(property) : Path.of("").toAbsolutePath().getParent();
    }

    @Test
    void theIdsAreUniqueAndTheRegistryIsNotEmpty() {
        List<DemoEntry> all = Demos.all();
        assertFalse(all.isEmpty());
        Set<String> ids = new HashSet<>();
        for (DemoEntry e : all) {
            assertTrue(ids.add(e.info().id()), "duplicate demo id " + e.info().id());
        }
    }

    @Test
    void everyDemoIsDescribed() {
        for (DemoEntry e : Demos.all()) {
            DemoInfo info = e.info();
            assertFalse(info.title().isBlank(), info.id());
            assertTrue(info.claim().endsWith("."), info.id() + ": the claim is a sentence");
            assertFalse(info.tags().isEmpty(), info.id());
            assertTrue(info.allocationBudget() > 0, info.id());
            assertFalse(info.controls().isBlank(), info.id());
            assertNotNull(e.factory());
        }
    }

    @Test
    void everyDemoHasACardInTheDemosDocument() throws IOException {
        String doc = Files.readString(root().resolve("docs/DEMOS.md"));
        for (DemoEntry e : Demos.all()) {
            String heading = "\n### " + e.info().id() + ": ";
            assertTrue(doc.contains(heading), "docs/DEMOS.md has no card (a heading '### " + e.info().id() + ": title') for the demo " + e.info().id());
        }
    }

    @Test
    void everyDemoHasARunConfiguration() {
        for (DemoEntry e : Demos.all()) {
            Path file = root().resolve(".run").resolve(runConfigurationName(e.info().id()));
            assertTrue(Files.isRegularFile(file), "missing run configuration " + file);
        }
        assertTrue(Files.isRegularFile(root().resolve(".run").resolve("Demos_menu.run.xml")));
        assertTrue(Files.isRegularFile(root().resolve(".run").resolve("Demos_smoke.run.xml")));
    }

    @Test
    void theFactoriesAcceptNoArgumentsOrTheSmokeArguments() {
        for (DemoEntry e : Demos.all()) {
            assertDoesNotThrow(() -> e.factory().apply(List.of()), e.info().id());
            assertDoesNotThrow(() -> e.factory().apply(e.info().smokeArgs()), e.info().id());
        }
    }

    /**
     * Gives the file name of the run configuration of a demo: the configuration is called
     * {@code Demo - <id>} and IntelliJ names the file after it with every run of other characters
     * replaced by an underscore.
     *
     * @param id the demo id
     * @return the file name
     */
    static String runConfigurationName(String id) {
        return ("Demo - " + id).replaceAll("[^A-Za-z0-9]+", "_") + ".run.xml";
    }
}
