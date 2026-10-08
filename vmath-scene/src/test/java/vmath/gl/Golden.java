package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Golden text for the tests of the generators: the output of a generator is compared byte for byte
 * with a file under {@code src/test/resources/golden}, so that a change of generated shader text is
 * a visible, reviewed change of that file and never a side effect. Run the tests with
 * {@code -Dvmath.writeGolden=true} (and {@code --no-configuration-cache}) to write the files from
 * the current output, then read the difference before committing it.
 *
 * <p>Line endings are normalised to {@code \n} on both sides, so that a checkout on Windows that
 * converted them does not fail.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads that name different files.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Golden.check("gl/vertex-inputs", layout.glslInputs());
 * }</pre>
 */
public final class Golden {

    private Golden() {
    }

    /**
     * Compares generated text with the golden file of a name.
     *
     * @param name the path of the file below {@code golden/}, without the extension
     * @param actual the text the generator produced now
     */
    public static void check(String name, String actual) {
        String normalised = actual.replace("\r\n", "\n");
        String resource = "golden/" + name + ".txt";
        if (Boolean.getBoolean("vmath.writeGolden")) {
            try {
                Path file = Path.of("src/test/resources").resolve(resource);
                Files.createDirectories(file.getParent());
                Files.writeString(file, normalised, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot write " + resource, e);
            }
            return;
        }
        try (InputStream in = Golden.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "the golden file " + resource + " is missing: run with -Dvmath.writeGolden=true");
            assertEquals(new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), normalised, "the generated text no longer matches " + resource);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
