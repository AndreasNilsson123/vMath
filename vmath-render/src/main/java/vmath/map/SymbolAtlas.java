package vmath.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import vmath.annotations.Experimental;
import vmath.mesh.RectPacker;

/**
 * A texture atlas of symbol sprites: the packing of a set of small RGBA images into one image, and
 * the rectangle of each in texture coordinates.
 *
 * <p>The sprites are packed with {@link RectPacker} into the smallest power-of-two image that
 * holds them, each with a gutter of {@value #GUTTER} texels that repeats its edge, so that
 * bilinear filtering and minification never pick up a neighbour. The texture coordinates of a
 * sprite are inset by half a texel so that the quad covers exactly the sprite. The library ships
 * no symbols: the images are the caller's (a symbol set is a licensing decision).
 *
 * <p>The pixels are straight (not premultiplied) 8-bit RGBA, row 0 at the top, the order that
 * image decoders and {@code glTexSubImage2D} take; the texture coordinate {@code v = 0} is
 * therefore the top of the image.
 *
 * <p><b>Thread safety.</b> A built atlas is immutable (its pixel array is exposed without a copy
 * because it can be large: do not write to it); the builder is for one thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * SymbolAtlas.Builder builder = SymbolAtlas.builder();
 * builder.add("airport", 24, 24, rgba);                  // rgba: 24 * 24 * 4 bytes
 * SymbolAtlas atlas = builder.build(2048);
 * float[] uv = new float[4];
 * atlas.rect(atlas.indexOf("airport"), uv);
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class SymbolAtlas {

    /** The border around every sprite in texels, filled with the sprite's own edge. */
    public static final int GUTTER = 1;

    private final int width;
    private final int height;
    private final byte[] pixels;
    private final String[] names;
    private final int[] x;
    private final int[] y;
    private final int[] w;
    private final int[] h;
    private final Map<String, Integer> index;

    private SymbolAtlas(int width, int height, byte[] pixels, String[] names, int[] x, int[] y, int[] w, int[] h) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        this.names = names;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.index = new HashMap<>();
        for (int i = 0; i < names.length; i++) {
            index.put(names[i], i);
        }
    }

    /**
     * Starts an atlas.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Collects the sprites of an atlas. */
    public static final class Builder {
        private final List<String> names = new ArrayList<>();
        private final List<int[]> sizes = new ArrayList<>();
        private final List<byte[]> images = new ArrayList<>();

        private Builder() {
        }

        /**
         * Adds a sprite.
         *
         * @param name the name it is found by; must not be {@code null} or already used
         * @param width the width in texels, at least 1
         * @param height the height in texels, at least 1
         * @param rgba the pixels, {@code width * height * 4} bytes, rows from the top; copied
         * @return this builder
         * @throws IllegalArgumentException if the name is used, a size is below 1 or the array has the wrong length
         */
        public Builder add(String name, int width, int height, byte[] rgba) {
            if (name == null || names.contains(name)) {
                throw new IllegalArgumentException("a sprite needs a name that is not used: " + name);
            }
            if (width < 1 || height < 1 || rgba.length != width * height * 4) {
                throw new IllegalArgumentException("need width, height >= 1 and width * height * 4 bytes: " + width + " x " + height + ", " + rgba.length + " bytes");
            }
            names.add(name);
            sizes.add(new int[] {width, height});
            images.add(rgba.clone());
            return this;
        }

        /**
         * Packs the sprites.
         *
         * @param maxSize the largest side of the image, a power of two (for example the texture size limit of the context)
         * @return the atlas
         * @throws IllegalArgumentException if there is no sprite or {@code maxSize} is below 1
         * @throws IllegalStateException if the sprites do not fit into {@code maxSize} x {@code maxSize}
         */
        public SymbolAtlas build(int maxSize) {
            int n = names.size();
            if (n == 0 || maxSize < 1) {
                throw new IllegalArgumentException("need at least one sprite and a positive maximum size");
            }
            int[] pw = new int[n], ph = new int[n];
            for (int i = 0; i < n; i++) {
                pw[i] = sizes.get(i)[0] + 2 * GUTTER;
                ph[i] = sizes.get(i)[1] + 2 * GUTTER;
            }
            RectPacker.Result packed = RectPacker.packSmallestPowerOfTwo(pw, ph, maxSize, false);
            if (packed == null) {
                throw new IllegalStateException("the " + n + " sprites do not fit into " + maxSize + " x " + maxSize);
            }
            int aw = packed.width(), ah = packed.height();
            byte[] out = new byte[aw * ah * 4];
            int[] sx = new int[n], sy = new int[n], sw = new int[n], sh = new int[n];
            for (int i = 0; i < n; i++) {
                int iw = sizes.get(i)[0], ih = sizes.get(i)[1];
                byte[] src = images.get(i);
                sx[i] = packed.x()[i] + GUTTER;
                sy[i] = packed.y()[i] + GUTTER;
                sw[i] = iw;
                sh[i] = ih;
                for (int row = -GUTTER; row < ih + GUTTER; row++) {
                    int sr = Math.min(Math.max(row, 0), ih - 1);
                    for (int col = -GUTTER; col < iw + GUTTER; col++) {
                        int sc = Math.min(Math.max(col, 0), iw - 1);
                        System.arraycopy(src, (sr * iw + sc) * 4, out, ((sy[i] + row) * aw + sx[i] + col) * 4, 4);
                    }
                }
            }
            return new SymbolAtlas(aw, ah, out, names.toArray(new String[0]), sx, sy, sw, sh);
        }
    }

    /**
     * Makes the atlas with every pixel passed through a colour map: a display palette applied to the
     * sprites. The layout is unchanged, so the texture coordinates stay valid and only the pixels need
     * uploading.
     *
     * @param colorMap maps a colour {@code 0xRRGGBBAA} to the colour to show; must not be {@code null}
     * @return a new atlas with new pixels
     */
    public SymbolAtlas mapped(IntUnaryOperator colorMap) {
        byte[] out = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i += 4) {
            int c = colorMap.applyAsInt((pixels[i] & 0xFF) << 24 | (pixels[i + 1] & 0xFF) << 16 | (pixels[i + 2] & 0xFF) << 8 | pixels[i + 3] & 0xFF);
            out[i] = (byte) (c >>> 24);
            out[i + 1] = (byte) (c >>> 16);
            out[i + 2] = (byte) (c >>> 8);
            out[i + 3] = (byte) c;
        }
        return new SymbolAtlas(width, height, out, names, x, y, w, h);
    }

    /**
     * Gives the width of the image.
     *
     * @return texels, a power of two
     */
    public int width() {
        return width;
    }

    /**
     * Gives the height of the image.
     *
     * @return texels, a power of two
     */
    public int height() {
        return height;
    }

    /**
     * Gives the pixels, without a copy.
     *
     * @return {@code width * height * 4} bytes of straight RGBA, rows from the top; do not write to it
     */
    public byte[] pixels() {
        return pixels;
    }

    /**
     * Gives the number of sprites.
     *
     * @return the count
     */
    public int count() {
        return names.length;
    }

    /**
     * Gives the name of a sprite.
     *
     * @param sprite the sprite, 0 to {@link #count()} - 1
     * @return the name
     * @throws IndexOutOfBoundsException if the sprite is out of range
     */
    public String name(int sprite) {
        return names[sprite];
    }

    /**
     * Finds a sprite by name.
     *
     * @param name the name
     * @return the sprite index
     * @throws IllegalArgumentException if there is no such sprite
     */
    public int indexOf(String name) {
        Integer i = index.get(name);
        if (i == null) {
            throw new IllegalArgumentException("no sprite named " + name);
        }
        return i;
    }

    /**
     * Gives the size of a sprite.
     *
     * @param sprite the sprite
     * @return its width in texels
     * @throws IndexOutOfBoundsException if the sprite is out of range
     */
    public int spriteWidth(int sprite) {
        return w[sprite];
    }

    /**
     * Gives the size of a sprite.
     *
     * @param sprite the sprite
     * @return its height in texels
     * @throws IndexOutOfBoundsException if the sprite is out of range
     */
    public int spriteHeight(int sprite) {
        return h[sprite];
    }

    /**
     * Gives the position of a sprite in the image, without the gutter.
     *
     * @param sprite the sprite
     * @param out receives {@code x, y, width, height} in texels at {@code out[0..3]}
     * @throws IndexOutOfBoundsException if the sprite is out of range
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void texels(int sprite, int[] out) {
        if (out.length < 4) {
            throw new IllegalArgumentException("out must have room for 4 values");
        }
        out[0] = x[sprite];
        out[1] = y[sprite];
        out[2] = w[sprite];
        out[3] = h[sprite];
    }

    /**
     * Gives the texture coordinates of a sprite.
     *
     * @param sprite the sprite
     * @param out receives {@code u0, v0, u1, v1} at {@code out[0..3]}: the top left and the bottom
     *     right corner, the whole sprite (the texel edges, not the centres)
     * @throws IndexOutOfBoundsException if the sprite is out of range
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public void rect(int sprite, float[] out) {
        if (out.length < 4) {
            throw new IllegalArgumentException("out must have room for 4 values");
        }
        out[0] = (float) x[sprite] / width;
        out[1] = (float) y[sprite] / height;
        out[2] = (float) (x[sprite] + w[sprite]) / width;
        out[3] = (float) (y[sprite] + h[sprite]) / height;
    }

    /**
     * Reads a texel with nearest filtering, for the CPU model of the fragment shader.
     *
     * @param u the horizontal texture coordinate
     * @param v the vertical texture coordinate
     * @return {@code 0xRRGGBBAA} of the nearest texel, the coordinates clamped to the image
     */
    public int sampleNearest(float u, float v) {
        int tx = Math.min(width - 1, Math.max(0, (int) Math.floor(u * width)));
        int ty = Math.min(height - 1, Math.max(0, (int) Math.floor(v * height)));
        int o = (ty * width + tx) * 4;
        return (pixels[o] & 0xFF) << 24 | (pixels[o + 1] & 0xFF) << 16 | (pixels[o + 2] & 0xFF) << 8 | pixels[o + 3] & 0xFF;
    }
}
