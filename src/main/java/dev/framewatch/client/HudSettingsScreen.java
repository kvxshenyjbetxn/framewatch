package dev.framewatch.client;

import dev.framewatch.FrameConfig;
import dev.framewatch.FrameWatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class HudSettingsScreen extends GuiScreen {
    private static final String[] TABS = {"appearance", "blocks", "graph", "measurement"};
    private static final String[] ANCHORS = {"TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"};
        private static final String[] LANGUAGES = {"auto", "en_US", "uk_UA"};
    private final GuiScreen parent;
    private final Map<Integer, Runnable> actions = new HashMap<>();
    private final Map<Integer, String> tips = new HashMap<>();
    private int tab, page, pages = 1, nextId, panelX, panelWidth, contentTop, footerTop;

    public HudSettingsScreen(GuiScreen parent) { this.parent = parent; }

    private FrameConfig cfg() { return FrameWatch.config; }
    private static String tr(String key, Object... args) { return HudTranslations.text("framewatch." + key, args); }

    @Override public void initGui() {
        HudTranslations.initialize();
        buttonList.clear();
        actions.clear();
        tips.clear();
        nextId = 0;
        panelWidth = Math.min(660, Math.max(40, width - 16));
        panelX = (width - panelWidth) / 2;
        boolean compact = height < 200;
        boolean shortWindow = height < 180;
        int tabTop = height < 110 ? 4 : 26;
        int tabColumns = width >= 420 ? 4 : 2;
        contentTop = 30 + (4 / tabColumns) * 24;
        if (compact) {
            addButton(panelX, tabTop, panelWidth, () -> tr("tab." + TABS[tab]) + " >", 0xE5D877,
                    () -> { tab = (tab + 1) % TABS.length; page = 0; initGui(); }, "tab.tip");
            contentTop = tabTop + 24;
        } else {
            for (int i = 0; i < TABS.length; i++) {
                final int index = i;
                int w = (panelWidth - (tabColumns - 1) * 4) / tabColumns;
                addButton(panelX + i % tabColumns * (w + 4), 30 + i / tabColumns * 24, w,
                        () -> tr("tab." + TABS[index]), i == tab ? 0x9CD66C : 0xE5D877,
                        () -> { tab = index; page = 0; initGui(); }, "tab." + TABS[index] + ".tip");
            }
        }
        footerTop = height - (shortWindow ? 24 : 72);
        List<Option> options = options();
        if (shortWindow) {
            for (String preset : new String[] {"minimal", "graph", "full"}) {
                command(options, "preset." + preset, () -> { cfg().applyPreset(preset); initGui(); });
            }
            command(options, "position", () -> mc.displayGuiScreen(new HudPositionScreen(this)));
            command(options, "reset", () -> { cfg().resetHudDefaults(); initGui(); });
        }
        int columns = width >= 600 ? 2 : 1;
        int rows = Math.max(1, (footerTop - contentTop - 4) / 24);
        int perPage = rows * columns;
        pages = Math.max(1, (options.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(pages - 1, page));
        int controlWidth = (panelWidth - (columns - 1) * 6) / columns;
        for (int i = 0; i < perPage && page * perPage + i < options.size(); i++) {
            Option option = options.get(page * perPage + i);
            int x = panelX + i % columns * (controlWidth + 6);
            int y = contentTop + i / columns * 24;
            if (option.getter == null) {
                addButton(x, y, controlWidth, option.label, 0xE5D877, option.action, option.key + ".tip");
            } else {
                int id = nextId++;
                buttonList.add(new GuiValueSlider(id, x, y, controlWidth, option.min, option.max, option.step,
                        option.getter, option.setter, option.label));
                tips.put(id, option.key + ".tip");
            }
        }
        int third = (panelWidth - 8) / 3;
        if (!shortWindow) {
            String[] presets = {"minimal", "graph", "full"};
            for (int i = 0; i < presets.length; i++) {
                final String preset = presets[i];
                addButton(panelX + i * (third + 4), footerTop, third, () -> tr("preset." + preset),
                        i == 0 ? 0x9CD66C : i == 1 ? 0xE5D877 : 0xE68B7C,
                        () -> { cfg().applyPreset(preset); initGui(); }, "preset." + preset + ".tip");
            }
            int half = (panelWidth - 4) / 2;
            addButton(panelX, footerTop + 24, half, () -> tr("position"), 0xE5D877,
                    () -> mc.displayGuiScreen(new HudPositionScreen(this)), "position.tip");
            addButton(panelX + half + 4, footerTop + 24, half, () -> tr("reset"), 0xE68B7C,
                    () -> { cfg().resetHudDefaults(); initGui(); }, "reset.tip");
        }
        int navigationY = height - 24;
        addButton(panelX, navigationY, third, () -> "< " + (page + 1) + "/" + pages, 0xE5D877,
                () -> changePage(-1), "page.tip").enabled = page > 0;
        addButton(panelX + third + 4, navigationY, third, () -> tr("done"), 0x9CD66C,
                this::done, "done.tip");
        addButton(panelX + 2 * (third + 4), navigationY, third, () -> (page + 1) + "/" + pages + " >", 0xE5D877,
                () -> changePage(1), "page.tip").enabled = page < pages - 1;
    }

    private List<Option> options() {
        FrameConfig c = cfg();
        List<Option> list = new ArrayList<>();
        switch (tab) {
            case 0:
                language(list);
                slider(list, "scale", 0.5, 2.5, 0.05, () -> c.hudScale, v -> c.hudScale = v, "", 2, false);
                slider(list, "opacity", 0, 1, 0.01, () -> c.panelOpacity, v -> c.panelOpacity = v, "", 2, false);
                toggle(list, "enabled", () -> c.hudEnabled, v -> c.hudEnabled = v);
                toggle(list, "hideWithF1", () -> c.hideWithF1, v -> c.hideWithF1 = v);
                toggle(list, "textShadow", () -> c.textShadow, v -> c.textShadow = v);
                anchor(list, "leftAnchor", () -> c.leftAnchor, v -> c.leftAnchor = v);
                anchor(list, "rightAnchor", () -> c.rightAnchor, v -> c.rightAnchor = v);
                slider(list, "textRefresh", 125, 1000, 25, () -> c.textRefreshMillis, v -> c.textRefreshMillis = (int) v, "ms", 0, false);
                break;
            case 1:
                toggle(list, "showTitles", () -> c.showTitles, v -> c.showTitles = v);
                toggle(list, "showFps", () -> c.showFps, v -> c.showFps = v);
                toggle(list, "showFrameTime", () -> c.showFrameTime, v -> c.showFrameTime = v);
                toggle(list, "showStatistics", () -> c.showStatistics, v -> c.showStatistics = v);
                toggle(list, "showMemory", () -> c.showMemory, v -> c.showMemory = v);
                toggle(list, "showDirectMemory", () -> c.showDirectMemory, v -> c.showDirectMemory = v);
                toggle(list, "showAllocation", () -> c.showAllocation, v -> c.showAllocation = v);
                toggle(list, "showGc", () -> c.showGc, v -> c.showGc = v);
                toggle(list, "showHardware", () -> c.showHardware, v -> c.showHardware = v);
                toggle(list, "showEnvironment", () -> c.showEnvironment, v -> c.showEnvironment = v);
                toggle(list, "showProfiler", () -> c.showProfiler, v -> { c.showProfiler = v; c.detailed = v; });
                toggle(list, "showFpsGraph", () -> c.showFpsGraph, v -> c.showFpsGraph = v);
                toggle(list, "showMemoryGraph", () -> c.showMemoryGraph, v -> c.showMemoryGraph = v);
                toggle(list, "showGraph", () -> c.showGraph, v -> c.showGraph = v);
                toggle(list, "showGraphEvents", () -> c.showGraphEvents, v -> c.showGraphEvents = v);
                break;
            case 2:
                toggle(list, "showFpsGraph", () -> c.showFpsGraph, v -> c.showFpsGraph = v);
                toggle(list, "showMemoryGraph", () -> c.showMemoryGraph, v -> c.showMemoryGraph = v);
                toggle(list, "showGraph", () -> c.showGraph, v -> c.showGraph = v);
                toggle(list, "showGraphEvents", () -> c.showGraphEvents, v -> c.showGraphEvents = v);
                slider(list, "graphWidth", 100, 400, 1, () -> c.graphWidth, v -> c.graphWidth = (int) v, "px", 0, false);
                slider(list, "graphHeight", 30, 140, 1, () -> c.graphHeight, v -> c.graphHeight = (int) v, "px", 0, false);
                slider(list, "graphWindow", 1, 30, 1, () -> c.graphWindowSeconds, v -> c.graphWindowSeconds = (int) v, "s", 0, false);
                // The first detent (29) means auto; all other values are real 30..250 ms.
                slider(list, "graphMax", 29, 250, 1, () -> c.graphMaxMs == 0 ? 29 : c.graphMaxMs,
                        v -> c.graphMaxMs = v < 30 ? 0 : v, "ms", 0, true);
                slider(list, "graphRefresh", 16, 200, 1, () -> c.graphRefreshMillis, v -> c.graphRefreshMillis = (int) v, "ms", 0, false);
                break;
            case 3:
                slider(list, "window", 1, 30, 1, () -> c.windowSeconds, v -> c.windowSeconds = (int) v, "s", 0, false);
                slider(list, "target", 0, 360, 1, () -> c.targetFps, v -> c.targetFps = (int) v, "fps", 0, true);
                slider(list, "warmup", 0, 5000, 50, () -> c.warmupMillis, v -> c.warmupMillis = (int) v, "ms", 0, false);
                slider(list, "longFrame", 10, 100, 1, () -> c.longFrameMillis, v -> c.longFrameMillis = v, "ms", 0, false);
                break;
            default: break;
        }
        return list;
    }

    private void command(List<Option> list, String key, Runnable action) {
        Option option = new Option(key);
        option.label = () -> tr(key);
        option.action = action;
        list.add(option);
    }

    private void toggle(List<Option> list, String key, BooleanSupplier getter, Consumer<Boolean> setter) {
        Option option = new Option(key);
        option.label = () -> tr("optionValue", tr(key), getter.getAsBoolean() ? "\u00a7a" + tr("on") : "\u00a7c" + tr("off"));
        option.action = () -> { setter.accept(!getter.getAsBoolean()); cfg().changed(); };
        list.add(option);
    }

    private void language(List<Option> list) {
        Option option = new Option("language");
        option.label = () -> tr("optionValue", tr("language"), tr("language." + languagePreference()));
        option.action = () -> {
            String preference = languagePreference();
            int index = 0;
            for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i].equals(preference)) index = i;
            cfg().language = LANGUAGES[(index + 1) % LANGUAGES.length];
            cfg().changed();
            initGui();
        };
        list.add(option);
    }

    private String languagePreference() {
        String preference = cfg().language;
        return preference == null || "auto".equalsIgnoreCase(preference)
                ? "auto" : HudTranslations.effectiveLanguage(preference, null);
    }

    private void anchor(List<Option> list, String key, Supplier<String> getter, Consumer<String> setter) {
        Option option = new Option(key);
        option.label = () -> tr("optionValue", tr(key), tr("anchor." + getter.get()));
        option.action = () -> {
            int index = 0;
            for (int i = 0; i < ANCHORS.length; i++) if (ANCHORS[i].equals(getter.get())) index = i;
            setter.accept(ANCHORS[(index + 1) % ANCHORS.length]);
            cfg().changed();
        };
        list.add(option);
    }

    private void slider(List<Option> list, String key, double min, double max, double step,
            DoubleSupplier getter, DoubleConsumer setter, String unit, int decimals, boolean auto) {
        Option option = new Option(key);
        option.min = min;
        option.max = max;
        option.step = step;
        option.getter = getter;
        option.setter = v -> { setter.accept(v); cfg().changed(); };
        option.label = () -> {
            double value = getter.getAsDouble();
            String number = String.format(Locale.ROOT, "%1$." + decimals + "f", value);
            String text = auto && value == min ? tr("auto")
                    : unit.isEmpty() ? number : tr("valueWithUnit", number, tr("unit." + unit));
            return tr("optionValue", tr(key), text);
        };
        list.add(option);
    }

    private PanelButton addButton(int x, int y, int w, Supplier<String> label, int color, Runnable action, String tip) {
        int id = nextId++;
        PanelButton button = new PanelButton(id, x, y, w, label, color);
        buttonList.add(button);
        actions.put(id, action);
        tips.put(id, tip);
        return button;
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xEA101419);
        drawRect(panelX - 4, 4, panelX + panelWidth + 4, height - 2, 0xEE171D23);
        if (height >= 110) drawCenteredString(fontRendererObj, tr("settings.title"), width / 2, 10, 0x9CD66C);
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (Object entry : buttonList) {
            GuiButton button = (GuiButton) entry;
            if (button.visible && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                    && mouseY >= button.yPosition && mouseY < button.yPosition + button.height) {
                String tip = tips.get(button.id);
                if (tip != null) {
                    List<String> lines = fontRendererObj.listFormattedStringToWidth(tr(tip), Math.max(40, Math.min(280, width - 32)));
                    drawHoveringText(lines, Math.max(12, Math.min(mouseX, width - 20)), mouseY, fontRendererObj);
                }
                break;
            }
        }
    }

    @Override protected void actionPerformed(GuiButton button) {
        Runnable action = actions.get(button.id);
        if (action != null) action.run();
    }

    private void changePage(int delta) {
        int next = Math.max(0, Math.min(pages - 1, page + delta));
        if (next != page) { page = next; initGui(); }
    }

    @Override public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) changePage(wheel > 0 ? -1 : 1);
    }

    @Override protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) done();
        else if (keyCode == Keyboard.KEY_NEXT) changePage(1);
        else if (keyCode == Keyboard.KEY_PRIOR) changePage(-1);
        else super.keyTyped(typedChar, keyCode);
    }

    private void done() { mc.displayGuiScreen(parent); }
    @Override public void onGuiClosed() { SettingsPersistence.save(); }
    @Override public boolean doesGuiPauseGame() { return false; }

    private static final class Option {
        final String key;
        Supplier<String> label;
        Runnable action;
        DoubleSupplier getter;
        DoubleConsumer setter;
        double min, max, step;
        Option(String key) { this.key = key; }
    }

    static final class PanelButton extends GuiButton {
        private final Supplier<String> label;
        private final int color;
        PanelButton(int id, int x, int y, int width, Supplier<String> label, int color) {
            super(id, x, y, width, 20, "");
            this.label = label;
            this.color = color;
        }
        @Override public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            field_146123_n = mouseX >= xPosition && mouseY >= yPosition
                    && mouseX < xPosition + width && mouseY < yPosition + height;
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height,
                    enabled && field_146123_n ? 0xFF343D45 : 0xF021272D);
            drawRect(xPosition, yPosition, xPosition + 2, yPosition + height, 0xFF000000 | (enabled ? color : 0x555555));
            displayString = label.get();
            String text = mc.fontRenderer.trimStringToWidth(displayString, Math.max(1, width - 10));
            mc.fontRenderer.drawString(text, xPosition + (width - mc.fontRenderer.getStringWidth(text)) / 2,
                    yPosition + 6, enabled ? color : 0x777777);
        }
    }
}
