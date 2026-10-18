package dev.framewatch.config;

import dev.framewatch.FrameConfig;
import dev.framewatch.FrameWatch;
import dev.framewatch.client.HudTranslations;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import org.junit.Test;
import static org.junit.Assert.*;

public class HudTranslationsTest {
    @Test public void forcedLanguagesIgnoreMinecraftLanguage() {
        assertEquals("en_US", HudTranslations.effectiveLanguage("en_US", "uk_UA"));
        assertEquals("uk_UA", HudTranslations.effectiveLanguage("uk_UA", "en_US"));
        assertEquals("FRAME: ", HudTranslations.textFor("en_US", "uk_UA", "framewatch.hud.frame"));
        assertEquals("КАДР: ", HudTranslations.textFor("uk_UA", "en_US", "framewatch.hud.frame"));
        assertEquals("Готово", HudTranslations.textFor("uk_UA", "fr_FR", "framewatch.done"));
    }

    @Test public void autoFollowsOnlyUkrainianAndUnknownPreferenceFallsBackToEnglish() {
        for (String game : new String[] {"uk_UA", "uk_ua", "UK_UA", "uk"}) {
            assertEquals("uk_UA", HudTranslations.effectiveLanguage("auto", game));
            assertEquals("uk_UA", HudTranslations.effectiveLanguage(null, game));
            assertEquals("КАДР: ", HudTranslations.textFor("auto", game, "framewatch.hud.frame"));
        }
        for (String game : new String[] {null, "", "en_US", "ru_RU", "fr_FR"}) {
            assertEquals("en_US", HudTranslations.effectiveLanguage("auto", game));
        }
        assertEquals("en_US", HudTranslations.effectiveLanguage("unknown", "uk_UA"));
        assertEquals("en_US", HudTranslations.effectiveLanguage("", "uk_UA"));
        assertEquals("FRAME: ", HudTranslations.textFor("unknown", "uk_UA", "framewatch.hud.frame"));
        assertEquals("missing.key", HudTranslations.textFor("uk_UA", "en_US", "missing.key"));
    }

    @Test public void repeatedLanguageResolutionReturnsCanonicalConstantReferences() {
        String auto = new String("AuTo");
        String ukrainian = new String("UK_ua");
        String english = new String("EN_us");
        String unknown = new String("unknown");
        String ukrainianGame = new String("Uk_UA");
        String otherGame = new String("fr_FR");
        for (int frame = 0; frame < 10000; frame++) {
            assertSame("uk_UA", HudTranslations.effectiveLanguage(auto, ukrainianGame));
            assertSame("uk_UA", HudTranslations.effectiveLanguage(null, ukrainianGame));
            assertSame("uk_UA", HudTranslations.effectiveLanguage(ukrainian, otherGame));
            assertSame("en_US", HudTranslations.effectiveLanguage(english, ukrainianGame));
            assertSame("en_US", HudTranslations.effectiveLanguage(unknown, ukrainianGame));
            assertSame("en_US", HudTranslations.effectiveLanguage(auto, otherGame));
            assertSame("en_US", HudTranslations.effectiveLanguage(auto, null));
            assertSame("en_US", HudTranslations.effectiveLanguage(auto, "u"));
        }
    }

    @Test public void missingUkrainianFallsBackToEnglishThenKey() throws Exception {
        Map<String, String> english = Collections.singletonMap("test.label", "English: ");
        assertEquals("English: ", translate(Collections.<String, String>emptyMap(), english, "test.label"));
        assertEquals("missing", translate(Collections.<String, String>emptyMap(), english, "missing"));
        assertEquals("Українська: ", translate(Collections.singletonMap("test.label", "Українська: "), english, "test.label"));
    }

    @Test public void formattingUsesRootAndBadFormatsRemainSafe() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            assertEquals("1.25 ms", translate(Collections.singletonMap("value", "%.2f ms"),
                    Collections.<String, String>emptyMap(), "value", 1.25));
            assertEquals("КАДР: 12", HudTranslations.textFor("uk_UA", "en_US", "framewatch.optionValue", "КАДР", 12));
            assertEquals("12 мс", HudTranslations.textFor("uk_UA", "en_US", "framewatch.valueWithUnit", 12, "мс"));
            assertEquals("%1$s: %2$s", HudTranslations.textFor("en_US", "uk_UA", "framewatch.optionValue", "only one"));
            assertEquals("%q", translate(Collections.singletonMap("bad", "%q"), Collections.<String, String>emptyMap(), "bad", 1));
            assertEquals("%d", translate(Collections.singletonMap("bad", "%d"), Collections.<String, String>emptyMap(), "bad", "text"));
            assertEquals(Locale.FRANCE, Locale.getDefault());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test public void bundledUtf8ResourcesHaveMatchingKeysAndValidMatchingFormats() throws Exception {
        Map<String, String> english = resource("en_US");
        Map<String, String> ukrainian = resource("uk_UA");
        assertFalse(english.isEmpty());
        assertEquals(english.keySet(), ukrainian.keySet());
        assertEquals("Українська", ukrainian.get("framewatch.language.uk_UA"));
        assertEquals("Прямі буфери (не VRAM)", ukrainian.get("framewatch.showDirectMemory"));
        Pattern tokens = Pattern.compile("%(?:[0-9]+\\$)?[-#+ 0,(]*[0-9]*(?:\\.[0-9]+)?[a-zA-Z%]");
        for (String key : english.keySet()) {
            String en = english.get(key), uk = ukrainian.get(key);
            assertFalse(key, uk.contains("\uFFFD"));
            assertEquals(key, formatTokens(tokens, en), formatTokens(tokens, uk));
            String expectedEn = String.format(Locale.ROOT, en, "first", "second");
            String expectedUk = String.format(Locale.ROOT, uk, "first", "second");
            assertEquals(key, expectedEn, HudTranslations.textFor("en_US", "uk_UA", key, "first", "second"));
            assertEquals(key, expectedUk, HudTranslations.textFor("uk_UA", "en_US", key, "first", "second"));
        }
    }

    @Test public void everyRendererKeyExistsAndJoiningLabelsRetainSuffixSpaces() throws Exception {
        String[] labels = {"FPSlabel", "fps", "frame", "avg", "max", "low", "p95p99", "heap", "direct",
                "allocation", "gcTotal", "gcOverlap", "frames", "java", "lwjgl", "angelica", "shader",
                "version", "budget", "tick", "render", "present", "other"};
        String[] other = {"title", "cpuUnavailable", "gpuUnavailable", "vramUnavailable",
                "profilerTitle", "profilerScopes", "unavailable", "events"};
        for (String language : new String[] {"en_US", "uk_UA"}) {
            Map<String, String> entries = resource(language);
            for (String label : labels) {
                String key = "framewatch.hud." + label;
                assertTrue(key, entries.containsKey(key));
                assertTrue(key, HudTranslations.textFor(language, null, key).endsWith(": "));
                assertEquals(key, entries.get(key), HudTranslations.textFor(language, null, key));
            }
            for (String label : other) assertTrue(label, entries.containsKey("framewatch.hud." + label));
            assertTrue(entries.containsKey("framewatch.unit.ms"));
            assertTrue(entries.containsKey("framewatch.unit.s"));
            assertTrue(entries.containsKey("framewatch.unit.MiB"));
            assertTrue(entries.containsKey("framewatch.unit.MiBps"));
        }
    }

    @Test public void eventLegendsAreCompleteLocalizedAndKeepEventColors() {
        assertEquals("Events: §eGC §bReload §dShader §cLong",
                HudTranslations.textFor("en_US", "uk_UA", "framewatch.hud.events"));
        assertEquals("Події: §eGC §bРесурси §dШейдер §cДовгий",
                HudTranslations.textFor("uk_UA", "en_US", "framewatch.hud.events"));
    }

    @Test public void runtimeSelectionDoesNotChangeMinecraftSettingsOrGlobalLocale() throws Exception {
        Field singleton = null;
        for (Field field : Minecraft.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Minecraft.class) singleton = field;
        }
        assertNotNull(singleton);
        singleton.setAccessible(true);
        Object previousClient = singleton.get(null);
        FrameConfig previousConfig = FrameWatch.config;
        Locale previousLocale = Locale.getDefault();
        Map<Field, Object> globalTranslations = new HashMap<>();
        Map<Field, Map<?, ?>> localeMaps = new HashMap<>();
        Object gameLocale = null;
        for (Field field : I18n.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                Object value = field.get(null);
                globalTranslations.put(field, value);
                if (value != null && value.getClass().getName().equals("net.minecraft.client.resources.Locale")) {
                    gameLocale = value;
                    for (Field localeField : value.getClass().getDeclaredFields()) {
                        if (!Modifier.isStatic(localeField.getModifiers()) && Map.class.isAssignableFrom(localeField.getType())) {
                            localeField.setAccessible(true);
                            localeMaps.put(localeField, new HashMap<>((Map<?, ?>) localeField.get(value)));
                        }
                    }
                }
            }
        }
        try {
            // Allocate only Java state: no display, GL context, resource loading or options I/O.
            Minecraft client = (Minecraft) allocate(Minecraft.class);
            Field managerField = null;
            Object manager = null;
            for (Field field : Minecraft.class.getDeclaredFields()) {
                if (field.getType().getName().equals("net.minecraft.client.resources.LanguageManager")) {
                    managerField = field;
                    field.setAccessible(true);
                    manager = allocate(field.getType());
                    field.set(client, manager);
                    break;
                }
            }
            assertNotNull(managerField);
            GameSettings settings = (GameSettings) allocate(GameSettings.class);
            singleton.set(null, client);
            FrameWatch.config = null;
            assertSame("en_US", HudTranslations.currentLanguage());
            settings.language = "uk_UA";
            client.gameSettings = settings;
            assertSame("uk_UA", HudTranslations.currentLanguage());
            FrameWatch.config = new FrameConfig();
            HudTranslations.initialize();
            FrameWatch.config.language = null;
            assertSame("uk_UA", HudTranslations.currentLanguage());
            assertEquals("Готово", HudTranslations.text("framewatch.done"));
            FrameWatch.config.language = "en_US";
            assertSame("en_US", HudTranslations.currentLanguage());
            assertEquals("Done", HudTranslations.text("framewatch.done"));
            settings.language = "fr_FR";
            FrameWatch.config.language = "uk_UA";
            assertSame("uk_UA", HudTranslations.currentLanguage());
            assertEquals("Готово", HudTranslations.text("framewatch.done"));
            assertEquals("fr_FR", settings.language);
            assertSame(settings, client.gameSettings);
            assertSame(manager, managerField.get(client));
            assertSame(previousLocale, Locale.getDefault());
            for (Map.Entry<Field, Object> entry : globalTranslations.entrySet()) {
                assertSame(entry.getValue(), entry.getKey().get(null));
            }
            for (Map.Entry<Field, Map<?, ?>> entry : localeMaps.entrySet()) {
                assertEquals(entry.getValue(), entry.getKey().get(gameLocale));
            }
        } finally {
            singleton.set(null, previousClient);
            FrameWatch.config = previousConfig;
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field instance = unsafeClass.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type);
    }

    private static String translate(Map<String, String> selected, Map<String, String> english,
            String key, Object... args) throws Exception {
        Method method = HudTranslations.class.getDeclaredMethod("translate", Map.class, Map.class, String.class, Object[].class);
        method.setAccessible(true);
        return (String) method.invoke(null, selected, english, key, args);
    }

    private static String formatTokens(Pattern pattern, String value) {
        StringBuilder result = new StringBuilder();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) result.append(matcher.group()).append(';');
        return result.toString();
    }

    private static Map<String, String> resource(String language) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (InputStream stream = HudTranslationsTest.class.getResourceAsStream("/assets/framewatch/lang/" + language + ".lang")) {
            assertNotNull(language, stream);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                    int separator = line.indexOf('=');
                    assertTrue(language + ": " + line, separator > 0);
                    String key = line.substring(0, separator);
                    assertNull("duplicate " + key, entries.put(key, line.substring(separator + 1)));
                }
            }
        }
        return entries;
    }
}
