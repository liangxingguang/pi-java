package com.pijava.ai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

class TranscriptContextTest {

    @Test
    void systemMessageCarriesItsNormalizedFields() {
        var system = new Message.SystemMessage(
            "base prompt", Instant.EPOCH, Map.of(), List.of(), List.of());

        assertThat(system.role()).isEqualTo("system");
        assertThat(system.content()).containsExactly(new ContentBlock.TextContent("base prompt"));
        assertThat(system.timestamp()).isEqualTo(Instant.EPOCH);
        assertThat(system.sections()).isEmpty();
        assertThat(system.toolsAdded()).isEmpty();
        assertThat(system.toolsRemoved()).isEmpty();

        assertThat(new TranscriptContext(List.of(system)).messages())
            .containsExactly(system);
    }

    @Test
    void systemMessageAndTranscriptContextDefensivelyCopyCollections() {
        var sections = new HashMap<String, String>();
        sections.put("intro", "Base");
        var added = new ArrayList<ToolDefinition>();
        added.add(new ToolDefinition("read", "Read files", Map.of("type", "object")));
        var removed = new ArrayList<ToolReference>();
        removed.add(new ToolReference("write"));
        var system = new Message.SystemMessage("prompt", Instant.EPOCH,
            sections, added, removed);
        var messages = new ArrayList<Message>();
        messages.add(system);
        var context = new TranscriptContext(messages);

        sections.put("later", "changed");
        added.clear();
        removed.clear();
        messages.add(new Message.UserMessage(List.of(new ContentBlock.TextContent("later"))));

        assertThat(system.sections()).containsExactly(Map.entry("intro", "Base"));
        assertThat(system.toolsAdded()).hasSize(1);
        assertThat(system.toolsRemoved()).containsExactly(new ToolReference("write"));
        assertThat(context.messages()).containsExactly(system);
    }

    @Test
    void nullCollectionsAndContentBecomeImmutableEmptyValues() {
        var system = new Message.SystemMessage((String) null, null, null, null, null);
        var blockless = new Message.SystemMessage(
            (List<ContentBlock>) null, Instant.EPOCH, null, null, null);
        var context = new TranscriptContext(null);

        // 空文本走「一个空文本块」而不是空列表 —— 见便捷构造器的 javadoc
        // （pi 的 content 必填，createInitialSystemMessage 写的是 systemPrompt ?? ""）。
        assertThat(system.content()).containsExactly(new ContentBlock.TextContent(""));
        assertThat(system.timestamp()).isNull();
        assertThat(system.sections()).isEmpty();
        assertThat(system.toolsAdded()).isEmpty();
        assertThat(system.toolsRemoved()).isEmpty();

        // 规范化构造器里的 null 块列表才是「没有内容」。
        assertThat(blockless.content()).isEmpty();
        assertThat(context.messages()).isEmpty();
    }
}
