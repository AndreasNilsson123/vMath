package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL45.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL45.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL45.GL_LINEAR;
import static org.lwjgl.opengl.GL45.GL_NEAREST;
import static org.lwjgl.opengl.GL45.GL_NONE;
import static org.lwjgl.opengl.GL45.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL45.GL_RGBA;
import static org.lwjgl.opengl.GL45.GL_RGBA8;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL45.glBindFramebuffer;
import static org.lwjgl.opengl.GL45.glBlitNamedFramebuffer;
import static org.lwjgl.opengl.GL45.glCheckNamedFramebufferStatus;
import static org.lwjgl.opengl.GL45.glCreateFramebuffers;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL45.glDeleteTextures;
import static org.lwjgl.opengl.GL45.glGetTextureImage;
import static org.lwjgl.opengl.GL45.glNamedFramebufferTexture;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;
import static org.lwjgl.opengl.GL45.glViewport;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * An offscreen frame: a framebuffer object with an RGBA8 colour texture and a 32-bit float depth
 * texture, both of which can be sampled by later passes (the depth texture is what a Hi-Z pyramid
 * is built from) and read back to the CPU (what the checks of the demos compare).
 *
 * <p>{@link #bind} makes it the target and sets the viewport; {@link #blitToWindow} copies the colour
 * to the window's framebuffer (use it at the end of {@code render}, so that the heads-up display
 * draws on top).
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
public final class SceneTarget {

    private final int width;
    private final int height;
    private final int framebuffer;
    private final int color;
    private final int depth;

    /**
     * Creates the target. The OpenGL context must be current.
     *
     * @param width the width in pixels; at least 1
     * @param height the height in pixels; at least 1
     * @throws IllegalStateException if the framebuffer is not complete
     */
    public SceneTarget(int width, int height) {
        this.width = width;
        this.height = height;
        color = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(color, 1, GL_RGBA8, width, height);
        glTextureParameteri(color, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(color, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        depth = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(depth, 1, GL_DEPTH_COMPONENT32F, width, height);
        glTextureParameteri(depth, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(depth, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        framebuffer = glCreateFramebuffers();
        glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, color, 0);
        glNamedFramebufferTexture(framebuffer, GL_DEPTH_ATTACHMENT, depth, 0);
        int status = glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(String.format("the framebuffer of a %d x %d scene target is not complete: 0x%04X", width, height, status));
        }
    }

    /**
     * Reads the width.
     *
     * @return the width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Reads the height.
     *
     * @return the height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Reads the depth texture, to build a pyramid from.
     *
     * @return the name of the texture, a {@code GL_DEPTH_COMPONENT32F} 2D texture
     */
    public int depthTexture() {
        return depth;
    }

    /**
     * Reads the colour texture.
     *
     * @return the name of the texture, an {@code RGBA8} 2D texture
     */
    public int colorTexture() {
        return color;
    }

    /**
     * Makes this the target of drawing and sets the viewport to cover it.
     */
    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, width, height);
    }

    /**
     * Copies the colour to the window's framebuffer, stretched to its size, and makes the window the
     * target again.
     *
     * @param windowWidth the width of the window in pixels
     * @param windowHeight the height of the window in pixels
     */
    public void blitToWindow(int windowWidth, int windowHeight) {
        glBlitNamedFramebuffer(framebuffer, 0, 0, 0, width, height, 0, 0, windowWidth, windowHeight, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, windowWidth, windowHeight);
    }

    /**
     * Reads the colour back to the CPU, bottom row first, which stalls until the GPU is done.
     *
     * @param out receives {@code width * height * 4} bytes; must not be {@code null}
     */
    public void readColor(ByteBuffer out) {
        glGetTextureImage(color, 0, GL_RGBA, GL_UNSIGNED_BYTE, out);
    }

    /**
     * Reads the depth back to the CPU, bottom row first, which stalls until the GPU is done.
     *
     * @param out receives {@code width * height} floats; must not be {@code null}
     */
    public void readDepth(FloatBuffer out) {
        glGetTextureImage(depth, 0, GL_DEPTH_COMPONENT, GL_FLOAT, out);
    }

    /**
     * Deletes the framebuffer and its textures.
     */
    public void dispose() {
        glDeleteFramebuffers(framebuffer);
        glDeleteTextures(color);
        glDeleteTextures(depth);
    }
}
