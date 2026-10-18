package dev.framewatch.client;

import dev.framewatch.FrameWatch;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

/** Draws editor chrome only; the normal post-present renderer supplies the real HUD. */
public final class HudPositionScreen extends GuiScreen {
    private final GuiScreen parent;
    private boolean dragging, profiler;
    private int grabX, grabY;

    public HudPositionScreen(GuiScreen parent) { this.parent = parent; }

    @Override public void initGui() {
        HudTranslations.initialize();
        dragging = false;
        buttonList.clear();
        int buttonWidth = Math.min(150, Math.max(20, width - 16));
        buttonList.add(new HudSettingsScreen.PanelButton(0, (width - buttonWidth) / 2,
                Math.max(0, height - 26), buttonWidth, () -> HudTranslations.text("framewatch.done"), 0x9CD66C));
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // No background/telemetry/GL state changes: keep the world and real HUD visible.
        HudLayout layout = HudLayout.calculate(FrameWatch.config, width, height);
        outline(layout.left, !profiler && dragging ? 0xFFE5D877 : 0xFF9CD66C);
        outline(layout.right, profiler && dragging ? 0xFFE5D877 : 0xFF9CD66C);
        String key = layout.left.visible() || layout.right.visible() ? "framewatch.position.help" : "framewatch.position.hidden";
        java.util.List<String> lines = fontRendererObj.listFormattedStringToWidth(HudTranslations.text(key), Math.max(1, width - 20));
        int top = Math.max(0, height - 32 - lines.size() * 10);
        drawRect(4, top - 3, Math.max(4, width - 4), height - 28, 0xC0111519);
        for (String line : lines) {
            drawCenteredString(fontRendererObj, line, width / 2, top, 0xE5D877);
            top += 10;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void outline(HudLayout.Bounds bounds, int color) {
        if (!bounds.visible()) return;
        drawRect(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + 1, color);
        drawRect(bounds.x, bounds.y + bounds.height - 1, bounds.x + bounds.width, bounds.y + bounds.height, color);
        drawRect(bounds.x, bounds.y, bounds.x + 1, bounds.y + bounds.height, color);
        drawRect(bounds.x + bounds.width - 1, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height, color);
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        for (Object entry : buttonList) {
            GuiButton button = (GuiButton) entry;
            if (button.mousePressed(mc, mouseX, mouseY)) {
                super.mouseClicked(mouseX, mouseY, mouseButton);
                return;
            }
        }
        if (mouseButton != 0) return;
        HudLayout layout = HudLayout.calculate(FrameWatch.config, width, height);
        // Match the renderer's drawing order when panels overlap: right panel is on top.
        if (layout.right.contains(mouseX, mouseY)) profiler = true;
        else if (layout.left.contains(mouseX, mouseY)) profiler = false;
        else return;
        HudLayout.Bounds bounds = profiler ? layout.right : layout.left;
        grabX = mouseX - bounds.x;
        grabY = mouseY - bounds.y;
        dragging = true;
    }

    @Override protected void mouseClickMove(int mouseX, int mouseY, int button, long heldMillis) {
        if (dragging && button == 0) HudLayout.move(FrameWatch.config, profiler, mouseX - grabX, mouseY - grabY, width, height);
    }

    @Override protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (button == 0) {
            if (dragging) HudLayout.move(FrameWatch.config, profiler, mouseX - grabX, mouseY - grabY, width, height);
            dragging = false;
        }
        super.mouseMovedOrUp(mouseX, mouseY, button);
    }

    @Override protected void actionPerformed(GuiButton button) { if (button.id == 0) done(); }
    @Override protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) done();
        else super.keyTyped(typedChar, keyCode);
    }

    private void done() { mc.displayGuiScreen(parent); }

    @Override public void onGuiClosed() { SettingsPersistence.save(); }
    @Override public boolean doesGuiPauseGame() { return false; }
}
