package vmath.gltf;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URLDecoder;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vmath.annotations.Experimental;
import vmath.anim.AnimationClip;
import vmath.anim.Skeleton;
import vmath.bulk.Strided;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec3f;
import vmath.mesh.Mesh;
import static vmath.gltf.JsonAccess.*;
import static vmath.gltf.AccessorFormat.*;
import static vmath.gltf.ClipResampling.*;

/**
 * A glTF 2.0 loader: reads a {@code .gltf} (JSON plus buffers) or a {@code .glb} (binary container)
 * and gives access to meshes, materials, the node tree, skins and animations in vmath's own types.
 *
 * <p>No dependencies, no pixel decoding.
 *
 * <p><b>Geometry.</b> {@link #readFloats}, {@link #readInts} and {@link #readInto} decode any
 * accessor: byte, short, int and float components, normalized or not, a {@code byteStride} in the
 * buffer view, matrix columns padded to 4 bytes as the specification requires, sparse accessors,
 * and accessors without a buffer view (zeros). {@link #readInto} writes the converted floats
 * straight into a {@link MemorySegment} with a chosen stride and byte order, so one accessor can
 * fill one attribute of an interleaved vertex buffer. {@link #toMesh} builds a {@link Mesh} from a
 * triangle, strip or fan primitive (positions, normals, tangents, up to four UV sets, indices; UVs
 * keep glTF's convention of a top-left origin).
 *
 * <p><b>Skins.</b> {@link #skin} returns a {@link Skeleton} (glTF joints are in any order, a
 * skeleton needs parents first, so the joints are reordered and {@link SkinData#skinToSkeleton}
 * maps a vertex's {@code JOINTS_0} value to the skeleton joint). Bind poses come from the joint
 * nodes' local transforms. <b>Animations.</b> {@link #clip} builds an {@link AnimationClip} for a
 * skeleton: translation, rotation and scale channels of the skin's joints. The clip type
 * interpolates linearly (slerp for rotations) only, so a {@code STEP} curve is converted by
 * doubling each key just before the next one, and a {@code CUBICSPLINE} curve is resampled at a
 * fixed rate (default 30 Hz): both are approximations and say so here. Weights (morph target)
 * channels are ignored; morph targets are not loaded.
 *
 * <p><b>Security.</b> A glTF file is untrusted input: sizes and offsets are checked with
 * overflow-safe arithmetic before any read, JSON nesting is limited, and a path-based load refuses
 * relative buffer URIs that leave the directory of the file. Every failure is a
 * {@link GltfException}.
 *
 * <p><b>Not supported:</b> compressed geometry ({@code KHR_draco_mesh_compression},
 * {@code EXT_meshopt_compression}; a file that requires them is rejected), morph targets, cameras,
 * lights and extensions beyond {@code KHR_mesh_quantization}. Tested with hand-built files, not
 * with the Khronos sample assets.
 *
 * <p><b>Thread safety.</b> Not thread-safe: the parsed data is read-only, but {@link #skin} builds
 * and caches its result on first use, so use one instance per thread, or call every accessor you
 * need once before sharing it (and never modify the arrays it hands out).
 *
 * <p><b>Example:</b> loading a file and building a mesh
 *
 * <pre>{@code
 * Gltf gltf = Gltf.load(Path.of("model.glb"));
 * Mesh mesh = gltf.toMesh(0, 0);                                                         // mesh 0, primitive 0
 * Mat4f[] world = gltf.worldMatrices();                                                  // of every node
 * }</pre>
 *
 * <p><b>Example:</b> a skinned character with an animation
 *
 * <pre>{@code
 * Gltf gltf = Gltf.load(Path.of("character.glb"));
 * Gltf.SkinData skin = gltf.skin(0);
 * AnimationClip clip = gltf.clip(0, skin);
 * Gltf.VertexSkinning weights = gltf.readSkinning(0, 0);
 * }</pre>
 */
@Experimental("the data model records and the animation conversion may change")
public final class Gltf {

    /**
     * Loads the bytes behind a buffer or image URI that is not a data URI.
     */
    public interface UriResolver {
        /**
         * Loads the bytes of an external file that the glTF file refers to by relative URI;
         * implemented by the caller so that files, archives and other sources can be used.
         *
         * @param uri the uri; must not be {@code null}
         * @return the bytes of the file {@code uri} names, a URI relative to the .gltf file as it
         *     appears in the JSON (not a data URI, which the reader decodes itself); throw
         *     {@link IOException} when it cannot be read
         * @throws IOException if the file cannot be read
         */
        byte[] resolve(String uri) throws IOException;
    }

    /**
     * Primitive mode of separate triangles (glTF mode 4).
     */
    public static final int MODE_TRIANGLES = 4;
    /**
     * Primitive mode of a triangle strip (glTF mode 5); {@code toMesh} converts it to separate
     * triangles.
     */
    public static final int MODE_TRIANGLE_STRIP = 5;
    /**
     * Primitive mode of a triangle fan (glTF mode 6); {@code toMesh} converts it to separate
     * triangles.
     */
    public static final int MODE_TRIANGLE_FAN = 6;

    /**
     * A primitive of a mesh: its topology mode, attribute name to accessor, index accessor
     * ({@code -1} for none) and material ({@code -1} for none).
     *
     * @param mode the mode
     * @param attributes the attributes; must not be {@code null}
     * @param indices the indices
     * @param material the material
     */
    public record Primitive(int mode, Map<String, Integer> attributes, int indices, int material) {
    }

    /**
     * A mesh: its name (may be null) and its primitives.
     *
     * @param name the name; must not be {@code null}
     * @param primitives the primitives; must not be {@code null}
     */
    public record MeshData(String name, List<Primitive> primitives) {
    }

    /**
     * The metallic-roughness material; texture fields are texture indices, {@code -1} when absent.
     *
     * @param name the name; must not be {@code null}
     * @param baseColorFactor the base color factor
     * @param baseColorTexture the base color texture
     * @param baseColorTexCoord the base color tex coord
     * @param metallicFactor the metallic factor
     * @param roughnessFactor the roughness factor
     * @param metallicRoughnessTexture the metallic roughness texture
     * @param normalTexture the normal texture
     * @param normalScale the normal scale
     * @param occlusionTexture the occlusion texture
     * @param occlusionStrength the occlusion strength
     * @param emissiveFactor the emissive factor
     * @param emissiveTexture the emissive texture
     * @param alphaMode the alpha mode; must not be {@code null}
     * @param alphaCutoff the alpha cutoff
     * @param doubleSided whether double sided
     */
    public record Material(String name, float[] baseColorFactor, int baseColorTexture, int baseColorTexCoord, float metallicFactor, float roughnessFactor,
                           int metallicRoughnessTexture, int normalTexture, float normalScale, int occlusionTexture, float occlusionStrength,
                           float[] emissiveFactor, int emissiveTexture, String alphaMode, float alphaCutoff, boolean doubleSided) {

        /**
         * Keeps copies of the arrays, so that the record does not share them with the caller.
         */
        public Material {
            baseColorFactor = Copies.of(baseColorFactor);
            emissiveFactor = Copies.of(emissiveFactor);
        }

        /**
         * Exposes a copy of the {@code baseColorFactor} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] baseColorFactor() {
            return Copies.of(baseColorFactor);
        }

        /**
         * Exposes a copy of the {@code emissiveFactor} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] emissiveFactor() {
            return Copies.of(emissiveFactor);
        }

        private Object[] parts() {
            return new Object[] {name, baseColorFactor, baseColorTexture, baseColorTexCoord, metallicFactor, roughnessFactor, metallicRoughnessTexture, normalTexture, normalScale, occlusionTexture, occlusionStrength, emissiveFactor, emissiveTexture, alphaMode, alphaCutoff, doubleSided};
        }

        /**
         * Compares the contents of the arrays, not their identities.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code Material} with equal components
         */
        @Override
        public boolean equals(Object o) {
            return o instanceof Material other && java.util.Arrays.deepEquals(parts(), other.parts());
        }

        /**
         * Hashes the contents of the arrays, not their identities.
         *
         * @return a hash code that agrees with {@link #equals(Object)}
         */
        @Override
        public int hashCode() {
            return java.util.Arrays.deepHashCode(parts());
        }
    }

    /**
     * A texture: the index of its image ({@code -1} when absent) and of its sampler ({@code -1} for
     * the default sampler).
     *
     * @param source the source
     * @param sampler the sampler
     */
    public record Texture(int source, int sampler) {
    }

    /**
     * An image reference: a URI (possibly a data URI) or a buffer view with a MIME type.
     *
     * <p>Use {@link #imageBytes} for the encoded bytes.
     *
     * @param name the name; must not be {@code null}
     * @param uri the uri; must not be {@code null}
     * @param bufferView the buffer view
     * @param mimeType the mime type; must not be {@code null}
     */
    public record Image(String name, String uri, int bufferView, String mimeType) {
    }

    /**
     * A sampler: the glTF (OpenGL) enum values of the magnification and minification filters
     * ({@code -1} when absent) and of the wrap modes (10497, repeat, when absent).
     *
     * @param magFilter the mag filter
     * @param minFilter the min filter
     * @param wrapS the wrap s
     * @param wrapT the wrap t
     */
    public record Sampler(int magFilter, int minFilter, int wrapS, int wrapT) {
    }

    /**
     * A node; {@code matrix} is null unless the file gave one, otherwise translation, rotation (x,
     * y, z, w) and scale are the values or their defaults.
     *
     * @param name the name; must not be {@code null}
     * @param children the children
     * @param mesh the mesh
     * @param skin the skin
     * @param translation the translation
     * @param rotation the rotation
     * @param scale the scale
     * @param matrix the matrix
     */
    public record Node(String name, int[] children, int mesh, int skin, float[] translation, float[] rotation, float[] scale, float[] matrix) {

        /**
         * Keeps copies of the arrays, so that the record does not share them with the caller.
         */
        public Node {
            children = Copies.of(children);
            translation = Copies.of(translation);
            rotation = Copies.of(rotation);
            scale = Copies.of(scale);
            matrix = Copies.of(matrix);
        }

        /**
         * Exposes a copy of the {@code children} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public int[] children() {
            return Copies.of(children);
        }

        /**
         * Exposes a copy of the {@code translation} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] translation() {
            return Copies.of(translation);
        }

        /**
         * Exposes a copy of the {@code rotation} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] rotation() {
            return Copies.of(rotation);
        }

        /**
         * Exposes a copy of the {@code scale} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] scale() {
            return Copies.of(scale);
        }

        /**
         * Exposes a copy of the {@code matrix} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] matrix() {
            return Copies.of(matrix);
        }

        private Object[] parts() {
            return new Object[] {name, children, mesh, skin, translation, rotation, scale, matrix};
        }

        /**
         * Compares the contents of the arrays, not their identities.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code Node} with equal components
         */
        @Override
        public boolean equals(Object o) {
            return o instanceof Node other && java.util.Arrays.deepEquals(parts(), other.parts());
        }

        /**
         * Hashes the contents of the arrays, not their identities.
         *
         * @return a hash code that agrees with {@link #equals(Object)}
         */
        @Override
        public int hashCode() {
            return java.util.Arrays.deepHashCode(parts());
        }
    }

    /**
     * What an accessor says about its data.
     *
     * <p>{@code min} and {@code max} are null when absent.
     *
     * @param count the number of elements
     * @param components the number of components
     * @param componentType the component type
     * @param normalized whether normalized
     * @param type the type; must not be {@code null}
     * @param min the min
     * @param max the max
     */
    public record AccessorInfo(int count, int components, int componentType, boolean normalized, String type, float[] min, float[] max) {

        /**
         * Keeps copies of the arrays, so that the record does not share them with the caller.
         */
        public AccessorInfo {
            min = Copies.of(min);
            max = Copies.of(max);
        }

        /**
         * Exposes a copy of the {@code min} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] min() {
            return Copies.of(min);
        }

        /**
         * Exposes a copy of the {@code max} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] max() {
            return Copies.of(max);
        }

        private Object[] parts() {
            return new Object[] {count, components, componentType, normalized, type, min, max};
        }

        /**
         * Compares the contents of the arrays, not their identities.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code AccessorInfo} with equal components
         */
        @Override
        public boolean equals(Object o) {
            return o instanceof AccessorInfo other && java.util.Arrays.deepEquals(parts(), other.parts());
        }

        /**
         * Hashes the contents of the arrays, not their identities.
         *
         * @return a hash code that agrees with {@link #equals(Object)}
         */
        @Override
        public int hashCode() {
            return java.util.Arrays.deepHashCode(parts());
        }
    }

    /**
     * A skin as vmath types.
     *
     * <p>{@code jointNodes[j]} is the node of skeleton joint {@code j}; {@code skinToSkeleton[k]}
     * is the skeleton joint of entry {@code k} of the glTF skin (the value a {@code JOINTS_0}
     * attribute holds). {@code inverseBindMatrices} are the file's, 16 floats per joint in skeleton
     * order, or null if the skin has none; the skeleton computes its own from the bind pose.
     *
     * <p>{@code rootTransform} is the world matrix of the nodes above the skeleton (an exporter's
     * "Armature" node, often a rotation), the identity when the roots have no parent. The
     * skeleton's bind pose and the animation clips are local to the joints and leave it out, while
     * the file's inverse bind matrices and vertices include it. To skin the vertices of the file:
     * {@code world = Skinning.worldMatrices(skeleton, pose)}, joint matrix
     * {@code world * inverseBindMatrix} (the file's, not the skeleton's), skin the positions with
     * those, and then transform the result by {@code rootTransform}. (The skeleton's own inverse
     * bind matrices omit the armature too, so with those the vertices must first be taken into
     * armature space by the inverse of {@code rootTransform}.)
     *
     * @param name the name; must not be {@code null}
     * @param skeleton the skeleton; must not be {@code null}
     * @param jointNodes the joint nodes
     * @param skinToSkeleton the skin to skeleton
     * @param inverseBindMatrices the inverse bind matrices
     * @param rootTransform the root transform; must not be {@code null}
     */
    public record SkinData(String name, Skeleton skeleton, int[] jointNodes, int[] skinToSkeleton, float[] inverseBindMatrices, Mat4f rootTransform) {

        /**
         * Keeps copies of the arrays, so that the record does not share them with the caller.
         */
        public SkinData {
            jointNodes = Copies.of(jointNodes);
            skinToSkeleton = Copies.of(skinToSkeleton);
            inverseBindMatrices = Copies.of(inverseBindMatrices);
        }

        /**
         * Exposes a copy of the {@code jointNodes} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public int[] jointNodes() {
            return Copies.of(jointNodes);
        }

        /**
         * Exposes a copy of the {@code skinToSkeleton} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public int[] skinToSkeleton() {
            return Copies.of(skinToSkeleton);
        }

        /**
         * Exposes a copy of the {@code inverseBindMatrices} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] inverseBindMatrices() {
            return Copies.of(inverseBindMatrices);
        }

        private Object[] parts() {
            return new Object[] {name, skeleton, jointNodes, skinToSkeleton, inverseBindMatrices, rootTransform};
        }

        /**
         * Compares the contents of the arrays, not their identities.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code SkinData} with equal components
         */
        @Override
        public boolean equals(Object o) {
            return o instanceof SkinData other && java.util.Arrays.deepEquals(parts(), other.parts());
        }

        /**
         * Hashes the contents of the arrays, not their identities.
         *
         * @return a hash code that agrees with {@link #equals(Object)}
         */
        @Override
        public int hashCode() {
            return java.util.Arrays.deepHashCode(parts());
        }
    }

    /**
     * Four joint indices and four weights per vertex.
     *
     * @param joints the joints
     * @param weights the weights
     */
    public record VertexSkinning(int[] joints, float[] weights) {

        /**
         * Keeps copies of the arrays, so that the record does not share them with the caller.
         */
        public VertexSkinning {
            joints = Copies.of(joints);
            weights = Copies.of(weights);
        }

        /**
         * Exposes a copy of the {@code joints} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public int[] joints() {
            return Copies.of(joints);
        }

        /**
         * Exposes a copy of the {@code weights} array, so that the record cannot be changed through it.
         *
         * @return a copy of the array, or {@code null} if there is none
         */
        @Override
        public float[] weights() {
            return Copies.of(weights);
        }

        private Object[] parts() {
            return new Object[] {joints, weights};
        }

        /**
         * Compares the contents of the arrays, not their identities.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code VertexSkinning} with equal components
         */
        @Override
        public boolean equals(Object o) {
            return o instanceof VertexSkinning other && java.util.Arrays.deepEquals(parts(), other.parts());
        }

        /**
         * Hashes the contents of the arrays, not their identities.
         *
         * @return a hash code that agrees with {@link #equals(Object)}
         */
        @Override
        public int hashCode() {
            return java.util.Arrays.deepHashCode(parts());
        }
    }

    private static final Set<String> SUPPORTED_REQUIRED = Set.of("KHR_mesh_quantization");
    private static final int GLB_MAGIC = 0x46546C67, CHUNK_JSON = 0x4E4F534A, CHUNK_BIN = 0x004E4942;

    private final Map<String, Object> root;
    private final List<byte[]> buffers = new ArrayList<>();
    private final List<Integer> bufferStarts = new ArrayList<>(), bufferLengths = new ArrayList<>(); // where each buffer starts in its array, and how long it is
    private final long[][] views;          // buffer, position in the array of the buffer (its start plus the byteOffset), length, stride
    private final List<Map<String, Object>> accessors;
    private final List<MeshData> meshes = new ArrayList<>();
    private final List<Material> materials = new ArrayList<>();
    private final List<Texture> textures = new ArrayList<>();
    private final List<Image> images = new ArrayList<>();
    private final List<Sampler> samplers = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    private final List<int[]> scenes = new ArrayList<>();
    private final int defaultScene;
    private final UriResolver resolver;
    private final Map<Integer, SkinData> skinCache = new HashMap<>();

    // ---------------------------------------------------------------- loading

    /**
     * The size above which {@link #load(Path)} refuses a file (the main file and each buffer file
     * separately): 1 GiB.
     */
    public static final long DEFAULT_MAX_FILE_BYTES = 1L << 30;

    /**
     * Loads a {@code .gltf} or {@code .glb} file; relative buffer URIs are resolved next to it and
     * may not leave its directory.
     *
     * <p>Every file is read completely into memory, so a file larger than
     * {@link #DEFAULT_MAX_FILE_BYTES} is refused with a {@link GltfException} (use
     * {@link #load(Path, long)} to choose the limit).
     *
     * @param file the file; must not be {@code null}
     * @return the loaded document, never {@code null}
     * @throws IOException if the file or a buffer cannot be read, exceeds the default size limit or
     *     a buffer URI leaves the directory of the file
     * @throws GltfException if the content is not valid glTF
     */
    public static Gltf load(Path file) throws IOException {
        return load(file, DEFAULT_MAX_FILE_BYTES);
    }

    /**
     * Loads a glTF file like {@link #load(Path)}, but with an explicit limit on the size of the
     * main file and of every buffer file, which guards against hostile or corrupt input.
     *
     * @param file the file; must not be {@code null}
     * @param maxFileBytes the max file bytes
     * @return as {@link #load(Path)} with an explicit size limit, in bytes, for the main file and
     *     for every buffer file
     * @throws IOException if a file cannot be read, exceeds {@code maxBytes} or a buffer URI leaves
     *     the directory of the glTF file
     * @throws GltfException if the content is not valid glTF
     */
    public static Gltf load(Path file, long maxFileBytes) throws IOException {
        byte[] data = readLimited(file, maxFileBytes);
        Path base = file.toAbsolutePath().normalize().getParent();
        Path realBase = base.toRealPath();
        return parse(data, uri -> {
            String decoded = URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8);
            Path p = base.resolve(decoded).normalize();
            if (!p.startsWith(base)) {
                throw new IOException("the URI leaves the directory of the glTF file: " + uri);
            }
            // the check above is on the text of the path; a link inside the directory can still lead out of it, so the resolved file is checked too
            Path real = p.toRealPath();
            if (!real.startsWith(realBase)) {
                throw new IOException("the URI leaves the directory of the glTF file through a link: " + uri);
            }
            return readLimited(real, maxFileBytes);
        });
    }

    private static byte[] readLimited(Path file, long maxFileBytes) throws IOException {
        long limit = Math.min(maxFileBytes, Integer.MAX_VALUE - 8L);
        // one read with a limit instead of asking for the size first: a file that grows in between cannot get past the limit
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] data = in.readNBytes((int) limit + 1);
            if (data.length > limit) {
                throw new GltfException(file.getFileName() + " is more than the limit of " + limit + " bytes");
            }
            return data;
        }
    }

    /**
     * Parses glTF from memory: a GLB container (recognised by its magic) or UTF-8 JSON.
     *
     * <p>{@code resolver} loads non-data URIs; it may be null when there are none.
     *
     * @param data the data (at least 3 elements)
     * @param resolver the resolver; must not be {@code null}
     * @return the parsed document, never {@code null}
     * @throws GltfException if the data is not a valid GLB container or JSON object: unsupported
     *     version, a length that does not match, a chunk past the end or no JSON chunk
     */
    public static Gltf parse(byte[] data, UriResolver resolver) {
        int binStart = 0, binLength = -1;
        String json;
        if (data.length >= 12 && le32(data, 0) == GLB_MAGIC) {
            if (le32(data, 4) != 2) {
                throw new GltfException("unsupported GLB version " + le32(data, 4));
            }
            long total = le32(data, 8) & 0xFFFFFFFFL;
            if (total > data.length || total < 12) {
                throw new GltfException("GLB length " + total + " does not match the data (" + data.length + " bytes)");
            }
            String jsonText = null;
            long pos = 12;
            while (pos + 8 <= total) {
                long len = le32(data, (int) pos) & 0xFFFFFFFFL;
                int type = le32(data, (int) pos + 4);
                pos += 8;
                if (len > total - pos) {
                    throw new GltfException("GLB chunk runs past the end of the file");
                }
                if (type == CHUNK_JSON && jsonText == null) {
                    jsonText = new String(data, (int) pos, (int) len, StandardCharsets.UTF_8);
                } else if (type == CHUNK_BIN && binLength < 0) {
                    // not copied: the buffer reads from this offset of the file bytes
                    binStart = (int) pos;
                    binLength = (int) len;
                }
                pos += len;
            }
            if (jsonText == null) {
                throw new GltfException("GLB has no JSON chunk");
            }
            json = jsonText;
        } else {
            int off = data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF ? 3 : 0;
            json = new String(data, off, data.length - off, StandardCharsets.UTF_8);
        }
        Object parsed = Json.parse(json);
        if (!(parsed instanceof JsonObject root)) {
            throw new GltfException("the top level of a glTF file must be an object");
        }
        return new Gltf(root, binLength < 0 ? null : data, binStart, binLength, resolver);
    }

    private Gltf(Map<String, Object> root, byte[] glbBin, int glbStart, int glbLength, UriResolver resolver) {
        this.root = root;
        this.resolver = resolver;
        Map<String, Object> asset = obj(root.get("asset"), "asset");
        String version = str(asset, "version", null);
        if (version == null || !version.startsWith("2")) {
            throw new GltfException("unsupported glTF version: " + version);
        }
        for (Object ext : list(root.get("extensionsRequired"))) {
            if (!(ext instanceof String s) || !SUPPORTED_REQUIRED.contains(s)) {
                throw new GltfException("the file requires an unsupported extension: " + ext);
            }
        }
        // buffers
        List<Object> bufferList = list(root.get("buffers"));
        for (int i = 0; i < bufferList.size(); i++) {
            Map<String, Object> b = obj(bufferList.get(i), "buffers[" + i + "]");
            long length = lng(b, "byteLength", -1);
            if (length < 1) {
                throw new GltfException("buffers[" + i + "] needs a positive byteLength");
            }
            String uri = str(b, "uri", null);
            byte[] bytes;
            int start = 0, available;
            if (uri == null) {
                if (i != 0 || glbBin == null) {
                    throw new GltfException("buffers[" + i + "] has no uri and is not the GLB binary chunk");
                }
                // the binary chunk is read in place, from its offset in the bytes of the file
                bytes = glbBin;
                start = glbStart;
                available = glbLength;
            } else {
                bytes = loadUri(uri, "buffers[" + i + "]");
                available = bytes.length;
            }
            if (available < length) {
                throw new GltfException("buffers[" + i + "] is shorter than its byteLength: " + available + " < " + length);
            }
            buffers.add(bytes);
            bufferStarts.add(start);
            bufferLengths.add(available);
        }
        // buffer views
        List<Object> viewList = list(root.get("bufferViews"));
        views = new long[viewList.size()][];
        for (int i = 0; i < views.length; i++) {
            Map<String, Object> v = obj(viewList.get(i), "bufferViews[" + i + "]");
            int buffer = (int) lng(v, "buffer", -1);
            long offset = lng(v, "byteOffset", 0), length = lng(v, "byteLength", -1), stride = lng(v, "byteStride", 0);
            if (buffer < 0 || buffer >= buffers.size() || offset < 0 || length < 1 || offset > bufferLengths.get(buffer)
                    || length > bufferLengths.get(buffer) - offset) {
                throw new GltfException("bufferViews[" + i + "] lies outside its buffer");
            }
            if (stride != 0 && (stride < 4 || stride > 252 || stride % 4 != 0)) {
                throw new GltfException("bufferViews[" + i + "] has an invalid byteStride " + stride);
            }
            views[i] = new long[] {buffer, bufferStarts.get(buffer) + offset, length, stride}; // the offset is an index into the array of the buffer
        }
        // accessors are kept as JSON and validated when read
        accessors = new ArrayList<>();
        List<Object> accList = list(root.get("accessors"));
        for (int i = 0; i < accList.size(); i++) {
            accessors.add(obj(accList.get(i), "accessors[" + i + "]"));
        }
        // meshes
        List<Object> meshList = list(root.get("meshes"));
        for (int i = 0; i < meshList.size(); i++) {
            Map<String, Object> m = obj(meshList.get(i), "meshes[" + i + "]");
            List<Primitive> prims = new ArrayList<>();
            List<Object> pl = list(m.get("primitives"));
            for (int p = 0; p < pl.size(); p++) {
                Map<String, Object> pm = obj(pl.get(p), "meshes[" + i + "].primitives[" + p + "]");
                Map<String, Integer> attrs = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : obj(pm.get("attributes"), "attributes").entrySet()) {
                    attrs.put(e.getKey(), accessorRef(e.getValue(), "attribute " + e.getKey()));
                }
                int mode = (int) lng(pm, "mode", MODE_TRIANGLES);
                int indices = pm.containsKey("indices") ? accessorRef(pm.get("indices"), "indices") : -1;
                prims.add(new Primitive(mode, attrs, indices, (int) lng(pm, "material", -1)));
            }
            meshes.add(new MeshData(str(m, "name", null), prims));
        }
        // textures, images, samplers, materials
        for (Object o : list(root.get("samplers"))) {
            Map<String, Object> s = obj(o, "sampler");
            samplers.add(new Sampler((int) lng(s, "magFilter", -1), (int) lng(s, "minFilter", -1), (int) lng(s, "wrapS", 10497), (int) lng(s, "wrapT", 10497)));
        }
        for (Object o : list(root.get("images"))) {
            Map<String, Object> im = obj(o, "image");
            images.add(new Image(str(im, "name", null), str(im, "uri", null), (int) lng(im, "bufferView", -1), str(im, "mimeType", null)));
        }
        for (Object o : list(root.get("textures"))) {
            Map<String, Object> t = obj(o, "texture");
            textures.add(new Texture((int) lng(t, "source", -1), (int) lng(t, "sampler", -1)));
        }
        for (Object o : list(root.get("materials"))) {
            materials.add(parseMaterial(obj(o, "material")));
        }
        // nodes and scenes
        List<Object> nodeList = list(root.get("nodes"));
        for (int i = 0; i < nodeList.size(); i++) {
            Map<String, Object> n = obj(nodeList.get(i), "nodes[" + i + "]");
            List<Object> ch = list(n.get("children"));
            int[] children = new int[ch.size()];
            for (int c = 0; c < children.length; c++) {
                children[c] = (int) num(ch.get(c), "child");
                if (children[c] < 0 || children[c] >= nodeList.size()) {
                    throw new GltfException("nodes[" + i + "] has a child index out of range: " + children[c]);
                }
            }
            nodes.add(new Node(str(n, "name", null), children, (int) lng(n, "mesh", -1), (int) lng(n, "skin", -1),
                    floats(n.get("translation"), 3, new float[] {0f, 0f, 0f}, "translation"), floats(n.get("rotation"), 4, new float[] {0f, 0f, 0f, 1f}, "rotation"),
                    floats(n.get("scale"), 3, new float[] {1f, 1f, 1f}, "scale"), floats(n.get("matrix"), 16, null, "matrix")));
        }
        checkNodeForest();
        for (Object o : list(root.get("scenes"))) {
            List<Object> ns = list(obj(o, "scene").get("nodes"));
            int[] roots = new int[ns.size()];
            for (int k = 0; k < roots.length; k++) {
                roots[k] = (int) num(ns.get(k), "scene node");
                if (roots[k] < 0 || roots[k] >= nodes.size()) {
                    throw new GltfException("a scene refers to node " + roots[k] + " which does not exist");
                }
            }
            scenes.add(roots);
        }
        this.defaultScene = root.containsKey("scene") ? (int) num(root.get("scene"), "scene") : (scenes.isEmpty() ? -1 : 0);
        if (defaultScene >= scenes.size()) {
            throw new GltfException("the default scene " + defaultScene + " does not exist");
        }
    }

    private Material parseMaterial(Map<String, Object> m) {
        Map<String, Object> pbr = m.get("pbrMetallicRoughness") == null ? new HashMap<>() : obj(m.get("pbrMetallicRoughness"), "pbrMetallicRoughness");
        Map<String, Object> base = pbr.get("baseColorTexture") == null ? null : obj(pbr.get("baseColorTexture"), "baseColorTexture");
        Map<String, Object> mr = pbr.get("metallicRoughnessTexture") == null ? null : obj(pbr.get("metallicRoughnessTexture"), "metallicRoughnessTexture");
        Map<String, Object> normal = m.get("normalTexture") == null ? null : obj(m.get("normalTexture"), "normalTexture");
        Map<String, Object> occ = m.get("occlusionTexture") == null ? null : obj(m.get("occlusionTexture"), "occlusionTexture");
        Map<String, Object> emi = m.get("emissiveTexture") == null ? null : obj(m.get("emissiveTexture"), "emissiveTexture");
        return new Material(str(m, "name", null), floats(pbr.get("baseColorFactor"), 4, new float[] {1f, 1f, 1f, 1f}, "baseColorFactor"),
                base == null ? -1 : (int) lng(base, "index", -1), base == null ? 0 : (int) lng(base, "texCoord", 0), (float) dbl(pbr, "metallicFactor", 1.0),
                (float) dbl(pbr, "roughnessFactor", 1.0), mr == null ? -1 : (int) lng(mr, "index", -1), normal == null ? -1 : (int) lng(normal, "index", -1),
                normal == null ? 1f : (float) dbl(normal, "scale", 1.0), occ == null ? -1 : (int) lng(occ, "index", -1),
                occ == null ? 1f : (float) dbl(occ, "strength", 1.0), floats(m.get("emissiveFactor"), 3, new float[] {0f, 0f, 0f}, "emissiveFactor"),
                emi == null ? -1 : (int) lng(emi, "index", -1), str(m, "alphaMode", "OPAQUE"), (float) dbl(m, "alphaCutoff", 0.5),
                Boolean.TRUE.equals(m.get("doubleSided")));
    }

    private void checkNodeForest() {
        int n = nodes.size();
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        for (int i = 0; i < n; i++) {
            for (int c : nodes.get(i).children()) {
                if (parent[c] >= 0) {
                    throw new GltfException("node " + c + " has more than one parent");
                }
                parent[c] = i;
            }
        }
        // a cycle shows up as a walk up the parent pointers that meets itself; states make every node be walked once (linear time)
        byte[] state = new byte[n]; // 0 unseen, 1 on the current walk, 2 known to reach a root
        for (int i = 0; i < n; i++) {
            int cur = i;
            while (cur >= 0 && state[cur] == 0) {
                state[cur] = 1;
                cur = parent[cur];
            }
            if (cur >= 0 && state[cur] == 1) {
                throw new GltfException("the node hierarchy has a cycle");
            }
            for (int w = i; w >= 0 && state[w] == 1; w = parent[w]) {
                state[w] = 2;
            }
        }
    }

    private byte[] loadUri(String uri, String what) {
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) {
                throw new GltfException(what + ": only base64 data URIs are supported");
            }
            try {
                return Base64.getDecoder().decode(uri.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                throw new GltfException(what + ": bad base64 data", e);
            }
        }
        if (resolver == null) {
            throw new GltfException(what + ": refers to the external file '" + uri + "' but no resolver was given");
        }
        try {
            return resolver.resolve(uri);
        } catch (IOException e) {
            throw new GltfException(what + ": cannot read '" + uri + "': " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- reference checks

    /**
     * The accessor index an integer in the JSON names; {@link GltfException} when there is no such
     * accessor.
     */
    int accessorRef(Object o, String what) {
        int a = (int) num(o, what);
        if (a < 0 || a >= accessors.size()) {
            throw new GltfException(what + " refers to accessor " + a + " which does not exist");
        }
        return a;
    }

    /**
     * The parsed JSON root, for the package-private builders.
     */
    Map<String, Object> root() {
        return root;
    }

    // ---------------------------------------------------------------- simple access

    /**
     * Counts the meshes in the file.
     *
     * @return the number of meshes
     */
    public int meshCount() {
        return meshes.size();
    }

    /**
     * Looks up a mesh by index; an invalid index throws, as for every indexed accessor.
     *
     * @param i the index
     * @return mesh {@code i}; {@link IndexOutOfBoundsException} for a bad index (the same for every
     *     indexed accessor below)
     */
    public MeshData mesh(int i) {
        return meshes.get(i);
    }

    /**
     * Counts the materials in the file.
     *
     * @return the number of materials
     */
    public int materialCount() {
        return materials.size();
    }

    /**
     * Looks up a material by index.
     *
     * @param i the index
     * @return material {@code i}
     */
    public Material material(int i) {
        return materials.get(i);
    }

    /**
     * Counts the textures in the file.
     *
     * @return the number of textures
     */
    public int textureCount() {
        return textures.size();
    }

    /**
     * Looks up a texture by index.
     *
     * @param i the index
     * @return texture {@code i}
     */
    public Texture texture(int i) {
        return textures.get(i);
    }

    /**
     * Counts the images in the file.
     *
     * @return the number of images
     */
    public int imageCount() {
        return images.size();
    }

    /**
     * Looks up an image by index.
     *
     * @param i the index
     * @return image {@code i}
     */
    public Image image(int i) {
        return images.get(i);
    }

    /**
     * Counts the samplers in the file.
     *
     * @return the number of samplers
     */
    public int samplerCount() {
        return samplers.size();
    }

    /**
     * Looks up a sampler by index.
     *
     * @param i the index
     * @return sampler {@code i}
     */
    public Sampler sampler(int i) {
        return samplers.get(i);
    }

    /**
     * Counts the nodes in the file.
     *
     * @return the number of nodes
     */
    public int nodeCount() {
        return nodes.size();
    }

    /**
     * Looks up a node by index.
     *
     * @param i the index
     * @return node {@code i}
     */
    public Node node(int i) {
        return nodes.get(i);
    }

    /**
     * Counts the scenes in the file.
     *
     * @return the number of scenes
     */
    public int sceneCount() {
        return scenes.size();
    }

    /**
     * Lists the root nodes of a scene, as a copy.
     *
     * @param scene the scene
     * @return root nodes of a scene
     */
    public int[] sceneNodes(int scene) {
        return scenes.get(scene).clone();
    }

    /**
     * Reports which scene the file marks as the default, if any.
     *
     * @return the default scene, or {@code -1} when the file has none
     */
    public int defaultScene() {
        return defaultScene;
    }

    /**
     * Counts the accessors in the file.
     *
     * @return the number of accessors
     */
    public int accessorCount() {
        return accessors.size();
    }

    /**
     * Reads the still-encoded bytes of an image, from the file or from a buffer view, without
     * decoding; the caller needs an image decoder.
     *
     * <p>Nothing is decoded.
     *
     * @param image the image
     * @return the encoded bytes of an image (PNG, JPEG, KTX2, ...): from its URI or its buffer view
     * @throws GltfException if the image has neither a URI nor a valid buffer view
     */
    public byte[] imageBytes(int image) {
        Image im = images.get(image);
        if (im.uri() != null) {
            return loadUri(im.uri(), "images[" + image + "]");
        }
        if (im.bufferView() >= 0 && im.bufferView() < views.length) {
            long[] v = views[im.bufferView()];
            return Arrays.copyOfRange(buffers.get((int) v[0]), (int) v[1], (int) (v[1] + v[2]));
        }
        throw new GltfException("images[" + image + "] has neither a uri nor a valid bufferView");
    }

    // ---------------------------------------------------------------- accessors

    /**
     * Describes an accessor from its declaration in the JSON, without reading any buffer data.
     *
     * @param accessor the accessor
     * @return what accessor {@code accessor} declares about its data; nothing is read from the
     *     buffers
     * @throws GltfException if the accessor has no type
     */
    public AccessorInfo accessorInfo(int accessor) {
        Map<String, Object> a = accessors.get(accessor);
        String type = str(a, "type", null);
        if (type == null) {
            throw new GltfException("accessors[" + accessor + "] has no type");
        }
        return new AccessorInfo((int) lng(a, "count", -1), typeComponents(type), (int) lng(a, "componentType", -1), Boolean.TRUE.equals(a.get("normalized")), type,
                a.get("min") == null ? null : floats(a.get("min"), typeComponents(type), null, "min"),
                a.get("max") == null ? null : floats(a.get("max"), typeComponents(type), null, "max"));
    }

    private interface Decoder {
        void put(int index, int component, byte[] buf, int pos);
    }

    /**
     * Elements of an accessor without a buffer view are zeros that cost memory, so their size is
     * capped: at most this many floats.
     */
    private static final long MAX_ZERO_FLOATS = 1L << 24;

    /**
     * Validates an accessor and calls the decoder for every component of every element, including
     * the sparse overrides.
     *
     * <p>With a null decoder it only validates, so that a caller can size its arrays from a count
     * that is known to be safe.
     */
    private void decode(int accessor, Decoder decoder) {
        Map<String, Object> a = accessors.get(accessor);
        String where = "accessors[" + accessor + "]";
        String type = str(a, "type", null);
        if (type == null) {
            throw new GltfException(where + " has no type");
        }
        int componentType = (int) lng(a, "componentType", -1);
        int compSize = componentSize(componentType);
        int comps = typeComponents(type);
        long count = lng(a, "count", -1);
        if (count < 1 || count > Integer.MAX_VALUE / 4) {
            throw new GltfException(where + " has an invalid count " + count);
        }
        boolean normalized = Boolean.TRUE.equals(a.get("normalized"));
        if (normalized && (componentType == 5125 || componentType == 5126)) {
            throw new GltfException(where + ": normalized is not allowed for this componentType");
        }
        int elem = elementBytes(type, compSize);
        if (a.get("bufferView") != null) {
            int bv = (int) lng(a, "bufferView", -1);
            if (bv < 0 || bv >= views.length) {
                throw new GltfException(where + " refers to buffer view " + bv + " which does not exist");
            }
            long[] v = views[bv];
            long stride = v[3] == 0 ? elem : v[3];
            if (stride < elem) {
                throw new GltfException(where + ": the byteStride " + stride + " is smaller than an element (" + elem + ")");
            }
            long offset = lng(a, "byteOffset", 0);
            if (offset < 0 || offset + stride * (count - 1) + elem > v[2]) {
                throw new GltfException(where + " reads past the end of its buffer view");
            }
            byte[] buf = buffers.get((int) v[0]);
            for (int i = 0; decoder != null && i < count; i++) {
                int base = (int) (v[1] + offset + stride * i);
                for (int c = 0; c < comps; c++) {
                    decoder.put(i, c, buf, base + componentOffset(type, compSize, c));
                }
            }
        } else { // no buffer view: all zeros
            if (count * comps > MAX_ZERO_FLOATS) {
                throw new GltfException(where + " has no buffer view and is too large (" + count * comps + " values)");
            }
            byte[] zero = new byte[8];
            for (int i = 0; decoder != null && i < count; i++) {
                for (int c = 0; c < comps; c++) {
                    decoder.put(i, c, zero, 0);
                }
            }
        }
        if (a.get("sparse") != null) {
            Map<String, Object> sp = obj(a.get("sparse"), where + ".sparse");
            long n = lng(sp, "count", -1);
            if (n < 1 || n > count) {
                throw new GltfException(where + ": invalid sparse count " + n);
            }
            Map<String, Object> ind = obj(sp.get("indices"), where + ".sparse.indices");
            Map<String, Object> val = obj(sp.get("values"), where + ".sparse.values");
            int indexType = (int) lng(ind, "componentType", -1);
            if (indexType != 5121 && indexType != 5123 && indexType != 5125) {
                throw new GltfException(where + ": sparse index type must be unsigned byte, short or int");
            }
            int indexSize = componentSize(indexType);
            long[] iv = viewOf(ind, where + ".sparse.indices");
            long io = lng(ind, "byteOffset", 0);
            if (io < 0 || io + n * indexSize > iv[2]) {
                throw new GltfException(where + ": sparse indices read past their buffer view");
            }
            long[] vv = viewOf(val, where + ".sparse.values");
            long vo = lng(val, "byteOffset", 0);
            if (vo < 0 || vo + n * elem > vv[2]) {
                throw new GltfException(where + ": sparse values read past their buffer view");
            }
            byte[] ib = buffers.get((int) iv[0]), vb = buffers.get((int) vv[0]);
            long previous = -1;
            for (int k = 0; k < n; k++) {
                int ip = (int) (iv[1] + io + (long) k * indexSize);
                long target = indexSize == 1 ? ib[ip] & 0xFF : indexSize == 2 ? (ib[ip] & 0xFF) | (ib[ip + 1] & 0xFF) << 8 : le32(ib, ip) & 0xFFFFFFFFL;
                if (target <= previous || target >= count) {
                    throw new GltfException(where + ": sparse indices must be strictly increasing and below the count");
                }
                previous = target;
                int base = (int) (vv[1] + vo + (long) k * elem);
                for (int c = 0; decoder != null && c < comps; c++) {
                    decoder.put((int) target, c, vb, base + componentOffset(type, compSize, c));
                }
            }
        }
    }

    private long[] viewOf(Map<String, Object> m, String where) {
        int bv = (int) lng(m, "bufferView", -1);
        if (bv < 0 || bv >= views.length) {
            throw new GltfException(where + " refers to buffer view " + bv + " which does not exist");
        }
        return views[bv];
    }

    /**
     * Reads an accessor into a float array, converting normalised integers to floats; the array is
     * a copy, so repeated reads allocate.
     *
     * @param accessor the accessor
     * @return all components of an accessor as floats ({@code count * components} of them),
     *     normalized integers mapped to [0, 1] or [-1, 1]
     */
    public float[] readFloats(int accessor) {
        decode(accessor, null); // validates first: the count is only trusted after this
        AccessorInfo info = accessorInfo(accessor);
        float[] out = new float[info.count() * info.components()];
        readFloats(accessor, out, 0);
        return out;
    }

    /**
     * As {@link #readFloats(int)} into {@code dst} starting at {@code dstOffset}.
     *
     * @param accessor the accessor
     * @param dst receives the result
     * @param dstOffset the index of the first element written to the destination
     * @throws GltfException if {@code dst} is too small for the accessor
     */
    public void readFloats(int accessor, float[] dst, int dstOffset) {
        AccessorInfo info = accessorInfo(accessor);
        int comps = info.components(), ct = info.componentType();
        boolean normalized = info.normalized();
        if (dstOffset < 0 || (long) dstOffset + (long) info.count() * comps > dst.length) {
            throw new GltfException("the destination is too small for accessor " + accessor);
        }
        decode(accessor, (i, c, buf, pos) -> dst[dstOffset + i * comps + c] = convert(buf, pos, ct, normalized));
    }

    /**
     * Reads an integer accessor into an int array; floating-point accessors are rejected.
     *
     * @param accessor the accessor
     * @return all components of an integer accessor (unsigned or signed byte, short, int) as ints;
     *     a float accessor is an error
     * @throws GltfException if the accessor holds floats, not integers
     */
    public int[] readInts(int accessor) {
        decode(accessor, null);
        AccessorInfo info = accessorInfo(accessor);
        if (info.componentType() == 5126) {
            throw new GltfException("accessor " + accessor + " holds floats, not integers");
        }
        int comps = info.components(), ct = info.componentType();
        int[] out = new int[info.count() * comps];
        decode(accessor, (i, c, buf, pos) -> {
            float f = convert(buf, pos, ct, false);
            out[i * comps + c] = ct == 5125 ? (int) (long) f : (int) f;
        });
        return out;
    }

    /**
     * Decodes an accessor to floats and writes them into {@code dst} as {@code count} elements of
     * {@code components} floats, element {@code i} at byte {@code offset + i * strideBytes}, in the
     * given byte order.
     *
     * <p>The bytes between elements are left alone, so this fills one attribute of an interleaved
     * buffer.
     *
     * @param accessor the accessor
     * @param dst receives the result; must not be {@code null}
     * @param offset the index of the first element to read or write
     * @param strideBytes the stride bytes
     * @param order the order; must not be {@code null}
     * @return the number of elements written
     */
    public int readInto(int accessor, MemorySegment dst, long offset, long strideBytes, ByteOrder order) {
        AccessorInfo info = accessorInfo(accessor);
        float[] tmp = readFloats(accessor);
        Strided.write(tmp, 0, info.components(), info.count(), dst, offset, strideBytes, order);
        return info.count();
    }

    // ---------------------------------------------------------------- meshes

    /**
     * Builds a {@link Mesh} from a triangle, strip or fan primitive of mesh {@code meshIndex}.
     *
     * @param meshIndex the mesh index
     * @param primitiveIndex the primitive index
     * @return the mesh, never {@code null}
     * @throws GltfException if the primitive is not a triangle primitive, has no {@code POSITION}
     *     of type {@code VEC3}, has invalid indices or an index outside the vertices
     */
    public Mesh toMesh(int meshIndex, int primitiveIndex) {
        Primitive p = meshes.get(meshIndex).primitives().get(primitiveIndex);
        if (p.mode() != MODE_TRIANGLES && p.mode() != MODE_TRIANGLE_STRIP && p.mode() != MODE_TRIANGLE_FAN) {
            throw new GltfException("primitive mode " + p.mode() + " is not a triangle mode");
        }
        Integer posAcc = p.attributes().get("POSITION");
        if (posAcc == null) {
            throw new GltfException("the primitive has no POSITION attribute");
        }
        AccessorInfo pi = accessorInfo(posAcc);
        if (pi.components() != 3) {
            throw new GltfException("POSITION must be a VEC3");
        }
        int nv = pi.count();
        float[] pos = readFloats(posAcc);
        Mesh mesh = new Mesh(nv, Math.max(1, nv / 3));
        Integer nrmAcc = p.attributes().get("NORMAL"), tanAcc = p.attributes().get("TANGENT");
        float[] nrm = null, tan = null;
        if (nrmAcc != null) {
            expect(accessorInfo(nrmAcc), 3, nv, "NORMAL");
            nrm = readFloats(nrmAcc);
            mesh.enableNormals();
        }
        if (tanAcc != null) {
            expect(accessorInfo(tanAcc), 4, nv, "TANGENT");
            tan = readFloats(tanAcc);
            mesh.enableTangents();
        }
        float[][] uv = new float[Mesh.MAX_UV_SETS][];
        for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
            Integer a = p.attributes().get("TEXCOORD_" + s);
            if (a != null) {
                expect(accessorInfo(a), 2, nv, "TEXCOORD_" + s);
                uv[s] = readFloats(a);
                mesh.enableUvs(s);
            }
        }
        for (int v = 0; v < nv; v++) {
            mesh.addVertex(pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]);
            if (nrm != null) {
                mesh.setNormal(v, nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2]);
            }
            if (tan != null) {
                mesh.setTangent(v, tan[v * 4], tan[v * 4 + 1], tan[v * 4 + 2], tan[v * 4 + 3]);
            }
            for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
                if (uv[s] != null) {
                    mesh.setUv(s, v, uv[s][v * 2], uv[s][v * 2 + 1]);
                }
            }
        }
        int[] idx;
        if (p.indices() >= 0) {
            AccessorInfo ii = accessorInfo(p.indices());
            if (ii.components() != 1 || ii.componentType() == 5126 || ii.componentType() == 5120 || ii.componentType() == 5122) {
                throw new GltfException("indices must be an unsigned scalar accessor");
            }
            idx = readInts(p.indices());
        } else {
            idx = new int[nv];
            for (int i = 0; i < nv; i++) {
                idx[i] = i;
            }
        }
        for (int i : idx) {
            if (i < 0 || i >= nv) {
                throw new GltfException("an index (" + i + ") is outside the " + nv + " vertices");
            }
        }
        switch (p.mode()) {
            case MODE_TRIANGLES -> {
                if (idx.length % 3 != 0) {
                    throw new GltfException("a triangle list needs a multiple of 3 indices: " + idx.length);
                }
                for (int t = 0; t < idx.length; t += 3) {
                    mesh.addTriangle(idx[t], idx[t + 1], idx[t + 2]);
                }
            }
            case MODE_TRIANGLE_STRIP -> {
                for (int t = 0; t + 2 < idx.length; t++) {
                    if ((t & 1) == 0) {
                        mesh.addTriangle(idx[t], idx[t + 1], idx[t + 2]);
                    } else {
                        mesh.addTriangle(idx[t + 1], idx[t], idx[t + 2]);
                    }
                }
            }
            default -> {
                for (int t = 1; t + 1 < idx.length; t++) {
                    mesh.addTriangle(idx[0], idx[t], idx[t + 1]);
                }
            }
        }
        return mesh;
    }

    private static void expect(AccessorInfo info, int components, int count, String what) {
        if (info.components() != components || info.count() != count) {
            throw new GltfException(what + " must have " + count + " elements of " + components + " components, has " + info.count() + " of " + info.components());
        }
    }

    /**
     * Reads the joint indices and weights that bind a primitive's vertices to the skeleton; a
     * primitive without skinning data is rejected.
     *
     * @param meshIndex the mesh index
     * @param primitiveIndex the primitive index
     * @return {@code JOINTS_0} and {@code WEIGHTS_0} of a primitive: four joint indices (as the
     *     file has them, into the skin's joint list) and four weights per vertex
     * @throws GltfException if the primitive has no matching {@code JOINTS_0} and {@code WEIGHTS_0}
     *     accessors
     */
    public VertexSkinning readSkinning(int meshIndex, int primitiveIndex) {
        Primitive p = meshes.get(meshIndex).primitives().get(primitiveIndex);
        Integer ja = p.attributes().get("JOINTS_0"), wa = p.attributes().get("WEIGHTS_0");
        if (ja == null || wa == null) {
            throw new GltfException("the primitive has no JOINTS_0 and WEIGHTS_0");
        }
        AccessorInfo ji = accessorInfo(ja), wi = accessorInfo(wa);
        if (ji.components() != 4 || wi.components() != 4 || ji.count() != wi.count() || ji.componentType() == 5126) {
            throw new GltfException("JOINTS_0 and WEIGHTS_0 must be matching VEC4 accessors, joints not float");
        }
        return new VertexSkinning(readInts(ja), readFloats(wa));
    }

    // ---------------------------------------------------------------- nodes

    /**
     * Builds the local transform of a node from its matrix or from its translation, rotation and
     * scale.
     *
     * @param node the node index
     * @return the local transform of a node as a matrix
     */
    public Mat4f localMatrix(int node) {
        Node n = nodes.get(node);
        if (n.matrix() != null) {
            return Mat4f.fromArray(n.matrix(), 0);
        }
        return Mat4f.translationRotateScale(new Vec3f(n.translation()[0], n.translation()[1], n.translation()[2]),
                new Quatf(n.rotation()[0], n.rotation()[1], n.rotation()[2], n.rotation()[3]).normalize(),
                new Vec3f(n.scale()[0], n.scale()[1], n.scale()[2]));
    }

    /**
     * Computes the world matrix of every node by walking the node hierarchy from the roots.
     *
     * @return world matrices of all nodes (the product of the local matrices from the root down)
     */
    public Mat4f[] worldMatrices() {
        int n = nodes.size();
        int[] parent = parents();
        Mat4f[] world = new Mat4f[n];
        int[] scratch = new int[n];
        for (int i = 0; i < n; i++) {
            computeWorld(i, parent, world, scratch);
        }
        return world;
    }

    private void computeWorld(int i, int[] parent, Mat4f[] world, int[] chain) {
        // iterative walk up to the first known ancestor, then down again: no recursion depth issue on a deep chain
        int top = i, depth = 0;
        while (top >= 0 && world[top] == null) {
            chain[depth++] = top;
            top = parent[top];
        }
        Mat4f current = top >= 0 ? world[top] : null;
        for (int d = depth - 1; d >= 0; d--) {
            int node = chain[d];
            Mat4f local = localMatrix(node);
            current = current == null ? local : current.mul(local);
            world[node] = current;
        }
    }

    /**
     * The parent node of every node, -1 for roots.
     */
    int[] parents() {
        int[] parent = new int[nodes.size()];
        Arrays.fill(parent, -1);
        for (int i = 0; i < parent.length; i++) {
            for (int c : nodes.get(i).children()) {
                parent[c] = i;
            }
        }
        return parent;
    }

    // ---------------------------------------------------------------- skins

    /**
     * Counts the skins in the file.
     *
     * @return the number of skins
     */
    public int skinCount() {
        return list(root.get("skins")).size();
    }

    /**
     * Builds (and caches) the skeleton of skin {@code index}; see the class comment for the joint
     * reordering.
     *
     * @param index the index
     * @return the skeleton data of the skin, never {@code null}
     */
    public SkinData skin(int index) {
        SkinData cached = skinCache.get(index);
        if (cached != null) {
            return cached;
        }
        SkinData data = GltfSkins.build(this, index);
        skinCache.put(index, data);
        return data;
    }

    // ---------------------------------------------------------------- animations

    /**
     * Counts the animations in the file.
     *
     * @return the number of animations
     */
    public int animationCount() {
        return list(root.get("animations")).size();
    }

    /**
     * Looks up the name of an animation, which the file may omit.
     *
     * @param animation the animation
     * @return the name of animation {@code animation}, or {@code null} when the file gives none
     */
    public String animationName(int animation) {
        return str(obj(list(root.get("animations")).get(animation), "animation"), "name", null);
    }

    /**
     * Converts an animation to a clip with a default sampling rate for cubic spline channels, which
     * the clip format cannot store as splines.
     *
     * @param animation the animation
     * @param skin the skin; must not be {@code null}
     * @return {@link #clip(int, SkinData, float)} with cubic curves resampled at 30 Hz
     */
    public AnimationClip clip(int animation, SkinData skin) {
        return clip(animation, skin, 30f);
    }

    /**
     * Converts an animation to a clip for a skin's skeleton, resampling cubic spline channels at
     * the given rate; channels that do not target the skin's joints are ignored.
     *
     * <p>STEP becomes a pair of keys per step, CUBICSPLINE is sampled {@code cubicRate} times a
     * second (and at every key).
     *
     * @param animation the animation
     * @param skin the skin; must not be {@code null}
     * @param cubicRate the cubic rate
     * @return the animation as a clip for the skeleton of {@code skin}: translation, rotation and
     *     scale channels that target the skin's joints (other nodes and weights channels are
     *     ignored)
     */
    public AnimationClip clip(int animation, SkinData skin, float cubicRate) {
        return GltfAnimations.clip(this, animation, skin, cubicRate);
    }

}
