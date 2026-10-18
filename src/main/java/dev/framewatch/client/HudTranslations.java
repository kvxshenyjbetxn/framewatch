package dev.framewatch.client;

import dev.framewatch.FrameWatch;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Bundled, mod-scoped translations; never changes Minecraft's language or resources. */
public final class HudTranslations {
    private HudTranslations() {}

    private static final class Bundles {
        static final Map<String, String> ENGLISH = load("en_US");
        static final Map<String, String> UKRAINIAN = load("uk_UA");
    }

    /** Preload both bundles once, before the first HUD draw if desired. */
    public static void initialize() { Bundles.ENGLISH.size(); }

    public static String effectiveLanguage(String preference, String minecraftLanguage) {
        if (preference == null || "auto".equalsIgnoreCase(preference)) {
            return minecraftLanguage != null && minecraftLanguage.regionMatches(true, 0, "uk", 0, 2)
                    ? "uk_UA" : "en_US";
        }
        return "uk_UA".equalsIgnoreCase(preference) ? "uk_UA" : "en_US";
    }

    public static String currentLanguage() {
        String preference = FrameWatch.config == null ? null : FrameWatch.config.language;
        // Forced languages do not even need the Minecraft singleton.
        if (preference != null && !"auto".equalsIgnoreCase(preference)) {
            return effectiveLanguage(preference, null);
        }
        Minecraft client = Minecraft.getMinecraft();
        return effectiveLanguage(preference, client == null || client.gameSettings == null
                ? null : client.gameSettings.language);
    }

    public static String text(String key, Object... args) {
        return textFor(currentLanguage(), null, key, args);
    }

    /** Pure selection/formatting entry point, independent of game and config globals. */
    public static String textFor(String preference, String minecraftLanguage, String key, Object... args) {
        Map<String, String> selected = "uk_UA".equals(effectiveLanguage(preference, minecraftLanguage))
                ? Bundles.UKRAINIAN : Bundles.ENGLISH;
        return translate(selected, Bundles.ENGLISH, key, args);
    }

    private static String translate(Map<String, String> selected, Map<String, String> english,
            String key, Object... args) {
        String value = selected.get(key);
        if (value == null) value = english.get(key);
        if (value == null) value = key;
        if (args == null || args.length == 0) return value;
        try {
            return String.format(Locale.ROOT, value, args);
        } catch (IllegalFormatException e) {
            // A broken format or mismatched caller must not take down the HUD.
            return value;
        }
    }

    private static Map<String, String> load(String language) {
        Map<String, String> entries = new HashMap<>();
        String path = "/assets/framewatch/lang/" + language + ".lang";
        try (InputStream stream = HudTranslations.class.getResourceAsStream(path)) {
            if (stream == null) return Collections.emptyMap();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("\uFEFF")) line = line.substring(1);
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    int separator = line.indexOf('=');
                    if (separator < 0) continue;
                    // Unlike Properties, .lang is UTF-8; label suffix spaces are significant.
                    entries.put(line.substring(0, separator).trim(), line.substring(separator + 1));
                }
            }
        } catch (IOException e) {
            if (FrameWatch.log != null) FrameWatch.log.warn("Cannot read bundled FrameWatch language " + language, e);
        }
        return Collections.unmodifiableMap(entries);
    }
}
