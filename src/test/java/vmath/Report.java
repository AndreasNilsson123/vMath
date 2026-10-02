package vmath;

/**
 * Where tests print the figures they measure (error bounds, hit rates, counts). The numbers are what the documents quote, but printing them on every run clutters
 * the output, so they appear only when the build is run with {@code -Dvmath.verbose=true} (and Gradle shows test output, for example with {@code -i}).
 */
public final class Report {

    private static final boolean VERBOSE = Boolean.getBoolean("vmath.verbose");

    private Report() {
    }

    public static void printf(String format, Object... args) {
        if (VERBOSE) {
            System.out.printf(format, args);
        }
    }

    public static void println(String line) {
        if (VERBOSE) {
            System.out.println(line);
        }
    }

    public static void print(String text) {
        if (VERBOSE) {
            System.out.print(text);
        }
    }
}
