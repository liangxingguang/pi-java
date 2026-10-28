package com.pijava.tui.screen;

import com.pijava.agent.entry.Entry;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.ChatPanel;
import com.pijava.tui.component.MetaKind;

/**
 * 批 29（E2 结构债拆分，ChatScreen 簇①通知面纯移动——行为零变化）：cache
 * 暖用量行／cache miss 通知的收口——门控（{@code showCacheMissNotices} 晚绑定
 * 现读）＋渲染＋增量去重态（暖条目 id 集、miss 跟踪器、即时臂已通知消息）。
 * {@code outputPad} 现读源由 ChatScreen 以供应器注入（现读其字段，同原
 * this.outputPad 直读语义）。
 */
final class CacheNoticeRenderer {

    private final ChatPanel chatPanel;
    private final java.util.function.IntSupplier outputPad;
    // 批 13（docs/24 T10/T11）：showCacheMissNotices 门（pi interactive-mode.ts
    // :4111/:4185 逐字——晚绑定现读，pi 每次渲染现查同）＋即时臂已上屏的暖条目 id
    // （java 增量追加特有的去重——pi 是重建式渲染，无此问题）。
    private java.util.function.BooleanSupplier showCacheMissNotices = () -> false;
    private final java.util.Set<String> liveUsageNoticeIds = new java.util.HashSet<>();
    // 批 13（T11）：miss 检测的增量跟踪器（裁决⑤）＋即时臂已通知的消息（onEntry
    // 同实例去重——pi 无此问题：pi 重建式渲染；java 是增量追加）。
    private com.pijava.tui.util.CacheMissTracker cacheMissTracker =
        com.pijava.tui.util.CacheMissTracker.noModels();
    private com.pijava.ai.message.Message.AssistantMessage lastMissNotifiedMessage;

    CacheNoticeRenderer(ChatPanel chatPanel, java.util.function.IntSupplier outputPad) {
        this.chatPanel = chatPanel;
        this.outputPad = outputPad;
    }

    /** 批 13（T10/T11）：{@code showCacheMissNotices} 现读源（pi 每次渲染现查同）。 */
    void setShowCacheMissNoticesSupplier(java.util.function.BooleanSupplier supplier) {
        showCacheMissNotices = supplier == null ? () -> false : supplier;
    }

    /** 批 13（T11）：miss 检测的目录查询源（provider+"/"+model ⇒ {@code catalog.find}）。 */
    void setCacheMissTrackerModels(
            java.util.function.Function<String, java.util.Optional<com.pijava.ai.catalog.ModelInfo>> models) {
        cacheMissTracker = new com.pijava.tui.util.CacheMissTracker(
            models == null ? key -> java.util.Optional.empty() : models);
    }

    /**
     * 批 13（T10，pi interactive-mode.ts:4110-4115 对位）：暖用量行的唯一收口——
     * 门控（{@code showCacheMissNotices} 晚绑定现读）＋渲染（from 是纯投影，
     * 门在调用点，批 12 T6 的 pad 参数同构）。非 cache_warm kind 由 from 滤掉。
     */
    void appendUsageNotice(Entry.Usage usage) {
        if (!showCacheMissNotices.getAsBoolean()) {
            return;
        }
        var bubble = ChatMessage.from(usage, outputPad.getAsInt());
        if (bubble != null) {
            chatPanel.append(bubble);
        }
    }

    /**
     * 批 13（T11，pi interactive-mode.ts:4193-4206 逐字）：miss 通知行——
     * 门：{@code missedTokens<20_000 且 missedCost<0.1 ⇒ 不显示}；label 三态
     * （模型切换／idle 超 TTL／普通）。（pi 用 warning 主题色＋前置 Spacer；java
     * System 气泡无主题/Spacer 面——与既有 System 通知同格，风格差随 R 域既有口径。）
     */
    void appendCacheMissNotice(com.pijava.coding.agent.core.CacheStats.CacheMiss miss) {
        if (miss.missedTokens() < 20_000 && miss.missedCost() < 0.1) {
            return;
        }
        var cost = miss.missedCost() >= 0.01
            ? " (~$" + String.format(java.util.Locale.ROOT, "%.2f", miss.missedCost()) + ")"
            : "";
        var reBilled = com.pijava.coding.agent.core.CacheWarmingFormatting.formatTokens(
            miss.missedTokens()) + " tokens re-billed" + cost;
        String label = "Cache miss";
        if (miss.modelChanged()) {
            label = "Cache miss after model switch";
        } else if (miss.idleMs() >= com.pijava.coding.agent.core.CacheStats.CACHE_TTL_MS) {
            label = "Cache miss after " + Math.round(miss.idleMs() / 60_000.0) + "m idle";
        }
        chatPanel.append(new ChatMessage.System(label + ": " + reBilled, MetaKind.GENERIC));
    }

    /** miss 跟踪器逐条 feed（onEntry／rebuildTranscript 同序先 feed 再渲染）。 */
    java.util.Optional<com.pijava.coding.agent.core.CacheStats.CacheMiss> feed(Entry entry) {
        return cacheMissTracker.feed(entry);
    }

    /** 即时臂已上屏的暖条目 id 查重（onEntry 的 usage 臂）。 */
    boolean liveNoticeKnown(String id) {
        return liveUsageNoticeIds.contains(id);
    }

    /** 即时臂暖条目 id 登记（SessionEventRouter 的 EntryAppended 臂——onEntry 投递时跳过）。 */
    void markLiveNotice(String id) {
        liveUsageNoticeIds.add(id);
    }

    /** message_end 即时臂的 miss 检测（SessionEventRouter 的 MessageEnd 臂）。 */
    java.util.Optional<com.pijava.coding.agent.core.CacheStats.CacheMiss> detectOnMessageEnd(
            com.pijava.ai.message.Message.AssistantMessage message) {
        return cacheMissTracker.detectOnMessageEnd(message);
    }

    /** miss 通知门现读（条目流臂／重建臂／即时臂共用）。 */
    boolean noticesEnabled() {
        return showCacheMissNotices.getAsBoolean();
    }

    /** 即时臂已通知的同实例判定（条目流臂／重建臂的 {@code am != lastMissNotifiedMessage}）。 */
    boolean isLastMissNotified(com.pijava.ai.message.Message.AssistantMessage message) {
        return message == lastMissNotifiedMessage;
    }

    /** 即时臂通知落位（message_end 臂的 {@code lastMissNotifiedMessage = am}——onEntry 同实例去重）。 */
    void markLastMissNotified(com.pijava.ai.message.Message.AssistantMessage message) {
        lastMissNotifiedMessage = message;
    }

    /** 批 13（T10/T11）：重建即新一轮增量流——去重集与 miss 跟踪器归零。 */
    void reset() {
        liveUsageNoticeIds.clear();
        cacheMissTracker.reset();
    }
}
