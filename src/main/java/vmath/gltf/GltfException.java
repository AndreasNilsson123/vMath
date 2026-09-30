package vmath.gltf;

/** Thrown for a glTF file that is malformed, inconsistent, truncated or uses something this loader does not support. */
public final class GltfException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GltfException(String message) {
        super(message);
    }

    public GltfException(String message, Throwable cause) {
        super(message, cause);
    }
}
