package dev.framewatch.client;

import dev.framewatch.FrameConfig;
import org.junit.Test;

import static org.junit.Assert.*;

public class HudLayoutTest {
    private static final String[] ANCHORS = { "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT" };

    @Test
    public void disabledBlocksAndDecorationAloneDoNotMakeEmptyPanels() {
        FrameConfig cfg = minimal();
        cfg.showTitles = true;
        cfg.showGraphEvents = true;
        HudLayout layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(0, layout.summaryRows);
        assertEquals(0, layout.leftLogicalHeight);
        assertEquals(0, layout.rightLogicalHeight);
        assertFalse(layout.left.visible());
        assertFalse(layout.right.visible());
        assertFalse(layout.left.contains(0, 0));

        cfg.showProfiler = true;
        layout = HudLayout.calculate(cfg, 854, 480);
        assertFalse(layout.left.visible());
        assertTrue(layout.right.visible());
        assertEquals(12 + 22 + 4 * 19, layout.rightLogicalHeight);

        cfg.hudEnabled = false;
        layout = HudLayout.calculate(cfg, 854, 480);
        assertFalse(layout.left.visible());
        assertFalse(layout.right.visible());
    }

    @Test
    public void minimalFpsHasOneRowAndFixedWidth() {
        FrameConfig cfg = minimal();
        cfg.showFps = true;
        HudLayout layout = HudLayout.calculate(cfg, 854, 480);
        assertTrue(layout.left.visible());
        assertFalse(layout.right.visible());
        assertEquals(252, layout.contentWidth);
        assertEquals(23, layout.leftLogicalHeight);
        assertEquals(1, layout.summaryRows);
        assertEquals(0, layout.fpsRow);
        assertEquals(-1, layout.titleRow);
        assertEquals(-1, layout.graphTop);
        assertEquals(202, layout.left.width);
        assertEquals(19, layout.left.height);
    }

    @Test
    public void eachSummaryToggleHasExactlyItsDeclaredRows() {
        for (int block = 0; block < 9; block++) {
            FrameConfig cfg = minimal();
            int expected;
            switch (block) {
                case 0: cfg.showFps = true; expected = 1; break;
                case 1: cfg.showFrameTime = true; expected = 1; break;
                case 2: cfg.showStatistics = true; expected = 3; break;
                case 3: cfg.showMemory = true; expected = HudLayout.MEMORY_ROWS; break;
                case 4: cfg.showDirectMemory = true; expected = 1; break;
                case 5: cfg.showAllocation = true; expected = 1; break;
                case 6: cfg.showGc = true; expected = 2; break;
                case 7: cfg.showHardware = true; expected = 3; break;
                default: cfg.showEnvironment = true; expected = 4;
            }
            HudLayout layout = HudLayout.calculate(cfg, 854, 480);
            assertEquals(expected, layout.summaryRows);
            assertEquals(12 + expected * 11, layout.leftLogicalHeight);
            cfg.showTitles = true;
            layout = HudLayout.calculate(cfg, 854, 480);
            assertEquals(expected + 1, layout.summaryRows);
            assertEquals(12 + (expected + 1) * 11, layout.leftLogicalHeight);
            assertEquals(0, layout.titleRow);
        }
    }

    @Test
    public void allRowsAreContiguousInRendererOrderAndGraphFits() {
        FrameConfig cfg = full();
        HudLayout layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(0, layout.titleRow);
        assertEquals(1, layout.fpsRow);
        assertEquals(-1, layout.frameRow);
        assertEquals(2, layout.statisticsRow);
        assertEquals(5, layout.memoryRow);
        assertEquals(6, layout.directMemoryRow);
        assertEquals(7, layout.allocationRow);
        assertEquals(8, layout.gcRow);
        assertEquals(10, layout.hardwareRow);
        assertEquals(13, layout.environmentRow);
        assertEquals(17, layout.summaryRows);
        assertEquals(6 + 18 * 11, layout.graphTop);
        assertEquals(layout.graphTop + 45 + 2, layout.graphFooter);
        assertEquals(layout.graphFooter + 11, layout.graphEventsRow);
        assertEquals(layout.graphEventsRow + 11 + 6, layout.leftLogicalHeight);
        assertEquals(layout.profilerTop + 4 * HudLayout.PROFILER_ROW_HEIGHT + 6, layout.rightLogicalHeight);
    }

    @Test
    public void heapAndDirectOnlyPanelsHaveNoEmptyRowsInAnyCombination() {
        assertEquals(1, HudLayout.MEMORY_ROWS);
        for (boolean heap : new boolean[] { false, true }) {
            for (boolean direct : new boolean[] { false, true }) {
                for (boolean titles : new boolean[] { false, true }) {
                    for (boolean graph : new boolean[] { false, true }) {
                        FrameConfig cfg = minimal();
                        cfg.showMemory = heap;
                        cfg.showDirectMemory = direct;
                        cfg.showTitles = titles;
                        cfg.showGraph = graph;
                        HudLayout layout = HudLayout.calculate(cfg, 854, 480);
                        int memoryRows = (heap ? 1 : 0) + (direct ? 1 : 0);
                        int titleRows = titles && (memoryRows > 0 || graph) ? 1 : 0;
                        int rows = memoryRows + titleRows;
                        assertEquals(titleRows > 0 ? 0 : -1, layout.titleRow);
                        assertEquals(heap ? titleRows : -1, layout.memoryRow);
                        assertEquals(direct ? titleRows + (heap ? 1 : 0) : -1, layout.directMemoryRow);
                        assertEquals(rows, layout.summaryRows);
                        assertEquals(memoryRows > 0 || graph, layout.left.visible());
                        assertFalse(layout.right.visible());
                        if (graph) {
                            assertEquals(6 + (rows + 1) * 11, layout.graphTop);
                            assertEquals(layout.graphFooter + 11 + 6, layout.leftLogicalHeight);
                        } else {
                            assertEquals(-1, layout.graphTop);
                            assertEquals(rows == 0 ? 0 : 12 + rows * 11, layout.leftLogicalHeight);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void memoryCombinationsKeepFollowingBlocksContiguous() {
        for (boolean heap : new boolean[] { false, true }) {
            for (boolean direct : new boolean[] { false, true }) {
                FrameConfig cfg = full();
                cfg.showMemory = heap;
                cfg.showDirectMemory = direct;
                HudLayout layout = HudLayout.calculate(cfg, 854, 480);
                int next = 5;
                assertEquals(heap ? next++ : -1, layout.memoryRow);
                assertEquals(direct ? next++ : -1, layout.directMemoryRow);
                assertEquals(next++, layout.allocationRow);
                assertEquals(next, layout.gcRow);
                next += HudLayout.GC_ROWS;
                assertEquals(next, layout.hardwareRow);
                next += HudLayout.HARDWARE_ROWS;
                assertEquals(next, layout.environmentRow);
                next += HudLayout.ENVIRONMENT_ROWS;
                assertEquals(next, layout.summaryRows);
                assertEquals(6 + (next + 1) * 11, layout.graphTop);
            }
        }
    }

    @Test
    public void memoryTogglesIndependentlyAddOneRowWithoutChangingProfiler() {
        for (boolean graph : new boolean[] { false, true }) {
            for (boolean directFirst : new boolean[] { false, true }) {
                FrameConfig cfg = minimal();
                cfg.showFps = cfg.showProfiler = true;
                cfg.showGraph = graph;
                cfg.hudScale = 1;
                HudLayout before = HudLayout.calculate(cfg, 854, 480);
                if (directFirst) cfg.showDirectMemory = true;
                else cfg.showMemory = true;
                HudLayout one = HudLayout.calculate(cfg, 854, 480);
                cfg.showDirectMemory = cfg.showMemory = true;
                HudLayout both = HudLayout.calculate(cfg, 854, 480);
                if (directFirst) cfg.showDirectMemory = false;
                else cfg.showMemory = false;
                HudLayout other = HudLayout.calculate(cfg, 854, 480);
                cfg.showDirectMemory = cfg.showMemory = false;
                HudLayout neither = HudLayout.calculate(cfg, 854, 480);
                assertEquals(before.leftLogicalHeight + 11, one.leftLogicalHeight);
                assertEquals(one.leftLogicalHeight + 11, both.leftLogicalHeight);
                assertEquals(one.leftLogicalHeight, other.leftLogicalHeight);
                assertEquals(before.leftLogicalHeight, neither.leftLogicalHeight);
                for (HudLayout changed : new HudLayout[] { one, both, other, neither }) {
                    assertEquals(before.rightLogicalHeight, changed.rightLogicalHeight);
                    assertEquals(before.right.x, changed.right.x);
                    assertEquals(before.right.y, changed.right.y);
                    assertEquals(before.right.width, changed.right.width);
                    assertEquals(before.right.height, changed.right.height);
                }
                if (graph) {
                    assertEquals(before.graphTop + 11, one.graphTop);
                    assertEquals(before.graphTop + 22, both.graphTop);
                    assertEquals(one.graphTop, other.graphTop);
                    assertEquals(before.graphTop, neither.graphTop);
                }
            }
        }
    }

    @Test
    public void memoryCombinationsPreserveAllAnchorsAndIndependentDragging() {
        for (boolean heap : new boolean[] { false, true }) {
            for (boolean direct : new boolean[] { false, true }) {
                for (String leftAnchor : ANCHORS) {
                    for (String rightAnchor : ANCHORS) {
                        FrameConfig cfg = minimal();
                        cfg.showMemory = heap;
                        cfg.showDirectMemory = direct;
                        cfg.showProfiler = true;
                        cfg.leftAnchor = leftAnchor;
                        cfg.rightAnchor = rightAnchor;
                        HudLayout before = HudLayout.calculate(cfg, 854, 480);
                        assertAnchor(before.right, rightAnchor, cfg.profilerX, cfg.profilerY, 854, 480);
                        if (heap || direct) {
                            assertAnchor(before.left, leftAnchor, cfg.x, cfg.y, 854, 480);
                            HudLayout.move(cfg, false, 71, 83, 854, 480);
                            HudLayout moved = HudLayout.calculate(cfg, 854, 480);
                            assertEquals(71, moved.left.x);
                            assertEquals(83, moved.left.y);
                            assertEquals(before.right.x, moved.right.x);
                            assertEquals(before.right.y, moved.right.y);
                        } else {
                            assertFalse(before.left.visible());
                        }
                        int leftX = cfg.x, leftY = cfg.y;
                        HudLayout.move(cfg, true, 97, 109, 854, 480);
                        HudLayout moved = HudLayout.calculate(cfg, 854, 480);
                        assertEquals(97, moved.right.x);
                        assertEquals(109, moved.right.y);
                        assertEquals(leftX, cfg.x);
                        assertEquals(leftY, cfg.y);
                        assertEquals(leftAnchor, cfg.leftAnchor);
                        assertEquals(rightAnchor, cfg.rightAnchor);
                        HudLayout.move(cfg, true, Integer.MAX_VALUE, Integer.MIN_VALUE, 320, 240);
                        assertOnScreen(HudLayout.calculate(cfg, 320, 240).right, 320, 240);
                        if (heap || direct) {
                            HudLayout.move(cfg, false, Integer.MIN_VALUE, Integer.MAX_VALUE, 320, 240);
                            assertOnScreen(HudLayout.calculate(cfg, 320, 240).left, 320, 240);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void graphOnlyReservesAxesAndOptionalEventLegendWithoutSummaryRows() {
        FrameConfig cfg = minimal();
        cfg.showGraph = true;
        HudLayout layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(0, layout.summaryRows);
        assertEquals(17, layout.graphTop);
        assertEquals(64, layout.graphFooter);
        assertEquals(81, layout.leftLogicalHeight);
        assertEquals(-1, layout.graphEventsRow);
        cfg.showGraphEvents = true;
        layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(92, layout.leftLogicalHeight);
        cfg.showGraph = false;
        layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(0, layout.leftLogicalHeight);
        assertFalse(layout.left.visible());
    }

    @Test
    public void profilerHasFourTimingAndBarRowsWithOptionalTitle() {
        FrameConfig cfg = minimal();
        cfg.showProfiler = true;
        HudLayout layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(-1, layout.profilerTitleRow);
        assertEquals(0, layout.profilerScopesRow);
        assertEquals(17, layout.profilerTop);
        assertEquals(99, layout.rightLogicalHeight);
        cfg.showTitles = true;
        layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(0, layout.profilerTitleRow);
        assertEquals(1, layout.profilerScopesRow);
        assertEquals(110, layout.rightLogicalHeight);
        assertFalse(layout.left.visible());
    }

    @Test
    public void narrowGraphDoesNotClipAwayProfilerTimingColumn() {
        FrameConfig cfg = minimal();
        cfg.showGraph = cfg.showProfiler = true;
        cfg.graphWidth = 100;
        HudLayout layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(112, layout.contentWidth);
        assertEquals(90, layout.left.width);
        assertEquals(202, layout.right.width);
        cfg.graphWidth = 400;
        layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(330, layout.left.width);
        assertEquals(202, layout.right.width);
        HudLayout.move(cfg, true, 71, 83, 854, 480);
        layout = HudLayout.calculate(cfg, 854, 480);
        assertEquals(71, layout.right.x);
        assertEquals(83, layout.right.y);
    }

    @Test
    public void anchorsUseScaledDimensionsButUnscaledGuiOffsets() {
        int[][] resolutions = { { 320, 240 }, { 854, 480 }, { 1920, 1080 }, { 3840, 2160 } };
        double[] scales = { 0.5, 0.8, 1.0, 2.0 };
        for (int[] resolution : resolutions) {
            for (double scale : scales) {
                for (String anchor : ANCHORS) {
                    FrameConfig cfg = minimal();
                    cfg.showFps = true;
                    cfg.showProfiler = true;
                    cfg.hudScale = scale;
                    cfg.leftAnchor = anchor;
                    cfg.rightAnchor = anchor;
                    cfg.x = 7;
                    cfg.y = 9;
                    cfg.profilerX = 13;
                    cfg.profilerY = 15;
                    int sw = resolution[0], sh = resolution[1];
                    HudLayout layout = HudLayout.calculate(cfg, sw, sh);
                    assertEquals(Math.min(scale, Math.min(sw / 252.0, sh / 99.0)), layout.scale, 1e-12);
                    assertAnchor(layout.left, anchor, 7, 9, sw, sh);
                    assertAnchor(layout.right, anchor, 13, 15, sw, sh);
                    assertOnScreen(layout.left, sw, sh);
                    assertOnScreen(layout.right, sw, sh);
                }
            }
        }
    }

    @Test
    public void adaptiveFitAndClampingKeepEveryVisiblePanelOnScreen() {
        int[][] resolutions = { { 1, 1 }, { 17, 9 }, { 160, 90 }, { 320, 240 }, { 854, 480 }, { 7680, 4320 } };
        for (int[] resolution : resolutions) {
            for (String leftAnchor : ANCHORS) {
                for (String rightAnchor : ANCHORS) {
                    FrameConfig cfg = full();
                    cfg.hudScale = 4;
                    cfg.leftAnchor = leftAnchor;
                    cfg.rightAnchor = rightAnchor;
                    cfg.x = cfg.profilerX = 10000;
                    cfg.y = cfg.profilerY = -100;
                    HudLayout layout = HudLayout.calculate(cfg, resolution[0], resolution[1]);
                    assertTrue(layout.scale > 0 && layout.scale <= cfg.hudScale);
                    assertOnScreen(layout.left, resolution[0], resolution[1]);
                    assertOnScreen(layout.right, resolution[0], resolution[1]);
                }
            }
        }
        FrameConfig cfg = full();
        assertFalse(HudLayout.calculate(cfg, 0, 480).left.visible());
        assertFalse(HudLayout.calculate(cfg, 854, 0).right.visible());
        assertFalse(HudLayout.calculate(cfg, -1, -1).left.visible());
    }

    @Test
    public void dragConvertsAllAnchorOffsetsAndLeavesOtherPanelUntouched() {
        for (String anchor : ANCHORS) {
            for (boolean profiler : new boolean[] { false, true }) {
                FrameConfig cfg = full();
                cfg.leftAnchor = anchor;
                cfg.rightAnchor = anchor;
                cfg.hudScale = 0.8;
                int otherX = profiler ? cfg.x : cfg.profilerX;
                int otherY = profiler ? cfg.y : cfg.profilerY;
                long revision = cfg.revision();
                HudLayout.move(cfg, profiler, 71, 83, 854, 480);
                assertEquals(revision + 1, cfg.revision());
                HudLayout layout = HudLayout.calculate(cfg, 854, 480);
                HudLayout.Bounds moved = profiler ? layout.right : layout.left;
                assertEquals(71, moved.x);
                assertEquals(83, moved.y);
                assertEquals(otherX, profiler ? cfg.x : cfg.profilerX);
                assertEquals(otherY, profiler ? cfg.y : cfg.profilerY);
                assertEquals(anchor, cfg.leftAnchor);
                assertEquals(anchor, cfg.rightAnchor);
                int x = profiler ? cfg.profilerX : cfg.x;
                int y = profiler ? cfg.profilerY : cfg.y;
                assertEquals(anchor.endsWith("RIGHT") ? 854 - moved.width - 71 : 71, x);
                assertEquals(anchor.startsWith("BOTTOM") ? 480 - moved.height - 83 : 83, y);
            }
        }
    }

    @Test
    public void noOpDragPreservesRevisionAndBothPanelsOffsets() {
        for (String anchor : ANCHORS) {
            for (boolean profiler : new boolean[] { false, true }) {
                FrameConfig cfg = full();
                cfg.leftAnchor = cfg.rightAnchor = anchor;
                cfg.x = 11;
                cfg.y = 13;
                cfg.profilerX = 17;
                cfg.profilerY = 19;
                HudLayout layout = HudLayout.calculate(cfg, 854, 480);
                HudLayout.Bounds bounds = profiler ? layout.right : layout.left;
                long revision = cfg.revision();
                HudLayout.move(cfg, profiler, bounds.x, bounds.y, 854, 480);
                assertEquals(revision, cfg.revision());
                assertEquals(11, cfg.x);
                assertEquals(13, cfg.y);
                assertEquals(17, cfg.profilerX);
                assertEquals(19, cfg.profilerY);
                assertEquals(anchor, cfg.leftAnchor);
                assertEquals(anchor, cfg.rightAnchor);
            }
        }
    }

    @Test
    public void movingOnlyOneAxisStillIncrementsRevision() {
        for (String anchor : ANCHORS) {
            for (boolean profiler : new boolean[] { false, true }) {
                FrameConfig cfg = full();
                cfg.leftAnchor = cfg.rightAnchor = anchor;
                HudLayout layout = HudLayout.calculate(cfg, 854, 480);
                HudLayout.Bounds bounds = profiler ? layout.right : layout.left;
                int otherX = profiler ? cfg.x : cfg.profilerX;
                int otherY = profiler ? cfg.y : cfg.profilerY;
                long revision = cfg.revision();
                HudLayout.move(cfg, profiler, bounds.x + 1, bounds.y, 854, 480);
                assertEquals(revision + 1, cfg.revision());
                HudLayout.move(cfg, profiler, bounds.x + 1, bounds.y + 1, 854, 480);
                assertEquals(revision + 2, cfg.revision());
                assertEquals(otherX, profiler ? cfg.x : cfg.profilerX);
                assertEquals(otherY, profiler ? cfg.y : cfg.profilerY);
            }
        }
    }

    @Test
    public void repeatedClampedEdgeDragDoesNotIncrementRevisionOrMoveOtherPanel() {
        int[][] outside = { { Integer.MAX_VALUE, Integer.MIN_VALUE }, { Integer.MIN_VALUE, Integer.MAX_VALUE } };
        for (String anchor : ANCHORS) {
            for (boolean profiler : new boolean[] { false, true }) {
                for (int[] position : outside) {
                    FrameConfig cfg = full();
                    cfg.leftAnchor = cfg.rightAnchor = anchor;
                    cfg.hudScale = 2;
                    int otherX = profiler ? cfg.x : cfg.profilerX;
                    int otherY = profiler ? cfg.y : cfg.profilerY;
                    long revision = cfg.revision();
                    HudLayout.move(cfg, profiler, position[0], position[1], 320, 240);
                    assertEquals(revision + 1, cfg.revision());
                    int targetX = profiler ? cfg.profilerX : cfg.x;
                    int targetY = profiler ? cfg.profilerY : cfg.y;
                    revision = cfg.revision();
                    // Different off-screen origins clamp to the same GUI edge.
                    HudLayout.move(cfg, profiler, position[0] / 2, position[1] / 2, 320, 240);
                    assertEquals(revision, cfg.revision());
                    assertEquals(targetX, profiler ? cfg.profilerX : cfg.x);
                    assertEquals(targetY, profiler ? cfg.profilerY : cfg.y);
                    assertEquals(otherX, profiler ? cfg.x : cfg.profilerX);
                    assertEquals(otherY, profiler ? cfg.y : cfg.profilerY);
                    assertEquals(anchor, cfg.leftAnchor);
                    assertEquals(anchor, cfg.rightAnchor);
                }
            }
        }
    }

    @Test
    public void draggingOutsideScreenClampsAndWorksAfterAdaptiveFit() {
        for (String anchor : ANCHORS) {
            for (boolean profiler : new boolean[] { false, true }) {
                FrameConfig cfg = full();
                cfg.leftAnchor = anchor;
                cfg.rightAnchor = anchor;
                cfg.hudScale = 2;
                HudLayout.move(cfg, profiler, Integer.MAX_VALUE, Integer.MIN_VALUE, 320, 240);
                HudLayout layout = HudLayout.calculate(cfg, 320, 240);
                HudLayout.Bounds bounds = profiler ? layout.right : layout.left;
                assertEquals(320 - bounds.width, bounds.x);
                assertEquals(0, bounds.y);
                assertOnScreen(bounds, 320, 240);
                HudLayout.move(cfg, profiler, Integer.MIN_VALUE, Integer.MAX_VALUE, 320, 240);
                layout = HudLayout.calculate(cfg, 320, 240);
                bounds = profiler ? layout.right : layout.left;
                assertEquals(0, bounds.x);
                assertEquals(240 - bounds.height, bounds.y);
                assertOnScreen(bounds, 320, 240);
            }
        }
    }

    @Test
    public void boundsHitTestingUsesHalfOpenEdges() {
        FrameConfig cfg = full();
        HudLayout layout = HudLayout.calculate(cfg, 854, 480);
        for (HudLayout.Bounds bounds : new HudLayout.Bounds[] { layout.left, layout.right }) {
            assertTrue(bounds.contains(bounds.x, bounds.y));
            assertTrue(bounds.contains(bounds.x + bounds.width - 1, bounds.y + bounds.height - 1));
            assertFalse(bounds.contains(bounds.x - 1, bounds.y));
            assertFalse(bounds.contains(bounds.x, bounds.y - 1));
            assertFalse(bounds.contains(bounds.x + bounds.width, bounds.y));
            assertFalse(bounds.contains(bounds.x, bounds.y + bounds.height));
        }
    }

    @Test
    public void measurementAndStyleSettingsDoNotChangeGeometry() {
        FrameConfig cfg = full();
        HudLayout before = HudLayout.calculate(cfg, 854, 480);
        cfg.detailed = !cfg.detailed;
        cfg.textShadow = !cfg.textShadow;
        cfg.graphMaxMs = 0;
        cfg.panelOpacity = 0;
        cfg.windowSeconds = 60;
        cfg.graphWindowSeconds = 30;
        cfg.targetFps = 1000;
        cfg.longFrameMillis = 1000;
        cfg.textRefreshMillis = 1000;
        cfg.graphRefreshMillis = 100;
        HudLayout after = HudLayout.calculate(cfg, 854, 480);
        assertEquals(before.contentWidth, after.contentWidth);
        assertEquals(before.leftLogicalHeight, after.leftLogicalHeight);
        assertEquals(before.rightLogicalHeight, after.rightLogicalHeight);
        assertEquals(before.scale, after.scale, 0);
    }

    private static void assertAnchor(HudLayout.Bounds b, String anchor, int x, int y, int sw, int sh) {
        assertEquals(Math.max(0, Math.min(anchor.endsWith("RIGHT") ? sw - b.width - x : x, sw - b.width)), b.x);
        assertEquals(Math.max(0, Math.min(anchor.startsWith("BOTTOM") ? sh - b.height - y : y, sh - b.height)), b.y);
    }

    private static void assertOnScreen(HudLayout.Bounds b, int sw, int sh) {
        assertTrue(b.visible());
        assertTrue(b.x >= 0 && b.y >= 0);
        assertTrue(b.x + b.width <= sw);
        assertTrue(b.y + b.height <= sh);
    }

    private static FrameConfig minimal() {
        FrameConfig cfg = new FrameConfig();
        cfg.hudEnabled = true;
        cfg.showTitles = cfg.showFps = cfg.showFrameTime = cfg.showStatistics = false;
        cfg.showMemory = cfg.showDirectMemory = cfg.showAllocation = cfg.showGc = cfg.showHardware = false;
        cfg.showEnvironment = cfg.showProfiler = cfg.showGraph = cfg.showGraphEvents = false;
        cfg.x = cfg.y = cfg.profilerX = cfg.profilerY = 6;
        cfg.graphWidth = 240;
        cfg.graphHeight = 45;
        cfg.hudScale = 0.8;
        cfg.leftAnchor = "TOP_LEFT";
        cfg.rightAnchor = "TOP_RIGHT";
        return cfg;
    }

    private static FrameConfig full() {
        FrameConfig cfg = minimal();
        cfg.showTitles = cfg.showFps = cfg.showFrameTime = cfg.showStatistics = true;
        cfg.showMemory = cfg.showDirectMemory = cfg.showAllocation = cfg.showGc = cfg.showHardware = true;
        cfg.showEnvironment = cfg.showProfiler = cfg.showGraph = cfg.showGraphEvents = true;
        return cfg;
    }
}
