package vmath.geo;

import java.util.Arrays;

/**
 * Meshes the zero set of an {@link Sdf} with <b>Naive Surface Nets</b>: the field is sampled on a
 * regular grid, every cell that the surface passes through gets one vertex (the average of the
 * points where the surface crosses the cell's edges, found by linear interpolation of the samples),
 * and every grid edge that the surface crosses becomes a quad joining the four cells around it.
 *
 * <p>The triangles are counter-clockwise seen from outside (the side where the field is
 * positive), so the mesh can go to {@code MassProperties.ofMesh} or to a renderer as it is.
 *
 * <p><b>What holds.</b> Where the surface is thicker than a cell the mesh is closed (every edge
 * has a partner) and consistently wound, and every edge is shared by exactly two triangles. Where
 * the surface is <em>thinner</em> than a cell, so that one cell holds two sheets of it (a thin
 * wall, a crease where a body meets itself, a lens between two bodies that nearly touch), the
 * method makes one vertex for both sheets and a few edges can be shared by more than two
 * triangles; the mesh stays closed (no edge loses its partner), but it is not a manifold there. The
 * mesh is open at the border of the box (see below). {@link #overSharedEdgeCount()} counts the
 * edges that break the rule, and {@link #manifold(boolean)} turns on a pass that gives each sheet
 * of such a cell its own vertex, which removes the cases it can tell apart (see there for what it
 * does not do).
 *
 * <p><b>What you get.</b> About one vertex per surface cell, quads of the same size, no sliver
 * triangles worth the name (a quad is split along its shorter diagonal), and no sharp features:
 * edges and corners of a box are rounded off by about one cell, because the vertex of a cell is a
 * smoothed average, not the solution of a quadric error problem as in dual contouring.
 * {@link #projection(int)} moves each vertex onto the surface by Newton steps, which removes the
 * error of the linear interpolation but not the rounding of sharp features. Normals
 * ({@link #normals(boolean)}) are the gradient of the field.
 *
 * <p><b>The grid.</b> The box {@code [min, max]} is divided into {@code nx * ny * nz} cells; the
 * field is sampled at their {@code (nx + 1)(ny + 1)(nz + 1)} corners. A surface that leaves the box
 * is cut there and the mesh is <b>open</b> at the border: choose a box that contains the solid with
 * at least one cell to spare to get a closed mesh. A sample exactly at the iso value counts as
 * outside.
 *
 * <p><b>Allocation.</b> One instance owns its working storage and its output arrays and reuses
 * them: after the first call for a grid size, meshing the same size again allocates nothing unless
 * the output outgrows the arrays (they double). The arrays returned by {@link #positions()} and the
 * others are live: they hold the last result until the next call, and are longer than the count
 * says.
 *
 * <p><b>Thread safety.</b> Not thread-safe: use one instance per thread. The {@link Sdf} is called
 * from the calling thread only.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Sdf shape = Sdfs.torus(0f, 0f, 0f, 1f, 0.35f);
 * SurfaceNets nets = new SurfaceNets().projection(1).normals(true);     // reuse the mesher: it keeps its buffers
 * nets.mesh(shape, -1.6f, -0.6f, -1.6f, 1.6f, 0.6f, 1.6f, 64, 24, 64);
 * float[] positions = nets.positions();                                  // the first 3 * vertexCount() floats
 * int[] indices = nets.indices();                                        // the first 3 * triangleCount() ints
 * int triangles = nets.triangleCount();
 * }</pre>
 */
public final class SurfaceNets {

    private float iso;
    private int projection;
    private boolean computeNormals;
    private boolean manifold;
    private short[] cellCode = new short[0];

    private float[] field = new float[0];
    private int[] cellVertex = new int[0];
    private float[] positions = new float[0], normals = new float[0];
    private int[] indices = new int[0];
    private int vertexCount, triangleCount;
    private final float[] scratch = new float[3];
    private Sdf current;
    /**
     * The field being meshed with the iso level subtracted, as one object so that projection and
     * normals allocate nothing per vertex.
     */
    private final Sdf shifted = (x, y, z) -> current.distance(x, y, z) - iso;

    // the 12 edges of a cell as pairs of corner numbers; the corner number is x + 2 y + 4 z
    private static final int[] EDGE_A = {0, 2, 4, 6, 0, 1, 4, 5, 0, 1, 2, 3};
    private static final int[] EDGE_B = {1, 3, 5, 7, 2, 3, 6, 7, 4, 5, 6, 7};

    // the corners of the six faces of a cell, in cyclic order; the same physical face of the neighbouring cell lists the same corners in the same order (x0 and x1, y0 and y1, z0 and z1)
    private static final int[][] FACES = {{0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 5, 7, 6}};

    /**
     * For every corner mask and every decision on the ambiguous faces, the number of separate sheets
     * in the cell and the sheet (0..) that each edge belongs to (-1 if the surface does not cross
     * it). Loaded the first time {@link #manifold(boolean)} is used.
     *
     * <p>Two crossed edges belong to the same sheet when a face joins them. A face with two crossed
     * edges joins them. A face with four (opposite corners inside) is decided by its four corner
     * values alone, so both cells that share it read it the same way: if the saddle of the bilinear
     * field on the face is inside, the inside corners are connected and the outside ones are cut off
     * one by one, else the other way round. Every crossed edge then has exactly one partner on each
     * of its two faces, so a sheet is a closed loop and its vertex has a single cycle of
     * neighbours.
     */
    private static final class Sheets {
        static final byte[] COUNT = new byte[256 << 6];
        static final byte[] OF_EDGE = new byte[(256 << 6) * 12];

        static {
            for (int code = 0; code < (256 << 6); code++) {
                int mask = code & 255, joinInside = code >> 8;
                int[] parent = new int[12];
                for (int e = 0; e < 12; e++) {
                    parent[e] = e;
                }
                for (int f = 0; f < 6; f++) {
                    int[] face = FACES[f];
                    int[] crossed = new int[4];
                    int n = 0;
                    for (int c = 0; c < 4; c++) {
                        int a = face[c], b = face[(c + 1) % 4];
                        if (((mask >> a) & 1) != ((mask >> b) & 1)) {
                            crossed[n++] = edgeOf(a, b);
                        }
                    }
                    if (n == 2) {
                        union(parent, crossed[0], crossed[1]);
                    } else if (n == 4) {
                        // the corners that are cut off one by one: the outside ones if the inside is joined, else the inside ones
                        int cutOff = ((joinInside >> f) & 1) != 0 ? 0 : 1;
                        for (int c = 0; c < 4; c++) {
                            if (((mask >> face[c]) & 1) == cutOff) {
                                union(parent, edgeOf(face[(c + 3) % 4], face[c]), edgeOf(face[c], face[(c + 1) % 4]));
                            }
                        }
                    }
                }
                int[] rootSheet = new int[12];
                Arrays.fill(rootSheet, -1);
                int sheets = 0;
                for (int e = 0; e < 12; e++) {
                    if (((mask >> EDGE_A[e]) & 1) == ((mask >> EDGE_B[e]) & 1)) {
                        OF_EDGE[code * 12 + e] = -1;
                        continue;
                    }
                    int root = find(parent, e);
                    if (rootSheet[root] < 0) {
                        rootSheet[root] = sheets++;
                    }
                    OF_EDGE[code * 12 + e] = (byte) rootSheet[root];
                }
                COUNT[code] = (byte) sheets;
            }
        }
    }

    /**
     * The mask of the corners that are inside, completed with one bit per ambiguous face (opposite
     * corners inside) that says whether the saddle of the bilinear field on that face is inside,
     * which makes the inside corners connected on it. Bits of faces that are not ambiguous are 0.
     */
    private static int sheetCode(int mask, float c0, float c1, float c2, float c3, float c4, float c5, float c6, float c7) {
        int joined = 0;
        for (int f = 0; f < 6; f++) {
            int[] face = FACES[f];
            int b0 = (mask >> face[0]) & 1, b1 = (mask >> face[1]) & 1, b2 = (mask >> face[2]) & 1, b3 = (mask >> face[3]) & 1;
            if (b0 == b2 && b1 == b3 && b0 != b1) {
                float v0 = corner(face[0], c0, c1, c2, c3, c4, c5, c6, c7), v1 = corner(face[1], c0, c1, c2, c3, c4, c5, c6, c7);
                float v2 = corner(face[2], c0, c1, c2, c3, c4, c5, c6, c7), v3 = corner(face[3], c0, c1, c2, c3, c4, c5, c6, c7);
                // the value at the saddle of the bilinear interpolation: the sums of opposite corners have opposite signs, so the denominator is not zero
                float saddle = (v0 * v2 - v1 * v3) / (v0 + v2 - v1 - v3);
                if (saddle < 0f) {
                    joined |= 1 << f;
                }
            }
        }
        return mask | joined << 8;
    }

    private static int edgeOf(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        for (int e = 0; e < 12; e++) {
            if (EDGE_A[e] == lo && EDGE_B[e] == hi) {
                return e;
            }
        }
        throw new IllegalStateException("not an edge of a cell: " + a + ", " + b);
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /**
     * Creates a mesher with the iso value 0, no projection and no normals.
     */
    public SurfaceNets() {
    }

    /**
     * Sets the level of the field that is meshed.
     *
     * @param iso the iso
     * @return the level of the field that is meshed (default 0: the surface; other values give
     *     offset surfaces)
     * @throws IllegalArgumentException if {@code iso} is not finite
     */
    public SurfaceNets isoLevel(float iso) {
        if (Float.isNaN(iso) || Float.isInfinite(iso)) {
            throw new IllegalArgumentException("the iso level must be finite: " + iso);
        }
        this.iso = iso;
        return this;
    }

    /**
     * Sets how many Newton steps pull each vertex onto the surface after placement, which costs
     * field evaluations for a more accurate surface.
     *
     * <p>The vertex stays inside its cell.
     *
     * @param steps the steps
     * @return the number of Newton steps that move each vertex onto the surface after it is placed
     *     (default 0; 1 or 2 is usually enough for a smooth field)
     * @throws IllegalArgumentException if {@code steps} is negative
     */
    public SurfaceNets projection(int steps) {
        if (steps < 0) {
            throw new IllegalArgumentException("the number of projection steps must not be negative: " + steps);
        }
        this.projection = steps;
        return this;
    }

    /**
     * Turns on the pass that gives every separate sheet of the surface in a cell its own vertex
     * (default off).
     *
     * <p>Without it a cell has one vertex, and a cell that holds two sheets of the surface (the
     * surface is thinner than a cell there) can make edges that more than two triangles share. With
     * it the crossed edges of a cell are grouped into sheets by the faces of the cell (two crossed
     * edges of a face belong together; on a face whose opposite corners are inside, the value at the
     * saddle of the bilinear field on the face decides whether the inside corners are joined or cut
     * off one by one, the same way for both cells that share the face), and each sheet gets a vertex
     * at the average of its own crossings. Cells with up to four sheets exist. The cost is a table
     * lookup per cell (the table of 16 384 entries is built when this is first used) and two more
     * bytes per cell of working storage; a mesh where no cell has two sheets is the same as without
     * the pass.
     *
     * <p>What it does not do: two sheets that the faces of a cell connect, although they are
     * different parts of the surface, still share a vertex, and a sheet thinner than a cell may be
     * missed altogether, as with any sampling of a field. {@link #overSharedEdgeCount()} tells
     * whether a mesh is clean.
     *
     * @param enabled whether to split the vertices of cells that hold several sheets
     * @return this mesher, for chaining
     */
    public SurfaceNets manifold(boolean enabled) {
        this.manifold = enabled;
        return this;
    }

    /**
     * Returns whether to compute a normal per vertex from the gradient of the field (default
     * false).
     *
     * @param enabled whether enabled
     * @return this mesher, for chaining
     */
    public SurfaceNets normals(boolean enabled) {
        this.computeNormals = enabled;
        return this;
    }

    /**
     * Returns the meshes {@code sdf} on the grid of {@code nx * ny * nz} cells over the box
     * {@code [min, max]}; the result is read with {@link #vertexCount}, {@link #positions},
     * {@link #normals}, {@link #triangleCount} and {@link #indices}.
     *
     * @param sdf the sdf; must not be {@code null}
     * @param minX the smallest x coordinate
     * @param minY the smallest y coordinate
     * @param minZ the smallest z coordinate
     * @param maxX the largest x coordinate
     * @param maxY the largest y coordinate
     * @param maxZ the largest z coordinate
     * @param nx the number of cells along x
     * @param ny the number of cells along y
     * @param nz the number of cells along z
     * @return this mesher, which holds the result
     * @throws IllegalArgumentException if a cell count is below 1, the box is empty or not finite,
     *     or the grid has more than about 2 billion samples
     */
    public SurfaceNets mesh(Sdf sdf, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int nx, int ny, int nz) {
        if (nx < 1 || ny < 1 || nz < 1) {
            throw new IllegalArgumentException("the grid needs at least one cell along each axis: " + nx + " x " + ny + " x " + nz);
        }
        if (!(maxX > minX) || !(maxY > minY) || !(maxZ > minZ) || Float.isInfinite(maxX - minX) || Float.isInfinite(maxY - minY) || Float.isInfinite(maxZ - minZ)) {
            throw new IllegalArgumentException("the box must have a positive finite size along each axis");
        }
        long samples = (long) (nx + 1) * (ny + 1) * (nz + 1);
        if (samples >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("the grid has too many samples: " + samples);
        }
        int sx = nx + 1, sy = ny + 1, sxy = sx * sy;
        float cx = (maxX - minX) / nx, cy = (maxY - minY) / ny, cz = (maxZ - minZ) / nz;
        if (field.length < samples) {
            field = new float[(int) samples];
        }
        int cells = nx * ny * nz;
        if (cellVertex.length < cells) {
            cellVertex = new int[cells];
        }
        if (manifold && cellCode.length < cells) {
            cellCode = new short[cells];
        }
        grid.set(minX, minY, minZ, cx, cy, cz, nx, ny, nz);
        sampleField(sdf, grid);
        vertexCount = 0;
        triangleCount = 0;
        current = sdf;
        placeVertices(grid);
        connectCells(grid);
        current = null;
        return this;
    }

    /** The grid that is meshed: the corner of the box, the size of a cell and the number of cells along each axis (one instance, reused by every call). */
    private static final class Grid {
        float minX, minY, minZ, cx, cy, cz;
        int nx, ny, nz;

        void set(float minX, float minY, float minZ, float cx, float cy, float cz, int nx, int ny, int nz) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
        }
    }

    // the grid of the current call, kept so that meshing allocates nothing
    private final Grid grid = new Grid();

    // samples the field (shifted by the iso value) at every corner of the grid
    private void sampleField(Sdf sdf, Grid g) {
        float minX = g.minX, minY = g.minY, minZ = g.minZ, cx = g.cx, cy = g.cy, cz = g.cz;
        int nx = g.nx, ny = g.ny, nz = g.nz;
        for (int k = 0, p = 0; k <= nz; k++) {
            float z = minZ + cz * k;
            for (int j = 0; j <= ny; j++) {
                float y = minY + cy * j;
                for (int i = 0; i <= nx; i++, p++) {
                    field[p] = sdf.distance(minX + cx * i, y, z) - iso;
                }
            }
        }
    }

    // places one vertex in every cell that the surface passes through
    private void placeVertices(Grid g) {
        float minX = g.minX, minY = g.minY, minZ = g.minZ, cx = g.cx, cy = g.cy, cz = g.cz;
        int nx = g.nx, ny = g.ny, nz = g.nz, sx = nx + 1, sy = ny + 1, sxy = sx * sy;
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    int cell = i + nx * (j + ny * k);
                    int base = i + sx * j + sxy * k;
                    int mask = 0;
                    float c0 = field[base], c1 = field[base + 1], c2 = field[base + sx], c3 = field[base + sx + 1];
                    float c4 = field[base + sxy], c5 = field[base + sxy + 1], c6 = field[base + sxy + sx], c7 = field[base + sxy + sx + 1];
                    if (c0 < 0f) {
                        mask |= 1;
                    }
                    if (c1 < 0f) {
                        mask |= 2;
                    }
                    if (c2 < 0f) {
                        mask |= 4;
                    }
                    if (c3 < 0f) {
                        mask |= 8;
                    }
                    if (c4 < 0f) {
                        mask |= 16;
                    }
                    if (c5 < 0f) {
                        mask |= 32;
                    }
                    if (c6 < 0f) {
                        mask |= 64;
                    }
                    if (c7 < 0f) {
                        mask |= 128;
                    }
                    if (mask == 0 || mask == 255) {
                        cellVertex[cell] = -1;
                        continue;
                    }
                    int code = manifold ? sheetCode(mask, c0, c1, c2, c3, c4, c5, c6, c7) : mask;
                    if (manifold) {
                        cellCode[cell] = (short) code;
                    }
                    for (int sheet = 0, sheets = manifold ? Sheets.COUNT[code] : 1; sheet < sheets; sheet++) {
                    float sumX = 0, sumY = 0, sumZ = 0;
                    int crossings = 0;
                    for (int e = 0; e < 12; e++) {
                        int a = EDGE_A[e], b = EDGE_B[e];
                        if (((mask >> a) & 1) == ((mask >> b) & 1) || manifold && Sheets.OF_EDGE[code * 12 + e] != sheet) {
                            continue;
                        }
                        float da = corner(a, c0, c1, c2, c3, c4, c5, c6, c7), db = corner(b, c0, c1, c2, c3, c4, c5, c6, c7);
                        float t = da / (da - db);
                        sumX += (a & 1) + ((b & 1) - (a & 1)) * t;
                        sumY += ((a >> 1) & 1) + (((b >> 1) & 1) - ((a >> 1) & 1)) * t;
                        sumZ += ((a >> 2) & 1) + (((b >> 2) & 1) - ((a >> 2) & 1)) * t;
                        crossings++;
                    }
                    float inv = 1f / crossings;
                    float px = minX + cx * (i + sumX * inv), py = minY + cy * (j + sumY * inv), pz = minZ + cz * (k + sumZ * inv);
                    if (projection > 0) {
                        float[] q = scratch;
                        float h = 0.25f * Math.min(cx, Math.min(cy, cz));
                        Sdfs.project(shifted, px, py, pz, projection, 0f, h, q);
                        // stay within the cell: a vertex that left it would tangle the quads
                        px = Math.max(minX + cx * i, Math.min(minX + cx * (i + 1), q[0]));
                        py = Math.max(minY + cy * j, Math.min(minY + cy * (j + 1), q[1]));
                        pz = Math.max(minZ + cz * k, Math.min(minZ + cz * (k + 1), q[2]));
                    }
                    int vertex = addVertex(px, py, pz, Math.min(cx, Math.min(cy, cz)));
                    if (sheet == 0) {
                        cellVertex[cell] = vertex; // the other sheets of the cell follow it
                    }
                    }
                }
            }
        }
    }

    // connects the vertices of the four cells around every grid edge that the surface crosses with a quad
    private void connectCells(Grid g) {
        int nx = g.nx, ny = g.ny, nz = g.nz, sx = nx + 1, sy = ny + 1, sxy = sx * sy;
        for (int k = 0; k <= nz; k++) {
            for (int j = 0; j <= ny; j++) {
                for (int i = 0; i <= nx; i++) {
                    float d0 = field[i + sx * j + sxy * k];
                    boolean in0 = d0 < 0f;
                    if (i < nx && j > 0 && j < ny && k > 0 && k < nz && in0 != field[i + 1 + sx * j + sxy * k] < 0f) {
                        // x edge: the four cells around it, counter-clockwise seen from +x
                        quad(vertexOf(i + nx * (j - 1 + ny * (k - 1)), 3), vertexOf(i + nx * (j + ny * (k - 1)), 2), vertexOf(i + nx * (j + ny * k), 0), vertexOf(i + nx * (j - 1 + ny * k), 1), in0);
                    }
                    if (j < ny && i > 0 && i < nx && k > 0 && k < nz && in0 != field[i + sx * (j + 1) + sxy * k] < 0f) {
                        // y edge: counter-clockwise seen from +y
                        quad(vertexOf(i - 1 + nx * (j + ny * (k - 1)), 7), vertexOf(i - 1 + nx * (j + ny * k), 5), vertexOf(i + nx * (j + ny * k), 4), vertexOf(i + nx * (j + ny * (k - 1)), 6), in0);
                    }
                    if (k < nz && i > 0 && i < nx && j > 0 && j < ny && in0 != field[i + sx * j + sxy * (k + 1)] < 0f) {
                        // z edge: counter-clockwise seen from +z
                        quad(vertexOf(i - 1 + nx * (j - 1 + ny * k), 11), vertexOf(i + nx * (j - 1 + ny * k), 10), vertexOf(i + nx * (j + ny * k), 8), vertexOf(i - 1 + nx * (j + ny * k), 9), in0);
                    }
                }
            }
        }
    }

    // the vertex of a cell for one of its edges: the only one, or that of the sheet that the edge belongs to
    private int vertexOf(int cell, int edge) {
        return manifold ? cellVertex[cell] + Sheets.OF_EDGE[cellCode[cell] * 12 + edge] : cellVertex[cell];
    }

    private static float corner(int c, float c0, float c1, float c2, float c3, float c4, float c5, float c6, float c7) {
        switch (c) {
            case 0:
                return c0;
            case 1:
                return c1;
            case 2:
                return c2;
            case 3:
                return c3;
            case 4:
                return c4;
            case 5:
                return c5;
            case 6:
                return c6;
            default:
                return c7;
        }
    }

    private int addVertex(float x, float y, float z, float cell) {
        int v = vertexCount++;
        if (3 * vertexCount > positions.length) {
            int n = Math.max(3 * 64, 2 * positions.length);
            positions = Arrays.copyOf(positions, n);
        }
        positions[3 * v] = x;
        positions[3 * v + 1] = y;
        positions[3 * v + 2] = z;
        if (computeNormals) {
            if (normals.length < positions.length) {
                normals = Arrays.copyOf(normals, positions.length);
            }
            float[] n = scratch;
            Sdfs.normal(shifted, x, y, z, 0.25f * cell, n);
            normals[3 * v] = n[0];
            normals[3 * v + 1] = n[1];
            normals[3 * v + 2] = n[2];
        }
        return v;
    }

    /**
     * Adds the quad {@code a, b, c, d} (counter-clockwise for an edge whose first sample is inside)
     * as two triangles split along the shorter diagonal.
     */
    private void quad(int a, int b, int c, int d, boolean firstInside) {
        if (!firstInside) { // the surface faces the other way: reverse the order
            int t = b;
            b = d;
            d = t;
        }
        if (3 * (triangleCount + 2) > indices.length) {
            indices = Arrays.copyOf(indices, Math.max(3 * 128, 2 * indices.length));
        }
        float acx = positions[3 * a] - positions[3 * c], acy = positions[3 * a + 1] - positions[3 * c + 1], acz = positions[3 * a + 2] - positions[3 * c + 2];
        float bdx = positions[3 * b] - positions[3 * d], bdy = positions[3 * b + 1] - positions[3 * d + 1], bdz = positions[3 * b + 2] - positions[3 * d + 2];
        int o = 3 * triangleCount;
        if (acx * acx + acy * acy + acz * acz <= bdx * bdx + bdy * bdy + bdz * bdz) {
            indices[o] = a;
            indices[o + 1] = b;
            indices[o + 2] = c;
            indices[o + 3] = a;
            indices[o + 4] = c;
            indices[o + 5] = d;
        } else {
            indices[o] = a;
            indices[o + 1] = b;
            indices[o + 2] = d;
            indices[o + 3] = b;
            indices[o + 4] = c;
            indices[o + 5] = d;
        }
        triangleCount += 2;
    }

    /**
     * Counts the vertices of the last mesh, which is the valid length of the position and normal
     * arrays.
     *
     * @return the number of vertices of the last mesh
     */
    public int vertexCount() {
        return vertexCount;
    }

    /**
     * Counts the triangles of the last mesh, which is the valid length of the index array.
     *
     * @return the number of triangles of the last mesh
     */
    public int triangleCount() {
        return triangleCount;
    }

    /**
     * Counts the directed edges that more than one triangle of the last mesh runs along. In a mesh
     * with a consistent winding that is the number of edges that more than two triangles share, so
     * 0 means that no edge breaks the rule (see the class comment).
     *
     * <p>A diagnostic: it sorts one {@code long} per triangle edge, so it allocates and takes time
     * of the order of the mesh size times its logarithm.
     *
     * @return the number of distinct directed edges that are used by two or more triangles
     */
    public int overSharedEdgeCount() {
        long[] keys = new long[3 * triangleCount];
        for (int t = 0, n = 0; t < triangleCount; t++) {
            for (int e = 0; e < 3; e++) {
                keys[n++] = ((long) indices[3 * t + e] << 32) | (indices[3 * t + (e + 1) % 3] & 0xFFFFFFFFL);
            }
        }
        Arrays.sort(keys);
        int over = 0;
        for (int i = 1; i < keys.length; i++) {
            if (keys[i] == keys[i - 1] && (i == 1 || keys[i] != keys[i - 2])) {
                over++;
            }
        }
        return over;
    }

    /**
     * Exposes the vertex positions of the last mesh as the live internal array, which is longer
     * than the data and is overwritten by the next run.
     *
     * @return the positions {@code x, y, z} of the vertices: the first {@code 3 * vertexCount()}
     *     floats of a live array that is longer than that
     */
    public float[] positions() {
        return positions;
    }

    /**
     * Exposes the vertex normals of the last mesh as the live internal array; valid only when
     * normal generation was enabled, longer than the data, and overwritten by the next run.
     *
     * @return the unit normals {@code x, y, z} of the vertices, if {@link #normals(boolean)} was on
     *     for the last mesh (else the array is stale): the first {@code 3 * vertexCount()} floats
     *     of a live array
     */
    public float[] normals() {
        return normals;
    }

    /**
     * Exposes the triangle indices of the last mesh as the live internal array, which is longer
     * than the data and is overwritten by the next run.
     *
     * @return the triangle indices: the first {@code 3 * triangleCount()} ints of a live array that
     *     is longer than that
     */
    public int[] indices() {
        return indices;
    }
}
