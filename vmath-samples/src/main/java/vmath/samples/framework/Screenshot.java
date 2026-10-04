package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.GL_BACK;
import static org.lwjgl.opengl.GL45.GL_RGBA;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL45.glReadBuffer;
import static org.lwjgl.opengl.GL45.glReadPixels;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;

/**
 * Reads the frame that was just drawn back from the GPU, writes it as a PNG and tells whether it is
 * blank.
 *
 * <p>The frame must be read before the buffers are swapped, because the contents of the back
 * buffer after a swap are undefined; the runner does this on the last frame of a scripted run and
 * when the screenshot key is pressed.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless, but {@link #capture} must be called on the thread that owns
 * the OpenGL context.
 */
public final class Screenshot {

    private Screenshot() {
    }

    /**
     * Reads the back buffer.
     *
     * @param width the width of the framebuffer in pixels
     * @param height the height of the framebuffer in pixels
     * @return the image, top row first
     */
    public static BufferedImage capture(int width, int height) {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            glReadBuffer(GL_BACK);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = ((height - 1 - y) * width + x) * 4;
                    int r = pixels.get(i) & 255, g = pixels.get(i + 1) & 255, b = pixels.get(i + 2) & 255;
                    image.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
            return image;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /**
     * Writes an image as a PNG, creating the parent directory if needed.
     *
     * @param image the image; must not be {@code null}
     * @param file the path of the file; must not be {@code null}
     * @throws IOException if the file cannot be written
     */
    public static void write(BufferedImage image, String file) throws IOException {
        File f = new File(file);
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(image, "png", f);
    }

    /**
     * Tells whether an image has too little variation to show a scene: fewer than the given number
     * of distinct colours among a regular sample of its pixels.
     *
     * @param image the image; must not be {@code null}
     * @param minimumColors the number of distinct colours that a real frame has at least; a text
     *     overlay on a single colour already has a handful, so 16 is a reasonable floor
     * @return {@code true} if the image looks blank
     */
    public static boolean isBlank(BufferedImage image, int minimumColors) {
        java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        int stepX = Math.max(1, image.getWidth() / 64), stepY = Math.max(1, image.getHeight() / 64);
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors.size() < minimumColors;
    }
}
