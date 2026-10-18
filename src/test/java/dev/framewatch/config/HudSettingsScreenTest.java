package dev.framewatch.config;

import dev.framewatch.FrameConfig;
import dev.framewatch.FrameWatch;
import dev.framewatch.client.HudGuiFactory;
import dev.framewatch.client.HudPositionScreen;
import dev.framewatch.client.HudSettingsScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.junit.Test;
import static org.junit.Assert.*;

public class HudSettingsScreenTest {
    @Test public void everyTabAndPageFitsSmallWindowsWithoutButtonOverlaps() throws Exception {
        FrameConfig previous = FrameWatch.config;
        try {
            FrameWatch.config = new FrameConfig();
            Field buttonsField = GuiScreen.class.getDeclaredField("buttonList");
            buttonsField.setAccessible(true);
            Field tabField = HudSettingsScreen.class.getDeclaredField("tab");
            tabField.setAccessible(true);
            Field pageField = HudSettingsScreen.class.getDeclaredField("page");
            pageField.setAccessible(true);
            Field pagesField = HudSettingsScreen.class.getDeclaredField("pages");
            pagesField.setAccessible(true);
            for (String language : new String[] {"auto", "en_US", "uk_UA"}) {
                FrameWatch.config.language = language;
                for (int width : new int[] {160, 240, 320, 420, 600, 960}) {
                    for (int height : new int[] {80, 100, 120, 160, 180, 200, 240, 360}) {
                        HudSettingsScreen screen = new HudSettingsScreen(null);
                        screen.width = width;
                        screen.height = height;
                        for (int tab = 0; tab < 4; tab++) {
                            tabField.setInt(screen, tab);
                            pageField.setInt(screen, 0);
                            screen.initGui();
                            int pages = pagesField.getInt(screen);
                            for (int page = 0; page < pages; page++) {
                                pageField.setInt(screen, page);
                                screen.initGui();
                                List<?> buttons = (List<?>) buttonsField.get(screen);
                                assertFits(buttons, width, height, language + " tab=" + tab + ", page=" + page);
                            }
                        }
                    }
                }
            }
            assertEquals(0, FrameWatch.config.revision());
        } finally {
            FrameWatch.config = previous;
        }
    }

    @Test public void screensDoNotPauseAndForgeFactoryExposesParentConstructor() throws Exception {
        assertFalse(new HudSettingsScreen(null).doesGuiPauseGame());
        assertFalse(new HudPositionScreen(null).doesGuiPauseGame());
        HudGuiFactory factory = new HudGuiFactory();
        assertEquals(HudSettingsScreen.class, factory.mainConfigGuiClass());
        assertNotNull(factory.mainConfigGuiClass().getConstructor(GuiScreen.class));
        assertTrue(factory.runtimeGuiCategories().isEmpty());
        assertNull(factory.getHandlerFor(null));
    }

    @Test public void languageCycleRebuildsLabelsImmediatelyWithoutChangingGeometry() throws Exception {
        FrameConfig previous = FrameWatch.config;
        try {
            FrameWatch.config = new FrameConfig();
            FrameWatch.config.language = "auto";
            HudSettingsScreen screen = new HudSettingsScreen(null);
            screen.width = 600;
            screen.height = 360;
            screen.initGui();
            List<?> original = buttons(screen);
            GuiButton language = buttonForTip(screen, "language.tip");
            for (String preference : new String[] {"en_US", "uk_UA", "auto"}) {
                long revision = FrameWatch.config.revision();
                click(screen, language);
                assertEquals(preference, FrameWatch.config.language);
                assertEquals(revision + 1, FrameWatch.config.revision());
                List<?> rebuilt = buttons(screen);

                assertEquals(original.size(), rebuilt.size());
                for (int i = 0; i < original.size(); i++) {
                    GuiButton a = (GuiButton) original.get(i), b = (GuiButton) rebuilt.get(i);
                    assertNotSame(a, b);
                    assertEquals(a.xPosition, b.xPosition);
                    assertEquals(a.yPosition, b.yPosition);
                    assertEquals(a.width, b.width);
                    assertEquals(a.height, b.height);
                }
                assertFits(rebuilt, screen.width, screen.height, preference);
                language = buttonForTip(screen, "language.tip");
                if ("uk_UA".equals(preference)) {
                    assertEquals("Мова моду: Українська", label(language));
                    assertEquals("Готово", label(buttonForTip(screen, "done.tip")));
                    assertTrue(label(buttonForTip(screen, "scale.tip")).startsWith("Масштаб HUD: "));
                } else if ("en_US".equals(preference)) {
                    assertEquals("Mod language: English", label(language));
                    assertEquals("Done", label(buttonForTip(screen, "done.tip")));
                }
            }
        } finally {
            FrameWatch.config = previous;
        }
    }

    @Test public void directBuffersToggleIsIndependentOfHeapAndProfiler() throws Exception {
        FrameConfig previous = FrameWatch.config;
        try {
            FrameWatch.config = new FrameConfig();
            FrameWatch.config.language = "en_US";
            HudSettingsScreen screen = new HudSettingsScreen(null);
            screen.width = 960;
            screen.height = 360;
            Field tab = HudSettingsScreen.class.getDeclaredField("tab");
            tab.setAccessible(true);
            tab.setInt(screen, 1);
            screen.initGui();
            boolean heap = FrameWatch.config.showMemory;
            boolean direct = FrameWatch.config.showDirectMemory;
            boolean profiler = FrameWatch.config.showProfiler;
            long revision = FrameWatch.config.revision();
            GuiButton button = buttonForTip(screen, "showDirectMemory.tip");
            assertTrue(label(button).startsWith("Direct buffers (not VRAM): "));
            click(screen, button);
            assertEquals(!direct, FrameWatch.config.showDirectMemory);
            assertEquals(heap, FrameWatch.config.showMemory);
            assertEquals(profiler, FrameWatch.config.showProfiler);
            assertEquals(revision + 1, FrameWatch.config.revision());
            click(screen, buttonForTip(screen, "showMemory.tip"));
            assertEquals(!heap, FrameWatch.config.showMemory);
            assertEquals(!direct, FrameWatch.config.showDirectMemory);
            assertTrue(label(buttonForTip(screen, "showProfiler.tip")).startsWith("Right CPU phase panel: "));
        } finally {
            FrameWatch.config = previous;
        }
    }

    private static List<?> buttons(HudSettingsScreen screen) throws Exception {
        Field field = GuiScreen.class.getDeclaredField("buttonList");
        field.setAccessible(true);
        return new java.util.ArrayList<>((List<?>) field.get(screen));
    }

    private static GuiButton buttonForTip(HudSettingsScreen screen, String tip) throws Exception {
        Field field = HudSettingsScreen.class.getDeclaredField("tips");
        field.setAccessible(true);
        Map<?, ?> tips = (Map<?, ?>) field.get(screen);
        for (Object entry : buttons(screen)) {
            GuiButton button = (GuiButton) entry;
            if (tip.equals(tips.get(button.id))) return button;
        }
        throw new AssertionError("Missing control: " + tip);
    }

    private static void click(HudSettingsScreen screen, GuiButton button) throws Exception {
        Method action = HudSettingsScreen.class.getDeclaredMethod("actionPerformed", GuiButton.class);
        action.setAccessible(true);
        action.invoke(screen, button);
    }

    private static String label(GuiButton button) throws Exception {
        Field field = button.getClass().getDeclaredField("label");
        field.setAccessible(true);
        return (String) ((Supplier<?>) field.get(button)).get();
    }

    private static void assertFits(List<?> buttons, int width, int height, String page) {
        String context = width + "x" + height + " " + page;
        for (int i = 0; i < buttons.size(); i++) {
            GuiButton a = (GuiButton) buttons.get(i);
            assertTrue(context + " left", a.xPosition >= 0);
            assertTrue(context + " top", a.yPosition >= 0);
            assertTrue(context + " right", a.xPosition + a.width <= width);
            assertTrue(context + " bottom", a.yPosition + a.height <= height);
            assertTrue(context + " size", a.width > 0 && a.height > 0);
            for (int j = i + 1; j < buttons.size(); j++) {
                GuiButton b = (GuiButton) buttons.get(j);
                boolean overlap = a.xPosition < b.xPosition + b.width && b.xPosition < a.xPosition + a.width
                        && a.yPosition < b.yPosition + b.height && b.yPosition < a.yPosition + a.height;
                assertFalse(context + " overlap " + a.id + "/" + b.id, overlap);
            }
        }
    }
}
