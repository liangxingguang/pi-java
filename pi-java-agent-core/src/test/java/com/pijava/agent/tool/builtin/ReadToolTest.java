package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.pijava.agent.tool.*;
import com.pijava.ai.message.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ReadToolTest {

    @Test
    void readsTextFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("hello.txt");
        Files.writeString(file, "Hello, World!");
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        assertThat(result.content()).isNotEmpty();
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("Hello, World!");
    }

    @Test
    void readsWithOffsetLimit(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("lines.txt");
        Files.writeString(file, "a\nb\nc\nd\ne");
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.of(2), Optional.of(2)),
            null, null, TestContexts.at(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("b", "c");
        assertThat(text).doesNotContain("a", "d");
    }

    @Test
    void readsImageFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("img.png");
        // Minimal PNG: 8-byte signature + IHDR chunk stub
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        Files.write(file, png);
        var tool = ReadTool.create();
        var result = tool.execute("id1", new ReadTool.ReadInput(
            file.toString(), Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0)).isInstanceOf(ContentBlock.TextContent.class);
        assertThat(result.content().get(1)).isInstanceOf(ContentBlock.ImageContent.class);
    }
}
