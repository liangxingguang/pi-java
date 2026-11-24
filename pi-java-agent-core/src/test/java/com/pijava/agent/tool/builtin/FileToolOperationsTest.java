package com.pijava.agent.tool.builtin;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.tool.TestContexts;
import com.pijava.ai.message.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B287：文件工具的 {@code Operations} 接缝必须可由工厂注入，且不再回落到本地 NIO。
 * 每个用例都用一个<b>磁盘上不存在</b>的路径，证明工具只走注入实现。
 */
class FileToolOperationsTest {

    private static String textOf(com.pijava.agent.tool.ToolResult<?> result) {
        return ((ContentBlock.TextContent) result.content().get(0)).text();
    }

    @Test
    void readUsesInjectedOperations(@TempDir Path tmp) throws Exception {
        var ops = new ReadTool.Operations() {
            @Override public byte[] readFile(String absolutePath) {
                return "line one\nline two".getBytes(StandardCharsets.UTF_8);
            }
            @Override public InputStream createReadStream(String absolutePath) {
                return new ByteArrayInputStream("line one\nline two".getBytes(StandardCharsets.UTF_8));
            }
            @Override public void access(String absolutePath) {
                // remote file exists
            }
            @Override public Optional<String> detectImageMimeType(String absolutePath) {
                return Optional.empty();
            }
        };

        var result = ReadTool.create(ops).execute("id",
            new ReadTool.ReadInput("ghost.txt", Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));

        assertThat(textOf(result)).contains("line one").contains("line two");
        assertThat(Files.exists(tmp.resolve("ghost.txt"))).isFalse();
    }

    @Test
    void writeUsesInjectedOperations(@TempDir Path tmp) throws Exception {
        var writes = new LinkedHashMap<String, String>();
        var mkdirs = new ArrayList<String>();
        var ops = new WriteTool.Operations() {
            @Override public void writeFile(String absolutePath, String content) {
                writes.put(absolutePath, content);
            }
            @Override public void mkdir(String directoryPath) {
                mkdirs.add(directoryPath);
            }
        };

        WriteTool.create(ops).execute("id",
            new WriteTool.WriteInput("remote.txt", "remote content"),
            null, null, TestContexts.at(tmp));

        String target = tmp.resolve("remote.txt").toString();
        assertThat(writes).containsEntry(target, "remote content");
        assertThat(mkdirs).containsExactly(tmp.toString());
        assertThat(Files.exists(tmp.resolve("remote.txt"))).isFalse();
    }

    @Test
    void editUsesInjectedOperationsForReadBackupAndWrite(@TempDir Path tmp) throws Exception {
        var writes = new LinkedHashMap<String, String>();
        var ops = new EditTool.Operations() {
            @Override public byte[] readFile(String absolutePath) {
                return "Hello World".getBytes(StandardCharsets.UTF_8);
            }
            @Override public void writeFile(String absolutePath, String content) {
                writes.put(absolutePath, content);
            }
            @Override public void access(String absolutePath) {
                // remote file exists
            }
        };

        EditTool.create(ops).execute("id",
            new EditTool.EditInput("remote.txt",
                List.of(new EditTool.Edit("World", "Java"))),
            null, null, TestContexts.at(tmp));

        String target = tmp.resolve("remote.txt").toString();
        assertThat(writes).containsEntry(target, "Hello Java");
        assertThat(writes).containsEntry(target + ".bak", "Hello World");
        assertThat(Files.exists(tmp.resolve("remote.txt"))).isFalse();
    }

    @Test
    void lsUsesInjectedOperations(@TempDir Path tmp) throws Exception {
        String dir = tmp.toString();
        var ops = new LsTool.Operations() {
            @Override public boolean exists(String absolutePath) {
                return true;
            }
            @Override public boolean isDirectory(String absolutePath) {
                return absolutePath.equals(dir) || absolutePath.endsWith("sub");
            }
            @Override public List<String> listNames(String absolutePath) {
                return List.of("b.txt", "sub");
            }
        };

        var result = LsTool.create(ops).execute("id",
            new LsTool.LsInput(Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));

        assertThat(textOf(result)).isEqualTo("b.txt\nsub/");
        assertThat(Files.exists(tmp.resolve("b.txt"))).isFalse();
    }

    @Test
    void findUsesInjectedGlob(@TempDir Path tmp) throws Exception {
        String dir = tmp.toString();
        var ops = new FindTool.Operations() {
            @Override public boolean exists(String absolutePath) {
                return true;
            }
            @Override public boolean isDirectory(String absolutePath) {
                return true;
            }
            @Override public List<String> glob(String pattern, String absoluteRoot,
                    List<String> ignore, int maxResults) {
                return List.of(dir + "/src/A.java", dir + "/b.txt");
            }
        };

        var result = FindTool.create(ops).execute("id",
            new FindTool.FindInput("*.java", Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));

        assertThat(textOf(result)).isEqualTo("src/A.java\nb.txt");
        assertThat(Files.exists(tmp.resolve("src"))).isFalse();
    }

    @Test
    void grepUsesInjectedDirectoryProbe(@TempDir Path tmp) throws Exception {
        var called = new AtomicReference<String>();
        var ops = new GrepTool.Operations() {
            @Override public boolean isDirectory(String absolutePath) {
                called.set(absolutePath);
                return false;
            }
        };

        var result = GrepTool.create(ops).execute("id",
            new GrepTool.GrepInput("anything", Optional.of("ghost.txt"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));

        assertThat(called.get()).isEqualTo(tmp.resolve("ghost.txt").toString());
        assertThat(textOf(result)).isEqualTo("No matches found");
    }

    @Test
    void defaultFactoriesStillUseTheContextFilesystem(@TempDir Path tmp) throws Exception {
        // 注入缺席时保持既有行为：read/write 仍走 ToolContext.fs()。
        Files.writeString(tmp.resolve("plain.txt"), "default path");
        var read = ReadTool.create().execute("id",
            new ReadTool.ReadInput("plain.txt", Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        assertThat(textOf(read)).contains("default path");

        WriteTool.create().execute("id", new WriteTool.WriteInput("written.txt", "ok"),
            null, null, TestContexts.at(tmp));
        assertThat(Files.readString(tmp.resolve("written.txt"))).isEqualTo("ok");
    }
}
