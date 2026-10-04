package vmath.samples.framework;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A texture atlas of the printable ASCII characters in a monospaced font, drawn once with Java 2D
 * into an array of coverage values that the heads-up display uploads to the GPU.
 *
 * <p>The atlas is a grid of equal cells: the 95 characters from space to tilde in order, then one
 * solid cell that the display uses for the backdrops of its text. A character outside that range
 * is shown as a question mark. The cells keep a one-pixel margin so that sampling never reaches
 * the neighbour. The class does not use OpenGL.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable once built: instances can be shared between threads.
 */
public final class FontAtlas {

    /**
     * The first character of the atlas.
     */
    public static final char FIRST = ' ';

    /**
     * The last character of the atlas.
     */
    public static final char LAST = '~';

    private static final int COLUMNS = 16;

    private final int cellWidth;
    private final int cellHeight;
    private final int width;
    private final int height;
    private final byte[] coverage;

    private FontAtlas(int cellWidth, int cellHeight, int width, int height, byte[] coverage) {
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.width = width;
        this.height = height;
        this.coverage = coverage;
    }

    /**
     * Draws the atlas.
     *
     * @param pixelSize the height of the font in pixels; at least 6
     * @return the atlas
     */
    public static FontAtlas bake(int pixelSize) {
        System.setProperty("java.awt.headless", "true");
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, pixelSize);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics metrics = pg.getFontMetrics();
        int cw = metrics.charWidth('M') + 2;
        int ch = metrics.getHeight() + 2;
        int ascent = metrics.getAscent();
        pg.dispose();

        int glyphs = LAST - FIRST + 1;
        int cells = glyphs + 1;
        int columns = COLUMNS;
        int rows = (cells + columns - 1) / columns;
        int w = columns * cw, h = rows * ch;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setFont(font);
        g.setColor(Color.WHITE);
        for (int i = 0; i < glyphs; i++) {
            int x = (i % columns) * cw + 1, y = (i / columns) * ch + 1;
            g.drawString(String.valueOf((char) (FIRST + i)), x, y + ascent);
        }
        int solid = glyphs;
        g.fillRect((solid % columns) * cw + 1, (solid / columns) * ch + 1, cw - 2, ch - 2);
        g.dispose();

        byte[] alpha = new byte[w * h];
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < alpha.length; i++) {
            alpha[i] = (byte) (argb[i] >>> 24);
        }
        return new FontAtlas(cw, ch, w, h, alpha);
    }

    /**
     * Reads the width of one character cell.
     *
     * @return the width in pixels, which is also the advance of the monospaced font plus the margin
     */
    public int cellWidth() {
        return cellWidth;
    }

    /**
     * Reads the height of one character cell.
     *
     * @return the height in pixels, which is also the line height plus the margin
     */
    public int cellHeight() {
        return cellHeight;
    }

    /**
     * Reads the width of the atlas image.
     *
     * @return the width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Reads the height of the atlas image.
     *
     * @return the height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Reads the coverage values of the atlas image.
     *
     * @return the width times height bytes, row by row from the top, 0 for empty and -1 (255) for
     *     fully covered; the array is the live one, which callers must not modify
     */
    public byte[] coverage() {
        return coverage;
    }

    /**
     * Finds the cell of a character.
     *
     * @param c the character
     * @return the cell index; that of the question mark if the character is not printable ASCII
     */
    public static int cell(char c) {
        return c >= FIRST && c <= LAST ? c - FIRST : '?' - FIRST;
    }

    /**
     * Finds the cell of the solid block.
     *
     * @return the cell index of the block that is covered everywhere
     */
    public static int solidCell() {
        return LAST - FIRST + 1;
    }

    /**
     * Reads the left edge of a cell, one pixel in from the margin, as a texture coordinate.
     *
     * @param cell the cell index
     * @return the {@code u} coordinate in 0 to 1
     */
    public float u0(int cell) {
        return ((cell % COLUMNS) * cellWidth + 1f) / width;
    }

    /**
     * Reads the right edge of a cell, one pixel in from the margin, as a texture coordinate.
     *
     * @param cell the cell index
     * @return the {@code u} coordinate in 0 to 1
     */
    public float u1(int cell) {
        return ((cell % COLUMNS) * cellWidth + cellWidth - 1f) / width;
    }

    /**
     * Reads the top edge of a cell, one pixel in from the margin, as a texture coordinate.
     *
     * @param cell the cell index
     * @return the {@code v} coordinate in 0 to 1, 0 at the top of the image
     */
    public float v0(int cell) {
        return ((cell / COLUMNS) * cellHeight + 1f) / height;
    }

    /**
     * Reads the bottom edge of a cell, one pixel in from the margin, as a texture coordinate.
     *
     * @param cell the cell index
     * @return the {@code v} coordinate in 0 to 1, 0 at the top of the image
     */
    public float v1(int cell) {
        return ((cell / COLUMNS) * cellHeight + cellHeight - 1f) / height;
    }
}
