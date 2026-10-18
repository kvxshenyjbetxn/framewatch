package dev.framewatch.client;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/** A GUI-pixel slider: only an actual value change invokes the live config callback. */
public final class GuiValueSlider extends GuiButton {
    private final double minimum, maximum, step;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private final Supplier<String> label;
    private boolean dragging;

    public GuiValueSlider(int id, int x, int y, int width, double minimum, double maximum, double step,
            DoubleSupplier getter, DoubleConsumer setter, Supplier<String> label) {
        super(id, x, y, width, 20, "");
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        this.getter = getter;
        this.setter = setter;
        this.label = label;
    }

    @Override public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) return;
        if (dragging) update(mouseX);
        field_146123_n = mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width && mouseY < yPosition + height;
        drawRect(xPosition, yPosition, xPosition + width, yPosition + height, 0xF021272D);
        drawRect(xPosition + 2, yPosition + height - 5, xPosition + width - 2, yPosition + height - 2, 0xFF111519);
        double fraction = Math.max(0, Math.min(1, (getter.getAsDouble() - minimum) / (maximum - minimum)));
        int knob = xPosition + 3 + (int) Math.round(fraction * Math.max(1, width - 6));
        drawRect(xPosition + 2, yPosition + height - 5, knob, yPosition + height - 2, 0xFF9CD66C);
        drawRect(knob - 2, yPosition + height - 7, knob + 2, yPosition + height - 1, 0xFFE5D877);
        displayString = label.get();
        String text = mc.fontRenderer.trimStringToWidth(displayString, Math.max(1, width - 10));
        mc.fontRenderer.drawString(text, xPosition + (width - mc.fontRenderer.getStringWidth(text)) / 2,
                yPosition + 3, enabled ? field_146123_n ? 0xE5D877 : 0xE6E9EB : 0x777777);
    }

    @Override public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!super.mousePressed(mc, mouseX, mouseY)) return false;
        dragging = true;
        update(mouseX);
        return true;
    }

    private void update(int mouseX) {
        double fraction = Math.max(0, Math.min(1, (mouseX - xPosition - 3) / (double) Math.max(1, width - 6)));
        double value = minimum + Math.round(fraction * (maximum - minimum) / step) * step;
        value = Math.max(minimum, Math.min(maximum, value));
        if (Math.abs(value - getter.getAsDouble()) > 0.000001) setter.accept(value);
    }

    @Override public void mouseReleased(int mouseX, int mouseY) { dragging = false; }
}
