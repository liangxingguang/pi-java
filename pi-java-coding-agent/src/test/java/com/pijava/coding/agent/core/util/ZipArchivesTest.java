package com.pijava.coding.agent.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipInputStream;

import com.pijava.coding.agent.core.bugreport.BugReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** pi {@code zip.test.ts} 对拍（解压回读；字节级不对拍——D79）。 */
class ZipArchivesTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsAllFourBugReportFiles() throws Exception {
        var entries = List.of(
            new BugReport.ReportFile("report.json", "application/json", "{\"a\":1}\n"),
            new BugReport.ReportFile("diagnostics.json", "application/json", "{\"b\":2}\n"),
            new BugReport.ReportFile("session.jsonl", "application/x-ndjson", "{\"l\":1}\n"),
            new BugReport.ReportFile("summary.md", "text/markdown", "# R\n"));
        var file = dir.resolve("pi-bug-report-x.zip");

        ZipArchives.write(file, entries);

        var read = unzip(file);
        assertThat(read).containsExactly(
            java.util.Map.entry("report.json", "{\"a\":1}\n"),
            java.util.Map.entry("diagnostics.json", "{\"b\":2}\n"),
            java.util.Map.entry("session.jsonl", "{\"l\":1}\n"),
            java.util.Map.entry("summary.md", "# R\n"));
    }

    @Test
    void roundTripsMinimalPair() throws Exception {
        var entries = List.of(
            new BugReport.ReportFile("report.json", "application/json", "{}"),
            new BugReport.ReportFile("diagnostics.json", "application/json", "{}"));
        var file = dir.resolve("m.zip");

        ZipArchives.write(file, entries);

        assertThat(unzip(file)).containsOnlyKeys("report.json", "diagnostics.json");
    }

    private static LinkedHashMap<String, String> unzip(Path file) throws Exception {
        var out = new LinkedHashMap<String, String>();
        try (var zis = new ZipInputStream(java.nio.file.Files.newInputStream(file),
                StandardCharsets.UTF_8)) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                out.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
