package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;

/**
 * Structured lane metadata used within snapshots.
 * Note: different from {@code com.pijava.agent.session.LaneInfo} which is a storage-layer type.
 */
public record LaneInfo(
    String name,
    String leafId,
    OperationInfo operation
) {
    /** Information about the current operation on this lane. */
    public record OperationInfo(
        String id,
        String kind,   // "run" | "compaction" | "navigation"
        String status  // "running" | "suspended" | "aborting"
    ) {}

    /** Snapshot of the three scheduling queues. */
    public record Queues(
        List<QueuedItem> steer,
        List<QueuedItem> followUp,
        List<QueuedItem> nextRun
    ) {}

    /**
     * A queued steer/followUp/nextRun item.
     *
     * <p>批 26（docs/37 T1，裁决⑧）：pi 的 {@code agent.steer/followUp} 收任意
     * {@code AgentMessage}（含 custom）；java 队列原为纯文本形，扩为两变体——
     * {@link Text} 是既有文本 prompt，{@link Custom} 携扩展 custom 消息载荷
     * （pi {@code CustomMessage} 四件：customType/content/display/details）。
     * custom 项排空时以 user 投影进 LLM 上下文（{@link CustomMessageContent#toBlocks()}
     * 先例），落盘臂经 {@code LaneState.customOrigins} 身份通道落成
     * {@code Entry.CustomMessage}。</p>
     */
    public sealed interface QueuedItem {

        /** Queue-local sequence number. */
        long seq();

        /** A queued text prompt (the original shape). */
        record Text(String prompt, List<PromptImage> images, long seq) implements QueuedItem {
            /** Defensively copies {@code images}. */
            public Text {
                images = List.copyOf(images);
            }
            /** Plain-text convenience constructor (no images). */
            public Text(String prompt, long seq) {
                this(prompt, List.of(), seq);
            }
        }

        /**
         * A queued extension custom message（pi {@code agent.steer(appMessage)} 的
         * custom 臂）。{@code content} 为 null ≙ pi 的 {@code content ?? []}（入队
         * 归一为空 Blocks）。
         */
        record Custom(String customType, CustomMessageContent content, boolean display,
                      Map<String, Object> details, long seq) implements QueuedItem {
            /** Normalizes null content/details (pi ingestion-time {@code ?? []}). */
            public Custom {
                if (content == null) {
                    content = new CustomMessageContent.Blocks(List.of());
                }
                details = details == null ? null : Map.copyOf(details);
            }
        }
    }
}
