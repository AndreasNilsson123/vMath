package vmath;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Documentation must not name types that do not exist: every back-ticked CamelCase identifier in the README and in the documents of docs/ has to be a name that
 * occurs in a source file of this repository (the double twins are accepted through their float templates) or in the list of external names below. The roadmap,
 * the debt register and the history are excluded because they describe work that does not exist yet.
 */
class DocReferencesTest {

    /** Names that are not ours: JOML, the JDK, graphics APIs, tools, and the example names used in prose. */
    private static final Set<String> EXTERNAL = Set.of(
            "FrustumIntersection", "Matrix4fStack", "Vector3i", "JaCoCo", "GitHub", "OpenGL", "LWJGL", "SPIR", "Vulkan", "GLSL", "Slang", "JMH", "PIT", "Qodana",
            "Dependabot", "Renovate", "Gradle", "Maven", "Central", "Javadoc", "JavaDoc", "ByteBuffer", "FloatBuffer", "IntBuffer", "DoubleBuffer", "ByteOrder",
            "MemorySegment", "ThreadMXBean", "ServiceLoader", "ForkJoinPool", "ExecutorService", "VkFormat", "VkDrawIndexedIndirectCommand", "VkDispatchIndirectCommand",
            "VkVertexInputAttributeDescription", "VkVertexInputBindingDescription", "DrawElementsIndirectCommand", "DrawArraysIndirectCommand", "DispatchIndirectCommand",
            "FreqInlineSize", "RGB10A2", "XxxGpu", "LightGpu", "SplittableRandom", "HashMap", "TreeMap", "StandardJavadocDocletOptions", "MutationCoverageReport",
            "FloatVector", "VectorSpecies", "JUnit", "JOML");

    private static final Pattern TICKED = Pattern.compile("`([A-Za-z_][A-Za-z0-9_.]*)(?:\\([^`]*\\))?`");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static Set<String> sourceWords(Path root) throws IOException {
        Set<String> words = new TreeSet<>();
        for (String dir : List.of("vmath-core/src", "vmath-geo/src", "vmath-scene/src", "vmath-render/src", "vmath-annotations/src", "vmath-codegen/src",
                "vmath-validator/src", "vmath-simd/src", "vmath-bench/src")) {
            Path d = root.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(d)) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    Matcher m = WORD.matcher(Files.readString(p, StandardCharsets.UTF_8));
                    while (m.find()) {
                        words.add(m.group());
                    }
                }
            }
        }
        return words;
    }

    private static boolean camelCase(String s) {
        if (s.length() < 4 || !Character.isUpperCase(s.charAt(0))) {
            return false;
        }
        boolean lower = false;
        int extra = 0;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            lower |= Character.isLowerCase(c);
            if (Character.isUpperCase(c) || Character.isDigit(c)) {
                extra++;
            }
        }
        return lower && extra > 0;
    }

    private static boolean exists(String name, Set<String> words) {
        if (words.contains(name) || EXTERNAL.contains(name)) {
            return true;
        }
        // the generated double twins and the generated GPU writers
        if (name.endsWith("d") && words.contains(name.substring(0, name.length() - 1) + "f")) {
            return true;
        }
        if (name.endsWith("dTest") && words.contains(name.substring(0, name.length() - 5) + "fTest")) {
            return true; // the generated double twin of a test template
        }
        return name.endsWith("Gpu") && words.contains(name.substring(0, name.length() - 3));
    }

    @Test
    void documentsOnlyNameTypesThatExist() throws IOException {
        Path root = Path.of("").toAbsolutePath().getParent(); // the tests run in the directory of their module
        Set<String> words = sourceWords(root);
        List<Path> docs = new ArrayList<>();
        docs.add(root.resolve("README.md"));
        docs.add(root.resolve("CHANGELOG.md"));
        try (Stream<Path> s = Files.list(root.resolve("docs"))) {
            s.filter(p -> p.toString().endsWith(".md")).forEach(docs::add);
        }
        List<String> problems = new ArrayList<>();
        Set<String> excluded = Set.of("ROADMAP.md", "technical-debt.md", "history.md");
        for (Path doc : docs) {
            if (excluded.contains(doc.getFileName().toString())) {
                continue;
            }
            Matcher m = TICKED.matcher(Files.readString(doc, StandardCharsets.UTF_8));
            while (m.find()) {
                String token = m.group(1);
                for (String part : token.split("\\.")) {
                    if (camelCase(part) && !exists(part, words)) {
                        problems.add(doc.getFileName() + ": `" + token + "` (" + part + ")");
                        break;
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "documents name types that no source declares or mentions:\n" + String.join("\n", new TreeSet<>(problems)));
    }
}
