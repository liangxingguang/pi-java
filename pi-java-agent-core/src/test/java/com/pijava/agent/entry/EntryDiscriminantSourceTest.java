package com.pijava.agent.entry;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.session.SessionJson;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B167（docs/15）：序列化用的判别值（{@code @JsonSubTypes.name}）与
 * Java 侧 {@link Entry#type()} 必须是**同一个事实源**。
 *
 * <p>对 9 个变体各断言：Jackson {@code valueToTree} 产出的 {@code type} 键
 * ＝ {@code entry.type()}。两份一旦漂移（只改一处）这里即红 —— 这正是
 * B167 缺的守卫。</p>
 */
class EntryDiscriminantSourceTest {

    static List<Entry> variants() {
        var block = new ContentBlock.TextContent("x");
        var user = new Message.UserMessage(List.of(block));
        var assistant = new Message.AssistantMessage(List.of(block));
        var tool = new Message.ToolResultMessage("c1", "read", List.of(block), false);
        return List.of(
            new Entry.Message("e", 0, null, Instant.EPOCH, user, null),
            new Entry.ModelChange("e", 0, null, Instant.EPOCH, "p", "m"),
            new Entry.ThinkingLevelChange("e", 0, null, Instant.EPOCH, "high"),
            new Entry.Compaction("e", 0, null, Instant.EPOCH, "s", "e", List.of(), 1, null, null),
            new Entry.BranchSummary("e", 0, null, Instant.EPOCH, "f", "s", java.util.Map.of(), null),
            new Entry.Custom("e", 0, null, Instant.EPOCH, "c", java.util.Map.of()),
            new Entry.CustomMessage("e", 0, null, Instant.EPOCH, "c",
                CustomMessageContent.of("x"), true, null),
            new Entry.Usage("e", 0, null, Instant.EPOCH, "assistant", "p", "m", null,
                null, null, null, null, null, null),
            new Entry.ContextEdit("e", 0, null, Instant.EPOCH, "e2", null));
    }

    @ParameterizedTest
    @MethodSource("variants")
    void serializedTypeMatchesTypeMethod(Entry entry) {
        var tree = SessionJson.mapper().valueToTree(entry);
        assertThat(tree.path("type").asText())
            .as("wire 判别值（@JsonSubTypes）必须等于 entry.type() —— 单一事实源")
            .isEqualTo(entry.type());
    }

    @Test
    void coversAllNineVariants() {
        assertThat(variants()).hasSize(9);
    }
}
