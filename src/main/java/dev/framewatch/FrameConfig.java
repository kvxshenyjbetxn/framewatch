package dev.framewatch;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;

public final class FrameConfig {
    public boolean hudEnabled = true;
    public boolean detailed = false;
    public boolean hideWithF1 = true;
    public int x = 6, y = 6, graphWidth = 240, graphHeight = 45;
    public int historyCapacity = 20000, windowSeconds = 5, warmupMillis = 1500, targetFps = 0;
    public double longFrameMillis = 20;

    public boolean showTitles = true, showFps = true, showFrameTime = true;
    public boolean showStatistics = false, showMemory = true, showDirectMemory = true, showAllocation = false, showGc = false;
    public String language = "auto";
    public boolean showHardware = false, showEnvironment = false, showProfiler = true;
    public boolean showGraph = true, showGraphEvents = true, textShadow = false;
    public double hudScale = 0.8, panelOpacity = 0.65, graphMaxMs = 100;
    public int graphWindowSeconds = 10, textRefreshMillis = 250, graphRefreshMillis = 33;
    public int profilerX = 6, profilerY = 6;
    public String leftAnchor = "TOP_LEFT", rightAnchor = "TOP_RIGHT";

    private Configuration properties;
    private File file;
    private long revision;

    public FrameConfig() {}

    public static FrameConfig load(File file) {
        if (file == null) throw new IllegalArgumentException("FrameWatch configuration file must not be null");
        FrameConfig result = new FrameConfig();
        result.file = file.getAbsoluteFile();
        try {
            Path path = result.file.toPath();
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                if (!Files.isRegularFile(path)) throw new IOException("Not a regular file");
                // Check readability before Forge's parser, which otherwise swallows IOException.
                Files.readAllBytes(path);
            } else {
                Files.createFile(path);
            }
            CheckedConfiguration c = new CheckedConfiguration(result.file);
            c.read();
            result.properties = c;
            result.readProperties(c);
            result.clamp();
            result.save();
            return result;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load FrameWatch settings from " + result.file
                    + ". Check file permissions and Forge configuration syntax; the original file was not replaced.", e);
        }
    }

    private void readProperties(Configuration c) {
        boolean legacyProfiler = c.hasKey("hud", "detailed") && !c.hasKey("hud", "showProfiler");
        hudEnabled = c.get("hud", "enabled", hudEnabled).getBoolean(hudEnabled);
        detailed = c.get("hud", "detailed", detailed).getBoolean(detailed);
        hideWithF1 = c.get("hud", "hideWithF1", hideWithF1).getBoolean(hideWithF1);
        x = c.get("hud", "x", x).getInt(x);
        y = c.get("hud", "y", y).getInt(y);
        graphWidth = c.get("hud", "graphWidth", graphWidth).getInt(graphWidth);
        graphHeight = c.get("hud", "graphHeight", graphHeight).getInt(graphHeight);
        showTitles = c.get("hud", "showTitles", showTitles).getBoolean(showTitles);
        showFps = c.get("hud", "showFps", showFps).getBoolean(showFps);
        showFrameTime = c.get("hud", "showFrameTime", showFrameTime).getBoolean(showFrameTime);
        showStatistics = c.get("hud", "showStatistics", showStatistics).getBoolean(showStatistics);
        showMemory = c.get("hud", "showMemory", showMemory).getBoolean(showMemory);
        // Before 0.3 heap and direct buffers shared one visibility flag.
        showDirectMemory = c.get("hud", "showDirectMemory", showMemory).getBoolean(showMemory);
        language = c.get("hud", "language", language).getString();
        showAllocation = c.get("hud", "showAllocation", showAllocation).getBoolean(showAllocation);
        showGc = c.get("hud", "showGc", showGc).getBoolean(showGc);
        showHardware = c.get("hud", "showHardware", showHardware).getBoolean(showHardware);
        showEnvironment = c.get("hud", "showEnvironment", showEnvironment).getBoolean(showEnvironment);
        showProfiler = c.get("hud", "showProfiler", legacyProfiler ? detailed : showProfiler).getBoolean(showProfiler);
        showGraph = c.get("hud", "showGraph", showGraph).getBoolean(showGraph);
        showGraphEvents = c.get("hud", "showGraphEvents", showGraphEvents).getBoolean(showGraphEvents);
        textShadow = c.get("hud", "textShadow", textShadow).getBoolean(textShadow);
        hudScale = c.get("hud", "hudScale", hudScale).getDouble(hudScale);
        panelOpacity = c.get("hud", "panelOpacity", panelOpacity).getDouble(panelOpacity);
        graphMaxMs = c.get("hud", "graphMaxMs", graphMaxMs).getDouble(graphMaxMs);
        graphWindowSeconds = c.get("hud", "graphWindowSeconds", graphWindowSeconds).getInt(graphWindowSeconds);
        textRefreshMillis = c.get("hud", "textRefreshMillis", textRefreshMillis).getInt(textRefreshMillis);
        graphRefreshMillis = c.get("hud", "graphRefreshMillis", graphRefreshMillis).getInt(graphRefreshMillis);
        profilerX = c.get("hud", "profilerX", profilerX).getInt(profilerX);
        profilerY = c.get("hud", "profilerY", profilerY).getInt(profilerY);
        leftAnchor = c.get("hud", "leftAnchor", leftAnchor).getString();
        rightAnchor = c.get("hud", "rightAnchor", rightAnchor).getString();
        targetFps = c.get("measurement", "targetFps", targetFps).getInt(targetFps);
        windowSeconds = c.get("measurement", "windowSeconds", windowSeconds).getInt(windowSeconds);
        historyCapacity = c.get("measurement", "historyCapacity", historyCapacity).getInt(historyCapacity);
        warmupMillis = c.get("measurement", "warmupMillis", warmupMillis).getInt(warmupMillis);
        longFrameMillis = c.get("measurement", "longFrameMillis", longFrameMillis).getDouble(longFrameMillis);
    }

    public void save() {
        clamp();
        // A detached default config is useful to layout/tests and has no persistence destination.
        if (file == null) return;
        writeProperties();
        Path temporary = null;
        try {
            Path destination = file.toPath();
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), "framewatch-", ".cfg.tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write("# Configuration file");
                writer.newLine();
                writer.newLine();
                for (String name : properties.getCategoryNames()) {
                    ConfigCategory category = properties.getCategory(name);
                    if (!category.isChild()) {
                        category.write(writer, 0);
                        writer.newLine();
                    }
                }
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot save FrameWatch settings to " + file
                    + ". Check directory permissions and free disk space; in-memory edits are still available.", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { if (FrameWatch.log != null) FrameWatch.log.warn("Cannot remove settings temporary file " + temporary, e); }
            }
        }
    }

    private void writeProperties() {
        properties.get("hud", "enabled", true).set(hudEnabled);
        properties.get("hud", "detailed", false).set(detailed);
        properties.get("hud", "hideWithF1", true).set(hideWithF1);
        properties.get("hud", "x", 6).set(x);
        properties.get("hud", "y", 6).set(y);
        properties.get("hud", "graphWidth", 240).set(graphWidth);
        properties.get("hud", "graphHeight", 45).set(graphHeight);
        properties.get("hud", "showTitles", true).set(showTitles);
        properties.get("hud", "showFps", true).set(showFps);
        properties.get("hud", "showFrameTime", true).set(showFrameTime);
        properties.get("hud", "showStatistics", false).set(showStatistics);
        properties.get("hud", "showMemory", true).set(showMemory);
        properties.get("hud", "showDirectMemory", true).set(showDirectMemory);
        properties.get("hud", "language", "auto").set(language);
        properties.get("hud", "showAllocation", false).set(showAllocation);
        properties.get("hud", "showGc", false).set(showGc);
        properties.get("hud", "showHardware", false).set(showHardware);
        properties.get("hud", "showEnvironment", false).set(showEnvironment);
        properties.get("hud", "showProfiler", true).set(showProfiler);
        properties.get("hud", "showGraph", true).set(showGraph);
        properties.get("hud", "showGraphEvents", true).set(showGraphEvents);
        properties.get("hud", "textShadow", false).set(textShadow);
        properties.get("hud", "hudScale", 0.8).set(hudScale);
        properties.get("hud", "panelOpacity", 0.65).set(panelOpacity);
        properties.get("hud", "graphMaxMs", 100.0).set(graphMaxMs);
        properties.get("hud", "graphWindowSeconds", 10).set(graphWindowSeconds);
        properties.get("hud", "textRefreshMillis", 250).set(textRefreshMillis);
        properties.get("hud", "graphRefreshMillis", 33).set(graphRefreshMillis);
        properties.get("hud", "profilerX", 6).set(profilerX);
        properties.get("hud", "profilerY", 6).set(profilerY);
        properties.get("hud", "leftAnchor", "TOP_LEFT").set(leftAnchor);
        properties.get("hud", "rightAnchor", "TOP_RIGHT").set(rightAnchor);
        properties.get("measurement", "targetFps", 0).set(targetFps);
        properties.get("measurement", "windowSeconds", 5).set(windowSeconds);
        properties.get("measurement", "historyCapacity", 20000).set(historyCapacity);
        properties.get("measurement", "warmupMillis", 1500).set(warmupMillis);
        properties.get("measurement", "longFrameMillis", 20.0).set(longFrameMillis);
    }

    public void changed() {
        clamp();
        revision++;
    }

    public long revision() { return revision; }

    public void applyPreset(String preset) {
        if (preset == null) throw new IllegalArgumentException("Preset must be minimal, graph or full");
        String name = preset.toLowerCase(Locale.ROOT);
        if (!name.equals("minimal") && !name.equals("graph") && !name.equals("full")) {
            throw new IllegalArgumentException("Unknown FrameWatch preset: " + preset);
        }
        boolean full = name.equals("full");
        showFps = showFrameTime = true;
        showTitles = showStatistics = showMemory = showDirectMemory = showAllocation = showGc = showEnvironment = showProfiler = full;
        showHardware = false;
        showGraph = !name.equals("minimal");
        showGraphEvents = full;
        detailed = showProfiler;
        changed();
    }

    public void resetHudDefaults() {
        hudEnabled = true;
        detailed = false;
        hideWithF1 = true;
        x = y = profilerX = profilerY = 6;
        graphWidth = 240;
        graphHeight = 45;
        showTitles = showFps = showFrameTime = showMemory = showDirectMemory = showProfiler = showGraph = showGraphEvents = true;
        showStatistics = showAllocation = showGc = showHardware = showEnvironment = textShadow = false;
        hudScale = 0.8;
        panelOpacity = 0.65;
        graphMaxMs = 100;
        graphWindowSeconds = 10;
        textRefreshMillis = 250;
        graphRefreshMillis = 33;
        leftAnchor = "TOP_LEFT";
        rightAnchor = "TOP_RIGHT";
        // Measurement/history values are deliberately not normalized or reset here.
        revision++;
    }

    private void clamp() {
        x = bound(x, 0, 4096);
        y = bound(y, 0, 4096);
        profilerX = bound(profilerX, 0, 4096);
        profilerY = bound(profilerY, 0, 4096);
        graphWidth = bound(graphWidth, 100, 400);
        graphHeight = bound(graphHeight, 30, 140);
        hudScale = bound(hudScale, 0.5, 2.5, 0.8);
        panelOpacity = bound(panelOpacity, 0, 1, 0.65);
        graphMaxMs = graphMaxMs == 0 ? 0 : bound(graphMaxMs, 30, 250, 100);
        graphWindowSeconds = bound(graphWindowSeconds, 1, 30);
        textRefreshMillis = bound(textRefreshMillis, 125, 1000);
        graphRefreshMillis = bound(graphRefreshMillis, 16, 200);
        // Keep native legacy measurement ranges; GUI sliders offer a narrower useful subset.
        targetFps = bound(targetFps, 0, 1000);
        windowSeconds = bound(windowSeconds, 1, 60);
        historyCapacity = bound(historyCapacity, 1000, 120000);
        warmupMillis = bound(warmupMillis, 0, 10000);
        longFrameMillis = bound(longFrameMillis, 1, 1000, 20);
        leftAnchor = anchor(leftAnchor, "TOP_LEFT");
        rightAnchor = anchor(rightAnchor, "TOP_RIGHT");
        language = normalizeLanguage(language);
    }

    private static String normalizeLanguage(String value) {
        if (value == null) return "auto";
        String normalized = value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        if (normalized.equals("uk") || normalized.equals("uk_ua")) return "uk_UA";
        if (normalized.equals("en") || normalized.equals("en_us")) return "en_US";
        return "auto";
    }

    private static int bound(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static double bound(double value, double min, double max, double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
    }

    private static String anchor(String value, String fallback) {
        if (value == null) return fallback;
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.equals("TOP_LEFT") || normalized.equals("TOP_RIGHT")
                || normalized.equals("BOTTOM_LEFT") || normalized.equals("BOTTOM_RIGHT") ? normalized : fallback;
    }

    private static final class CheckedConfiguration extends Configuration {
        CheckedConfiguration(File file) { super(file); }
        // Forge's constructor invokes load virtually and catches Throwable to rename bad configs.
        // Defer parsing until outside that constructor so syntax errors leave the file untouched.
        @Override public void load() {}
        void read() { super.load(); }
    }
}
