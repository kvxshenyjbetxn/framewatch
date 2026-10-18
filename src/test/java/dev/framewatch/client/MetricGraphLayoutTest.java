package dev.framewatch.client;

import dev.framewatch.FrameConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetricGraphLayoutTest {
    @Test public void independentGraphsStackWithoutEmptySlots() {
        FrameConfig c = new FrameConfig();
        c.applyPreset("minimal");
        c.showFps = c.showFrameTime = false;
        assertFalse(HudLayout.calculate(c, 800, 600).left.visible());
        c.showFpsGraph = true;
        HudLayout fps = HudLayout.calculate(c, 800, 600);
        assertTrue(fps.left.visible());
        assertEquals(-1, fps.graphTop);
        assertEquals(-1, fps.memoryGraphTop);
        assertEquals(HudLayout.PADDING + HudLayout.ROW_HEIGHT, fps.fpsGraphTop);
        c.showMemoryGraph = true;
        HudLayout both = HudLayout.calculate(c, 800, 600);
        assertTrue(both.memoryGraphTop > both.fpsGraphTop + both.graphHeight);
        assertTrue(both.leftLogicalHeight > fps.leftLogicalHeight);
        c.showFpsGraph = false;
        HudLayout memory = HudLayout.calculate(c, 800, 600);
        assertEquals(fps.fpsGraphTop, memory.memoryGraphTop);
        assertEquals(fps.leftLogicalHeight, memory.leftLogicalHeight);
    }

    @Test public void graphHeadersReplaceOnlyTheirOwnSummaryRows() {
        FrameConfig c = new FrameConfig();
        HudLayout original = HudLayout.calculate(c, 800, 600);
        assertTrue(original.fpsRow >= 0);
        assertTrue(original.memoryRow >= 0);
        c.showFpsGraph = true;
        HudLayout fps = HudLayout.calculate(c, 800, 600);
        assertEquals(-1, fps.fpsRow);
        assertTrue(fps.memoryRow >= 0);
        assertEquals(original.summaryRows - 1, fps.summaryRows);
        c.showMemoryGraph = true;
        HudLayout both = HudLayout.calculate(c, 800, 600);
        assertEquals(-1, both.fpsRow);
        assertEquals(-1, both.memoryRow);
        assertTrue(both.directMemoryRow >= 0);
        assertEquals(original.summaryRows - 2, both.summaryRows);
        // Graph headers remain available when standalone text flags are off.
        c.showFps = c.showMemory = false;
        HudLayout headers = HudLayout.calculate(c, 800, 600);
        assertEquals(both.fpsGraphTop, headers.fpsGraphTop);
        assertEquals(both.memoryGraphTop, headers.memoryGraphTop);
        c.showFpsGraph = c.showMemoryGraph = false;
        c.showFps = c.showMemory = true;
        HudLayout restored = HudLayout.calculate(c, 800, 600);
        assertEquals(original.fpsRow, restored.fpsRow);
        assertEquals(original.memoryRow, restored.memoryRow);
        assertEquals(original.summaryRows, restored.summaryRows);
    }

    @Test public void frameGraphHeaderReplacesStandaloneValueJustLikeOtherGraphs() {
        FrameConfig c = new FrameConfig();
        c.showGraph = false;
        HudLayout standalone = HudLayout.calculate(c, 800, 600);
        assertTrue(standalone.frameRow >= 0);
        c.showGraph = true;
        HudLayout grouped = HudLayout.calculate(c, 800, 600);
        assertEquals(-1, grouped.frameRow);
        assertEquals(standalone.summaryRows - 1, grouped.summaryRows);
        assertTrue(grouped.graphTop >= 0);
        c.showFrameTime = false;
        HudLayout graphOnly = HudLayout.calculate(c, 800, 600);
        assertEquals(grouped.graphTop, graphOnly.graphTop);
        assertEquals(grouped.leftLogicalHeight, graphOnly.leftLogicalHeight);
    }

    @Test public void presetsAndResetHandleAllGraphs() {
        FrameConfig c = new FrameConfig();
        assertFalse(c.showFpsGraph || c.showMemoryGraph);
        c.applyPreset("full");
        assertTrue(c.showGraph && c.showFpsGraph && c.showMemoryGraph);
        HudLayout all = HudLayout.calculate(c, 320, 200);
        assertTrue(all.left.height <= 200);
        assertTrue(all.memoryGraphTop > all.fpsGraphTop);
        c.applyPreset("minimal");
        assertFalse(c.hasGraphs());
        c.applyPreset("graph");
        assertTrue(c.showGraph);
        assertFalse(c.showFpsGraph || c.showMemoryGraph);
        c.applyPreset("full");
        c.resetHudDefaults();
        assertTrue(c.showGraph);
        assertFalse(c.showFpsGraph || c.showMemoryGraph);
    }
}
