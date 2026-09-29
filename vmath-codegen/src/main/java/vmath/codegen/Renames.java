package vmath.codegen;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Name mapping for the double output. The {@code exact} map holds the template family ({@code Vec3f -> Vec3d}) plus
 * project entries such as the JOML oracle types. On top of that, any {@code Float*} identifier becomes
 * {@code Double*} ({@code FloatBuffer}), and a few JDK members that are not a plain substitution are tabled.
 */
public final class Renames {

    /** JDK members whose double counterpart isn't obtained by replacing "Float" with "Double". */
    private static final Map<String, String> JDK_MEMBERS = Map.of(
            "floatToIntBits", "doubleToLongBits",
            "floatToRawIntBits", "doubleToRawLongBits",
            "intBitsToFloat", "longBitsToDouble",
            "floatValue", "doubleValue",
            "JAVA_FLOAT", "JAVA_DOUBLE",
            "JAVA_FLOAT_UNALIGNED", "JAVA_DOUBLE_UNALIGNED",
            "getFloat", "getDouble",
            "putFloat", "putDouble",
            "asFloatBuffer", "asDoubleBuffer");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, String> exact;
    /** Matches a template-family name embedded in a longer identifier, e.g. {@code nextVec3f}. */
    private final Pattern embedded;
    private final Map<String, String> families;

    /**
     * @param exact    whole-identifier renames (JOML types and the like)
     * @param families template family names to their twins; also renamed inside longer identifiers when followed
     *                 by an upper-case letter, digit or the end ({@code nextVec3f}, {@code Vec3fTest})
     */
    public Renames(Map<String, String> exact, Map<String, String> families) {
        this.exact = new HashMap<>(exact);
        this.exact.putAll(families);
        this.families = new HashMap<>(families);
        String alternation = families.keySet().stream()
                .sorted((a, b) -> b.length() - a.length())
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        this.embedded = alternation.isEmpty() ? null : Pattern.compile("(" + alternation + ")(?![a-z_])");
    }

    private String embeddedFamilies(String name) {
        if (embedded == null) {
            return name;
        }
        Matcher m = embedded.matcher(name);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(families.get(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Type and variable names. */
    public String identifier(String name) {
        String hit = exact.get(name);
        if (hit != null) {
            return hit;
        }
        if (name.startsWith("Float")) {
            return "Double" + name.substring("Float".length());
        }
        String jdk = JDK_MEMBERS.get(name);
        return jdk != null ? jdk : embeddedFamilies(name);
    }

    /** Names after a dot: fields and methods. */
    public String member(String name) {
        return identifier(name);
    }

    /** Comments and string literals: renames family words and the word {@code float}. */
    public String text(String s) {
        Matcher m = WORD.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String w = m.group();
            String to = exact.get(w);
            if (to == null) {
                if (w.equals("float")) {
                    to = "double";
                } else if (w.equals("floats")) {
                    to = "doubles";
                } else if (w.startsWith("Float")) {
                    to = "Double" + w.substring("Float".length());
                } else {
                    to = embeddedFamilies(w);
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(to));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
