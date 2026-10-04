package vmath.samples.demos.portals;

import java.util.ArrayDeque;
import vmath.bulk.BoundsArray;
import vmath.geo.Aabbf;
import vmath.spatial.PortalGraph;
import vmath.util.Rng;

/**
 * A generated building for portal culling: a square grid of rooms separated by walls, a maze of
 * doors between neighbouring rooms, furniture in every room, and the {@link PortalGraph} that
 * describes it.
 *
 * <p>The geometry is boxes in a {@link BoundsArray}. Room {@code (i, j)} has an interior of 10 by 4
 * by 10 metres at {@code x = 12 i + 1 .. 12 i + 11}; the walls are the 2 metre slots in between
 * (slot {@code k} is at {@code 12 k - 1 .. 12 k + 1}), with a door, a 2 by 3 metre opening in the
 * middle of the wall, where the maze says so. Sectors are the rooms and one small box per door, so
 * the portals that {@code autoPortals} finds are exactly the door openings. A door has a slab
 * (an object) that fills it when the door is shut.
 *
 * <p>The objects are in this order: the structure (floors, ceilings, walls, lintels, pillars), the
 * door slabs, then the furniture. {@link #structureEnd()} and {@link #slabEnd()} give the
 * boundaries, which the demo uses to tint the three groups. Every object overlaps the sectors it
 * belongs to by a few centimetres, so that walls, floors and slabs are members of both rooms they
 * touch.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: the door states change at run time and are read and
 * written by the render thread only.
 */
final class Building {

    static final float PITCH = 12f;
    static final float HEIGHT = 4f;
    static final float DOOR_HEIGHT = 3f;
    private static final float OVERLAP = 0.05f;

    private final int side;
    private final BoundsArray bounds = new BoundsArray(1024);
    private final PortalGraph graph;
    private final float[] sectorBoxes;
    private final int roomCount;
    private final int doorCount;
    private final int[] doorSector;
    private final int[] doorSlab;
    private final float[] doorX;
    private final float[] doorZ;
    private final boolean[] doorOpen;
    private final int structureEnd;
    private final int slabEnd;

    private Building(int side, int props, long seed, int pathColumn) {
        this.side = side;
        this.roomCount = side * side;
        Rng rng = new Rng(seed);
        // doors: a random spanning tree of the rooms (a maze), a quarter of the other walls, and the walls on the scripted path
        boolean[][] doorX = new boolean[side + 1][side]; // slot k between rooms (k-1, j) and (k, j)
        boolean[][] doorZ = new boolean[side][side + 1]; // slot k between rooms (i, k-1) and (i, k)
        boolean[] seen = new boolean[roomCount];
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.push(0);
        seen[0] = true;
        while (!stack.isEmpty()) {
            int room = stack.peek();
            int i = room % side, j = room / side;
            int[] order = {0, 1, 2, 3};
            for (int a = 3; a > 0; a--) {
                int b = rng.nextInt(a + 1);
                int t = order[a];
                order[a] = order[b];
                order[b] = t;
            }
            boolean moved = false;
            for (int d : order) {
                int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (ni < 0 || nj < 0 || ni >= side || nj >= side || seen[nj * side + ni]) {
                    continue;
                }
                seen[nj * side + ni] = true;
                if (d == 0) {
                    doorX[i + 1][j] = true;
                } else if (d == 1) {
                    doorX[i][j] = true;
                } else if (d == 2) {
                    doorZ[i][j + 1] = true;
                } else {
                    doorZ[i][j] = true;
                }
                stack.push(nj * side + ni);
                moved = true;
                break;
            }
            if (!moved) {
                stack.pop();
            }
        }
        for (int k = 1; k < side; k++) {
            for (int j = 0; j < side; j++) {
                doorX[k][j] |= rng.nextDouble() < 0.25;
                doorZ[j][k] |= rng.nextDouble() < 0.25;
            }
        }
        for (int k = 1; k < side; k++) {
            doorZ[pathColumn][k] = true;
        }

        // sectors: the rooms, then the door boxes
        PortalGraph.Builder builder = PortalGraph.builder();
        sectorBoxesList = new java.util.ArrayList<>();
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                addSector(builder, i * PITCH + 1f, 0f, j * PITCH + 1f, i * PITCH + 11f, HEIGHT, j * PITCH + 11f);
            }
        }
        java.util.ArrayList<float[]> doors = new java.util.ArrayList<>();
        for (int k = 1; k < side; k++) {
            for (int j = 0; j < side; j++) {
                if (doorX[k][j]) {
                    float zc = j * PITCH + 6f, xc = k * PITCH;
                    doors.add(new float[] {xc, zc, 0f});
                    addSector(builder, xc - 1f, 0f, zc - 1f, xc + 1f, DOOR_HEIGHT, zc + 1f);
                }
                if (doorZ[j][k]) {
                    float xc = j * PITCH + 6f, zc = k * PITCH;
                    doors.add(new float[] {xc, zc, 1f});
                    addSector(builder, xc - 1f, 0f, zc - 1f, xc + 1f, DOOR_HEIGHT, zc + 1f);
                }
            }
        }
        doorCount = doors.size();
        builder.autoPortals(0.01f);
        graph = builder.build();
        sectorBoxes = new float[sectorBoxesList.size() * 6];
        for (int s = 0; s < sectorBoxesList.size(); s++) {
            System.arraycopy(sectorBoxesList.get(s), 0, sectorBoxes, s * 6, 6);
        }

        // structure
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                float x0 = i * PITCH + 1f, z0 = j * PITCH + 1f;
                box(x0, -0.4f, z0, x0 + 10f, 0f, z0 + 10f);
                box(x0, HEIGHT, z0, x0 + 10f, HEIGHT + 0.4f, z0 + 10f);
            }
        }
        for (int k = 0; k <= side; k++) {
            for (int j = 0; j < side; j++) {
                float zc = j * PITCH + 6f, z0 = j * PITCH + 1f, z1 = j * PITCH + 11f, x = k * PITCH;
                if (k > 0 && k < side && doorX[k][j]) {
                    box(x - 1f, 0f, z0, x + 1f, HEIGHT, zc - 1f);
                    box(x - 1f, 0f, zc + 1f, x + 1f, HEIGHT, z1);
                    box(x - 1f, DOOR_HEIGHT, zc - 1f, x + 1f, HEIGHT, zc + 1f);
                } else {
                    box(x - 1f, 0f, z0, x + 1f, HEIGHT, z1);
                }
                float xc = j * PITCH + 6f, x0 = j * PITCH + 1f, x1 = j * PITCH + 11f, z = k * PITCH;
                if (k > 0 && k < side && doorZ[j][k]) {
                    box(x0, 0f, z - 1f, xc - 1f, HEIGHT, z + 1f);
                    box(xc + 1f, 0f, z - 1f, x1, HEIGHT, z + 1f);
                    box(xc - 1f, DOOR_HEIGHT, z - 1f, xc + 1f, HEIGHT, z + 1f);
                } else {
                    box(x0, 0f, z - 1f, x1, HEIGHT, z + 1f);
                }
            }
        }
        for (int kz = 0; kz <= side; kz++) {
            for (int kx = 0; kx <= side; kx++) {
                box(kx * PITCH - 1f, 0f, kz * PITCH - 1f, kx * PITCH + 1f, HEIGHT, kz * PITCH + 1f);
            }
        }
        structureEnd = bounds.size();

        // door slabs
        doorSector = new int[doorCount];
        doorSlab = new int[doorCount];
        this.doorX = new float[doorCount];
        this.doorZ = new float[doorCount];
        doorOpen = new boolean[doorCount];
        for (int d = 0; d < doorCount; d++) {
            float[] door = doors.get(d);
            doorSector[d] = roomCount + d;
            this.doorX[d] = door[0];
            this.doorZ[d] = door[1];
            doorOpen[d] = true;
            if (door[2] == 0f) {
                doorSlab[d] = box(door[0] - 1f, 0f, door[1] - 0.95f, door[0] + 1f, DOOR_HEIGHT - 0.05f, door[1] + 0.95f);
            } else {
                doorSlab[d] = box(door[0] - 0.95f, 0f, door[1] - 1f, door[0] + 0.95f, DOOR_HEIGHT - 0.05f, door[1] + 1f);
            }
        }
        slabEnd = bounds.size();

        // furniture
        for (int room = 0; room < roomCount; room++) {
            int i = room % side, j = room / side;
            for (int p = 0; p < props; p++) {
                float w = 0.4f + (float) rng.nextDouble() * 0.9f, d = 0.4f + (float) rng.nextDouble() * 0.9f, h = 0.3f + (float) rng.nextDouble() * 1.9f;
                float x = i * PITCH + 1f + w * 0.5f + (float) rng.nextDouble() * (10f - w), z = j * PITCH + 1f + d * 0.5f + (float) rng.nextDouble() * (10f - d);
                bounds.add(x - w * 0.5f, 0f, z - d * 0.5f, x + w * 0.5f, h, z + d * 0.5f);
            }
        }
        graph.assignAll(bounds);
    }

    private java.util.List<float[]> sectorBoxesList;

    private void addSector(PortalGraph.Builder builder, float x0, float y0, float z0, float x1, float y1, float z1) {
        builder.addBox(new Aabbf(x0, y0, z0, x1, y1, z1));
        sectorBoxesList.add(new float[] {x0, y0, z0, x1, y1, z1});
    }

    /**
     * Adds a box to the scene, a few centimetres larger than given in every direction so that it
     * overlaps the sectors it touches.
     */
    private int box(float x0, float y0, float z0, float x1, float y1, float z1) {
        return bounds.add(x0 - OVERLAP, y0 - OVERLAP, z0 - OVERLAP, x1 + OVERLAP, y1 + OVERLAP, z1 + OVERLAP);
    }

    /**
     * Generates a building.
     *
     * @param side the number of rooms along one side; at least 2
     * @param props the number of pieces of furniture per room; at least 0
     * @param seed the seed of the maze and the furniture
     * @return the building
     */
    static Building generate(int side, int props, long seed) {
        return new Building(side, props, seed, side / 2);
    }

    BoundsArray bounds() {
        return bounds;
    }

    PortalGraph graph() {
        return graph;
    }

    int side() {
        return side;
    }

    int roomCount() {
        return roomCount;
    }

    int sectorCount() {
        return roomCount + doorCount;
    }

    int doorCount() {
        return doorCount;
    }

    /**
     * Reads the end of the structure objects, which are the first in the bounds.
     *
     * @return the index one past the last structure object
     */
    int structureEnd() {
        return structureEnd;
    }

    /**
     * Reads the end of the door slabs, which follow the structure.
     *
     * @return the index one past the last door slab
     */
    int slabEnd() {
        return slabEnd;
    }

    /**
     * Reads the bounds of a sector.
     *
     * @param sector the sector index
     * @param out receives {@code minX, minY, minZ, maxX, maxY, maxZ}
     */
    void sectorBox(int sector, float[] out) {
        System.arraycopy(sectorBoxes, sector * 6, out, 0, 6);
    }

    boolean isDoorOpen(int door) {
        return doorOpen[door];
    }

    int doorSlab(int door) {
        return doorSlab[door];
    }

    int doorSector(int door) {
        return doorSector[door];
    }

    /**
     * Opens or shuts a door: both of its portals change, so the rooms on either side stop seeing
     * each other through it, and its slab is drawn when it is shut.
     *
     * @param door the door index
     * @param open whether the door is open
     */
    void setDoorOpen(int door, boolean open) {
        doorOpen[door] = open;
        int s = doorSector[door];
        for (int k = 0; k < graph.portalsOf(s); k++) {
            graph.setPortalOpen(graph.portalOf(s, k), open);
        }
    }

    /**
     * Finds the door closest to a point in the horizontal plane.
     *
     * @param x the x coordinate
     * @param z the z coordinate
     * @return the door index, or -1 if there is no door
     */
    int nearestDoor(float x, float z) {
        int best = -1;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (int d = 0; d < doorCount; d++) {
            float dx = doorX[d] - x, dz = doorZ[d] - z;
            float dist = dx * dx + dz * dz;
            if (dist < bestDistance) {
                bestDistance = dist;
                best = d;
            }
        }
        return best;
    }
}
