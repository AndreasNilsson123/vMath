package vmath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks the built jar as a module, the way a consumer on the module path sees it. Tests themselves run on the class
 * path, so this is the only place JPMS is actually exercised.
 */
class ModuleDescriptorTest {

    private static final Set<String> EXPECTED_EXPORTS =
            Set.of("vmath.core", "vmath.geo", "vmath.bulk", "vmath.spatial", "vmath.gl", "vmath.camera", "vmath.pack",
                    "vmath.occlusion", "vmath.mesh", "vmath.anim", "vmath.tex", "vmath.gltf");

    private static Path jar() {
        String p = System.getProperty("vmath.jar");
        if (p == null || !Files.exists(Path.of(p))) {
            return null; // not launched through Gradle
        }
        return Path.of(p);
    }

    private static ModuleReference module(Path jar) {
        return ModuleFinder.of(jar).find("vmath").orElseThrow(() -> new AssertionError("no module 'vmath' in " + jar));
    }

    @Test
    void descriptorExportsExactlyThePublicPackages() {
        Path jar = jar();
        if (jar == null) {
            return;
        }
        ModuleDescriptor d = module(jar).descriptor();
        assertEquals("vmath", d.name());
        assertFalse(d.isAutomatic(), "must be an explicit module, not an automatic one");
        Set<String> exported = d.exports().stream().map(ModuleDescriptor.Exports::source).collect(Collectors.toSet());
        assertEquals(EXPECTED_EXPORTS, exported);
        assertTrue(d.exports().stream().noneMatch(ModuleDescriptor.Exports::isQualified), "no qualified exports");
        assertTrue(d.opens().isEmpty(), "nothing is opened for reflection");
    }

    @Test
    void everyPackageInTheJarIsExported() {
        Path jar = jar();
        if (jar == null) {
            return;
        }
        Set<String> packages = new TreeSet<>(module(jar).descriptor().packages());
        packages.removeAll(EXPECTED_EXPORTS);
        assertTrue(packages.isEmpty(), "packages shipped but not exported (hidden API or a forgotten export): " + packages);
    }

    @Test
    void annotationsAreACompileTimeOnlyDependency() {
        Path jar = jar();
        if (jar == null) {
            return;
        }
        ModuleDescriptor d = module(jar).descriptor();
        ModuleDescriptor.Requires annotations = d.requires().stream()
                .filter(r -> r.name().equals("vmath.annotations")).findFirst()
                .orElseThrow(() -> new AssertionError("the module should declare its build-time dependency"));
        assertTrue(annotations.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC),
                "vmath.annotations must be 'requires static'");
        Set<String> runtimeRequires = d.requires().stream().filter(r -> !r.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC))
                .map(ModuleDescriptor.Requires::name).collect(Collectors.toSet());
        assertEquals(Set.of("java.base"), runtimeRequires, "at run time the library needs nothing but java.base");
    }

    @Test
    void resolvesAloneWithoutTheAnnotationsModule() {
        Path jar = jar();
        if (jar == null) {
            return;
        }
        ModuleFinder finder = ModuleFinder.of(jar);
        Configuration cfg = Configuration.resolve(finder, java.util.List.of(ModuleLayer.boot().configuration()), ModuleFinder.of(),
                Set.of("vmath"));
        assertTrue(cfg.findModule("vmath").isPresent());
        assertTrue(cfg.findModule("vmath.annotations").isEmpty(), "static requires must not be pulled in");
    }

    private static void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }
}
