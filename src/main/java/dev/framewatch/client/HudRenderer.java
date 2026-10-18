package dev.framewatch.client;

import java.util.Locale;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import dev.framewatch.FrameConfig;
import dev.framewatch.telemetry.FrameSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

/**
 * Pre-Display.update, compatibility/Angelica FFP HUD, outside the HUD cache.
 * No GPU timings, sensor queries, synchronization, or Minecraft debug-FPS counter are used here.
 */
public final class HudRenderer {
    private static final int WHITE = 0xffffff;
    private static final int GREEN = 0x55ff55;
    private static final int YELLOW = 0xffd344;
    private static final int RED = 0xff5555;
    private static final String[] scopeNames = new String[4];
    private static String cachedLanguage, profilerTitle, profilerScopes, eventLegend;
    private static String msUnit, secondsUnit, memoryUnit, allocationUnit, unavailable;
    private static int scopeValueOffset;

    private static FrameConfig cachedConfig;
    private static long cachedRevision;
    private static FrameSnapshot cachedStats;
    private static SystemSnapshot cachedSystem;
    private static FontRenderer cachedFont;
    private static HudLayout layout;
    private static ScaledResolution cachedResolution, layoutResolution;
    private static int cachedDisplayWidth, cachedDisplayHeight, cachedGuiScale;
    private static boolean cachedUnicodeFont;
    private static String[] labels, values;
    private static int[] valueOffsets, textColors;
    private static final String[] scopeValues = new String[4];
    private static final double[] scopeFractions = new double[4];
    private static final double[] scopeTotals = new double[4];
    private static final int[] scopeColors = new int[4];
    private static int[] graphHeights, graphColors, graphFlags;
    private static boolean[] graphClipped;
    private static int graphShown;
    private static double axisMs, cachedBudget = Double.NaN;
    private static String axisLabel, budgetLabel, durationLabel;
    private static int durationWidth;
    private static long nextGraph;
    private static boolean textReady;

    private HudRenderer() {}

    static boolean shouldRenderOnScreen(GuiScreen screen) {
        // Like InfoHUD, hide in every GUI except our own live panel-position editor.
        return screen == null || screen instanceof HudPositionScreen;
    }

    /** Render-thread only: call when the parent refreshes the time-bucket buffers. */
    public static void invalidateGraph() {
        nextGraph = 0;
    }

    /**
     * Render-thread only, with the default framebuffer already bound. Buffers hold
     * chronological, equally spaced time buckets (nanosecond peaks), not frames.
     * One bucket occupies one logical graph pixel. Zero means no sample and is a
     * gap, including in the line. Only [0, bucketCount) is read; buffers are neither
     * retained nor modified. The parent owns collection and refresh scheduling.
     *
     * FPS uses complete positive intervals ending in the snapshot's latest ~1 s;
     * AVG is snapshot.fps over the statistics window. MAX is 1000 / the smallest
     * positive interval in that window, not a sampled debug-FPS maximum. FRAME is
     * the last interval, not work time. CPU scope bars are window-average wall
     * scopes, normalized by tick + render + present + other, never GPU/frame time.
     */
    public static void render(Minecraft mc, FrameSnapshot stats, SystemSnapshot system,
            FrameConfig config, double budgetMs, long[] bucketPeaks, int[] bucketEvents, int bucketCount) {
        if (mc == null || config == null || !config.hudEnabled || !shouldRenderOnScreen(mc.currentScreen)
                || mc.fontRenderer == null
                || mc.displayWidth <= 0 || mc.displayHeight <= 0
                || (config.hideWithF1 && mc.gameSettings.hideGUI)) return;

        ScaledResolution resolution = resolution(mc);
        long revision = config.revision();
        boolean configChanged = cachedConfig != config || cachedRevision != revision;
        boolean layoutChanged = layout == null || configChanged || layoutResolution != resolution;
        if (layoutChanged) {
            layout = HudLayout.calculate(config, resolution.getScaledWidth(), resolution.getScaledHeight());
            layoutResolution = resolution;
            cachedConfig = config;
            cachedRevision = revision;
            labels = new String[layout.summaryRows];
            values = new String[layout.summaryRows];
            valueOffsets = new int[layout.summaryRows];
            textColors = new int[layout.summaryRows];
            int width = layout.contentWidth - HudLayout.PADDING * 2;
            if (graphHeights == null || graphHeights.length != width) {
                graphHeights = new int[width];
                graphColors = new int[width];
                graphFlags = new int[width];
                graphClipped = new boolean[width];
            }
            nextGraph = 0;
        }
        if (!layout.left.visible() && !layout.right.visible()) return;

        long now = System.nanoTime();
        boolean budgetChanged = Double.compare(cachedBudget, budgetMs) != 0;
        // Auto follows Minecraft's language even when the mod config revision is unchanged.
        String language = HudTranslations.currentLanguage();
        boolean forceText = !textReady || layoutChanged || cachedFont != mc.fontRenderer || budgetChanged
                || !language.equals(cachedLanguage);
        if (forceText || cachedStats != stats || cachedSystem != system) {
            cacheText(mc.fontRenderer, stats, system, config, budgetMs, bucketPeaks, bucketCount, language);
            nextGraph = 0;
        }
        if (config.showGraph && now >= nextGraph) {
            cacheGraph(bucketPeaks, bucketEvents, bucketCount);
            nextGraph = now + Math.max(0, config.graphRefreshMillis) * 1_000_000L;
        }

        int previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        boolean projectionPushed = false;
        boolean modelviewPushed = false;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            projectionPushed = true;
            GL11.glLoadIdentity();
            GL11.glOrtho(0, resolution.getScaledWidth_double(), resolution.getScaledHeight_double(),
                    0, -1000, 1000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            modelviewPushed = true;
            GL11.glLoadIdentity();
            GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            drawPanel(mc, resolution, config, false);
            drawPanel(mc, resolution, config, true);
        } finally {
            try {
                if (modelviewPushed) {
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);
                    GL11.glPopMatrix();
                }
            } finally {
                try {
                    if (projectionPushed) {
                        GL11.glMatrixMode(GL11.GL_PROJECTION);
                        GL11.glPopMatrix();
                    }
                } finally {
                    try {
                        GL11.glMatrixMode(previousMatrixMode);
                    } finally {
                        // Includes viewport, scissor, texture binding, blending, and current color.
                        GL11.glPopAttrib();
                    }
                }
            }
        }
    }

    private static void cacheText(FontRenderer font, FrameSnapshot stats, SystemSnapshot system,
            FrameConfig cfg, double budgetMs, long[] peaks, int bucketCount, String language) {
        cachedLanguage = language;
        msUnit = HudTranslations.text("framewatch.unit.ms");
        secondsUnit = HudTranslations.text("framewatch.unit.s");
        memoryUnit = HudTranslations.text("framewatch.unit.MiB");
        allocationUnit = HudTranslations.text("framewatch.unit.MiBps");
        unavailable = hud("unavailable");
        profilerTitle = hud("profilerTitle");
        profilerScopes = hud("profilerScopes");
        eventLegend = hud("events");
        scopeNames[0] = hud("tick");
        scopeNames[1] = hud("render");
        scopeNames[2] = hud("present");
        scopeNames[3] = hud("other");
        cachedStats = stats;
        cachedSystem = system;
        cachedFont = font;
        cachedBudget = budgetMs;
        textReady = true;
        boolean sampled = stats != null && stats.sampleCount > 0;
        double latestMs = sampled ? stats.intervalsNanos[stats.sampleCount - 1] / 1_000_000.0 : Double.NaN;
        boolean latestLong = sampled && (stats.events[stats.sampleCount - 1] & FrameSnapshot.EVENT_LONG_FRAME) != 0;
        double recentTotal = 0;
        int recentCount = 0;
        long minInterval = 0;
        boolean windowLong = false;
        boolean recentLong = false;
        double work = 0;
        for (int s = 0; s < 4; s++) scopeTotals[s] = 0;
        if (sampled) {
            long latest = stats.timestampsNanos[stats.sampleCount - 1];
            long cutoff = latest < Long.MIN_VALUE + 1_000_000_000L ? Long.MIN_VALUE : latest - 1_000_000_000L;
            for (int i = 0; i < stats.sampleCount; i++) {
                long interval = stats.intervalsNanos[i];
                boolean longFrame = (stats.events[i] & FrameSnapshot.EVENT_LONG_FRAME) != 0;
                windowLong |= longFrame;
                if (interval > 0) {
                    if (minInterval == 0 || interval < minInterval) minInterval = interval;
                    if (stats.timestampsNanos[i] >= cutoff) {
                        recentTotal += interval;
                        recentCount++;
                        recentLong |= longFrame;
                    }
                }
                scopeTotals[0] += stats.tickNanos[i];
                scopeTotals[1] += stats.renderNanos[i];
                scopeTotals[2] += stats.presentNanos[i];
                work += stats.workNanos[i];
            }
        }
        // Clamp AFTER summing: phases may overlap or exceed a frame interval.
        scopeTotals[3] = Math.max(0, work - scopeTotals[0] - scopeTotals[1] - scopeTotals[2]);
        double scopeSum = scopeTotals[0] + scopeTotals[1] + scopeTotals[2] + scopeTotals[3];
        for (int s = 0; s < 4; s++) {
            double ms = sampled ? scopeTotals[s] / stats.sampleCount / 1_000_000.0 : Double.NaN;
            scopeFractions[s] = sampled && scopeSum > 0 ? scopeTotals[s] / scopeSum : 0;
            scopeValues[s] = number(ms, 2) + " " + msUnit + " | "
                    + (sampled && scopeSum > 0 ? number(scopeFractions[s] * 100, 1) + "%" : unavailable);
            scopeColors[s] = metricColor(ms, false);
        }

        cacheScopeColumn(font);

        double currentFps = recentCount > 0 && recentTotal > 0 ? recentCount * 1_000_000_000.0 / recentTotal : Double.NaN;
        row(font, layout.titleRow, hud("title"), "", WHITE);
        row(font, layout.fpsRow, hud("fps"), number(currentFps, 0),
                metricColor(recentCount > 0 ? recentTotal / recentCount / 1_000_000.0 : Double.NaN, recentLong));
        row(font, layout.frameRow, hud("frame"), number(latestMs, 2) + " " + msUnit, metricColor(latestMs, latestLong));
        if (layout.statisticsRow >= 0) {
            row(font, layout.statisticsRow, hud("avg"), number(sampled ? stats.fps : Double.NaN, 1)
                    + "  " + hud("max") + number(minInterval > 0 ? 1_000_000_000.0 / minInterval : Double.NaN, 1),
                    metricColor(sampled ? stats.meanMs : Double.NaN, windowLong));
            row(font, layout.statisticsRow + 1, "1% " + hud("low"), number(sampled ? stats.onePercentLowFps : Double.NaN, 1),
                    metricColor(sampled && stats.onePercentLowFps > 0 ? 1000 / stats.onePercentLowFps : Double.NaN, windowLong));
            row(font, layout.statisticsRow + 2, hud("p95p99"), number(sampled ? stats.p95Ms : Double.NaN, 2)
                    + " / " + number(sampled ? stats.p99Ms : Double.NaN, 2) + " " + msUnit,
                    metricColor(sampled ? stats.p99Ms : Double.NaN, windowLong));
        }
        if (layout.memoryRow >= 0) {
            row(font, layout.memoryRow, hud("heap"), memory(system == null ? -1 : system.heapUsedBytes)
                    + " / " + memory(system == null ? -1 : system.heapMaxBytes) + " " + memoryUnit, GREEN);
        }
        if (layout.directMemoryRow >= 0) {
            row(font, layout.directMemoryRow, hud("direct"), memory(system == null ? -1 : system.directUsedBytes)
                    + " " + memoryUnit, GREEN);
        }
        row(font, layout.allocationRow, hud("allocation"), number(system == null ? Double.NaN : system.allocationMiBPerSecond, 1)
                + " " + allocationUnit, GREEN);
        if (layout.gcRow >= 0) {
            row(font, layout.gcRow, hud("gcTotal"), count(system == null ? -1 : system.gcCount) + " | "
                    + count(system == null ? -1 : system.gcCollectionMillis) + " " + msUnit, WHITE);
            boolean gcKnown = sampled && system != null && system.gcNotificationsAvailable;
            row(font, layout.gcRow + 1, hud("gcOverlap"), number(gcKnown ? stats.gcPauseTotalMs : Double.NaN, 1)
                    + " " + msUnit + " | " + hud("frames")
                    + (gcKnown ? Integer.toString(stats.gcOverlapFrames) : unavailable), WHITE);
        }
        if (layout.hardwareRow >= 0) {
            row(font, layout.hardwareRow, hud("cpuUnavailable"), "", WHITE);
            row(font, layout.hardwareRow + 1, hud("gpuUnavailable"), "", WHITE);
            row(font, layout.hardwareRow + 2, hud("vramUnavailable"), "", WHITE);
        }
        if (layout.environmentRow >= 0) {
            ModContainer mod = Loader.instance().getIndexedModList().get("framewatch");
            row(font, layout.environmentRow, hud("version"), "MC 1.7.10 | FrameWatch "
                    + value(mod == null ? null : mod.getVersion()), WHITE);
            row(font, layout.environmentRow + 1, hud("java"), value(system == null ? null : system.javaVersion), WHITE);
            row(font, layout.environmentRow + 2, hud("lwjgl"), value(system == null ? null : system.lwjgl)
                    + " | " + hud("angelica") + value(system == null ? null : system.angelica), WHITE);
            row(font, layout.environmentRow + 3, hud("shader"), value(system == null ? null : system.shader), WHITE);
        }

        axisMs = positiveFinite(cfg.graphMaxMs) ? cfg.graphMaxMs : Math.max(33.3, positiveFinite(budgetMs) ? budgetMs : 0);
        if (!positiveFinite(cfg.graphMaxMs)) {
            int count = peaks == null ? 0 : Math.min(Math.max(0, bucketCount), peaks.length);
            for (int i = Math.max(0, count - graphHeights.length); i < count; i++) {
                axisMs = Math.max(axisMs, peaks[i] / 1_000_000.0);
            }
            axisMs *= 1.1;
        }
        // Auto-axis text changes only at text/stat refresh; new peaks still get clipping markers.
        axisLabel = number(axisMs, 1) + " " + msUnit;
        budgetLabel = hud("budget") + number(positiveFinite(budgetMs) ? budgetMs : Double.NaN, 1) + " " + msUnit;
        durationLabel = Integer.toString(cfg.graphWindowSeconds) + " " + secondsUnit;
        durationWidth = font.getStringWidth(durationLabel);
    }

    private static String hud(String key) {
        return HudTranslations.text("framewatch.hud." + key);
    }

    private static void cacheScopeColumn(FontRenderer font) {
        int labelWidth = 0;
        int timingWidth = 0;
        for (int i = 0; i < 4; i++) {
            labelWidth = Math.max(labelWidth, font.getStringWidth(scopeNames[i]));
            timingWidth = Math.max(timingWidth, font.getStringWidth(scopeValues[i]));
        }
        int width = HudLayout.PROFILER_WIDTH - HudLayout.PADDING * 2;
        int gap = 6;
        // Keep the original English column when it fits; longer translations can
        // expand it, but timings retain their measured width before labels do.
        scopeValueOffset = clamp(Math.max(96, labelWidth + gap), gap,
                Math.max(gap, width - timingWidth));
        for (int i = 0; i < 4; i++) {
            scopeNames[i] = font.trimStringToWidth(scopeNames[i], scopeValueOffset - gap);
        }
    }

    private static void row(FontRenderer font, int index, String label, String value, int color) {
        if (index < 0) return;
        labels[index] = label;
        values[index] = value;
        valueOffsets[index] = font.getStringWidth(label);
        textColors[index] = color;
    }

    private static int metricColor(double ms, boolean longFrame) {
        if (!finiteNonnegative(ms)) return WHITE;
        return longFrame ? RED : positiveFinite(cachedBudget) && ms > cachedBudget ? YELLOW : GREEN;
    }

    private static void cacheGraph(long[] peaks, int[] events, int bucketCount) {
        int count = peaks == null ? 0 : Math.min(Math.max(0, bucketCount), peaks.length);
        graphShown = Math.min(count, graphHeights.length);
        int first = count - graphShown;
        for (int x = 0; x < graphShown; x++) {
            int i = first + x;
            long peak = peaks[i];
            int flags = events != null && i < events.length ? events[i] : 0;
            double ms = peak / 1_000_000.0;
            graphHeights[x] = peak <= 0 ? 0 : Math.max(1, Math.min(layout.graphHeight,
                    (int) Math.ceil(ms / axisMs * layout.graphHeight)));
            graphColors[x] = metricColor(ms, (flags & FrameSnapshot.EVENT_LONG_FRAME) != 0);
            graphFlags[x] = flags;
            graphClipped[x] = peak > 0 && ms > axisMs;
        }
    }

    private static void drawPanel(Minecraft mc, ScaledResolution resolution, FrameConfig cfg, boolean profiler) {
        HudLayout.Bounds bounds = profiler ? layout.right : layout.left;
        if (!bounds.visible()) return;
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            // Origins are GUI pixels; only local panel geometry receives HUD scaling.
            GL11.glTranslated(bounds.x, bounds.y, 0);
            GL11.glScaled(layout.scale, layout.scale, 1);
            setClip(mc, resolution, bounds.x, bounds.y, bounds.width, bounds.height);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            try {
                int alpha = (int) Math.round(Math.max(0, Math.min(1, cfg.panelOpacity)) * 255);
                rect(tessellator, 0, 0, profiler ? HudLayout.PROFILER_WIDTH : layout.contentWidth,
                        profiler ? layout.rightLogicalHeight : layout.leftLogicalHeight, alpha << 24 | 0x101216);
                if (profiler) profilerRects(tessellator);
                else if (cfg.showGraph) graphRects(tessellator, cfg.showGraphEvents);
            } finally {
                // Exactly one untextured rectangle batch per visible panel, also on failure.
                tessellator.draw();
            }
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glColor4f(1, 1, 1, 1);
            // Long labels are clipped to the fixed content width, not used to resize it.
            double padding = HudLayout.PADDING * layout.scale;
            setClip(mc, resolution, bounds.x + padding, bounds.y + padding,
                    ((profiler ? HudLayout.PROFILER_WIDTH : layout.contentWidth) - 2 * HudLayout.PADDING) * layout.scale,
                    ((profiler ? layout.rightLogicalHeight : layout.leftLogicalHeight) - 2 * HudLayout.PADDING) * layout.scale);
            if (profiler) profilerText(mc.fontRenderer, cfg.textShadow);
            else leftText(mc.fontRenderer, cfg);
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
        }
    }

    private static void leftText(FontRenderer font, FrameConfig cfg) {
        int x = HudLayout.PADDING;
        for (int i = 0; i < layout.summaryRows; i++) {
            int y = HudLayout.PADDING + i * HudLayout.ROW_HEIGHT;
            text(font, labels[i], x, y, WHITE, cfg.textShadow);
            text(font, values[i], x + valueOffsets[i], y, textColors[i], cfg.textShadow);
        }
        if (!cfg.showGraph) return;
        text(font, axisLabel, x, layout.graphTop - HudLayout.ROW_HEIGHT, WHITE, cfg.textShadow);
        text(font, budgetLabel, x, layout.graphFooter, WHITE, cfg.textShadow);
        text(font, durationLabel, layout.contentWidth - HudLayout.PADDING - durationWidth,
                layout.graphFooter, WHITE, cfg.textShadow);
        if (layout.graphEventsRow >= 0) text(font, eventLegend, x, layout.graphEventsRow, WHITE, cfg.textShadow);
    }

    private static void profilerText(FontRenderer font, boolean shadow) {
        if (layout.profilerTitleRow >= 0) text(font, profilerTitle, HudLayout.PADDING,
                HudLayout.PADDING + layout.profilerTitleRow * HudLayout.ROW_HEIGHT, WHITE, shadow);
        text(font, profilerScopes, HudLayout.PADDING,
                HudLayout.PADDING + layout.profilerScopesRow * HudLayout.ROW_HEIGHT, WHITE, shadow);
        for (int i = 0; i < 4; i++) {
            int y = layout.profilerTop + i * HudLayout.PROFILER_ROW_HEIGHT;
            text(font, scopeNames[i], HudLayout.PADDING, y, WHITE, shadow);
            text(font, scopeValues[i], HudLayout.PADDING + scopeValueOffset, y, scopeColors[i], shadow);
        }
    }

    private static void profilerRects(Tessellator t) {
        int x = HudLayout.PADDING;
        int width = HudLayout.PROFILER_WIDTH - x * 2;
        for (int i = 0; i < 4; i++) {
            int y = layout.profilerTop + i * HudLayout.PROFILER_ROW_HEIGHT + HudLayout.ROW_HEIGHT;
            rect(t, x, y, x + width, y + HudLayout.PROFILER_BAR_HEIGHT, 0x50363b40);
            if (scopeFractions[i] > 0) rect(t, x, y, x + width * scopeFractions[i],
                    y + HudLayout.PROFILER_BAR_HEIGHT, 0xff000000 | scopeColors[i]);
        }
    }

    private static void graphRects(Tessellator t, boolean showEvents) {
        int left = HudLayout.PADDING;
        int top = layout.graphTop;
        int height = layout.graphHeight;
        int bottom = top + height;
        int width = graphHeights.length;
        int firstX = left + width - graphShown;
        for (int i = 0; i < graphShown; i++) {
            int h = graphHeights[i];
            if (h == 0) continue;
            int x = firstX + i;
            int y = bottom - h;
            int color = graphColors[i];
            rect(t, x, y, x + 1, bottom, 0x50000000 | color);
            rect(t, x, y, x + 1, y + 1, 0xff000000 | color);
            // A thin step-line joins adjacent occupied buckets only, never a gap.
            if (i > 0 && graphHeights[i - 1] > 0) {
                int previousY = bottom - graphHeights[i - 1];
                rect(t, x, Math.min(previousY, y), x + 0.6, Math.max(previousY, y) + 1, 0xff000000 | color);
            }
        }
        if (positiveFinite(cachedBudget) && cachedBudget <= axisMs) {
            double y = Math.max(top, Math.min(bottom - 1, bottom - cachedBudget / axisMs * height));
            rect(t, left, y, left + width, y + 1, 0x90ffffff);
        }
        // Markers follow the budget/line so clipped peaks cannot be hidden by them.
        for (int i = 0; i < graphShown; i++) {
            if (graphHeights[i] == 0) continue;
            int x = firstX + i;
            int flags = graphFlags[i];
            if (graphClipped[i]) rect(t, x, top, x + 1, top + Math.min(2, height), 0xff000000 | graphColors[i]);
            if (showEvents) {
                marker(t, x, top, height, 0, flags, FrameSnapshot.EVENT_GC, 0xffffd344);
                marker(t, x, top, height, 2, flags, FrameSnapshot.EVENT_RESOURCE_RELOAD, 0xff55ffff);
                marker(t, x, top, height, 4, flags, FrameSnapshot.EVENT_SHADER_CHANGE, 0xffff55ff);
                marker(t, x, top, height, 6, flags, FrameSnapshot.EVENT_LONG_FRAME, 0xffff5555);
            }
        }
    }

    private static void marker(Tessellator t, int x, int top, int height, int lane, int flags, int mask, int color) {
        if (lane < height && (flags & mask) != 0) rect(t, x, top + lane, x + 1, top + lane + 1, color);
    }

    private static void rect(Tessellator t, double left, double top, double right, double bottom, int color) {
        t.setColorRGBA_I(color & 0xffffff, color >>> 24);
        t.addVertex(right, top, 0);
        t.addVertex(left, top, 0);
        t.addVertex(left, bottom, 0);
        t.addVertex(right, bottom, 0);
    }

    private static void text(FontRenderer font, String value, int x, int y, int color, boolean shadow) {
        if (value == null || value.length() == 0) return;
        if (shadow) font.drawStringWithShadow(value, x, y, color);
        else font.drawString(value, x, y, color);
    }

    private static ScaledResolution resolution(Minecraft mc) {
        int guiScale = mc.gameSettings.guiScale;
        // Forge stable_12 keeps this query's SRG name. Unicode changes the scale factor.
        boolean unicode = mc.func_152349_b();
        if (cachedResolution == null || cachedDisplayWidth != mc.displayWidth
                || cachedDisplayHeight != mc.displayHeight || cachedGuiScale != guiScale || cachedUnicodeFont != unicode) {
            cachedResolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            cachedDisplayWidth = mc.displayWidth;
            cachedDisplayHeight = mc.displayHeight;
            cachedGuiScale = guiScale;
            cachedUnicodeFont = unicode;
        }
        return cachedResolution;
    }

    private static void setClip(Minecraft mc, ScaledResolution resolution, double x, double y, double width, double height) {
        double sx = mc.displayWidth / resolution.getScaledWidth_double();
        double sy = mc.displayHeight / resolution.getScaledHeight_double();
        int left = clamp((int) Math.ceil(x * sx), 0, mc.displayWidth);
        int right = clamp((int) Math.floor((x + width) * sx), left, mc.displayWidth);
        int bottom = clamp((int) Math.ceil(mc.displayHeight - (y + height) * sy), 0, mc.displayHeight);
        int top = clamp((int) Math.floor(mc.displayHeight - y * sy), bottom, mc.displayHeight);
        GL11.glScissor(left, bottom, right - left, top - bottom);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static boolean finiteNonnegative(double value) {
        return value >= 0 && !Double.isInfinite(value) && !Double.isNaN(value);
    }

    private static boolean positiveFinite(double value) {
        return value > 0 && finiteNonnegative(value);
    }

    private static String number(double value, int decimals) {
        if (!finiteNonnegative(value)) return unavailable;
        return String.format(Locale.ROOT, decimals == 0 ? "%.0f" : decimals == 1 ? "%.1f" : "%.2f", value);
    }

    private static String memory(long bytes) {
        return bytes < 0 ? unavailable : number(bytes / 1048576.0, 1);
    }

    private static String count(long value) {
        return value < 0 ? unavailable : Long.toString(value);
    }

    private static String value(String value) {
        if (value == null || value.trim().length() == 0 || "unknown".equalsIgnoreCase(value.trim())
                || "n/a".equalsIgnoreCase(value.trim())) return unavailable;
        // Metadata must not inject font colors or control characters.
        return value.replace('\u00a7', '?').replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }
}
