package dev.framewatch.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import dev.framewatch.FrameConfig;
import dev.framewatch.FrameWatch;
import dev.framewatch.core.HookStatus;
import dev.framewatch.telemetry.CsvExporter;
import dev.framewatch.telemetry.FrameHistory;
import dev.framewatch.telemetry.FrameSnapshot;
import dev.framewatch.telemetry.JvmTelemetry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.util.ChatComponentText;

public final class ClientMonitor {
    private static final long SECOND = 1_000_000_000L;
    private final Minecraft mc = Minecraft.getMinecraft();
    private final FrameConfig config = FrameWatch.config;
    private final FrameHistory history = new FrameHistory(config.historyCapacity);
    private final JvmTelemetry jvm = new JvmTelemetry(history);
    private final AngelicaBridge bridge = new AngelicaBridge();
    private long[] graphIntervals = new long[config.graphWidth];
    private int[] graphEvents = new int[config.graphWidth];
    private double[] graphTotals = new double[config.graphWidth], graphMemory = new double[config.graphWidth];
    private int[] graphFrameCounts = new int[config.graphWidth];
    private final dev.framewatch.telemetry.MemoryHistory memoryHistory = new dev.framewatch.telemetry.MemoryHistory();
    private int graphCount;
    private long nextGraph, seenConfigRevision = -1;
        private int seenGraphWindow, seenGraphRefresh, seenTextRefresh, seenStatsWindow, seenTargetFps;
        private boolean seenShowGraph;
        private int pendingGui;
    private final AtomicBoolean exporting = new AtomicBoolean();
    private final ExecutorService exports = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "FrameWatch-CSV");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final String gpu = GL11.glGetString(GL11.GL_RENDERER);
    private final String lwjgl = Sys.getVersion();
    private FrameSnapshot stats = history.snapshot(0, 0);
    private SystemSnapshot system;
    private Object lastWorld;
    private String lastShader;
    private boolean active, eligibleLastFrame, paused;
    private long warmupUntil, previousPresent, frameStart, presentFinished;
    private long renderStart, tickStart, presentStart, pacingStart;
    private long renderNanos, tickNanos, presentNanos, pacingNanos, idleNanos, idleStart;
    private int ticks, pacingDepth, idleDepth, pendingEvents;
    private int lastShaderGeneration = Integer.MIN_VALUE;
    private long nextStats, nextSystem;
    private double budgetMs = 1000.0 / 60.0;

    public ClientMonitor() {
        sampleSystem(System.nanoTime());
        lastShaderGeneration = bridge.shaderGeneration();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { jvm.close(); exports.shutdown(); }
        }, "FrameWatch-Cleanup"));
    }

    public void beginFrame() {
        long now = System.nanoTime();
        frameStart = now;
        renderStart = tickStart = presentStart = pacingStart = presentFinished = 0;
        renderNanos = tickNanos = presentNanos = pacingNanos = idleNanos = idleStart = 0;
        ticks = pacingDepth = idleDepth = 0;
        if (lastWorld != mc.theWorld) {
            lastWorld = mc.theWorld;
            history.clear();
            memoryHistory.clear();
            nextSystem = nextGraph = 0;
            stats = history.snapshot(now, 0);
            invalidateBoundary(now);
        }
        boolean eligible = isEligible();
        if (eligible && !eligibleLastFrame) invalidateBoundary(now);
        eligibleLastFrame = eligible;
        active = eligible && now >= warmupUntil;
        if (!active) previousPresent = 0;
    }

    public void endFrame() {
        long now = System.nanoTime();
        int generation = bridge.shaderGeneration();
        if (generation != Integer.MIN_VALUE && generation != lastShaderGeneration) {
            lastShaderGeneration = generation;
            shaderReloaded();
        }
        // Focus/pause/world may change while Display.update pumps messages.
        if (active && (!isEligible() || lastWorld != mc.theWorld)) {
            active = false;
            eligibleLastFrame = false;
        }
        if (active && presentFinished != 0) {
            if (previousPresent != 0) {
                long interval = presentFinished - previousPresent;
                if (interval > 0) {
                    int flags = pendingEvents;
                    if (interval / 1_000_000.0 > Math.max(config.longFrameMillis, budgetMs * 2.0)) {
                        flags |= FrameSnapshot.EVENT_LONG_FRAME;
                    }
                    history.record(presentFinished, interval, Math.max(0, now - frameStart - pacingNanos + idleNanos),
                            renderNanos, presentNanos, tickNanos, ticks, 0, flags);
                    pendingEvents = 0;
                }
            }
            previousPresent = presentFinished;
        } else previousPresent = 0;
        active = false;
    }

    private boolean isEligible() {
        return !paused && mc.theWorld != null && !mc.isGamePaused()
                && !mc.skipRenderWorld && Display.isVisible() && Display.isActive();
    }

    public void renderStart() { if (active) renderStart = System.nanoTime(); }
    public void renderEnd() {
        if (active && renderStart != 0) { renderNanos += System.nanoTime() - renderStart; renderStart = 0; }
    }
    public void tickStart() { if (active) tickStart = System.nanoTime(); }
    public void tickEnd() {
        if (active && tickStart != 0) { tickNanos += System.nanoTime() - tickStart; ticks++; tickStart = 0; }
    }
    public void presentStart() { if (active) presentStart = System.nanoTime(); }
    public void presentEnd() {
        if (active && presentStart != 0) {
            presentFinished = System.nanoTime();
            presentNanos += presentFinished - presentStart;
            presentStart = 0;
        }
    }
    public void pacingStart() {
        if (active && pacingDepth++ == 0) pacingStart = System.nanoTime();
    }
    public void pacingEnd() {
        if (active && pacingDepth > 0 && --pacingDepth == 0) {
            pacingNanos += System.nanoTime() - pacingStart;
            pacingStart = 0;
        }
    }

    public void idleWorkStart() {
        if (active && pacingDepth > 0 && idleDepth++ == 0) idleStart = System.nanoTime();
    }
    public void idleWorkEnd() {
        if (active && idleDepth > 0 && --idleDepth == 0) {
            idleNanos += System.nanoTime() - idleStart;
            idleStart = 0;
        }
    }

    public void drawHud() {
        long now = System.nanoTime();
        if (seenConfigRevision != config.revision()) {
            seenConfigRevision = config.revision();
            if (graphIntervals.length != config.graphWidth) {
                graphIntervals = new long[config.graphWidth];
                graphEvents = new int[config.graphWidth];
                graphTotals = new double[config.graphWidth];
                graphMemory = new double[config.graphWidth];
                graphFrameCounts = new int[config.graphWidth];
                graphCount = 0;
                nextGraph = 0;
            }
            if (seenGraphWindow != config.graphWindowSeconds || seenGraphRefresh != config.graphRefreshMillis
                    || (config.hasGraphs() && !seenShowGraph)) nextGraph = 0;
            if (seenTextRefresh != config.textRefreshMillis || seenStatsWindow != config.windowSeconds) nextStats = 0;
            if (seenTargetFps != config.targetFps) nextSystem = 0;
            seenGraphWindow = config.graphWindowSeconds;
            seenGraphRefresh = config.graphRefreshMillis;
            seenTextRefresh = config.textRefreshMillis;
            seenStatsWindow = config.windowSeconds;
            seenTargetFps = config.targetFps;
            seenShowGraph = config.hasGraphs();
        }
        if (now >= nextSystem) sampleSystem(now);
        if (!config.hudEnabled || mc.theWorld == null || !Display.isVisible()
                || !HudRenderer.shouldRenderOnScreen(mc.currentScreen)
                || (config.hideWithF1 && mc.gameSettings.hideGUI)) return;
        if (now >= nextStats) {
            stats = history.snapshot(now, config.windowSeconds * SECOND);
            nextStats = now + config.textRefreshMillis * 1_000_000L;
        }
        if (config.hasGraphs() && now >= nextGraph) {
            graphCount = history.copyTimeBuckets(now, config.graphWindowSeconds * SECOND, graphIntervals, graphEvents,
                    graphTotals, graphFrameCounts);
            memoryHistory.copyTimeBuckets(now, config.graphWindowSeconds * SECOND, graphMemory);
            nextGraph = now + config.graphRefreshMillis * 1_000_000L;
            HudRenderer.invalidateGraph();
        }
        HudRenderer.render(mc, stats, system, config, budgetMs, graphIntervals, graphEvents, graphCount,
                graphTotals, graphFrameCounts, graphMemory);
    }

    private void sampleSystem(long now) {
        AngelicaBridge.Status status = bridge.read();
        system = new SystemSnapshot(jvm.sample(now), status, gpu, lwjgl,
                mc.gameSettings.limitFramerate, mc.gameSettings.enableVsync);
        if (lastShader != null && !lastShader.equals(status.shader)) {
            pendingEvents |= FrameSnapshot.EVENT_SHADER_CHANGE;
            invalidateBoundary(now);
        }
        lastShader = status.shader;
        int target = config.targetFps;
        if (target == 0) {
            int refresh = Display.getDisplayMode().getFrequency();
            int cap = mc.gameSettings.limitFramerate;
            boolean capped = cap > 0 && cap < 260;
            target = refresh > 0 ? (capped ? Math.min(refresh, cap) : refresh) : (capped ? cap : 60);
        }
        memoryHistory.record(now, system.heapUsedBytes);
        budgetMs = 1000.0 / Math.max(1, target);
        nextSystem = now + SECOND;
    }

    public void shaderReloaded() {
            pendingEvents |= FrameSnapshot.EVENT_SHADER_CHANGE;
            invalidateBoundary(System.nanoTime());
            nextSystem = 0;
        }

        public void resourcesReloaded() {
        pendingEvents |= FrameSnapshot.EVENT_RESOURCE_RELOAD;
        invalidateBoundary(System.nanoTime());
    }

    private void invalidateBoundary(long now) {
        previousPresent = 0;
        active = false;
        warmupUntil = now + config.warmupMillis * 1_000_000L;
    }

    public void reset() {
        history.clear();
        memoryHistory.clear();
        nextSystem = nextGraph = 0;
        pendingEvents = 0;
        invalidateBoundary(System.nanoTime());
        stats = history.snapshot(0, 0);
    }
    public void setPaused(boolean paused) { this.paused = paused; invalidateBoundary(System.nanoTime()); }
    public boolean isPaused() { return paused; }
    public void setBudget(int fps) {
            config.targetFps = fps;
            config.changed();
            SettingsPersistence.save();
            nextSystem = 0;
        }
        // GuiChat closes itself after a client command. Defer until client-tick END;
        // Minecraft's scheduled-task helper executes immediately on this thread.
        public void openSettings() { pendingGui = 1; }
        public void openPositionEditor() { pendingGui = 2; }
        public void processGuiRequests() {
            int request = pendingGui;
            pendingGui = 0;
            if (request == 0) return;
            GuiScreen parent = mc.currentScreen instanceof GuiChat ? null : mc.currentScreen;
            mc.displayGuiScreen(request == 1 ? new HudSettingsScreen(parent) : new HudPositionScreen(parent));
        }

    public String status() {
        stats = history.snapshot(System.nanoTime(), config.windowSeconds * SECOND);
                return "FrameWatch: hooks=" + HookStatus.minecraft + ", Angelica pacing=" + HookStatus.angelicaPacing
                        + ", idle-work=" + HookStatus.angelicaIdleWork + ", shader-reload=" + HookStatus.irisReload
                + ", failed=" + FrameHooks.isFailed() + ", collection=" + (paused ? "paused" : "running")
                + ", rolling samples=" + stats.sampleCount;
    }

    public void requestExport() {
        if (!exporting.compareAndSet(false, true)) { message(HudTranslations.text("framewatch.message.exportBusy")); return; }
        // Copy once on the client thread; disk formatting/writes never run there.
        final FrameSnapshot capture = history.snapshot(System.nanoTime(), 0);
        final Map<String, String> metadata = metadata();
        final Path directory = mc.mcDataDir.toPath().resolve("framewatch").resolve("exports");
        if (capture.sampleCount == 0) {
            exporting.set(false); message(HudTranslations.text("framewatch.message.noFrames")); return;
        }
        exports.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Path file = CsvExporter.export(directory, capture, metadata);
                    mc.func_152344_a(new Runnable() {
                        @Override public void run() { message(HudTranslations.text("framewatch.message.exported", capture.sampleCount,
                                                        "framewatch/exports/" + file.getFileName())); }
                    });
                } catch (IOException | RuntimeException ex) {
                    FrameWatch.log.error("FrameWatch CSV export failed", ex);
                    mc.func_152344_a(new Runnable() {
                        @Override public void run() { message(HudTranslations.text("framewatch.message.exportFailed")); }
                    });
                } finally { exporting.set(false); }
            }
        });
    }

    private Map<String, String> metadata() {
        Map<String, String> data = new LinkedHashMap<String, String>();
        data.put("framewatch_version", FrameWatch.VERSION);
        data.put("minecraft", "1.7.10");
        data.put("frame_interval", "between CPU completions of Display.update; NOT measured screen presentation");
        data.put("work", "runGameLoop wall time minus hooked pacer scopes plus nested chunk idle work; includes present CPU/wait; phases need not sum to interval");
                data.put("angelica_idle_work_hooks", String.valueOf(HookStatus.angelicaIdleWork));
                data.put("iris_reload_hooks", String.valueOf(HookStatus.irisReload));
        data.put("gc", "asynchronous millisecond GC activity overlap, not proof of stall causation; concurrent Cycles omitted");
        data.put("excluded", "no-world, paused, unfocused, invisible, warmup and detected resource/shader transitions");
        data.put("warmup_ms", String.valueOf(config.warmupMillis));
                data.put("gc_notifications_available", String.valueOf(system != null && system.gcNotificationsAvailable));
                data.put("graph_window_seconds", String.valueOf(config.graphWindowSeconds));
                data.put("statistics_window_seconds", String.valueOf(config.windowSeconds));
        data.put("minecraft_hooks", String.valueOf(HookStatus.minecraft));
        data.put("angelica_pacing_hooks", String.valueOf(HookStatus.angelicaPacing));
        if (system != null) {
            data.put("java", system.javaVersion); data.put("gc_collectors", system.collectors);
            data.put("gpu_device", system.gpu); data.put("lwjgl", system.lwjgl);
            data.put("angelica", system.angelica); data.put("shader", system.shader);
            data.put("fps_cap", String.valueOf(system.fpsCap)); data.put("vsync", system.vsync);
            data.put("chunk_threads_configured", String.valueOf(system.chunkThreads));
            data.put("cpu_render_ahead", String.valueOf(system.renderAhead));
        }
        return data;
    }

    public void message(String text) {
        FrameWatch.log.info(text);
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("\u00a76[FrameWatch]\u00a7r " + text));
    }
}
