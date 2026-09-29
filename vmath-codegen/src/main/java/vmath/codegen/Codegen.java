package vmath.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Build-time source generator. See {@code docs/CODEGEN.md}.
 *
 * <pre>
 *   --templates DIR       float templates for main   (repeatable via --test-templates for tests)
 *   --test-templates DIR  float templates for tests
 *   --out DIR             output for main templates (and --sources when given)
 *   --test-out DIR        output for test templates
 *   --sources DIR         hand-written main sources; copied to --out with @ValueType applied (Valhalla profile)
 *   --renames FILE        extra exact renames for the double output, as a properties file (JOML types)
 *   --valhalla            emit {@code value record} for @ValueType
 *   --gpu SRC=OUT         generate {@code <Name>Gpu} classes for {@code @GpuStruct} records found in SRC into OUT (repeatable)
 * </pre>
 */
public final class Codegen {

    private Codegen() {
    }

    public static void main(String[] args) throws IOException {
        Path templates = null;
        Path testTemplates = null;
        Path out = null;
        Path testOut = null;
        Path sources = null;
        Path renamesFile = null;
        boolean valhalla = false;
        List<Path[]> gpu = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--templates" -> templates = Path.of(args[++i]);
                case "--test-templates" -> testTemplates = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--test-out" -> testOut = Path.of(args[++i]);
                case "--sources" -> sources = Path.of(args[++i]);
                case "--renames" -> renamesFile = Path.of(args[++i]);
                case "--valhalla" -> valhalla = true;
                case "--gpu" -> {
                    String[] pair = args[++i].split("=", 2);
                    if (pair.length != 2) {
                        throw new IllegalArgumentException("--gpu expects SOURCE_DIR=OUTPUT_DIR, got " + args[i]);
                    }
                    gpu.add(new Path[] {Path.of(pair[0]), Path.of(pair[1])});
                }
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        try {
            run(templates, testTemplates, out, testOut, sources, renamesFile, valhalla, gpu);
        } catch (Transformer.TemplateException e) {
            System.err.println("codegen: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(Path templates, Path testTemplates, Path out, Path testOut, Path sources, Path renamesFile,
            boolean valhalla, List<Path[]> gpu) throws IOException {
        Map<String, String> exact = new HashMap<>();
        Map<String, String> families = new HashMap<>();
        if (renamesFile != null && Files.exists(renamesFile)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(renamesFile)) {
                props.load(in);
            }
            props.forEach((k, v) -> exact.put(k.toString().trim(), v.toString().trim()));
        }

        List<Path> mainFiles = javaFiles(templates);
        List<Path> testFiles = javaFiles(testTemplates);
        for (Path f : concat(mainFiles, testFiles)) {
            for (Transformer.Family fam : Transformer.families(read(f), f.getFileName().toString())) {
                families.put(fam.name(), fam.twin());
            }
        }
        Renames renames = new Renames(exact, families);

        // Every generator records what it wrote per output directory, so stale files can be removed once, at the end.
        Map<Path, Set<Path>> produced = new LinkedHashMap<>();
        if (out != null && templates != null) {
            generate(templates, mainFiles, out, valhalla, renames, sources, produced.computeIfAbsent(key(out), k -> new HashSet<>()));
        }
        if (testOut != null && testTemplates != null) {
            generate(testTemplates, testFiles, testOut, valhalla, renames, null,
                    produced.computeIfAbsent(key(testOut), k -> new HashSet<>()));
        }
        if (!gpu.isEmpty()) {
            List<Path> gpuFiles = new ArrayList<>();
            for (Path[] pair : gpu) {
                gpuFiles.addAll(javaFiles(pair[0]));
            }
            GpuStructGenerator generator = new GpuStructGenerator();
            for (Path f : gpuFiles) {
                generator.register(read(f), f.getFileName().toString());
            }
            for (Path[] pair : gpu) {
                Set<Path> set = produced.computeIfAbsent(key(pair[1]), k -> new HashSet<>());
                for (Path f : javaFiles(pair[0])) {
                    for (GpuStructGenerator.Output o : generator.generate(read(f), f.getFileName().toString())) {
                        Path rel = pair[0].relativize(f).resolveSibling(o.className() + ".java");
                        set.add(write(pair[1].resolve(rel), o.source()));
                    }
                }
            }
        }
        for (Map.Entry<Path, Set<Path>> e : produced.entrySet()) {
            deleteStale(e.getKey(), e.getValue());
        }
    }

    private static Path key(Path dir) {
        return dir.toAbsolutePath().normalize();
    }

    private static void generate(Path root, List<Path> files, Path out, boolean valhalla, Renames renames,
            Path passthrough, Set<Path> produced) throws IOException {
        for (Path f : files) {
            Path rel = root.relativize(f);
            String fileName = f.getFileName().toString();
            String className = fileName.substring(0, fileName.length() - ".java".length());
            String source = read(f);
            List<Transformer.Family> families = Transformer.families(source, fileName);
            if (families.isEmpty()) {
                throw new Transformer.TemplateException(f + ": template has no @GenerateDouble type");
            }
            String twin = renames.identifier(className);
            String header = "// GENERATED from template " + rel.toString().replace('\\', '/')
                    + " by vmath-codegen. Do not edit; edit the template.";
            String floatOut = Transformer.transform(source, fileName,
                    new Transformer.Options(Transformer.Mode.FLOAT, valhalla, renames, header));
            String doubleOut = Transformer.transform(source, fileName,
                    new Transformer.Options(Transformer.Mode.DOUBLE, valhalla, renames, header));
            produced.add(write(out.resolve(rel), floatOut));
            produced.add(write(out.resolve(rel.resolveSibling(twin + ".java")), doubleOut));
        }
        if (passthrough != null) {
            for (Path f : javaFiles(passthrough)) {
                String source = read(f);
                String result = Transformer.transform(source, f.getFileName().toString(),
                        new Transformer.Options(Transformer.Mode.PLAIN, valhalla, renames, null));
                produced.add(write(out.resolve(passthrough.relativize(f)), result));
            }
        }
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Writes only when the content changed, so timestamps stay stable and incremental builds work. */
    private static Path write(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        if (!Files.exists(target) || !Files.readString(target, StandardCharsets.UTF_8).equals(content)) {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }
        return target.toAbsolutePath().normalize();
    }

    private static void deleteStale(Path out, Set<Path> produced) throws IOException {
        if (!Files.isDirectory(out)) {
            return;
        }
        try (Stream<Path> s = Files.walk(out)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (!produced.contains(p.toAbsolutePath().normalize())) {
                    Files.delete(p);
                }
            }
        }
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static List<Path> concat(List<Path> a, List<Path> b) {
        List<Path> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
