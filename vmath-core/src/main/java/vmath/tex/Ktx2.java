package vmath.tex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import vmath.annotations.Experimental;

/**
 * Reads the header and the level index of a KTX 2.0 file.
 *
 * <p>It does not decode pixels and does not decompress supercompressed data (Basis Universal,
 * Zstandard, ZLIB): it tells you where each level is and how it is stored, so a loader can hand the
 * bytes to the GPU or to a transcoder.
 *
 * <p>Layout of the file (all little-endian), from the KTX 2.0 specification: a 12-byte identifier, then nine 32-bit header fields ({@code vkFormat, typeSize,
 * pixelWidth, pixelHeight, pixelDepth, layerCount, faceCount, levelCount, supercompressionScheme}), four 32-bit index fields for the data format descriptor
 * and the key/value data, two 64-bit fields for the supercompression global data, and then
 * {@code max(levelCount, 1)} level index entries of three 64-bit values
 * ({@code byteOffset, byteLength, uncompressedByteLength}).
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads
 * freely. The arrays it hands out are its own storage: do not modify them.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(Path.of("texture.ktx2")));
 * Ktx2.Header header = Ktx2.parse(file);
 * TextureFormat format = header.format();
 * TextureLayout layout = header.layout();
 * long level0 = layout.levelOffset(0);                         // within the tightly packed texture
 * }</pre>
 */
@Experimental("covers the header and level index only; key/value data and the data format descriptor are reported as ranges, not parsed")
public final class Ktx2 {

    private Ktx2() {
    }

    /**
     * The 12 bytes every KTX2 file starts with: {@code "«KTX 20»\r\n\u001A\n"}.
     */
    public static final byte[] IDENTIFIER = {(byte) 0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, (byte) 0xBB, 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * Bytes of the fixed header including the identifier.
     */
    public static final int HEADER_BYTES = 80;
    /**
     * Bytes of one level index entry.
     */
    public static final int LEVEL_INDEX_ENTRY_BYTES = 24;

    /**
     * No supercompression: the level data is stored as it is.
     */
    public static final int SUPERCOMPRESSION_NONE = 0;
    /**
     * Basis Universal BasisLZ supercompression.
     */
    public static final int SUPERCOMPRESSION_BASIS_LZ = 1;
    /**
     * Zstandard supercompression of each level.
     */
    public static final int SUPERCOMPRESSION_ZSTD = 2;
    /**
     * zlib (deflate) supercompression of each level.
     */
    public static final int SUPERCOMPRESSION_ZLIB = 3;

    /**
     * One entry of the level index; level 0 is the largest image.
     *
     * <p>Offsets are from the start of the file.
     *
     * @param byteOffset the byte offset
     * @param byteLength the byte length
     * @param uncompressedByteLength the uncompressed byte length
     */
    public record Level(long byteOffset, long byteLength, long uncompressedByteLength) {
    }

    /**
     * The parsed header.
     *
     * <p>{@code levelCount} is as stored: 0 means the file holds the base level only and the loader
     * is expected to generate the rest. {@code format} is {@code null} when {@code vkFormat} is not
     * in {@link TextureFormat} (and 0, {@code VK_FORMAT_UNDEFINED}, for Basis Universal data).
     *
     * @param vkFormat the vk format
     * @param typeSize the type size
     * @param pixelWidth the pixel width
     * @param pixelHeight the pixel height
     * @param pixelDepth the pixel depth
     * @param layerCount the layer count
     * @param faceCount the face count
     * @param levelCount the level count
     * @param supercompressionScheme the supercompression scheme
     * @param dfdByteOffset the dfd byte offset
     * @param dfdByteLength the dfd byte length
     * @param kvdByteOffset the kvd byte offset
     * @param kvdByteLength the kvd byte length
     * @param sgdByteOffset the sgd byte offset
     * @param sgdByteLength the sgd byte length
     * @param levels the levels; must not be {@code null}
     */
    public record Header(int vkFormat, int typeSize, int pixelWidth, int pixelHeight, int pixelDepth, int layerCount, int faceCount, int levelCount,
                         int supercompressionScheme, int dfdByteOffset, int dfdByteLength, int kvdByteOffset, int kvdByteLength,
                         long sgdByteOffset, long sgdByteLength, Level[] levels) {

        /**
         * Looks up the {@link TextureFormat} that matches the Vulkan format stored in the file
         * header.
         *
         * @return the format of {@code vkFormat}, or {@code null} when it is not one of
         *     {@link TextureFormat}
         */
        public TextureFormat format() {
            return TextureFormat.fromVkFormat(vkFormat);
        }

        /**
         * Returns whether the file is a cube map (six faces).
         *
         * @return {@code true} if the file is a cube map (six faces)
         */
        public boolean isCube() {
            return faceCount == 6;
        }

        /**
         * Returns whether the level data is supercompressed and has to be decompressed before use.
         *
         * @return {@code true} if the level data is supercompressed and has to be decompressed
         *     before use
         */
        public boolean isSupercompressed() {
            return supercompressionScheme != SUPERCOMPRESSION_NONE;
        }

        /**
         * Normalises the layer count of the header, where the file format stores zero for a texture
         * that is not an array.
         *
         * @return number of layers as an array would count them: a stored 0 (not an array) is 1
         */
        public int layers() {
            return Math.max(1, layerCount);
        }

        /**
         * Derives the mip and layer layout that the decoded data will have from the header fields,
         * when it can be known without decoding.
         *
         * @return the addressing of the decoded texture, when the format is known and the file is
         *     not supercompressed; otherwise {@code null}
         */
        public TextureLayout layout() {
            TextureFormat f = format();
            if (f == null) {
                return null;
            }
            int levelsStored = Math.max(1, levelCount);
            return new TextureLayout(f, pixelWidth, Math.max(1, pixelHeight), Math.max(1, pixelDepth), levelsStored, layers(), Math.max(1, faceCount));
        }
    }

    /**
     * Thrown for data that is not a well-formed KTX2 header.
     */
    public static final class FormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates a header that is not well formed, or whose dimensions describe a texture that
         * cannot be addressed.
         *
         * @param message the message; must not be {@code null}
         */
        public FormatException(String message) {
            super(message);
        }
    }

    /**
     * Parses the header and level index at the start of {@code data} (absolute reads; the buffer's
     * position and byte order are not changed).
     *
     * @param data the data; must not be {@code null}
     * @return the parsed header, never {@code null}
     * @throws FormatException if the data is too short, is not a KTX2 file or declares an invalid
     *     dimension, face count or level count
     */
    public static Header parse(ByteBuffer data) {
        ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.limit() < HEADER_BYTES) {
            throw new FormatException("too short for a KTX2 header: " + b.limit() + " bytes");
        }
        for (int i = 0; i < IDENTIFIER.length; i++) {
            if (b.get(i) != IDENTIFIER[i]) {
                throw new FormatException("not a KTX2 file (identifier mismatch at byte " + i + ")");
            }
        }
        int vkFormat = b.getInt(12), typeSize = b.getInt(16);
        int w = b.getInt(20), h = b.getInt(24), d = b.getInt(28);
        int layers = b.getInt(32), faces = b.getInt(36), levelCount = b.getInt(40), scheme = b.getInt(44);
        int dfdOff = b.getInt(48), dfdLen = b.getInt(52), kvdOff = b.getInt(56), kvdLen = b.getInt(60);
        long sgdOff = b.getLong(64), sgdLen = b.getLong(72);
        if (w < 1) {
            throw new FormatException("pixelWidth must be at least 1: " + w);
        }
        if (h < 0 || d < 0 || layers < 0 || levelCount < 0) {
            throw new FormatException("negative dimension, layer or level count");
        }
        if (faces != 1 && faces != 6) {
            throw new FormatException("faceCount must be 1 or 6: " + faces);
        }
        if (faces == 6 && (h == 0 || w != h)) {
            throw new FormatException("a cube map needs square faces: " + w + " x " + h);
        }
        int stored = Math.max(1, levelCount);
        if (levelCount > TextureLayout.maxLevels(w, Math.max(1, h), Math.max(1, d))) {
            throw new FormatException("levelCount " + levelCount + " is more than the dimensions allow");
        }
        long indexEnd = HEADER_BYTES + (long) stored * LEVEL_INDEX_ENTRY_BYTES;
        if (indexEnd > b.limit()) {
            throw new FormatException("the level index (" + stored + " entries) runs past the end of the data");
        }
        Level[] levels = new Level[stored];
        for (int i = 0; i < stored; i++) {
            int o = HEADER_BYTES + i * LEVEL_INDEX_ENTRY_BYTES;
            levels[i] = new Level(b.getLong(o), b.getLong(o + 8), b.getLong(o + 16));
            if (levels[i].byteOffset() < 0 || levels[i].byteLength() < 0 || levels[i].byteOffset() > b.limit()
                    || levels[i].byteLength() > b.limit() - levels[i].byteOffset()) {
                throw new FormatException("level " + i + " lies outside the data");
            }
        }
        Header header = new Header(vkFormat, typeSize, w, h, d, layers, faces, levelCount, scheme, dfdOff, dfdLen, kvdOff, kvdLen, sgdOff, sgdLen, levels);
        try {
            header.layout(); // a header whose dimensions describe a texture that cannot be addressed is malformed, whatever else it says
        } catch (IllegalArgumentException e) {
            throw new FormatException("the dimensions are not a valid texture: " + e.getMessage());
        }
        return header;
    }

    /**
     * Writes the 80-byte header and the level index into {@code out} at its position (little-endian
     * regardless of the buffer's order, which is restored).
     *
     * <p>Used to make test files and to write simple uncompressed containers; the caller writes the
     * level data at the offsets it put in {@code h.levels()}.
     *
     * @param h the header; must not be {@code null}
     * @param out receives the result; must not be {@code null}
     * @throws java.nio.BufferOverflowException if {@code out} has fewer bytes remaining than the
     *     header and the level index need; nothing is written then
     */
    public static void writeHeader(Header h, ByteBuffer out) {
        int needed = HEADER_BYTES + LEVEL_INDEX_ENTRY_BYTES * h.levels().length;
        if (out.remaining() < needed) {
            throw new java.nio.BufferOverflowException();
        }
        ByteOrder saved = out.order();
        out.order(ByteOrder.LITTLE_ENDIAN);
        try {
            out.put(IDENTIFIER);
            out.putInt(h.vkFormat()).putInt(h.typeSize()).putInt(h.pixelWidth()).putInt(h.pixelHeight()).putInt(h.pixelDepth());
            out.putInt(h.layerCount()).putInt(h.faceCount()).putInt(h.levelCount()).putInt(h.supercompressionScheme());
            out.putInt(h.dfdByteOffset()).putInt(h.dfdByteLength()).putInt(h.kvdByteOffset()).putInt(h.kvdByteLength());
            out.putLong(h.sgdByteOffset()).putLong(h.sgdByteLength());
            for (Level l : h.levels()) {
                out.putLong(l.byteOffset()).putLong(l.byteLength()).putLong(l.uncompressedByteLength());
            }
        } finally {
            out.order(saved);
        }
    }
}
