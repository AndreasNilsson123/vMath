package vmath.samples.framework;

import java.util.List;

/**
 * The description of a demo: what it is called, what strength of the library it shows, and what the
 * smoke run may expect of it.
 *
 * <p>Internal: part of the samples, not of the library. Every demo has one, registered in
 * {@code vmath.samples.Demos}, and a card in {@code docs/DEMOS.md} that a test checks.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads.
 *
 * @param id the short name used on the command line and in the run configurations, lower case with
 *     hyphens; must not be {@code null}
 * @param title the name shown in the menu and the title bar; must not be {@code null}
 * @param claim one sentence that says which strength the demo shows; must not be {@code null}
 * @param tags the themes of the demo, such as {@code culling}, {@code animation}, {@code physics}
 *     or {@code rendering}; must not be {@code null}
 * @param allocationBudget the most that the render thread may allocate per frame in
 *     {@link Demo#update} and {@link Demo#render}, in bytes, as an average over the measured
 *     frames; the smoke run fails above it
 * @param smokeArgs the demo arguments of the smoke run, which should make the demo small enough to
 *     start in a second or two; must not be {@code null}
 * @param controls one line that lists the keys and mouse buttons of the demo, shown in the help
 *     line of the window; must not be {@code null}
 */
public record DemoInfo(String id, String title, String claim, List<String> tags, long allocationBudget, List<String> smokeArgs, String controls) {

    /**
     * Creates the description and copies the lists.
     *
     * @throws IllegalArgumentException if the id is empty or has anything but lower-case letters,
     *     digits and hyphens
     */
    public DemoInfo {
        if (!id.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("a demo id is lower case words joined with hyphens: " + id);
        }
        tags = List.copyOf(tags);
        smokeArgs = List.copyOf(smokeArgs);
    }
}
