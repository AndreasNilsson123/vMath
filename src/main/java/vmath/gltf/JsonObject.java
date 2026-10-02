package vmath.gltf;

import java.util.LinkedHashMap;

/** A JSON object as {@link Json} produces it. A class of its own (not a bare {@code Map}) so that {@code instanceof} gives a typed map and the glTF reader needs no unchecked cast. */
final class JsonObject extends LinkedHashMap<String, Object> {
    private static final long serialVersionUID = 1L;
}
