package vmath.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The generator run for one module of a multi-module build: families and struct sources of the modules below are read, not generated. */
class CodegenModulesTest {

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static final String VEC = """
            package p.low;

            import vmath.annotations.GenerateDouble;

            @GenerateDouble
            public record Vecf(float x) {
            }
            """;

    private static final String USER = """
            package p.high;

            import p.low.Vecf;
            import vmath.annotations.GenerateDouble;

            @GenerateDouble
            public record Boxf(Vecf min) {
                public Vecf lower() {
                    return min;
                }
            }
            """;

    @Test
    void aTemplateUsesTheTwinOfATypeFromAModuleBelow(@TempDir Path dir) throws IOException {
        Path low = dir.resolve("low/template"), high = dir.resolve("high/template"), out = dir.resolve("high/out");
        write(low.resolve("p/low/Vecf.java"), VEC);
        write(high.resolve("p/high/Boxf.java"), USER);
        Codegen.run(high, null, out, null, null, null, false, List.of(), List.of(low), List.of());
        String twin = Files.readString(out.resolve("p/high/Boxd.java"));
        assertTrue(twin.contains("record Boxd(Vecd min)") && twin.contains("public Vecd lower()") && twin.contains("import p.low.Vecd;"), twin);
        assertFalse(Files.exists(out.resolve("p/low")), "the types of the module below are not generated again");
        // without the families of the module below the twin keeps the float name: that is the bug the option exists for
        Path plain = dir.resolve("high/plain");
        Codegen.run(high, null, plain, null, null, null, false, List.of(), List.of(), List.of());
        assertTrue(Files.readString(plain.resolve("p/high/Boxd.java")).contains("Vecf"), "without --family-templates the float type of the other module stays");
    }

    @Test
    void structsOfAModuleBelowCanBeReferenced(@TempDir Path dir) throws IOException {
        Path low = dir.resolve("low/src"), high = dir.resolve("high/src"), out = dir.resolve("high/out");
        write(low.resolve("p/low/Inner.java"), """
                package p.low;

                import vmath.annotations.GpuStruct;

                @GpuStruct
                public record Inner(float a, float b) {
                }
                """);
        write(high.resolve("p/high/Outer.java"), """
                package p.high;

                import p.low.Inner;
                import vmath.annotations.GpuStruct;

                @GpuStruct
                public record Outer(Inner inner, float c) {
                }
                """);
        Codegen.run(null, null, null, null, null, null, false, List.<Path[]>of(new Path[] {high, out}), List.of(), List.of(low));
        assertTrue(Files.exists(out.resolve("p/high/OuterGpu.java")), "the struct that refers to the one below is generated");
        assertFalse(Files.exists(out.resolve("p/low/InnerGpu.java")), "the struct below is registered, not generated");
    }
}
