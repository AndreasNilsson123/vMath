package vmath.cookbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps docs/COOKBOOK.md identical to what its template and CookbookTest say: every {@code {{recipe:id}}} in docs/COOKBOOK.template.md is replaced by the lines of
 * CookbookTest between {@code // recipe[id]: ...} and {@code // recipe end} (lines between {@code // recipe skip} and {@code // recipe resume} left out, the
 * indentation removed). Run with {@code -Dvmath.writeDocs=true} to rewrite docs/COOKBOOK.md after changing either source.
 */
class CookbookDocTest {

    private static final Pattern START = Pattern.compile("^\\s*// recipe\\[([a-z-]+)]:.*$");

    static Map<String, String> recipes(List<String> source) {
        Map<String, String> out = new LinkedHashMap<>();
        String id = null;
        List<String> lines = new ArrayList<>();
        boolean skipping = false;
        for (String line : source) {
            Matcher m = START.matcher(line);
            if (id == null) {
                if (m.matches()) {
                    id = m.group(1);
                    lines = new ArrayList<>();
                    lines.add(line.replaceFirst("// recipe\\[[a-z-]+]: *", "// "));
                }
                continue;
            }
            String t = line.trim();
            if (t.equals("// recipe end")) {
                out.put(id, dedent(lines));
                id = null;
            } else if (t.equals("// recipe skip")) {
                skipping = true;
            } else if (t.equals("// recipe resume")) {
                skipping = false;
            } else if (!skipping) {
                lines.add(line);
            }
        }
        assertTrue(id == null, "recipe " + id + " has no '// recipe end'");
        return out;
    }

    private static String dedent(List<String> lines) {
        int indent = Integer.MAX_VALUE;
        for (String l : lines) {
            if (!l.isBlank()) {
                int n = 0;
                while (n < l.length() && l.charAt(n) == ' ') {
                    n++;
                }
                indent = Math.min(indent, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l.isBlank() ? "" : l.substring(indent).stripTrailing()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Test
    void theCookbookIsGeneratedFromItsTemplateAndTheRunnableRecipes() throws IOException {
        Path root = Path.of("").toAbsolutePath().getParent(); // the tests run in the directory of their module
        Path testSource = root.resolve("vmath-render/src/test/java/vmath/cookbook/CookbookTest.java");
        Path template = root.resolve("docs/COOKBOOK.template.md");
        Path doc = root.resolve("docs/COOKBOOK.md");
        Map<String, String> recipes = recipes(Files.readAllLines(testSource, StandardCharsets.UTF_8));
        String text = Files.readString(template, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Matcher m = Pattern.compile("\\{\\{recipe:([a-z-]+)}}").matcher(text);
        StringBuilder out = new StringBuilder();
        int used = 0;
        while (m.find()) {
            String code = recipes.get(m.group(1));
            assertTrue(code != null, "the template asks for recipe '" + m.group(1) + "' which CookbookTest does not define");
            m.appendReplacement(out, Matcher.quoteReplacement("```java\n" + code + "\n```"));
            used++;
        }
        m.appendTail(out);
        assertEquals(recipes.size(), used, "every recipe of CookbookTest appears once in the template: " + recipes.keySet());
        String expected = out.toString();
        if (Boolean.getBoolean("vmath.writeDocs")) {
            Files.writeString(doc, expected, StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(doc), "docs/COOKBOOK.md is missing: run with -Dvmath.writeDocs=true");
        assertEquals(expected, Files.readString(doc, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "docs/COOKBOOK.md is out of date: run with -Dvmath.writeDocs=true");
    }
}
