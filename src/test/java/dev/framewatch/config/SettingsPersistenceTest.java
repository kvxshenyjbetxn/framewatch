package dev.framewatch.config;

import cpw.mods.fml.relauncher.FMLInjectionData;
import dev.framewatch.FrameConfig;
import dev.framewatch.FrameWatch;
import dev.framewatch.client.HudPositionScreen;
import dev.framewatch.client.HudSettingsScreen;
import dev.framewatch.client.SettingsPersistence;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;

public class SettingsPersistenceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private FrameConfig previousConfig;
    private Logger previousLog;

    @BeforeClass public static void requireClientRuntime() throws Exception {
        // Minecraft's static type dependencies are absent from RFG's headless test runtime.
        try { Class.forName("org.lwjgl.LWJGLException", false, SettingsPersistenceTest.class.getClassLoader()); }
        catch (ClassNotFoundException e) { assumeNoException("Persistence GUI tests require the Minecraft client runtime", e); }
        Field home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        if (home.get(null) == null) home.set(null, new File("."));
    }

    @Before public void preserveGlobals() {
        previousConfig = FrameWatch.config;
        previousLog = FrameWatch.log;
        FrameWatch.log = null;
    }

    @After public void restoreGlobals() {
        FrameWatch.config = previousConfig;
        FrameWatch.log = previousLog;
    }

    @Test public void successfulSaveReturnsTrueAndPersistsLiveEdits() {
        File file = new File(folder.getRoot(), "framewatch.cfg");
        FrameWatch.config = FrameConfig.load(file);
        FrameWatch.config.showProfiler = false;
        FrameWatch.config.hudScale = 1.25;
        FrameWatch.config.changed();
        assertTrue(SettingsPersistence.save());
        FrameConfig reloaded = FrameConfig.load(file);
        assertFalse(reloaded.showProfiler);
        assertEquals(1.25, reloaded.hudScale, 0);
    }

    @Test public void failedSaveReturnsFalseWithNoLoggerOrPlayerAndPreservesEdits() throws Exception {
        File blockedDirectory = makeSaveFail();
        FrameConfig config = FrameWatch.config;
        long revision = config.revision();
        assertFalse(SettingsPersistence.save());
        assertSame(config, FrameWatch.config);
        assertFalse(config.hudEnabled);
        assertEquals(1.25, config.hudScale, 0);
        assertEquals(revision, config.revision());
        assertEquals("not a directory", new String(Files.readAllBytes(blockedDirectory.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void bothCloseCallbacksTolerateRealSaveFailureWithoutDiscardingEdits() throws Exception {
        makeSaveFail();
        FrameConfig config = FrameWatch.config;
        long revision = config.revision();
        new HudSettingsScreen(null).onGuiClosed();
        new HudPositionScreen(null).onGuiClosed();
        assertSame(config, FrameWatch.config);
        assertFalse(config.hudEnabled);
        assertEquals(1.25, config.hudScale, 0);
        assertEquals(revision, config.revision());
    }

    private File makeSaveFail() throws Exception {
        File directory = folder.newFolder("settings");
        File file = new File(directory, "framewatch.cfg");
        FrameWatch.config = FrameConfig.load(file);
        assertTrue(file.delete());
        assertTrue(directory.delete());
        Files.write(directory.toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));
        FrameWatch.config.hudEnabled = false;
        FrameWatch.config.hudScale = 1.25;
        FrameWatch.config.changed();
        return directory;
    }
}
