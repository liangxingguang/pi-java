package com.pijava.ai.stream;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑥（docs/33）：工具调用在**流式起点**就带身份。
 *
 * <p>pi 五条车道都在发出 {@code toolcall_start} 之前**先把块建好塞进 content**
 * （{@code anthropic-messages.ts:648-660}、{@code openai-completions.ts:497-536}、
 * {@code google-generative-ai.ts}、{@code mistral-conversations.ts}、
 * {@code openai-responses.ts}）⇒ pi 的起点 {@code partial} 里那个位置**已经是**
 * 带 {@code id}/{@code name} 的 toolCall 块。pi-java 此前插的是空占位
 * {@code ToolUseContent("", "", {})}。</p>
 */
class StreamPartialBuilderToolIdentityTest {

    @Test
    void toolCallStartSeedsIdentityBlockBeforeSnapshot() {
        var builder = new StreamPartialBuilder();
        builder.emitStart();

        var start = builder.emitToolCallStart("call_1", "write");

        var content = start.partial().content();
        assertThat(content).hasSize(1);
        assertThat(content.get(start.contentIndex()))
            .isEqualTo(new ContentBlock.ToolUseContent("call_1", "write", Map.of()));
    }

    @Test
    void unknownIdAndNameBecomeEmptyStringsOnTheBlock() {
        // P6：completions 车道允许起点块只有 id、name 稍后补 ⇒ 「起点 name 是空串」
        // 是 pi 自己也有的形状（openai-completions.ts:497-529 + :534-536）。
        // null 与 "" 落到线上必须是**同一个**形状，不能出现 null 块组件。
        var builder = new StreamPartialBuilder();
        builder.emitStart();

        var start = builder.emitToolCallStart(null, null);

        var block = (ContentBlock.ToolUseContent) start.partial().content().get(0);
        assertThat(block.id()).isEmpty();
        assertThat(block.name()).isEmpty();
    }

    @Test
    void argumentStreamKeepsTheNameSeededAtStart() {
        // 隐性偏差回归：emitToolCallDelta 从 toolCallId/toolCallName 重建块，
        // 而 toolCallName 此前**只有 emitToolCallEnd 才写** ⇒ 整个参数流期间
        // 块上的 name 恒为空串（前端拿不到工具名）。
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        builder.emitToolCallStart("call_1", "write");

        var delta = builder.emitToolCallDelta("call_1", "{\"path\":\"a.txt\"}");

        var blocks = delta.partial().content();
        assertThat(blocks).hasSize(1);
        var block = (ContentBlock.ToolUseContent) blocks.get(0);
        assertThat(block.id()).isEqualTo("call_1");
        assertThat(block.name()).isEqualTo("write");
        assertThat(block.arguments()).containsEntry("path", "a.txt");
    }

    @Test
    void toolCallStartIndexPointsAtTheSeededBlock() {
        // 起点事件与快照必须指同一个位置（此前 contentIndex 与 blocks.size()
        // 是对齐的，本次改动不能打破它）。
        var builder = new StreamPartialBuilder();
        builder.emitStart();
        builder.emitTextStart();
        builder.emitTextDelta(" preamble ");

        var start = builder.emitToolCallStart("call_9", "bash");

        assertThat(start.contentIndex()).isEqualTo(1);
        assertThat(start.partial().content()).hasSize(2);
        assertThat(start.partial().content().get(1))
            .isInstanceOf(ContentBlock.ToolUseContent.class);
        assertThat(((ContentBlock.ToolUseContent) start.partial().content().get(1)).name())
            .isEqualTo("bash");
    }
}
