package vmath.map;

import vmath.annotations.Experimental;
import vmath.gl.GraphicsCapabilities;

/**
 * The GLSL that colours a height texture on the GPU: the same hillshade and ramp look-up as
 * {@link TerrainShading}, one pixel per node, for a draw that covers the tile.
 *
 * <p>The vertex shader needs no buffers: draw 3 vertices as {@code GL_TRIANGLES} with an empty
 * vertex array object and it covers the viewport with one triangle. With a viewport of the size of
 * the grid each pixel is one node; drawn into a larger viewport the heights are read with the
 * nearest node (a quad with texture coordinates and bilinear filtering is the engine's choice for
 * a map tile, the shading of a node is the part defined here). The textures:
 *
 * <ul>
 *   <li>{@value #U_HEIGHTS}: {@code sampler2D}, one 32-bit float channel ({@code GL_R32F}), row 0 the
 *       north row, nearest filtering (it is read with {@code texelFetch}); {@code NaN} is no data;
 *   <li>{@value #U_RAMP}: {@code sampler2D}, {@code texels x 1} RGBA8 from {@link ColorRamp.Baked#writeRgba}, read with {@code texelFetch}.
 * </ul>
 *
 * <p>The uniforms: {@value #U_RAMP_RANGE} ({@code vec2}, the range of the baked ramp),
 * {@value #U_RAMP_TEXELS} ({@code int}), {@value #U_SUN} ({@code vec3}, {@link Hillshade#sunVector}),
 * {@value #U_CELL} ({@code vec2}, the ground size of a cell, east and north), {@value #U_EXAGGERATION} and
 * {@value #U_STRENGTH} ({@code float}). {@link #cell} gives the cell size of a grid.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * String vs = TerrainShader.vertexSource(caps);
 * String fs = TerrainShader.fragmentSource(caps);
 * double[] sun = Hillshade.sunVector(Math.toRadians(315), Math.toRadians(45));
 * double[] cell = TerrainShader.cell(grid);
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class TerrainShader {

    /** The name of the height texture sampler. */
    public static final String U_HEIGHTS = "u_heights";
    /** The name of the ramp texture sampler. */
    public static final String U_RAMP = "u_ramp";
    /** The name of the uniform with the value range of the ramp ({@code vec2}). */
    public static final String U_RAMP_RANGE = "u_rampRange";
    /** The name of the uniform with the number of texels of the ramp ({@code int}). */
    public static final String U_RAMP_TEXELS = "u_rampTexels";
    /** The name of the uniform with the unit vector towards the light ({@code vec3}). */
    public static final String U_SUN = "u_sun";
    /** The name of the uniform with the ground size of a cell ({@code vec2}). */
    public static final String U_CELL = "u_cell";
    /** The name of the uniform with the vertical exaggeration ({@code float}). */
    public static final String U_EXAGGERATION = "u_exaggeration";
    /** The name of the uniform with the strength of the shade ({@code float}). */
    public static final String U_STRENGTH = "u_strength";

    private TerrainShader() {
    }

    /**
     * Gives the vertex shader.
     *
     * @param caps the capabilities of the context; must not be {@code null}
     * @return the GLSL source: one triangle that covers the viewport, from {@code gl_VertexID}
     */
    public static String vertexSource(GraphicsCapabilities caps) {
        return caps.glsl().versionLine() + """

                void main() {
                    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
    }

    /**
     * Gives the fragment shader.
     *
     * @param caps the capabilities of the context; must not be {@code null}
     * @return the GLSL source
     */
    public static String fragmentSource(GraphicsCapabilities caps) {
        return caps.glsl().versionLine() + """

                uniform sampler2D u_heights;
                uniform sampler2D u_ramp;
                uniform vec2 u_rampRange;
                uniform int u_rampTexels;
                uniform vec3 u_sun;
                uniform vec2 u_cell;
                uniform float u_exaggeration;
                uniform float u_strength;
                out vec4 o_color;

                float nodeHeight(ivec2 p, ivec2 size, float centre) {
                    float v = texelFetch(u_heights, clamp(p, ivec2(0), size - 1), 0).r;
                    return isnan(v) ? centre : v;
                }

                void main() {
                    ivec2 size = textureSize(u_heights, 0);
                    ivec2 p = ivec2(int(gl_FragCoord.x), size.y - 1 - int(gl_FragCoord.y));
                    float e = texelFetch(u_heights, p, 0).r;
                    if (isnan(e)) {
                        o_color = vec4(0.0);
                        return;
                    }
                    float a = nodeHeight(p + ivec2(-1, -1), size, e);
                    float b = nodeHeight(p + ivec2(0, -1), size, e);
                    float c = nodeHeight(p + ivec2(1, -1), size, e);
                    float d = nodeHeight(p + ivec2(-1, 0), size, e);
                    float f = nodeHeight(p + ivec2(1, 0), size, e);
                    float g = nodeHeight(p + ivec2(-1, 1), size, e);
                    float h = nodeHeight(p + ivec2(0, 1), size, e);
                    float i = nodeHeight(p + ivec2(1, 1), size, e);
                    float dzdx = ((c + 2.0 * f + i) - (a + 2.0 * d + g)) / (8.0 * u_cell.x);
                    float dzdy = ((a + 2.0 * b + c) - (g + 2.0 * h + i)) / (8.0 * u_cell.y);
                    vec3 n = normalize(vec3(-u_exaggeration * dzdx, -u_exaggeration * dzdy, 1.0));
                    float shade = max(dot(n, u_sun), 0.0);
                    float u = clamp((e - u_rampRange.x) / (u_rampRange.y - u_rampRange.x), 0.0, 1.0);
                    int index = min(u_rampTexels - 1, int(u * float(u_rampTexels)));
                    vec4 ramp = texelFetch(u_ramp, ivec2(index, 0), 0);
                    o_color = vec4(ramp.rgb * mix(1.0, shade, u_strength), ramp.a);
                }
                """;
    }

    /**
     * Gives the ground size of a cell of a grid, for {@value #U_CELL}.
     *
     * @param grid the grid; must not be {@code null}
     * @return {@code {east, north}} in ground metres
     */
    public static double[] cell(TerrainGrid grid) {
        return new double[] {grid.cellEast() * grid.groundScale(), grid.cellNorth() * grid.groundScale()};
    }
}
