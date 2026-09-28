import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generates the double-precision types (and their tests) from the float sources.
 *
 * <p>The float files are the single source of truth. Run from the project root:
 * <pre>
 *   java tools/GenDouble.java           # regenerate *d.java and *dTest.java
 *   java tools/GenDouble.java --check   # fail if generated files are stale (for CI)
 * </pre>
 *
 * <p>Markers understood in float sources:
 * <ul>
 *   <li>{@code // @float-only-begin} ... {@code // @float-only-end}: dropped from the double output</li>
 *   <li>{@code // @eps-double <literal>}: on a field declaration, replaces its initializer</li>
 * </ul>
 * Double-only members are declared in {@link #DOUBLE_EXTRAS} below.
 */
public class GenDouble {

    private static final Path MAIN = Path.of("src/main/java/vmath/core");
    private static final Path TEST = Path.of("src/test/java/vmath/core");

    private static final Pattern MAIN_FILE = Pattern.compile("(Vec[234]|Quat|Mat[34])f\\.java");
    private static final Pattern TEST_FILE = Pattern.compile("(Vec[234]|Quat|Mat[34])fTest\\.java");

    /** Members that only exist on the double types, inserted before the closing brace. */
    private static final Map<String, String> DOUBLE_EXTRAS = Map.of(
            "Vec2d", """
                public Vec2f toFloat() {
                    return new Vec2f((float) x, (float) y);
                }
            """,
            "Vec3d", """
                public Vec3f toFloat() {
                    return new Vec3f((float) x, (float) y, (float) z);
                }

                /**
                 * {@code this - origin}, subtracted in double and then narrowed to float. This is the core of
                 * camera-relative rendering: pass the camera's world position as {@code origin} so vertex
                 * data stays small enough for float precision on the GPU.
                 */
                public Vec3f relativeTo(Vec3d origin) {
                    return new Vec3f((float) (x - origin.x), (float) (y - origin.y), (float) (z - origin.z));
                }
            """,
            "Vec4d", """
                public Vec4f toFloat() {
                    return new Vec4f((float) x, (float) y, (float) z, (float) w);
                }
            """,
            "Quatd", """
                public Quatf toFloat() {
                    return new Quatf((float) x, (float) y, (float) z, (float) w);
                }
            """,
            "Mat3d", """
                public Mat3f toFloat() {
                    return new Mat3f(
                            (float) m00, (float) m01, (float) m02,
                            (float) m10, (float) m11, (float) m12,
                            (float) m20, (float) m21, (float) m22);
                }
            """,
            "Mat4d", """
                public Mat4f toFloat() {
                    return new Mat4f(
                            (float) m00, (float) m01, (float) m02, (float) m03,
                            (float) m10, (float) m11, (float) m12, (float) m13,
                            (float) m20, (float) m21, (float) m22, (float) m23,
                            (float) m30, (float) m31, (float) m32, (float) m33);
                }
            """);

    public static void main(String[] args) throws IOException {
        boolean check = args.length > 0 && args[0].equals("--check");
        List<String> stale = new ArrayList<>();
        stale.addAll(process(MAIN, MAIN_FILE, check));
        if (Files.isDirectory(TEST)) {
            stale.addAll(process(TEST, TEST_FILE, check));
        }
        if (check && !stale.isEmpty()) {
            System.err.println("Stale generated files (run: java tools/GenDouble.java):");
            stale.forEach(s -> System.err.println("  " + s));
            System.exit(1);
        }
        System.out.println(check ? "Generated files are up to date." : "Done.");
    }

    private static List<String> process(Path dir, Pattern filePattern, boolean check) throws IOException {
        List<String> stale = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> s = Files.list(dir)) {
            sources = s.filter(p -> filePattern.matcher(p.getFileName().toString()).matches()).sorted().toList();
        }
        for (Path src : sources) {
            String srcName = src.getFileName().toString();
            String dstName = renameTypes(srcName);
            Path dst = dir.resolve(dstName);
            String out = convert(Files.readString(src), srcName, dstName.replace(".java", ""));
            String existing = Files.exists(dst) ? Files.readString(dst) : null;
            if (!out.equals(existing)) {
                if (check) {
                    stale.add(dst.toString());
                } else {
                    Files.writeString(dst, out);
                    System.out.println("wrote " + dst);
                }
            }
        }
        return stale;
    }

    static String convert(String text, String srcName, String dstClass) {
        StringBuilder sb = new StringBuilder();
        boolean skipping = false;
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.equals("// @float-only-begin")) {
                skipping = true;
                continue;
            }
            if (t.equals("// @float-only-end")) {
                skipping = false;
                continue;
            }
            if (skipping) {
                continue;
            }
            sb.append(convertLine(line)).append('\n');
        }
        String out = sb.toString();
        out = out.substring(0, out.length() - 1); // undo the extra trailing newline

        String extra = DOUBLE_EXTRAS.get(dstClass);
        if (extra != null) {
            int close = out.lastIndexOf('}');
            out = out.substring(0, close) + "\n" + extra.stripTrailing() + "\n" + out.substring(close);
            out = out.replace("\n\n\n", "\n\n");
        }

        int pkgEnd = out.indexOf('\n', out.indexOf("package ")) + 1;
        return out.substring(0, pkgEnd)
                + "\n// GENERATED from " + srcName + " by tools/GenDouble.java. Do not edit; edit the float source.\n"
                + out.substring(pkgEnd);
    }

    private static final Pattern EPS_DOUBLE = Pattern.compile("^(.*=\\s*)[^;]+;(\\s*)//\\s*@eps-double\\s+(\\S+)\\s*$");
    private static final Pattern FLOAT_INT_LITERAL = Pattern.compile("(?<![\\w.])(\\d+)[fF]\\b");
    private static final Pattern FLOAT_DEC_LITERAL = Pattern.compile("(?<![\\w.])(\\d+\\.\\d*(?:[eE][-+]?\\d+)?|\\d+[eE][-+]?\\d+)[fF]\\b");

    static String convertLine(String line) {
        Matcher eps = EPS_DOUBLE.matcher(line);
        if (eps.matches()) {
            line = eps.group(1) + eps.group(3) + ";";
        }
        line = line.replaceAll("\\(float\\)\\s*", "");
        line = renameTypes(line);
        line = line.replaceAll("\\bfloat\\b", "double");
        line = line.replaceAll("\\bFloat", "Double");
        line = FLOAT_DEC_LITERAL.matcher(line).replaceAll("$1");
        line = FLOAT_INT_LITERAL.matcher(line).replaceAll("$1.0");
        return line;
    }

    static String renameTypes(String s) {
        s = s.replaceAll("(Vec[234]|Quat|Mat[34])f(?=\\b|Test|[A-Z])", "$1d");
        s = s.replaceAll("(Vector[234]|Quaternion|Matrix[34])f(c?)\\b", "$1d$2");
        return s;
    }
}
