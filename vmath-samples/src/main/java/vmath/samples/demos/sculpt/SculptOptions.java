package vmath.samples.demos.sculpt;

import java.util.List;

/**
 * The options of the {@link SculptDemo}, parsed from the arguments that the launcher passes on.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param grid the number of cells along each axis of the field; the mesh has the same resolution
 * @param normals whether the mesher computes normals from the gradient of the field (otherwise
 *     the renderer shades flat from the triangles' derivatives)
 * @param projection the number of Newton steps that move each vertex onto the surface
 * @param verify whether every 10 frames the mesh is checked to be closed and consistently wound,
 *     which fails the run if it is not
 * @param manifold whether the mesher gives every sheet of the surface in a cell its own vertex
 *     ({@code SurfaceNets.manifold}), which removes the edges that more than two triangles share
 */
record SculptOptions(int grid, boolean normals, int projection, boolean verify, boolean manifold) {

    /**
     * The usage text of the demo's own options.
     */
    static final String USAGE = """
            options of the sdf-sculpt demo:
              --grid N             cells along each axis of the field and the mesh (default 64, from 16 to 192)
              --no-normals         do not compute normals in the mesher (flat shading)
              --projection N       Newton steps that move each vertex onto the surface (default 0)
              --verify             every 10 frames, fail if the mesh has an edge without a partner
              --manifold           split the vertex of a cell that holds several sheets of the surface (no edge shared by more than two triangles)
            """;

    /**
     * Parses the arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @return the options
     * @throws IllegalArgumentException if an argument is unknown or a value is missing, not a
     *     number or out of range; the message includes the usage text
     */
    static SculptOptions parse(List<String> args) {
        int grid = 64, projection = 0;
        boolean normals = true, verify = false, manifold = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--grid" -> grid = intValue(args, ++i);
                case "--projection" -> projection = intValue(args, ++i);
                case "--no-normals" -> normals = false;
                case "--verify" -> verify = true;
                case "--manifold" -> manifold = true;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + "\n" + USAGE);
            }
        }
        if (grid < 16 || grid > 192 || projection < 0 || projection > 8) {
            throw new IllegalArgumentException("--grid must be from 16 to 192 and --projection from 0 to 8\n" + USAGE);
        }
        return new SculptOptions(grid, normals, projection, verify, manifold);
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
