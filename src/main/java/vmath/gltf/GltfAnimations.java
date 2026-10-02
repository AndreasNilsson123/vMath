package vmath.gltf;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vmath.anim.AnimationClip;
import vmath.core.Mat4f;
import vmath.gltf.Gltf.SkinData;
import static vmath.gltf.ClipResampling.*;
import static vmath.gltf.JsonAccess.*;

/** Converts a glTF animation into an {@link AnimationClip} for the skeleton of a skin. */
final class GltfAnimations {

    private GltfAnimations() {
    }

    static AnimationClip clip(Gltf g, int animation, SkinData skin, float cubicRate) {
        if (!(cubicRate > 0f)) {
            throw new IllegalArgumentException("cubicRate must be positive: " + cubicRate);
        }
        Map<String, Object> an = obj(list(g.root().get("animations")).get(animation), "animations[" + animation + "]");
        List<Object> samplerList = list(an.get("samplers"));
        Map<Integer, Integer> jointOfNode = new HashMap<>();
        for (int j = 0; j < skin.jointNodes().length; j++) {
            jointOfNode.put(skin.jointNodes()[j], j);
        }
        AnimationClip.Builder builder = AnimationClip.builder(skin.skeleton().jointCount());
        Set<String> seen = new HashSet<>();
        for (Object co : list(an.get("channels"))) {
            Map<String, Object> ch = obj(co, "channel");
            Map<String, Object> target = obj(ch.get("target"), "channel target");
            String path = str(target, "path", "");
            int node = (int) lng(target, "node", -1);
            Integer joint = jointOfNode.get(node);
            AnimationClip.Channel channel = switch (path) {
                case "translation" -> AnimationClip.Channel.TRANSLATION;
                case "rotation" -> AnimationClip.Channel.ROTATION;
                case "scale" -> AnimationClip.Channel.SCALE;
                default -> null;
            };
            if (joint == null || channel == null) {
                continue;
            }
            if (!seen.add(joint + ":" + path)) {
                throw new GltfException("animations[" + animation + "] has two channels for " + path + " of node " + node);
            }
            int si = (int) lng(ch, "sampler", -1);
            if (si < 0 || si >= samplerList.size()) {
                throw new GltfException("a channel refers to sampler " + si + " which does not exist");
            }
            Map<String, Object> sm = obj(samplerList.get(si), "animation sampler");
            String interpolation = str(sm, "interpolation", "LINEAR");
            float[] times = g.readFloats(g.accessorRef(sm.get("input"), "sampler input"));
            float[] values = g.readFloats(g.accessorRef(sm.get("output"), "sampler output"));
            int comps = channel.components();
            int perKey = interpolation.equals("CUBICSPLINE") ? 3 : 1;
            if (values.length != times.length * comps * perKey) {
                throw new GltfException("animation sampler output has " + values.length + " values for " + times.length + " keys of " + interpolation + " "
                        + path);
            }
            float[][] converted = switch (interpolation) {
                case "LINEAR" -> new float[][] {times, values};
                case "STEP" -> stepToLinear(times, values, comps);
                case "CUBICSPLINE" -> resampleCubic(times, values, comps, cubicRate);
                default -> throw new GltfException("unknown interpolation " + interpolation);
            };
            try {
                builder.track(joint, channel, converted[0], converted[1]);
            } catch (IllegalArgumentException e) {
                throw new GltfException("animations[" + animation + "]: " + e.getMessage(), e);
            }
        }
        return builder.build();
    }
}
