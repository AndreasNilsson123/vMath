package vmath.anim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Morph targets (blend shapes) of one mesh: for each target the <b>change</b> of the position of
 * every vertex it moves, optionally also of the normal and of the tangent, stored <em>sparsely</em>
 * (only the vertices whose change is larger than a tolerance, as is typical of a facial expression
 * that touches a part of the head).
 *
 * <p>A pose of the mesh is the base mesh plus the sum of {@code weight * delta} over the targets:
 * the weights usually come from an animation (the glTF morph weights) and are mostly zero.
 *
 * <ul>
 *   <li>{@link #apply} adds the weighted deltas to a copy of the base positions (and
 *       {@link #applyNormals} to the normals, optionally renormalising them) on the CPU, in time
 *       proportional to the number of stored entries of the targets with a non-zero weight.</li>
 *   <li>{@link #activeTargets} picks the few targets with the largest weights, the standard way to
 *       fit a GPU morph budget of 4 or 8 targets per draw.</li>
 *   <li>{@link #boundsExpansion} gives the distance by which a pose can move any vertex away from
 *       the base mesh, a conservative growth for the culling bounds of a morphed mesh.</li>
 *   <li>The pack methods write the data for the GPU: dense (a block of {@code vertexCount} deltas
 *       per target), sparse (vertex indices and deltas, for a scatter), or sparse with 16-bit
 *       signed normalised deltas scaled per target ({@link #packSparseSnorm16}), a quarter of the
 *       size of 4 floats.</li>
 * </ul>
 *
 * <p>Build one with {@link #builder(int)}. <b>Thread safety.</b> Immutable once built: safe to
 * share between threads. The apply methods write only into the arrays you pass.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float[] smile = new float[3 * 4];                                       // position deltas for 4 vertices, mostly zero
 * smile[3] = 0.1f;
 * MorphTargets targets = MorphTargets.builder(4).target("smile", smile).build();   // stores only the vertices that move
 * float[] base = new float[3 * 4];
 * float[] weights = {0.5f};
 * float[] morphed = new float[3 * 4];
 * targets.apply(base, weights, morphed);                                  // base + weights[t] * delta_t
 * }</pre>
 */
public final class MorphTargets {

    private final int vertexCount;
    private final int targetCount;
    private final boolean hasNormals, hasTangents;
    private final int[] targetStart; // entries of target t: targetStart[t] .. targetStart[t + 1] - 1
    private final int[] vertex; // vertex index of each entry, ascending within a target
    private final float[] position; // 3 floats per entry
    private final float[] normal; // 3 floats per entry, or null
    private final float[] tangent; // 3 floats per entry, or null
    private final float[] maxDisplacement; // per target: the largest length of a position delta
    private final String[] names;

    private MorphTargets(int vertexCount, int targetCount, boolean hasNormals, boolean hasTangents, int[] targetStart, int[] vertex, float[] position, float[] normal, float[] tangent,
                         float[] maxDisplacement, String[] names) {
        this.vertexCount = vertexCount;
        this.targetCount = targetCount;
        this.hasNormals = hasNormals;
        this.hasTangents = hasTangents;
        this.targetStart = targetStart;
        this.vertex = vertex;
        this.position = position;
        this.normal = normal;
        this.tangent = tangent;
        this.maxDisplacement = maxDisplacement;
        this.names = names;
    }

    /**
     * Starts a builder for the morph targets of a mesh with the given number of vertices.
     *
     * @param vertexCount the number of vertices
     * @return a builder for the targets of a mesh of {@code vertexCount} vertices
     */
    public static Builder builder(int vertexCount) {
        return new Builder(vertexCount);
    }

    /**
     * Exposes the number of vertices of the mesh that the targets belong to.
     *
     * @return the number of vertices of the mesh the targets belong to
     */
    public int vertexCount() {
        return vertexCount;
    }

    /**
     * Counts the morph targets.
     *
     * @return the number of targets
     */
    public int targetCount() {
        return targetCount;
    }

    /**
     * Returns whether the targets carry normal deltas.
     *
     * @return {@code true} if the targets carry normal deltas
     */
    public boolean hasNormals() {
        return hasNormals;
    }

    /**
     * Returns whether the targets carry tangent deltas.
     *
     * @return {@code true} if the targets carry tangent deltas
     */
    public boolean hasTangents() {
        return hasTangents;
    }

    /**
     * Looks up the name of a target, which is empty when none was given.
     *
     * @param t the target index
     * @return the name of target {@code t}, or an empty string when it was added without one
     */
    public String name(int t) {
        return names[t];
    }

    /**
     * Counts the vertices that one target moves, which is the number of its stored sparse entries.
     *
     * @param t the target index
     * @return the number of vertices that target {@code t} moves (its stored entries)
     */
    public int entryCount(int t) {
        return targetStart[t + 1] - targetStart[t];
    }

    /**
     * Counts the sparse entries over all targets.
     *
     * @return the total number of stored entries over all targets
     */
    public int entryCount() {
        return vertex.length;
    }

    /**
     * Reads the worst-case displacement of a target at full weight, which is the input for
     * conservative bounds of morphed meshes.
     *
     * @param t the target index
     * @return the largest distance by which target {@code t}, at weight 1, moves any vertex
     */
    public float maxDisplacement(int t) {
        return maxDisplacement[t];
    }

    // ------------------------------------------------------------ applying

    /**
     * Writes {@code basePositions + sum over t of weights[t] * delta_t} to {@code out} (3 floats
     * per vertex).
     *
     * <p>{@code out} may be the same array as {@code basePositions}, in which case the deltas are
     * added in place. Targets with a weight of exactly zero are skipped. {@code weights} needs at
     * least {@link #targetCount()} entries; further ones are ignored.
     *
     * @param basePositions the base positions
     * @param weights the weights
     * @param out receives the result
     * @throws IllegalArgumentException if {@code out} has room for fewer vertices than the mesh has
     */
    public void apply(float[] basePositions, float[] weights, float[] out) {
        check(basePositions, 3, "positions");
        checkWeights(weights);
        if (out.length < 3 * vertexCount) {
            throw new IllegalArgumentException("the output has room for " + out.length / 3 + " vertices, the mesh has " + vertexCount);
        }
        if (out != basePositions) {
            System.arraycopy(basePositions, 0, out, 0, 3 * vertexCount);
        }
        accumulate(position, weights, out);
    }

    /**
     * Writes the morphed normals to {@code out}:
     * {@code baseNormals + sum of weights[t] * normalDelta_t}, and with {@code renormalize} each
     * normal that a target touched is scaled back to unit length (a normal that has shrunk to zero
     * is left as the base normal).
     *
     * <p>The targets must carry normals ({@link #hasNormals()}).
     *
     * @param baseNormals the base normals (at least 3 elements)
     * @param weights the weights
     * @param out receives the result in {@code [0, 3)}
     * @param renormalize whether renormalize
     * @throws IllegalStateException if the targets have no normal deltas
     * @throws IllegalArgumentException if {@code out} has room for fewer vertices than the mesh has
     */
    public void applyNormals(float[] baseNormals, float[] weights, float[] out, boolean renormalize) {
        if (!hasNormals) {
            throw new IllegalStateException("the targets have no normal deltas");
        }
        check(baseNormals, 3, "normals");
        checkWeights(weights);
        if (out.length < 3 * vertexCount) {
            throw new IllegalArgumentException("the output has room for " + out.length / 3 + " vertices, the mesh has " + vertexCount);
        }
        if (out != baseNormals) {
            System.arraycopy(baseNormals, 0, out, 0, 3 * vertexCount);
        }
        accumulate(normal, weights, out);
        if (renormalize) {
            for (int t = 0; t < targetCount; t++) {
                if (weights[t] == 0f) {
                    continue;
                }
                for (int e = targetStart[t]; e < targetStart[t + 1]; e++) {
                    int o = 3 * vertex[e];
                    float l = (float) Math.sqrt(out[o] * out[o] + out[o + 1] * out[o + 1] + out[o + 2] * out[o + 2]);
                    if (l > 1e-12f) {
                        // scaling a vertex twice (touched by two targets) is harmless: the length is 1 after the first
                        out[o] /= l;
                        out[o + 1] /= l;
                        out[o + 2] /= l;
                    } else {
                        out[o] = baseNormals[o];
                        out[o + 1] = baseNormals[o + 1];
                        out[o + 2] = baseNormals[o + 2];
                    }
                }
            }
        }
    }

    /**
     * Writes the morphed tangents (the {@code xyz} of each tangent, 3 floats per vertex) to
     * {@code out}: the base plus the weighted tangent deltas.
     *
     * <p>The targets must carry tangents.
     *
     * @param baseTangents the base tangents
     * @param weights the weights
     * @param out receives the result
     * @throws IllegalStateException if the targets have no tangent deltas
     * @throws IllegalArgumentException if {@code out} has room for fewer vertices than the mesh has
     */
    public void applyTangents(float[] baseTangents, float[] weights, float[] out) {
        if (!hasTangents) {
            throw new IllegalStateException("the targets have no tangent deltas");
        }
        check(baseTangents, 3, "tangents");
        checkWeights(weights);
        if (out.length < 3 * vertexCount) {
            throw new IllegalArgumentException("the output has room for " + out.length / 3 + " vertices, the mesh has " + vertexCount);
        }
        if (out != baseTangents) {
            System.arraycopy(baseTangents, 0, out, 0, 3 * vertexCount);
        }
        accumulate(tangent, weights, out);
    }

    private void accumulate(float[] delta, float[] weights, float[] out) {
        for (int t = 0; t < targetCount; t++) {
            float w = weights[t];
            if (w == 0f) {
                continue;
            }
            for (int e = targetStart[t]; e < targetStart[t + 1]; e++) {
                int o = 3 * vertex[e];
                out[o] += w * delta[3 * e];
                out[o + 1] += w * delta[3 * e + 1];
                out[o + 2] += w * delta[3 * e + 2];
            }
        }
    }

    private void check(float[] a, int stride, String what) {
        if (a.length < stride * vertexCount) {
            throw new IllegalArgumentException("the " + what + " array has room for " + a.length / stride + " vertices, the mesh has " + vertexCount);
        }
    }

    private void checkWeights(float[] weights) {
        if (weights.length < targetCount) {
            throw new IllegalArgumentException("there are " + targetCount + " targets and " + weights.length + " weights");
        }
    }

    // ------------------------------------------------------------ weights and bounds

    /**
     * Chooses the targets to draw when only {@code maxActive} can be: those whose weight has an
     * absolute value above {@code threshold}, the largest first (ties by the lower index).
     *
     * <p>Their indices go to {@code outIndex} and their weights to {@code outWeight}, both of at
     * least {@code maxActive} entries. Returns how many there are, at most {@code maxActive}. The
     * weights are not rescaled; the dropped ones are simply not applied.
     *
     * @param weights the weights
     * @param threshold the threshold
     * @param maxActive the max active
     * @param outIndex the out index
     * @param outWeight the out weight
     * @return how many there are, at most {@code maxActive}
     * @throws IllegalArgumentException if the output arrays are shorter than {@code maxActive}
     */
    public int activeTargets(float[] weights, float threshold, int maxActive, int[] outIndex, float[] outWeight) {
        checkWeights(weights);
        if (maxActive < 1 || outIndex.length < maxActive || outWeight.length < maxActive) {
            throw new IllegalArgumentException("maxActive " + maxActive + " needs output arrays of that size");
        }
        int n = 0;
        for (int t = 0; t < targetCount; t++) {
            float a = Math.abs(weights[t]);
            if (!(a > threshold)) {
                continue;
            }
            // insertion into the list kept in descending order of |weight|, ascending index among equals
            int at = n;
            while (at > 0 && Math.abs(outWeight[at - 1]) < a) {
                at--;
            }
            if (at >= maxActive) {
                continue;
            }
            int last = Math.min(n, maxActive - 1);
            for (int i = last; i > at; i--) {
                outIndex[i] = outIndex[i - 1];
                outWeight[i] = outWeight[i - 1];
            }
            outIndex[at] = t;
            outWeight[at] = weights[t];
            n = Math.min(n + 1, maxActive);
        }
        return n;
    }

    /**
     * Computes a conservative bound for how far the current weights can move any vertex, by summing
     * the weighted maximum displacements, so that culling bounds can be expanded safely; the bound
     * is generally not tight.
     *
     * <p>Add it to the margin of the culling bounds of the mesh (a conservative inflation: it is
     * reached only when all moving vertices of every target coincide).
     *
     * @param weights the weights
     * @return an upper bound of the distance by which the weights can move any vertex:
     *     {@code sum of |weights[t]| * maxDisplacement(t)}
     */
    public float boundsExpansion(float[] weights) {
        checkWeights(weights);
        double sum = 0;
        for (int t = 0; t < targetCount; t++) {
            sum += Math.abs(weights[t]) * maxDisplacement[t];
        }
        return (float) sum;
    }

    // ------------------------------------------------------------ packing for the GPU

    /**
     * Packs the dense block for the GPU: {@code targetCount * vertexCount} position deltas, target
     * after target, vertex after vertex, each with {@code stride} floats (3, or 4 when the shader
     * reads {@code vec4}s: the fourth is 0).
     *
     * <p>{@code out} needs {@code targetCount * vertexCount * stride} floats. Vertices a target
     * does not move hold zeros.
     *
     * @param out receives the result in {@code [0, 3)}
     * @param stride the distance between consecutive elements
     * @throws IllegalArgumentException if {@code stride} is not 3 or 4, or {@code out} is too short
     */
    public void packDense(float[] out, int stride) {
        if (stride != 3 && stride != 4) {
            throw new IllegalArgumentException("the stride must be 3 or 4: " + stride);
        }
        long need = (long) targetCount * vertexCount * stride;
        if (out.length < need) {
            throw new IllegalArgumentException("the output needs " + need + " floats, has " + out.length);
        }
        Arrays.fill(out, 0, (int) need, 0f);
        for (int t = 0; t < targetCount; t++) {
            int base = t * vertexCount * stride;
            for (int e = targetStart[t]; e < targetStart[t + 1]; e++) {
                int o = base + vertex[e] * stride;
                out[o] = position[3 * e];
                out[o + 1] = position[3 * e + 1];
                out[o + 2] = position[3 * e + 2];
            }
        }
    }

    /**
     * Packs the sparse form for a scatter on the GPU:
     * {@code targetOffsets[t] .. targetOffsets[t + 1] - 1} are the entries of target {@code t}
     * ({@code targetCount + 1} ints), {@code vertexIds} the vertex of each entry and {@code deltas}
     * its position delta with {@code stride} floats (3 or 4, the fourth 0).
     *
     * <p>The arrays need {@code targetCount + 1}, {@link #entryCount()} and
     * {@code entryCount() * stride} elements.
     *
     * @param targetOffsets the target offsets
     * @param vertexIds the vertex ids
     * @param deltas the deltas
     * @param stride the distance between consecutive elements
     * @throws IllegalArgumentException if {@code stride} is not 3 or 4, or an output array is too
     *     small
     */
    public void packSparse(int[] targetOffsets, int[] vertexIds, float[] deltas, int stride) {
        if (stride != 3 && stride != 4) {
            throw new IllegalArgumentException("the stride must be 3 or 4: " + stride);
        }
        if (targetOffsets.length < targetCount + 1 || vertexIds.length < vertex.length || deltas.length < vertex.length * stride) {
            throw new IllegalArgumentException("the output arrays are too small for " + targetCount + " targets and " + vertex.length + " entries");
        }
        System.arraycopy(targetStart, 0, targetOffsets, 0, targetCount + 1);
        System.arraycopy(vertex, 0, vertexIds, 0, vertex.length);
        for (int e = 0; e < vertex.length; e++) {
            deltas[e * stride] = position[3 * e];
            deltas[e * stride + 1] = position[3 * e + 1];
            deltas[e * stride + 2] = position[3 * e + 2];
            if (stride == 4) {
                deltas[e * stride + 3] = 0f;
            }
        }
    }

    /**
     * Packs the sparse form with 16-bit signed normalised deltas: every target gets a scale (the
     * largest absolute component of its position deltas, {@code scales[t]}) and each component is
     * stored as {@code round(delta / scale * 32767)}, three {@code short}s per entry in
     * {@code deltas}.
     *
     * <p>The shader multiplies by {@code scale / 32767}. The error of a decoded component is at
     * most {@code scales[t] / 65534}. {@code scales} needs {@code targetCount} floats and
     * {@code deltas} {@code 3 * entryCount()} shorts.
     *
     * @param scales the scales
     * @param deltas the deltas
     * @throws IllegalArgumentException if an output array is too small
     */
    public void packSparseSnorm16(float[] scales, short[] deltas) {
        if (scales.length < targetCount || deltas.length < 3 * vertex.length) {
            throw new IllegalArgumentException("the output arrays are too small for " + targetCount + " targets and " + vertex.length + " entries");
        }
        for (int t = 0; t < targetCount; t++) {
            float scale = 0f;
            for (int e = targetStart[t]; e < targetStart[t + 1]; e++) {
                scale = Math.max(scale, Math.max(Math.abs(position[3 * e]), Math.max(Math.abs(position[3 * e + 1]), Math.abs(position[3 * e + 2]))));
            }
            scales[t] = scale;
            for (int e = targetStart[t]; e < targetStart[t + 1]; e++) {
                for (int k = 0; k < 3; k++) {
                    deltas[3 * e + k] = scale > 0f ? (short) Math.round(position[3 * e + k] / scale * 32767f) : 0;
                }
            }
        }
    }

    /**
     * Decodes the entries of {@link #packSparseSnorm16}:
     * {@code out[3 e + k] = deltas[3 e + k] * scales[t] / 32767} for each target {@code t} and
     * entry {@code e} of it.
     *
     * @param scales the scales
     * @param deltas the deltas
     * @param out receives the result
     * @throws IllegalArgumentException if an array is too small
     */
    public void unpackSparseSnorm16(float[] scales, short[] deltas, float[] out) {
        if (scales.length < targetCount || deltas.length < 3 * vertex.length || out.length < 3 * vertex.length) {
            throw new IllegalArgumentException("the arrays are too small for " + targetCount + " targets and " + vertex.length + " entries");
        }
        for (int t = 0; t < targetCount; t++) {
            for (int e = targetStart[t]; e < targetStart[t + 1]; e++) {
                for (int k = 0; k < 3; k++) {
                    out[3 * e + k] = deltas[3 * e + k] * scales[t] / 32767f;
                }
            }
        }
    }

    /**
     * Reads which vertex a sparse entry moves; the entries of a target are ordered by vertex, which
     * supports merging.
     *
     * @param e the entry index in the sparse form
     * @return the vertex index of entry {@code e} of the sparse form (entries are ordered by
     *     target, and by vertex within a target)
     */
    public int entryVertex(int e) {
        return vertex[e];
    }

    /**
     * Reads where the entries of a target start in the sparse storage; the offset after the last
     * target is the total entry count.
     *
     * @param t the target index
     * @return the offset of the first entry of target {@code t} in the sparse form;
     *     {@code targetOffset(targetCount())} is the total
     */
    public int targetOffset(int t) {
        return targetStart[t];
    }

    // ------------------------------------------------------------ the builder

    /**
     * Collects targets from dense per-vertex deltas and stores them sparsely.
     */
    public static final class Builder {

        private final int vertexCount;
        private final List<float[]> positions = new ArrayList<>(), normals = new ArrayList<>(), tangents = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private float tolerance;

        private Builder(int vertexCount) {
            if (vertexCount < 1) {
                throw new IllegalArgumentException("a mesh needs at least one vertex: " + vertexCount);
            }
            this.vertexCount = vertexCount;
        }

        /**
         * Sets the tolerance below which a vertex counts as not moved: a vertex is stored when any
         * component of its position, normal or tangent delta has an absolute value above it.
         *
         * <p>The default 0 stores every vertex with any change. Dropping tiny deltas changes the
         * result by at most that tolerance per component per target at weight 1.
         *
         * @param tolerance the tolerance
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code tolerance} is negative
         */
        public Builder tolerance(float tolerance) {
            if (!(tolerance >= 0f)) {
                throw new IllegalArgumentException("the tolerance must not be negative: " + tolerance);
            }
            this.tolerance = tolerance;
            return this;
        }

        /**
         * Adds a target with position deltas only: {@code positionDeltas} has 3 floats per vertex.
         *
         * @param name the name; must not be {@code null}
         * @param positionDeltas the position deltas
         * @return this builder, for chaining
         */
        public Builder target(String name, float[] positionDeltas) {
            return target(name, positionDeltas, null, null);
        }

        /**
         * Adds a target from dense deltas of 3 floats per vertex: the positions, and optionally
         * (null when absent) the normals and the tangents.
         *
         * <p>Every target of a set must have the same optional parts as the first. The arrays are
         * copied.
         *
         * @param name the name; may be {@code null}
         * @param positionDeltas the position deltas
         * @param normalDeltas the normal deltas
         * @param tangentDeltas the tangent deltas
         * @return this builder, for chaining
         * @throws IllegalArgumentException if a delta array does not hold 3 floats per vertex, only
         *     some targets have normal and tangent deltas, or a delta is not finite
         */
        public Builder target(String name, float[] positionDeltas, float[] normalDeltas, float[] tangentDeltas) {
            if (positionDeltas.length != 3 * vertexCount || (normalDeltas != null && normalDeltas.length != 3 * vertexCount) || (tangentDeltas != null && tangentDeltas.length != 3 * vertexCount)) {
                throw new IllegalArgumentException("the deltas need 3 floats for each of the " + vertexCount + " vertices");
            }
            if (!positions.isEmpty() && ((normalDeltas != null) != (normals.get(0) != null) || (tangentDeltas != null) != (tangents.get(0) != null))) {
                throw new IllegalArgumentException("every target must have normal and tangent deltas, or none must");
            }
            for (float[] a : new float[][] {positionDeltas, normalDeltas, tangentDeltas}) {
                if (a != null) {
                    for (float v : a) {
                        if (!Float.isFinite(v)) {
                            throw new IllegalArgumentException("a delta is not finite");
                        }
                    }
                }
            }
            positions.add(positionDeltas.clone());
            normals.add(normalDeltas == null ? null : normalDeltas.clone());
            tangents.add(tangentDeltas == null ? null : tangentDeltas.clone());
            names.add(name == null ? "" : name);
            return this;
        }

        /**
         * Builds the sparse set from the targets added so far; the builder may be reused.
         *
         * <p>At least one target is needed.
         *
         * @return the morph targets, never {@code null}
         * @throws IllegalStateException if no target was added
         */
        public MorphTargets build() {
            int n = positions.size();
            if (n == 0) {
                throw new IllegalStateException("add at least one target");
            }
            boolean hasN = normals.get(0) != null, hasT = tangents.get(0) != null;
            int[] start = new int[n + 1];
            int total = 0;
            boolean[][] moved = new boolean[n][];
            for (int t = 0; t < n; t++) {
                moved[t] = new boolean[vertexCount];
                for (int v = 0; v < vertexCount; v++) {
                    boolean m = moves(positions.get(t), v);
                    m |= hasN && moves(normals.get(t), v);
                    m |= hasT && moves(tangents.get(t), v);
                    moved[t][v] = m;
                    if (m) {
                        total++;
                    }
                }
                start[t + 1] = total;
            }
            int[] vertex = new int[total];
            float[] pos = new float[3 * total], nor = hasN ? new float[3 * total] : null, tan = hasT ? new float[3 * total] : null;
            float[] maxDisp = new float[n];
            int e = 0;
            for (int t = 0; t < n; t++) {
                for (int v = 0; v < vertexCount; v++) {
                    if (!moved[t][v]) {
                        continue;
                    }
                    vertex[e] = v;
                    for (int k = 0; k < 3; k++) {
                        pos[3 * e + k] = positions.get(t)[3 * v + k];
                        if (hasN) {
                            nor[3 * e + k] = normals.get(t)[3 * v + k];
                        }
                        if (hasT) {
                            tan[3 * e + k] = tangents.get(t)[3 * v + k];
                        }
                    }
                    maxDisp[t] = Math.max(maxDisp[t], (float) Math.sqrt(pos[3 * e] * pos[3 * e] + pos[3 * e + 1] * pos[3 * e + 1] + pos[3 * e + 2] * pos[3 * e + 2]));
                    e++;
                }
            }
            return new MorphTargets(vertexCount, n, hasN, hasT, start, vertex, pos, nor, tan, maxDisp, names.toArray(new String[0]));
        }

        private boolean moves(float[] a, int v) {
            return Math.abs(a[3 * v]) > tolerance || Math.abs(a[3 * v + 1]) > tolerance || Math.abs(a[3 * v + 2]) > tolerance;
        }
    }
}
