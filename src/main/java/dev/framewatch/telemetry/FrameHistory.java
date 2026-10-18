package dev.framewatch.telemetry;

import java.util.Arrays;

/**
 * Fixed-capacity, primitive frame ring. Successful {@link #record} calls allocate
 * nothing. Access is synchronized so a snapshot cannot observe a partial frame.
 */
public final class FrameHistory {
    private static final int MAX_GC_ACTIVITIES = 256;

    private final long[] timestampsNanos;
    private final long[] intervalsNanos;
    private final long[] workNanos;
    private final long[] renderNanos;
    private final long[] presentNanos;
    private final long[] tickNanos;
    private final long[] gcPauseNanos;
    private final int[] tickCounts;
    private final int[] events;
    private int next;
    private int size;
    private final long[] gcStartsNanos;
    private final long[] gcEndsNanos;
    private final boolean[] gcPointAssigned;
    private int nextGc;
    private int gcSize;

    public FrameHistory(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        timestampsNanos = new long[capacity];
        intervalsNanos = new long[capacity];
        workNanos = new long[capacity];
        renderNanos = new long[capacity];
        presentNanos = new long[capacity];
        tickNanos = new long[capacity];
        gcPauseNanos = new long[capacity];
        tickCounts = new int[capacity];
        events = new int[capacity];
        int gcCapacity = Math.min(capacity, MAX_GC_ACTIVITIES);
        gcStartsNanos = new long[gcCapacity];
        gcEndsNanos = new long[gcCapacity];
        gcPointAssigned = new boolean[gcCapacity];
    }

    /**
     * Records a frame. Timestamps must be nondecreasing until {@link #clear};
     * equal and negative timestamps are valid (as with System.nanoTime()).
     * Phase and GC durations need not sum to, or fit within, the interval.
     * Event flags are preserved, including bits unknown to this module.
     * Retained GC notifications also contribute overlap duration and EVENT_GC.
     * The explicit gcPauseNanos parameter is additive: pass zero when the same
     * pauses are supplied through addGcActivity to avoid counting them twice.
     */
    public synchronized void record(long timestampNanos, long intervalNanos,
            long workNanos, long renderNanos, long presentNanos, long tickNanos,
            int tickCount, long gcPauseNanos, int events) {
        if (intervalNanos <= 0) {
            throw new IllegalArgumentException("intervalNanos must be positive");
        }
        if (workNanos < 0 || renderNanos < 0 || presentNanos < 0
                || tickNanos < 0 || gcPauseNanos < 0 || tickCount < 0) {
            throw new IllegalArgumentException("phase durations, GC duration and tick count must be nonnegative");
        }
        int previous = next == 0 ? timestampsNanos.length - 1 : next - 1;
        if (size > 0 && timestampNanos < timestampsNanos[previous]) {
            throw new IllegalArgumentException("timestampNanos must not move backward");
        }

        timestampsNanos[next] = timestampNanos;
        intervalsNanos[next] = intervalNanos;
        this.workNanos[next] = workNanos;
        this.renderNanos[next] = renderNanos;
        this.presentNanos[next] = presentNanos;
        this.tickNanos[next] = tickNanos;
        tickCounts[next] = tickCount;
        this.gcPauseNanos[next] = gcPauseNanos;
        this.events[next] = events;
        for (int i = 0; i < gcSize; i++) {
            annotateGc(next, i);
        }
        next++;
        if (next == timestampsNanos.length) {
            next = 0;
        }
        if (size < timestampsNanos.length) {
            size++;
        }
    }

    /**
     * Adds an asynchronously delivered GC interval in the frame timestamp clock
     * domain, annotating retained frames and subsequent records. Delivery may be
     * out of timestamp order; end must be at least start and the duration must
     * fit in a nonnegative long. Negative timestamps themselves are valid.
     *
     * Frames and notifications use (start, end] boundaries. Positive overlaps
     * add their intersection duration; merely touching endpoints do not count.
     * A zero-duration notification marks only the earliest-ending intersecting
     * retained or subsequently recorded frame, adding no duration.
     *
     * The most recent min(frame capacity, 256) distinct notifications (by delivery
     * order) are retained in a primitive ring, bounding record-time GC scanning
     * to at most 256 entries. Exact start/end deduplication is limited to these
     * retained entries; callers must not replay old notifications after eviction.
     * Eviction does not undo annotations already applied to frames. Distinct
     * notifications and explicit GC durations are summed, saturating at
     * Long.MAX_VALUE rather than overflowing. This method allocates nothing on
     * successful calls.
     */
    public synchronized void addGcActivity(long startNanos, long endNanos) {
        if (endNanos < startNanos || endNanos - startNanos < 0) {
            throw new IllegalArgumentException("GC duration must be nonnegative and fit in a long");
        }
        for (int i = 0; i < gcSize; i++) {
            if (gcStartsNanos[i] == startNanos && gcEndsNanos[i] == endNanos) {
                return;
            }
        }
        int activity = nextGc;
        gcStartsNanos[activity] = startNanos;
        gcEndsNanos[activity] = endNanos;
        gcPointAssigned[activity] = false;
        nextGc++;
        if (nextGc == gcStartsNanos.length) {
            nextGc = 0;
        }
        if (gcSize < gcStartsNanos.length) {
            gcSize++;
        }
        int frame = next - size;
        if (frame < 0) {
            frame += timestampsNanos.length;
        }
        for (int i = 0; i < size; i++) {
            annotateGc(frame, activity);
            frame = advance(frame);
        }
    }

    /**
     * Copies the newest min(size, intervals.length, flags.length) retained frames
     * into caller-owned buffers starting at index zero, in chronological order.
     * Intervals are in nanoseconds; flags include any late GC annotations.
     * Returns the number copied and leaves the remainder of each buffer untouched.
     * Empty history or zero-length buffers return zero. Both buffers must be
     * nonnull, even when no frames can be copied. Successful calls allocate
     * nothing and only visit the frames being copied.
     */
    public synchronized int copyRecent(long[] intervals, int[] flags) {
        if (intervals == null) {
            throw new NullPointerException("intervals");
        }
        if (flags == null) {
            throw new NullPointerException("flags");
        }
        int count = Math.min(size, Math.min(intervals.length, flags.length));
        int frame = next - count;
        if (frame < 0) {
            frame += intervalsNanos.length;
        }
        for (int i = 0; i < count; i++) {
            intervals[i] = intervalsNanos[frame];
            flags[i] = events[frame];
            frame = advance(frame);
        }
        return count;
    }

    /**
     * Copies peak intervals and ORed event flags into fixed wall-clock buckets,
     * oldest on the left and newest on the right. Selects frame timestamps in
     * [nowNanos - windowNanos, nowNanos], saturating the lower bound at
     * Long.MIN_VALUE. Each frame contributes only to its timestamp's bucket;
     * durations are not clipped, spread over gaps, or synthesized. Late GC
     * annotations are included. The nominal window stays fixed even when its
     * lower bound underflows; nowNanos always belongs to the final bucket.
     *
     * Both entire buffers are cleared, including unused trailing elements.
     * Returns min(peaks.length, flags.length), even with no selected frames,
     * so callers render the full window, including empty buckets. Buffers must
     * be nonnull and windowNanos must be positive, even for zero-width output.
     * Successful calls allocate nothing, binary-search the retained ring for
     * the start, and scan only selected frames. Graph callers should reuse
     * buffers and refresh at about 30 Hz, rather than on every rendered frame.
     */
    public synchronized int copyTimeBuckets(long nowNanos, long windowNanos,
            long[] peaks, int[] flags) {
        return copyTimeBuckets(nowNanos, windowNanos, peaks, flags, null, null);
    }

    /** Optional reusable sums/counts produce FPS as count * 1e9 / summed intervals. */
    public synchronized int copyTimeBuckets(long nowNanos, long windowNanos,
            long[] peaks, int[] flags, double[] intervalTotals, int[] frameCounts) {
        if ((intervalTotals == null) != (frameCounts == null)) throw new IllegalArgumentException("Both FPS buffers required");
        if (intervalTotals != null) {
            if (intervalTotals.length < Math.min(peaks.length, flags.length)
                    || frameCounts.length < Math.min(peaks.length, flags.length)) {
                throw new IllegalArgumentException("FPS buffers too small");
            }
            Arrays.fill(intervalTotals, 0);
            Arrays.fill(frameCounts, 0);
        }
        if (peaks == null) {
            throw new NullPointerException("peaks");
        }
        if (flags == null) {
            throw new NullPointerException("flags");
        }
        if (windowNanos <= 0) {
            throw new IllegalArgumentException("windowNanos must be positive");
        }
        Arrays.fill(peaks, 0L);
        Arrays.fill(flags, 0);
        int width = Math.min(peaks.length, flags.length);
        if (width == 0 || size == 0) {
            return width;
        }

        long start = nowNanos < Long.MIN_VALUE + windowNanos
                ? Long.MIN_VALUE : nowNanos - windowNanos;
        int first = next - size;
        if (first < 0) {
            first += timestampsNanos.length;
        }
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (timestampsNanos[ringIndex(first, middle)] < start) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        int frame = ringIndex(first, low);
        for (int i = low; i < size; i++) {
            long timestamp = timestampsNanos[frame];
            if (timestamp > nowNanos) {
                break;
            }
            // Selected ages fit in a long, even if the nominal start underflows.
            long offset = windowNanos - (nowNanos - timestamp);
            int bucket = timeBucket(offset, windowNanos, width);
            peaks[bucket] = Math.max(peaks[bucket], intervalsNanos[frame]);
            flags[bucket] |= events[frame];
            if (intervalTotals != null) {
                intervalTotals[bucket] += intervalsNanos[frame];
                frameCounts[bucket]++;
            }
            frame = advance(frame);
        }
        return width;
    }

    private int ringIndex(int first, int offset) {
        int untilWrap = timestampsNanos.length - first;
        return offset >= untilWrap ? offset - untilWrap : first + offset;
    }

    private static int timeBucket(long offset, long window, int width) {
        if (offset == window) {
            return width - 1;
        }
        if (offset <= Long.MAX_VALUE / width) {
            return (int) (offset * width / window);
        }
        // Find floor(offset * width / window) without overflowing the product.
        // Boundary b starts at ceil(window * b / width); split window first so
        // both products fit (the remainder and b are bounded by int width).
        long quotient = window / width;
        long remainder = window % width;
        int low = 0;
        int high = width;
        while (low + 1 < high) {
            int middle = low + (high - low) / 2;
            long boundary = quotient * middle
                    + (remainder * middle + width - 1L) / width;
            if (offset < boundary) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return low;
    }

    /** Drops retained frames and GC notifications without reallocating. */
    public synchronized void clear() {
        next = 0;
        size = 0;
        nextGc = 0;
        gcSize = 0;
    }

    private void annotateGc(int frame, int activity) {
        long frameEnd = timestampsNanos[frame];
        boolean startUnderflows = frameEnd < Long.MIN_VALUE + intervalsNanos[frame];
        long frameStart = startUnderflows ? Long.MIN_VALUE : frameEnd - intervalsNanos[frame];
        long gcStart = gcStartsNanos[activity];
        long gcEnd = gcEndsNanos[activity];
        if (gcStart == gcEnd) {
            if (!gcPointAssigned[activity] && gcEnd <= frameEnd
                    && (startUnderflows || gcEnd > frameStart)) {
                events[frame] |= FrameSnapshot.EVENT_GC;
                gcPointAssigned[activity] = true;
            }
            return;
        }
        long overlapStart = Math.max(frameStart, gcStart);
        long overlapEnd = Math.min(frameEnd, gcEnd);
        if (overlapEnd > overlapStart) {
            long overlap = overlapEnd - overlapStart;
            long previousPause = gcPauseNanos[frame];
            gcPauseNanos[frame] = previousPause > Long.MAX_VALUE - overlap
                    ? Long.MAX_VALUE : previousPause + overlap;
            events[frame] |= FrameSnapshot.EVENT_GC;
        }
    }

    /**
     * Returns detached, chronological samples and statistics. A positive window
     * selects timestamps in the inclusive range [nowNanos - windowNanos, nowNanos].
     * A nonpositive window selects all retained samples, regardless of nowNanos.
     * Durations of selected frames are not clipped at the window boundaries.
     */
    public FrameSnapshot snapshot(long nowNanos, long windowNanos) {
        long[] timestamps;
        long[] intervals;
        long[] work;
        long[] render;
        long[] present;
        long[] tick;
        long[] gcPause;
        int[] counts;
        int[] flags;
        synchronized (this) {
            long start = windowNanos > 0 && nowNanos < Long.MIN_VALUE + windowNanos
                    ? Long.MIN_VALUE : nowNanos - windowNanos;
            int first = next - size;
            if (first < 0) {
                first += timestampsNanos.length;
            }
            int selected = 0;
            int index = first;
            for (int i = 0; i < size; i++) {
                long timestamp = timestampsNanos[index];
                if (windowNanos <= 0 || (timestamp >= start && timestamp <= nowNanos)) {
                    selected++;
                }
                index = advance(index);
            }
            timestamps = new long[selected];
            intervals = new long[selected];
            work = new long[selected];
            render = new long[selected];
            present = new long[selected];
            tick = new long[selected];
            gcPause = new long[selected];
            counts = new int[selected];
            flags = new int[selected];
            index = first;
            int output = 0;
            for (int i = 0; i < size; i++) {
                long timestamp = timestampsNanos[index];
                if (windowNanos <= 0 || (timestamp >= start && timestamp <= nowNanos)) {
                    timestamps[output] = timestamp;
                    intervals[output] = intervalsNanos[index];
                    work[output] = workNanos[index];
                    render[output] = renderNanos[index];
                    present[output] = presentNanos[index];
                    tick[output] = tickNanos[index];
                    gcPause[output] = gcPauseNanos[index];
                    counts[output] = tickCounts[index];
                    flags[output] = events[index];
                    output++;
                }
                index = advance(index);
            }
        }
        // Sorting and statistics do not hold the recording lock.
        return new FrameSnapshot(timestamps, intervals, work, render, present,
                tick, gcPause, counts, flags);
    }

    private int advance(int index) {
        return index + 1 == timestampsNanos.length ? 0 : index + 1;
    }
}
