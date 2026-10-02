package vmath.gltf;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vmath.anim.Skeleton;
import vmath.core.Mat4f;
import vmath.gltf.Gltf.AccessorInfo;
import vmath.gltf.Gltf.Node;
import vmath.gltf.Gltf.SkinData;
import static vmath.gltf.JsonAccess.*;

/** Builds the {@link Gltf.SkinData} of a skin: joint ordering, bind pose, inverse bind matrices and the transform above the skeleton. */
final class GltfSkins {

    private GltfSkins() {
    }

    static SkinData build(Gltf g, int index) {
        List<Object> skins = list(g.root().get("skins"));
        Map<String, Object> s = obj(skins.get(index), "skins[" + index + "]");
        List<Object> jl = list(s.get("joints"));
        int n = jl.size();
        if (n < 1) {
            throw new GltfException("skins[" + index + "] has no joints");
        }
        int[] jointNode = new int[n];
        Map<Integer, Integer> slot = new HashMap<>();
        for (int k = 0; k < n; k++) {
            jointNode[k] = (int) num(jl.get(k), "joint");
            if (jointNode[k] < 0 || jointNode[k] >= g.nodeCount() || slot.put(jointNode[k], k) != null) {
                throw new GltfException("skins[" + index + "] has a bad or repeated joint node " + jointNode[k]);
            }
        }
        int[] nodeParent = g.parents();
        // the parent of a joint is its direct parent node when that is a joint too; a joint whose nearest joint ancestor is further up has a node in between
        int[] parentSlot = new int[n];
        int[] depth = new int[n];
        for (int k = 0; k < n; k++) {
            int direct = nodeParent[jointNode[k]];
            parentSlot[k] = direct >= 0 && slot.containsKey(direct) ? slot.get(direct) : -1;
            int up = direct;
            while (parentSlot[k] < 0 && up >= 0) {
                if (slot.containsKey(up)) {
                    throw new GltfException("skins[" + index + "]: a node that is not a joint sits between joints " + up + " and " + jointNode[k]);
                }
                up = nodeParent[up];
            }
        }
        for (int k = 0; k < n; k++) {
            int d = 0, cur = k;
            while (parentSlot[cur] >= 0) {
                cur = parentSlot[cur];
                d++;
            }
            depth[k] = d;
        }
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) {
            order[k] = k;
        }
        Arrays.sort(order, (a, b) -> depth[a] != depth[b] ? Integer.compare(depth[a], depth[b]) : Integer.compare(a, b));
        int[] skinToSkeleton = new int[n];
        for (int j = 0; j < n; j++) {
            skinToSkeleton[order[j]] = j;
        }
        int[] parents = new int[n];
        int[] nodesInOrder = new int[n];
        float[] bind = new float[n * 10];
        String[] names = new String[n];
        for (int j = 0; j < n; j++) {
            int k = order[j];
            parents[j] = parentSlot[k] < 0 ? -1 : skinToSkeleton[parentSlot[k]];
            nodesInOrder[j] = jointNode[k];
            Node node = g.node(jointNode[k]);
            names[j] = node.name() != null ? node.name() : "joint" + jointNode[k];
            float[] t = node.translation(), r = node.rotation(), sc = node.scale();
            if (node.matrix() != null) {
                Mat4f.Trs trs = Mat4f.fromArray(node.matrix(), 0).decompose();
                t = new float[] {trs.translation().x(), trs.translation().y(), trs.translation().z()};
                r = new float[] {trs.rotation().x(), trs.rotation().y(), trs.rotation().z(), trs.rotation().w()};
                sc = new float[] {trs.scale().x(), trs.scale().y(), trs.scale().z()};
            }
            System.arraycopy(t, 0, bind, j * 10, 3);
            System.arraycopy(r, 0, bind, j * 10 + 3, 4);
            System.arraycopy(sc, 0, bind, j * 10 + 7, 3);
        }
        Skeleton skeleton;
        try {
            skeleton = new Skeleton(parents, bind, names);
        } catch (IllegalArgumentException e) {
            throw new GltfException("skins[" + index + "]: " + e.getMessage(), e);
        }
        float[] ibm = null;
        if (s.get("inverseBindMatrices") != null) {
            int acc = g.accessorRef(s.get("inverseBindMatrices"), "inverseBindMatrices");
            AccessorInfo info = g.accessorInfo(acc);
            if (!info.type().equals("MAT4") || info.count() < n) {
                throw new GltfException("skins[" + index + "]: inverseBindMatrices must be MAT4 with at least one per joint");
            }
            float[] raw = g.readFloats(acc);
            ibm = new float[n * 16];
            for (int k = 0; k < n; k++) {
                System.arraycopy(raw, k * 16, ibm, skinToSkeleton[k] * 16, 16);
            }
        }
        // the world matrix above the skeleton: every root joint must hang from the same place
        Mat4f rootTransform = Mat4f.IDENTITY;
        Mat4f[] worlds = null;
        boolean first = true;
        for (int k = 0; k < n; k++) {
            if (parentSlot[k] >= 0) {
                continue;
            }
            int above = nodeParent[jointNode[k]];
            if (above >= 0 && worlds == null) {
                worlds = g.worldMatrices();
            }
            Mat4f here = above >= 0 ? worlds[above] : Mat4f.IDENTITY;
            if (first) {
                rootTransform = here;
                first = false;
            } else if (!rootTransform.approxEquals(here, 1e-4f)) {
                throw new GltfException("skins[" + index + "]: the root joints hang below different transforms");
            }
        }
        SkinData data = new SkinData(str(s, "name", null), skeleton, nodesInOrder, skinToSkeleton, ibm, rootTransform);
        return data;
    }
}
