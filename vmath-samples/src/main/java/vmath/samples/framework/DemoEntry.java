package vmath.samples.framework;

import java.util.List;
import java.util.function.Function;

/**
 * A registered demo: its description and the way to make a fresh instance of it.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads, provided that the
 * factory is.
 *
 * @param info the description of the demo; must not be {@code null}
 * @param factory makes a new demo from the demo's own command-line arguments, or throws
 *     {@link IllegalArgumentException} with a message that explains what is wrong with them; must
 *     not be {@code null}
 */
public record DemoEntry(DemoInfo info, Function<List<String>, Demo> factory) {
}
