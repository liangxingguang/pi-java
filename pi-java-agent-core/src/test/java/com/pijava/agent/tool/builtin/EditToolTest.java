package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    @Test
    void mixedLineEndingsKeepUntouchedLines(@TempDir Path tmp) throws Exception {
        // 旧实现按首个行尾重写整文件；现在只重写被触及的范围（pi edit.ts:188-201）。
        var file = tmp.resolve("f.txt");
        Files.writeString(file, "a\r\nb\nc\r\nd");
        var tool = EditTool.create();
        tool.execute("id", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("b", "B"))), null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("a\r\nB\nc\r\nd");
    }

    @Test
    void loneCrFileKeepsItsLineEndings(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("f.txt");
        Files.writeString(file, "a\rb\rc");
        var tool = EditTool.create();
        tool.execute("id", new EditTool.EditInput(file.toString(),
            List.of(new EditTool.Edit("b", "B"))), null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("a\rB\rc");
    }

    @Test
    void editsAsJsonStringAreParsed(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("f.txt");
        Files.writeString(file, "a\nb");
        var tool = EditTool.create();
        var input = tool.prepareArguments(Map.of("path", file.toString(),
            "edits", "[{\"oldText\":\"a\",\"newText\":\"A\"},{\"oldText\":\"b\",\"newText\":\"B\"}]"));
        tool.execute("id", input, null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("A\nB");
    }

    @Test
    void singleEditObjectFoldsIntoArray(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("f.txt");
        Files.writeString(file, "a");
        var tool = EditTool.create();
        var input = tool.prepareArguments(Map.of("path", file.toString(),
            "edits", Map.of("oldText", "a", "newText", "A")));
        assertThat(input.edits()).hasSize(1);
        tool.execute("id", input, null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("A");
    }

    @Test
    void legacyOldTextNewTextFoldsIntoEdits(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("f.txt");
        Files.writeString(file, "a\nb");
        var tool = EditTool.create();
        var input = tool.prepareArguments(Map.of("path", file.toString(),
            "edits", List.of(Map.of("oldText", "a", "newText", "A")),
            "oldText", "b", "newText", "B"));
        assertThat(input.edits()).hasSize(2);
        tool.execute("id", input, null, null, TestContexts.at(tmp));
        assertThat(Files.readString(file)).isEqualTo("A\nB");
    }

    @Test
    void editToolTextsArePiVerbatim() {
        var tool = EditTool.create();
        assertThat(tool.description()).isEqualTo(
            "Edit a single file using exact text replacement. Every edits[].oldText must match "
                + "a unique, non-overlapping region of the original file. If two changes affect "
                + "the same block or nearby lines, merge them into one edit instead of emitting "
                + "overlapping edits. Do not include large unchanged regions just to connect "
                + "distant changes.");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) tool.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        var edits = (Map<String, Object>) props.get("edits");
        @SuppressWarnings("unchecked")
        var items = (Map<String, Map<String, Object>>) edits.get("items");
        var itemProps = items.get("properties");
        assertThat(((Map<?, ?>) itemProps.get("oldText")).get("description")).isEqualTo(
            "Exact text for one targeted replacement. It must be unique in the original file "
                + "and must not overlap with any other edits[].oldText in the same call.");
        assertThat(((Map<?, ?>) itemProps.get("newText")).get("description"))
            .isEqualTo("Replacement text for this targeted edit.");
    }
}
