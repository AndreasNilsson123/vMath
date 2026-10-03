package vmath.assets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Vec3f;

/**
 * Builds realistic asset files from scratch, deterministically, the way exporters lay them out: an armature node above the skeleton, interleaved and strided
 * buffer views, normalized integer attributes, scrambled joint order, several UV sets and vertex colours, unknown optional extensions, cameras and lights that a
 * loader must ignore, morph targets it does not load, sparse accessors, external buffers and images, and KTX2 files with real descriptors and block-compressed mips.
 * The same bytes are committed under {@code src/test/resources/assets} so that they can be opened in any glTF or KTX viewer; a test checks that the committed files
 * still equal what this class produces.
 */
public final class AssetFactory {

    private AssetFactory() {
    }

    // ------------------------------------------------------------------ helpers

    private static String f(double v) {
        return String.format(Locale.ROOT, "%s", Float.toString((float) v));
    }

    private static String floats(float... v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(f(v[i]));
        }
        return sb.append("]").toString();
    }

    /** Collects the buffer, views and accessors of one glTF document. */
    private static final class Doc {
        final Bin bin = new Bin();
        final List<String> views = new ArrayList<>(), accessors = new ArrayList<>();

        int view(byte[] data, int stride) {
            bin.align(4);
            int offset = bin.size();
            bin.bytes(data);
            views.add("{\"buffer\":0,\"byteOffset\":" + offset + ",\"byteLength\":" + data.length + (stride > 0 ? ",\"byteStride\":" + stride : "") + "}");
            return views.size() - 1;
        }

        int accessor(int view, int byteOffset, int componentType, boolean normalized, int count, String type, float[] min, float[] max) {
            accessors.add("{\"bufferView\":" + view + (byteOffset > 0 ? ",\"byteOffset\":" + byteOffset : "") + ",\"componentType\":" + componentType
                    + (normalized ? ",\"normalized\":true" : "") + ",\"count\":" + count + ",\"type\":\"" + type + "\""
                    + (min != null ? ",\"min\":" + floats(min) + ",\"max\":" + floats(max) : "") + "}");
            return accessors.size() - 1;
        }

        String bufferViewsJson() {
            return "\"bufferViews\":[" + String.join(",", views) + "]";
        }

        String accessorsJson() {
            return "\"accessors\":[" + String.join(",", accessors) + "]";
        }
    }

    private static byte[] glb(String json, byte[] bin) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int jp = (j.length + 3) & ~3, bp = (bin.length + 3) & ~3;
        Bin out = new Bin();
        out.u32(0x46546C67L, 2, 12 + 8 + jp + 8 + bp);
        out.u32(jp, 0x4E4F534AL).bytes(j);
        for (int i = j.length; i < jp; i++) {
            out.u8(0x20);
        }
        out.u32(bp, 0x004E4942L).bytes(bin);
        out.align(4);
        return out.toBytes();
    }

    private static void matrix(Bin b, Mat4f m) {
        b.f32(m.m00(), m.m01(), m.m02(), m.m03(), m.m10(), m.m11(), m.m12(), m.m13(), m.m20(), m.m21(), m.m22(), m.m23(), m.m30(), m.m31(), m.m32(), m.m33());
    }

    private static byte[] f32(float[] a) {
        Bin b = new Bin();
        b.f32(a);
        return b.toBytes();
    }

    // ------------------------------------------------------------------ the skinned tube

    public static final int TUBE_RINGS = 24, TUBE_SIDES = 16, TUBE_VERTICES = (TUBE_RINGS + 1) * (TUBE_SIDES + 1);
    public static final float TUBE_LENGTH = 1.5f, TUBE_RADIUS = 0.25f, BONE_SPACING = 0.5f;
    /** The armature's rotation: -90 degrees about x, the usual Z-up to Y-up conversion of Blender exports. */
    public static final Quatf ARMATURE_ROTATION = new Quatf(-0.70710678f, 0f, 0f, 0.70710678f);
    /** Skin joint list: node indices of Bone2, Bone0, Bone3, Bone1 (a scrambled order on purpose). */
    public static final int[] TUBE_SKIN_JOINTS = {3, 1, 4, 2};

    /** The raw data of the tube, before it is written to a file, for tests that need an independent oracle. */
    public record Tube(byte[] glb, float[] positions, float[] normals, float[] tangents, float[] uv0, int[] joints, float[] weights, int[] indices,
                       float[] inverseBind, byte[] png) {
    }

    public static Tube skinnedTube() {
        int n = TUBE_VERTICES;
        float[] pos = new float[n * 3], nrm = new float[n * 3], tan = new float[n * 4], uv0 = new float[n * 2], uv1 = new float[n * 2], w = new float[n * 4];
        int[] joints = new int[n * 4];
        int[] boneToSkin = new int[4];
        for (int s = 0; s < TUBE_SKIN_JOINTS.length; s++) {
            boneToSkin[TUBE_SKIN_JOINTS[s] - 1] = s;
        }
        Mat4f armature = Mat4f.translationRotateScale(Vec3f.ZERO, ARMATURE_ROTATION, new Vec3f(1f, 1f, 1f));
        for (int r = 0; r <= TUBE_RINGS; r++) {
            for (int k = 0; k <= TUBE_SIDES; k++) {
                int v = r * (TUBE_SIDES + 1) + k;
                float y = TUBE_LENGTH * r / TUBE_RINGS, a = (float) (2 * Math.PI * k / TUBE_SIDES);
                float c = (float) Math.cos(a), s = (float) Math.sin(a);
                Vec3f p = armature.transformPosition(new Vec3f(TUBE_RADIUS * c, y, TUBE_RADIUS * s));
                Vec3f nn = armature.transformDirection(new Vec3f(c, 0f, s));
                Vec3f tt = armature.transformDirection(new Vec3f(-s, 0f, c));
                pos[v * 3] = p.x();
                pos[v * 3 + 1] = p.y();
                pos[v * 3 + 2] = p.z();
                nrm[v * 3] = nn.x();
                nrm[v * 3 + 1] = nn.y();
                nrm[v * 3 + 2] = nn.z();
                tan[v * 4] = tt.x();
                tan[v * 4 + 1] = tt.y();
                tan[v * 4 + 2] = tt.z();
                tan[v * 4 + 3] = 1f;
                uv0[v * 2] = (float) k / TUBE_SIDES;
                uv0[v * 2 + 1] = (float) r / TUBE_RINGS;
                uv1[v * 2] = 0.05f + 0.9f * uv0[v * 2];
                uv1[v * 2 + 1] = 0.05f + 0.9f * uv0[v * 2 + 1];
                float t = Math.min(y / BONE_SPACING, 2.999f);
                int b = (int) Math.floor(t);
                float frac = t - b;
                joints[v * 4] = boneToSkin[b];
                joints[v * 4 + 1] = boneToSkin[b + 1];
                w[v * 4] = 1f - frac;
                w[v * 4 + 1] = frac;
            }
        }
        int[] idx = new int[TUBE_RINGS * TUBE_SIDES * 6];
        int o = 0;
        for (int r = 0; r < TUBE_RINGS; r++) {
            for (int k = 0; k < TUBE_SIDES; k++) {
                int a = r * (TUBE_SIDES + 1) + k, b = a + 1, c = a + TUBE_SIDES + 2, d = a + TUBE_SIDES + 1;
                idx[o++] = a;
                idx[o++] = c;
                idx[o++] = b;
                idx[o++] = a;
                idx[o++] = d;
                idx[o++] = c;
            }
        }
        // inverse bind matrices in the order of the skin's joint list: inverse(armature * translate(0, 0.5 * bone, 0))
        float[] ibm = new float[TUBE_SKIN_JOINTS.length * 16];
        Bin ibmBin = new Bin();
        for (int s = 0; s < TUBE_SKIN_JOINTS.length; s++) {
            int bone = TUBE_SKIN_JOINTS[s] - 1;
            Mat4f world = armature.mul(Mat4f.translation(0f, BONE_SPACING * bone, 0f));
            matrix(ibmBin, world.invert());
        }
        byte[] ibmBytes = ibmBin.toBytes();
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(ibmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ibm.length; i++) {
            ibm[i] = bb.getFloat(i * 4);
        }

        Doc d = new Doc();
        // interleaved position + normal, stride 24
        Bin inter = new Bin();
        for (int v = 0; v < n; v++) {
            inter.f32(pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2]);
        }
        int vInter = d.view(inter.toBytes(), 24);
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int v = 0; v < n; v++) {
            for (int c = 0; c < 3; c++) {
                min[c] = Math.min(min[c], pos[v * 3 + c]);
                max[c] = Math.max(max[c], pos[v * 3 + c]);
            }
        }
        int aPos = d.accessor(vInter, 0, 5126, false, n, "VEC3", min, max);
        int aNrm = d.accessor(vInter, 12, 5126, false, n, "VEC3", null, null);
        int aTan = d.accessor(d.view(f32(tan), 0), 0, 5126, false, n, "VEC4", null, null);
        int aUv0 = d.accessor(d.view(f32(uv0), 0), 0, 5126, false, n, "VEC2", null, null);
        Bin uv1Bin = new Bin();
        for (int v = 0; v < n; v++) {
            uv1Bin.u16(Math.round(uv1[v * 2] * 65535f), Math.round(uv1[v * 2 + 1] * 65535f));
        }
        int aUv1 = d.accessor(d.view(uv1Bin.toBytes(), 0), 0, 5123, true, n, "VEC2", null, null);
        Bin jBin = new Bin(), cBin = new Bin();
        for (int v = 0; v < n; v++) {
            jBin.u8(joints[v * 4], joints[v * 4 + 1], joints[v * 4 + 2], joints[v * 4 + 3]);
            cBin.u8(Math.round(255f * uv0[v * 2]), Math.round(255f * uv0[v * 2 + 1]), 128, 255);
        }
        int aJoints = d.accessor(d.view(jBin.toBytes(), 0), 0, 5121, false, n, "VEC4", null, null);
        int aWeights = d.accessor(d.view(f32(w), 0), 0, 5126, false, n, "VEC4", null, null);
        int aColor = d.accessor(d.view(cBin.toBytes(), 0), 0, 5121, true, n, "VEC4", null, null);
        Bin iBin = new Bin();
        for (int i : idx) {
            iBin.u16(i);
        }
        int aIndices = d.accessor(d.view(iBin.toBytes(), 0), 0, 5123, false, idx.length, "SCALAR", null, null);
        int aIbm = d.accessor(d.view(ibmBytes, 0), 0, 5126, false, TUBE_SKIN_JOINTS.length, "MAT4", null, null);

        // animations
        int aTimes3 = d.accessor(d.view(f32(new float[] {0f, 1f, 2f}), 0), 0, 5126, false, 3, "SCALAR", new float[] {0f}, new float[] {2f});
        int aTimes2 = d.accessor(d.view(f32(new float[] {0f, 2f}), 0), 0, 5126, false, 2, "SCALAR", new float[] {0f}, new float[] {2f});
        int[] aBend = new int[3];
        for (int b = 0; b < 3; b++) { // rotation about z of bones 1 to 3: 0, 40 degrees, 0
            float half = (float) Math.toRadians(40) / 2;
            aBend[b] = d.accessor(d.view(f32(new float[] {0, 0, 0, 1, 0, 0, (float) Math.sin(half), (float) Math.cos(half), 0, 0, 0, 1}), 0), 0, 5126, false, 3, "VEC4", null, null);
        }
        int aSwayT = d.accessor(d.view(f32(new float[] {0, 0, 0, 0.1f, 0, 0, 0, 0, 0.1f}), 0), 0, 5126, false, 3, "VEC3", null, null);
        int aSwayS = d.accessor(d.view(f32(new float[] {0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1.5f, 1, 1.5f, 0, 0, 0}), 0), 0, 5126, false, 6, "VEC3", null, null);

        byte[] png = PngWriter.rgba(64, 64, PngWriter.checker(64, 64, 8, 0xFFC03030, 0xFFF0E0C0));
        int vPng = d.view(png, 0);
        d.bin.align(4);

        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"vmath AssetFactory\",\"extras\":{\"note\":\"generated, see src/test/java/vmath/assets\"}},"
                + "\"extensionsUsed\":[\"KHR_materials_emissive_strength\"],\"scene\":0,\"scenes\":[{\"name\":\"Scene\",\"nodes\":[0,5]}],"
                + "\"nodes\":[{\"name\":\"Armature\",\"rotation\":" + floats(-0.70710678f, 0f, 0f, 0.70710678f) + ",\"children\":[1]},"
                + "{\"name\":\"Bone0\",\"children\":[2]},{\"name\":\"Bone1\",\"translation\":[0,0.5,0],\"children\":[3]},"
                + "{\"name\":\"Bone2\",\"translation\":[0,0.5,0],\"children\":[4]},{\"name\":\"Bone3\",\"translation\":[0,0.5,0]},"
                + "{\"name\":\"Tube\",\"mesh\":0,\"skin\":0}],"
                + "\"skins\":[{\"name\":\"Armature\",\"inverseBindMatrices\":" + aIbm + ",\"skeleton\":1,\"joints\":[3,1,4,2]}],"
                + "\"meshes\":[{\"name\":\"Tube\",\"primitives\":[{\"attributes\":{\"POSITION\":" + aPos + ",\"NORMAL\":" + aNrm + ",\"TANGENT\":" + aTan + ",\"TEXCOORD_0\":" + aUv0
                + ",\"TEXCOORD_1\":" + aUv1 + ",\"COLOR_0\":" + aColor + ",\"JOINTS_0\":" + aJoints + ",\"WEIGHTS_0\":" + aWeights + "},\"indices\":" + aIndices + ",\"material\":0}]}],"
                + "\"materials\":[{\"name\":\"Skin\",\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},\"metallicFactor\":0.0,\"roughnessFactor\":0.8},"
                + "\"emissiveFactor\":[0.1,0.0,0.0],\"extensions\":{\"KHR_materials_emissive_strength\":{\"emissiveStrength\":2.0}},\"extras\":{\"tag\":\"x\"}}],"
                + "\"textures\":[{\"sampler\":0,\"source\":0}],\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":10497,\"wrapT\":10497}],"
                + "\"images\":[{\"name\":\"checker\",\"bufferView\":" + vPng + ",\"mimeType\":\"image/png\"}],"
                + "\"animations\":[{\"name\":\"bend\",\"samplers\":[{\"input\":" + aTimes3 + ",\"output\":" + aBend[0] + ",\"interpolation\":\"LINEAR\"},{\"input\":" + aTimes3 + ",\"output\":"
                + aBend[1] + "},{\"input\":" + aTimes3 + ",\"output\":" + aBend[2] + "}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":2,\"path\":\"rotation\"}},"
                + "{\"sampler\":1,\"target\":{\"node\":3,\"path\":\"rotation\"}},{\"sampler\":2,\"target\":{\"node\":4,\"path\":\"rotation\"}}]},"
                + "{\"name\":\"sway\",\"samplers\":[{\"input\":" + aTimes3 + ",\"output\":" + aSwayT + ",\"interpolation\":\"STEP\"},{\"input\":" + aTimes2 + ",\"output\":" + aSwayS
                + ",\"interpolation\":\"CUBICSPLINE\"}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":1,\"path\":\"translation\"}},"
                + "{\"sampler\":1,\"target\":{\"node\":3,\"path\":\"scale\"}}]}],"
                + "\"buffers\":[{\"byteLength\":" + d.bin.size() + "}]," + d.bufferViewsJson() + "," + d.accessorsJson() + "}";
        return new Tube(glb(json, d.bin.toBytes()), pos, nrm, tan, uv0, joints, w, idx, ibm, png);
    }

    // ------------------------------------------------------------------ the quantized sphere

    public static final int SPHERE_LATITUDES = 16, SPHERE_LONGITUDES = 32;
    public static final float SPHERE_NODE_SCALE = 2f;

    /** The raw data of the sphere: float originals and the triangles. */
    public record Sphere(byte[] glb, float[] positions, float[] normals, float[] uv, int[] indices) {
    }

    /** A UV sphere stored the way KHR_mesh_quantization allows: normalized shorts for positions, normalized bytes for normals (3 bytes with a stride of 4), normalized ushorts for UVs. */
    public static Sphere quantizedSphere() {
        int rows = SPHERE_LATITUDES + 1, cols = SPHERE_LONGITUDES + 1, n = rows * cols;
        float[] pos = new float[n * 3], nrm = new float[n * 3], uv = new float[n * 2];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = r * cols + c;
                double theta = Math.PI * r / SPHERE_LATITUDES, phi = 2 * Math.PI * c / SPHERE_LONGITUDES;
                float x = (float) (Math.sin(theta) * Math.cos(phi)), y = (float) Math.cos(theta), z = (float) (Math.sin(theta) * Math.sin(phi));
                pos[v * 3] = x;
                pos[v * 3 + 1] = y;
                pos[v * 3 + 2] = z;
                nrm[v * 3] = x;
                nrm[v * 3 + 1] = y;
                nrm[v * 3 + 2] = z;
                uv[v * 2] = (float) c / SPHERE_LONGITUDES;
                uv[v * 2 + 1] = (float) r / SPHERE_LATITUDES;
            }
        }
        List<Integer> idx = new ArrayList<>();
        for (int r = 0; r < SPHERE_LATITUDES; r++) {
            for (int c = 0; c < SPHERE_LONGITUDES; c++) {
                int a = r * cols + c, b = a + 1, cc = a + cols + 1, dd = a + cols;
                if (r > 0) { // the top ring would be degenerate
                    idx.add(a);
                    idx.add(b);
                    idx.add(cc);
                }
                if (r < SPHERE_LATITUDES - 1) {
                    idx.add(a);
                    idx.add(cc);
                    idx.add(dd);
                }
            }
        }
        int[] indices = idx.stream().mapToInt(Integer::intValue).toArray();
        Doc d = new Doc();
        Bin pb = new Bin();
        for (int v = 0; v < n; v++) {
            pb.u16(Math.round(pos[v * 3] * 32767f), Math.round(pos[v * 3 + 1] * 32767f), Math.round(pos[v * 3 + 2] * 32767f), 0); // padded to 8 bytes
        }
        int aPos = d.accessor(d.view(pb.toBytes(), 8), 0, 5122, true, n, "VEC3", new float[] {-1f, -1f, -1f}, new float[] {1f, 1f, 1f});
        Bin nb = new Bin();
        for (int v = 0; v < n; v++) {
            nb.u8(Math.round(nrm[v * 3] * 127f), Math.round(nrm[v * 3 + 1] * 127f), Math.round(nrm[v * 3 + 2] * 127f), 0);
        }
        int aNrm = d.accessor(d.view(nb.toBytes(), 4), 0, 5120, true, n, "VEC3", null, null);
        Bin ub = new Bin();
        for (int v = 0; v < n; v++) {
            ub.u16(Math.round(uv[v * 2] * 65535f), Math.round(uv[v * 2 + 1] * 65535f));
        }
        int aUv = d.accessor(d.view(ub.toBytes(), 0), 0, 5123, true, n, "VEC2", null, null);
        Bin ib = new Bin();
        for (int i : indices) {
            ib.u32(i);
        }
        int aIdx = d.accessor(d.view(ib.toBytes(), 0), 0, 5125, false, indices.length, "SCALAR", null, null);
        d.bin.align(4);
        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"vmath AssetFactory\"},\"extensionsUsed\":[\"KHR_mesh_quantization\"],\"extensionsRequired\":[\"KHR_mesh_quantization\"],"
                + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"name\":\"Sphere\",\"mesh\":0,\"scale\":[" + f(SPHERE_NODE_SCALE) + "," + f(SPHERE_NODE_SCALE) + "," + f(SPHERE_NODE_SCALE) + "]}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + aPos + ",\"NORMAL\":" + aNrm + ",\"TEXCOORD_0\":" + aUv + "},\"indices\":" + aIdx + "}]}],"
                + "\"buffers\":[{\"byteLength\":" + d.bin.size() + "}]," + d.bufferViewsJson() + "," + d.accessorsJson() + "}";
        return new Sphere(glb(json, d.bin.toBytes()), pos, nrm, uv, indices);
    }

    // ------------------------------------------------------------------ the multi-file scene

    /** The files of a {@code .gltf} scene with an external buffer and image: name to bytes. */
    public static Map<String, byte[]> sceneFiles() {
        Doc d = new Doc();
        // Ground: a 4 x 4 cell plane with two UV sets and float vertex colours
        int cells = 4, side = cells + 1, gn = side * side;
        float[] gp = new float[gn * 3], gnrm = new float[gn * 3], gu0 = new float[gn * 2], gu1 = new float[gn * 2], gcol = new float[gn * 3];
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                int v = j * side + i;
                gp[v * 3] = i - cells / 2f;
                gp[v * 3 + 2] = j - cells / 2f;
                gnrm[v * 3 + 1] = 1f;
                gu0[v * 2] = (float) i / cells;
                gu0[v * 2 + 1] = (float) j / cells;
                gu1[v * 2] = gu0[v * 2] * 0.5f;
                gu1[v * 2 + 1] = gu0[v * 2 + 1] * 0.5f;
                gcol[v * 3] = (float) i / cells;
                gcol[v * 3 + 1] = (float) j / cells;
                gcol[v * 3 + 2] = 0.5f;
            }
        }
        Bin gi = new Bin();
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int a = j * side + i;
                gi.u16(a, a + side, a + 1, a + 1, a + side, a + side + 1);
            }
        }
        int aGp = d.accessor(d.view(f32(gp), 0), 0, 5126, false, gn, "VEC3", new float[] {-2f, 0f, -2f}, new float[] {2f, 0f, 2f});
        int aGn = d.accessor(d.view(f32(gnrm), 0), 0, 5126, false, gn, "VEC3", null, null);
        int aGu0 = d.accessor(d.view(f32(gu0), 0), 0, 5126, false, gn, "VEC2", null, null);
        int aGu1 = d.accessor(d.view(f32(gu1), 0), 0, 5126, false, gn, "VEC2", null, null);
        int aGc = d.accessor(d.view(f32(gcol), 0), 0, 5126, false, gn, "VEC3", null, null);
        int aGi = d.accessor(d.view(gi.toBytes(), 0), 0, 5123, false, cells * cells * 6, "SCALAR", null, null);
        // Quad: a triangle strip with indices
        int aQp = d.accessor(d.view(f32(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0}), 0), 0, 5126, false, 4, "VEC3", null, null);
        Bin qi = new Bin();
        qi.u8(0, 1, 2, 3);
        int aQi = d.accessor(d.view(qi.toBytes(), 0), 0, 5121, false, 4, "SCALAR", null, null);
        // Hex: a triangle fan without indices (centre, six ring vertices, the first ring vertex again)
        float[] hp = new float[8 * 3];
        for (int k = 0; k < 7; k++) {
            double a = 2 * Math.PI * (k % 6) / 6;
            hp[(k + 1) * 3] = (float) Math.cos(a);
            hp[(k + 1) * 3 + 1] = (float) Math.sin(a);
        }
        int aHp = d.accessor(d.view(f32(hp), 0), 0, 5126, false, 8, "VEC3", null, null);
        // Bump: a flat 3 x 3 grid whose height comes from a sparse accessor, plus a morph target that loaders of this library ignore
        float[] bp = new float[9 * 3];
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                bp[(j * 3 + i) * 3] = i - 1f;
                bp[(j * 3 + i) * 3 + 2] = j - 1f;
            }
        }
        int vBase = d.view(f32(bp), 0);
        Bin sidx = new Bin();
        sidx.u8(4, 5, 7, 0);
        int vSi = d.view(sidx.toBytes(), 0);
        int vSv = d.view(f32(new float[] {0, 1.0f, 0, 0, 0.5f, 0, 0, 0.25f, 0}), 0);
        d.accessors.add("{\"bufferView\":" + vBase + ",\"componentType\":5126,\"count\":9,\"type\":\"VEC3\",\"sparse\":{\"count\":3,\"indices\":{\"bufferView\":" + vSi
                + ",\"componentType\":5121},\"values\":{\"bufferView\":" + vSv + "}}}");
        int aBp = d.accessors.size() - 1;
        Bin bi = new Bin();
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                int a = j * 3 + i;
                bi.u16(a, a + 3, a + 1, a + 1, a + 3, a + 4);
            }
        }
        int aBi = d.accessor(d.view(bi.toBytes(), 0), 0, 5123, false, 24, "SCALAR", null, null);
        int aMorph = d.accessor(d.view(f32(new float[9 * 3]), 0), 0, 5126, false, 9, "VEC3", null, null);
        byte[] albedo = PngWriter.rgba(32, 32, PngWriter.checker(32, 32, 4, 0xFF3060C0, 0xFFE0E0E0));
        byte[] normalMap = PngWriter.rgba(8, 8, PngWriter.checker(8, 8, 2, 0xFF8080FF, 0xFF8080FF));
        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"vmath AssetFactory\"},\"extensionsUsed\":[\"KHR_lights_punctual\",\"KHR_texture_transform\"],"
                + "\"extensions\":{\"KHR_lights_punctual\":{\"lights\":[{\"type\":\"directional\",\"intensity\":3.0}]}},"
                + "\"scene\":1,\"scenes\":[{\"name\":\"empty\",\"nodes\":[]},{\"name\":\"main\",\"nodes\":[0,6]}],"
                + "\"nodes\":[{\"name\":\"Root\",\"translation\":[0,1,0],\"children\":[1,2]},{\"name\":\"GroundNode\",\"mesh\":0},"
                + "{\"name\":\"Props\",\"scale\":[2,2,2],\"children\":[3,4,5]},{\"name\":\"QuadNode\",\"mesh\":1,\"rotation\":" + floats(0f, 0.70710678f, 0f, 0.70710678f) + "},"
                + "{\"name\":\"HexNode\",\"mesh\":2,\"translation\":[3,0,0]},{\"name\":\"BumpNode\",\"mesh\":3,\"matrix\":[1,0,0,0, 0,1,0,0, 0,0,1,0, -3,0,0,1]},"
                + "{\"name\":\"Sun\",\"extensions\":{\"KHR_lights_punctual\":{\"light\":0}}},{\"name\":\"Camera\",\"camera\":0}],"
                + "\"cameras\":[{\"type\":\"perspective\",\"perspective\":{\"yfov\":0.8,\"znear\":0.1,\"zfar\":100.0}}],"
                + "\"meshes\":[{\"name\":\"Ground\",\"primitives\":[{\"attributes\":{\"POSITION\":" + aGp + ",\"NORMAL\":" + aGn + ",\"TEXCOORD_0\":" + aGu0 + ",\"TEXCOORD_1\":" + aGu1
                + ",\"COLOR_0\":" + aGc + "},\"indices\":" + aGi + ",\"material\":0}]},"
                + "{\"name\":\"Quad\",\"primitives\":[{\"attributes\":{\"POSITION\":" + aQp + "},\"indices\":" + aQi + ",\"mode\":5,\"material\":1}]},"
                + "{\"name\":\"Hex\",\"primitives\":[{\"attributes\":{\"POSITION\":" + aHp + "},\"mode\":6,\"material\":1}]},"
                + "{\"name\":\"Bump\",\"weights\":[0.5],\"primitives\":[{\"attributes\":{\"POSITION\":" + aBp + "},\"indices\":" + aBi + ",\"targets\":[{\"POSITION\":" + aMorph + "}]}]}],"
                + "\"materials\":[{\"name\":\"Ground\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.8,0.8,0.8,1.0],\"baseColorTexture\":{\"index\":0,\"texCoord\":0,"
                + "\"extensions\":{\"KHR_texture_transform\":{\"scale\":[2,2]}}},\"metallicFactor\":0.1,\"roughnessFactor\":0.9},\"normalTexture\":{\"index\":1,\"scale\":0.5}},"
                + "{\"name\":\"Plain\",\"alphaMode\":\"MASK\",\"alphaCutoff\":0.3,\"doubleSided\":true}],"
                + "\"textures\":[{\"source\":0,\"sampler\":0},{\"source\":1}],\"samplers\":[{\"magFilter\":9728,\"minFilter\":9728}],"
                + "\"images\":[{\"uri\":\"albedo.png\"},{\"uri\":\"data:image/png;base64," + Base64.getEncoder().encodeToString(normalMap) + "\"}],"
                + "\"buffers\":[{\"byteLength\":" + d.bin.size() + ",\"uri\":\"scene.bin\"}]," + d.bufferViewsJson() + "," + d.accessorsJson() + "}";
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("scene.gltf", json.getBytes(StandardCharsets.UTF_8));
        files.put("scene.bin", d.bin.toBytes());
        files.put("albedo.png", albedo);
        return files;
    }

    /** The decoded normal map embedded in the scene as a data URI. */
    public static byte[] sceneNormalMapPng() {
        return PngWriter.rgba(8, 8, PngWriter.checker(8, 8, 2, 0xFF8080FF, 0xFF8080FF));
    }

    // ------------------------------------------------------------------ KTX2 files

    /** 2x2 box filter; odd sizes drop the last row or column pair. */
    public static int[] downsample(int w, int h, int[] px) {
        int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int[] c = new int[4];
                for (int k = 0; k < 4; k++) {
                    int p = px[Math.min(h - 1, y * 2 + k / 2) * w + Math.min(w - 1, x * 2 + k % 2)];
                    for (int ch = 0; ch < 4; ch++) {
                        c[ch] += (p >>> (8 * ch)) & 255;
                    }
                }
                out[y * nw + x] = (((c[3] + 2) / 4) << 24) | (((c[2] + 2) / 4) << 16) | (((c[1] + 2) / 4) << 8) | ((c[0] + 2) / 4);
            }
        }
        return out;
    }

    /** The source image of the BC1 texture at level {@code level}: a red and blue checker, cells of 8 texels at level 0. */
    public static int[] checkerLevel(int level) {
        int[] px = PngWriter.checker(64, 64, 8, 0xFFD02020, 0xFF2040E0);
        int w = 64, h = 64;
        for (int l = 0; l < level; l++) {
            px = downsample(w, h, px);
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        return px;
    }

    /** A 64 x 64 BC1 sRGB texture with a full chain of 7 levels. */
    public static byte[] checkerBc1Ktx2() {
        int levels = 7;
        byte[][] data = new byte[levels][];
        for (int l = 0; l < levels; l++) {
            int size = Math.max(1, 64 >> l);
            data[l] = Bc1.encode(size, size, checkerLevel(l));
        }
        byte[] dfd = Ktx2Writer.dfd(128, 1, 2, new int[] {4, 4, 1, 1}, 8, new Ktx2Writer.Sample(0, 64, 0, false, 0, 0xFFFFFFFFL));
        byte[] kvd = Ktx2Writer.kvd(new String[][] {{"KTXorientation", "rd"}, {"KTXwriter", "vmath AssetFactory"}});
        return Ktx2Writer.write(134, 1, 64, 64, 0, 0, 1, levels, dfd, kvd, data);
    }

    /** Colour of texel {@code (x, y)} of face {@code face} of the cube map at level 0: a distinct hue per face, brighter to the right. */
    public static int cubeTexel(int face, int x, int y, int size) {
        int[] base = {0xE03030, 0x30E030, 0x3030E0, 0xE0E030, 0x30E0E0, 0xE030E0};
        int shade = 128 + 127 * x / Math.max(1, size - 1);
        int r = ((base[face] >> 16) & 255) * shade / 255, g = ((base[face] >> 8) & 255) * shade / 255, b = (base[face] & 255) * shade / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** A 16 x 16 RGBA8 sRGB cube map with 5 levels, six faces per level in the order +X, -X, +Y, -Y, +Z, -Z. */
    public static byte[] cubemapRgba8Ktx2() {
        int levels = 5, size = 16;
        int[][] faces = new int[6][size * size];
        for (int f = 0; f < 6; f++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    faces[f][y * size + x] = cubeTexel(f, x, y, size);
                }
            }
        }
        byte[][] data = new byte[levels][];
        int w = size;
        for (int l = 0; l < levels; l++) {
            Bin b = new Bin();
            for (int f = 0; f < 6; f++) {
                for (int p : faces[f]) {
                    b.u8((p >> 16) & 255, (p >> 8) & 255, p & 255, (p >>> 24) & 255);
                }
                faces[f] = downsample(w, w, faces[f]);
            }
            w = Math.max(1, w / 2);
            data[l] = b.toBytes();
        }
        byte[] dfd = Ktx2Writer.dfd(1, 1, 2, new int[] {1, 1, 1, 1}, 4, new Ktx2Writer.Sample(0, 8, 0, false, 0, 255), new Ktx2Writer.Sample(8, 8, 1, false, 0, 255),
                new Ktx2Writer.Sample(16, 8, 2, false, 0, 255), new Ktx2Writer.Sample(24, 8, 15, true, 0, 255));
        byte[] kvd = Ktx2Writer.kvd(new String[][] {{"KTXwriter", "vmath AssetFactory"}});
        return Ktx2Writer.write(43, 1, size, size, 0, 0, 6, levels, dfd, kvd, data);
    }

    /** Value of texel {@code (x, y)} of layer {@code layer} of the 8 x 8 R8 array at level 0. */
    public static int arrayTexel(int layer, int x, int y) {
        return (layer * 70 + x * 8 + y * 3) & 255;
    }

    /** An 8 x 8 R8 array texture with 3 layers and 4 levels (8, 4, 2, 1). */
    public static byte[] arrayR8Ktx2() {
        int levels = 4, layers = 3;
        byte[][] data = new byte[levels][];
        int[][] cur = new int[layers][64];
        for (int layer = 0; layer < layers; layer++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int v = arrayTexel(layer, x, y);
                    cur[layer][y * 8 + x] = 0xFF000000 | v << 16 | v << 8 | v;
                }
            }
        }
        int w = 8;
        for (int l = 0; l < levels; l++) {
            Bin b = new Bin();
            for (int layer = 0; layer < layers; layer++) {
                for (int p : cur[layer]) {
                    b.u8(p & 255);
                }
                cur[layer] = downsample(w, w, cur[layer]);
            }
            w = Math.max(1, w / 2);
            data[l] = b.toBytes();
        }
        byte[] dfd = Ktx2Writer.dfd(1, 1, 1, new int[] {1, 1, 1, 1}, 1, new Ktx2Writer.Sample(0, 8, 0, false, 0, 255));
        byte[] kvd = Ktx2Writer.kvd(new String[][] {{"KTXwriter", "vmath AssetFactory"}});
        return Ktx2Writer.write(9, 1, 8, 8, 0, layers, 1, levels, dfd, kvd, data);
    }

    /** A 4 x 4 RGBA8 texture that declares 0 levels (the loader is meant to generate the chain). */
    public static byte[] noMipsRgba8Ktx2() {
        Bin b = new Bin();
        for (int i = 0; i < 16; i++) {
            b.u8(i * 16, 255 - i * 16, 64, 255);
        }
        byte[] dfd = Ktx2Writer.dfd(1, 1, 1, new int[] {1, 1, 1, 1}, 4, new Ktx2Writer.Sample(0, 8, 0, false, 0, 255), new Ktx2Writer.Sample(8, 8, 1, false, 0, 255),
                new Ktx2Writer.Sample(16, 8, 2, false, 0, 255), new Ktx2Writer.Sample(24, 8, 15, true, 0, 255));
        return Ktx2Writer.write(37, 1, 4, 4, 0, 0, 1, 0, dfd, Ktx2Writer.kvd(new String[][] {{"KTXwriter", "vmath AssetFactory"}}), new byte[][] {b.toBytes()});
    }

    // ------------------------------------------------------------------ all files

    /** Every generated file by relative path, in a fixed order. */
    public static Map<String, byte[]> all() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("gltf/skinned_tube.glb", skinnedTube().glb());
        files.put("gltf/quantized_sphere.glb", quantizedSphere().glb());
        for (Map.Entry<String, byte[]> e : sceneFiles().entrySet()) {
            files.put("gltf/scene/" + e.getKey(), e.getValue());
        }
        files.put("ktx2/checker_bc1.ktx2", checkerBc1Ktx2());
        files.put("ktx2/cubemap_rgba8.ktx2", cubemapRgba8Ktx2());
        files.put("ktx2/array_r8.ktx2", arrayR8Ktx2());
        files.put("ktx2/nomips_rgba8.ktx2", noMipsRgba8Ktx2());
        return files;
    }

    /** Writes every generated file under {@code dir}. */
    public static void writeAll(Path dir) throws IOException {
        for (Map.Entry<String, byte[]> e : all().entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
    }
}
