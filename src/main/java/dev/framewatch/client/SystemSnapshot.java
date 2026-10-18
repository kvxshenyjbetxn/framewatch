package dev.framewatch.client;

import dev.framewatch.telemetry.JvmTelemetry;

public final class SystemSnapshot {
    public final long heapUsedBytes, heapCommittedBytes, heapMaxBytes, directUsedBytes;
    public final long gcCount, gcCollectionMillis;
    public final double allocationMiBPerSecond;
    public final String collectors, javaVersion, gpu, lwjgl, angelica, shader, vsync;
    public final boolean gcNotificationsAvailable;
    public final int fpsCap, chunkThreads, renderAhead;

    public SystemSnapshot(JvmTelemetry.Sample jvm, AngelicaBridge.Status bridge,
            String gpu, String lwjgl, int fpsCap, boolean vanillaVsync) {
        heapUsedBytes = jvm.heapUsedBytes; heapCommittedBytes = jvm.heapCommittedBytes;
        heapMaxBytes = jvm.heapMaxBytes; directUsedBytes = jvm.directUsedBytes;
        gcCount = jvm.gcCount; gcCollectionMillis = jvm.gcCollectionMillis;
        allocationMiBPerSecond = jvm.allocationMiBPerSecond;
        collectors = jvm.collectors; javaVersion = jvm.javaVersion;
        gcNotificationsAvailable = jvm.gcNotificationsAvailable;
        this.gpu = gpu; this.lwjgl = lwjgl; this.fpsCap = fpsCap;
        angelica = bridge.version; shader = bridge.shader;
        vsync = "unknown".equals(bridge.vsync) ? (vanillaVsync ? "ON" : "OFF") : bridge.vsync;
        chunkThreads = bridge.chunkThreads; renderAhead = bridge.renderAhead;
    }
}
