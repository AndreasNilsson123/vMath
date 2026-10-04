package vmath.samples.demos.globe;

import vmath.geo.TileId;

/**
 * Where the demo gets its tiles from: a height grid and an image for every tile of the Web Mercator
 * pyramid.
 *
 * <p>The only implementation in the repository is {@link ProceduralTileSource}, because the demos do
 * not download anything. A source that reads real tiles (imagery from a tile server, elevation as
 * Terrarium or terrain-RGB PNGs) would return the same two arrays; the heights must be
 * node-registered ({@code cells + 1} samples along an edge, the first and last on the edge of the
 * tile, see {@code TerrainRgb}) so that neighbouring tiles agree along their border.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> {@link #load} is called from several worker threads at the same time and
 * must be thread-safe.
 */
interface TileSource {

    /**
     * Gives the number of quads along the edge of a tile mesh; a tile has {@code cells + 1} height
     * samples along an edge.
     *
     * @return the number of cells, at least 1
     */
    int cells();

    /**
     * Gives the width and height of a tile image in pixels.
     *
     * @return the size, at least 2
     */
    int imageSize();

    /**
     * Produces a tile.
     *
     * @param id the tile; must not be {@code null}
     * @return the data of the tile
     */
    TileData load(TileId id);

    /**
     * The data of one tile.
     *
     * @param id the tile
     * @param terrarium the heights in the Terrarium encoding, three bytes per sample, row 0 (the
     *     north row) first, {@code (cells + 1)^2} samples
     * @param imagery the surface colour as RGBA bytes, row 0 (the north row) first,
     *     {@code imageSize^2} pixels
     */
    record TileData(TileId id, byte[] terrarium, byte[] imagery) {
    }
}
