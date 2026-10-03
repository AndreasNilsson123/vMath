package vmath.core;

import java.util.Objects;

/**
 * The name of a coordinate frame (world, ship, camera, a planet's surface), which a
 * {@link FrameTransformf} carries so that transforms between the wrong frames cannot be composed
 * by accident.
 *
 * <p>Two frames are the same when their names are equal; the name is the identity, so frames made
 * in different places for the same coordinate system agree.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Frame world = Frame.of("world");
 * Frame ship = Frame.of("ship");
 * boolean same = ship.equals(Frame.of("ship"));                               // true
 * }</pre>
 *
 * @param name the name of the frame, not empty; must not be {@code null}
 */
public record Frame(String name) {

    /**
     * Checks that the name is usable.
     *
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is empty
     */
    public Frame {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a frame needs a name");
        }
    }

    /**
     * Creates the frame with a name.
     *
     * @param name the name of the frame, not empty; must not be {@code null}
     * @return the frame of that name
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is empty
     */
    public static Frame of(String name) {
        return new Frame(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
