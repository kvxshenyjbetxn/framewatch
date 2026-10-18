package dev.framewatch.core;

/** Set only after a complete, validated transformation. */
public final class HookStatus {
    public static volatile boolean minecraft;
    public static volatile boolean angelicaPacing;
        public static volatile boolean angelicaIdleWork;
        public static volatile boolean irisReload;
    private HookStatus() {}
}
