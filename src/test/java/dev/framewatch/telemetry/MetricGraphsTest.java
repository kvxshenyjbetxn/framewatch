package dev.framewatch.telemetry;

import org.junit.Test;
import static org.junit.Assert.*;

public class MetricGraphsTest {
    @Test public void fpsUsesCountOverSumAndClearsReusedBuffers() {
        FrameHistory history = new FrameHistory(3);
        history.record(100, 10, 0, 0, 0, 0, 0, 0, 1);
        history.record(110, 30, 0, 0, 0, 0, 0, 0, 2);
        long[] peaks = new long[2];
        int[] flags = new int[2], counts = new int[2];
        double[] sums = new double[2];
        assertEquals(2, history.copyTimeBuckets(110, 100, peaks, flags, sums, counts));
        assertArrayEquals(new long[] {0, 30}, peaks);
        assertArrayEquals(new int[] {0, 2}, counts);
        assertArrayEquals(new double[] {0, 40}, sums, 0);
        assertEquals(50_000_000, counts[1] * 1e9 / sums[1], 0);
        assertEquals(3, flags[1]);
        history.clear();
        history.copyTimeBuckets(110, 100, peaks, flags, sums, counts);
        assertArrayEquals(new double[] {0, 0}, sums, 0);
        assertArrayEquals(new int[] {0, 0}, counts);
    }

    @Test public void heapSamplesAreHeldBrieflyNeverBackfilledOrInvented() {
        MemoryHistory history = new MemoryHistory();
        double[] buckets = new double[4];
        history.record(0, 2 * 1048576L);
        history.record(1_000_000_000L, 0);
        history.copyTimeBuckets(2_000_000_000L, 4_000_000_000L, buckets);
        assertArrayEquals(new double[] {-1, 2, 0, 0}, buckets, 0);
        history.copyTimeBuckets(4_000_000_000L, 4_000_000_000L, buckets);
        assertArrayEquals(new double[] {0, 0, -1, -1}, buckets, 0);
        history.clear();
        history.copyTimeBuckets(4_000_000_000L, 4_000_000_000L, buckets);
        assertArrayEquals(new double[] {-1, -1, -1, -1}, buckets, 0);
    }

    @Test public void heapRingKeepsNewestSamplesAndUnknownIsGap() {
        MemoryHistory history = new MemoryHistory();
        for (int i = 0; i < 100; i++) history.record(i * 1_000_000_000L, i * 1048576L);
        history.record(100_000_000_000L, -1);
        double[] buckets = new double[4];
        history.copyTimeBuckets(100_000_000_000L, 4_000_000_000L, buckets);
        assertArrayEquals(new double[] {97, 98, 99, -1}, buckets, 0);
    }
}
