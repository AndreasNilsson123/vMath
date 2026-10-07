package vmath.lines;

import vmath.annotations.Experimental;

/**
 * Position histories ("trails") of many moving tracks: a ring buffer of the last positions of each
 * track with the time of each, and the means to draw a trail as a line that fades with age.
 *
 * <p>Each track has room for {@code pointsPerTrack} positions; pushing one more overwrites the
 * oldest. Points are numbered from the <b>oldest</b> (0) to the newest ({@code size - 1}).
 *
 * <p><b>Fading.</b> {@link #updateLines} writes a trail into a {@link LineSet} as a few polylines,
 * one for each of {@code fadeSteps} slices of age: the newest slice has the colour of the style
 * as it is and each older slice is more transparent, down to a fraction {@code 1 / fadeSteps} of
 * the alpha for the oldest, and points older than the maximum age are left out. The slices share
 * their boundary points, so the trail is connected. This is a fade in steps, not a gradient along
 * every segment: a gradient needs an alpha per end of a segment in the segment record, which
 * the records of {@link LineGpu} do not have. Use a solid style (dashes restart at every slice)
 * and, for thick lines drawn with transparency, round caps show the joints between slices least.
 *
 * <p>The polylines of a trail belong to the line set that {@code updateLines} was given first;
 * {@link #releaseLines} removes them. Updates reuse the polylines, so in steady state a trail
 * allocates nothing as long as it keeps about the same number of points per slice.
 *
 * <p><b>Thread safety.</b> Not thread-safe: one thread at a time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * TrailBuffer trails = new TrailBuffer(1000, 64, 8);
 * int track = trails.addTrack();
 * trails.push(track, x, y, z, timeSeconds);                                  // every update of the track
 * trails.updateLines(set, track, nowSeconds, 60.0, LineStyle.pixels(2f).withColor(0x00FF00FF));
 * trails.releaseLines(set, track);                                           // when the track ends
 * trails.removeTrack(track);
 * }</pre>
 */
@Experimental("the trail buffer may change")
public final class TrailBuffer {

    private final int maxTracks;
    private final int pointsPerTrack;
    private final int fadeSteps;
    private final double[] xyz;
    private final double[] times;
    private final int[] head;      // index of the slot that the next push writes
    private final int[] count;
    private final boolean[] used;
    private final long[] handles;  // fadeSteps per track
    private final LineStyle[] baseOf;      // the style each track's slice styles were made from
    private final LineStyle[] sliceStyles; // fadeSteps per track
    private int usedCount;
    private int searchFrom;
    private final double[] piece;

    /**
     * Creates a buffer.
     *
     * @param maxTracks the most tracks at once, at least 1
     * @param pointsPerTrack the positions kept per track, at least 2
     * @param fadeSteps the slices of age of a drawn trail, at least 1
     * @throws IllegalArgumentException if an argument is below its minimum
     */
    public TrailBuffer(int maxTracks, int pointsPerTrack, int fadeSteps) {
        if (maxTracks < 1 || pointsPerTrack < 2 || fadeSteps < 1) {
            throw new IllegalArgumentException("tracks >= 1, points >= 2, steps >= 1: " + maxTracks + ", " + pointsPerTrack + ", " + fadeSteps);
        }
        this.maxTracks = maxTracks;
        this.pointsPerTrack = pointsPerTrack;
        this.fadeSteps = fadeSteps;
        this.xyz = new double[3 * maxTracks * pointsPerTrack];
        this.times = new double[maxTracks * pointsPerTrack];
        this.head = new int[maxTracks];
        this.count = new int[maxTracks];
        this.used = new boolean[maxTracks];
        this.handles = new long[maxTracks * fadeSteps];
        this.baseOf = new LineStyle[maxTracks];
        this.sliceStyles = new LineStyle[maxTracks * fadeSteps];
        this.piece = new double[3 * pointsPerTrack];
    }

    private void check(int track) {
        if (track < 0 || track >= maxTracks || !used[track]) {
            throw new IllegalArgumentException("not a track of this buffer: " + track);
        }
    }

    /**
     * Starts a track.
     *
     * @return the track number, from 0 up
     * @throws IllegalStateException if all tracks are in use
     */
    public int addTrack() {
        if (usedCount == maxTracks) {
            throw new IllegalStateException("all " + maxTracks + " tracks are in use");
        }
        for (int i = 0; i < maxTracks; i++) {
            int t = (searchFrom + i) % maxTracks;
            if (!used[t]) {
                used[t] = true;
                head[t] = 0;
                count[t] = 0;
                searchFrom = t + 1;
                usedCount++;
                return t;
            }
        }
        throw new IllegalStateException("all tracks are in use");
    }

    /**
     * Ends a track and frees its number. Its polylines in a line set stay until {@link
     * #releaseLines} removes them, so call that first.
     *
     * @param track the track
     * @throws IllegalArgumentException if it is not a track in use
     */
    public void removeTrack(int track) {
        check(track);
        used[track] = false;
        usedCount--;
        for (int s = 0; s < fadeSteps; s++) {
            handles[track * fadeSteps + s] = LineSet.NONE;
        }
        baseOf[track] = null;
    }

    /**
     * Counts the tracks in use.
     *
     * @return the number of tracks
     */
    public int trackCount() {
        return usedCount;
    }

    /**
     * Adds a position to a track, overwriting the oldest if the track is full.
     *
     * @param track the track
     * @param x the x coordinate
     * @param y the y coordinate
     * @param z the z coordinate
     * @param time the time of the position, not before the time of the previous one
     * @throws IllegalArgumentException if it is not a track in use, a value is not finite, or the
     *     time is before the previous one
     */
    public void push(int track, double x, double y, double z, double time) {
        check(track);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(time)) {
            throw new IllegalArgumentException("the position and the time must be finite");
        }
        int base = track * pointsPerTrack;
        if (count[track] > 0) {
            int last = (head[track] + pointsPerTrack - 1) % pointsPerTrack;
            if (time < times[base + last]) {
                throw new IllegalArgumentException("the time " + time + " is before the previous " + times[base + last]);
            }
        }
        int at = base + head[track];
        xyz[3 * at] = x;
        xyz[3 * at + 1] = y;
        xyz[3 * at + 2] = z;
        times[at] = time;
        head[track] = (head[track] + 1) % pointsPerTrack;
        if (count[track] < pointsPerTrack) {
            count[track]++;
        }
    }

    /**
     * Counts the positions kept for a track.
     *
     * @param track the track
     * @return the number of positions, at most {@code pointsPerTrack}
     * @throws IllegalArgumentException if it is not a track in use
     */
    public int size(int track) {
        check(track);
        return count[track];
    }

    private int slot(int track, int index) {
        if (index < 0 || index >= count[track]) {
            throw new IndexOutOfBoundsException("point " + index + " of " + count[track]);
        }
        return track * pointsPerTrack + (head[track] - count[track] + index + 2 * pointsPerTrack) % pointsPerTrack;
    }

    /**
     * Reads a coordinate of a position.
     *
     * @param track the track
     * @param index the position, 0 for the oldest
     * @param axis 0 for x, 1 for y, 2 for z
     * @return the coordinate
     * @throws IllegalArgumentException if it is not a track in use
     * @throws IndexOutOfBoundsException if the position or the axis does not exist
     */
    public double coordinate(int track, int index, int axis) {
        check(track);
        if (axis < 0 || axis > 2) {
            throw new IndexOutOfBoundsException("axis " + axis);
        }
        return xyz[3 * slot(track, index) + axis];
    }

    /**
     * Reads the time of a position.
     *
     * @param track the track
     * @param index the position, 0 for the oldest
     * @return the time
     * @throws IllegalArgumentException if it is not a track in use
     * @throws IndexOutOfBoundsException if the position does not exist
     */
    public double time(int track, int index) {
        check(track);
        return times[slot(track, index)];
    }

    /**
     * Copies the positions of a track in order, oldest first.
     *
     * @param track the track
     * @param out receives {@code x, y, z} triples; must have room for {@code 3 * size} values
     * @return the number of positions copied
     * @throws IllegalArgumentException if it is not a track in use or {@code out} is too short
     */
    public int copyPoints(int track, double[] out) {
        check(track);
        if (out.length < 3 * count[track]) {
            throw new IllegalArgumentException("the array needs " + 3 * count[track] + " values");
        }
        for (int i = 0; i < count[track]; i++) {
            System.arraycopy(xyz, 3 * slot(track, i), out, 3 * i, 3);
        }
        return count[track];
    }

    /**
     * Forgets the positions of a track that are older than a time.
     *
     * @param track the track
     * @param time the oldest time to keep
     * @return the number of positions forgotten
     * @throws IllegalArgumentException if it is not a track in use
     */
    public int dropOlderThan(int track, double time) {
        check(track);
        int dropped = 0;
        while (count[track] > 0 && times[slot(track, 0)] < time) {
            count[track]--;
            dropped++;
        }
        return dropped;
    }

    /**
     * Forgets all positions of a track.
     *
     * @param track the track
     * @throws IllegalArgumentException if it is not a track in use
     */
    public void clear(int track) {
        check(track);
        count[track] = 0;
    }

    /**
     * Gives the alpha factor of a slice of age.
     *
     * @param slice the slice, 0 for the newest
     * @return the factor to multiply the alpha of the style by: 1 for the newest slice, down to
     *     {@code 1 / fadeSteps} for the oldest
     * @throws IndexOutOfBoundsException if there is no such slice
     */
    public double alphaOfSlice(int slice) {
        if (slice < 0 || slice >= fadeSteps) {
            throw new IndexOutOfBoundsException("slice " + slice + " of " + fadeSteps);
        }
        return 1.0 - (double) slice / fadeSteps;
    }

    private static LineStyle faded(LineStyle base, double factor) {
        int c = base.color();
        int alpha = (int) Math.round((c & 255) * factor);
        return base.withColor(c & 0xFFFFFF00 | Math.max(0, Math.min(255, alpha)));
    }

    /**
     * Writes a track into a line set as polylines that fade with age, creating, changing or
     * removing them as the points move between slices.
     *
     * @param set the line set; must not be {@code null}
     * @param track the track
     * @param now the present time
     * @param maxAge the age at which a position is no longer drawn, more than 0
     * @param style the style of the newest slice; the colour's alpha is scaled for the others;
     *     pass the same object every time to keep the update free of allocation
     * @return the number of polylines the track has in the set now
     * @throws IllegalArgumentException if it is not a track in use, {@code maxAge} is not more
     *     than 0, or a polyline cannot be added
     * @throws IllegalStateException if the set is full: grow it
     */
    public int updateLines(LineSet set, int track, double now, double maxAge, LineStyle style) {
        check(track);
        if (!(maxAge > 0)) {
            throw new IllegalArgumentException("the maximum age must be more than 0: " + maxAge);
        }
        if (baseOf[track] != style) {
            baseOf[track] = java.util.Objects.requireNonNull(style);
            for (int s = 0; s < fadeSteps; s++) {
                int h = track * fadeSteps + s;
                sliceStyles[h] = faded(style, alphaOfSlice(s));
                if (set.contains(handles[h])) {
                    set.setStyle(handles[h], sliceStyles[h]);
                }
            }
        }
        int next = count[track] - 1;            // the newest point not yet in a piece; the piece starts with it
        int alive = 0;
        for (int s = 0; s < fadeSteps; s++) {
            int h = track * fadeSteps + s;
            int pointsInPiece = 0;
            boolean distinct = false;
            double boundary = (double) (s + 1) / fadeSteps;
            int i = next;
            while (i >= 0) {
                int sl = slot(track, i);
                double age = (now - times[sl]) / maxAge;
                if (age > 1.0) {
                    break;                      // too old to draw
                }
                System.arraycopy(xyz, 3 * sl, piece, 3 * pointsInPiece, 3);
                distinct |= pointsInPiece > 0 && (piece[0] != piece[3 * pointsInPiece] || piece[1] != piece[3 * pointsInPiece + 1] || piece[2] != piece[3 * pointsInPiece + 2]);
                pointsInPiece++;
                if (pointsInPiece > 1 && age >= boundary) {
                    break;                      // this point is the start of the next, older piece
                }
                i--;
            }
            if (pointsInPiece >= 2 && distinct) {
                alive++;
                if (set.contains(handles[h])) {
                    set.set(handles[h], piece, 0, pointsInPiece, false);
                } else {
                    handles[h] = set.add(piece, 0, pointsInPiece, false, sliceStyles[h]);
                }
            } else if (set.contains(handles[h])) {
                set.remove(handles[h]);
                handles[h] = LineSet.NONE;
            }
            boolean ended = i < 0 || pointsInPiece == 0 || (now - times[slot(track, i)]) / maxAge > 1.0;
            if (ended) {
                for (int r = s + 1; r < fadeSteps; r++) {
                    int hr = track * fadeSteps + r;
                    if (set.contains(handles[hr])) {
                        set.remove(handles[hr]);
                        handles[hr] = LineSet.NONE;
                    }
                }
                break;
            }
            next = i;
        }
        return alive;
    }

    /**
     * Removes the polylines of a track from a line set.
     *
     * @param set the line set; must not be {@code null}
     * @param track the track
     * @throws IllegalArgumentException if it is not a track in use
     */
    public void releaseLines(LineSet set, int track) {
        check(track);
        for (int s = 0; s < fadeSteps; s++) {
            int h = track * fadeSteps + s;
            if (set.contains(handles[h])) {
                set.remove(handles[h]);
            }
            handles[h] = LineSet.NONE;
        }
    }
}
