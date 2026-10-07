package vmath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Holds the packages of {@code vmath} to a layering: the table below says which package may use which, the test measures the real dependencies from the compiled classes with {@code jdeps} and fails
 * on an edge the table does not allow, on a cycle, and on a package that is missing from the table. It is the finer guard inside the module split of the roadmap (INF-6): each
 * module is a set of rows of this table whose edges point down: {@code MODULE} says which, and the build enforces it (a module cannot see the ones above it).
 *
 * <p>To add a dependency, add it to the table and say why in the commit: a new edge between layers is a design decision, not an import. The table is the measured state of 2026-10-03.
 */
class PackageLayeringTest {

    /** package, then the packages it may depend on (besides itself, the JDK and the annotations). */
    private static final Map<String, Set<String>> ALLOWED = new TreeMap<>(Map.ofEntries(
            // layer 0: nothing below
            Map.entry("core", Set.of()),
            Map.entry("mem", Set.of()),
            // layer 1: only the core
            Map.entry("geo", Set.of("core")),
            Map.entry("color", Set.of("core")),
            Map.entry("tex", Set.of("core")),
            // layer 2
            Map.entry("bulk", Set.of("core", "geo")),
            Map.entry("pack", Set.of("core", "geo")),
            Map.entry("physics", Set.of("core", "geo")),
            // layer 3
            Map.entry("anim", Set.of("core", "bulk")),
            Map.entry("gl", Set.of("core", "bulk")),
            Map.entry("lines", Set.of("core", "geo", "bulk", "gl", "mem", "spatial")),
            Map.entry("spatial", Set.of("core", "geo", "bulk")),
            // layer 4; util uses anim only for DebugLines.skeleton
            Map.entry("occlusion", Set.of("core", "geo", "bulk", "spatial")),
            Map.entry("util", Set.of("core", "geo", "anim")),
            Map.entry("camera", Set.of("core", "geo")),
            Map.entry("lighting", Set.of("core", "geo", "bulk", "gl", "spatial", "camera")),
            Map.entry("sky", Set.of()),
            Map.entry("mesh", Set.of("geo", "gl", "pack", "spatial")),
            // layer 5: the widest fan-out
            Map.entry("gltf", Set.of("core", "bulk", "anim", "mesh")),
            Map.entry("gpucull", Set.of("core", "geo", "bulk", "gl", "mesh", "occlusion"))));

    /** The module (by layer, bottom to top: core 0, geo 1, scene 2, render 3) each package ships in. */
    private static final Map<String, Integer> MODULE = Map.ofEntries(
            Map.entry("core", 0), Map.entry("mem", 0), Map.entry("color", 0), Map.entry("tex", 0),
            Map.entry("geo", 1), Map.entry("pack", 1), Map.entry("physics", 1),
            Map.entry("bulk", 2), Map.entry("anim", 2), Map.entry("gl", 2), Map.entry("lines", 2), Map.entry("spatial", 2), Map.entry("occlusion", 2), Map.entry("util", 2),
            Map.entry("camera", 3), Map.entry("lighting", 3), Map.entry("sky", 3), Map.entry("mesh", 3), Map.entry("gltf", 3), Map.entry("gpucull", 3));

    /** The measured edges between the packages of the library: package to the set of packages it uses. */
    private static Map<String, Set<String>> measure() throws Exception {
        String property = System.getProperty("vmath.jars");
        Environment.require(property != null, "not launched through Gradle: the jars of the modules are not known");
        List<String> command = new ArrayList<>(List.of("-verbose:package", "-filter:none"));
        for (String entry : property.split(java.io.File.pathSeparator)) {
            String[] pair = entry.split("=", 2);
            if (!pair[0].equals("vmath-all")) { // the aggregate has no classes
                Environment.require(Files.exists(Path.of(pair[1])), "missing " + pair[1]);
                command.add(pair[1]);
            }
        }
        ToolProvider jdeps = ToolProvider.findFirst("jdeps").orElse(null);
        Environment.require(jdeps != null, "no jdeps in this runtime");
        StringWriter out = new StringWriter(), err = new StringWriter();
        int status = jdeps.run(new PrintWriter(out), new PrintWriter(err), command.toArray(String[]::new));
        Environment.require(status == 0, "jdeps could not read the classes (preview class files of the Valhalla build?): " + err);
        Map<String, Set<String>> edges = new TreeMap<>();
        for (String line : out.toString().split("\\R")) {
            String[] part = line.trim().split("\\s+");
            if (part.length >= 3 && part[1].equals("->") && part[0].startsWith("vmath.") && part[2].startsWith("vmath.")) {
                String from = part[0].substring("vmath.".length()), to = part[2].substring("vmath.".length());
                // the module descriptors show up as dependencies between the module names (vmath.scene -> vmath.geo): only packages count
                if (!from.equals(to) && MODULE.containsKey(from) && MODULE.containsKey(to)) {
                    edges.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
                }
            }
        }
        return edges;
    }

    @Test
    void everyDependencyBetweenPackagesIsAllowed() throws Exception {
        Map<String, Set<String>> edges = measure();
        List<String> forbidden = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
            Set<String> allowed = ALLOWED.get(e.getKey());
            assertTrue(allowed != null, "vmath." + e.getKey() + " is not in the layering table of PackageLayeringTest: add it with its allowed dependencies");
            for (String to : e.getValue()) {
                if (!allowed.contains(to)) {
                    forbidden.add("vmath." + e.getKey() + " -> vmath." + to);
                }
            }
        }
        assertTrue(forbidden.isEmpty(), "dependencies the layering does not allow (a new edge between packages is a design decision: add it to the table, with the reason):\n" + String.join("\n", forbidden));
    }

    @Test
    void theTableIsNotMoreGenerousThanTheCode() throws Exception {
        // an allowed edge nothing uses is a hole in the guard: it would let a dependency in unnoticed
        Map<String, Set<String>> edges = measure();
        List<String> unused = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : ALLOWED.entrySet()) {
            for (String to : e.getValue()) {
                if (!edges.getOrDefault(e.getKey(), Set.of()).contains(to)) {
                    unused.add("vmath." + e.getKey() + " -> vmath." + to);
                }
            }
        }
        assertTrue(unused.isEmpty(), "edges in the table that the code does not use (remove them to keep the guard tight):\n" + String.join("\n", unused));
    }

    @Test
    void thereAreNoCyclesAndTheTableIsLayered() throws Exception {
        Map<String, Integer> depth = new TreeMap<>();
        for (String p : ALLOWED.keySet()) {
            depth(p, new ArrayList<>(), depth);
        }
        // the layers of the table: each package is above everything it uses
        for (Map.Entry<String, Set<String>> e : ALLOWED.entrySet()) {
            for (String to : e.getValue()) {
                assertTrue(depth.get(e.getKey()) > depth.get(to), e.getKey() + " must be above " + to);
            }
        }
        assertEquals(0, (int) depth.get("core"));
        assertTrue(depth.get("gpucull") >= 5, "gpucull is the top of the stack: " + depth);
        // every package is in one module, the table and the module map agree, and no package uses a package of a module above it: that is what makes the modules acyclic
        assertEquals(ALLOWED.keySet(), MODULE.keySet(), "the layering table and the module map list the same packages");
        for (Map.Entry<String, Set<String>> e : ALLOWED.entrySet()) {
            for (String to : e.getValue()) {
                assertTrue(MODULE.get(e.getKey()) >= MODULE.get(to), "vmath." + e.getKey() + " (module layer " + MODULE.get(e.getKey()) + ") uses vmath." + to + " (module layer " + MODULE.get(to) + "): the dependency points up");
            }
        }
    }

    private static int depth(String p, List<String> stack, Map<String, Integer> memo) {
        if (stack.contains(p)) {
            throw new AssertionError("dependency cycle: " + String.join(" -> ", stack) + " -> " + p);
        }
        Integer known = memo.get(p);
        if (known != null) {
            return known;
        }
        stack.add(p);
        int d = 0;
        for (String to : ALLOWED.get(p)) {
            d = Math.max(d, depth(to, stack, memo) + 1);
        }
        stack.remove(stack.size() - 1);
        memo.put(p, d);
        return d;
    }
}
