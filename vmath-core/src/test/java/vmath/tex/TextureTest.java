package vmath.tex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.core.Vec3f;

class TextureTest {

    @Test
    void formatTableIsConsistent() {
        Set<Integer> seen = new HashSet<>();
        for (TextureFormat f : TextureFormat.values()) {
            assertTrue(seen.add(f.vkFormat()), "unique VkFormat value " + f);
            assertEquals(f, TextureFormat.fromVkFormat(f.vkFormat()));
            assertTrue(f.bytesPerBlock() > 0 && f.blockWidth() > 0 && f.blockHeight() > 0);
            if (f.name().startsWith("ASTC")) {
                assertEquals(16, f.bytesPerBlock(), "every ASTC block is 128 bits");
                assertTrue(f.name().contains(f.blockWidth() + "x" + f.blockHeight()), f.name());
            }
            if (f.name().startsWith("BC") && !f.name().startsWith("BC1") && !f.name().startsWith("BC4")) {
                assertEquals(16, f.bytesPerBlock(), f.name());
            }
            assertEquals(f.name().endsWith("SRGB"), f.isSrgb(), f.name());
        }
        assertNull(TextureFormat.fromVkFormat(-1));
        assertNull(TextureFormat.fromVkFormat(0));
        // values the Vulkan specification fixes
        assertEquals(37, TextureFormat.R8G8B8A8_UNORM.vkFormat());
        assertEquals(145, TextureFormat.BC7_UNORM.vkFormat());
        assertEquals(157, TextureFormat.ASTC_4x4_UNORM.vkFormat());
        assertEquals(8, TextureFormat.BC1_RGB_UNORM.bytesPerBlock());
    }

    @Test
    void imageBytesRoundUpToWholeBlocks() {
        assertEquals(1024L * 1024 * 4, TextureFormat.R8G8B8A8_UNORM.imageBytes(1024, 1024));
        assertEquals(8L, TextureFormat.BC1_RGB_UNORM.imageBytes(1, 1), "a 1 x 1 image still takes a whole block");
        assertEquals(8L * 2 * 2, TextureFormat.BC1_RGB_UNORM.imageBytes(5, 5));
        assertEquals(16L * 2 * 2, TextureFormat.ASTC_6x6_UNORM.imageBytes(7, 7));
        assertEquals(16L * 1 * 1, TextureFormat.ASTC_12x12_UNORM.imageBytes(12, 12));
    }

    @Test
    void mipChainSizes() {
        assertEquals(1, TextureLayout.maxLevels(1, 1, 1));
        assertEquals(11, TextureLayout.maxLevels(1024, 1024, 1));
        assertEquals(11, TextureLayout.maxLevels(1024, 3, 1));
        assertEquals(10, TextureLayout.maxLevels(1000, 1000, 1));
        assertEquals(1, TextureLayout.levelSize(1, 5));
        assertEquals(5, TextureLayout.levelSize(20, 2));
        TextureLayout t = TextureLayout.texture2d(TextureFormat.R8G8B8A8_UNORM, 8, 4);
        assertEquals(4, t.levels());
        long sum = 0;
        for (int l = 0; l < 4; l++) {
            assertEquals(sum, t.levelOffset(l));
            sum += (long) t.levelWidth(l) * t.levelHeight(l) * 4;
        }
        assertEquals(sum, t.totalBytes());
        assertEquals(8 * 4 * 4 + 4 * 2 * 4 + 2 * 1 * 4 + 1 * 1 * 4, sum);
    }

    @Test
    void offsetsOfLayersAndFaces() {
        TextureLayout t = new TextureLayout(TextureFormat.BC7_UNORM, 16, 16, 1, 3, 2, 6);
        long img0 = t.imageBytes(0);
        assertEquals(16L * 4 * 4, img0, "4 x 4 blocks of 16 bytes");
        assertEquals(0, t.imageOffset(0, 0, 0));
        assertEquals(img0, t.imageOffset(0, 0, 1));
        assertEquals(6 * img0, t.imageOffset(0, 1, 0), "all faces of layer 0 come before layer 1");
        assertEquals(t.levelBytes(0), t.levelOffset(1));
        assertEquals(t.levelBytes(0) + 7 * t.imageBytes(1), t.imageOffset(1, 1, 1));
        assertEquals(t.levelOffset(2) + t.levelBytes(2), t.totalBytes());
        assertThrows(IndexOutOfBoundsException.class, () -> t.imageOffset(0, 2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> t.levelOffset(3));
        assertThrows(IllegalArgumentException.class, () -> new TextureLayout(TextureFormat.R8_UNORM, 4, 4, 1, 4, 1, 1), "4 x 4 has 3 levels");
        assertThrows(IllegalArgumentException.class, () -> new TextureLayout(TextureFormat.R8_UNORM, 4, 4, 1, 1, 1, 3));
    }

    @Test
    void volumeTexturesCountDepthSlices() {
        TextureLayout t = new TextureLayout(TextureFormat.R8_UNORM, 4, 4, 4, 3, 1, 1);
        assertEquals(64, t.imageBytes(0));
        assertEquals(8, t.imageBytes(1));
        assertEquals(1, t.imageBytes(2));
    }

    @Test
    void cubeFaceMappingRoundTrips() {
        Rnd rnd = Rnd.create();
        for (int i = 0; i < Rnd.N; i++) {
            Vec3f d = rnd.nextVec3f();
            if (d.length() < 1e-2f) {
                continue;
            }
            CubeFace f = CubeFace.of(d.x(), d.y(), d.z());
            float u = f.u(d.x(), d.y(), d.z()), v = f.v(d.x(), d.y(), d.z());
            assertTrue(u >= -1e-5f && u <= 1 + 1e-5f && v >= -1e-5f && v <= 1 + 1e-5f, "on the face: " + u + ", " + v);
            Vec3f back = f.direction(u, v);
            // the same direction up to length
            float k = back.dot(d) / d.dot(d);
            assertTrue(k > 0f, "same side");
            assertEquals(0f, back.sub(d.mul(k)).length(), 1e-3f * Math.max(1f, back.length()));
        }
        for (CubeFace f : CubeFace.values()) {
            Vec3f centre = f.direction(0.5f, 0.5f);
            assertEquals(1f, centre.length(), 1e-6f);
            assertEquals(f, CubeFace.of(centre.x(), centre.y(), centre.z()));
        }
        assertEquals(1f, CubeFace.POSITIVE_X.direction(0.5f, 0.5f).x());
        assertEquals(-1f, CubeFace.NEGATIVE_Y.direction(0.5f, 0.5f).y());
        assertEquals(-1f, CubeFace.NEGATIVE_Z.direction(0.5f, 0.5f).z());
    }

    private static ByteBuffer file(int vkFormat, int w, int h, int d, int layers, int faces, int levels, int scheme, TextureLayout layout, int totalBytes) {
        int stored = Math.max(1, levels);
        ByteBuffer b = ByteBuffer.allocate(Ktx2.HEADER_BYTES + stored * Ktx2.LEVEL_INDEX_ENTRY_BYTES + totalBytes);
        // level data smallest first, as in a file
        long offset = Ktx2.HEADER_BYTES + (long) stored * Ktx2.LEVEL_INDEX_ENTRY_BYTES;
        Ktx2.Level[] lv = new Ktx2.Level[stored];
        for (int l = stored - 1; l >= 0; l--) {
            long len = layout.levelBytes(l);
            lv[l] = new Ktx2.Level(offset, len, len);
            offset += len;
        }
        Ktx2.writeHeader(new Ktx2.Header(vkFormat, 1, w, h, d, layers, faces, levels, scheme, 0, 0, 0, 0, 0, 0, lv), b);
        b.rewind();
        return b;
    }

    @Test
    void ktx2WriteHeaderChecksTheCapacityAndKeepsTheByteOrder() {
        Ktx2.Header h = new Ktx2.Header(37, 1, 4, 4, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, new Ktx2.Level[] {new Ktx2.Level(104, 64, 64)});
        ByteBuffer small = ByteBuffer.allocate(Ktx2.HEADER_BYTES + Ktx2.LEVEL_INDEX_ENTRY_BYTES - 1).order(ByteOrder.BIG_ENDIAN);
        assertThrows(java.nio.BufferOverflowException.class, () -> Ktx2.writeHeader(h, small));
        assertEquals(0, small.position(), "nothing was written");
        assertEquals(ByteOrder.BIG_ENDIAN, small.order());
        ByteBuffer exact = ByteBuffer.allocate(Ktx2.HEADER_BYTES + Ktx2.LEVEL_INDEX_ENTRY_BYTES).order(ByteOrder.BIG_ENDIAN);
        Ktx2.writeHeader(h, exact);
        assertEquals(exact.capacity(), exact.position());
        assertEquals(ByteOrder.BIG_ENDIAN, exact.order());
    }

    @Test
    void ktx2HeaderRoundTrip() {
        TextureLayout layout = TextureLayout.texture2d(TextureFormat.BC7_UNORM, 64, 32);
        ByteBuffer b = file(TextureFormat.BC7_UNORM.vkFormat(), 64, 32, 0, 0, 1, layout.levels(), 0, layout, (int) layout.totalBytes());
        Ktx2.Header h = Ktx2.parse(b);
        assertEquals(TextureFormat.BC7_UNORM, h.format());
        assertEquals(64, h.pixelWidth());
        assertEquals(32, h.pixelHeight());
        assertEquals(7, h.levelCount());
        assertEquals(7, h.levels().length);
        assertEquals(layout, h.layout());
        assertEquals(1, h.layers());
        assertTrue(!h.isCube() && !h.isSupercompressed());
        // level 0 is the largest and is stored last
        assertTrue(h.levels()[0].byteOffset() > h.levels()[6].byteOffset());
        assertEquals(layout.levelBytes(0), h.levels()[0].byteLength());
        assertEquals(b.capacity(), h.levels()[0].byteOffset() + h.levels()[0].byteLength());
        assertEquals(0, b.position(), "absolute reads leave the buffer alone");
        ByteBuffer big = ByteBuffer.wrap(b.array()).order(ByteOrder.BIG_ENDIAN);
        assertEquals(h.pixelWidth(), Ktx2.parse(big).pixelWidth(), "the byte order of the buffer does not matter");
    }

    @Test
    void ktx2CubeAndUnmipped() {
        TextureLayout layout = new TextureLayout(TextureFormat.R8G8B8A8_SRGB, 16, 16, 1, 1, 1, 6);
        ByteBuffer b = file(TextureFormat.R8G8B8A8_SRGB.vkFormat(), 16, 16, 0, 0, 6, 0, 0, layout, (int) layout.totalBytes());
        Ktx2.Header h = Ktx2.parse(b);
        assertTrue(h.isCube());
        assertEquals(0, h.levelCount(), "0 means the loader generates the chain");
        assertEquals(1, h.levels().length, "the base level is still indexed");
        assertEquals(1, h.layout().levels());
        assertTrue(h.format().isSrgb());
    }

    @Test
    void ktx2UnknownFormatAndSupercompression() {
        TextureLayout layout = TextureLayout.texture2d(TextureFormat.R8_UNORM, 4, 4);
        ByteBuffer b = file(0, 4, 4, 0, 0, 1, layout.levels(), Ktx2.SUPERCOMPRESSION_BASIS_LZ, layout, (int) layout.totalBytes());
        Ktx2.Header h = Ktx2.parse(b);
        assertNull(h.format(), "VK_FORMAT_UNDEFINED");
        assertNull(h.layout());
        assertTrue(h.isSupercompressed());
        assertNotNull(h.levels());
    }

    @Test
    void ktx2RejectsBadData() {
        TextureLayout layout = TextureLayout.texture2d(TextureFormat.R8_UNORM, 4, 4);
        byte[] good = file(9, 4, 4, 0, 0, 1, layout.levels(), 0, layout, (int) layout.totalBytes()).array();
        assertThrows(Ktx2.FormatException.class, () -> Ktx2.parse(ByteBuffer.wrap(new byte[10])));
        byte[] badMagic = good.clone();
        badMagic[1] = 0;
        assertThrows(Ktx2.FormatException.class, () -> Ktx2.parse(ByteBuffer.wrap(badMagic)));
        assertThrows(Ktx2.FormatException.class, () -> Ktx2.parse(ByteBuffer.wrap(good, 0, 90)), "level index cut off");
        ByteBuffer badFaces = ByteBuffer.wrap(good.clone()).order(ByteOrder.LITTLE_ENDIAN);
        badFaces.putInt(36, 3);
        assertThrows(Ktx2.FormatException.class, () -> Ktx2.parse(badFaces));
        ByteBuffer badWidth = ByteBuffer.wrap(good.clone()).order(ByteOrder.LITTLE_ENDIAN);
        badWidth.putInt(20, 0);
        assertThrows(Ktx2.FormatException.class, () -> Ktx2.parse(badWidth));
        ByteBuffer badLevels = ByteBuffer.wrap(good.clone()).order(ByteOrder.LITTLE_ENDIAN);
        badLevels.putInt(40, 12);
        assertThrows(Ktx2.FormatException.class, () -> Ktx2.parse(badLevels));
        ByteBuffer badRange = ByteBuffer.wrap(good.clone()).order(ByteOrder.LITTLE_ENDIAN);
        badRange.putLong(Ktx2.HEADER_BYTES + 8, 1L << 40);
        assertThrows(Ktx2.FormatException.class, () -> Ktx2.parse(badRange));
        ByteBuffer cubeNotSquare = ByteBuffer.wrap(good.clone()).order(ByteOrder.LITTLE_ENDIAN);
        cubeNotSquare.putInt(36, 6);
        cubeNotSquare.putInt(24, 2);
        assertThrows(Ktx2.FormatException.class, () -> Ktx2.parse(cubeNotSquare));
    }

    @Test
    void sizesNearTheIntegerLimitDoNotOverflow() {
        // width + blockWidth - 1 overflowed an int; the block counts are exact now
        assertEquals(536_870_912, TextureFormat.BC7_UNORM.blocksWide(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, TextureFormat.R8_UNORM.blocksWide(Integer.MAX_VALUE));
        assertEquals(16L * 536_870_912L * 536_870_912L, TextureFormat.BC7_UNORM.imageBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
        // a texture that is large but addressable is accepted, one that is not is refused instead of reporting negative sizes
        TextureLayout big = new TextureLayout(TextureFormat.R8_UNORM, 1 << 20, 1 << 20, 1, 1, 1, 1);
        assertEquals(1L << 40, big.totalBytes());
        assertThrows(IllegalArgumentException.class, () -> new TextureLayout(TextureFormat.R8G8B8A8_SRGB, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TextureLayout(TextureFormat.BC7_UNORM, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, Integer.MAX_VALUE, 6));
    }
}
