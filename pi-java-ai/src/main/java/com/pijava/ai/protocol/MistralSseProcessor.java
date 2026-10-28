package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.stream.ToolCallBuilder;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * Mistral 车道的 SSE 帧处理簇（从 {@code MistralConversationsApi} 拆出，
 * 结构债拆分——纯移动零行为变化）：{@code currentBlock}/{@code stopReason}
 * 两件套状态、线格 finish_reason 映射、逐帧处理。唯一实例依赖
 * （{@code streamEventHook}）经构造器注入。
 */
final class MistralSseProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderHooks.StreamEventHook streamEventHook;

    MistralSseProcessor(ProviderHooks.StreamEventHook streamEventHook) {
        this.streamEventHook = streamEventHook;
    }

    /**
     * pi {@code mistral-conversations.ts:583} 的 {@code currentBlock}：当前开着的内容块
     * 只有 NONE/TEXT/THINKING 三态（B80 前只有一个 boolean 文本槽）。
     *
     * <p>异型到达（text→thinking 或反向；GLM 还会在<b>同一个事件</b>里切，
     * {@code [thinking(…), {text:…}]}）先 {@link #finish} 旧块（发 {@code *_end}）
     * 再发新块的 {@code *_start} —— 对应 pi :652-655/:674-678/:693-697；
     * 工具调用到达即关块（pi :712-715）；流末统一收尾（pi :759）。</p>
     */
    static final class ContentState {

        private enum Open { NONE, TEXT, THINKING }

        private Open current = Open.NONE;

        /** 切到目标块型：同型空转；异型先关旧块再开新块（pi 的 finish＋push start）。 */
        void switchTo(Open target, StreamPartialBuilder builder,
                      SubmissionPublisher<StreamEvent> publisher) {
            if (current == target) {
                return;
            }
            finish(builder, publisher);
            current = target;
            switch (target) {
                case TEXT -> publisher.submit(builder.emitTextStart());
                case THINKING -> publisher.submit(builder.emitThinkingStart());
                case NONE -> { }
            }
        }

        /** pi {@code :588-607} 的 finishCurrentBlock：text→text_end，thinking→thinking_end。 */
        void finish(StreamPartialBuilder builder,
                    SubmissionPublisher<StreamEvent> publisher) {
            switch (current) {
                case TEXT -> publisher.submit(builder.emitTextEnd());
                case THINKING -> publisher.submit(builder.emitThinkingEnd());
                case NONE -> { }
            }
            current = Open.NONE;
        }
    }

    /** pi 的 {@code output.stopReason} + {@code output.errorMessage} 两件套。 */
    static final class StopState {
        // 初值 "pending"（mistral-conversations.ts:222）：收尾的「没观测到 finish reason」哨兵。
        StopReason reason = StopReason.PENDING;
        String errorMessage;
    }

    /**
     * 线格取值 → pi 的 {@code StopReason}（pi {@code mistral-conversations.ts:926-941}）。
     *
     * <p>⚠️ 与 Google 车道的**形状差异**：Mistral 把文案**做进映射**（{@code error} 与
     * {@code default} 两支都自带 {@code Provider stopped with: …}），而 Google 的映射只返回
     * 裸 {@link StopReason#ERROR}、文案由收尾处用 {@code rawStopReason} 拼。两处都照 pi 写，
     * 别「统一」。</p>
     *
     * <p>⚠️ 与 completions / Google 又不同的一处：本车道的未知取值 pi **不抛**（落 error 事件），
     * 与 completions 同向、与 Anthropic / Google 反向。</p>
     */
    static MappedStopReason mapChatStopReason(String reason) {
        if (reason == null) {
            // pi 的 `if (reason === null) return {stopReason:"stop"}`（`:927`）。调用点的
            // 真值守卫已挡掉 null 与空串 ⇒ 流路径上不可达，留着是为了与 pi 逐行同形。
            return new MappedStopReason(StopReason.STOP, null);
        }
        return switch (reason) {
            case "stop" -> new MappedStopReason(StopReason.STOP, null);
            case "length", "model_length" -> new MappedStopReason(StopReason.LENGTH, null);
            case "tool_calls" -> new MappedStopReason(StopReason.TOOL_USE, null);
            // pi mistral-conversations.ts:948（7fb59f995）：补 " (server error)" 让 retry
            // 白名单的 server.?error 抓手生效 —— Mistral 以此报告瞬态服务失败，应可重试。
            case "error" -> new MappedStopReason(StopReason.ERROR,
                "Provider stopped with: error (server error)");
            default -> new MappedStopReason(StopReason.ERROR, "Provider stopped with: " + reason);
        };
    }

    /** 映射结果：pi 的 {@code { stopReason, errorMessage? }}。 */
    record MappedStopReason(StopReason reason, String errorMessage) {}

    @SuppressWarnings("unchecked") // SSE response JSON parsing with generic Map types
    void processSseData(String data,
                          SubmissionPublisher<StreamEvent> publisher,
                          StreamPartialBuilder builder,
                          Map<String, ToolCallBuilder> toolBuilders,
                          ContentState contentState,
                          StopState stop,
                          ModelInfo model) {
        try {
            var json = MAPPER.readValue(data, Map.class);
            // docs/17（pi mc.ts:611）：解析后 chunk、逐字段映射前（pi 喂的是解析后
            // JSON chunk ⇒ java 对位为解析出的 Map，不是 SSE 原文行）。
            if (streamEventHook != null) {
                streamEventHook.onEvent(json, model);
            }

            // Usage —— 位置照 pi：usage 应用（{@code :596-611}）在「choices 空帧早退」
            // （{@code :613} 的 `if (!choice) continue`）**之前**。两个后果：
            // ① Mistral 真会把 usage 放在终局 {@code choices: []} 帧上 —— 早退在前会
            //    整帧丢掉（修复前的形状，M5fix 探针实测恰红这一条）；
            // ② 同帧既有内容又有 usage 时，UsageInfo 排在本帧 delta **之前** —— 与 pi
            //    的 partial 可见性同向（pi 先写 output.usage，后 push 的 delta 带着它）。
            //    UsageInfo 本身是 pi-java 自有的投影（pi 无 usage 事件）。
            var usage = (Map<String, Object>) json.get("usage");
            if (usage != null) {
                publisher.submit(builder.emitUsage(MistralUsage.parse(usage, model)));
            }

            var choices = (List<Map<String, Object>>) json.get("choices");
            if (choices == null || choices.isEmpty()) return;

            var choice = choices.get(0);
            // delta 缺失不再整段早退：终帧「只有 finish_reason、没有 delta」是合法形状，
            // 原先那道 `if (delta == null) return;` 会把它的取值整块丢掉。
            var delta = (Map<String, Object>) choice.get("delta");
            if (delta != null) {
                // B80（pi :643-708）：delta.content 既可能是串也可能是块数组 ——
                // 串 / {type:"thinking"} / {type:"text"} 三支，未知 type 静默忽略。
                Object contentNode = delta.get("content");
                if (contentNode != null) {
                    // pi :644 的 typeof 归一：串→[串]；数组逐项；既非串也非数组（理论不可达）按空。
                    List<?> items = contentNode instanceof String s
                            ? List.of(s)
                            : contentNode instanceof List<?> l ? l : List.of();
                    for (Object item : items) {
                        switch (MistralContentDelta.ofItem(item)) {
                            case MistralContentDelta.Text(var raw) -> {
                                // pi :647-650：先净化再判空（GLM 在 thinking/工具调用周围发的
                                // 空串不许开块，否则 thinking 被切成多块、Mistral 重放会拒）。
                                var textDelta = SanitizeUnicode.surrogates(raw);
                                if (textDelta.isEmpty()) break;
                                contentState.switchTo(ContentState.Open.TEXT, builder, publisher);
                                publisher.submit(builder.emitTextDelta(textDelta));
                            }
                            case MistralContentDelta.Thinking(var raw) -> {
                                // pi :667-687：parts 已在 MistralContentDelta 拼接、过滤非空。
                                var thinkingDelta = SanitizeUnicode.surrogates(raw);
                                if (thinkingDelta.isEmpty()) break;
                                contentState.switchTo(
                                        ContentState.Open.THINKING, builder, publisher);
                                publisher.submit(builder.emitThinkingDelta(thinkingDelta));
                            }
                            case MistralContentDelta.Ignored ignored -> { }
                        }
                    }
                }

                // Tool call delta —— pi :712-715：**首个**工具调用到达即关当前文本/思考块
                // （关闭在 toolCall 循环体内，空列表不触发 ⇒ 不能无条件 finish）。
                var toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    contentState.finish(builder, publisher);
                    for (var tc : toolCalls) {
                        var index = String.valueOf(tc.getOrDefault("index", "0"));
                        var tcId = (String) tc.get("id");
                        var function = (Map<String, Object>) tc.get("function");
                        if (function == null) continue;

                        var name = (String) function.get("name");
                        var args = (String) function.get("arguments");

                        var toolBuilder = toolBuilders.computeIfAbsent(index,
                                k -> new ToolCallBuilder());

                        if (tcId != null && name != null && !toolBuilder.isStarted()) {
                            toolBuilder.start(tcId, name);
                            // 包⑥：起点即带身份（上行的 null 判定已保证两者非空）。
                            publisher.submit(builder.emitToolCallStart(tcId, name));
                        }
                        if (args != null) {
                            toolBuilder.append(args);
                            publisher.submit(builder.emitToolCallDelta(
                                    toolBuilder.id(), args));
                        }
                    }
                }
            }

            // Finish reason —— 放在 delta 处理**之后**（pi 是之前，`mistral-conversations.ts:613`）：
            // 下面那步「toolUse 补发 ToolCallEnd」要看工具块是否已完整，而工具块是上面刚折进来
            // 的 ⇒ 顺序反了会把同帧的 tool_call 终帧漏掉。**刻意偏差**，只此一处。
            var reason = (String) choice.get("finish_reason");
            if (reason != null && !reason.isEmpty()) {
                // pi `:614`：原值先落消息（⑨/D5），映射结果再落 stop.reason。
                builder.noteRawStopReason(reason);
                var mapped = mapChatStopReason(reason);
                stop.reason = mapped.reason();
                stop.errorMessage = mapped.errorMessage();
                if (stop.reason == StopReason.TOOL_USE) {
                    for (var tb : toolBuilders.values()) {
                        if (tb.isComplete()) {
                            publisher.submit(builder.emitToolCallEnd(
                                    tb.id(), tb.name()));
                        }
                    }
                }
            }

        } catch (JsonProcessingException e) {
            // Skip unparseable data lines
        }
    }
}
