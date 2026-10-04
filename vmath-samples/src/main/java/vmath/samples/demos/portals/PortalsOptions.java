package vmath.samples.demos.portals;

import java.util.List;

/**
 * The options of the {@link PortalsDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param rooms the number of rooms along one side of the building
 * @param props the number of pieces of furniture in every room
 * @param overlay whether the sector and portal overlay starts on
 */
record PortalsOptions(int rooms, int props, boolean overlay) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the interior-portals demo:
              --rooms N            rooms along one side of the building (default 20: 400 rooms)
              --props N            pieces of furniture per room (default 150)
              --overlay            start with the visible sectors and portals drawn
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static PortalsOptions parse(List<String> args) {
        int rooms = 20, props = 150;
        boolean overlay = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--rooms" -> rooms = intValue(args, ++i);
                case "--props" -> props = intValue(args, ++i);
                case "--overlay" -> overlay = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (rooms < 2 || rooms > 200 || props < 0) {
            throw new IllegalArgumentException("--rooms must be from 2 to 200 and --props at least 0\n" + USAGE);
        }
        return new PortalsOptions(rooms, props, overlay);
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
