package dev.framewatch.telemetry;

import java.util.Arrays;

/**
 * Detached chronological frame data and statistics computed at snapshot time.
 * Public arrays belong to this snapshot; modifying them cannot affect the ring
 * or another snapshot, but does not recalculate the statistics.
 *
 * All percentiles, including median, use nearest rank: the sorted sample at
 * ceil(p * sampleCount), with ranks starting at one. For an even sample count,
 * the median is therefore the lower middle sample, not an interpolated average.
 */
public final class FrameSnapshot {
    public static final int EVENT_GC = 1;
    public static final int EVENT_RESOURCE_RELOAD = 2;
    public static final int EVENT_LONG_FRAME = 4;
    public static final int EVENT_SHADER_CHANGE = 8;

    public final long[] timestampsNanos;
    public final long[] intervalsNanos;
    public final long[] workNanos;
    public final long[] renderNanos;
    public final long[] presentNanos;
    public final long[] tickNanos;
    public final long[] gcPauseNanos;
    public final int[] tickCounts;
    public final int[] events;
    public final int sampleCount;
    public final double fps;
    public final double meanMs;
    public final double medianMs;
    public final double p95Ms;
    public final double p99Ms;
    public final double maxMs;
    public final double onePercentLowFps;
    public final double gcPauseTotalMs;
    /** Number of selected frames with positive GC overlap, independent of flags. */
    public final int gcOverlapFrames;
    public final double latestWorkMs;
    public final double latestRenderMs;
    public final double latestPresentMs;
    public final double latestTickMs;

    // Takes ownership of arrays freshly allocated by FrameHistory.snapshot().
    FrameSnapshot(long[] timestampsNanos, long[] intervalsNanos, long[] workNanos,
            long[] renderNanos, long[] presentNanos, long[] tickNanos,
            long[] gcPauseNanos, int[] tickCounts, int[] events) {
        this.timestampsNanos = timestampsNanos;
        this.intervalsNanos = intervalsNanos;
        this.workNanos = workNanos;
        this.renderNanos = renderNanos;
        this.presentNanos = presentNanos;
        this.tickNanos = tickNanos;
        this.gcPauseNanos = gcPauseNanos;
        this.tickCounts = tickCounts;
        this.events = events;
        sampleCount = intervalsNanos.length;
        if (sampleCount == 0) {
            fps = meanMs = medianMs = p95Ms = p99Ms = maxMs = onePercentLowFps = 0.0;
            gcPauseTotalMs = 0.0;
            gcOverlapFrames = 0;
            latestWorkMs = latestRenderMs = latestPresentMs = latestTickMs = 0.0;
            return;
        }

        // Double accumulation avoids overflowing long for large durations.
        double intervalTotal = 0.0;
        double gcTotal = 0.0;
        int overlaps = 0;
        for (int i = 0; i < sampleCount; i++) {
            intervalTotal += intervalsNanos[i];
            gcTotal += gcPauseNanos[i];
            if (gcPauseNanos[i] > 0) {
                overlaps++;
            }
        }
        fps = 1_000_000_000.0 * sampleCount / intervalTotal;
        meanMs = intervalTotal / sampleCount / 1_000_000.0;
        long[] sorted = intervalsNanos.clone();
        Arrays.sort(sorted);
        medianMs = percentileMs(sorted, 50);
        p95Ms = percentileMs(sorted, 95);
        p99Ms = percentileMs(sorted, 99);
        maxMs = sorted[sampleCount - 1] / 1_000_000.0;

        // ceil(n / 100) computed with integer arithmetic, without overflow.
        int slowestCount = (int) ((sampleCount + 99L) / 100L);
        double slowestTotal = 0.0;
        for (int i = sampleCount - slowestCount; i < sampleCount; i++) {
            slowestTotal += sorted[i];
        }
        onePercentLowFps = 1_000_000_000.0 * slowestCount / slowestTotal;
        gcPauseTotalMs = gcTotal / 1_000_000.0;
        gcOverlapFrames = overlaps;
        int latest = sampleCount - 1;
        latestWorkMs = workNanos[latest] / 1_000_000.0;
        latestRenderMs = renderNanos[latest] / 1_000_000.0;
        latestPresentMs = presentNanos[latest] / 1_000_000.0;
        latestTickMs = tickNanos[latest] / 1_000_000.0;
    }

    private static double percentileMs(long[] sorted, int percentile) {
        int rank = (int) ((sorted.length * (long) percentile + 99L) / 100L);
        return sorted[rank - 1] / 1_000_000.0;
    }
}
