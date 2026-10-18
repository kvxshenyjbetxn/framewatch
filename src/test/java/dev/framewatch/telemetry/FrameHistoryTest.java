package dev.framewatch.telemetry;

import org.junit.Test;

import static org.junit.Assert.*;

public class FrameHistoryTest {
    private static final long MS = 1_000_000L;
    private static final double EPSILON = 0.000000001;

    @Test
    public void wraparoundPreservesEveryColumnInChronologicalOrder() {
        FrameHistory history = new FrameHistory(3);
        for (int i = 1; i <= 5; i++) {
            history.record(i * 100L, i * MS, i * 2L, i * 3L, i * 4L,
                    i * 5L, i, i * 6L, 1 << i);
        }
        FrameSnapshot snapshot = history.snapshot(0, 0);
        assertEquals(3, snapshot.sampleCount);
        assertArrayEquals(new long[] {300, 400, 500}, snapshot.timestampsNanos);
        assertArrayEquals(new long[] {3 * MS, 4 * MS, 5 * MS}, snapshot.intervalsNanos);
        assertArrayEquals(new long[] {6, 8, 10}, snapshot.workNanos);
        assertArrayEquals(new long[] {9, 12, 15}, snapshot.renderNanos);
        assertArrayEquals(new long[] {12, 16, 20}, snapshot.presentNanos);
        assertArrayEquals(new long[] {15, 20, 25}, snapshot.tickNanos);
        assertArrayEquals(new long[] {18, 24, 30}, snapshot.gcPauseNanos);
        assertArrayEquals(new int[] {3, 4, 5}, snapshot.tickCounts);
        assertArrayEquals(new int[] {8, 16, 32}, snapshot.events);
    }

    @Test
    public void positiveWindowIncludesBothBoundariesButNotFutureFrames() {
        FrameHistory history = new FrameHistory(4);
        for (int i = 1; i <= 4; i++) {
            record(history, i * 100, i * MS);
        }
        FrameSnapshot snapshot = history.snapshot(300, 100);
        assertArrayEquals(new long[] {200, 300}, snapshot.timestampsNanos);
        assertEquals(400.0, snapshot.fps, EPSILON);
        assertEquals(2.5, snapshot.meanMs, EPSILON);
        assertArrayEquals(new long[] {100, 200, 300, 400},
                history.snapshot(-1, 0).timestampsNanos);
        assertArrayEquals(new long[] {100, 200, 300, 400},
                history.snapshot(-1, -100).timestampsNanos);
        assertEmpty(history.snapshot(500, 50));
        assertEmpty(history.snapshot(50, 10));
    }

    @Test
    public void windowWorksAfterWraparoundAndSaturatesAtLongMinimum() {
        FrameHistory history = new FrameHistory(2);
        record(history, Long.MIN_VALUE, MS);
        record(history, Long.MIN_VALUE + 5, MS);
        assertArrayEquals(new long[] {Long.MIN_VALUE, Long.MIN_VALUE + 5},
                history.snapshot(Long.MIN_VALUE + 5, 10).timestampsNanos);
        record(history, Long.MIN_VALUE + 10, MS);
        assertArrayEquals(new long[] {Long.MIN_VALUE + 5, Long.MIN_VALUE + 10},
                history.snapshot(Long.MIN_VALUE + 10, 5).timestampsNanos);
    }

    @Test
    public void percentilesUseExactNearestRanksAndDoNotReorderRawFrames() {
        FrameHistory history = new FrameHistory(100);
        for (int i = 1; i <= 100; i++) {
            record(history, i, (101 - i) * MS);
        }
        FrameSnapshot snapshot = history.snapshot(100, 0);
        assertEquals(50.5, snapshot.meanMs, EPSILON);
        assertEquals(50.0, snapshot.medianMs, EPSILON);
        assertEquals(95.0, snapshot.p95Ms, EPSILON);
        assertEquals(99.0, snapshot.p99Ms, EPSILON);
        assertEquals(100.0, snapshot.maxMs, EPSILON);
        assertEquals(1000.0 * 100 / 5050, snapshot.fps, EPSILON);
        assertEquals(10.0, snapshot.onePercentLowFps, EPSILON);
        assertEquals(100 * MS, snapshot.intervalsNanos[0]);
        assertEquals(MS, snapshot.intervalsNanos[99]);
    }

    @Test
    public void onePercentLowUsesMeanOfCeilingCountNotPercentileOrMeanFps() {
        FrameHistory history = new FrameHistory(101);
        for (int i = 1; i <= 101; i++) {
            record(history, i, i * MS);
        }
        FrameSnapshot snapshot = history.snapshot(101, 0);
        assertEquals(51.0, snapshot.medianMs, EPSILON);
        assertEquals(96.0, snapshot.p95Ms, EPSILON);
        assertEquals(100.0, snapshot.p99Ms, EPSILON);
        assertEquals(1000.0 / 100.5, snapshot.onePercentLowFps, EPSILON);
    }

    @Test
    public void smallSamplesAndEvenMedianAreSafe() {
        FrameHistory history = new FrameHistory(2);
        record(history, 1, 2 * MS);
        FrameSnapshot one = history.snapshot(0, 0);
        assertEquals(500.0, one.fps, EPSILON);
        assertEquals(2.0, one.meanMs, EPSILON);
        assertEquals(2.0, one.medianMs, EPSILON);
        assertEquals(2.0, one.p95Ms, EPSILON);
        assertEquals(2.0, one.p99Ms, EPSILON);
        assertEquals(500.0, one.onePercentLowFps, EPSILON);
        record(history, 2, 8 * MS);
        FrameSnapshot two = history.snapshot(0, 0);
        assertEquals(2.0, two.medianMs, EPSILON);
        assertEquals(8.0, two.p95Ms, EPSILON);
        assertEquals(8.0, two.p99Ms, EPSILON);
        assertEquals(125.0, two.onePercentLowFps, EPSILON);
    }

    @Test
    public void gcTotalsAndLatestPhasesUseSelectedChronologicalSamples() {
        FrameHistory history = new FrameHistory(4);
        history.record(10, MS, MS, MS, MS, MS, 1, 9 * MS, 0);
        history.record(20, MS, 2 * MS, 3 * MS, 4 * MS, 5 * MS, 2, MS,
                FrameSnapshot.EVENT_GC | FrameSnapshot.EVENT_LONG_FRAME);
        history.record(30, MS, 6 * MS, 7 * MS, 8 * MS, 9 * MS, 3, 0,
                FrameSnapshot.EVENT_GC);
        history.record(40, MS, 10 * MS, 11 * MS, 12 * MS, 13 * MS, 4, 2 * MS,
                FrameSnapshot.EVENT_SHADER_CHANGE | FrameSnapshot.EVENT_RESOURCE_RELOAD);
        FrameSnapshot snapshot = history.snapshot(40, 20);
        assertEquals(3.0, snapshot.gcPauseTotalMs, EPSILON);
        assertEquals(2, snapshot.gcOverlapFrames);
        assertEquals(10.0, snapshot.latestWorkMs, EPSILON);
        assertEquals(11.0, snapshot.latestRenderMs, EPSILON);
        assertEquals(12.0, snapshot.latestPresentMs, EPSILON);
        assertEquals(13.0, snapshot.latestTickMs, EPSILON);
        assertEquals(5, snapshot.events[0]);
        assertEquals(10, snapshot.events[2]);
        assertEquals(2.0, history.snapshot(20, 1).latestWorkMs, EPSILON);
    }

    @Test
    public void snapshotsAreDetachedInBothDirectionsForEveryArray() {
        FrameHistory history = new FrameHistory(1);
        history.record(10, MS, 1, 2, 3, 4, 5, 6, 7);
        FrameSnapshot first = history.snapshot(0, 0);
        FrameSnapshot second = history.snapshot(0, 0);
        first.timestampsNanos[0] = 999;
        first.intervalsNanos[0] = 999;
        first.workNanos[0] = 999;
        first.renderNanos[0] = 999;
        first.presentNanos[0] = 999;
        first.tickNanos[0] = 999;
        first.gcPauseNanos[0] = 999;
        first.tickCounts[0] = 999;
        first.events[0] = 999;
        FrameSnapshot third = history.snapshot(0, 0);
        assertOriginalFrame(second);
        assertOriginalFrame(third);
        history.record(20, 2 * MS, 11, 12, 13, 14, 15, 16, 17);
        history.clear();
        assertOriginalFrame(second);
        assertOriginalFrame(third);
        assertEquals(1000.0, first.fps, EPSILON);
    }

    @Test
    public void clearResetsOrderingAndEmptySnapshotsHaveOnlyZeroStatistics() {
        FrameHistory history = new FrameHistory(1);
        assertEmpty(history.snapshot(0, 0));
        record(history, 100, MS);
        history.clear();
        assertEmpty(history.snapshot(100, 0));
        record(history, -100, MS);
        record(history, -100, 2 * MS);
        assertArrayEquals(new long[] {-100}, history.snapshot(0, 0).timestampsNanos);
    }

    @Test
    public void invalidRecordsDoNotChangeTheRingOrTimestampOrdering() {
        final FrameHistory history = new FrameHistory(2);
        record(history, 10, MS);
        expectIllegalArgument(new Runnable() {
            @Override public void run() { record(history, 11, 0); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { record(history, 11, -1); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { record(history, 9, MS); }
        });
        for (int field = 0; field < 6; field++) {
            final int negativeField = field;
            expectIllegalArgument(new Runnable() {
                @Override public void run() {
                    history.record(100, MS, negativeField == 0 ? -1 : 0,
                            negativeField == 1 ? -1 : 0, negativeField == 2 ? -1 : 0,
                            negativeField == 3 ? -1 : 0, negativeField == 4 ? -1 : 0,
                            negativeField == 5 ? -1 : 0, 0);
                }
            });
        }
        assertArrayEquals(new long[] {10}, history.snapshot(0, 0).timestampsNanos);
        record(history, 11, MS);
        assertArrayEquals(new long[] {10, 11}, history.snapshot(0, 0).timestampsNanos);
    }

    @Test
    public void nonpositiveCapacityIsRejected() {
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new FrameHistory(0); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new FrameHistory(-1); }
        });
    }

    @Test
    public void durationTotalsDoNotOverflowLong() {
        FrameHistory history = new FrameHistory(2);
        history.record(0, Long.MAX_VALUE, 0, 0, 0, 0, 0, Long.MAX_VALUE, 0);
        history.record(1, Long.MAX_VALUE, 0, 0, 0, 0, 0, Long.MAX_VALUE, 0);
        FrameSnapshot snapshot = history.snapshot(0, 0);
        assertEquals(1_000_000_000.0 / Long.MAX_VALUE, snapshot.fps, 0.0);
        assertEquals(Long.MAX_VALUE / 1_000_000.0, snapshot.meanMs, 0.0);
        assertEquals(2.0 * Long.MAX_VALUE / 1_000_000.0, snapshot.gcPauseTotalMs, 0.0);
        assertEquals(2, snapshot.gcOverlapFrames);
    }

    @Test
    public void copyRecentTruncatesToShorterBufferAndLeavesUnusedSlotsUntouched() {
        FrameHistory history = new FrameHistory(4);
        for (int i = 1; i <= 3; i++) {
            history.record(i, i * MS, 0, 0, 0, 0, 0, 0, i);
        }
        long[] intervals = new long[2];
        int[] flags = {-1, -1, -1, -1};
        assertEquals(2, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {2 * MS, 3 * MS}, intervals);
        assertArrayEquals(new int[] {2, 3, -1, -1}, flags);

        intervals = new long[] {-1, -1, -1, -1};
        flags = new int[1];
        assertEquals(1, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {3 * MS, -1, -1, -1}, intervals);
        assertArrayEquals(new int[] {3}, flags);
    }

    @Test
    public void copyRecentRemainsChronologicalAcrossWraparoundIncludingNextIndexZero() {
        FrameHistory history = new FrameHistory(3);
        for (int i = 1; i <= 5; i++) {
            history.record(i, i, 0, 0, 0, 0, 0, 0, i);
        }
        long[] intervals = new long[3];
        int[] flags = new int[3];
        assertEquals(3, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {3, 4, 5}, intervals);
        assertArrayEquals(new int[] {3, 4, 5}, flags);
        history.record(6, 6, 0, 0, 0, 0, 0, 0, 6);
        assertEquals(3, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {4, 5, 6}, intervals);
        assertArrayEquals(new int[] {4, 5, 6}, flags);
        history.record(7, 7, 0, 0, 0, 0, 0, 0, 7);
        intervals = new long[2];
        flags = new int[2];
        assertEquals(2, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {6, 7}, intervals);
        assertArrayEquals(new int[] {6, 7}, flags);
    }

    @Test
    public void copyRecentHandlesEmptyHistoryZeroLengthBuffersAndClear() {
        FrameHistory history = new FrameHistory(2);
        long[] intervals = {-1};
        int[] flags = {-1};
        assertEquals(0, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {-1}, intervals);
        assertArrayEquals(new int[] {-1}, flags);
        record(history, 10, MS);
        assertEquals(0, history.copyRecent(new long[0], flags));
        assertEquals(0, history.copyRecent(intervals, new int[0]));
        assertEquals(0, history.copyRecent(new long[0], new int[0]));
        assertArrayEquals(new long[] {-1}, intervals);
        assertArrayEquals(new int[] {-1}, flags);
        history.clear();
        assertEquals(0, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {-1}, intervals);
        assertArrayEquals(new int[] {-1}, flags);
    }

    @Test
    public void copyRecentHandlesCapacityOneAndOversizedBuffers() {
        FrameHistory history = new FrameHistory(1);
        history.record(1, 10, 0, 0, 0, 0, 0, 0, 4);
        history.record(2, 20, 0, 0, 0, 0, 0, 0, 8);
        long[] intervals = {-1, -1};
        int[] flags = {-1, -1};
        assertEquals(1, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {20, -1}, intervals);
        assertArrayEquals(new int[] {8, -1}, flags);
    }

    @Test
    public void copyRecentRejectsNullBuffersEvenWhenNothingCanBeCopied() {
        FrameHistory history = new FrameHistory(1);
        try {
            history.copyRecent(null, new int[0]);
            fail("Expected NullPointerException for intervals");
        } catch (NullPointerException expected) {
            assertEquals("intervals", expected.getMessage());
        }
        try {
            history.copyRecent(new long[0], null);
            fail("Expected NullPointerException for flags");
        } catch (NullPointerException expected) {
            assertEquals("flags", expected.getMessage());
        }
        record(history, 1, 1);
        long[] intervals = {-1};
        try {
            history.copyRecent(intervals, null);
            fail("Expected NullPointerException for flags");
        } catch (NullPointerException expected) {
            assertArrayEquals(new long[] {-1}, intervals);
        }
    }

    @Test
    public void copyRecentIncludesLateGcFlagsAndBufferWritesCannotChangeHistory() {
        FrameHistory history = new FrameHistory(3);
        history.record(10, 10, 0, 0, 0, 0, 0, 0, FrameSnapshot.EVENT_SHADER_CHANGE);
        history.record(20, 20, 0, 0, 0, 0, 0, 0, FrameSnapshot.EVENT_LONG_FRAME);
        long[] intervals = new long[2];
        int[] flags = new int[2];
        assertEquals(2, history.copyRecent(intervals, flags));
        assertArrayEquals(new int[] {8, 4}, flags);
        history.addGcActivity(12, 16);
        history.addGcActivity(10, 10);
        assertEquals(2, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {10, 20}, intervals);
        assertArrayEquals(new int[] {9, 5}, flags);
        intervals[0] = 999;
        flags[0] = 999;
        assertEquals(2, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {10, 20}, intervals);
        assertArrayEquals(new int[] {9, 5}, flags);
    }

    @Test
    public void copyRecentUses240SampleBuffersWithLargeWrappedHistory() {
        FrameHistory history = new FrameHistory(20_000);
        for (int i = 1; i <= 20_100; i++) {
            history.record(i, i, 0, 0, 0, 0, 0, 0, i & 15);
        }
        long[] intervals = new long[240];
        int[] flags = new int[240];
        assertEquals(240, history.copyRecent(intervals, flags));
        for (int i = 0; i < intervals.length; i++) {
            int expected = 20_100 - 239 + i;
            assertEquals(expected, intervals[i]);
            assertEquals(expected & 15, flags[i]);
        }
    }

    private static void record(FrameHistory history, long timestamp, long interval) {
        history.record(timestamp, interval, 0, 0, 0, 0, 0, 0, 0);
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void assertOriginalFrame(FrameSnapshot snapshot) {
        assertArrayEquals(new long[] {10}, snapshot.timestampsNanos);
        assertArrayEquals(new long[] {MS}, snapshot.intervalsNanos);
        assertArrayEquals(new long[] {1}, snapshot.workNanos);
        assertArrayEquals(new long[] {2}, snapshot.renderNanos);
        assertArrayEquals(new long[] {3}, snapshot.presentNanos);
        assertArrayEquals(new long[] {4}, snapshot.tickNanos);
        assertArrayEquals(new long[] {6}, snapshot.gcPauseNanos);
        assertArrayEquals(new int[] {5}, snapshot.tickCounts);
        assertArrayEquals(new int[] {7}, snapshot.events);
    }

    private static void assertEmpty(FrameSnapshot snapshot) {
        assertEquals(0, snapshot.sampleCount);
        assertEquals(0, snapshot.timestampsNanos.length);
        assertEquals(0, snapshot.intervalsNanos.length);
        assertEquals(0, snapshot.workNanos.length);
        assertEquals(0, snapshot.renderNanos.length);
        assertEquals(0, snapshot.presentNanos.length);
        assertEquals(0, snapshot.tickNanos.length);
        assertEquals(0, snapshot.gcPauseNanos.length);
        assertEquals(0, snapshot.tickCounts.length);
        assertEquals(0, snapshot.events.length);
        assertEquals(0.0, snapshot.fps, 0.0);
        assertEquals(0.0, snapshot.meanMs, 0.0);
        assertEquals(0.0, snapshot.medianMs, 0.0);
        assertEquals(0.0, snapshot.p95Ms, 0.0);
        assertEquals(0.0, snapshot.p99Ms, 0.0);
        assertEquals(0.0, snapshot.maxMs, 0.0);
        assertEquals(0.0, snapshot.onePercentLowFps, 0.0);
        assertEquals(0.0, snapshot.gcPauseTotalMs, 0.0);
        assertEquals(0, snapshot.gcOverlapFrames);
        assertEquals(0.0, snapshot.latestWorkMs, 0.0);
        assertEquals(0.0, snapshot.latestRenderMs, 0.0);
        assertEquals(0.0, snapshot.latestPresentMs, 0.0);
        assertEquals(0.0, snapshot.latestTickMs, 0.0);
    }
}
