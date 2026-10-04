package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_CULL_FACE;
import static org.lwjgl.opengl.GL45.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL45.GL_NEAREST;
import static org.lwjgl.opengl.GL45.GL_R32F;
import static org.lwjgl.opengl.GL45.GL_RED;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteTextures;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glDisable;
import static org.lwjgl.opengl.GL45.glDrawArrays;
import static org.lwjgl.opengl.GL45.glEnable;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glProgramUniform1f;
import static org.lwjgl.opengl.GL45.glProgramUniform4f;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;
import static org.lwjgl.opengl.GL45.glTextureSubImage2D;
import static org.lwjgl.opengl.GL45.glUseProgram;

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;
import vmath.occlusion.DepthBuffer;

/**
 * Draws one level of a {@link DepthBuffer} as a small picture in the window: pixels that no
 * occluder covers are dark blue, the others are grey, nearer is brighter.
 *
 * <p>The buffer stores the reciprocal of the view distance ({@link DepthBuffer#invDepth}); the
 * shader turns it back into a distance and maps zero to the far limit given in {@link #draw}. The
 * texture has the size of the buffer's finest level and is refilled for the chosen level every
 * time {@link #upload} is called. The picture is drawn in the heads-up display's pass, so it
 * belongs in {@link Demo#hud}.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
public final class DepthInset {

    private final int width;
    private final int height;
    private final int program;
    private final int rectLocation;
    private final int screenLocation;
    private final int maxDepthLocation;
    private final int vao;
    private final int texture;
    private final FloatBuffer pixels;

    /**
     * Creates the texture and the program for a buffer's finest level. The OpenGL context must be
     * current.
     *
     * @param width the width of the buffer's finest level in pixels
     * @param height the height of the buffer's finest level in pixels
     */
    public DepthInset(int width, int height) {
        this.width = width;
        this.height = height;
        program = Gl.program("""
                #version 450 core
                uniform vec4 rect;      // x, y, width, height in window pixels, y from the top
                uniform vec4 screen;    // window width, window height, texture width, texture height
                out vec2 vUv;
                void main() {
                    vec2 corner = vec2(float(gl_VertexID == 1 || gl_VertexID == 4 || gl_VertexID == 5),
                                       float(gl_VertexID == 2 || gl_VertexID == 3 || gl_VertexID == 5));
                    vUv = vec2(corner.x, 1.0 - corner.y);
                    vec2 px = rect.xy + corner * rect.zw;
                    gl_Position = vec4(px.x / screen.x * 2.0 - 1.0, 1.0 - px.y / screen.y * 2.0, 0.0, 1.0);
                }
                """, """
                #version 450 core
                in vec2 vUv;
                layout(binding = 0) uniform sampler2D depth;
                uniform float maxDepth;
                out vec4 color;
                void main() {
                    float inv = texture(depth, vUv).r;
                    if (inv <= 0.0) {
                        color = vec4(0.05, 0.07, 0.22, 1.0);
                    } else {
                        float g = 1.0 - clamp((1.0 / inv) / maxDepth, 0.0, 1.0);
                        color = vec4(vec3(0.15 + 0.85 * g), 1.0);
                    }
                }
                """);
        rectLocation = glGetUniformLocation(program, "rect");
        screenLocation = glGetUniformLocation(program, "screen");
        maxDepthLocation = glGetUniformLocation(program, "maxDepth");
        vao = glCreateVertexArrays();
        texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, 1, GL_R32F, width, height);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        pixels = MemoryUtil.memAllocFloat(width * height);
    }

    /**
     * Copies a level of the buffer into the texture.
     *
     * @param buffer the depth buffer, finished for the frame; must not be {@code null}
     * @param level the pyramid level to show, 0 for the finest; the level is stretched to the
     *     picture's size, so a coarse level looks blocky, which is the point
     */
    public void upload(DepthBuffer buffer, int level) {
        int lw = Math.max(1, width >> level), lh = Math.max(1, height >> level);
        pixels.clear();
        for (int y = 0; y < height; y++) {
            int sy = Math.min(lh - 1, y * lh / height);
            for (int x = 0; x < width; x++) {
                int sx = Math.min(lw - 1, x * lw / width);
                pixels.put(buffer.invDepth(sx, sy, level));
            }
        }
        pixels.flip();
        glTextureSubImage2D(texture, 0, 0, 0, width, height, GL_RED, GL_FLOAT, pixels);
    }

    /**
     * Draws the picture.
     *
     * @param x the left edge in window pixels
     * @param y the top edge in window pixels
     * @param w the width in window pixels
     * @param h the height in window pixels
     * @param windowWidth the width of the window in pixels
     * @param windowHeight the height of the window in pixels
     * @param maxDepth the view distance that is drawn black, beyond which nothing is told apart
     */
    public void draw(float x, float y, float w, float h, int windowWidth, int windowHeight, float maxDepth) {
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glUseProgram(program);
        glProgramUniform4f(program, rectLocation, x, y, w, h);
        glProgramUniform4f(program, screenLocation, windowWidth, windowHeight, width, height);
        glProgramUniform1f(program, maxDepthLocation, maxDepth);
        glBindTextureUnit(0, texture);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    /**
     * Deletes the GPU objects and the native upload buffer.
     */
    public void dispose() {
        glDeleteVertexArrays(vao);
        glDeleteTextures(texture);
        glDeleteProgram(program);
        MemoryUtil.memFree(pixels);
    }
}
