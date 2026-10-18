package dev.framewatch.telemetry;

import org.junit.Test;

import static org.junit.Assert.*;

public class FrameGcActivityTest {
    @Test
    public void lateNotificationAnnotatesRetainedFrameWithoutChangingOldSnapshot() {
        FrameHistory history = new FrameHistory(3);
        history.record(10, 10, 0, 0, 0, 0, 0, 0, FrameSnapshot.EVENT_LONG_FRAME);
        FrameSnapshot before = history.snapshot(0, 0);
        history.addGcActivity(2, 6);
        FrameSnapshot after = history.snapshot(0, 0);
        assertArrayEquals(new long[] {4}, after.gcPauseNanos);
        assertArrayEquals(new int[] {FrameSnapshot.EVENT_GC | FrameSnapshot.EVENT_LONG_FRAME}, after.events);
        assertEquals(0.000004, after.gcPauseTotalMs, 0.0);
        assertEquals(1, after.gcOverlapFrames);
        assertArrayEquals(new long[] {0}, before.gcPauseNanos);
        assertArrayEquals(new int[] {FrameSnapshot.EVENT_LONG_FRAME}, before.events);
    }

    @Test
    public void earlyNotificationAnnotatesFrameWhenItIsRecorded() {
        FrameHistory history = new FrameHistory(2);
        history.addGcActivity(3, 8);
        record(history, 10, 10);
        assertGc(history, new long[] {5}, new int[] {1});
    }

    @Test
    public void spanningNotificationIsSplitEquallyForEarlyAndLateDelivery() {
        FrameHistory early = new FrameHistory(2);
        early.addGcActivity(5, 15);
        record(early, 10, 10);
        record(early, 20, 10);
        FrameHistory late = new FrameHistory(2);
        record(late, 10, 10);
        record(late, 20, 10);
        late.addGcActivity(5, 15);
        assertGc(early, new long[] {5, 5}, new int[] {1, 1});
        assertGc(late, new long[] {5, 5}, new int[] {1, 1});
    }

    @Test
    public void notificationCanArriveBetweenTheFramesItSpans() {
        FrameHistory history = new FrameHistory(2);
        record(history, 10, 10);
        history.addGcActivity(8, 14);
        record(history, 20, 10);
        assertGc(history, new long[] {2, 4}, new int[] {1, 1});
    }

    @Test
    public void endpointTouchesDoNotCountAndExactFrameBoundariesDoNotDoubleCount() {
        FrameHistory history = new FrameHistory(4);
        record(history, 10, 10);
        record(history, 20, 10);
        history.addGcActivity(-10, 0);
        history.addGcActivity(20, 30);
        assertGc(history, new long[] {0, 0}, new int[] {0, 0});
        history.addGcActivity(0, 10);
        assertGc(history, new long[] {10, 0}, new int[] {1, 0});
        history.addGcActivity(10, 20);
        assertGc(history, new long[] {10, 10}, new int[] {1, 1});
        record(history, 30, 10);
        assertGc(history, new long[] {10, 10, 10}, new int[] {1, 1, 1});
    }

    @Test
    public void zeroDurationAtSharedBoundaryMarksOnlyFrameEndingThere() {
        FrameHistory early = new FrameHistory(2);
        early.addGcActivity(10, 10);
        record(early, 10, 10);
        record(early, 20, 10);
        early.addGcActivity(10, 10);
        FrameHistory late = new FrameHistory(2);
        record(late, 10, 10);
        record(late, 20, 10);
        late.addGcActivity(10, 10);
        late.addGcActivity(10, 10);
        assertGc(early, new long[] {0, 0}, new int[] {1, 0});
        assertGc(late, new long[] {0, 0}, new int[] {1, 0});
        assertEquals(0, early.snapshot(0, 0).gcOverlapFrames);
        assertEquals(0.0, early.snapshot(0, 0).gcPauseTotalMs, 0.0);
    }

    @Test
    public void zeroDurationMarksNearestIntersectingFrameOnlyEvenWithOverlappingFrames() {
        FrameHistory history = new FrameHistory(3);
        record(history, 10, 10);
        record(history, 20, 20);
        history.addGcActivity(5, 5);
        record(history, 30, 30);
        assertGc(history, new long[] {0, 0, 0}, new int[] {1, 0, 0});
    }

    @Test
    public void zeroDurationOutsideFramesWaitsForAnIntersection() {
        FrameHistory history = new FrameHistory(3);
        record(history, 10, 10);
        history.addGcActivity(0, 0);
        history.addGcActivity(15, 15);
        history.addGcActivity(30, 30);
        assertGc(history, new long[] {0}, new int[] {0});
        record(history, 20, 10);
        record(history, 30, 10);
        assertGc(history, new long[] {0, 0, 0}, new int[] {0, 1, 1});
    }

    @Test
    public void noOverlapAndFrameEvictionLeaveOnlyRetainedIntersections() {
        FrameHistory history = new FrameHistory(2);
        record(history, 10, 10);
        record(history, 20, 10);
        record(history, 30, 10);
        history.addGcActivity(0, 10);
        assertGc(history, new long[] {0, 0}, new int[] {0, 0});
        history.addGcActivity(15, 25);
        assertGc(history, new long[] {5, 5}, new int[] {1, 1});
        FrameSnapshot window = history.snapshot(30, 5);
        assertArrayEquals(new long[] {30}, window.timestampsNanos);
        assertArrayEquals(new long[] {5}, window.gcPauseNanos);
        assertEquals(0.000005, window.gcPauseTotalMs, 0.0);
    }

    @Test
    public void notificationRingEvictsOldestDeliveredEventsBeforeFutureRecording() {
        FrameHistory history = new FrameHistory(2);
        history.addGcActivity(1, 2);
        history.addGcActivity(11, 12);
        history.addGcActivity(21, 22);
        record(history, 10, 10);
        assertGc(history, new long[] {0}, new int[] {0});
        record(history, 20, 10);
        record(history, 30, 10);
        assertGc(history, new long[] {1, 1}, new int[] {1, 1});
    }

    @Test
    public void notificationEvictionDoesNotUndoAlreadyAnnotatedFrames() {
        FrameHistory history = new FrameHistory(1);
        record(history, 10, 10);
        history.addGcActivity(1, 2);
        history.addGcActivity(3, 4);
        history.addGcActivity(20, 25);
        assertGc(history, new long[] {2}, new int[] {1});
    }

    @Test
    public void exactDuplicatesBeforeAndAfterRecordingAreIgnoredAndDoNotEvictEvents() {
        FrameHistory history = new FrameHistory(2);
        history.addGcActivity(2, 6);
        history.addGcActivity(12, 16);
        history.addGcActivity(12, 16);
        record(history, 10, 10);
        record(history, 20, 10);
        history.addGcActivity(2, 6);
        history.addGcActivity(12, 16);
        assertGc(history, new long[] {4, 4}, new int[] {1, 1});
    }

    @Test
    public void distinctNotificationsMayArriveOutOfOrderAndAreSummed() {
        FrameHistory history = new FrameHistory(4);
        history.addGcActivity(15, 18);
        history.addGcActivity(2, 6);
        record(history, 10, 10);
        record(history, 20, 10);
        history.addGcActivity(5, 7);
        assertGc(history, new long[] {6, 3}, new int[] {1, 1});
    }

    @Test
    public void explicitGcDurationRemainsAdditiveForBothDeliveryOrders() {
        FrameHistory early = new FrameHistory(1);
        early.addGcActivity(2, 6);
        early.record(10, 10, 0, 0, 0, 0, 0, 3, 0);
        FrameHistory late = new FrameHistory(1);
        late.record(10, 10, 0, 0, 0, 0, 0, 3, 0);
        late.addGcActivity(2, 6);
        assertGc(early, new long[] {7}, new int[] {1});
        assertGc(late, new long[] {7}, new int[] {1});
    }

    @Test
    public void clearDropsNotificationsAndResetsDuplicateAndPointAssignmentState() {
        FrameHistory history = new FrameHistory(2);
        history.addGcActivity(2, 6);
        history.addGcActivity(10, 10);
        record(history, 10, 10);
        history.clear();
        record(history, 10, 10);
        assertGc(history, new long[] {0}, new int[] {0});
        history.addGcActivity(2, 6);
        assertGc(history, new long[] {4}, new int[] {1});
        history.clear();
        history.addGcActivity(10, 10);
        record(history, 10, 10);
        assertGc(history, new long[] {0}, new int[] {1});
    }

    @Test
    public void reversedAndOverflowingDurationsAreRejectedWithoutEvictingValidEvents() {
        FrameHistory history = new FrameHistory(1);
        history.addGcActivity(2, 6);
        expectRejected(history, 6, 2);
        expectRejected(history, Long.MIN_VALUE, Long.MAX_VALUE);
        expectRejected(history, -1, Long.MAX_VALUE);
        record(history, 10, 10);
        assertGc(history, new long[] {4}, new int[] {1});
    }

    @Test
    public void negativeTimestampsAndUnderflowingFrameStartsAreSupported() {
        FrameHistory history = new FrameHistory(2);
        history.addGcActivity(-8, -3);
        record(history, -1, 10);
        assertGc(history, new long[] {5}, new int[] {1});
        history.clear();
        history.addGcActivity(Long.MIN_VALUE, Long.MIN_VALUE + 3);
        record(history, Long.MIN_VALUE + 5, 10);
        assertGc(history, new long[] {3}, new int[] {1});
        history.clear();
        history.addGcActivity(Long.MIN_VALUE, Long.MIN_VALUE);
        record(history, Long.MIN_VALUE, 10);
        assertGc(history, new long[] {0}, new int[] {1});
    }

    @Test
    public void explicitPlusNotificationDurationSaturatesInsteadOfWrappingNegative() {
        FrameHistory history = new FrameHistory(1);
        history.record(10, 10, 0, 0, 0, 0, 0, Long.MAX_VALUE - 1, 0);
        history.addGcActivity(2, 6);
        assertGc(history, new long[] {Long.MAX_VALUE}, new int[] {1});
    }

    @Test
    public void gcRingIsBoundedTo256AndWrapsIndependentlyOfLargeFrameHistory() {
        FrameHistory history = new FrameHistory(20_000);
        for (int i = 1; i <= 1024; i++) {
            history.addGcActivity(i * 2L, i * 2L + 1);
        }
        // Duplicates within the retained 256 must not evict their oldest entry.
        history.addGcActivity(769 * 2L, 769 * 2L + 1);
        history.addGcActivity(1024 * 2L, 1024 * 2L + 1);
        for (int i = 1; i <= 1024; i++) {
            record(history, i * 2L + 1, 1);
        }
        FrameSnapshot snapshot = history.snapshot(0, 0);
        assertEquals(1024, snapshot.sampleCount);
        assertEquals(256, snapshot.gcOverlapFrames);
        for (int i = 0; i < snapshot.sampleCount; i++) {
            long expectedPause = i < 768 ? 0 : 1;
            int expectedFlags = i < 768 ? 0 : FrameSnapshot.EVENT_GC;
            assertEquals("GC duration for frame " + i, expectedPause, snapshot.gcPauseNanos[i]);
            assertEquals("GC flags for frame " + i, expectedFlags, snapshot.events[i]);
        }
    }

    @Test
    public void boundedGcEvictionPreservesExistingAnnotationsButExcludesFutureOverlap() {
        FrameHistory history = new FrameHistory(20_000);
        record(history, 1, 1);
        history.addGcActivity(0, 1);
        for (int i = 1; i <= 256; i++) {
            history.addGcActivity(i * 2L, i * 2L + 1);
        }
        record(history, 2, 2);
        assertGc(history, new long[] {1, 0}, new int[] {1, 0});
    }

    private static void record(FrameHistory history, long timestamp, long interval) {
        history.record(timestamp, interval, 0, 0, 0, 0, 0, 0, 0);
    }

    private static void assertGc(FrameHistory history, long[] pause, int[] flags) {
        FrameSnapshot snapshot = history.snapshot(0, 0);
        assertArrayEquals(pause, snapshot.gcPauseNanos);
        assertArrayEquals(flags, snapshot.events);
    }

    private static void expectRejected(FrameHistory history, long start, long end) {
        try {
            history.addGcActivity(start, end);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Invalid notification must not change retained state.
        }
    }
}
