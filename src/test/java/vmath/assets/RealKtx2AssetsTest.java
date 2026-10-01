package vmath.assets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vmath.core.Vec3f;
import vmath.tex.CubeFace;
import vmath.tex.Ktx2;
import vmath.tex.TextureFormat;
import vmath.tex.TextureLayout;

/** The generated KTX2 files (committed under src/test/resources/assets) through the parser, with the pixels decoded independently. */
class RealKtx2AssetsTest {

    private static final String[] FILES = {"ktx2/checker_bc1.ktx2", "ktx2/cubemap_rgba8.ktx2", "ktx2/array_r8.ktx2", "ktx2/nomips_rgba8.ktx2"};

    private static ByteBuffer little(byte[] file) {
        return ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Checks everything about the level index that the specification prescribes and that a loader relies on. */
    private static void checkContainer(byte[] file, Ktx2.Header h) {
        assertEquals(0, h.sgdByteLength(), "no supercompression global data");
        assertEquals(Ktx2.SUPERCOMPRESSION_NONE, h.supercompressionScheme());
        assertEquals(0, h.dfdByteOffset() % 4, "the descriptor is 4-byte aligned");
        assertEquals(0, h.kvdByteOffset() % 4);
        assertTrue(h.dfdByteOffset() >= Ktx2.HEADER_BYTES + h.levels().length * Ktx2.LEVEL_INDEX_ENTRY_BYTES, "after the level index");
        assertTrue(h.dfdByteOffset() + h.dfdByteLength() <= h.kvdByteOffset(), "the descriptor comes before the key/value data");
        assertTrue(h.kvdByteOffset() + h.kvdByteLength() <= file.length);
        long previousStart = Long.MAX_VALUE;
        for (int l = 0; l < h.levels().length; l++) {
            Ktx2.Level level = h.levels()[l];
            assertEquals(0, level.byteOffset() % 16, "level " + l + " starts on a 16-byte boundary");
            assertEquals(level.byteLength(), level.uncompressedByteLength(), "no supercompression");
            assertTrue(level.byteOffset() >= h.kvdByteOffset() + h.kvdByteLength(), "image data comes after the key/value data");
            assertTrue(level.byteOffset() + level.byteLength() <= previousStart, "level " + l + " lies before the level above it: smallest first in the file");
            previousStart = level.byteOffset();
        }
        assertEquals(file.length, h.levels()[0].byteOffset() + h.levels()[0].byteLength(), "the largest level ends the file exactly");
        // the descriptor: total size, version, block size
        ByteBuffer b = little(file);
        int dfd = h.dfdByteOffset();
        assertEquals(h.dfdByteLength(), b.getInt(dfd), "totalSize covers the whole descriptor");
        assertEquals(0, b.getInt(dfd + 4), "vendor 0, basic descriptor");
        assertEquals(2, b.getInt(dfd + 8) & 0xFFFF, "descriptor version 2");
        int blockSize = b.getInt(dfd + 8) >>> 16;
        assertEquals(h.dfdByteLength() - 4, blockSize);
        assertEquals(0, (blockSize - 24) % 16, "whole samples");
    }

    private static List<String[]> keyValues(byte[] file, Ktx2.Header h) {
        ByteBuffer b = little(file);
        List<String[]> out = new ArrayList<>();
        int pos = h.kvdByteOffset(), end = pos + h.kvdByteLength();
        while (pos < end) {
            int len = b.getInt(pos);
            assertTrue(len > 1 && pos + 4 + len <= end, "an entry inside the block");
            byte[] entry = new byte[len];
            b.get(pos + 4, entry);
            int nul = 0;
            while (entry[nul] != 0) {
                nul++;
            }
            out.add(new String[] {new String(entry, 0, nul, StandardCharsets.UTF_8), new String(entry, nul + 1, len - nul - 2, StandardCharsets.UTF_8)});
            assertEquals(0, entry[len - 1], "the value is NUL-terminated");
            pos += 4 + len;
            pos = (pos + 3) & ~3;
        }
        assertEquals(end, pos, "entries fill the block exactly");
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i - 1)[0].compareTo(out.get(i)[0]) < 0, "keys are sorted");
        }
        return out;
    }

    @Test
    void theBc1ChainParsesAndDecodesBackToTheSource() throws IOException {
        byte[] file = AssetFilesTest.resource(FILES[0]);
        Ktx2.Header h = Ktx2.parse(ByteBuffer.wrap(file));
        assertEquals(134, h.vkFormat());
        assertEquals(TextureFormat.BC1_RGBA_SRGB, h.format());
        assertEquals(64, h.pixelWidth());
        assertEquals(64, h.pixelHeight());
        assertEquals(7, h.levelCount());
        assertEquals(1, h.layers());
        assertFalse(h.isCube());
        checkContainer(file, h);
        TextureLayout layout = h.layout();
        assertNotNull(layout);
        assertEquals(7, layout.levels());
        for (int l = 0; l < 7; l++) {
            assertEquals(layout.levelBytes(l), h.levels()[l].byteLength(), "level " + l + " has the size the layout computes (blocks rounded up)");
        }
        // descriptor contents
        ByteBuffer b = little(file);
        int dfd = h.dfdByteOffset();
        assertEquals(128, b.getInt(dfd + 12) & 0xFF, "colour model BC1A");
        assertEquals(1, (b.getInt(dfd + 12) >> 8) & 0xFF, "BT.709 primaries");
        assertEquals(2, (b.getInt(dfd + 12) >> 16) & 0xFF, "sRGB transfer");
        assertEquals(3, b.getInt(dfd + 16) & 0xFF, "a 4x4 block is stored as 3");
        assertEquals(8, b.getInt(dfd + 20) & 0xFF, "8 bytes per block");
        List<String[]> kv = keyValues(file, h);
        assertEquals("KTXorientation", kv.get(0)[0]);
        assertEquals("rd", kv.get(0)[1]);
        assertEquals("vmath AssetFactory", kv.get(1)[1]);
        // the pixels: every level decodes to the source image within the RGB565 quantisation
        for (int l = 0; l < 7; l++) {
            int size = Math.max(1, 64 >> l);
            int[] decoded = Bc1.decode(size, size, file, (int) h.levels()[l].byteOffset());
            int[] source = AssetFactory.checkerLevel(l);
            int worst = 0;
            for (int i = 0; i < source.length; i++) {
                for (int shift = 0; shift <= 16; shift += 8) {
                    worst = Math.max(worst, Math.abs(((decoded[i] >> shift) & 255) - ((source[i] >> shift) & 255)));
                }
            }
            assertTrue(worst <= 8, "level " + l + ": largest channel error " + worst);
        }
    }

    @Test
    void theCubeMapHasSixFacesPerLevelInTheSpecifiedOrder() throws IOException {
        byte[] file = AssetFilesTest.resource(FILES[1]);
        Ktx2.Header h = Ktx2.parse(ByteBuffer.wrap(file));
        assertTrue(h.isCube());
        assertEquals(6, h.faceCount());
        assertEquals(0, h.layerCount());
        assertEquals(5, h.levelCount());
        assertEquals(TextureFormat.R8G8B8A8_SRGB, h.format());
        checkContainer(file, h);
        TextureLayout layout = h.layout();
        assertEquals(6, layout.faces());
        // the expected pixels are rebuilt independently, level by level
        int[][] faces = new int[6][16 * 16];
        for (int f = 0; f < 6; f++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    faces[f][y * 16 + x] = AssetFactory.cubeTexel(f, x, y, 16);
                }
            }
        }
        int size = 16;
        for (int l = 0; l < 5; l++) {
            assertEquals(layout.levelBytes(l), h.levels()[l].byteLength());
            for (int f = 0; f < 6; f++) {
                long at = h.levels()[l].byteOffset() + layout.imageOffset(l, 0, f) - layout.levelOffset(l);
                for (int i = 0; i < size * size; i++) {
                    int p = faces[f][i];
                    int o = (int) at + i * 4;
                    assertEquals((p >> 16) & 255, file[o] & 255, "level " + l + " face " + f + " texel " + i + " red");
                    assertEquals((p >> 8) & 255, file[o + 1] & 255);
                    assertEquals(p & 255, file[o + 2] & 255);
                    assertEquals(p >>> 24, file[o + 3] & 255);
                }
                faces[f] = AssetFactory.downsample(size, size, faces[f]);
            }
            size = Math.max(1, size / 2);
        }
        // the faces of the file agree with CubeFace: the axis direction of a face hits that face's hue
        Vec3f[] axes = {new Vec3f(1f, 0f, 0f), new Vec3f(-1f, 0f, 0f), new Vec3f(0f, 1f, 0f), new Vec3f(0f, -1f, 0f), new Vec3f(0f, 0f, 1f), new Vec3f(0f, 0f, -1f)};
        Random rnd = new Random(3);
        for (int f = 0; f < 6; f++) {
            assertEquals(f, CubeFace.of(axes[f].x(), axes[f].y(), axes[f].z()).ordinal(), "the order of CubeFace is the order of the file");
            for (int trial = 0; trial < 50; trial++) {
                Vec3f d = axes[f].add(new Vec3f(rnd.nextFloat() * 0.8f - 0.4f, rnd.nextFloat() * 0.8f - 0.4f, rnd.nextFloat() * 0.8f - 0.4f));
                CubeFace face = CubeFace.of(d.x(), d.y(), d.z());
                float u = face.u(d.x(), d.y(), d.z()), v = face.v(d.x(), d.y(), d.z());
                int x = Math.min(15, (int) (u * 16)), y = Math.min(15, (int) (v * 16));
                long at = h.levels()[0].byteOffset() + layout.imageOffset(0, 0, face.ordinal()) + (y * 16 + x) * 4L;
                int expected = AssetFactory.cubeTexel(face.ordinal(), x, y, 16);
                assertEquals((expected >> 16) & 255, file[(int) at] & 255, "direction " + d + " reads face " + face);
            }
        }
    }

    @Test
    void theArrayTextureStoresLayersInsideEachLevel() throws IOException {
        byte[] file = AssetFilesTest.resource(FILES[2]);
        Ktx2.Header h = Ktx2.parse(ByteBuffer.wrap(file));
        assertEquals(3, h.layerCount());
        assertEquals(3, h.layers());
        assertEquals(4, h.levelCount());
        assertEquals(TextureFormat.R8_UNORM, h.format());
        checkContainer(file, h);
        TextureLayout layout = h.layout();
        assertEquals(3, layout.layers());
        int[][] layers = new int[3][64];
        for (int layer = 0; layer < 3; layer++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int v = AssetFactory.arrayTexel(layer, x, y);
                    layers[layer][y * 8 + x] = 0xFF000000 | v << 16 | v << 8 | v;
                }
            }
        }
        int size = 8;
        for (int l = 0; l < 4; l++) {
            assertEquals(layout.levelBytes(l), h.levels()[l].byteLength());
            for (int layer = 0; layer < 3; layer++) {
                long at = h.levels()[l].byteOffset() + layout.imageOffset(l, layer, 0) - layout.levelOffset(l);
                for (int i = 0; i < size * size; i++) {
                    assertEquals(layers[layer][i] & 255, file[(int) at + i] & 255, "level " + l + " layer " + layer + " texel " + i);
                }
                layers[layer] = AssetFactory.downsample(size, size, layers[layer]);
            }
            size = Math.max(1, size / 2);
        }
    }

    @Test
    void aFileThatAsksForGeneratedMipsHasOneStoredLevel() throws IOException {
        byte[] file = AssetFilesTest.resource(FILES[3]);
        Ktx2.Header h = Ktx2.parse(ByteBuffer.wrap(file));
        assertEquals(0, h.levelCount());
        assertEquals(1, h.levels().length);
        assertEquals(1, h.layout().levels());
        assertEquals(TextureFormat.R8G8B8A8_UNORM, h.format());
        checkContainer(file, h);
        assertEquals(64, h.levels()[0].byteLength());
        assertEquals(0, file[(int) h.levels()[0].byteOffset()] & 255);
        assertEquals(255, file[(int) h.levels()[0].byteOffset() + 1] & 255);
    }

    @Test
    void corruptedRealFilesOnlyEverRaiseIllegalArgument() throws IOException {
        Random rnd = new Random(2024);
        int accepted = 0, rejected = 0;
        for (String name : FILES) {
            byte[] original = AssetFilesTest.resource(name);
            for (int trial = 0; trial < 5000; trial++) {
                byte[] d = original.clone();
                int edits = 1 + rnd.nextInt(3);
                for (int e = 0; e < edits; e++) {
                    int pos = rnd.nextInt(Math.min(d.length, 400)); // the header, the level index and the descriptors are where the interesting fields are
                    switch (rnd.nextInt(3)) {
                        case 0 -> d[pos] = (byte) rnd.nextInt(256);
                        case 1 -> d[pos] = (byte) (rnd.nextBoolean() ? 0 : 0xFF);
                        default -> d[pos] ^= (byte) (1 << rnd.nextInt(8));
                    }
                }
                byte[] mutated = rnd.nextInt(10) == 0 ? java.util.Arrays.copyOf(d, rnd.nextInt(d.length)) : d;
                try {
                    Ktx2.Header h = Ktx2.parse(ByteBuffer.wrap(mutated));
                    TextureLayout layout = h.layout();
                    if (layout != null) {
                        layout.totalBytes();
                    }
                    accepted++;
                } catch (IllegalArgumentException expected) {
                    rejected++;
                }
            }
        }
        assertTrue(accepted > 200 && rejected > 200, "both outcomes must occur: " + accepted + " accepted, " + rejected + " rejected");
        assertArrayEquals(new byte[] {(byte) 0xAB, 'K', 'T', 'X', ' ', '2', '0', (byte) 0xBB, '\r', '\n', 0x1A, '\n'}, Ktx2.IDENTIFIER);
    }
}
