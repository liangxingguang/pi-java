package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * 溢出恢复的持久省略 —— pi {@code agent-session.ts:1208-1225}
 * 的 {@code _omitRecoveryAttempt}。
 *
 * <p>在自动压缩前，给失败助手及其本轮工具结果各追加一条
 * {@code replacement:null} 的 {@link Entry.ContextEdit}，让它们<b>永久</b>退出
 * 模型上下文；随后按 {@code _refreshFinalizedContext}（:1224）重建工作副本，
 * 使 {@code contextTokens} 与切点规划读到剔除后的投影。日志原文保留。</p>
 *
 * <p>error 助手在投影里本就被 stopReason 过滤（{@code ContextEntries.project}），
 * edit 真正额外剔除的是<b>工具结果</b>（它们无 stopReason 过滤、照常投影）。</p>
 *
 * <p><b>定位规则对齐 {@code _findPersistedMessageEntryId}（:1185-1206）</b>：
 * 先按对象身份在 transcript 中匹配；落空时，目标在工作副本中的身份下标经投影
 * 反查源条目 id（B170，{@code docs/20}）；目标在副本里却仍解析不到 ⇒ 抛 pi 的
 * 固定错误；既不在 transcript 也不在工作副本 ⇒ 静默跳过（无可省略物）。
 * pi 第一档 WeakMap 缓存不移植：其值全部由后两档同源推出（{@code docs/20 §7}）。</p>
 */
final class RecoveryOmissions {

    private RecoveryOmissions() {}

    /** Persist omission edits for the failed assistant and this turn's tool results, then rebuild the copy. */
    static void persist(LaneState lane, Message.AssistantMessage failed) {
        String assistantId = resolveEntryId(lane, failed);
        if (assistantId == null && inWorkingCopy(lane, failed)) {
            // pi :1216-1218：投影中的消息没有源条目 ⇒ 无法安全持久省略。
            throw new IllegalStateException(
                "Cannot persist recovery omission because a projected message has no source entry");
        }
        if (assistantId != null) {
            appendOmission(lane, assistantId);
        }
        for (String toolResultId : toolResultEntryIds(lane, failed)) {
            appendOmission(lane, toolResultId);
        }
        // pi :1224 _refreshFinalizedContext：压缩前让取数与切点读到剔除后的上下文。
        HarnessUtils.rebuildLaneMessages(lane);
    }

    /**
     * Persist the omission of only the failed assistant for the auto-retry path
     * (B172, {@code docs/19}；pi {@code _prepareRetry → _omitRecoveryAttempt(message)}，
     * {@code agent-session.ts:3738})：环 A 不传 toolResults，守卫/定位规则同
     * {@link #persist}，随后刷新工作副本。
     */
    static void persistRetryOmission(LaneState lane, Message.AssistantMessage failed) {
        String assistantId = resolveEntryId(lane, failed);
        if (assistantId == null && inWorkingCopy(lane, failed)) {
            // pi :1216-1218：投影中的消息没有源条目 ⇒ 无法安全持久省略。
            throw new IllegalStateException(
                "Cannot persist recovery omission because a projected message has no source entry");
        }
        if (assistantId != null) {
            appendOmission(lane, assistantId);
        }
        // pi _refreshFinalizedContext（:1224）：退避前让后续读取看到剔除后的投影。
        HarnessUtils.rebuildLaneMessages(lane);
    }

    /**
     * Whether the target is the <b>same instance</b> in the working copy.
     * Must use reference identity, not {@link java.util.List#contains}: the
     * messages are records with value equality, but pi's JS
     * {@code state.messages.includes(message)} is object identity — two
     * component-equal messages are different messages here.
     */
    private static boolean inWorkingCopy(LaneState lane, Message target) {
        for (Message m : lane.messages) {
            if (m == target) {
                return true;
            }
        }
        return false;
    }

    /**
     * pi {@code _findPersistedMessageEntryId}（agent-session.ts:1185-1206）：
     * 先反向身份扫描，落空再走投影下标兜底。
     */
    private static String resolveEntryId(LaneState lane, Message target) {
        String identity = identityEntryId(lane, target);
        return identity != null ? identity : projectedEntryId(lane, target);
    }

    /** Find an entry id whose message is the same object (pi :1190-1194 reverse identity scan). */
    private static String identityEntryId(LaneState lane, Message target) {
        for (int i = lane.transcript.size() - 1; i >= 0; i--) {
            if (lane.transcript.get(i) instanceof Entry.Message m && m.message() == target) {
                return m.id();
            }
        }
        return null;
    }

    /**
     * 第三档（pi :1191-1205）：目标是工作副本中的实例时，按其身份下标经投影
     * 反查贡献该位置的源条目 id。{@code lane.messages} 与投影扁平视图同源
     * （{@link HarnessUtils#rebuildLaneMessages}）。
     */
    private static String projectedEntryId(LaneState lane, Message target) {
        int messageIndex = -1;
        for (int i = 0; i < lane.messages.size(); i++) {
            // pi indexOf 是对象身份：消息是值相等的 record，不能用 List.indexOf。
            if (lane.messages.get(i) == target) {
                messageIndex = i;
                break;
            }
        }
        if (messageIndex < 0) {
            return null;
        }
        List<ContextEntries.ProjectedEntry> projected =
            ContextEntries.projectEntries(lane.transcript, HarnessUtils.lastEntryId(lane));
        int projectedIndex = 0;
        for (ContextEntries.ProjectedEntry entry : projected) {
            for (int i = 0; i < entry.messages().size(); i++) {
                if (projectedIndex == messageIndex) {
                    return entry.source().id();
                }
                projectedIndex++;
            }
        }
        return null;
    }

    /**
     * Tool results of this turn (matched by the assistant's tool_use ids) that
     * landed in the transcript. pi passes the turn's {@code toolResults} (:2996);
     * resolving via tool_use ids covers exactly the results that can project.
     */
    private static List<String> toolResultEntryIds(LaneState lane, Message.AssistantMessage failed) {
        Set<String> toolUseIds = new HashSet<>();
        for (ContentBlock block : failed.content()) {
            if (block instanceof ContentBlock.ToolUseContent toolUse) {
                toolUseIds.add(toolUse.id());
            }
        }
        List<String> ids = new ArrayList<>();
        for (Entry entry : lane.transcript) {
            if (entry instanceof Entry.Message m
                    && m.message() instanceof Message.ToolResultMessage tool
                    && toolUseIds.contains(tool.toolUseId())) {
                ids.add(m.id());
            }
        }
        return ids;
    }

    private static void appendOmission(LaneState lane, String targetId) {
        // 追加走 D6 的车道锁点（取序号/叶在监视器内）；不标 deferred：
        // 这些条目紧接着由压缩整体替换承接，随本次 run 的写链落盘。
        lane.appendEntry((seq, parentId) -> new Entry.ContextEdit(
            UUID.randomUUID().toString(), seq, parentId, Instant.now(),
            targetId, null));
    }
}
