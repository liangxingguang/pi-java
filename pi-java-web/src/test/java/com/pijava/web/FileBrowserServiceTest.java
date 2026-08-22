package com.pijava.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件浏览器（Phase 7 Stage B）：目录列表 / 读文件 / 工作区约束。
 */
class FileBrowserServiceTest {

    @TempDir
    Path tempDir;

    private FileBrowserService service() {
        return new FileBrowserService(tempDir);
    }

    @Test
    void listDirReturnsChildrenDirsFirst() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello");
        Files.createDirectory(tempDir.resolve("src"));

        var listing = service().listDir("");

        assertThat(listing.path()).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
        assertThat(listing.entries()).extracting(e -> e.name())
            .containsExactly("src", "a.txt");
        assertThat(listing.entries().get(0).kind()).isEqualTo("dir");
        assertThat(listing.entries().get(1).kind()).isEqualTo("file");
    }

    @Test
    void readFileReturnsContent() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello world");

        var content = service().readFile("a.txt");

        assertThat(content.content()).isEqualTo("hello world");
        assertThat(content.truncated()).isFalse();
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        assertThatThrownBy(() -> service().readFile("../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("outside workspace");
    }

    @Test
    void readNonFileIsRejected() {
        assertThatThrownBy(() -> service().readFile(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Not a file");
    }
}
