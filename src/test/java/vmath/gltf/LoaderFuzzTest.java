package vmath.gltf;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.assets.AssetFactory;
import vmath.core.Rnd;

/**
 * A seeded mutation fuzz of the glTF reader and its JSON parser, which take untrusted input: the valid assets of {@code AssetFactory} are damaged (bit flips, byte
 * overwrites, truncation, insertion, deletion, 32-bit words replaced by edge values) and fed to {@link Gltf#parse}, and everything the parsed file offers is then exercised
 * (meshes, skins, clips, accessors, images). The contract is that the <em>only</em> thing a damaged file may do is throw {@link GltfException}: any other exception
 * (index out of bounds, negative array size, class cast, null pointer), an {@link Error} such as {@link OutOfMemoryError} or {@link StackOverflowError}, or a case that takes
 * more than a few seconds fails the test, with the seed and the mutation so it can be replayed. {@code -Dvmath.trials} scales the number of cases (the nightly build uses
 * 20,000), {@code -Dvmath.seed} changes them.
 */
class LoaderFuzzTest {

    private static final long SEED = Rnd.SEED;
    private static final long SLOW_CASE_NANOS = 5_000_000_000L;

    private final Map<String, byte[]> assets = AssetFactory.all();
    private int refusedAtParse;
    private int refusedLater;
    private int fullyExercised;

    // ---------------------------------------------------------------- mutations

    /** The region of a file that a mutation may touch: the whole file, or for a .glb the JSON chunk or the binary chunk, so that more cases get past the header. */
    private static int[] region(byte[] b, String name, SplittableRandom r) {
        if (name.endsWith(".glb") && b.length > 28 && r.nextInt(3) > 0) {
            int jsonLength = (b[12] & 0xFF) | (b[13] & 0xFF) << 8 | (b[14] & 0xFF) << 16 | (b[15] & 0xFF) << 24;
            int jsonEnd = 20 + jsonLength;
            if (jsonLength > 0 && jsonEnd < b.length - 8) {
                return r.nextBoolean() ? new int[] {20, jsonEnd} : new int[] {jsonEnd + 8, b.length};
            }
        }
        return new int[] {0, b.length};
    }

    // ---------------------------------------------------------------- what is exercised after a successful parse

    private static void exercise(Gltf g) {
        for (int m = 0; m < g.meshCount(); m++) {
            Gltf.MeshData md = g.mesh(m);
            for (int p = 0; p < md.primitives().size(); p++) {
                g.toMesh(m, p);
                g.readSkinning(m, p);
            }
        }
        for (int i = 0; i < g.accessorCount(); i++) {
            g.accessorInfo(i);
            g.readFloats(i);
            g.readInts(i);
        }
        for (int i = 0; i < g.imageCount(); i++) {
            g.imageBytes(i);
        }
        g.worldMatrices();
        for (int s = 0; s < g.sceneCount(); s++) {
            g.sceneNodes(s);
        }
        for (int n = 0; n < g.nodeCount(); n++) {
            g.localMatrix(n);
        }
        for (int s = 0; s < g.skinCount(); s++) {
            Gltf.SkinData skin = g.skin(s);
            for (int a = 0; a < g.animationCount(); a++) {
                g.clip(a, skin);
            }
        }
        for (int a = 0; a < g.animationCount(); a++) {
            g.clip(a, null);
        }
    }

    private void runCase(String what, byte[] data, Gltf.UriResolver resolver, SplittableRandom r, String how, List<String> failures) {
        long t0 = System.nanoTime();
        boolean parsed = false;
        try {
            Gltf g = Gltf.parse(data, resolver);
            parsed = true;
            exercise(g);
            fullyExercised++;
        } catch (GltfException expected) {
            // the documented way for a damaged file to fail
            if (parsed) {
                refusedLater++;
            } else {
                refusedAtParse++;
            }
        } catch (Throwable t) {
            failures.add(what + " (" + how + "): " + t);
            if (failures.size() <= 3) {
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement e : t.getStackTrace()) {
                    if (sb.length() > 0 && sb.length() > 700) {
                        break;
                    }
                    sb.append("\n    at ").append(e);
                }
                failures.add("    stack of the first failures:" + sb);
            }
        }
        long took = System.nanoTime() - t0;
        if (took > SLOW_CASE_NANOS) {
            failures.add(what + " (" + how + ") took " + took / 1_000_000 + " ms");
        }
    }

    // ---------------------------------------------------------------- the tests

    @Test
    void damagedGlbFilesFailOnlyWithGltfException() {
        List<String> failures = new ArrayList<>();
        int cases = Math.max(500, Rnd.N);
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            for (Map.Entry<String, byte[]> e : assets.entrySet()) {
                if (!e.getKey().endsWith(".glb")) {
                    continue;
                }
                SplittableRandom r = new SplittableRandom(SEED ^ e.getKey().hashCode());
                for (int i = 0; i < cases; i++) {
                    StringBuilder how = new StringBuilder("seed " + SEED + ", case " + i + ": ");
                    int[] reg = region(e.getValue(), e.getKey(), r);
                    byte[] damaged = vmath.Fuzz.mutate(e.getValue(), r, how, reg[0], reg[1]);
                    runCase(e.getKey(), damaged, uri -> new byte[0], r, how.toString(), failures);
                }
            }
        });
        vmath.Report.printf("glb fuzz: %d refused at parse, %d refused while exercising the parsed file, %d fully exercised%n", refusedAtParse, refusedLater, fullyExercised);
        org.junit.jupiter.api.Assertions.assertTrue(fullyExercised + refusedLater >= 100,
                "the damage must get past the header often enough to test the decoding: only " + (fullyExercised + refusedLater) + " cases did");
        report(failures);
    }

    @Test
    void damagedExternalFilesFailOnlyWithGltfException() {
        List<String> failures = new ArrayList<>();
        int cases = Math.max(500, Rnd.N);
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            for (Map.Entry<String, byte[]> e : assets.entrySet()) {
                if (!e.getKey().startsWith("gltf/scene/") || !e.getKey().endsWith(".gltf")) {
                    continue;
                }
                String dir = e.getKey().substring(0, e.getKey().lastIndexOf('/') + 1);
                SplittableRandom r = new SplittableRandom(SEED ^ e.getKey().hashCode());
                // damage either the .gltf text or one of the files it refers to
                List<String> members = new ArrayList<>();
                for (String name : assets.keySet()) {
                    if (name.startsWith(dir)) {
                        members.add(name);
                    }
                }
                for (int i = 0; i < cases; i++) {
                    String target = members.get(r.nextInt(members.size()));
                    StringBuilder how = new StringBuilder("seed " + SEED + ", case " + i + ", damaging " + target + ": ");
                    byte[] original = assets.get(target);
                    byte[] damaged = vmath.Fuzz.mutate(original, r, how, 0, original.length);
                    byte[] main = target.equals(e.getKey()) ? damaged : e.getValue();
                    String fileTarget = target;
                    Gltf.UriResolver resolver = uri -> {
                        String key = dir + uri;
                        byte[] content = key.equals(fileTarget) ? damaged : assets.get(key);
                        if (content == null) {
                            throw new java.io.IOException("no such file " + uri);
                        }
                        return content;
                    };
                    runCase(e.getKey(), main, resolver, r, how.toString(), failures);
                }
            }
        });
        report(failures);
    }

    @Test
    void damagedJsonFailsOnlyWithGltfException() {
        List<String> failures = new ArrayList<>();
        String alphabet = "{}[],:\"\\0123456789eE.-+truefalsn \n\t/*u";
        List<String> corpus = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : assets.entrySet()) {
            if (e.getKey().endsWith(".gltf")) {
                corpus.add(new String(e.getValue(), StandardCharsets.UTF_8));
            }
        }
        corpus.add("{\"a\": [1, 2.5, -3e2, true, false, null, \"x\\u00e9\\n\"], \"b\": {}, \"c\": []}");
        int cases = Math.max(2000, 10 * Rnd.N);
        assertTimeoutPreemptively(Duration.ofMinutes(10), () -> {
            SplittableRandom r = new SplittableRandom(SEED + 77);
            for (String text : corpus) {
                for (int i = 0; i < cases; i++) {
                    StringBuilder s = new StringBuilder(text);
                    int edits = 1 + r.nextInt(4);
                    StringBuilder how = new StringBuilder("seed " + (SEED + 77) + ", case " + i + ": ");
                    for (int k = 0; k < edits && s.length() > 0; k++) {
                        int at = r.nextInt(s.length());
                        switch (r.nextInt(4)) {
                            case 0 -> {
                                s.setCharAt(at, alphabet.charAt(r.nextInt(alphabet.length())));
                                how.append("replace ").append(at).append("; ");
                            }
                            case 1 -> {
                                s.insert(at, alphabet.charAt(r.nextInt(alphabet.length())));
                                how.append("insert ").append(at).append("; ");
                            }
                            case 2 -> {
                                s.deleteCharAt(at);
                                how.append("delete ").append(at).append("; ");
                            }
                            default -> {
                                s.setLength(at);
                                how.append("truncate ").append(at).append("; ");
                            }
                        }
                    }
                    long t0 = System.nanoTime();
                    try {
                        Json.parse(s.toString());
                    } catch (GltfException expected) {
                        // fine
                    } catch (Throwable t) {
                        failures.add("Json.parse (" + how + "): " + t);
                    }
                    if (System.nanoTime() - t0 > SLOW_CASE_NANOS) {
                        failures.add("Json.parse (" + how + ") was slow");
                    }
                }
            }
        });
        report(failures);
    }

    @Test
    void deeplyNestedAndHugeJsonIsRefusedNotCrashed() {
        // nesting far beyond the limit must be refused with the documented exception, not a StackOverflowError
        String deepArrays = "[".repeat(100_000) + "]".repeat(100_000);
        String deepObjects = "{\"a\":".repeat(50_000) + "1" + "}".repeat(50_000);
        for (String s : List.of(deepArrays, deepObjects)) {
            try {
                Json.parse(s);
                fail("accepted nesting of 50,000 levels or more");
            } catch (GltfException expected) {
                // refused
            }
        }
        // a big but flat document is fine
        StringBuilder flat = new StringBuilder("[");
        for (int i = 0; i < 200_000; i++) {
            flat.append(i == 0 ? "" : ",").append(i);
        }
        flat.append("]");
        Json.parse(flat.toString());
    }

    private static void report(List<String> failures) {
        if (!failures.isEmpty()) {
            fail(failures.size() + " fuzz cases broke the contract (only GltfException is allowed):\n" + String.join("\n", failures.subList(0, Math.min(failures.size(), 12))));
        }
    }
}
