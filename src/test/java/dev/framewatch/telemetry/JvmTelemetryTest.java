package dev.framewatch.telemetry;

import org.junit.Test;

import static org.junit.Assert.*;

public class JvmTelemetryTest {
    @Test
    public void allocationRateUsesAllocatedByteDeltaAndBinaryMebibytes() {
        assertEquals(2.0, JvmTelemetry.allocationRate(123, 123 + 4 * 1024 * 1024, 2_000_000_000L), 0.0);
        assertEquals(0.5, JvmTelemetry.allocationRate(123, 123 + 256 * 1024, 500_000_000L), 0.0);
        assertEquals(0.0, JvmTelemetry.allocationRate(123, 123, 1_000_000_000L), 0.0);
    }

    @Test
    public void firstUnavailableAndResetAllocationCountersHaveNoEstimate() {
        assertEquals(-1.0, JvmTelemetry.allocationRate(-1, 1024, 1_000_000_000L), 0.0);
        assertEquals(-1.0, JvmTelemetry.allocationRate(1024, -1, 1_000_000_000L), 0.0);
        assertEquals(-1.0, JvmTelemetry.allocationRate(-1, -1, 1_000_000_000L), 0.0);
        assertEquals(-1.0, JvmTelemetry.allocationRate(1024, 0, 1_000_000_000L), 0.0);
    }

    @Test
    public void nonpositiveAllocationIntervalsAreUnavailable() {
        assertEquals(-1.0, JvmTelemetry.allocationRate(0, 1024, 0), 0.0);
        assertEquals(-1.0, JvmTelemetry.allocationRate(0, 1024, -1), 0.0);
    }

    @Test
    public void largeAllocationDeltaDoesNotOverflowIntegerArithmetic() {
        double expected = Long.MAX_VALUE / (1024.0 * 1024.0);
        assertEquals(expected, JvmTelemetry.allocationRate(0, Long.MAX_VALUE, 1_000_000_000L), expected * 1e-15);
    }

    @Test
    public void uptimeConversionUsesFixedAnchorForPastAndFutureEvents() {
        long anchor = 12_345_678_901L;
        assertEquals(anchor, JvmTelemetry.uptimeToNanos(1000, 1000, anchor));
        assertEquals(anchor - 7_000_000L, JvmTelemetry.uptimeToNanos(993, 1000, anchor));
        assertEquals(anchor + 3_000_000L, JvmTelemetry.uptimeToNanos(1003, 1000, anchor));
    }

    @Test
    public void negativeNanoTimeAnchorsAndJvmStartAreValid() {
        assertEquals(-5_000_000L, JvmTelemetry.uptimeToNanos(0, 3, -2_000_000L));
        assertEquals(1_000_000L, JvmTelemetry.uptimeToNanos(6, 3, -2_000_000L));
    }

    @Test
    public void convertedGcIntervalPreservesMillisecondDuration() {
        long start = JvmTelemetry.uptimeToNanos(17, 30, -99);
        long end = JvmTelemetry.uptimeToNanos(21, 30, -99);
        assertEquals(4_000_000L, end - start);
        assertEquals(start, JvmTelemetry.uptimeToNanos(17, 30, -99));
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeEventUptimeIsRejected() {
        JvmTelemetry.uptimeToNanos(-1, 0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeAnchorUptimeIsRejected() {
        JvmTelemetry.uptimeToNanos(0, -1, 0);
    }

    @Test(expected = ArithmeticException.class)
    public void overflowingMillisecondConversionIsRejected() {
        JvmTelemetry.uptimeToNanos(Long.MAX_VALUE, 0, 0);
    }

    @Test(expected = ArithmeticException.class)
    public void overflowingPositiveAnchorAdditionIsRejected() {
        JvmTelemetry.uptimeToNanos(1, 0, Long.MAX_VALUE);
    }

    @Test(expected = ArithmeticException.class)
    public void overflowingNegativeAnchorAdditionIsRejected() {
        JvmTelemetry.uptimeToNanos(0, 1, Long.MIN_VALUE);
    }

    @Test
    public void concurrentCycleNamesAreFilteredCaseInsensitivelyAnywhereInName() {
        assertTrue(JvmTelemetry.isCycleCollector("ZGC Cycles"));
        assertTrue(JvmTelemetry.isCycleCollector("ZGC Minor Cycles"));
        assertTrue(JvmTelemetry.isCycleCollector("ZGC Major cYcLeS"));
        assertTrue(JvmTelemetry.isCycleCollector("cycles collector"));
        assertTrue(JvmTelemetry.isCycleCollector("prefixCYCLESsuffix"));
    }

    @Test
    public void pauseAndOtherCollectorsAreNotFiltered() {
        assertFalse(JvmTelemetry.isCycleCollector("ZGC Pauses"));
        assertFalse(JvmTelemetry.isCycleCollector("ZGC Major Pauses"));
        assertFalse(JvmTelemetry.isCycleCollector("G1 Young Generation"));
        assertFalse(JvmTelemetry.isCycleCollector("PS MarkSweep"));
        assertFalse(JvmTelemetry.isCycleCollector("Cycle"));
        assertFalse(JvmTelemetry.isCycleCollector(""));
        assertFalse(JvmTelemetry.isCycleCollector(null));
    }
}
