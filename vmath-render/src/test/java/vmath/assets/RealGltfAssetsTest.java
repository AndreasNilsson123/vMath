package vmath.assets;

import vmath.Report;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import vmath.anim.AnimationClip;
import vmath.anim.ClipSampler;
import vmath.anim.Pose;
import vmath.anim.Skeleton;
import vmath.anim.Skinning;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Intersectionf;
import vmath.geo.Trianglef;
import vmath.gltf.Gltf;
import vmath.mesh.ClusterHierarchy;
import vmath.mesh.Mesh;
import vmath.mesh.MeshSimplifier;
import vmath.mesh.Meshlets;
import vmath.mesh.Overdraw;
import vmath.mesh.UvAtlas;

/** The generated exporter-style glTF assets (committed under src/test/resources/assets) through the loader and the mesh tools, against independent oracles. */
class RealGltfAssetsTest {

    private static Gltf load(String path) throws IOException {
        return Gltf.parse(AssetFilesTest.resource(path), null);
    }

    private static Path resourcePath(String rel) throws URISyntaxException {
        return Path.of(RealGltfAssetsTest.class.getResource("/assets/" + rel).toURI());
    }

    // ---------------------------------------------------------------- the skinned tube

    @Test
    void theTubeHasTheStructureAnExporterWrites() throws IOException {
        Gltf g = load("gltf/skinned_tube.glb");
        assertEquals(1, g.meshCount());
        assertEquals("Tube", g.mesh(0).name());
        Gltf.Primitive p = g.mesh(0).primitives().get(0);
        assertEquals(8, p.attributes().size(), "position, normal, tangent, two UV sets, colour, joints, weights");
        assertEquals(6, g.nodeCount());
        assertEquals("Armature", g.node(0).name());
        assertEquals(1, g.skinCount());
        assertEquals(2, g.animationCount());
        assertEquals("bend", g.animationName(0));
        assertEquals("sway", g.animationName(1));
        assertEquals(0, g.defaultScene());
        assertArrayEquals(new int[] {0, 5}, g.sceneNodes(0));
        // the file's own accessor min and max are right
        Gltf.AccessorInfo pos = g.accessorInfo(p.attributes().get("POSITION"));
        float[] positions = g.readFloats(p.attributes().get("POSITION"));
        for (int c = 0; c < 3; c++) {
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int v = 0; v < pos.count(); v++) {
                lo = Math.min(lo, positions[v * 3 + c]);
                hi = Math.max(hi, positions[v * 3 + c]);
            }
            assertEquals(lo, pos.min()[c], 0f);
            assertEquals(hi, pos.max()[c], 0f);
        }
    }

    @Test
    void theTubeMeshMatchesTheGeneratorsData() throws IOException {
        AssetFactory.Tube t = AssetFactory.skinnedTube();
        Gltf g = load("gltf/skinned_tube.glb");
        Mesh m = g.toMesh(0, 0);
        assertEquals(AssetFactory.TUBE_VERTICES, m.vertexCount());
        assertEquals(t.indices().length / 3, m.triangleCount());
        assertTrue(m.hasNormals() && m.hasTangents() && m.hasUvs(0) && m.hasUvs(1) && !m.hasUvs(2));
        for (int i = 0; i < m.vertexCount() * 3; i++) {
            assertEquals(t.positions()[i], m.positions()[i], 0f, "positions are read through the interleaved stride without loss");
            assertEquals(t.normals()[i], m.normals()[i], 0f);
        }
        for (int i = 0; i < m.vertexCount() * 4; i++) {
            assertEquals(t.tangents()[i], m.tangents()[i], 0f);
        }
        for (int i = 0; i < m.vertexCount() * 2; i++) {
            assertEquals(t.uv0()[i], m.uvs(0)[i], 0f);
            assertEquals(0.05f + 0.9f * t.uv0()[i], m.uvs(1)[i], 2e-5f, "the normalized ushort UV set 1 is within one step of 1/65535");
        }
        assertArrayEquals(t.indices(), Arrays.copyOf(m.indices(), m.indexCount()));
        // winding: every triangle faces the same way as its vertex normals
        float[] p = m.positions(), n = m.normals();
        int[] idx = m.indices();
        for (int tri = 0; tri < m.triangleCount(); tri++) {
            int a = idx[tri * 3] * 3, b = idx[tri * 3 + 1] * 3, c = idx[tri * 3 + 2] * 3;
            double ex = p[b] - p[a], ey = p[b + 1] - p[a + 1], ez = p[b + 2] - p[a + 2], fx = p[c] - p[a], fy = p[c + 1] - p[a + 1], fz = p[c + 2] - p[a + 2];
            double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            assertTrue(nx * n[a] + ny * n[a + 1] + nz * n[a + 2] > 0, "triangle " + tri + " is wound counter-clockwise seen from outside");
        }
    }

    @Test
    void materialsImagesAndIgnoredExtensions() throws IOException {
        AssetFactory.Tube t = AssetFactory.skinnedTube();
        Gltf g = load("gltf/skinned_tube.glb");
        Gltf.Material m = g.material(0);
        assertEquals("Skin", m.name());
        assertEquals(0, m.baseColorTexture());
        assertEquals(0f, m.metallicFactor());
        assertEquals(0.8f, m.roughnessFactor());
        assertArrayEquals(new float[] {0.1f, 0f, 0f}, m.emissiveFactor());
        assertEquals(new Gltf.Texture(0, 0), g.texture(0));
        assertEquals(9729, g.sampler(0).magFilter());
        assertEquals(9987, g.sampler(0).minFilter());
        assertArrayEquals(t.png(), g.imageBytes(0), "the image comes out of its buffer view byte for byte");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(g.imageBytes(0)));
        assertEquals(64, img.getWidth());
        assertEquals(0xFFC03030, img.getRGB(0, 0));
        assertEquals(0xFFF0E0C0, img.getRGB(8, 0));
    }

    @Test
    void theArmatureNodeAndTheBoneChain() throws IOException {
        Gltf g = load("gltf/skinned_tube.glb");
        Mat4f[] w = g.worldMatrices();
        // Bone1 sits 0.5 up its parent; the armature turns local +y into world -z
        Vec3f bone1 = w[2].transformPosition(Vec3f.ZERO);
        assertEquals(0f, bone1.x(), 1e-6f);
        assertEquals(0f, bone1.y(), 1e-6f);
        assertEquals(-0.5f, bone1.z(), 1e-6f);
        Vec3f bone3 = w[4].transformPosition(Vec3f.ZERO);
        assertEquals(-1.5f, bone3.z(), 1e-5f);
        assertEquals(0f, w[5].transformPosition(new Vec3f(1f, 2f, 3f)).x() - 1f, 1e-6f, "the mesh node is the identity");
    }

    @Test
    void theSkinIsReorderedAndKeepsTheArmatureTransform() throws IOException {
        AssetFactory.Tube t = AssetFactory.skinnedTube();
        Gltf g = load("gltf/skinned_tube.glb");
        Gltf.SkinData skin = g.skin(0);
        Skeleton sk = skin.skeleton();
        assertEquals(4, sk.jointCount());
        assertArrayEquals(new int[] {1, 2, 3, 4}, skin.jointNodes(), "Bone0 to Bone3, parents first, though the file lists them as [3, 1, 4, 2]");
        assertArrayEquals(new int[] {2, 0, 3, 1}, skin.skinToSkeleton());
        assertEquals(-1, sk.parent(0));
        assertEquals(2, sk.parent(3));
        assertEquals("Bone2", sk.name(2));
        // the file's inverse bind matrices, in skeleton order, are the generator's
        for (int j = 0; j < 4; j++) {
            int skinIndex = -1;
            for (int s = 0; s < 4; s++) {
                if (AssetFactory.TUBE_SKIN_JOINTS[s] - 1 == j) {
                    skinIndex = s;
                }
            }
            for (int k = 0; k < 16; k++) {
                assertEquals(t.inverseBind()[skinIndex * 16 + k], skin.inverseBindMatrices()[j * 16 + k], 0f);
            }
        }
        // the transform above the skeleton is the armature's rotation; without it the skeleton's own inverse bind matrices differ from the file's exactly by it
        Vec3f up = skin.rootTransform().transformDirection(new Vec3f(0f, 1f, 0f));
        assertEquals(0f, up.y(), 1e-6f);
        assertEquals(-1f, up.z(), 1e-6f);
        float[] own = sk.inverseBindMatrices();
        Mat4f inverseRoot = skin.rootTransform().invert();
        for (int j = 0; j < 4; j++) {
            Mat4f expected = Mat4f.fromArray(own, j * 16).mul(inverseRoot);
            Mat4f file = Mat4f.fromArray(skin.inverseBindMatrices(), j * 16);
            assertTrue(expected.approxEquals(file, 1e-5f), "file inverse bind = skeleton inverse bind * inverse(armature), joint " + j);
        }
    }

    // a double precision oracle that shares no code with the library

    private static double[] identity() {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + row] * b[c * 4 + k];
                }
                r[c * 4 + row] = s;
            }
        }
        return r;
    }

    private static double[] translation(double x, double y, double z) {
        double[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    private static double[] rotationZ(double angle) {
        double[] m = identity();
        m[0] = Math.cos(angle);
        m[1] = Math.sin(angle);
        m[4] = -Math.sin(angle);
        m[5] = Math.cos(angle);
        return m;
    }

    private static double[] armature() {
        double x = AssetFactory.ARMATURE_ROTATION.x(), y = AssetFactory.ARMATURE_ROTATION.y(), z = AssetFactory.ARMATURE_ROTATION.z(), w = AssetFactory.ARMATURE_ROTATION.w();
        return new double[] {1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0, 2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0, 0, 0, 0, 1};
    }

    /** Skinned world-space positions of the tube at bend angle {@code angle} (radians, applied to bones 1 to 3). */
    private static double[] oracleSkin(AssetFactory.Tube t, double angle) {
        double[][] bone = new double[4][];
        double[] chain = armature();
        for (int b = 0; b < 4; b++) {
            chain = mul(chain, translation(0, b == 0 ? 0 : AssetFactory.BONE_SPACING, 0));
            if (b > 0) {
                chain = mul(chain, rotationZ(angle));
            }
            bone[b] = chain;
        }
        int n = AssetFactory.TUBE_VERTICES;
        double[] out = new double[n * 3];
        for (int v = 0; v < n; v++) {
            for (int k = 0; k < 4; k++) {
                double w = t.weights()[v * 4 + k];
                if (w == 0) {
                    continue;
                }
                int skinIndex = t.joints()[v * 4 + k];
                int b = AssetFactory.TUBE_SKIN_JOINTS[skinIndex] - 1;
                double[] ibm = new double[16];
                for (int i = 0; i < 16; i++) {
                    ibm[i] = t.inverseBind()[skinIndex * 16 + i];
                }
                double[] m = mul(bone[b], ibm);
                double px = t.positions()[v * 3], py = t.positions()[v * 3 + 1], pz = t.positions()[v * 3 + 2];
                out[v * 3] += w * (m[0] * px + m[4] * py + m[8] * pz + m[12]);
                out[v * 3 + 1] += w * (m[1] * px + m[5] * py + m[9] * pz + m[13]);
                out[v * 3 + 2] += w * (m[2] * px + m[6] * py + m[10] * pz + m[14]);
            }
        }
        return out;
    }

    /** The library's way, from the file: loader, clip, pose, joint matrices with the file's inverse bind matrices, skinning, then the root transform. */
    private static float[] librarySkin(Gltf g, int animation, float time) {
        Gltf.SkinData skin = g.skin(0);
        Skeleton sk = skin.skeleton();
        Pose pose = new Pose(sk);
        pose.setToBind(sk);
        if (animation >= 0) {
            new ClipSampler(g.clip(animation, skin)).sample(time, false, pose);
        }
        float[] world = new float[16 * sk.jointCount()];
        Skinning.worldMatrices(sk, pose, world);
        float[] joint = new float[16 * sk.jointCount()];
        for (int j = 0; j < sk.jointCount(); j++) {
            Mat4f.fromArray(world, j * 16).mul(Mat4f.fromArray(skin.inverseBindMatrices(), j * 16)).writeTo(joint, j * 16);
        }
        Mesh mesh = g.toMesh(0, 0);
        Gltf.VertexSkinning vs = g.readSkinning(0, 0);
        int[] remapped = new int[vs.joints().length];
        for (int i = 0; i < remapped.length; i++) {
            remapped[i] = skin.skinToSkeleton()[vs.joints()[i]];
        }
        int n = mesh.vertexCount();
        float[] out = new float[n * 3];
        Skinning.skinPositions(joint, mesh.positions(), remapped, vs.weights(), n, out);
        for (int v = 0; v < n; v++) {
            Vec3f q = skin.rootTransform().transformPosition(new Vec3f(out[v * 3], out[v * 3 + 1], out[v * 3 + 2]));
            out[v * 3] = q.x();
            out[v * 3 + 1] = q.y();
            out[v * 3 + 2] = q.z();
        }
        return out;
    }

    @Test
    void skinningTheFileMatchesTheIndependentOracleAtRestAndBent() throws IOException {
        AssetFactory.Tube t = AssetFactory.skinnedTube();
        Gltf g = load("gltf/skinned_tube.glb");
        // at rest (no clip) every vertex goes to itself, which only works if the armature, the joint order and the inverse bind matrices are all handled
        float[] rest = librarySkin(g, -1, 0f);
        for (int i = 0; i < rest.length; i++) {
            assertEquals(t.positions()[i], rest[i], 2e-5f, "rest pose, component " + i);
        }
        double[] oracleRest = oracleSkin(t, 0);
        for (int i = 0; i < rest.length; i++) {
            assertEquals(t.positions()[i], oracleRest[i], 1e-6, "the oracle itself reproduces the bind pose");
        }
        for (float time : new float[] {0.25f, 0.5f, 1f, 1.5f}) {
            double angle = Math.toRadians(40) * (time <= 1 ? time : 2 - time);
            float[] lib = librarySkin(g, 0, time);
            double[] oracle = oracleSkin(t, angle);
            double worst = 0;
            for (int i = 0; i < lib.length; i++) {
                worst = Math.max(worst, Math.abs(lib[i] - oracle[i]));
            }
            assertTrue(worst < 3e-4, "time " + time + ": largest difference to the oracle " + worst);
        }
        // and the bend really moves the tube
        float[] bent = librarySkin(g, 0, 1f);
        double moved = 0;
        for (int i = 0; i < bent.length; i++) {
            moved = Math.max(moved, Math.abs(bent[i] - t.positions()[i]));
        }
        assertTrue(moved > 0.3, "the bent tube differs visibly from the rest pose: " + moved);
    }

    @Test
    void stepAndCubicAnimationsSampleAsDocumented() throws IOException {
        Gltf g = load("gltf/skinned_tube.glb");
        Gltf.SkinData skin = g.skin(0);
        AnimationClip sway = g.clip(1, skin);
        assertEquals(2, sway.trackCount());
        assertEquals(2f, sway.duration(), 1e-6f);
        Pose pose = new Pose(skin.skeleton());
        ClipSampler s = new ClipSampler(sway);
        s.sample(0.5f, false, pose);
        assertEquals(0f, pose.data()[0], 1e-6f, "STEP holds the first value");
        s.sample(1.0f, false, pose);
        assertEquals(0.1f, pose.data()[0], 1e-6f, "and jumps at the key");
        s.sample(1.5f, false, pose);
        assertEquals(0.1f, pose.data()[0], 1e-6f);
        s.sample(2.0f, false, pose);
        assertEquals(0.1f, pose.data()[2], 1e-6f);
        // CUBICSPLINE scale of Bone2 (joint 2): zero tangents, 1 to 1.5 in x over two seconds
        ClipSampler c = new ClipSampler(sway);
        c.sample(1.0f, false, pose);
        assertEquals(1.25f, pose.data()[2 * 10 + 7], 1e-4f);
        ClipSampler c2 = new ClipSampler(sway);
        c2.sample(0.5f, false, pose);
        assertEquals(1.078125f, pose.data()[2 * 10 + 7], 1e-4f, "Hermite at a quarter of the way");
    }

    // ---------------------------------------------------------------- the quantized sphere

    @Test
    void quantizedAttributesDecodeWithinTheirSteps() throws IOException {
        AssetFactory.Sphere s = AssetFactory.quantizedSphere();
        Gltf g = load("gltf/quantized_sphere.glb");
        Gltf.Primitive p = g.mesh(0).primitives().get(0);
        Gltf.AccessorInfo pos = g.accessorInfo(p.attributes().get("POSITION")), nrm = g.accessorInfo(p.attributes().get("NORMAL"));
        assertEquals(5122, pos.componentType());
        assertTrue(pos.normalized());
        assertEquals(5120, nrm.componentType());
        Mesh m = g.toMesh(0, 0);
        assertEquals(s.positions().length / 3, m.vertexCount());
        for (int i = 0; i < s.positions().length; i++) {
            assertEquals(Math.round(s.positions()[i] * 32767f) / 32767f, m.positions()[i], 1e-6f, "position " + i);
            assertEquals(Math.max(-1f, Math.round(s.normals()[i] * 127f) / 127f), m.normals()[i], 1e-6f, "normal " + i);
        }
        for (int i = 0; i < s.uv().length; i++) {
            assertEquals(Math.round(s.uv()[i] * 65535f) / 65535f, m.uvs(0)[i], 1e-6f, "uv " + i);
        }
        assertArrayEquals(s.indices(), Arrays.copyOf(m.indices(), m.indexCount()));
        // the quantization is faithful: the decoded surface encloses the volume of the exact one within 0.1%
        Mesh exact = new Mesh();
        for (int v = 0; v < s.positions().length / 3; v++) {
            exact.addVertex(s.positions()[v * 3], s.positions()[v * 3 + 1], s.positions()[v * 3 + 2]);
        }
        for (int i = 0; i < s.indices().length; i += 3) {
            exact.addTriangle(s.indices()[i], s.indices()[i + 1], s.indices()[i + 2]);
        }
        assertEquals(exact.signedVolume(), m.signedVolume(), exact.signedVolume() * 1e-3);
        assertTrue(m.signedVolume() > 0, "outward winding");
        // the node scales the unit sphere to radius 2
        Mat4f w = g.worldMatrices()[0];
        for (int v = 0; v < m.vertexCount(); v += 37) {
            Vec3f q = w.transformPosition(new Vec3f(m.positions()[v * 3], m.positions()[v * 3 + 1], m.positions()[v * 3 + 2]));
            assertEquals(AssetFactory.SPHERE_NODE_SCALE, q.length(), 1e-3f);
        }
    }

    // ---------------------------------------------------------------- the multi-file scene

    @Test
    void theSceneLoadsFromDiskWithItsExternalFiles() throws Exception {
        Path file = resourcePath("gltf/scene/scene.gltf");
        Gltf g = Gltf.load(file);
        assertEquals(4, g.meshCount());
        assertEquals(2, g.sceneCount());
        assertEquals(1, g.defaultScene());
        assertEquals(0, g.sceneNodes(0).length);
        assertArrayEquals(new int[] {0, 6}, g.sceneNodes(1));
        // Ground: two UV sets and colours (ignored), indexed
        Mesh ground = g.toMesh(0, 0);
        assertEquals(25, ground.vertexCount());
        assertEquals(32, ground.triangleCount());
        assertTrue(ground.hasNormals() && ground.hasUvs(0) && ground.hasUvs(1));
        assertEquals(0.5f, ground.uvs(1)[(24) * 2], 1e-6f, "the far corner of UV set 1");
        for (int t = 0; t < ground.triangleCount(); t++) {
            assertTrue(normalOf(ground, t)[1] > 0, "the ground faces up");
        }
        // Quad: a strip
        Mesh quad = g.toMesh(1, 0);
        assertEquals(2, quad.triangleCount());
        for (int t = 0; t < 2; t++) {
            assertTrue(normalOf(quad, t)[2] > 0, "strip triangle " + t + " faces +z");
        }
        // Hex: a fan without indices
        Mesh hex = g.toMesh(2, 0);
        assertEquals(6, hex.triangleCount());
        for (int t = 0; t < 6; t++) {
            assertTrue(normalOf(hex, t)[2] > 0, "fan triangle " + t);
        }
        // Bump: sparse accessor applied, the morph target and weights ignored
        Mesh bump = g.toMesh(3, 0);
        assertEquals(9, bump.vertexCount());
        assertEquals(1.0f, bump.positions()[4 * 3 + 1]);
        assertEquals(0.5f, bump.positions()[5 * 3 + 1]);
        assertEquals(0.25f, bump.positions()[7 * 3 + 1]);
        assertEquals(0f, bump.positions()[0 * 3 + 1]);
        assertEquals(0f, bump.positions()[8 * 3 + 1]);
        // the tree
        Mat4f[] w = g.worldMatrices();
        Vec3f hexPos = w[4].transformPosition(Vec3f.ZERO);
        assertEquals(6f, hexPos.x(), 1e-5f);
        assertEquals(1f, hexPos.y(), 1e-5f);
        Vec3f bumpPos = w[5].transformPosition(Vec3f.ZERO);
        assertEquals(-6f, bumpPos.x(), 1e-5f);
        // materials, textures and images, external and embedded
        Gltf.Material ground0 = g.material(0);
        assertEquals(0, ground0.baseColorTexture());
        assertEquals(1, ground0.normalTexture());
        assertEquals(0.5f, ground0.normalScale());
        assertEquals("MASK", g.material(1).alphaMode());
        assertTrue(g.material(1).doubleSided());
        assertArrayEquals(AssetFilesTest.resource("gltf/scene/albedo.png"), g.imageBytes(0), "the external image");
        assertArrayEquals(AssetFactory.sceneNormalMapPng(), g.imageBytes(1), "the data-URI image");
        BufferedImage normal = ImageIO.read(new ByteArrayInputStream(g.imageBytes(1)));
        assertEquals(8, normal.getWidth());
        assertEquals(0xFF8080FF, normal.getRGB(3, 3));
    }

    private static double[] normalOf(Mesh m, int t) {
        float[] p = m.positions();
        int[] idx = m.indices();
        int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
        double ex = p[b] - p[a], ey = p[b + 1] - p[a + 1], ez = p[b + 2] - p[a + 2], fx = p[c] - p[a], fy = p[c + 1] - p[a + 1], fz = p[c + 2] - p[a + 2];
        return new double[] {ey * fz - ez * fy, ez * fx - ex * fz, ex * fy - ey * fx};
    }

    // ---------------------------------------------------------------- the mesh tools on the real meshes

    /** The joint matrices of the tube bent by 40 degrees (clip "bend" at t = 1), in skeleton order, with the file's inverse bind matrices. */
    private static float[] bentJointMatrices(Gltf g) {
        Gltf.SkinData skin = g.skin(0);
        Pose pose = new Pose(skin.skeleton());
        pose.setToBind(skin.skeleton());
        new ClipSampler(g.clip(0, skin)).sample(1f, false, pose);
        float[] world = new float[16 * 4], joint = new float[16 * 4];
        Skinning.worldMatrices(skin.skeleton(), pose, world);
        for (int j = 0; j < 4; j++) {
            Mat4f.fromArray(world, j * 16).mul(Mat4f.fromArray(skin.inverseBindMatrices(), j * 16)).writeTo(joint, j * 16);
        }
        return joint;
    }

    /** Dense skin weights, one per skeleton joint, for every vertex of the tube. */
    private static float[] denseWeights(Gltf g, AssetFactory.Tube t) {
        int n = AssetFactory.TUBE_VERTICES;
        float[] dense = new float[n * 4];
        for (int v = 0; v < n; v++) {
            for (int k = 0; k < 4; k++) {
                dense[v * 4 + g.skin(0).skinToSkeleton()[t.joints()[v * 4 + k]]] += t.weights()[v * 4 + k];
            }
        }
        return dense;
    }

    /** Largest distance from the skinned vertices of {@code simple} (dense weights, one per skeleton joint) to the surface of the skinned original. */
    private static double skinnedDeviation(Gltf g, AssetFactory.Tube t, Mesh original, Mesh simple, float[] simpleDense) {
        float[] joint = bentJointMatrices(g);
        int n = simple.vertexCount();
        int[] joints = new int[n * 4];
        float[] weights = new float[n * 4];
        for (int v = 0; v < n; v++) {
            for (int k = 0; k < 4; k++) {
                joints[v * 4 + k] = k;
                weights[v * 4 + k] = simpleDense[v * 4 + k];
            }
        }
        float[] skinnedSimple = new float[n * 3];
        Skinning.skinPositions(joint, simple.positions(), joints, weights, n, skinnedSimple);
        float[] originalDense = denseWeights(g, t);
        int[] originalJoints = new int[original.vertexCount() * 4];
        for (int i = 0; i < originalJoints.length; i++) {
            originalJoints[i] = i % 4;
        }
        float[] skinnedOriginal = new float[original.vertexCount() * 3];
        Skinning.skinPositions(joint, original.positions(), originalJoints, originalDense, original.vertexCount(), skinnedOriginal);
        double worst = 0;
        for (int v = 0; v < n; v++) {
            Vec3f p = new Vec3f(skinnedSimple[v * 3], skinnedSimple[v * 3 + 1], skinnedSimple[v * 3 + 2]);
            double best = Double.MAX_VALUE;
            for (int tri = 0; tri < original.triangleCount(); tri++) {
                int a = original.indices()[tri * 3] * 3, b = original.indices()[tri * 3 + 1] * 3, c = original.indices()[tri * 3 + 2] * 3;
                Trianglef tr = Trianglef.of(new Vec3f(skinnedOriginal[a], skinnedOriginal[a + 1], skinnedOriginal[a + 2]),
                        new Vec3f(skinnedOriginal[b], skinnedOriginal[b + 1], skinnedOriginal[b + 2]), new Vec3f(skinnedOriginal[c], skinnedOriginal[c + 1], skinnedOriginal[c + 2]));
                best = Math.min(best, Intersectionf.pointTriangleDistanceSquared(p, tr));
            }
            worst = Math.max(worst, Math.sqrt(best));
        }
        return worst;
    }

    @Test
    void aSkinBlindSimplifierDestroysTheBentTubeAndTheSkinAwareOneDoesNot() throws IOException {
        AssetFactory.Tube t = AssetFactory.skinnedTube();
        Gltf g = load("gltf/skinned_tube.glb");
        Mesh original = g.toMesh(0, 0);
        float[] dense = denseWeights(g, t);
        for (int divisor : new int[] {2, 4}) {
            int target = original.triangleCount() / divisor;
            // 1. geometry only: a cylinder collapses along its length for free, so vertices with very different weights are merged
            Mesh blind = g.toMesh(0, 0);
            MeshSimplifier.Result rb = MeshSimplifier.simplify(blind, target, Float.MAX_VALUE, false);
            float[] blindWeights = new float[blind.vertexCount() * 4];
            for (int v = 0; v < blind.vertexCount(); v++) {
                System.arraycopy(dense, rb.remap()[v] * 4, blindWeights, v * 4, 4);
            }
            double blindDeviation = skinnedDeviation(g, t, original, blind, blindWeights);

            // 2. with the skinning weights as attributes
            Mesh aware = g.toMesh(0, 0);
            MeshSimplifier.Result ra = MeshSimplifier.simplify(aware, target, Float.MAX_VALUE, false, null, dense, 4, 0.0625f);
            assertEquals(aware.vertexCount() * 4, ra.attributes().length);
            for (int v = 0; v < aware.vertexCount(); v++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    assertTrue(ra.attributes()[v * 4 + k] >= -1e-6f);
                    sum += ra.attributes()[v * 4 + k];
                }
                assertEquals(1f, sum, 1e-4f, "the merged weights of vertex " + v + " still sum to 1");
            }
            double awareDeviation = skinnedDeviation(g, t, original, aware, ra.attributes());
            Report.println("TUBE-SIMPLIFY target " + target + " of " + original.triangleCount() + ": skin-blind reached " + rb.trianglesAfter()
                    + " triangles, skinned deviation " + blindDeviation + "; skin-aware reached " + ra.trianglesAfter() + " triangles, skinned deviation " + awareDeviation
                    + " (tube radius 0.25, length 1.5)");
            assertTrue(blindDeviation > 0.15, "without the weights the bent tube is wrecked (" + blindDeviation + "): this is why the attribute term exists");
            assertTrue(awareDeviation < 0.06, "with the weights the simplified tube stays within 0.06 of the bent original: " + awareDeviation);
            assertTrue(awareDeviation < blindDeviation / 5, "and at least five times closer");
            assertTrue(ra.trianglesAfter() < original.triangleCount(), "something was removed");
        }
    }

    @Test
    void meshletsHierarchyAndOverdrawOnTheRealMeshes() throws IOException {
        Gltf g = load("gltf/skinned_tube.glb");
        Mesh tube = g.toMesh(0, 0);
        Meshlets ml = Meshlets.build(tube, 64, 124);
        int covered = 0;
        for (int m = 0; m < ml.count(); m++) {
            covered += ml.triangleCount(m);
        }
        assertEquals(tube.triangleCount(), covered);
        ClusterHierarchy h = ClusterHierarchy.build(tube, 64, 124, 4);
        int[] out = new int[h.clusterCount()];
        int n = h.select(0f, 0f, 3f, 1000f, 0f, out);
        // the tube is developable, so its upper levels are exactly as good as the original (error 0): a zero budget may take them instead of the leaves
        assertTrue(h.triangles(out, n).length / 3 <= tube.triangleCount());
        for (int i = 0; i < n; i++) {
            assertEquals(0f, h.lodError(out[i]), "only lossless clusters at a zero budget");
        }
        for (int v : h.triangles(out, n)) {
            assertTrue(v >= 0 && v < h.vertices().vertexCount());
        }
        Overdraw.Result o = Overdraw.optimize(tube, 64, 1.05f);
        assertTrue(o.overdrawAfter() <= o.overdrawBefore() + 1e-6f);
        Mesh sphere = load("gltf/quantized_sphere.glb").toMesh(0, 0);
        UvAtlas.Result atlas = UvAtlas.generate(sphere, 1, 35f, 1024, 2);
        assertTrue(atlas.charts() > 1);
        for (float uv : sphere.uvs(1)) {
            assertTrue(uv >= -1e-6f && uv <= 1 + 1e-6f);
        }
        assertFalse(Float.isNaN(atlas.efficiency()));
        assertNotNull(atlas.remap());
    }
}
