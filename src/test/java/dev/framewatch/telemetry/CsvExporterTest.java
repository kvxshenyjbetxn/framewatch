package dev.framewatch.telemetry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class CsvExporterTest {
    private static final String HEADER = "timestamp_ns,interval_ms,work_ms,render_ms,"
            + "present_ms,tick_ms,tick_count,gc_pause_overlap_ms,event_flags";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void exportsCompleteChronologicalRawValuesInUtf8() throws IOException {
        FrameHistory history = new FrameHistory(2);
        history.record(1, 1, 0, 0, 0, 0, 0, 0, 0);
        history.record(200, 16_123_456, 12_345_678, 4_567_890, 1_234_567,
                2_345_678, 2, 345_678, FrameSnapshot.EVENT_GC | FrameSnapshot.EVENT_LONG_FRAME);
        history.record(300, 20_000_001, 10_000_002, 3_000_003, 4_000_004,
                5_000_005, 3, 0, FrameSnapshot.EVENT_RESOURCE_RELOAD | FrameSnapshot.EVENT_SHADER_CHANGE);
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("note", "caf\u00e9 \u96ea");
        Path file = CsvExporter.export(directory(), history.snapshot(0, 0), metadata);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(4, lines.size());
        assertEquals("# note=caf\u00e9 \u96ea", lines.get(0));
        assertEquals(HEADER, lines.get(1));
        assertEquals("200,16.123456,12.345678,4.567890,1.234567,2.345678,2,0.345678,5", lines.get(2));
        assertEquals("300,20.000001,10.000002,3.000003,4.000004,5.000005,3,0.000000,10", lines.get(3));
    }

    @Test
    public void metadataCannotInjectRowsAndLiteralBackslashesStayDistinguishable() throws IOException {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("note\r\nkey", "first\r\nsecond\nthird\\n\u0085\u2028\u2029");
        metadata.put("empty", null);
        metadata.put(null, "ignored");
        Path file = CsvExporter.export(directory(), emptySnapshot(), metadata);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertEquals("# note\\r\\nkey=first\\r\\nsecond\\nthird\\\\n\\u0085\\u2028\\u2029", lines.get(0));
        assertEquals("# empty=", lines.get(1));
        assertEquals(HEADER, lines.get(2));
    }

    @Test
    public void omitsCommonPrivateMetadataAndDoesNotAddMachineInformation() throws IOException {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("mod_version", "1.0");
        metadata.put("ACCESS_TOKEN", "private-token");
        metadata.put("username", "private-user");
        metadata.put("game_directory", "private-path");
        metadata.put("api_key", "private-key");
        metadata.put("sessionId", "private-session");
        metadata.put("email", "private-email");
        metadata.put("command_arguments", "private-arguments");
        Path file = CsvExporter.export(directory(), emptySnapshot(), metadata);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("# mod_version=1.0", lines.get(0));
        assertEquals(HEADER, lines.get(1));
    }

    @Test
    public void filenamesAreTimestampedUniqueAndEarlierExportsAreNotOverwritten() throws IOException {
        Path directory = directory();
        FrameSnapshot snapshot = emptySnapshot();
        Set<Path> files = new HashSet<Path>();
        for (int i = 0; i < 20; i++) {
            Path file = CsvExporter.export(directory, snapshot,
                    Collections.singletonMap("sequence", Integer.toString(i)));
            assertTrue(files.add(file));
            assertEquals(directory, file.getParent());
            assertTrue(file.getFileName().toString().matches("framewatch-\\d{8}-\\d{6}-\\d{3}-.+\\.csv"));
        }
        for (Path file : files) {
            assertEquals(2, Files.readAllLines(file, StandardCharsets.UTF_8).size());
        }
        Set<String> sequences = new HashSet<String>();
        for (Path file : files) {
            sequences.add(Files.readAllLines(file, StandardCharsets.UTF_8).get(0));
        }
        assertEquals(20, sequences.size());
    }

    @Test
    public void decimalFormattingDoesNotFollowDefaultLocale() throws IOException {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            FrameHistory history = new FrameHistory(1);
            history.record(123, 1_500_000, 2_250_000, 3_125_000, 4_062_500,
                    5_031_250, 2, 500_000, 1);
            Path file = CsvExporter.export(directory(), history.snapshot(0, 0), null);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertEquals(2, lines.size());
            assertEquals(HEADER, lines.get(0));
            assertEquals("123,1.500000,2.250000,3.125000,4.062500,5.031250,2,0.500000,1", lines.get(1));
            assertEquals(9, lines.get(1).split(",", -1).length);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void emptySnapshotExportsOnlyHeaderAndMissingDirectoryIsCreated() throws IOException {
        Path directory = temporaryFolder.getRoot().toPath().resolve("nested").resolve("exports");
        Path file = CsvExporter.export(directory, emptySnapshot(), Collections.<String, String>emptyMap());
        assertEquals(Collections.singletonList(HEADER), Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    @Test
    public void invalidDirectoryFailsWithIOException() throws IOException {
        Path notDirectory = temporaryFolder.newFile("not-a-directory").toPath();
        try {
            CsvExporter.export(notDirectory, emptySnapshot(), null);
            fail("Expected IOException");
        } catch (IOException expected) {
            assertTrue(Files.isRegularFile(notDirectory));
        }
    }

    private Path directory() {
        return temporaryFolder.getRoot().toPath();
    }

    private static FrameSnapshot emptySnapshot() {
        return new FrameHistory(1).snapshot(0, 0);
    }
}
