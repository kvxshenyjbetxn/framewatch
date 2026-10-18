package dev.framewatch.telemetry;

import java.math.BigInteger;
import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.*;

public class FrameGraphBucketsTest {
    @Test
    public void peaksPreserveStuttersAndOrAllEventsIncludingNonPeakSamples() {
        FrameHistory history = new FrameHistory(8);
        record(history, 10, 7, FrameSnapshot.EVENT_RESOURCE_RELOAD);
        record(history, 11, 500, FrameSnapshot.EVENT_LONG_FRAME);
        record(history, 12, 3, Integer.MIN_VALUE);
        record(history, 60, 9, FrameSnapshot.EVENT_SHADER_CHANGE);
        assertBuckets(history, 100, 100, new long[] {500, 0, 9, 0},
                new int[] {FrameSnapshot.EVENT_RESOURCE_RELOAD | FrameSnapshot.EVENT_LONG_FRAME
                        | Integer.MIN_VALUE, 0, FrameSnapshot.EVENT_SHADER_CHANGE, 0});
    }

    @Test
    public void inclusiveStartAndNowAndExactBucketBoundaries() {
        FrameHistory history = new FrameHistory(8);
        record(history, -1, 999, 128);
        record(history, 0, 1, 1);
        record(history, 24, 2, 2);
        record(history, 25, 3, 4);
        record(history, 50, 4, 8);
        record(history, 75, 5, 16);
        record(history, 100, 6, 32);
        record(history, 101, 999, 128);
        assertBuckets(history, 100, 100, new long[] {2, 3, 4, 6},
                new int[] {3, 4, 8, 48});
    }

    @Test
    public void fractionalBoundariesAndWindowSmallerThanWidthStayInBounds() {
        FrameHistory history = new FrameHistory(11);
        for (int i = 0; i <= 10; i++) {
            record(history, i, i + 1, 0);
        }
        assertBuckets(history, 10, 10, new long[] {4, 7, 11}, new int[3]);
        assertBuckets(history, 10, 2, new long[] {9, 0, 10, 0, 11}, new int[5]);
    }

    @Test
    public void gapsRemainEmptyAndIntervalsAreNeitherClippedNorSpread() {
        FrameHistory history = new FrameHistory(2);
        record(history, 20, 1_000, 2);
        record(history, 90, 2_000, 4);
        assertBuckets(history, 100, 100, new long[] {0, 0, 1_000, 0, 0, 0, 0, 0, 0, 2_000},
                new int[] {0, 0, 2, 0, 0, 0, 0, 0, 0, 4});
    }

    @Test
    public void windowUsesWallClockNotSampleCountAndExcludesFutureFrames() {
        FrameHistory history = new FrameHistory(6);
        record(history, 0, 999, 128);
        record(history, 10, 2, 2);
        record(history, 40, 4, 4);
        record(history, 90, 8, 8);
        record(history, 100, 16, 16);
        record(history, 101, 999, 128);
        assertBuckets(history, 100, 100, new long[] {999, 4, 0, 16},
                new int[] {130, 4, 0, 24});
        assertBuckets(history, 100, 60, new long[] {4, 0, 0, 16}, new int[] {4, 0, 0, 24});
        assertBuckets(history, 100, 10, new long[] {8, 0, 0, 16}, new int[] {8, 0, 0, 16});
        assertBuckets(history, 200, 20, new long[4], new int[4]);
        assertBuckets(history, -10, 20, new long[4], new int[4]);
    }

    @Test
    public void wrappedRingAndDuplicateStartTimestampsAreAllSelected() {
        FrameHistory history = new FrameHistory(5);
        record(history, 0, 999, 128);
        record(history, 1, 999, 128);
        record(history, 10, 9, 1);
        record(history, 10, 2, 2);
        record(history, 10, 3, 4);
        record(history, 20, 4, 8);
        record(history, 30, 5, 16);
        assertBuckets(history, 30, 20, new long[] {9, 4, 5}, new int[] {7, 8, 16});
        record(history, 30, 6, 32);
        record(history, 30, 7, 64);
        record(history, 30, 8, 128);
        // next is now zero; duplicates at now must still occupy the last bucket.
        assertBuckets(history, 30, 20, new long[] {0, 4, 8}, new int[] {0, 8, 240});
    }

    @Test
    public void ringCapacityExpiryLeavesZerosInsteadOfStretchingRemainingFrames() {
        FrameHistory history = new FrameHistory(3);
        for (int i = 0; i <= 5; i++) {
            record(history, i * 10L, i + 1, 1 << i);
        }
        assertBuckets(history, 60, 60, new long[] {0, 0, 0, 4, 5, 6},
                new int[] {0, 0, 0, 8, 16, 32});
        assertBuckets(history, 100, 10, new long[6], new int[6]);
    }

    @Test
    public void narrowWindowWorksInLargeWrappedRing() {
        FrameHistory history = new FrameHistory(20_000);
        for (int i = 0; i < 40_005; i++) {
            record(history, i, i + 1, i & 15);
        }
        assertBuckets(history, 40_002, 3, new long[] {40_000, 40_001, 40_003},
                new int[] {15, 0, 3});
    }

    @Test
    public void reusedBuffersAndBothMismatchedTailsAreFullyCleared() {
        FrameHistory history = new FrameHistory(1);
        record(history, 100, 7, 8);
        long[] peaks = {99, 99, 99, 99, 99};
        int[] flags = {99, 99};
        assertEquals(2, history.copyTimeBuckets(100, 100, peaks, flags));
        assertArrayEquals(new long[] {0, 7, 0, 0, 0}, peaks);
        assertArrayEquals(new int[] {0, 8}, flags);
        assertEquals(2, history.copyTimeBuckets(300, 100, peaks, flags));
        assertArrayEquals(new long[5], peaks);
        assertArrayEquals(new int[2], flags);

        peaks = new long[] {99, 99};
        flags = new int[] {99, 99, 99, 99, 99};
        assertEquals(2, history.copyTimeBuckets(100, 100, peaks, flags));
        assertArrayEquals(new long[] {0, 7}, peaks);
        assertArrayEquals(new int[] {0, 8, 0, 0, 0}, flags);
    }

    @Test
    public void emptyAndClearedHistoryReturnWidthAndClearBuffers() {
        FrameHistory history = new FrameHistory(1);
        long[] peaks = {99, 99, 99};
        int[] flags = {99, 99, 99};
        assertEquals(3, history.copyTimeBuckets(0, 1, peaks, flags));
        assertArrayEquals(new long[3], peaks);
        assertArrayEquals(new int[3], flags);
        record(history, 0, 7, 8);
        history.copyTimeBuckets(0, 1, peaks, flags);
        history.clear();
        assertEquals(3, history.copyTimeBuckets(0, 1, peaks, flags));
        assertArrayEquals(new long[3], peaks);
        assertArrayEquals(new int[3], flags);
    }

    @Test
    public void zeroWidthStillClearsTheOtherBuffer() {
        FrameHistory history = new FrameHistory(1);
        record(history, 0, 7, 8);
        long[] peaks = {99, 99};
        int[] flags = {99, 99};
        assertEquals(0, history.copyTimeBuckets(0, 1, peaks, new int[0]));
        assertArrayEquals(new long[2], peaks);
        assertEquals(0, history.copyTimeBuckets(0, 1, new long[0], flags));
        assertArrayEquals(new int[2], flags);
        assertEquals(0, history.copyTimeBuckets(0, 1, new long[0], new int[0]));
    }

    @Test
    public void rejectsNullAndNonpositiveWindowsEvenWithEmptyOutput() {
        FrameHistory history = new FrameHistory(1);
        expectRejected(history, 1, null, new int[0], NullPointerException.class);
        expectRejected(history, 1, new long[0], null, NullPointerException.class);
        expectRejected(history, 1, null, null, NullPointerException.class);
        for (long window : new long[] {0, -1, Long.MIN_VALUE}) {
            expectRejected(history, window, new long[0], new int[0], IllegalArgumentException.class);
        }
        long[] peaks = {99};
        int[] flags = {99};
        expectRejected(history, 0, peaks, flags, IllegalArgumentException.class);
        assertArrayEquals(new long[] {99}, peaks);
        assertArrayEquals(new int[] {99}, flags);
    }

    @Test
    public void negativeNanoTimeAndWindowCrossingZeroAreSupported() {
        FrameHistory history = new FrameHistory(5);
        record(history, -101, 999, 128);
        record(history, -100, 1, 1);
        record(history, -75, 2, 2);
        record(history, -50, 3, 4);
        record(history, -1, 4, 8);
        assertBuckets(history, -1, 99, new long[] {1, 2, 3, 4}, new int[] {1, 2, 4, 8});
        assertBuckets(history, 25, 125, new long[] {2, 3, 0, 4}, new int[] {3, 4, 0, 8});
    }

    @Test
    public void saturatedStartDoesNotWrapOrChangeTheNominalWindow() {
        FrameHistory history = new FrameHistory(5);
        record(history, Long.MIN_VALUE, 1, 1);
        record(history, Long.MIN_VALUE + 24, 2, 2);
        record(history, Long.MIN_VALUE + 25, 3, 4);
        record(history, Long.MIN_VALUE + 50, 4, 8);
        record(history, Long.MIN_VALUE + 51, 999, 128);
        assertBuckets(history, Long.MIN_VALUE + 50, 100, new long[] {0, 0, 2, 4},
                new int[] {0, 0, 3, 12});
        assertBuckets(history, Long.MIN_VALUE, Long.MAX_VALUE, new long[] {0, 0, 0, 1},
                new int[] {0, 0, 0, 1});
    }

    @Test
    public void largeWindowsUseExactBucketsWithoutLongOrIntMultiplicationOverflow() {
        for (long window : new long[] {1L << 62, Long.MAX_VALUE - 13, Long.MAX_VALUE}) {
            for (int width : new int[] {1, 3, 240, 65_537}) {
                FrameHistory history = new FrameHistory(16);
                long[] expectedPeaks = new long[width];
                int[] expectedFlags = new int[width];
                recordWithOracle(history, window, window, width, expectedPeaks, expectedFlags, 0, 1, 1);
                // Samples immediately before, at, and after exact rational boundaries.
                int previousBoundary = 0;
                for (int b : new int[] {1, width / 2, width - 1}) {
                    if (b <= previousBoundary || b >= width) {
                        continue;
                    }
                    previousBoundary = b;
                    BigInteger divisor = BigInteger.valueOf(width);
                    long boundary = BigInteger.valueOf(window).multiply(BigInteger.valueOf(b))
                            .add(divisor.subtract(BigInteger.ONE)).divide(divisor).longValueExact();
                    for (int delta = -1; delta <= 1; delta++) {
                        recordWithOracle(history, window, window, width, expectedPeaks, expectedFlags,
                                boundary + delta, b + delta + 2L, 1 << (delta + 1));
                    }
                }
                recordWithOracle(history, window, window, width, expectedPeaks, expectedFlags,
                        window - 1, Long.MAX_VALUE, Integer.MIN_VALUE);
                recordWithOracle(history, window, window, width, expectedPeaks, expectedFlags,
                        window, 3, 16);
                assertBuckets(history, window, window, expectedPeaks, expectedFlags);
            }
        }
    }

    @Test
    public void maximumWindowWithNegativeTimestampsUsesSafeDifferences() {
        FrameHistory history = new FrameHistory(8);
        long[] peaks = new long[7];
        int[] flags = new int[7];
        long now = -1;
        long window = Long.MAX_VALUE;
        long[] timestamps = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -window / 2, -2, -1};
        for (int i = 0; i < timestamps.length; i++) {
            recordWithOracle(history, now, window, 7, peaks, flags, timestamps[i], i + 1, 1 << i);
        }
        record(history, 0, Long.MAX_VALUE, Integer.MIN_VALUE);
        assertBuckets(history, now, window, peaks, flags);
    }

    @Test
    public void singleBucketIncludesLatePointGcAndOutputWritesCannotChangeHistory() {
        FrameHistory history = new FrameHistory(1);
        record(history, 10, 5, FrameSnapshot.EVENT_LONG_FRAME);
        long[] peaks = {99, 99, 99};
        int[] flags = {99};
        assertEquals(1, history.copyTimeBuckets(10, 10, peaks, flags));
        assertArrayEquals(new long[] {5, 0, 0}, peaks);
        assertArrayEquals(new int[] {FrameSnapshot.EVENT_LONG_FRAME}, flags);
        history.addGcActivity(10, 10);
        assertEquals(1, history.copyTimeBuckets(10, 10, peaks, flags));
        assertArrayEquals(new int[] {FrameSnapshot.EVENT_LONG_FRAME | FrameSnapshot.EVENT_GC}, flags);
        peaks[0] = Long.MAX_VALUE;
        flags[0] = Integer.MIN_VALUE;
        assertBuckets(history, 10, 10, new long[] {5},
                new int[] {FrameSnapshot.EVENT_LONG_FRAME | FrameSnapshot.EVENT_GC});
        assertArrayEquals(new long[] {0}, history.snapshot(0, 0).gcPauseNanos);
    }

    @Test
    public void lateGcIsOrredIntoNonPeakSamplesAndExistingApisStayUnchanged() {
        FrameHistory history = new FrameHistory(3);
        record(history, 10, 20, FrameSnapshot.EVENT_LONG_FRAME);
        record(history, 20, 5, FrameSnapshot.EVENT_SHADER_CHANGE);
        FrameSnapshot before = history.snapshot(0, 0);
        assertBuckets(history, 50, 50, new long[] {20, 0}, new int[] {12, 0});
        history.addGcActivity(16, 18);
        assertBuckets(history, 50, 50, new long[] {20, 0}, new int[] {13, 0});
        FrameSnapshot after = history.snapshot(0, 0);
        assertArrayEquals(before.timestampsNanos, after.timestampsNanos);
        assertArrayEquals(before.intervalsNanos, after.intervalsNanos);
        assertEquals(before.meanMs, after.meanMs, 0.0);
        assertArrayEquals(new long[] {0, 2}, after.gcPauseNanos);
        assertArrayEquals(new int[] {4, 9}, after.events);
        assertArrayEquals(new int[] {4, 8}, before.events);
        long[] intervals = {99, 99, 99};
        int[] flags = {99, 99, 99};
        assertEquals(2, history.copyRecent(intervals, flags));
        assertArrayEquals(new long[] {20, 5, 99}, intervals);
        assertArrayEquals(new int[] {4, 9, 99}, flags);
        Arrays.fill(intervals, 0);
        Arrays.fill(flags, 0);
        assertBuckets(history, 50, 50, new long[] {20, 0}, new int[] {13, 0});
    }

    private static void record(FrameHistory history, long timestamp, long interval, int flags) {
        history.record(timestamp, interval, 0, 0, 0, 0, 0, 0, flags);
    }

    private static void assertBuckets(FrameHistory history, long now, long window,
            long[] expectedPeaks, int[] expectedFlags) {
        long[] peaks = new long[expectedPeaks.length];
        int[] flags = new int[expectedFlags.length];
        Arrays.fill(peaks, -1L);
        Arrays.fill(flags, -1);
        assertEquals(expectedPeaks.length, history.copyTimeBuckets(now, window, peaks, flags));
        assertArrayEquals(expectedPeaks, peaks);
        assertArrayEquals(expectedFlags, flags);
    }

    private static void recordWithOracle(FrameHistory history, long now, long window, int width,
            long[] peaks, int[] flags, long timestamp, long interval, int events) {
        record(history, timestamp, interval, events);
        BigInteger span = BigInteger.valueOf(window);
        BigInteger offset = BigInteger.valueOf(timestamp).subtract(BigInteger.valueOf(now).subtract(span));
        int bucket = Math.min(width - 1, offset.multiply(BigInteger.valueOf(width)).divide(span).intValueExact());
        peaks[bucket] = Math.max(peaks[bucket], interval);
        flags[bucket] |= events;
    }

    private static void expectRejected(FrameHistory history, long window, long[] peaks, int[] flags,
            Class<? extends RuntimeException> type) {
        try {
            history.copyTimeBuckets(0, window, peaks, flags);
            fail("Expected " + type.getSimpleName());
        } catch (RuntimeException expected) {
            assertTrue("Unexpected exception: " + expected, type.isInstance(expected));
        }
    }
}
