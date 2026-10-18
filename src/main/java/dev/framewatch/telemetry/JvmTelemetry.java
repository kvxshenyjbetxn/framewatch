package dev.framewatch.telemetry;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Low-frequency JVM sampling; call sample from the render thread only.
 * GC notifications describe collector activity, not necessarily stop-the-world
 * pauses. Their millisecond timestamps are coarse, and asynchronous delivery
 * can only annotate frames still retained by the history (or future records).
 * No allocation tracking is enabled and no heap-delta estimate is used.
 */
public final class JvmTelemetry implements AutoCloseable {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final MemoryMXBean memory;
    private final BufferPoolMXBean directPool;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final ThreadAllocationReader allocationReader;
    private final List<NotificationEmitter> registeredEmitters = new ArrayList<NotificationEmitter>();
    private final NotificationListener gcListener;
    private final String collectors;
    private final String javaVersion;
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final boolean gcNotificationsAvailable;
    private boolean closed;

    // Render-thread-owned state. Switching threads or losing the counter resets the baseline.
    private long previousThreadId = -1;
    private long previousAllocatedBytes = -1;
    private long previousSampleNanos;

    public JvmTelemetry(FrameHistory history) {
        Objects.requireNonNull(history, "history");
        MemoryMXBean foundMemory = null;
        BufferPoolMXBean foundDirectPool = null;
        List<GarbageCollectorMXBean> foundGcBeans = Collections.emptyList();
        ThreadAllocationReader foundAllocationReader = null;
        String foundJavaVersion = "unknown";
        try {
            foundMemory = ManagementFactory.getMemoryMXBean();
        } catch (RuntimeException ignored) {
            // Management access may be denied on an otherwise usable JVM.
        }
        try {
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if ("direct".equals(pool.getName())) {
                    foundDirectPool = pool;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // A missing direct-buffer counter is reported as -1.
        }
        try {
            foundGcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        } catch (RuntimeException ignored) {
            // GC polling and notifications are optional.
        }
        try {
            foundAllocationReader = new ThreadAllocationReader(ManagementFactory.getThreadMXBean());
        } catch (RuntimeException | LinkageError ignored) {
            // Keep the optional com.sun.management dependency out of the required path.
        }
        try {
            foundJavaVersion = System.getProperty("java.version", "unknown");
        } catch (SecurityException ignored) {
            // Do not require permission to read system properties.
        }
        memory = foundMemory;
        directPool = foundDirectPool;
        gcBeans = foundGcBeans;
        allocationReader = foundAllocationReader;
        javaVersion = foundJavaVersion;

        StringBuilder names = new StringBuilder();
        for (GarbageCollectorMXBean bean : gcBeans) {
            try {
                String name = bean.getName();
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(name);
            } catch (RuntimeException ignored) {
                // A failed name query must not disable other collectors.
            }
        }
        collectors = names.length() == 0 ? "unknown" : names.toString();

        NotificationListener listener = null;
        try {
            long beforeNanos = System.nanoTime();
            long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            long afterNanos = System.nanoTime();
            long anchorNanos = beforeNanos + (afterNanos - beforeNanos) / 2;
            if (uptimeMillis >= 0) {
                listener = new GcListener(history, uptimeMillis, anchorNanos);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Polling still works without the vendor notification API or uptime anchor.
        }
        gcListener = listener;
        if (gcListener != null) {
            for (GarbageCollectorMXBean bean : gcBeans) {
                if (bean instanceof NotificationEmitter) {
                    NotificationEmitter emitter = (NotificationEmitter) bean;
                    try {
                        emitter.addNotificationListener(gcListener, null, null);
                        registeredEmitters.add(emitter);
                    } catch (RuntimeException | LinkageError ignored) {
                        // One unsupported emitter must not prevent registering the others.
                    }
                }
            }
        }
        gcNotificationsAvailable = !registeredEmitters.isEmpty();
    }

    /**
     * Samples cumulative GC counters and current memory usage at roughly 1 Hz.
     * Negative values mean unavailable. Allocation is for the calling thread,
     * not the whole JVM; the first sample (or a reset/unavailable counter) is -1.
     * GC sums include supported values from ALL beans, including concurrent
     * cycle beans, and are not exact pause counts or stop-the-world durations.
     */
    public Sample sample(long nowNanos) {
        long threadId = Thread.currentThread().getId();
        long allocatedBytes = -1;
        if (allocationReader != null) {
            try {
                allocatedBytes = allocationReader.read(threadId);
            } catch (RuntimeException | LinkageError ignored) {
                // Unsupported, disabled or inaccessible counters have no approximation.
            }
        }
        double allocationRate = previousThreadId == threadId
                ? allocationRate(previousAllocatedBytes, allocatedBytes, nowNanos - previousSampleNanos) : -1;
        previousThreadId = threadId;
        previousAllocatedBytes = allocatedBytes;
        previousSampleNanos = nowNanos;

        long heapUsed = -1;
        long heapCommitted = -1;
        long heapMax = -1;
        if (memory != null) {
            try {
                MemoryUsage heap = memory.getHeapMemoryUsage();
                heapUsed = heap.getUsed();
                heapCommitted = heap.getCommitted();
                heapMax = heap.getMax();
            } catch (RuntimeException ignored) {
                // Leave unavailable values at -1.
            }
        }
        long directUsed = -1;
        if (directPool != null) {
            try {
                directUsed = directPool.getMemoryUsed();
            } catch (RuntimeException ignored) {
                // Direct-buffer accounting is optional.
            }
        }
        long gcCount = -1;
        long gcMillis = -1;
        for (GarbageCollectorMXBean bean : gcBeans) {
            try {
                gcCount = sumSupported(gcCount, bean.getCollectionCount());
            } catch (RuntimeException ignored) {
                // Count and time support are independent.
            }
            try {
                gcMillis = sumSupported(gcMillis, bean.getCollectionTime());
            } catch (RuntimeException ignored) {
                // Retain other collectors' supported values.
            }
        }
        return new Sample(heapUsed, heapCommitted, heapMax, directUsed,
                gcCount, gcMillis, allocationRate, collectors, javaVersion, gcNotificationsAvailable);
    }

    /** Removes only this instance's exact registrations; repeated closes are harmless. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        // Do not hold the callback lock while invoking an emitter's removal code.
        for (NotificationEmitter emitter : registeredEmitters) {
            try {
                emitter.removeNotificationListener(gcListener, null, null);
            } catch (ListenerNotFoundException ignored) {
                // The emitter may already have removed the registration.
            } catch (RuntimeException | LinkageError failure) {
                logFailureOnce(failure);
            }
        }
    }

    private void logFailureOnce(Throwable failure) {
        if (failureLogged.compareAndSet(false, true)) {
            try {
                Logger.getLogger(JvmTelemetry.class.getName()).log(Level.WARNING,
                        "JVM GC telemetry failed; subsequent failures will not be logged", failure);
            } catch (RuntimeException ignored) {
                // Even a logging/security failure must not escape a JVM callback.
            }
        }
    }

    private static long sumSupported(long sum, long value) {
        if (value < 0) {
            return sum;
        }
        if (sum < 0) {
            return value;
        }
        return sum > Long.MAX_VALUE - value ? Long.MAX_VALUE : sum + value;
    }

    static double allocationRate(long previousBytes, long currentBytes, long elapsedNanos) {
        if (previousBytes < 0 || currentBytes < previousBytes || elapsedNanos <= 0) {
            return -1;
        }
        return (currentBytes - previousBytes) * (1_000_000_000.0 / (1024.0 * 1024.0)) / elapsedNanos;
    }

    static long uptimeToNanos(long uptimeMillis, long anchorUptimeMillis, long anchorNanos) {
        if (uptimeMillis < 0 || anchorUptimeMillis < 0) {
            throw new IllegalArgumentException("JVM uptime must be nonnegative");
        }
        return Math.addExact(anchorNanos,
                Math.multiplyExact(uptimeMillis - anchorUptimeMillis, NANOS_PER_MILLI));
    }

    static boolean isCycleCollector(String name) {
        if (name != null) {
            for (int i = 0; i <= name.length() - 6; i++) {
                if (name.regionMatches(true, i, "Cycles", 0, 6)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Loaded separately so JVMs without the optional management extension can fall back. */
    private static final class ThreadAllocationReader {
        private final com.sun.management.ThreadMXBean bean;

        ThreadAllocationReader(java.lang.management.ThreadMXBean bean) {
            this.bean = bean instanceof com.sun.management.ThreadMXBean
                    ? (com.sun.management.ThreadMXBean) bean : null;
        }

        long read(long threadId) {
            return bean != null && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()
                    ? bean.getThreadAllocatedBytes(threadId) : -1;
        }
    }

    private final class GcListener implements NotificationListener {
        private final FrameHistory history;
        private final long anchorUptimeMillis;
        private final long anchorNanos;

        GcListener(FrameHistory history, long anchorUptimeMillis, long anchorNanos) {
            this.history = history;
            this.anchorUptimeMillis = anchorUptimeMillis;
            this.anchorNanos = anchorNanos;
            // from(null) is specified to return null; resolve the optional parser before registration.
            com.sun.management.GarbageCollectionNotificationInfo.from(null);
        }

        @Override
        public void handleNotification(Notification notification, Object handback) {
            try {
                synchronized (JvmTelemetry.this) {
                    if (closed || !com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                            .equals(notification.getType())) {
                        return;
                    }
                    Object data = notification.getUserData();
                    if (!(data instanceof CompositeData)) {
                        return;
                    }
                    com.sun.management.GarbageCollectionNotificationInfo info =
                            com.sun.management.GarbageCollectionNotificationInfo.from((CompositeData) data);
                    // ZGC cycles include concurrent work and their pauses have separate notifications.
                    if (info == null || isCycleCollector(info.getGcName())) {
                        return;
                    }
                    com.sun.management.GcInfo gc = info.getGcInfo();
                    long startNanos = uptimeToNanos(gc.getStartTime(), anchorUptimeMillis, anchorNanos);
                    long endNanos = uptimeToNanos(gc.getEndTime(), anchorUptimeMillis, anchorNanos);
                    history.addGcActivity(startNanos, endNanos);
                }
            } catch (RuntimeException | LinkageError failure) {
                logFailureOnce(failure);
            }
        }
    }

    /** Immutable snapshot; byte/counter/rate fields use -1 for unavailable data. */
    public static final class Sample {
        public final long heapUsedBytes;
        public final long heapCommittedBytes;
        public final long heapMaxBytes;
        public final long directUsedBytes;
        public final long gcCount;
        public final long gcCollectionMillis;
        public final double allocationMiBPerSecond;
        public final String collectors;
        public final String javaVersion;
        /** True when at least one listener registration succeeded during construction. */
        public final boolean gcNotificationsAvailable;

        private Sample(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes, long directUsedBytes,
                long gcCount, long gcCollectionMillis, double allocationMiBPerSecond,
                String collectors, String javaVersion, boolean gcNotificationsAvailable) {
            this.heapUsedBytes = heapUsedBytes;
            this.heapCommittedBytes = heapCommittedBytes;
            this.heapMaxBytes = heapMaxBytes;
            this.directUsedBytes = directUsedBytes;
            this.gcCount = gcCount;
            this.gcCollectionMillis = gcCollectionMillis;
            this.allocationMiBPerSecond = allocationMiBPerSecond;
            this.collectors = collectors;
            this.javaVersion = javaVersion;
            this.gcNotificationsAvailable = gcNotificationsAvailable;
        }
    }
}
