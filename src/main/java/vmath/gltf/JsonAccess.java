package vmath.gltf;

import java.util.List;
import java.util.Map;

/** Typed reads of the parsed JSON tree for {@link Gltf}: every wrong shape is a {@link GltfException}, never a ClassCastException. */
final class JsonAccess {

    private JsonAccess() {
    }

    static Map<String, Object> obj(Object o, String what) {
        if (!(o instanceof JsonObject object)) {
            throw new GltfException(what + " must be an object");
        }
        return object;
    }

    static List<Object> list(Object o) {
        if (o == null) {
            return List.of();
        }
        if (!(o instanceof JsonArray array)) {
            throw new GltfException("expected an array");
        }
        return array;
    }

    static double num(Object o, String what) {
        if (!(o instanceof Double d)) {
            throw new GltfException(what + " must be a number");
        }
        return d;
    }

    static long lng(Map<String, Object> m, String key, long dflt) {
        Object o = m.get(key);
        if (o == null) {
            return dflt;
        }
        double d = num(o, key);
        if (d != Math.rint(d) || Math.abs(d) > 9.0e15) {
            throw new GltfException(key + " must be an integer: " + d);
        }
        return (long) d;
    }

    static double dbl(Map<String, Object> m, String key, double dflt) {
        Object o = m.get(key);
        return o == null ? dflt : num(o, key);
    }

    static String str(Map<String, Object> m, String key, String dflt) {
        Object o = m.get(key);
        if (o == null) {
            return dflt;
        }
        if (!(o instanceof String s)) {
            throw new GltfException(key + " must be a string");
        }
        return s;
    }

    static float[] floats(Object o, int n, float[] dflt, String what) {
        if (o == null) {
            return dflt;
        }
        List<Object> l = list(o);
        if (l.size() != n) {
            throw new GltfException(what + " needs " + n + " numbers, has " + l.size());
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) num(l.get(i), what);
        }
        return out;
    }
}
