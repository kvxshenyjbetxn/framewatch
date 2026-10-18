package dev.framewatch.config;

import cpw.mods.fml.relauncher.FMLInjectionData;
import dev.framewatch.FrameConfig;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.minecraftforge.common.config.Configuration;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FrameConfigTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass public static void bootstrapForgeConfigPath() throws Exception {
        // Forge Configuration needs its launch-time home even in a headless unit test.
        Field home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        if (home.get(null) == null) home.set(null, new File("."));
    }

    @Test public void publicConstructorHasCompleteDefaults() {
        FrameConfig c = new FrameConfig();
        assertTrue(c.hudEnabled);
        assertFalse(c.detailed);
        assertTrue(c.hideWithF1);
        assertTrue(c.showTitles && c.showFps && c.showFrameTime && c.showMemory);
        assertTrue(c.showDirectMemory);
        assertEquals("auto", c.language);
        assertTrue(c.showProfiler && c.showGraph && c.showGraphEvents);
        assertFalse(c.showStatistics || c.showAllocation || c.showGc || c.showHardware || c.showEnvironment || c.textShadow);
        assertEquals(0.8, c.hudScale, 0);
        assertEquals(0.65, c.panelOpacity, 0);
        assertEquals(100, c.graphMaxMs, 0);
        assertEquals(10, c.graphWindowSeconds);
        assertEquals(250, c.textRefreshMillis);
        assertEquals(33, c.graphRefreshMillis);
        assertEquals(6, c.profilerX);
        assertEquals(6, c.profilerY);
        assertEquals("TOP_LEFT", c.leftAnchor);
        assertEquals("TOP_RIGHT", c.rightAnchor);
        assertEquals(20000, c.historyCapacity);
        assertEquals(5, c.windowSeconds);
        assertEquals(1500, c.warmupMillis);
        assertEquals(0, c.targetFps);
        assertEquals(20, c.longFrameMillis, 0);
        assertEquals(0, c.revision());
        c.save(); // Detached defaults have no file to overwrite.
    }

    @Test public void allPublicSettingsRoundTripIncludingLiveFlags() throws Exception {
        File file = new File(folder.getRoot(), "nested/framewatch.cfg");
        FrameConfig c = FrameConfig.load(file);
        for (Field field : FrameConfig.class.getFields()) {
            if (field.getType() == boolean.class) field.setBoolean(c, !field.getBoolean(c));
        }
        c.x = 12;
        c.y = 34;
        c.profilerX = 56;
        c.profilerY = 78;
        c.graphWidth = 333;
        c.graphHeight = 99;
        c.hudScale = 1.35;
        c.panelOpacity = 0.27;
        c.graphMaxMs = 187.5;
        c.graphWindowSeconds = 23;
        c.textRefreshMillis = 675;
        c.graphRefreshMillis = 71;
        c.leftAnchor = "BOTTOM_RIGHT";
        c.rightAnchor = "BOTTOM_LEFT";
        c.language = "uk_UA";
        c.historyCapacity = 54321;
        c.windowSeconds = 29;
        c.warmupMillis = 4321;
        c.targetFps = 144;
        c.longFrameMillis = 42.5;
        // Commands/keys may write public fields directly; save must not require changed().
        c.save();
        FrameConfig reloaded = FrameConfig.load(file);
        assertPublicSettingsEqual(c, reloaded);
        assertEquals("uk_UA", reloaded.language);
        assertEquals(0, reloaded.revision());
        c.graphMaxMs = 0;
        c.save();
        assertEquals(0, FrameConfig.load(file).graphMaxMs, 0);
    }

    @Test public void newFileKeepsProfilerDefaultButLegacyDetailedMigratesOnlyOnce() throws Exception {
        assertTrue(FrameConfig.load(new File(folder.getRoot(), "new.cfg")).showProfiler);
        File file = write("legacy.cfg", "hud {\n B:detailed=false\n}\n");
        FrameConfig c = FrameConfig.load(file);
        assertFalse(c.showProfiler);
        assertFalse(c.detailed);
        c.showProfiler = true;
        c.save();
        c = FrameConfig.load(file);
        assertTrue(c.showProfiler);
        assertFalse(c.detailed);
        file = write("legacy-on.cfg", "hud {\n B:detailed=true\n}\n");
        assertTrue(FrameConfig.load(file).showProfiler);
        file = write("explicit.cfg", "hud {\n B:detailed=true\n B:showProfiler=false\n}\n");
        assertFalse(FrameConfig.load(file).showProfiler);
    }

    @Test public void missingDirectMemoryMigratesHeapVisibilityOnlyOnce() throws Exception {
        FrameConfig defaults = FrameConfig.load(new File(folder.getRoot(), "new.cfg"));
        assertTrue(defaults.showMemory);
        assertTrue(defaults.showDirectMemory);
        assertEquals("auto", defaults.language);

        File file = write("legacy-memory-off.cfg", "hud {\n B:showMemory=false\n}\n");
        FrameConfig c = FrameConfig.load(file);
        assertFalse(c.showMemory);
        assertFalse(c.showDirectMemory);
        Configuration persisted = new Configuration(file);
        assertTrue(persisted.hasKey("hud", "showDirectMemory"));
        assertFalse(persisted.get("hud", "showDirectMemory", true).getBoolean(true));
        c = FrameConfig.load(file);
        assertFalse(c.showMemory);
        assertFalse(c.showDirectMemory);
        c.showMemory = true;
        c.save();
        c = FrameConfig.load(file);
        assertTrue(c.showMemory);
        assertFalse(c.showDirectMemory);

        file = write("legacy-memory-on.cfg", "hud {\n B:showMemory=true\n}\n");
        c = FrameConfig.load(file);
        assertTrue(c.showMemory);
        assertTrue(c.showDirectMemory);
    }

    @Test public void heapAndDirectMemoryTogglesAreIndependentAndPersist() throws Exception {
        File file = new File(folder.getRoot(), "memory.cfg");
        FrameConfig c = FrameConfig.load(file);
        for (boolean heap : new boolean[] {false, true}) {
            for (boolean direct : new boolean[] {false, true}) {
                c.showMemory = heap;
                c.showDirectMemory = direct;
                c.changed();
                assertEquals(heap, c.showMemory);
                assertEquals(direct, c.showDirectMemory);
                c.save();
                c = FrameConfig.load(file);
                assertEquals(heap, c.showMemory);
                assertEquals(direct, c.showDirectMemory);
                Configuration persisted = new Configuration(file);
                assertTrue(persisted.hasKey("hud", "showMemory"));
                assertTrue(persisted.hasKey("hud", "showDirectMemory"));
                assertEquals(heap, persisted.get("hud", "showMemory", !heap).getBoolean(!heap));
                assertEquals(direct, persisted.get("hud", "showDirectMemory", !direct).getBoolean(!direct));
            }
        }
    }

    @Test public void changedNormalizesLanguageAliasesAndFallsBackForInvalidValues() {
        FrameConfig c = new FrameConfig();
        String[][] cases = {
                {"uk", "uk_UA"}, {"UK", "uk_UA"}, {"uk_UA", "uk_UA"},
                {"uk-ua", "uk_UA"}, {"  UK-uA  ", "uk_UA"},
                {"en", "en_US"}, {"EN", "en_US"}, {"en_US", "en_US"},
                {"en-us", "en_US"}, {"  En-uS  ", "en_US"},
                {"auto", "auto"}, {" AUTO ", "auto"}, {null, "auto"},
                {"", "auto"}, {"   ", "auto"}, {"invalid", "auto"},
                {"en_GB", "auto"}, {"uk_RU", "auto"}
        };
        for (String[] entry : cases) {
            c.language = entry[0];
            c.changed();
            assertEquals("Language input: " + entry[0], entry[1], c.language);
        }
    }

    @Test public void languageNormalizationOnLoadAndSaveIsPersisted() throws Exception {
        String[][] cases = {
                {"UK-ua", "uk_UA"}, {"En-US", "en_US"},
                {"uk", "uk_UA"}, {"en", "en_US"},
                {"auto", "auto"}, {"unsupported", "auto"}
        };
        for (int i = 0; i < cases.length; i++) {
            String[] entry = cases[i];
            File file = write("language-" + i + ".cfg", "hud {\n S:language=" + entry[0] + "\n}\n");
            FrameConfig c = FrameConfig.load(file);
            assertEquals(entry[1], c.language);
            Configuration persisted = new Configuration(file);
            assertTrue(persisted.hasKey("hud", "language"));
            assertEquals(entry[1], persisted.get("hud", "language", "missing").getString());
            assertEquals(entry[1], FrameConfig.load(file).language);

            c.language = entry[0];
            c.save(); // Direct field edits must normalize without changed().
            assertEquals(entry[1], c.language);
            assertEquals(entry[1], FrameConfig.load(file).language);
        }
    }

    @Test public void unknownLegacyPropertiesListsAndNestedCategoriesAreRetained() throws Exception {
        File file = write("legacy.cfg", "hud {\n B:enabled=false\n B:detailed=true\n S:futureSetting=keep-me\n}\n"
                + "measurement {\n I:windowSeconds=59\n I:targetFps=900\n I:warmupMillis=9000\n D:longFrameMillis=999.5\n}\n"
                + "unknown {\n S:words <\n first\n second\n >\n child {\n I:value=73\n }\n}\n");
        FrameConfig c = FrameConfig.load(file);
        assertFalse(c.hudEnabled);
        assertTrue(c.detailed);
        assertEquals(59, c.windowSeconds);
        assertEquals(900, c.targetFps);
        assertEquals(9000, c.warmupMillis);
        assertEquals(999.5, c.longFrameMillis, 0);
        c.applyPreset("minimal");
        c.save();
        FrameConfig.load(file).save();
        Configuration persisted = new Configuration(file);
        assertEquals("keep-me", persisted.get("hud", "futureSetting", "").getString());
        assertArrayEquals(new String[] {"first", "second"}, persisted.get("unknown", "words", new String[0]).getStringList());
        assertEquals(73, persisted.get("unknown.child", "value", 0).getInt());
    }

    @Test public void minimalAndGraphPresetsEnableOnlyRequestedBlocksAndPersist() throws Exception {
        File file = new File(folder.getRoot(), "presets.cfg");
        FrameConfig c = FrameConfig.load(file);
        c.applyPreset("full");
        c.applyPreset("minimal");
        assertOnlyFpsAndFrameTime(c, false);
        c.save();
        assertOnlyFpsAndFrameTime(FrameConfig.load(file), false);
        c.applyPreset("graph");
        assertOnlyFpsAndFrameTime(c, true);
        c.save();
        assertOnlyFpsAndFrameTime(FrameConfig.load(file), true);
    }

    @Test public void fullPresetEnablesMeasuredBlocksButNotHardware() {
        FrameConfig c = new FrameConfig();
        c.applyPreset("minimal");
        c.showHardware = true;
        c.applyPreset("FULL");
        assertTrue(c.showTitles && c.showFps && c.showFrameTime && c.showStatistics && c.showMemory);
        assertTrue(c.showDirectMemory);
        assertTrue(c.showAllocation && c.showGc && c.showEnvironment && c.showProfiler && c.showGraph && c.showGraphEvents);
        assertTrue(c.detailed);
        assertFalse(c.showHardware);
        assertEquals(2, c.revision());
    }

    @Test public void presetDoesNotChangeAppearancePositionOrMeasurement() throws Exception {
        File file = new File(folder.getRoot(), "preset-language.cfg");
        FrameConfig c = FrameConfig.load(file);
        c.language = "uk_UA";
        setMeasurement(c);
        c.hudScale = 1.45;
        c.panelOpacity = 0.39;
        c.x = 101;
        c.y = 202;
        c.profilerX = 303;
        c.profilerY = 404;
        c.hudEnabled = false;
        for (String preset : new String[] {"minimal", "graph", "full"}) {
            c.applyPreset(preset);
            assertMeasurement(c);
            assertEquals(1.45, c.hudScale, 0);
            assertEquals(0.39, c.panelOpacity, 0);
            assertEquals(101, c.x);
            assertEquals(202, c.y);
            assertEquals(303, c.profilerX);
            assertEquals(404, c.profilerY);
            assertFalse(c.hudEnabled);
            assertEquals("uk_UA", c.language);
            c.save();
            assertPublicSettingsEqual(c, FrameConfig.load(file));
        }
    }

    @Test public void resetHudDefaultsPreservesEveryMeasurementAndHistorySetting() throws Exception {
        File file = new File(folder.getRoot(), "reset.cfg");
        FrameConfig c = FrameConfig.load(file);
        c.applyPreset("minimal");
        c.language = "uk_UA";
        setMeasurement(c);
        c.hudEnabled = false;
        c.hudScale = 2.5;
        c.panelOpacity = 0;
        c.x = 300;
        c.y = 200;
        c.profilerX = 100;
        c.profilerY = 400;
        c.leftAnchor = "BOTTOM_LEFT";
        c.rightAnchor = "BOTTOM_RIGHT";
        c.graphMaxMs = 0;
        c.graphWindowSeconds = 30;
        c.graphWidth = 400;
        c.graphHeight = 140;
        c.textRefreshMillis = 1000;
        c.graphRefreshMillis = 200;
        c.textShadow = true;
        c.hideWithF1 = false;
        long revision = c.revision();
        c.resetHudDefaults();
        assertEquals(revision + 1, c.revision());
        assertMeasurement(c);
        FrameConfig expected = new FrameConfig();
        expected.language = "uk_UA";
        setMeasurement(expected);
        assertEquals("uk_UA", c.language);
        assertTrue(c.showDirectMemory);
        assertPublicSettingsEqual(expected, c);
        c.save();
        assertPublicSettingsEqual(expected, FrameConfig.load(file));
    }

    @Test public void changedClampsInvalidNumbersAndAnchorsAndIncrementsRevision() {
        FrameConfig c = new FrameConfig();
        c.hudScale = Double.NaN;
        c.panelOpacity = Double.POSITIVE_INFINITY;
        c.graphMaxMs = Double.NEGATIVE_INFINITY;
        c.longFrameMillis = Double.NaN;
        c.x = -1;
        c.y = Integer.MAX_VALUE;
        c.profilerX = -5;
        c.profilerY = Integer.MAX_VALUE;
        c.graphWidth = -10;
        c.graphHeight = 999;
        c.graphWindowSeconds = 0;
        c.textRefreshMillis = 0;
        c.graphRefreshMillis = 1000;
        c.historyCapacity = 0;
        c.windowSeconds = 0;
        c.warmupMillis = -1;
        c.targetFps = Integer.MAX_VALUE;
        c.leftAnchor = null;
        c.rightAnchor = "invalid";
        c.changed();
        assertEquals(1, c.revision());
        assertEquals(0.8, c.hudScale, 0);
        assertEquals(0.65, c.panelOpacity, 0);
        assertEquals(100, c.graphMaxMs, 0);
        assertEquals(20, c.longFrameMillis, 0);
        assertEquals(0, c.x);
        assertEquals(4096, c.y);
        assertEquals(0, c.profilerX);
        assertEquals(4096, c.profilerY);
        assertEquals(100, c.graphWidth);
        assertEquals(140, c.graphHeight);
        assertEquals(1, c.graphWindowSeconds);
        assertEquals(125, c.textRefreshMillis);
        assertEquals(200, c.graphRefreshMillis);
        assertEquals(1000, c.historyCapacity);
        assertEquals(1, c.windowSeconds);
        assertEquals(0, c.warmupMillis);
        assertEquals(1000, c.targetFps);
        assertEquals("TOP_LEFT", c.leftAnchor);
        assertEquals("TOP_RIGHT", c.rightAnchor);
        c.hudScale = 100;
        c.panelOpacity = -1;
        c.graphMaxMs = 1;
        c.longFrameMillis = -100;
        c.leftAnchor = "bottom_right";
        c.changed();
        assertEquals(2, c.revision());
        assertEquals(2.5, c.hudScale, 0);
        assertEquals(0, c.panelOpacity, 0);
        assertEquals(30, c.graphMaxMs, 0);
        assertEquals(1, c.longFrameMillis, 0);
        assertEquals("BOTTOM_RIGHT", c.leftAnchor);
        c.changed();
        assertEquals(3, c.revision());
    }

    @Test public void nonfiniteValuesInFileAreNormalizedAndPersisted() throws Exception {
        File file = write("nan.cfg", "hud {\n D:hudScale=NaN\n D:panelOpacity=Infinity\n D:graphMaxMs=NaN\n}\n"
                + "measurement {\n D:longFrameMillis=-Infinity\n}\n");
        FrameConfig c = FrameConfig.load(file);
        assertEquals(0.8, c.hudScale, 0);
        assertEquals(0.65, c.panelOpacity, 0);
        assertEquals(100, c.graphMaxMs, 0);
        assertEquals(20, c.longFrameMillis, 0);
        assertPublicSettingsEqual(c, FrameConfig.load(file));
        String persisted = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertFalse(persisted.contains("NaN"));
        assertFalse(persisted.contains("Infinity"));
    }

    @Test public void malformedConfigIsNotRenamedOrReplacedWithDefaults() throws Exception {
        File file = write("malformed.cfg", "hud {\n !bad syntax\n}\n");
        byte[] original = Files.readAllBytes(file.toPath());
        try {
            FrameConfig.load(file);
            fail("Expected actionable syntax failure");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains(file.getAbsolutePath()));
            assertTrue(e.getMessage().contains("syntax"));
        }
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        assertArrayEquals(new String[] {"malformed.cfg"}, folder.getRoot().list());
    }

    @Test public void persistenceFailureIsActionableAndDoesNotDiscardLiveEdits() throws Exception {
        File directory = folder.newFolder("settings");
        File file = new File(directory, "framewatch.cfg");
        FrameConfig c = FrameConfig.load(file);
        assertTrue(file.delete());
        assertTrue(directory.delete());
        Files.write(directory.toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));
        c.hudEnabled = false;
        try {
            c.save();
            fail("Expected actionable save failure");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains(file.getAbsolutePath()));
            assertTrue(e.getMessage().contains("permissions"));
        }
        assertFalse(c.hudEnabled);
    }

    @Test public void unknownPresetDoesNotMutateSettingsOrRevision() {
        FrameConfig c = new FrameConfig();
        try {
            c.applyPreset("unknown");
            fail("Expected unknown preset rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(c.showMemory);
            assertTrue(c.showDirectMemory);
            assertEquals("auto", c.language);
            assertEquals(0, c.revision());
        }
    }

    private File write(String name, String contents) throws Exception {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void assertPublicSettingsEqual(FrameConfig expected, FrameConfig actual) throws Exception {
        for (Field field : FrameConfig.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) assertEquals(field.getName(), field.get(expected), field.get(actual));
        }
    }

    private static void assertOnlyFpsAndFrameTime(FrameConfig c, boolean graph) {
        assertTrue(c.showFps && c.showFrameTime);
        assertEquals(graph, c.showGraph);
        assertFalse(c.showTitles || c.showStatistics || c.showMemory || c.showDirectMemory || c.showAllocation || c.showGc);
        assertFalse(c.showHardware || c.showEnvironment || c.showProfiler || c.showGraphEvents || c.detailed);
    }

    private static void setMeasurement(FrameConfig c) {
        c.historyCapacity = 54321;
        c.windowSeconds = 59;
        c.warmupMillis = 9000;
        c.targetFps = 900;
        c.longFrameMillis = 999.5;
    }

    private static void assertMeasurement(FrameConfig c) {
        assertEquals(54321, c.historyCapacity);
        assertEquals(59, c.windowSeconds);
        assertEquals(9000, c.warmupMillis);
        assertEquals(900, c.targetFps);
        assertEquals(999.5, c.longFrameMillis, 0);
    }
}
