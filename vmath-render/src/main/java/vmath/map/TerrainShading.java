package vmath.map;

import vmath.annotations.Experimental;

/**
 * Colours a {@link TerrainGrid} on the CPU: the colour of a baked {@link ColorRamp} at the height of
 * every node, darkened by the {@link Hillshade}. The result is an RGBA image of the grid, one pixel
 * per node, that is uploaded as a texture and drawn on the tile: the older, universally available
 * way. {@link TerrainShader} does the same per pixel on the GPU from the height texture and the ramp
 * texture, and the GPU check in the samples compares the two.
 *
 * <p>The pixel is {@code (rgb of the ramp) * mix(1, shade, strength)} with the alpha of the ramp; a
 * node with no data is transparent. {@code strength} 0 is the ramp alone, 1 the full shade.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads that write to
 * different arrays.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ColorRamp.Baked ramp = ColorRamp.linear(new double[] {0, 2000}, new int[] {0x206020FF, 0xFFFFFFFF}).bake(0f, 2000f, 256);
 * byte[] rgba = new byte[4 * grid.width() * grid.height()];
 * TerrainShading.render(grid, ramp, Math.toRadians(315), Math.toRadians(45), 1.0, 0.6, rgba);
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class TerrainShading {

    private TerrainShading() {
    }

    /**
     * Renders the grid.
     *
     * @param grid the grid; must not be {@code null}
     * @param ramp the baked ramp; must not be {@code null}
     * @param azimuth the direction the light comes from, radians clockwise from north
     * @param altitude the height of the light above the horizon, radians, 0 to pi/2
     * @param exaggeration the vertical exaggeration, positive
     * @param strength how much the shade darkens, 0 to 1
     * @param rgba receives {@code 4 * width * height} bytes, row 0 (north) first
     * @throws IllegalArgumentException if {@code rgba} is too short, or the strength, altitude or exaggeration is out of range
     */
    public static void render(TerrainGrid grid, ColorRamp.Baked ramp, double azimuth, double altitude, double exaggeration, double strength, byte[] rgba) {
        int w = grid.width(), h = grid.height();
        if (rgba.length < 4 * w * h) {
            throw new IllegalArgumentException("rgba needs " + 4 * w * h + " bytes");
        }
        if (!(strength >= 0.0 && strength <= 1.0) || !(altitude >= 0.0 && altitude <= Math.PI / 2) || !(exaggeration > 0.0)) {
            throw new IllegalArgumentException("strength 0 to 1, altitude 0 to pi/2 and a positive exaggeration are needed");
        }
        double[] sun = Hillshade.sunVector(azimuth, altitude);
        double[] g = new double[2];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                float height = grid.at(c, r);
                int o = 4 * (r * w + c);
                if (height != height) {
                    rgba[o] = 0;
                    rgba[o + 1] = 0;
                    rgba[o + 2] = 0;
                    rgba[o + 3] = 0;
                    continue;
                }
                Hillshade.gradient(grid, c, r, g);
                double f = 1.0 - strength + strength * Hillshade.shadeOf(g[0], g[1], exaggeration, sun);
                int color = ramp.colorAt(height);
                rgba[o] = (byte) Math.round(((color >>> 24) & 255) * f);
                rgba[o + 1] = (byte) Math.round(((color >>> 16) & 255) * f);
                rgba[o + 2] = (byte) Math.round(((color >>> 8) & 255) * f);
                rgba[o + 3] = (byte) color;
            }
        }
    }
}
