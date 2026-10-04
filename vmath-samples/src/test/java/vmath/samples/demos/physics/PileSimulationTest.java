package vmath.samples.demos.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests of the pile simulation without a window: bodies stay in the pit, come close to rest, never
 * become non-numbers, and the same seed gives the same pile.
 *
 * <p><b>Thread safety.</b> Each test builds its own simulation; the tests may run in parallel.
 */
class PileSimulationTest {

    private static PileSimulation run(int bodies, int steps) {
        PileSimulation sim = new PileSimulation(bodies);
        for (int s = 0; s < steps; s++) {
            if (s % 2 == 0) {
                sim.spawn();
            }
            sim.step();
        }
        return sim;
    }

    @Test
    void theBodiesStayInThePitAndDoNotFallThroughTheFloor() {
        PileSimulation sim = run(120, 1200);
        assertEquals(120, sim.dynamicCount());
        assertEquals(0, sim.lost(), "every body is above the floor and inside the walls");
        assertTrue(sim.contacts() > 0, "a pile has contacts");
    }

    @Test
    void thePileLosesMostOfItsEnergyAndNothingExplodes() {
        PileSimulation sim = run(80, 240);
        double early = 0;
        for (int i = 0; i < 4; i++) {
            sim.step();
        }
        for (int s = 0; s < 1200; s++) {
            sim.step();
            early = Math.max(early, sim.kineticEnergy());
        }
        assertTrue(Double.isFinite(early));
        double rest = sim.kineticEnergy();
        double mass = 0;
        for (int i = sim.staticCount(); i < sim.bodyCount(); i++) {
            mass += sim.body(i).mass();
        }
        assertTrue(rest < 0.5 * mass, "the pile is nearly at rest: kinetic energy " + rest + " J for " + mass + " kg");
    }

    @Test
    void theSameSeedGivesTheSamePile() {
        PileSimulation a = run(40, 300), b = run(40, 300);
        for (int i = a.staticCount(); i < a.bodyCount(); i++) {
            assertEquals(a.body(i).px, b.body(i).px, 0.0);
            assertEquals(a.body(i).py, b.body(i).py, 0.0);
            assertEquals(a.body(i).pz, b.body(i).pz, 0.0);
        }
    }

    @Test
    void spawnStopsAtTheCapacity() {
        PileSimulation sim = new PileSimulation(3);
        assertTrue(sim.spawn() && sim.spawn() && sim.spawn());
        assertTrue(!sim.spawn());
        assertEquals(3, sim.dynamicCount());
    }
}
