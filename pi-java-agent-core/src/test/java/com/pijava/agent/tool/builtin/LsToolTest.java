package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.pijava.agent.tool.*;
import com.pijava.ai.message.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class LsToolTest {

    @Test
    void listsDirectoryContents(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "a");
        Files.writeString(tmp.resolve("b.txt"), "b");
        var tool = LsTool.create();
        var result = tool.execute("id1", new LsTool.LsInput(
            Optional.empty(), Optional.empty()),
            null, null, TestContexts.at(tmp));
        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("a.txt", "b.txt");
    }
}
