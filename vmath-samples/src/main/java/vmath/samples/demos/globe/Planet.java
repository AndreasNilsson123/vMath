package vmath.samples.demos.globe;

import vmath.util.Noise;

/**
 * A procedural planet: height and colour as functions of longitude and latitude, from noise
 * evaluated on the unit sphere so that the world is seamless across the antimeridian and the poles
 * and the same at every zoom level.
 *
 * <p><b>Why it is procedural.</b> The demo needs elevation and imagery tiles but may not download
 * any (docs/DEMOS.md, rule 4). The geometry that the tiles are placed on is exact (WGS-84 and the
 * EPSG:3857 tile grid); the continents, mountains and colours are noise. A real data source would
 * implement {@link TileSource} instead.
 *
 * <p><b>The height.</b> Large-scale noise sets the continents (about 40% land), ridged noise adds
 * mountain ranges of up to a few kilometres, and fine noise adds detail down to the metre scale, so
 * a camera at the ground still sees relief. The sea floor is below zero and the height is clamped
 * to {@link #MAX_HEIGHT}. Within a few degrees of the limits of the map ({@code +-85.05} degrees)
 * the land fades into sea, so that the ice cap that closes the map at each pole meets flat ground
 * at height 0.
 *
 * <p><b>The colour.</b> {@link #albedo} gives the surface colour from the height, the latitude and
 * the slope: water that is darker where it is deeper, sand, grass, desert and tundra by climate,
 * rock on steep ground, snow above a snow line that falls towards the poles, and ice at the poles.
 * It is the colour before lighting; the shader lights it.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 */
final class Planet {

    /**
     * The highest height that {@link #height} returns, in metres.
     */
    static final double MAX_HEIGHT = 9000.0;

    /**
     * The lowest height that {@link #height} returns, in metres (the sea floor).
     */
    static final double MIN_HEIGHT = -6000.0;

    private static final int SEED = 20260;

    private Planet() {
    }

    /**
     * Gives the height of the ground above the ellipsoid.
     *
     * @param longitude the longitude in radians
     * @param latitude the geodetic latitude in radians
     * @return the height in metres, from {@link #MIN_HEIGHT} to {@link #MAX_HEIGHT}; negative on the
     *     sea floor
     */
    static double height(double longitude, double latitude) {
        double cl = Math.cos(latitude);
        double x = cl * Math.cos(longitude), y = cl * Math.sin(longitude), z = Math.sin(latitude);
        double continent = Noise.fbm3(Noise.Kind.SIMPLEX, x * 1.7, y * 1.7, z * 1.7, SEED, 6, 2.0, 0.5);
        double fade = smoothstep(86.0, 80.0, Math.abs(Math.toDegrees(latitude)));
        double land = (continent - 0.04) * fade - (1.0 - fade) * 0.3;
        if (land <= 0.0) {
            double deep = Math.min(1.0, -land * 3.0);
            return -(40.0 + deep * 5000.0 * (0.8 + 0.2 * Noise.fbm3(Noise.Kind.PERLIN, x * 9, y * 9, z * 9, SEED + 1, 3, 2.0, 0.5)));
        }
        double ridge = 1.0 - Math.abs(Noise.fbm3(Noise.Kind.SIMPLEX, x * 7, y * 7, z * 7, SEED + 2, 5, 2.0, 0.5)) * 2.0;
        ridge = Math.max(0.0, ridge);
        double rise = Math.min(1.0, land * 4.0);
        double detail = Noise.fbm3(Noise.Kind.SIMPLEX, x * 300, y * 300, z * 300, SEED + 3, 14, 2.0, 0.55);
        double h = rise * 250.0 + land * 1400.0 + ridge * ridge * rise * 5200.0 * (0.5 + 0.5 * land) + detail * rise * (200.0 + 900.0 * ridge);
        return Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, Math.max(h, 1.0)));
    }

    /**
     * Gives the surface colour, before lighting.
     *
     * @param longitude the longitude in radians
     * @param latitude the geodetic latitude in radians
     * @param height the height in metres, from {@link #height}
     * @param slope how steep the ground is, 0 for flat and 1 for vertical (1 minus the cosine of the
     *     angle from the vertical)
     * @param out receives red, green and blue from 0 to 1; must not be {@code null}
     */
    static void albedo(double longitude, double latitude, double height, double slope, double[] out) {
        double lat = Math.abs(Math.toDegrees(latitude));
        double cl = Math.cos(latitude);
        double nx = cl * Math.cos(longitude), ny = cl * Math.sin(longitude), nz = Math.sin(latitude);
        if (height <= 0.0) {
            double depth = Math.min(1.0, -height / 4000.0);
            double r = 0.10 * (1 - depth) + 0.01 * depth, g = 0.42 * (1 - depth) + 0.07 * depth, b = 0.58 * (1 - depth) + 0.22 * depth;
            double ice = smoothstep(68.0, 76.0, lat);
            out[0] = r + (0.92 - r) * ice;
            out[1] = g + (0.95 - g) * ice;
            out[2] = b + (0.98 - b) * ice;
            return;
        }
        double wet = 0.5 + 0.5 * Noise.fbm3(Noise.Kind.PERLIN, nx * 4, ny * 4, nz * 4, SEED + 4, 4, 2.0, 0.5);
        double grain = 0.5 + 0.5 * Noise.fbm3(Noise.Kind.VALUE, nx * 4000, ny * 4000, nz * 4000, SEED + 5, 4, 2.0, 0.5);
        double dryness = smoothstep(0.62, 0.35, wet) * smoothstep(48.0, 14.0, lat);
        double cold = smoothstep(52.0, 68.0, lat);
        double r = 0.22 + 0.06 * grain, g = 0.38 + 0.08 * grain, b = 0.14; // grass
        r = mix(r, 0.69 + 0.05 * grain, dryness);                         // desert
        g = mix(g, 0.58 + 0.05 * grain, dryness);
        b = mix(b, 0.36, dryness);
        r = mix(r, 0.44, cold);                                           // tundra
        g = mix(g, 0.46, cold);
        b = mix(b, 0.38, cold);
        double sand = smoothstep(18.0, 2.0, height);
        r = mix(r, 0.80, sand);
        g = mix(g, 0.74, sand);
        b = mix(b, 0.54, sand);
        double rock = Math.max(smoothstep(0.10, 0.32, slope), smoothstep(1800.0, 3200.0, height - cold * 800.0));
        r = mix(r, 0.40 + 0.06 * grain, rock);
        g = mix(g, 0.37 + 0.05 * grain, rock);
        b = mix(b, 0.34, rock);
        double snowLine = 4600.0 - 4300.0 * Math.pow(Math.sin(Math.toRadians(lat)), 1.3);
        double snow = smoothstep(snowLine, snowLine + 500.0, height) * smoothstep(0.55, 0.20, slope);
        snow = Math.max(snow, smoothstep(76.0, 84.0, lat));
        out[0] = mix(r, 0.95, snow);
        out[1] = mix(g, 0.96, snow);
        out[2] = mix(b, 0.98, snow);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }
}
