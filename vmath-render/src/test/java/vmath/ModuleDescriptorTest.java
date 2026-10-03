package vmath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks the built jars as modules, the way a consumer on the module path sees them: the four parts of the library, which export the public packages between them, and the aggregate module
 * {@code vmath} that requires them all. Tests themselves run on the class path, so this is the only place JPMS is actually exercised.
 */
class ModuleDescriptorTest {

    /** Module name to the packages it exports (and, with the same list, contains), bottom to top. */
    private static final Map<String, Set<String>> PARTS = new TreeMap<>(Map.of(
            "vmath.core", Set.of("vmath.core", "vmath.mem", "vmath.color", "vmath.tex"),
            "vmath.geo", Set.of("vmath.geo", "vmath.pack", "vmath.physics"),
            "vmath.scene", Set.of("vmath.bulk", "vmath.spatial", "vmath.occlusion", "vmath.anim", "vmath.gl", "vmath.util"),
            "vmath.render", Set.of("vmath.camera", "vmath.mesh", "vmath.gltf", "vmath.gpucull")));

    /** The modules each part may require at run time besides java.base: the ones below it. */
    private static final Map<String, Set<String>> REQUIRES = Map.of(
            "vmath.core", Set.of(),
            "vmath.geo", Set.of("vmath.core"),
            "vmath.scene", Set.of("vmath.core", "vmath.geo"),
            "vmath.render", Set.of("vmath.core", "vmath.geo", "vmath.scene"));

    /** The jars of the aggregate and the parts, by Gradle project name; null when not launched through Gradle. */
    private static Map<String, Path> jars() {
        String p = System.getProperty("vmath.jars");
        if (p == null) {
            return null;
        }
        Map<String, Path> jars = new TreeMap<>();
        for (String entry : p.split(File.pathSeparator)) {
            String[] pair = entry.split("=", 2);
            Path jar = Path.of(pair[1]);
            if (!Files.exists(jar)) {
                return null;
            }
            jars.put(pair[0], jar);
        }
        return jars;
    }

    private static ModuleReference module(Map<String, Path> jars, String project, String module) {
        return ModuleFinder.of(jars.get(project)).find(module).orElseThrow(() -> new AssertionError("no module '" + module + "' in " + jars.get(project)));
    }

    private static String project(String module) {
        return module.replace('.', '-');
    }

    @Test
    void eachPartExportsExactlyItsPackagesAndNothingIsHidden() {
        Map<String, Path> jars = jars();
        if (jars == null) {
            return;
        }
        Set<String> all = new TreeSet<>();
        for (Map.Entry<String, Set<String>> part : PARTS.entrySet()) {
            ModuleDescriptor d = module(jars, project(part.getKey()), part.getKey()).descriptor();
            assertEquals(part.getKey(), d.name());
            assertFalse(d.isAutomatic(), "must be an explicit module, not an automatic one");
            Set<String> exported = d.exports().stream().map(ModuleDescriptor.Exports::source).collect(Collectors.toSet());
            assertEquals(part.getValue(), exported, part.getKey());
            assertEquals(part.getValue(), new TreeSet<>(d.packages()), part.getKey() + ": packages shipped but not exported (hidden API or a forgotten export) or the other way round");
            assertTrue(d.exports().stream().noneMatch(ModuleDescriptor.Exports::isQualified), "no qualified exports");
            assertTrue(d.opens().isEmpty(), "nothing is opened for reflection");
            for (String pkg : part.getValue()) {
                assertTrue(all.add(pkg), pkg + " is in two modules");
            }
        }
        assertEquals(17, all.size(), "the library has 17 packages: " + all);
    }

    @Test
    void theAggregateHasNoPackagesAndRequiresEveryPart() {
        Map<String, Path> jars = jars();
        if (jars == null) {
            return;
        }
        ModuleDescriptor d = module(jars, "vmath-all", "vmath").descriptor();
        assertEquals("vmath", d.name());
        assertTrue(d.packages().isEmpty(), "the aggregate has no packages of its own: " + d.packages());
        assertTrue(d.exports().isEmpty());
        Set<String> transitive = d.requires().stream().filter(r -> r.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)).map(ModuleDescriptor.Requires::name)
                .collect(Collectors.toSet());
        assertEquals(PARTS.keySet(), transitive, "requires transitive every part, so that `requires vmath` still gives the whole library");
    }

    @Test
    void annotationsAreACompileTimeOnlyDependencyAndTheLayersPointDown() {
        Map<String, Path> jars = jars();
        if (jars == null) {
            return;
        }
        for (String name : PARTS.keySet()) {
            ModuleDescriptor d = module(jars, project(name), name).descriptor();
            ModuleDescriptor.Requires annotations = d.requires().stream().filter(r -> r.name().equals("vmath.annotations")).findFirst()
                    .orElseThrow(() -> new AssertionError(name + " should declare its build-time dependency"));
            assertTrue(annotations.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC), name + ": vmath.annotations must be 'requires static'");
            Set<String> runtime = d.requires().stream().filter(r -> !r.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)).map(ModuleDescriptor.Requires::name)
                    .collect(Collectors.toSet());
            Set<String> expected = new TreeSet<>(REQUIRES.get(name));
            expected.add("java.base");
            assertEquals(expected, runtime, name + ": at run time it needs java.base and the modules below it, nothing else");
            assertTrue(d.requires().stream().filter(r -> REQUIRES.containsKey(r.name())).allMatch(r -> r.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)),
                    name + ": the modules below are re-exported (requires transitive), because their types appear in its API");
        }
    }

    @Test
    void theWholeLibraryResolvesWithoutTheAnnotationsModule() {
        Map<String, Path> jars = jars();
        if (jars == null) {
            return;
        }
        List<Path> all = List.copyOf(jars.values());
        ModuleFinder finder = ModuleFinder.of(all.toArray(Path[]::new));
        Configuration cfg = Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()), ModuleFinder.of(), Set.of("vmath"));
        for (String name : PARTS.keySet()) {
            assertTrue(cfg.findModule(name).isPresent(), name + " is pulled in by the aggregate");
        }
        assertTrue(cfg.findModule("vmath.annotations").isEmpty(), "static requires must not be pulled in");
        // a consumer of only the bottom part does not get the rest
        Configuration core = Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()), ModuleFinder.of(), Set.of("vmath.core"));
        assertTrue(core.findModule("vmath.geo").isEmpty() && core.findModule("vmath.scene").isEmpty() && core.findModule("vmath.render").isEmpty(), "vmath.core stands alone");
        Configuration geo = Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()), ModuleFinder.of(), Set.of("vmath.geo"));
        assertTrue(geo.findModule("vmath.core").isPresent() && geo.findModule("vmath.scene").isEmpty(), "vmath.geo needs core only");
    }
}
