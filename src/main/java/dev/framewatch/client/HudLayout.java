package dev.framewatch.client;

import dev.framewatch.FrameConfig;

/** Pure GUI-pixel geometry shared by the HUD and its position editor. */
public final class HudLayout {
    public static final int PADDING = 6;
    public static final int ROW_HEIGHT = 11;
    public static final int STATISTICS_ROWS = 3;
    public static final int MEMORY_ROWS = 1;
    public static final int GC_ROWS = 2;
    public static final int HARDWARE_ROWS = 3;
    public static final int ENVIRONMENT_ROWS = 4;
    public static final int PROFILER_WIDTH = 252;
    public static final int PROFILER_BAR_HEIGHT = 4;
    public static final int PROFILER_ROW_HEIGHT = ROW_HEIGHT + PROFILER_BAR_HEIGHT + 4;

    /** Already scaled, anchored, and clamped: do not scale these origins again. */
    public final Bounds left, right;
    public final double scale;
    /** Outer logical left-panel width, including padding; never depends on text. */
    public final int contentWidth;
    public final int leftLogicalHeight, rightLogicalHeight;

    // Row indices, not glyph measurements. The renderer uses these exact offsets.
    final int titleRow, fpsRow, frameRow, statisticsRow, memoryRow, directMemoryRow;
    final int allocationRow, gcRow, hardwareRow, environmentRow, summaryRows;
    final int graphTop, graphHeight, graphFooter, graphEventsRow;
    final int fpsGraphTop, memoryGraphTop;
    final int profilerTitleRow, profilerScopesRow, profilerTop;

    private HudLayout(FrameConfig cfg, int screenWidth, int screenHeight) {
        contentWidth = Math.max(1, cfg.graphWidth) + PADDING * 2;
        boolean summary = cfg.showFps || cfg.showFrameTime || cfg.showStatistics || cfg.showMemory
                || cfg.showDirectMemory || cfg.showAllocation || cfg.showGc || cfg.showHardware || cfg.showEnvironment;
        boolean leftContent = summary || cfg.hasGraphs();
        int rows = 0;
        titleRow = cfg.showTitles && leftContent ? rows++ : -1;
        // When a graph is visible its header owns the metric, not the summary.
        fpsRow = cfg.showFps && !cfg.showFpsGraph ? rows++ : -1;
        frameRow = cfg.showFrameTime && !cfg.showGraph ? rows++ : -1;
        statisticsRow = cfg.showStatistics ? rows : -1;
        if (cfg.showStatistics) rows += STATISTICS_ROWS;
        memoryRow = cfg.showMemory && !cfg.showMemoryGraph ? rows++ : -1;
        directMemoryRow = cfg.showDirectMemory ? rows++ : -1;
        allocationRow = cfg.showAllocation ? rows++ : -1;
        gcRow = cfg.showGc ? rows : -1;
        if (cfg.showGc) rows += GC_ROWS;
        hardwareRow = cfg.showHardware ? rows : -1;
        if (cfg.showHardware) rows += HARDWARE_ROWS;
        environmentRow = cfg.showEnvironment ? rows : -1;
        if (cfg.showEnvironment) rows += ENVIRONMENT_ROWS;
        summaryRows = rows;
        graphHeight = Math.max(1, cfg.graphHeight);
        // A top axis row and footer are part of the graph even with titles disabled.
        graphTop = cfg.showGraph ? PADDING + (rows + 1) * ROW_HEIGHT : -1;
        graphFooter = cfg.showGraph ? graphTop + graphHeight + 2 : -1;
        graphEventsRow = cfg.showGraph && cfg.showGraphEvents ? graphFooter + ROW_HEIGHT : -1;
        int bottom = cfg.showGraph
                ? graphFooter + ROW_HEIGHT + (cfg.showGraphEvents ? ROW_HEIGHT : 0)
                : PADDING + rows * ROW_HEIGHT;
        fpsGraphTop = cfg.showFpsGraph ? bottom + ROW_HEIGHT : -1;
        if (cfg.showFpsGraph) bottom = fpsGraphTop + graphHeight + 2 + ROW_HEIGHT;
        memoryGraphTop = cfg.showMemoryGraph ? bottom + ROW_HEIGHT : -1;
        if (cfg.showMemoryGraph) bottom = memoryGraphTop + graphHeight + 2 + ROW_HEIGHT;
        leftLogicalHeight = leftContent ? bottom + PADDING : 0;

        profilerTitleRow = cfg.showProfiler && cfg.showTitles ? 0 : -1;
        profilerScopesRow = cfg.showTitles ? 1 : 0;
        profilerTop = PADDING + (profilerScopesRow + 1) * ROW_HEIGHT;
        rightLogicalHeight = cfg.showProfiler ? profilerTop + 4 * PROFILER_ROW_HEIGHT + PADDING : 0;

        int sw = Math.max(0, screenWidth);
        int sh = Math.max(0, screenHeight);
        double requested = cfg.hudScale;
        if (!(requested > 0.0) || Double.isInfinite(requested)) requested = 0.8;
        int tallest = Math.max(leftLogicalHeight, rightLogicalHeight);
        int widest = leftLogicalHeight > 0 ? contentWidth : 0;
        if (rightLogicalHeight > 0) widest = Math.max(widest, PROFILER_WIDTH);
        scale = sw == 0 || sh == 0 ? requested : tallest == 0 ? requested
                : Math.min(requested, Math.min(sw / (double) widest, sh / (double) tallest));
        int width = scaled(contentWidth, scale, sw);
        left = place(cfg.hudEnabled, width, scaled(leftLogicalHeight, scale, sh),
                cfg.leftAnchor, cfg.x, cfg.y, sw, sh);
        right = place(cfg.hudEnabled, scaled(PROFILER_WIDTH, scale, sw), scaled(rightLogicalHeight, scale, sh),
                cfg.rightAnchor, cfg.profilerX, cfg.profilerY, sw, sh);
    }

    public static HudLayout calculate(FrameConfig cfg, int screenWidth, int screenHeight) {
        return new HudLayout(cfg, screenWidth, screenHeight);
    }

    /**
     * Moves a panel's top-left GUI origin. Offsets are GUI pixels from its anchor,
     * not logical pixels: HUD scaling affects dimensions, never the drag delta.
     * The other panel's independently anchored offsets are not modified.
     */
    public static void move(FrameConfig cfg, boolean profiler, int newX, int newY, int sw, int sh) {
        HudLayout layout = calculate(cfg, sw, sh);
        sw = Math.max(0, sw);
        sh = Math.max(0, sh);
        int height = profiler ? layout.rightLogicalHeight : layout.leftLogicalHeight;
        int width = height > 0 ? scaled(profiler ? PROFILER_WIDTH : layout.contentWidth, layout.scale, sw) : 0;
        int scaledHeight = scaled(height, layout.scale, sh);
        int x = clamp(newX, sw - width);
        int y = clamp(newY, sh - scaledHeight);
        String anchor = profiler ? cfg.rightAnchor : cfg.leftAnchor;
        int offsetX = fromRight(anchor) ? sw - width - x : x;
        int offsetY = fromBottom(anchor) ? sh - scaledHeight - y : y;
        if (offsetX == (profiler ? cfg.profilerX : cfg.x)
                && offsetY == (profiler ? cfg.profilerY : cfg.y)) return;
        if (profiler) {
            cfg.profilerX = offsetX;
            cfg.profilerY = offsetY;
        } else {
            cfg.x = offsetX;
            cfg.y = offsetY;
        }
        cfg.changed();
    }

    private static Bounds place(boolean enabled, int width, int height, String anchor,
            int offsetX, int offsetY, int sw, int sh) {
        if (!enabled || width <= 0 || height <= 0) return new Bounds(0, 0, 0, 0);
        int x = clamp(fromRight(anchor) ? sw - width - Math.max(0, offsetX) : offsetX, sw - width);
        int y = clamp(fromBottom(anchor) ? sh - height - Math.max(0, offsetY) : offsetY, sh - height);
        return new Bounds(x, y, width, height);
    }

    private static boolean fromRight(String anchor) {
        return "TOP_RIGHT".equals(anchor) || "BOTTOM_RIGHT".equals(anchor);
    }

    private static boolean fromBottom(String anchor) {
        return "BOTTOM_LEFT".equals(anchor) || "BOTTOM_RIGHT".equals(anchor);
    }

    private static int scaled(int logical, double scale, int limit) {
        return Math.min(limit, Math.max(0, (int) Math.ceil(logical * scale)));
    }

    private static int clamp(int value, int maximum) {
        return Math.max(0, Math.min(value, Math.max(0, maximum)));
    }

    public static final class Bounds {
        public final int x, y, width, height;

        private Bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public boolean visible() {
            return width > 0 && height > 0;
        }

        /** Half-open GUI-pixel hitbox, identical to the renderer's panel clip. */
        public boolean contains(int px, int py) {
            return visible() && px >= x && py >= y && px - x < width && py - y < height;
        }
    }
}
