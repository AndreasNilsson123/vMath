package vmath.samples.demos.globe;

import vmath.core.Vec3d;
import vmath.core.Wgs84;
import vmath.geo.TerrainRgb;
import vmath.geo.TileId;
import vmath.geo.TileSelector;
import vmath.geo.WebMercator;

/**
 * Builds the vertices of the mesh of one tile, and the index list that every tile shares.
 *
 * <p><b>The grid.</b> A tile is {@code cells + 1} by {@code cells + 1} nodes, row 0 on the north
 * edge. Every node is placed on the WGS-84 ellipsoid at its longitude and latitude with
 * {@link Wgs84#toEcef} at the height of the ground (the sea is clamped to height 0, so the water is
 * a smooth surface and the sea floor is only in the data). Positions are stored relative to the
 * point of the ellipsoid under the middle of the tile, the <em>reference point</em>, computed in
 * {@code double} and narrowed to {@code float} afterwards: near the camera they are small numbers
 * with a precision of micrometres, which is what camera-relative rendering relies on.
 *
 * <p><b>The skirt.</b> Along each of the four edges a second row of vertices hangs below the first
 * one, along the ellipsoid normal, by {@link #skirtDepth}. Where a tile meets a neighbour of a
 * coarser level (the selector allows one level of difference) the two surfaces differ slightly
 * between the nodes of the coarse one and the skirt closes the gap.
 *
 * <p><b>Vertex layout.</b> Eight floats per vertex: the position relative to the reference point
 * (ECEF axes), the unit normal in ECEF axes (from {@code TerrainRgb.normal} in the local
 * East-North-Up frame of the node, then rotated), and the texture coordinate into the tile's image
 * (adjusted so that the border nodes read the centres of the border pixels). The vertex order is the
 * grid row by row, then the north, south, west and east skirt rows.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time, each with its own arrays.
 */
final class TileMeshes {

    /**
     * The number of floats of one vertex.
     */
    static final int FLOATS = 8;

    private TileMeshes() {
    }

    /**
     * Gives the number of vertices of a tile mesh including the skirts.
     *
     * @param cells the number of quads along an edge
     * @return the vertex count
     */
    static int vertexCount(int cells) {
        int n = cells + 1;
        return n * n + 4 * n;
    }

    /**
     * Gives the number of indices of a tile mesh including the skirts.
     *
     * @param cells the number of quads along an edge
     * @return the index count
     */
    static int indexCount(int cells) {
        return 6 * cells * cells + 4 * 6 * cells;
    }

    /**
     * Gives how far the skirt of a tile hangs, in metres.
     *
     * <p>Half the spacing of the nodes, which covers the difference between a coarse tile and the
     * finer one that touches it where the ground is smooth, and at least 2 m.
     *
     * @param zoom the zoom level
     * @param y the row of the tile
     * @param cells the number of quads along an edge
     * @return the depth in metres
     */
    static double skirtDepth(int zoom, int y, int cells) {
        return Math.max(2.0, 0.5 * TileSelector.vertexSpacing(zoom, y, cells));
    }

    /**
     * Gives the indices that every tile shares: the two triangles of every cell, then the two
     * triangles of every skirt segment.
     *
     * @param cells the number of quads along an edge
     * @return {@link #indexCount} indices into the vertex order described in the class comment
     */
    static int[] indices(int cells) {
        int n = cells + 1;
        int[] ix = new int[indexCount(cells)];
        int k = 0;
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int v00 = j * n + i, v10 = v00 + 1, v01 = v00 + n, v11 = v01 + 1;
                ix[k++] = v00;
                ix[k++] = v01;
                ix[k++] = v11;
                ix[k++] = v00;
                ix[k++] = v11;
                ix[k++] = v10;
            }
        }
        int g = n * n;
        // skirt rows: north (j = 0), south (j = cells), west (i = 0), east (i = cells)
        for (int s = 0; s < 4; s++) {
            for (int e = 0; e < cells; e++) {
                int a;
                int b;
                switch (s) {
                    case 0 -> {
                        a = e;
                        b = e + 1;
                    }
                    case 1 -> {
                        a = cells * n + e;
                        b = a + 1;
                    }
                    case 2 -> {
                        a = e * n;
                        b = (e + 1) * n;
                    }
                    default -> {
                        a = e * n + cells;
                        b = (e + 1) * n + cells;
                    }
                }
                int sa = g + s * n + e, sb = sa + 1;
                ix[k++] = a;
                ix[k++] = sa;
                ix[k++] = sb;
                ix[k++] = a;
                ix[k++] = sb;
                ix[k++] = b;
            }
        }
        return ix;
    }

    /**
     * Builds the vertices of a tile.
     *
     * @param id the tile; must not be {@code null}
     * @param heights the decoded height grid, {@code (cells + 1)^2} values, row 0 north; must not be
     *     {@code null}
     * @param cells the number of quads along an edge
     * @param imageSize the width of the tile's image in pixels, for the texture coordinates
     * @param out receives {@code vertexCount(cells) * FLOATS} floats; must not be {@code null}
     * @param reference receives the ECEF position of the reference point (3 values); must not be
     *     {@code null}
     */
    static void build(TileId id, float[] heights, int cells, int imageSize, float[] out, double[] reference) {
        int n = cells + 1;
        double lat0 = id.centerLatitude(), lon0 = id.centerLongitude();
        Vec3d ref = Wgs84.toEcef(lat0, lon0, 0.0);
        reference[0] = ref.x();
        reference[1] = ref.y();
        reference[2] = ref.z();
        double cellNorth = (id.north() - id.south()) / cells * Wgs84.meridionalRadius(lat0);
        double widthRad = id.east() - id.west();
        double depth = skirtDepth(id.zoom(), id.y(), cells);
        float[] flat = new float[n * n];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = Math.max(0f, heights[i]);
        }
        double[] local = new double[3];
        double texel = 1.0 / imageSize;
        for (int j = 0; j < n; j++) {
            double lat = ProceduralTileSource.latitudeAt(id, (double) j / cells);
            double cellEast = widthRad / cells * Wgs84.A * Math.max(Math.cos(lat), 1e-6);
            for (int i = 0; i < n; i++) {
                double lon = ProceduralTileSource.longitudeAt(id, (double) i / cells);
                double h = flat[j * n + i];
                Vec3d p = Wgs84.toEcef(lat, lon, h);
                Vec3d east = Wgs84.east(lon), north = Wgs84.north(lat, lon), up = Wgs84.up(lat, lon);
                TerrainRgb.normal(flat, n, n, i, j, cellEast, cellNorth, 1.0, local);
                double nx = east.x() * local[0] + north.x() * local[1] + up.x() * local[2];
                double ny = east.y() * local[0] + north.y() * local[1] + up.y() * local[2];
                double nz = east.z() * local[0] + north.z() * local[1] + up.z() * local[2];
                float u = (float) (texel * 0.5 + (1.0 - texel) * i / cells);
                float v = (float) (texel * 0.5 + (1.0 - texel) * j / cells);
                put(out, j * n + i, p.x() - ref.x(), p.y() - ref.y(), p.z() - ref.z(), nx, ny, nz, u, v);
                if (j == 0 || j == cells || i == 0 || i == cells) {
                    Vec3d low = Wgs84.toEcef(lat, lon, h - depth);
                    if (j == 0) {
                        put(out, n * n + i, low.x() - ref.x(), low.y() - ref.y(), low.z() - ref.z(), nx, ny, nz, u, v);
                    }
                    if (j == cells) {
                        put(out, n * n + n + i, low.x() - ref.x(), low.y() - ref.y(), low.z() - ref.z(), nx, ny, nz, u, v);
                    }
                    if (i == 0) {
                        put(out, n * n + 2 * n + j, low.x() - ref.x(), low.y() - ref.y(), low.z() - ref.z(), nx, ny, nz, u, v);
                    }
                    if (i == cells) {
                        put(out, n * n + 3 * n + j, low.x() - ref.x(), low.y() - ref.y(), low.z() - ref.z(), nx, ny, nz, u, v);
                    }
                }
            }
        }
    }

    private static void put(float[] out, int vertex, double px, double py, double pz, double nx, double ny, double nz, float u, float v) {
        int o = vertex * FLOATS;
        out[o] = (float) px;
        out[o + 1] = (float) py;
        out[o + 2] = (float) pz;
        out[o + 3] = (float) nx;
        out[o + 4] = (float) ny;
        out[o + 5] = (float) nz;
        out[o + 6] = u;
        out[o + 7] = v;
    }

    /**
     * Builds the vertices of the cap that closes the map at a pole: a fan from the ring at the
     * limit latitude of the projection to the pole, flat at height 0.
     *
     * @param north {@code true} for the cap at the north pole, {@code false} for the south pole
     * @param segments the number of segments of the ring, at least 3
     * @param out receives {@code (segments + 1) * FLOATS} floats, the pole first; must not be
     *     {@code null}
     * @param reference receives the ECEF position of the pole (3 values); must not be {@code null}
     */
    static void polarCap(boolean north, int segments, float[] out, double[] reference) {
        Vec3d pole = Wgs84.toEcef(north ? Math.PI / 2 : -Math.PI / 2, 0.0, 0.0);
        reference[0] = pole.x();
        reference[1] = pole.y();
        reference[2] = pole.z();
        double nz = north ? 1.0 : -1.0;
        put(out, 0, 0, 0, 0, 0, 0, nz, 0.5f, 0.5f);
        double lat = north ? WebMercator.MAX_LATITUDE : -WebMercator.MAX_LATITUDE;
        for (int s = 0; s < segments; s++) {
            double lon = 2 * Math.PI * s / segments;
            Vec3d p = Wgs84.toEcef(lat, lon, 0.0);
            put(out, 1 + s, p.x() - pole.x(), p.y() - pole.y(), p.z() - pole.z(), 0, 0, nz, 0.5f, 0.5f);
        }
    }

    /**
     * Gives the indices of a polar cap fan.
     *
     * @param segments the number of segments of the ring
     * @return {@code 3 * segments} indices into the vertices of {@link #polarCap}
     */
    static int[] polarCapIndices(int segments) {
        int[] ix = new int[3 * segments];
        for (int s = 0; s < segments; s++) {
            ix[3 * s] = 0;
            ix[3 * s + 1] = 1 + s;
            ix[3 * s + 2] = 1 + (s + 1) % segments;
        }
        return ix;
    }
}
