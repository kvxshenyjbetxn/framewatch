package dev.framewatch.telemetry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** CSV I/O intended to be called off the render thread with a detached snapshot. */
public final class CsvExporter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final String HEADER = "timestamp_ns,interval_ms,work_ms,render_ms,"
            + "present_ms,tick_ms,tick_count,gc_pause_overlap_ms,event_flags";

    private CsvExporter() {
    }

    /**
     * Exports every sample in snapshot order as UTF-8. Files are atomically
     * reserved with unique timestamped names and existing exports are not replaced.
     * Metadata may be null; CR/LF and other line separators are escaped so they
     * cannot introduce data rows. No system properties, user names or paths are
     * automatically collected. Commonly sensitive metadata keys are omitted.
     * Callers must only supply non-sensitive metadata: arbitrary values cannot
     * reliably be identified as secrets from their contents alone.
     */
    public static Path export(Path directory, FrameSnapshot snapshot,
            Map<String, String> metadata) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(snapshot, "snapshot");
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory,
                "framewatch-" + FILE_TIME.format(Instant.now()) + "-", ".csv");
        boolean complete = false;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE)) {
                if (metadata != null) {
                    for (Map.Entry<String, String> entry : metadata.entrySet()) {
                        String key = entry.getKey();
                        if (key == null || isSensitiveKey(key)) {
                            continue;
                        }
                        writer.write("# " + escapeMetadata(key) + "="
                                + escapeMetadata(entry.getValue()));
                        writer.write('\n');
                    }
                }
                writer.write(HEADER);
                writer.write('\n');
                for (int i = 0; i < snapshot.sampleCount; i++) {
                    writer.write(String.format(Locale.ROOT,
                            "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.6f,%d%n",
                            snapshot.timestampsNanos[i],
                            snapshot.intervalsNanos[i] / 1_000_000.0,
                            snapshot.workNanos[i] / 1_000_000.0,
                            snapshot.renderNanos[i] / 1_000_000.0,
                            snapshot.presentNanos[i] / 1_000_000.0,
                            snapshot.tickNanos[i] / 1_000_000.0,
                            snapshot.tickCounts[i],
                            snapshot.gcPauseNanos[i] / 1_000_000.0,
                            snapshot.events[i]));
                }
            }
            complete = true;
            return file;
        } finally {
            if (!complete) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static String escapeMetadata(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
                .replace("\u0085", "\\u0085").replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("credential") || normalized.contains("auth")
                || normalized.contains("session") || normalized.contains("username")
                || normalized.contains("user_name") || normalized.contains("uuid")
                || normalized.contains("email") || normalized.contains("hostname")
                || normalized.contains("host_name") || normalized.contains("address")
                || normalized.contains("path") || normalized.contains("directory")
                || normalized.contains("command") || normalized.contains("argument")
                || normalized.contains("api_key") || normalized.contains("apikey")
                || normalized.equals("user") || normalized.equals("ip");
    }
}
