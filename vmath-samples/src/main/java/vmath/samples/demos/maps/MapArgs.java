package vmath.samples.demos.maps;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code --name value} arguments of the line demos: a name that the demo does not know, a
 * missing value or a number out of range is an {@link IllegalArgumentException} with the usage
 * text, like the options of the other demos.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable after parsing.
 */
final class MapArgs {

    private final Map<String, String> values = new HashMap<>();
    private final String usage;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments
     * @param usage the usage text; its lines that start with {@code --name} define the names that are known
     * @throws IllegalArgumentException if an argument is unknown or has no value
     */
    MapArgs(List<String> args, String usage) {
        this.usage = usage;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!a.startsWith("--") || !usage.contains("  " + a + " ")) {
                throw new IllegalArgumentException("unknown argument " + a + "\n" + usage);
            }
            if (i + 1 >= args.size()) {
                throw new IllegalArgumentException("the argument " + a + " needs a value\n" + usage);
            }
            values.put(a, args.get(++i));
        }
    }

    /**
     * Reads an integer option.
     *
     * @param name the name with its dashes
     * @param fallback the value when it is not given
     * @param min the smallest accepted value
     * @param max the largest accepted value
     * @return the value
     * @throws IllegalArgumentException if it is not an integer in the range
     */
    int integer(String name, int fallback, int min, int max) {
        String s = values.get(name);
        if (s == null) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(s);
            if (v < min || v > max) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer from " + min + " to " + max + ", not " + s + "\n" + usage);
        }
    }
}
