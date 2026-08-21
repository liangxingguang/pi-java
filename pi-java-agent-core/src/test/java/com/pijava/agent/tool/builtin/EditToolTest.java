package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class EditToolTest {

    @Test
    void replacesText(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("edit.txt");
        Files.writeString(file, "Hello World");
        var tool = EditTool.create();
        tool.execute("id1", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("World", "Java"))),
            null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("Hello Java");
    }

    @Test
    void createsBackupFile(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("edit.txt");
        Files.writeString(file, "original");
        var tool = EditTool.create();
        tool.execute("id1", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("original", "modified"))),
            null, null, TestContexts.at(tmp));
        assertThat(Files.exists(Path.of(file.toString() + ".bak"))).isTrue();
        assertThat(Files.readString(Path.of(file.toString() + ".bak"))).isEqualTo("original");
    }

    @Test
    void resultCarriesDiffBlockForUi(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("edit.txt");
        Files.writeString(file, "Hello World");
        var tool = EditTool.create();
        var result = tool.execute("id1", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("World", "Java"))),
            null, null, TestContexts.at(tmp));

        assertThat(result.content()).hasSize(2);
        assertThat(result.content().get(0))
            .isInstanceOf(com.pijava.ai.message.ContentBlock.TextContent.class);
        assertThat(result.content().get(1))
            .isInstanceOf(com.pijava.ai.message.ContentBlock.DiffContent.class);
        var diff = (com.pijava.ai.message.ContentBlock.DiffContent) result.content().get(1);
        assertThat(diff.diffText()).contains("- Hello World").contains("+ Hello Java");
    }
}
