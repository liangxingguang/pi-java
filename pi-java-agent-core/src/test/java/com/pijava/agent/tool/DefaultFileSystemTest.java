package com.pijava.agent.tool;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultFileSystemTest {

    @Test
    void readLinesReadsFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3");
        var fs = new DefaultFileSystem();
        var lines = fs.readLines(file.toString(), 0, 0);
        assertThat(lines).containsExactly("line1", "line2", "line3");
    }

    @Test
    void readLinesWithOffset(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3");
        var fs = new DefaultFileSystem();
        var lines = fs.readLines(file.toString(), 1, 0);
        assertThat(lines).containsExactly("line2", "line3");
    }

    @Test
    void writeFileCreatesFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("subdir").resolve("out.txt");
        var fs = new DefaultFileSystem();
        fs.writeFile(file.toString(), "hello world");
        assertThat(Files.readString(file)).isEqualTo("hello world");
    }

    @Test
    void fileInfoReturnsMetadata(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("info.txt");
        Files.writeString(file, "data");
        var fs = new DefaultFileSystem();
        var info = fs.fileInfo(file.toString());
        assertThat(info.kind()).isEqualTo("file");
        assertThat(info.size()).isGreaterThan(0);
    }

    @Test
    void listDirListsContents(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "a");
        Files.writeString(tmp.resolve("b.txt"), "b");
        Files.createDirectory(tmp.resolve("sub"));
        var fs = new DefaultFileSystem();
        var entries = fs.listDir(tmp.toString(), false);
        assertThat(entries).hasSize(3);
        assertThat(entries.stream().map(FileInfo::kind)).contains("file", "dir");
    }

    @Test
    void listDirRecursive(@TempDir Path tmp) throws Exception {
        var sub = tmp.resolve("sub");
        Files.createDirectory(sub);
        Files.writeString(sub.resolve("nested.txt"), "nested");
        Files.writeString(tmp.resolve("top.txt"), "top");
        var fs = new DefaultFileSystem();
        var entries = fs.listDir(tmp.toString(), true);
        assertThat(entries).hasSize(3); // sub dir + sub/nested.txt + top.txt
    }
}
