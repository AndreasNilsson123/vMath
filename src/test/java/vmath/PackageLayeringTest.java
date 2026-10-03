package vmath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * on an edge the table does not allow, on a cycle, and on a package that is missing from the table. It is the guard that makes the module split of the roadmap (INF-6) a mechanical step: each
 * future module is a set of rows of this table whose edges already point down, and nothing can drift while the split waits (docs/technical-debt.md TD-11).
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
            Map.entry("spatial", Set.of("core", "geo", "bulk")),
            // layer 4; util uses anim only for DebugLines.skeleton
            Map.entry("occlusion", Set.of("core", "geo", "bulk", "spatial")),
            Map.entry("util", Set.of("core", "geo", "anim")),
            Map.entry("camera", Set.of("core", "geo", "bulk", "gl", "spatial")),
            Map.entry("mesh", Set.of("geo", "gl", "pack", "spatial")),
            // layer 5: the widest fan-out
            Map.entry("gltf", Set.of("core", "bulk", "anim", "mesh")),
            Map.entry("gpucull", Set.of("core", "geo", "bulk", "gl", "mesh", "occlusion"))));

    /** The measured edges between the packages of the library: package to the set of packages it uses. */
    private static Map<String, Set<String>> measure() throws Exception {
        Path classes;
        try {
            classes = Path.of(vmath.core.Vec3f.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        assumeTrue(Files.isDirectory(classes), "the classes are not in a directory (a jar?): " + classes);
        ToolProvider jdeps = ToolProvider.findFirst("jdeps").orElse(null);
        assumeTrue(jdeps != null, "no jdeps in this runtime");
        StringWriter out = new StringWriter(), err = new StringWriter();
        int status = jdeps.run(new PrintWriter(out), new PrintWriter(err), "-verbose:package", "-filter:none", classes.toString());
        assumeTrue(status == 0, "jdeps could not read the classes (preview class files of the Valhalla build?): " + err);
        Map<String, Set<String>> edges = new TreeMap<>();
        for (String line : out.toString().split("\\R")) {
            String[] part = line.trim().split("\\s+");
            if (part.length >= 3 && part[1].equals("->") && part[0].startsWith("vmath.") && part[2].startsWith("vmath.")) {
                String from = part[0].substring("vmath.".length()), to = part[2].substring("vmath.".length());
                if (!from.equals(to)) {
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
        // every package that exports from the module is in the table, and the table has no package that does not exist
        Set<String> exported = new TreeSet<>();
        for (String name : vmath.core.Vec3f.class.getModule().getDescriptor() == null ? Set.<String>of() : vmath.core.Vec3f.class.getModule().getDescriptor().exports().stream().map(x -> x.source()).toList()) {
            exported.add(name.substring("vmath.".length()));
        }
        if (!exported.isEmpty()) {
            assertEquals(new TreeSet<>(ALLOWED.keySet()), exported, "the exported packages of the module and the rows of the layering table");
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
