package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vmath.gl.GraphicsCapabilities.Feature;
import vmath.gpucull.CullBackend;
import vmath.lines.LineStrategy;
import vmath.map.AreaStrategy;
import vmath.map.SymbolStrategy;

/**
 * Keeps the decision tables of the guides equal to the code: each table between
 * {@code <!-- decision-table:NAME -->} and {@code <!-- /decision-table -->} is regenerated here
 * and compared. Run with {@code -Dvmath.writeDocs=true} (and {@code --no-configuration-cache})
 * to rewrite the pages after a change of a strategy.
 */
class DecisionTablesDocTest {

    private static String capabilityMatrix() {
        Map<String, GraphicsCapabilities> profiles = new LinkedHashMap<>();
        profiles.put("OpenGL 3.3", GraphicsCapabilities.openGl(3, 3, List.of()));
        profiles.put("OpenGL 4.2", GraphicsCapabilities.openGl(4, 2, List.of()));
        profiles.put("OpenGL 4.3", GraphicsCapabilities.openGl(4, 3, List.of()));
        profiles.put("OpenGL 4.5", GraphicsCapabilities.openGl(4, 5, List.of()));
        profiles.put("OpenGL 4.6", GraphicsCapabilities.openGl(4, 6, List.of()));
        profiles.put("Vulkan, no optional features", GraphicsCapabilities.vulkan(false, false, false));
        profiles.put("Vulkan, all three", GraphicsCapabilities.vulkan(true, true, true));
        StringBuilder sb = new StringBuilder("| Feature |");
        StringBuilder rule = new StringBuilder("|---|");
        for (String name : profiles.keySet()) {
            sb.append(' ').append(name).append(" |");
            rule.append("---|");
        }
        sb.append('\n').append(rule).append('\n');
        sb.append("| GLSL |");
        for (GraphicsCapabilities c : profiles.values()) {
            sb.append(' ').append(c.glsl().number()).append(" |");
        }
        sb.append('\n');
        for (Feature f : Feature.values()) {
            sb.append("| `").append(f.name()).append("` |");
            for (GraphicsCapabilities c : profiles.values()) {
                sb.append(c.has(f) ? " yes |" : " no |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String lineStrategyByProfile() {
        Map<String, GraphicsCapabilities> profiles = new LinkedHashMap<>();
        profiles.put("OpenGL 3.3", GraphicsCapabilities.openGl(3, 3, List.of()));
        profiles.put("OpenGL 4.2", GraphicsCapabilities.openGl(4, 2, List.of()));
        profiles.put("OpenGL 4.3", GraphicsCapabilities.openGl(4, 3, List.of()));
        profiles.put("OpenGL 4.5, with ARB_shader_draw_parameters", GraphicsCapabilities.openGl(4, 5, List.of("GL_ARB_shader_draw_parameters")));
        profiles.put("OpenGL 4.6", GraphicsCapabilities.openGl(4, 6, List.of()));
        profiles.put("Vulkan, no optional features", GraphicsCapabilities.vulkan(false, false, false));
        profiles.put("Vulkan, multiDrawIndirect and drawIndirectFirstInstance", GraphicsCapabilities.vulkan(true, true, false));
        profiles.put("Vulkan, all three", GraphicsCapabilities.vulkan(true, true, true));
        StringBuilder sb = new StringBuilder("| Context | Best strategy |\n|---|---|\n");
        for (Map.Entry<String, GraphicsCapabilities> e : profiles.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | `").append(LineStrategy.choose(e.getValue())).append("` |\n");
        }
        return sb.toString();
    }

    private static Map<String, Map<String, String>> documents() {
        Map<String, String> gpu = new LinkedHashMap<>();
        gpu.put("capabilities", capabilityMatrix());
        gpu.put("draw-plain", DrawSubmission.chooser(false, false).markdownTable());
        gpu.put("draw-instanced", DrawSubmission.chooser(true, false).markdownTable());
        gpu.put("draw-base-instance", DrawSubmission.chooser(true, true).markdownTable());
        gpu.put("access-random", StructArrayAccess.chooser(StructArrayAccess.Need.RANDOM_ACCESS).markdownTable());
        gpu.put("access-instance", StructArrayAccess.chooser(StructArrayAccess.Need.PER_INSTANCE).markdownTable());
        gpu.put("cull-backend", CullBackend.chooser().markdownTable());
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("line-strategy", LineStrategy.chooser().markdownTable());
        lines.put("line-strategy-by-context", lineStrategyByProfile());
        Map<String, Map<String, String>> docs = new LinkedHashMap<>();
        docs.put("docs/GPU.md", gpu);
        docs.put("docs/LINES.md", lines);
        Map<String, String> maps = new LinkedHashMap<>();
        maps.put("symbol-strategy", SymbolStrategy.chooser().markdownTable());
        maps.put("area-strategy", AreaStrategy.chooser().markdownTable());
        docs.put("docs/MAPS.md", maps);
        return docs;
    }

    private static Path find(String relative) {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve(relative))) {
            p = p.getParent();
        }
        assertTrue(p != null, relative + " was not found above " + Path.of("").toAbsolutePath());
        return p.resolve(relative);
    }

    @Test
    void theTablesOfTheGuidesAreTheTablesOfTheCode() throws IOException {
        for (Map.Entry<String, Map<String, String>> d : documents().entrySet()) {
            Path doc = find(d.getKey());
            String raw = Files.readString(doc, StandardCharsets.UTF_8);
            boolean crlf = raw.contains("\r\n");   // a checkout on Windows may have converted the line endings
            String text = raw.replace("\r\n", "\n");
            String rewritten = text;
            for (Map.Entry<String, String> e : d.getValue().entrySet()) {
                String open = "<!-- decision-table:" + e.getKey() + " -->\n";
                String close = "<!-- /decision-table -->";
                int a = rewritten.indexOf(open);
                assertTrue(a >= 0, doc + " (" + text.length() + " characters) has no marker for the table '" + e.getKey() + "'");
                int from = a + open.length();
                int b = rewritten.indexOf(close, from);
                assertTrue(b >= 0, "the table '" + e.getKey() + "' is not closed");
                rewritten = rewritten.substring(0, from) + e.getValue() + rewritten.substring(b);
            }
            if (Boolean.getBoolean("vmath.writeDocs")) {
                Files.writeString(doc, crlf ? rewritten.replace("\n", "\r\n") : rewritten, StandardCharsets.UTF_8);
            } else {
                assertEquals(rewritten, text, d.getKey() + " is out of date: run with -Dvmath.writeDocs=true");
            }
        }
    }
}
