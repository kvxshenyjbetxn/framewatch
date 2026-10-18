package dev.framewatch.telemetry;

import java.util.Arrays;

/** Render-thread JVM heap samples, at 1 Hz; independent of frame/statistics history. */
public final class MemoryHistory {
    private static final long MAX_AGE = 1_500_000_000L;
    private final long[] times = new long[64], bytes = new long[64];
    private int next, size;

    public void record(long now, long usedBytes) {
        times[next] = now;
        bytes[next] = usedBytes;
        next = (next + 1) % times.length;
        size = Math.min(size + 1, times.length);
    }

    public void clear() { next = size = 0; }

    /** Latest known sample at each bucket's right edge, held for at most 1.5 s.
     * Negative output denotes unavailable data; zero heap usage is valid. */
    public void copyTimeBuckets(long now, long window, double[] output) {
        if (window <= 0) throw new IllegalArgumentException("window must be positive");
        Arrays.fill(output, -1);
        int first = (next - size + times.length) % times.length;
        int sample = 0, latest = -1;
        for (int x = 0; x < output.length; x++) {
            long age = window * (output.length - x - 1) / output.length;
            if (now < Long.MIN_VALUE + age) continue;
            long end = now - age;
            while (sample < size && times[(first + sample) % times.length] <= end) {
                latest = (first + sample++) % times.length;
            }
            if (latest >= 0 && end - times[latest] >= 0 && end - times[latest] <= MAX_AGE
                    && bytes[latest] >= 0) output[x] = bytes[latest] / 1048576.0;
        }
    }
}
