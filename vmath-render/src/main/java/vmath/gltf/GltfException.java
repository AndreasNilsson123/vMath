package vmath.gltf;

/** Thrown for a glTF file that is malformed, inconsistent, truncated or uses something this loader does not support. */
public final class GltfException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** A malformed or unsupported file; {@code message} says what and where. */
    public GltfException(String message) {
        super(message);
    }

    /** As {@link #GltfException(String)}, with the exception that caused it (for example an {@link java.io.IOException} from the URI resolver). */
    public GltfException(String message, Throwable cause) {
        super(message, cause);
    }
}
