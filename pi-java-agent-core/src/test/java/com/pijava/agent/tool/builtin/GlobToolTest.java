package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.pijava.agent.tool.*;
import com.pijava.ai.message.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class GlobToolTest {

    @Test
    void matchesJavaFiles(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Test.java"), "class Test{}");
        Files.writeString(tmp.resolve("readme.txt"), "docs");
        var tool = GlobTool.create();
        var result = tool.execute("id1", new GlobTool.GlobInput(
            "*.java", Optional.empty()),
            null, null, TestContexts.at(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("Test.java");
        assertThat(text).doesNotContain("readme.txt");
    }
}
