package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(diff.diffText()).contains("-1 Hello World").contains("+1 Hello Java");
    }

    @Test
    void fuzzyMatchAppliesWhenExactMissing(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("f.txt");
        Files.writeString(file, "alpha\nbeta"); // clean line, oldText has trailing space
        var tool = EditTool.create();
        tool.execute("id", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("alpha ", "ALPHA"))),
            null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("ALPHA\nbeta");
    }

    @Test
    void preservesCrlfLineEndings(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("f.txt");
        Files.writeString(file, "hello\r\nworld\r\n");
        var tool = EditTool.create();
        tool.execute("id", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("world", "W"))),
            null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("hello\r\nW\r\n");
    }

    @Test
    void preservesBom(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("f.txt");
        Files.writeString(file, "﻿abc");
        var tool = EditTool.create();
        tool.execute("id", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("abc", "ABC"))),
            null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("﻿ABC");
    }

    @Test
    void overlappingEditsFailEndToEnd(@TempDir Path tmp) {
        var file = tmp.resolve("f.txt");
        assertThatThrownBy(() -> {
            Files.writeString(file, "abcd");
            var tool = EditTool.create();
            tool.execute("id", new EditTool.EditInput(file.toString(),
                List.of(new EditTool.Edit("ab", "AB"), new EditTool.Edit("bc", "BC"))),
                null, null, TestContexts.at(tmp));
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleEditsAreApplied(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("f.txt");
        Files.writeString(file, "one\ntwo\nthree");
        var tool = EditTool.create();
        tool.execute("id", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("one", "1"), new EditTool.Edit("three", "3"))),
            null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("1\ntwo\n3");
    }
}
