package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.*;
import com.pijava.ai.message.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class GrepToolTest {

    private static ToolContext contextFor(Path cwd) {
        return new ToolContext(cwd.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
    }

    @Test
    void findsMatchInFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.java");
        Files.writeString(file, "public class Test {\n    private int x;\n}");
        var tool = GrepTool.create();
        var result = tool.execute("id1", new GrepTool.GrepInput(
            "class", Optional.of(file.toString()), Optional.empty()),
            null, null, contextFor(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("class");
    }

    @Test
    void noMatchReturnsMessage(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.txt");
        Files.writeString(file, "hello world");
        var tool = GrepTool.create();
        var result = tool.execute("id1", new GrepTool.GrepInput(
            "NOTFOUND", Optional.of(file.toString()), Optional.empty()),
            null, null, contextFor(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("No matches");
    }
}
