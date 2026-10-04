package vmath.samples.demos.gpucull;

import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_NEAREST_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL45.GL_NEAREST;
import static org.lwjgl.opengl.GL45.GL_R32F;
import static org.lwjgl.opengl.GL45.GL_READ_ONLY;
import static org.lwjgl.opengl.GL45.GL_RED;
import static org.lwjgl.opengl.GL45.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_FETCH_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL45.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL45.glBindImageTexture;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glClearTexImage;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteTextures;
import static org.lwjgl.opengl.GL45.glDispatchCompute;
import static org.lwjgl.opengl.GL45.glGetTextureImage;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glMemoryBarrier;
import static org.lwjgl.opengl.GL45.glProgramUniform2i;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;
import static org.lwjgl.opengl.GL45.glUseProgram;

import java.nio.FloatBuffer;
import vmath.occlusion.HiZ;
import vmath.samples.framework.Gl;

/**
 * The Hi-Z pyramid on the GPU: an {@code R32F} texture with a full chain of levels, built from a
 * depth texture with two compute shaders.
 *
 * <p>The first shader copies the window depth of the scene (0 near, 1 far) into level 0 as
 * normalised device depth ({@code 2 d - 1}, the convention {@code DepthRange.NEGATIVE_ONE_TO_ONE}
 * that the library's culling shader expects of the pyramid's texels). The second reduces a level
 * into the next, the texel being the {@code max}, the farthest, of the up to four texels below it,
 * and a parent covers the texels {@code 2 x, 2 x + 1} and {@code 2 y, 2 y + 1} that exist, the
 * rule of {@code HiZPyramid} on the CPU, for any size and not only powers of two. The level sizes
 * are {@code HiZ.mipSize}. Before the first frame the whole pyramid holds the far value 1, so
 * nothing is occluded.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
final class HizBuilder {

    private final int sourceWidth;
    private final int sourceHeight;
    private final int width;
    private final int height;
    private final int levels;
    private final int texture;
    private final int copyProgram;
    private final int reduceProgram;
    private final int copySourceLocation;
    private final int copySizeLocation;
    private final int reduceSrcLocation;
    private final int reduceDstLocation;

    /**
     * Creates the pyramid for a depth image of a size. Level 0 is the next power of two above each
     * side, because OpenGL halves the sizes of a mip chain rounding down and the library's culling
     * shader (and {@code HiZPyramid}) round up, and the two agree only for powers of two. The
     * OpenGL context must be current.
     *
     * @param sourceWidth the width of the depth image in pixels
     * @param sourceHeight the height of the depth image in pixels
     */
    HizBuilder(int sourceWidth, int sourceHeight) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.width = Integer.highestOneBit(Math.max(1, sourceWidth - 1)) << 1;
        this.height = Integer.highestOneBit(Math.max(1, sourceHeight - 1)) << 1;
        this.levels = HiZ.mipCount(width, height);
        texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, levels, GL_R32F, width, height);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        for (int l = 0; l < levels; l++) {
            glClearTexImage(texture, l, GL_RED, GL_FLOAT, new float[] {1f});
        }
        copyProgram = Gl.computeProgram("""
                #version 450 core
                layout(local_size_x = 16, local_size_y = 16) in;
                layout(binding = 0) uniform sampler2D depthTexture;
                layout(r32f, binding = 1) writeonly uniform image2D destination;
                uniform ivec2 sourceSize;
                uniform ivec2 size;
                void main() {
                    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                    if (p.x >= size.x || p.y >= size.y) return;
                    // the texel covers these pixels of the depth image: take the farthest, which is conservative
                    ivec2 lo = min((p * sourceSize) / size, sourceSize - 1);
                    ivec2 hi = min(((p + 1) * sourceSize + size - 1) / size - 1, sourceSize - 1);
                    float far = -1e30;
                    for (int y = lo.y; y <= hi.y; y++) {
                        for (int x = lo.x; x <= hi.x; x++) {
                            far = max(far, texelFetch(depthTexture, ivec2(x, y), 0).r * 2.0 - 1.0);
                        }
                    }
                    imageStore(destination, p, vec4(far));
                }
                """);
        reduceProgram = Gl.computeProgram("""
                #version 450 core
                layout(local_size_x = 16, local_size_y = 16) in;
                layout(r32f, binding = 0) readonly uniform image2D source;
                layout(r32f, binding = 1) writeonly uniform image2D destination;
                uniform ivec2 sourceSize;
                uniform ivec2 destinationSize;
                void main() {
                    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                    if (p.x >= destinationSize.x || p.y >= destinationSize.y) return;
                    ivec2 b = p * 2;
                    float far = imageLoad(source, b).r;
                    bool moreX = b.x + 1 < sourceSize.x, moreY = b.y + 1 < sourceSize.y;
                    if (moreX) far = max(far, imageLoad(source, b + ivec2(1, 0)).r);
                    if (moreY) {
                        far = max(far, imageLoad(source, b + ivec2(0, 1)).r);
                        if (moreX) far = max(far, imageLoad(source, b + ivec2(1, 1)).r);
                    }
                    imageStore(destination, p, vec4(far));
                }
                """);
        copySourceLocation = glGetUniformLocation(copyProgram, "sourceSize");
        copySizeLocation = glGetUniformLocation(copyProgram, "size");
        reduceSrcLocation = glGetUniformLocation(reduceProgram, "sourceSize");
        reduceDstLocation = glGetUniformLocation(reduceProgram, "destinationSize");
    }

    /**
     * Reads the width of level 0, the width of the depth image rounded up to a power of two.
     *
     * @return the width in texels
     */
    int width() {
        return width;
    }

    /**
     * Reads the height of level 0, the height of the depth image rounded up to a power of two.
     *
     * @return the height in texels
     */
    int height() {
        return height;
    }

    /**
     * Resamples a depth image to the size of level 0 the way the copy shader does: a texel takes the
     * farthest of the pixels that it covers. The CPU model of the pyramid is built from this image,
     * so that it can be compared with the GPU's texel by texel.
     *
     * @param ndc the depth image as normalised device depth, row 0 first; must not be {@code null}
     * @param w the width of the image
     * @param h the height of the image
     * @param targetWidth the width of level 0
     * @param targetHeight the height of level 0
     * @return the resampled image, {@code targetWidth * targetHeight} values
     */
    static float[] resample(float[] ndc, int w, int h, int targetWidth, int targetHeight) {
        float[] out = new float[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            int y0 = Math.min(y * h / targetHeight, h - 1), y1 = Math.min(((y + 1) * h + targetHeight - 1) / targetHeight - 1, h - 1);
            for (int x = 0; x < targetWidth; x++) {
                int x0 = Math.min(x * w / targetWidth, w - 1), x1 = Math.min(((x + 1) * w + targetWidth - 1) / targetWidth - 1, w - 1);
                float far = -1e30f;
                for (int sy = y0; sy <= y1; sy++) {
                    for (int sx = x0; sx <= x1; sx++) {
                        far = Math.max(far, ndc[sy * w + sx]);
                    }
                }
                out[y * targetWidth + x] = far;
            }
        }
        return out;
    }

    int levels() {
        return levels;
    }

    /**
     * Reads the texture to bind at unit 0 for the culling shader.
     *
     * @return the name of the {@code R32F} texture with all its levels
     */
    int texture() {
        return texture;
    }

    /**
     * Builds the whole pyramid from a depth texture of the same size.
     *
     * @param depthTexture a {@code GL_DEPTH_COMPONENT32F} texture of the size that the builder was
     *     made for
     */
    void build(int depthTexture) {
        glUseProgram(copyProgram);
        glBindTextureUnit(0, depthTexture);
        glBindImageTexture(1, texture, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
        glProgramUniform2i(copyProgram, copySourceLocation, sourceWidth, sourceHeight);
        glProgramUniform2i(copyProgram, copySizeLocation, width, height);
        glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        glUseProgram(reduceProgram);
        for (int l = 1; l < levels; l++) {
            int sw = HiZ.mipSize(width, l - 1), sh = HiZ.mipSize(height, l - 1), dw = HiZ.mipSize(width, l), dh = HiZ.mipSize(height, l);
            glBindImageTexture(0, texture, l - 1, false, 0, GL_READ_ONLY, GL_R32F);
            glBindImageTexture(1, texture, l, false, 0, GL_WRITE_ONLY, GL_R32F);
            glProgramUniform2i(reduceProgram, reduceSrcLocation, sw, sh);
            glProgramUniform2i(reduceProgram, reduceDstLocation, dw, dh);
            glDispatchCompute((dw + 15) / 16, (dh + 15) / 16, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
        // the culling shader fetches the texels and the checks read them back: both need their own barrier after image stores
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    }

    /**
     * Reads a level back to the CPU, row 0 first (the bottom row of the screen), which stalls until
     * the GPU is done.
     *
     * @param level the level
     * @param out receives {@code HiZ.mipSize(width, level) * HiZ.mipSize(height, level)} floats
     */
    void readLevel(int level, FloatBuffer out) {
        glGetTextureImage(texture, level, GL_RED, GL_FLOAT, out);
    }

    /**
     * Deletes the texture and the programs.
     */
    void dispose() {
        glDeleteTextures(texture);
        glDeleteProgram(copyProgram);
        glDeleteProgram(reduceProgram);
    }
}
