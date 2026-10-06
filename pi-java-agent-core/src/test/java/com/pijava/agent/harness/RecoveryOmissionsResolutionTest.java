package com.pijava.agent.harness;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B170（{@code docs/20}）：省略目标定位的投影下标兜底 —— pi
 * {@code _findPersistedMessageEntryId}（{@code agent-session.ts:1185-1206}）
 * 第三档。
 *
 * <p>夹具形状：transcript 中给失败助手挂一条 <b>replacement 非 null</b> 的
 * context_edit 后重建副本，副本里的助手即与 transcript 载荷不同实例的投影
 * 副本 —— 身份扫描必落空，只能按下标反查源条目。</p>
 */
class RecoveryOmissionsResolutionTest {

    @Test
    void projectedAssistantCopyResolvesViaIndexToSourceEntry() {
        var lane = new LaneState();
        Instant now = Instant.now();
        var assistant = textAssistant("orig", now);
        lane.transcript.add(message("e-user", 0, null, now, user("hello")));
        lane.transcript.add(message("e-asst", 1, "e-user", now, assistant));
        lane.transcript.add(replacementEdit("ce-replace", "e-asst", 2, "e-asst", "edited text", now));
        HarnessUtils.rebuildLaneMessages(lane);
        var target = (Message.AssistantMessage) lane.messages.get(1);
        assertThat(target).as("副本里是投影副本，不是 transcript 载荷").isNotSameAs(assistant);

        RecoveryOmissions.persistRetryOmission(lane, target);

        assertThat(lane.transcript).hasSize(4);
        var omission = (Entry.ContextEdit) lane.transcript.get(3);
        assertThat(omission.targetId())
            .as("下标兜底定位到助手源条目").isEqualTo("e-asst");
        assertThat(omission.replacement())
            .as("replacement:null ⇒ 剔除").isNull();
        assertThat(lane.messages)
            .as("重建后助手退出副本").hasSize(1);
        assertThat(lane.messages.get(0)).isInstanceOf(Message.UserMessage.class);
    }

    @Test
    void projectedAssistantCopyInOverflowPersistResolvesViaIndex() {
        var lane = new LaneState();
        Instant now = Instant.now();
        var assistant = textAssistant("orig", now);
        lane.transcript.add(message("e-user", 0, null, now, user("hello")));
        lane.transcript.add(message("e-asst", 1, "e-user", now, assistant));
        lane.transcript.add(replacementEdit("ce-replace", "e-asst", 2, "e-asst", "edited text", now));
        HarnessUtils.rebuildLaneMessages(lane);
        var target = (Message.AssistantMessage) lane.messages.get(1);

        // 溢出路径：助手无 tool call ⇒ 只落助手自己一条 omission edit。
        RecoveryOmissions.persist(lane, target);

        var nullEdits = lane.transcript.stream()
            .filter(Entry.ContextEdit.class::isInstance)
            .map(Entry.ContextEdit.class::cast)
            .filter(edit -> edit.replacement() == null)
            .toList();
        assertThat(nullEdits).hasSize(1);
        assertThat(nullEdits.get(0).targetId()).isEqualTo("e-asst");
    }

    @Test
    void valueEqualAssistantEarlierInCopyDoesNotStealTheIndex() {
        // pi indexOf 是对象身份：副本中更早位置有一条值相等的助手，不得抢下标。
        var lane = new LaneState();
        Instant now = Instant.now();
        var first = textAssistant("same", now);
        var second = textAssistant("same", now);
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotSameAs(second);
        lane.transcript.add(message("e-user", 0, null, now, user("hello")));
        lane.transcript.add(message("e-a1", 1, "e-user", now, first));
        lane.transcript.add(message("e-a2", 2, "e-a1", now, second));
        lane.transcript.add(replacementEdit("ce2", "e-a2", 3, "e-a2", "same", now));
        HarnessUtils.rebuildLaneMessages(lane);
        assertThat(lane.messages).hasSize(3);
        var target = (Message.AssistantMessage) lane.messages.get(2);
        assertThat(target).isNotSameAs(second);

        RecoveryOmissions.persistRetryOmission(lane, target);

        var omission = (Entry.ContextEdit) lane.transcript.get(4);
        assertThat(omission.targetId())
            .as("身份下标跳过值相等的 e-a1，定位 e-a2").isEqualTo("e-a2");
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message.AssistantMessage textAssistant(String text, Instant timestamp) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)),
            null, null, null, null, null, null, timestamp, null, null);
    }

    private static Entry.Message message(String id, long seq, String parentId,
                                         Instant now, Message payload) {
        return new Entry.Message(id, seq, parentId, now, payload, null);
    }

    private static Entry.ContextEdit replacementEdit(String id, String parentId, long seq,
                                                     String targetId, String text, Instant now) {
        return new Entry.ContextEdit(id, seq, parentId, now, targetId,
            new Entry.ContextEdit.Replacement(CustomMessageContent.of(text)));
    }
}
