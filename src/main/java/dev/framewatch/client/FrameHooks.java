package dev.framewatch.client;

import dev.framewatch.FrameWatch;

/** Call sites injected into client code; inactive until FML initialization. */
public final class FrameHooks {
    private static ClientMonitor monitor;
    private static boolean failed;
    private FrameHooks() {}
    static void attach(ClientMonitor value) { monitor = value; }

    public static void frameStart() {
        if (monitor == null || failed) return;
        try { monitor.beginFrame(); } catch (RuntimeException | LinkageError ex) { disable(ex); }
    }
    public static void frameEnd() {
        if (monitor == null || failed) return;
        try { monitor.endFrame(); } catch (RuntimeException | LinkageError ex) { disable(ex); }
    }
    public static void drawHud() {
        if (monitor == null || failed) return;
        try { monitor.drawHud(); } catch (RuntimeException | LinkageError ex) { disable(ex); }
    }
    public static void renderStart() { if (monitor != null && !failed) monitor.renderStart(); }
    public static void renderEnd() { if (monitor != null && !failed) monitor.renderEnd(); }
    public static void presentStart() { if (monitor != null && !failed) monitor.presentStart(); }
    public static void presentEnd() { if (monitor != null && !failed) monitor.presentEnd(); }
    public static void tickStart() { if (monitor != null && !failed) monitor.tickStart(); }
    public static void tickEnd() { if (monitor != null && !failed) monitor.tickEnd(); }
    public static void pacingStart() { if (monitor != null && !failed) monitor.pacingStart(); }
    public static void pacingEnd() { if (monitor != null && !failed) monitor.pacingEnd(); }
    public static void idleWorkStart() { if (monitor != null && !failed) monitor.idleWorkStart(); }
        public static void idleWorkEnd() { if (monitor != null && !failed) monitor.idleWorkEnd(); }
        public static void shaderReload() { if (monitor != null && !failed) monitor.shaderReloaded(); }
        public static boolean isFailed() { return failed; }
    private static void disable(Throwable ex) {
        failed = true;
        FrameWatch.log.error("FrameWatch disabled after an instrumentation error; Minecraft continues", ex);
    }
}
