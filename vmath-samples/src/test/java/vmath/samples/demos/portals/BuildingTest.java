package vmath.samples.demos.portals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.spatial.CullContext;
import vmath.spatial.PortalGraph;
import vmath.spatial.PortalStage;

/**
 * Tests of the generated building: its sectors and portals, the maze of doors, the assignment of
 * the objects to the rooms, and what the portal culling does with an open and a shut door.
 *
 * <p><b>Thread safety.</b> Each test builds its own building; the tests may run in parallel.
 */
class BuildingTest {

    @Test
    void everyDoorHasTwoPortalsAndEveryRoomCanBeReached() {
        Building b = Building.generate(6, 5, 3);
        PortalGraph g = b.graph();
        assertEquals(36, b.roomCount());
        assertEquals(36 + b.doorCount(), b.sectorCount());
        assertEquals(2 * b.doorCount(), g.portalCount(), "a door joins a room to the door box on each side");
        boolean[] seen = new boolean[b.sectorCount()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        seen[0] = true;
        while (!queue.isEmpty()) {
            int s = queue.poll();
            for (int k = 0; k < g.portalsOf(s); k++) {
                int other = g.otherSector(g.portalOf(s, k), s);
                if (!seen[other]) {
                    seen[other] = true;
                    queue.add(other);
                }
            }
        }
        for (int room = 0; room < b.roomCount(); room++) {
            assertTrue(seen[room], "room " + room + " is not connected: the doors must form a spanning maze");
        }
    }

    @Test
    void theSameSeedMakesTheSameBuildingAndAnotherSeedADifferentOne() {
        Building a = Building.generate(5, 4, 9), b = Building.generate(5, 4, 9), c = Building.generate(5, 4, 10);
        assertEquals(a.doorCount(), b.doorCount());
        assertEquals(a.bounds().size(), b.bounds().size());
        boolean differs = a.doorCount() != c.doorCount() || a.bounds().size() != c.bounds().size();
        for (int i = 0; i < a.bounds().size(); i++) {
            assertEquals(a.bounds().get(i), b.bounds().get(i));
            if (i < c.bounds().size() && !a.bounds().get(i).equals(c.bounds().get(i))) {
                differs = true;
            }
        }
        assertTrue(differs, "another seed gives another maze or other furniture");
    }

    @Test
    void theObjectsAreInTheOrderStructureSlabsFurnitureAndAllBelongToARoom() {
        Building b = Building.generate(4, 6, 1);
        BoundsArray bounds = b.bounds();
        assertTrue(b.structureEnd() > 0 && b.structureEnd() < b.slabEnd());
        assertEquals(b.structureEnd() + b.doorCount(), b.slabEnd());
        assertEquals(b.slabEnd() + 16 * 6, bounds.size());
        for (int i = 0; i < bounds.size(); i++) {
            assertTrue(b.graph().isAssigned(i), "object " + i + " belongs to no sector and would never be culled");
        }
        for (int d = 0; d < b.doorCount(); d++) {
            assertTrue(b.doorSlab(d) >= b.structureEnd() && b.doorSlab(d) < b.slabEnd());
        }
    }

    @Test
    void shuttingADoorClosesBothOfItsPortals() {
        Building b = Building.generate(4, 2, 1);
        int d = 0;
        int s = b.doorSector(d);
        assertTrue(b.isDoorOpen(d));
        b.setDoorOpen(d, false);
        assertFalse(b.isDoorOpen(d));
        assertEquals(2, b.graph().portalsOf(s));
        for (int k = 0; k < 2; k++) {
            assertFalse(b.graph().isPortalOpen(b.graph().portalOf(s, k)));
        }
        b.setDoorOpen(d, true);
        for (int k = 0; k < 2; k++) {
            assertTrue(b.graph().isPortalOpen(b.graph().portalOf(s, k)));
        }
    }

    @Test
    void theNearestDoorIsTheOneAtThePoint() {
        Building b = Building.generate(5, 0, 2);
        float[] box = new float[6];
        for (int d = 0; d < b.doorCount(); d++) {
            b.sectorBox(b.doorSector(d), box);
            assertEquals(d, b.nearestDoor((box[0] + box[3]) * 0.5f, (box[2] + box[5]) * 0.5f));
        }
    }

    @Test
    void portalCullingLeavesFewerObjectsThanTheFrustumAndHidesWhatIsBehindAShutDoor() {
        Building b = Building.generate(6, 20, 4);
        BoundsArray bounds = b.bounds();
        float x = 3 * Building.PITCH + 6f;
        Cameraf cam = Cameraf.lookingAt(new Vec3f(x, 1.7f, 6f), new Vec3f(x, 1.7f, 60f), Vec3f.UNIT_Y, 1.0f, 16f / 9f, 0.1f, 400f, DepthRange.NEGATIVE_ONE_TO_ONE);
        CullContext ctx = CullContext.perspective(cam.frustum(), cam.position(), 1.0f, 900);
        PortalStage stage = new PortalStage(b.graph());

        VisibilitySet open = new VisibilitySet(bounds.size());
        open.setAll(bounds.size());
        stage.setView(cam.viewProjection(), DepthRange.NEGATIVE_ONE_TO_ONE).cull(ctx, bounds, open);
        assertTrue(stage.lastSector() >= 0 && stage.lastSector() < b.roomCount(), "the camera is in a room");
        int withDoors = open.count();
        assertTrue(withDoors < bounds.size() / 2, "the portals alone remove most of the building: " + withDoors + " of " + bounds.size());

        for (int d = 0; d < b.doorCount(); d++) {
            b.setDoorOpen(d, false);
        }
        VisibilitySet shut = new VisibilitySet(bounds.size());
        shut.setAll(bounds.size());
        stage.cull(ctx, bounds, shut);
        assertTrue(shut.count() < withDoors, "with every door shut the camera sees only its own room: " + shut.count() + " against " + withDoors);
    }

    @Test
    void aBuildingNeedsAtLeastTwoRoomsPerSideInTheOptionsAndTheOptionsParse() {
        assertEquals(20, PortalsOptions.parse(List.of()).rooms());
        assertEquals(150, PortalsOptions.parse(List.of()).props());
        PortalsOptions o = PortalsOptions.parse(List.of("--rooms", "7", "--props", "3", "--overlay"));
        assertEquals(7, o.rooms());
        assertEquals(3, o.props());
        assertTrue(o.overlay());
        assertThrows(IllegalArgumentException.class, () -> PortalsOptions.parse(List.of("--rooms", "1")));
        assertThrows(IllegalArgumentException.class, () -> PortalsOptions.parse(List.of("--rooms")));
        assertThrows(IllegalArgumentException.class, () -> PortalsOptions.parse(List.of("--what")));
    }
}
