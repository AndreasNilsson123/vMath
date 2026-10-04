package vmath.samples.demos.physics;

import java.util.List;

/**
 * The options of the {@link PileDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param bodies the number of boxes and spheres that are dropped in all
 * @param perFrame the number of bodies added per frame until all are in
 * @param contacts whether the contact points and normals start drawn
 * @param verify whether the demo checks every 30 frames that no body has left the pit or become a
 *     non-number, and fails the run if one has
 */
record PileOptions(int bodies, int perFrame, boolean contacts, boolean verify) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the rigid-pile demo:
              --bodies N           boxes and spheres to drop in all (default 1000)
              --per-frame N        bodies added per frame until all are in (default 4)
              --contacts           start with the contact points and normals drawn
              --verify             every 30 frames, fail if a body left the pit or is not a number
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or not positive; the message includes the usage text
     */
    static PileOptions parse(List<String> args) {
        int bodies = 1000, perFrame = 4;
        boolean contacts = false, verify = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--bodies" -> bodies = intValue(args, ++i);
                case "--per-frame" -> perFrame = intValue(args, ++i);
                case "--contacts" -> contacts = true;
                case "--verify" -> verify = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (bodies < 1 || perFrame < 1) {
            throw new IllegalArgumentException("the counts must be positive\n" + USAGE);
        }
        return new PileOptions(bodies, perFrame, contacts, verify);
    }

    private static int intValue(List<String> args, int i) {
        if (i >= args.size()) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number\n" + USAGE);
        }
        try {
            return Integer.parseInt(args.get(i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(args.get(i - 1) + " needs a number, not " + args.get(i) + "\n" + USAGE);
        }
    }
}
