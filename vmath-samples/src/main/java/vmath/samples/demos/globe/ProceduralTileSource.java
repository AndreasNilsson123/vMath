package vmath.samples.demos.globe;

import vmath.core.Wgs84;
import vmath.geo.TerrainRgb;
import vmath.geo.TileId;
import vmath.geo.WebMercator;

/**
 * Generates tiles from {@link Planet}: the heights of the mesh nodes, stored as Terrarium bytes
 * exactly as an elevation tile set would deliver them, and an image whose pixels are the colour of
 * the ground at the centre of each pixel.
 *
 * <p>The height of a node depends only on its longitude and latitude, so two neighbouring tiles
 * produce the same value along their shared edge to the last bit (the position is computed from the
 * tile numbers by the same expression on both sides), and a tile agrees with its parent wherever the
 * parent has a node. The image is shaded by slope from the heights of the pixels around each one,
 * so that it has detail of the size of a pixel rather than of a mesh cell.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable and stateless apart from its two sizes: {@link #load} may be
 * called from any number of threads at the same time.
 */
final class ProceduralTileSource implements TileSource {

    private final int cells;
    private final int imageSize;

    /**
     * Creates a source.
     *
     * @param cells the number of quads along a tile edge, at least 1
     * @param imageSize the width and height of the image in pixels, at least 2
     */
    ProceduralTileSource(int cells, int imageSize) {
        this.cells = cells;
        this.imageSize = imageSize;
    }

    @Override
    public int cells() {
        return cells;
    }

    @Override
    public int imageSize() {
        return imageSize;
    }

    /**
     * Gives the longitude of a position across a tile, computed the same way for every tile so that
     * shared edges agree exactly.
     *
     * @param id the tile
     * @param f the position across the tile in units of tiles, 0 at the west edge and 1 at the east
     * @return the longitude in radians
     */
    static double longitudeAt(TileId id, double f) {
        return WebMercator.longitudeOfU((id.x() + f) / (1 << id.zoom()));
    }

    /**
     * Gives the latitude of a position down a tile, computed the same way for every tile so that
     * shared edges agree exactly.
     *
     * @param id the tile
     * @param f the position down the tile in units of tiles, 0 at the north edge and 1 at the south
     * @return the geodetic latitude in radians
     */
    static double latitudeAt(TileId id, double f) {
        return WebMercator.latitudeOfV((id.y() + f) / (1 << id.zoom()));
    }

    @Override
    public TileData load(TileId id) {
        int n = cells + 1;
        byte[] terrarium = new byte[n * n * 3];
        for (int j = 0; j < n; j++) {
            double lat = latitudeAt(id, (double) j / cells);
            for (int i = 0; i < n; i++) {
                double lon = longitudeAt(id, (double) i / cells);
                TerrainRgb.encode(TerrainRgb.Encoding.TERRARIUM, Planet.height(lon, lat), terrarium, 3 * (j * n + i));
            }
        }
        // heights of the pixel centres with a one pixel border, for the slope
        int m = imageSize + 2;
        double[] h = new double[m * m];
        for (int j = 0; j < m; j++) {
            double lat = latitudeAt(id, (j - 0.5) / imageSize);
            for (int i = 0; i < m; i++) {
                h[j * m + i] = Planet.height(longitudeAt(id, (i - 0.5) / imageSize), lat);
            }
        }
        double west = id.west(), east = id.east(), north = id.north(), south = id.south();
        double[] rgb = new double[3];
        byte[] imagery = new byte[imageSize * imageSize * 4];
        for (int j = 0; j < imageSize; j++) {
            double lat = latitudeAt(id, (j + 0.5) / imageSize);
            double cellEast = (east - west) / imageSize * Wgs84.A * Math.cos(lat);
            double cellNorth = (north - south) / imageSize * Wgs84.meridionalRadius(lat);
            for (int i = 0; i < imageSize; i++) {
                int c = (j + 1) * m + i + 1;
                double height = h[c];
                double gx = (Math.max(0, h[c + 1]) - Math.max(0, h[c - 1])) / (2 * cellEast);
                double gy = (Math.max(0, h[c - m]) - Math.max(0, h[c + m])) / (2 * cellNorth);
                double slope = 1.0 - 1.0 / Math.sqrt(1.0 + gx * gx + gy * gy);
                Planet.albedo(longitudeAt(id, (i + 0.5) / imageSize), lat, height, slope, rgb);
                int o = 4 * (j * imageSize + i);
                imagery[o] = (byte) Math.round(255 * Math.max(0, Math.min(1, rgb[0])));
                imagery[o + 1] = (byte) Math.round(255 * Math.max(0, Math.min(1, rgb[1])));
                imagery[o + 2] = (byte) Math.round(255 * Math.max(0, Math.min(1, rgb[2])));
                imagery[o + 3] = (byte) 255;
            }
        }
        return new TileData(id, terrarium, imagery);
    }
}
