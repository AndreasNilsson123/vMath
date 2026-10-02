package vmath.assets;

import vmath.Report;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Keeps the committed files under {@code src/test/resources/assets} identical to what {@link AssetFactory} produces (so they can be opened in any glTF or KTX viewer
 * and the tests that read them are reading the generator's output), and checks the PNG writer against the JDK's decoder. To regenerate the files run
 * {@code ./gradlew :test --tests "vmath.assets.AssetFilesTest" -Dvmath.writeAssets=src/test/resources/assets --rerun-tasks}.
 */
class AssetFilesTest {

    @Test
    void writeAssetsWhenAsked() throws IOException {
        String target = System.getProperty("vmath.writeAssets");
        org.junit.jupiter.api.Assumptions.assumeTrue(target != null, "a generator, not a check: run with -Dvmath.writeAssets=<directory> to regenerate the assets");
        AssetFactory.writeAll(Path.of(target));
        for (String name : AssetFactory.all().keySet()) {
            org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(Path.of(target).resolve(name)), "the generator wrote " + name);
        }
        Report.println("ASSETS written to " + Path.of(target).toAbsolutePath());
    }

    static byte[] resource(String path) throws IOException {
        try (InputStream in = AssetFilesTest.class.getResourceAsStream("/assets/" + path)) {
            assertNotNull(in, "missing resource assets/" + path + "; regenerate with -Dvmath.writeAssets=src/test/resources/assets (see the class comment)");
            return in.readAllBytes();
        }
    }

    @Test
    void committedFilesEqualTheGenerator() throws IOException {
        if (System.getProperty("vmath.writeAssets") != null) {
            return; // being regenerated right now
        }
        for (Map.Entry<String, byte[]> e : AssetFactory.all().entrySet()) {
            assertArrayEquals(e.getValue(), resource(e.getKey()), e.getKey() + " differs from the generator output");
        }
    }

    @Test
    void theGeneratorIsDeterministic() {
        Map<String, byte[]> a = AssetFactory.all(), b = AssetFactory.all();
        assertEquals(a.keySet(), b.keySet());
        for (String k : a.keySet()) {
            assertArrayEquals(a.get(k), b.get(k), k);
            assertTrue(a.get(k).length > 100, k + " is not empty");
        }
    }

    @Test
    void theJdkDecodesOurPng() throws IOException {
        int[] px = PngWriter.checker(16, 8, 4, 0xFFC03030, 0xFF3060C0);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(PngWriter.rgba(16, 8, px)));
        assertNotNull(img, "a valid PNG");
        assertEquals(16, img.getWidth());
        assertEquals(8, img.getHeight());
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(px[y * 16 + x], img.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
        // alpha survives too
        int[] alpha = {0x80FF0000, 0x00000000, 0xFF00FF00, 0x400000FF};
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(PngWriter.rgba(2, 2, alpha)));
        assertEquals(0x80FF0000, a.getRGB(0, 0));
        assertEquals(0x400000FF, a.getRGB(1, 1));
    }
}
