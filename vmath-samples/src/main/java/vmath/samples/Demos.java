package vmath.samples;

import java.util.List;
import vmath.samples.demos.city.CityDemo;
import vmath.samples.framework.DemoEntry;

/**
 * The registry of the demos: the list that the launcher, the menu, the smoke run and the tests of
 * the cards read.
 *
 * <p>To add a demo, write its class in {@code vmath.samples.demos.<id>}, give it a public
 * {@code DemoInfo} constant, add an entry here, a card to {@code docs/DEMOS.md} and a run
 * configuration (the registry test checks the last two).
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: the method may be called from any number of threads.
 */
public final class Demos {

    private Demos() {
    }

    /**
     * Lists the registered demos in menu order.
     *
     * @return an unmodifiable list; never {@code null} or empty
     */
    public static List<DemoEntry> all() {
        return List.of(new DemoEntry(CityDemo.INFO, CityDemo::new));
    }
}
