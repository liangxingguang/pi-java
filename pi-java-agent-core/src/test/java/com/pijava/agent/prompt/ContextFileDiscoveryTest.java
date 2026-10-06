package com.pijava.agent.prompt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContextFileDiscovery} characterization — pi
 * {@code loadProjectContextFiles}（resource-loader.ts）。
 */
class ContextFileDiscoveryTest {

    @TempDir
    Path temp;

    private void write(Path dir, String name, String content) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void emptyWhenNoCandidateExists() {
        var files = ContextFileDiscovery.discover(temp, null);
        assertThat(files).isEmpty();
    }

    @Test
    void candidateOrderFirstMatchWins() throws Exception {
        write(temp, "CLAUDE.md", "claude");
        write(temp, "AGENTS.md", "agents");
        write(temp, "AGENTS.override.md", "override");

        var files = ContextFileDiscovery.discover(temp, null);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).endsWith("AGENTS.override.md");
        assertThat(files.get(0).content()).isEqualTo("override");
    }

    @Test
    void uppercaseNameCandidateIsReachable() throws Exception {
        write(temp, "AGENTS.MD", "upper");
        var files = ContextFileDiscovery.discover(temp, null);
        assertThat(files).singleElement()
            .extracting(f -> f.content()).isEqualTo("upper");
        // On a case-sensitive FS (pi's platform) the hit path is literally AGENTS.MD;
        // on case-insensitive FS (Windows) AGENTS.md resolves the same file first.
        var path = files.get(0).path();
        assertThat(path).matches(p -> p.endsWith("AGENTS.MD") || p.endsWith("AGENTS.md"));
    }

    @Test
    void directoriesAreNotRegularFilesAndSkipped() throws Exception {
        Files.createDirectory(temp.resolve("AGENTS.md"));
        write(temp, "CLAUDE.md", "claude");

        var files = ContextFileDiscovery.discover(temp, null);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).endsWith("CLAUDE.md");
    }

    @Test
    void bomIsStripped() throws Exception {
        Files.createDirectories(temp);
        Files.write(temp.resolve("AGENTS.md"),
            new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'});

        assertThat(ContextFileDiscovery.discover(temp, null))
            .singleElement()
            .extracting(f -> f.content())
            .isEqualTo("hi");
    }

    @Test
    void ancestorsOrderedFarthestToNearest() throws Exception {
        var grandparent = temp;
        var parent = grandparent.resolve("a");
        var cwd = parent.resolve("b");
        write(grandparent, "AGENTS.md", "grand");
        write(parent, "AGENTS.md", "parent");
        write(cwd, "AGENTS.md", "cwd");

        var files = ContextFileDiscovery.discover(cwd, null);

        assertThat(files).extracting(f -> f.content())
            .containsExactly("grand", "parent", "cwd");
        assertThat(files).allSatisfy(f -> assertThat(f.path()).endsWith("AGENTS.md"));
    }

    @Test
    void globalAgentDirComesFirst() throws Exception {
        var agentDir = temp.resolve("agent");
        var cwd = temp.resolve("project");
        write(agentDir, "AGENTS.md", "global");
        write(cwd, "AGENTS.md", "project");

        assertThat(ContextFileDiscovery.discover(cwd, agentDir))
            .extracting(f -> f.content())
            .containsExactly("global", "project");
    }

    @Test
    void sameFileViaSymlinkIsDeduplicated() throws Exception {
        write(temp.resolve("real"), "AGENTS.md", "x");
        var link = temp.resolve("link");
        try {
            Files.createSymbolicLink(link, temp.resolve("real"));
        } catch (Exception | LinkageError e) {
            // symlinks unsupported (e.g. Windows without privilege) — skip
            return;
        }
        // walk from link dir; the two walk positions resolve to the same real path
        var files = ContextFileDiscovery.discover(link, null);
        assertThat(files).hasSize(1);
    }

    @Test
    void unreadableCandidateIsSkippedNotThrown() throws Exception {
        // A path that is not a readable dir ⇒ loadFromDir simply finds nothing
        var nonexistent = temp.resolve("missing");
        assertThat(ContextFileDiscovery.discover(nonexistent, null)).isEmpty();
    }

    @Test
    void stripBomHelper() {
        assertThat(ContextFileDiscovery.stripBom("﻿x")).isEqualTo("x");
        assertThat(ContextFileDiscovery.stripBom("x")).isEqualTo("x");
        assertThat(ContextFileDiscovery.stripBom("")).isEmpty();
    }

    @Test
    void candidatesMatchPiOrder() {
        assertThat(ContextFileDiscovery.CANDIDATES).isEqualTo(List.of(
            "AGENTS.override.md", "AGENTS.md", "AGENTS.MD",
            "CLAUDE.md", "CLAUDE.MD"));
    }
}
