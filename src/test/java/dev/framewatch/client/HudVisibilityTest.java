package dev.framewatch.client;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import org.junit.Test;

import static org.junit.Assert.*;

public class HudVisibilityTest {
    @Test public void gameplayWithoutAnOpenScreenShowsHud() {
        assertTrue(HudRenderer.shouldRenderOnScreen(null));
    }

    @Test public void containersAndModdedContainerScreensHideHud() {
        assertFalse(HudRenderer.shouldRenderOnScreen(new ContainerScreen()));
        assertFalse(HudRenderer.shouldRenderOnScreen(new ModdedContainerScreen()));
    }

    @Test public void chatPauseSettingsAndOtherMenusHideHud() {
        for (GuiScreen screen : new GuiScreen[] {
                new GuiScreen(), new GuiChat(), new GuiIngameMenu(), new HudSettingsScreen(null) }) {
            assertFalse(screen.getClass().getName(), HudRenderer.shouldRenderOnScreen(screen));
        }
    }

    @Test public void positionEditorKeepsRealPanelsVisibleRegardlessOfParent() {
        assertTrue(HudRenderer.shouldRenderOnScreen(new HudPositionScreen(null)));
        assertTrue(HudRenderer.shouldRenderOnScreen(new HudPositionScreen(new HudSettingsScreen(null))));
        assertTrue(HudRenderer.shouldRenderOnScreen(new HudPositionScreen(new ContainerScreen())));
        assertTrue(HudRenderer.shouldRenderOnScreen(null));
    }

    private static class ContainerScreen extends GuiContainer {
        ContainerScreen() { super(null); }
        @Override protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {}
    }

    private static final class ModdedContainerScreen extends ContainerScreen {}
}
