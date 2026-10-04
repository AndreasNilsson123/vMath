  package vmath.samples.demos.terrain;

import java.util.stream.IntStream;
import vmath.bulk.BoundsArray;
import vmath.mesh.Mesh;
import vmath.mesh.MeshLod;
import vmath.util.Noise;

/**
 * A square terrain of fractal noise cut into chunks, each chunk a grid mesh that is simplified into
 * a chain of levels of detail.
 *
 * <p>The height at a point is a function of the position only ({@link #height}), made of simplex
 * noise ({@code Noise.fbm2}) shaped so that there are broad valleys and sharp ridges, so any chunk
 * can be built independently and neighbouring chunks agree along their shared edge. A chunk is a
 * grid of {@code cells x cells} squares of two triangles each in world coordinates. The chain of a
 * chunk comes from {@code MeshLod.build} with the border locked: the vertices on the edge of the
 * chunk are kept at every level, so neighbours that are drawn at different levels still meet
 * without cracks.
 *
 * <p>The class does not use OpenGL. The chains are built on the common fork-join pool, one chunk
 * per task.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable once {@link #buildChains} has returned, so it can be read by
 * several threads; {@link #buildChains} itself must be called once, from one thread.
 */
final class Terrain {

    private static final int SEED = 33;
    private static final double AMPLITUDE = 340.0;
    private static final double FREQUENCY = 1.0 / 900.0;

    private final int chunks;
    private final int cells;
    private final float chunkSize;
    private final float origin;
    private final MeshLod.Chain[] chain;
    private final BoundsArray bounds;
    private final float radius;
    private int levels;

    /**
     * Creates the terrain and the bounds of its chunks; the meshes are made by {@link #buildChains}.
     *
     * @param chunks the number of chunks along each side; at least 1
     * @param cells the number of grid cells along the side of a chunk; at least 4
     * @param chunkSize the side of a chunk in metres; positive
     */
    Terrain(int chunks, int cells, float chunkSize) {
        if (chunks < 1 || cells < 4 || !(chunkSize > 0f)) {
            throw new IllegalArgumentException("a terrain needs chunks, at least 4 cells per chunk and a positive chunk size: " + chunks + ", " + cells + ", " + chunkSize);
        }
        this.chunks = chunks;
        this.cells = cells;
        this.chunkSize = chunkSize;
        this.origin = -chunks * chunkSize * 0.5f;
        this.chain = new MeshLod.Chain[chunks * chunks];
        this.bounds = new BoundsArray(chunks * chunks);
        float lowest = Float.MAX_VALUE, highest = -Float.MAX_VALUE;
        for (int cz = 0; cz < chunks; cz++) {
            for (int cx = 0; cx < chunks; cx++) {
                float x0 = origin + cx * chunkSize, z0 = origin + cz * chunkSize;
                float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int j = 0; j <= cells; j++) {
                    for (int i = 0; i <= cells; i++) {
                        float y = height(x0 + i * chunkSize / cells, z0 + j * chunkSize / cells);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
                bounds.add(x0, minY, z0, x0 + chunkSize, maxY, z0 + chunkSize);
                lowest = Math.min(lowest, minY);
                highest = Math.max(highest, maxY);
            }
        }
        float half = chunkSize * 0.5f, hy = (highest - lowest) * 0.5f;
        this.radius = (float) Math.sqrt(2 * half * half + hy * hy);
    }

    /**
     * Gives the height of the terrain at a point.
     *
     * @param x the x coordinate in metres
     * @param z the z coordinate in metres
     * @return the height in metres; a pure function of the position, between 0 and about 340
     */
    static float height(double x, double z) {
        double n = Noise.fbm2(Noise.Kind.SIMPLEX, x * FREQUENCY, z * FREQUENCY, SEED, 6, 2.0, 0.5);
        double ridge = 1.0 - Math.abs(Noise.fbm2(Noise.Kind.SIMPLEX, x * FREQUENCY * 0.5 + 40.0, z * FREQUENCY * 0.5 - 17.0, SEED + 7, 3, 2.0, 0.5));
        double t = Math.max(0.0, 0.5 + 0.5 * n);
        return (float) (AMPLITUDE * (0.7 * Math.pow(t, 2.0) + 0.3 * Math.pow(ridge, 3.0) * t));
    }

    int chunks() {
        return chunks;
    }

    int cells() {
        return cells;
    }

    float chunkSize() {
        return chunkSize;
    }

    float origin() {
        return origin;
    }

    int chunkCount() {
        return chunks * chunks;
    }

    BoundsArray bounds() {
        return bounds;
    }

    /**
     * Reads the radius of a sphere around any chunk, the same for all.
     *
     * @return the radius in metres
     */
    float boundingRadius() {
        return radius;
    }

    /**
     * Reads the number of levels that every chain has: the simplifier can stop early on a flat
     * chunk, so this is the least of them.
     *
     * @return the levels, 0 before {@link #buildChains}
     */
    int levels() {
        return levels;
    }

    /**
     * Reads the chain of a chunk.
     *
     * @param chunk the chunk index, row by row
     * @return the chain; {@code null} before {@link #buildChains}
     */
    MeshLod.Chain chain(int chunk) {
        return chain[chunk];
    }

    /**
     * Builds the mesh of one chunk at full detail: a grid of two triangles per cell, in world
     * coordinates, wound counter-clockwise seen from above.
     *
     * @param chunk the chunk index, row by row
     * @return the mesh, without normals
     */
    Mesh buildChunk(int chunk) {
        int cx = chunk % chunks, cz = chunk / chunks;
        float x0 = origin + cx * chunkSize, z0 = origin + cz * chunkSize, step = chunkSize / cells;
        Mesh mesh = new Mesh((cells + 1) * (cells + 1), cells * cells * 2);
        for (int j = 0; j <= cells; j++) {
            for (int i = 0; i <= cells; i++) {
                float x = x0 + i * step, z = z0 + j * step;
                mesh.addVertex(x, height(x, z), z);
            }
        }
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int v00 = j * (cells + 1) + i, v10 = v00 + 1, v01 = v00 + cells + 1, v11 = v01 + 1;
                mesh.addTriangle(v00, v01, v10);
                mesh.addTriangle(v10, v01, v11);
            }
        }
        return mesh;
    }

    /**
     * Builds the chain of levels of detail of every chunk, in parallel.
     *
     * @param levelCount the number of levels, the full-detail mesh included; at least 2
     * @param ratio the fraction of the triangles that each level keeps of the one before; in 0 to 1
     * @param minTriangles the least number of triangles that a level may have
     */
    void buildChains(int levelCount, float ratio, int minTriangles) {
        IntStream.range(0, chunkCount()).parallel().forEach(i -> chain[i] = MeshLod.build(buildChunk(i), levelCount, ratio, minTriangles, true));
        levels = Integer.MAX_VALUE;
        for (MeshLod.Chain c : chain) {
            levels = Math.min(levels, c.count());
        }
    }

    /**
     * Averages the error of each level over all chunks, which is what one set of selector
     * thresholds for the whole terrain is made from.
     *
     * @return the mean object-space error of each level in metres; level 0 has the error 0
     */
    float[] meanErrors() {
        float[] mean = new float[levels];
        for (int c = 0; c < chunkCount(); c++) {
            float[] e = chain[c].errors();
            for (int l = 0; l < levels; l++) {
                mean[l] += e[l];
            }
        }
        for (int l = 0; l < levels; l++) {
            mean[l] /= chunkCount();
        }
        return mean;
    }
}
