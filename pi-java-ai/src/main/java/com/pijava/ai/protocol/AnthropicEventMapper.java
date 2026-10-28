package com.pijava.ai.protocol;

import com.anthropic.models.messages.RawMessageStreamEvent;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.AllowedFallbackModel;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * Anthropic 车道的 SDK 流事件 → {@link StreamEvent} 映射（从
 * {@code AnthropicMessagesApi} 拆出，结构债拆分——纯移动零行为变化）。
 * 唯一实例依赖（{@code oauth} 身份门）经构造器注入。
 */
final class AnthropicEventMapper {

    /** B81：Claude Code 伪装门（pi {@code isOAuthToken}）——入站工具名 demap 用。 */
    private final boolean oauth;

    AnthropicEventMapper(boolean oauth) {
        this.oauth = oauth;
    }

    StreamEvent mapEvent(RawMessageStreamEvent event,
                          StreamRequest request,
                          StreamPartialBuilder builder,
                          boolean[] isToolBlock,
                          boolean[] isThinkingBlock,
                          String[] pendingToolName,
                          String[] pendingToolId,
                          AnthropicStreamSupport.StopState stop,
                          AnthropicUsageState usageState,
                          java.util.List<AllowedFallbackModel> fallbacks,
                          AnthropicResponseExtras responseExtras,
                          java.util.List<com.pijava.ai.api.ToolDefinition> currentTools) {
        try {
            if (event.isMessageStart()) {
                var startMessage = event.asMessageStart().message();
                // B148（pi :666-690）：responseModel/fallback 计价/input_transformations
                // 三件流内触点收敛在 AnthropicResponseExtras；定价切换必须在本次
                // onMessageStart（首次计价）之前。
                responseExtras.noteStart(builder, request, fallbacks, startMessage);
                usageState.usePricing(responseExtras.servingPricing());
                // pi `:615-625`：**首帧保留** —— 五字段无条件 `|| 0` 写入，注释逐字
                // "This ensures we have input token counts even if the stream is aborted
                // early"。pi 在此**不 push 任何事件**（它没有 usage 事件，usage 是
                // output 对象上的字段）⇒ 本处用 noteUsage 而非 emitUsage，**帧数不变**。
                var u = startMessage.usage();
                Long oneHour = u._cacheCreation().asKnown()
                        .flatMap(c -> c._ephemeral1hInputTokens().asKnown())
                        .orElse(null);
                usageState.onMessageStart(
                        u._inputTokens().asKnown().orElse(null),
                        u._outputTokens().asKnown().orElse(null),
                        u._cacheReadInputTokens().asKnown().orElse(null),
                        u._cacheCreationInputTokens().asKnown().orElse(null),
                        oneHour);
                builder.noteUsage(usageState.usage());
                return null;
            }
            if (event.isContentBlockStart()) {
                var block = event.asContentBlockStart().contentBlock();
                // B148（pi :690-694，探针 AnthropicFallbackBlockProbeTest 已 settle
                // 访问面）：SDK 联合无 "fallback" typed 变体，经 _json() 逃生槽判别。
                // 无已有内容 ⇒ continue（块不入 content、不发事件）；已有内容 ⇒ 抛错，
                // 被本方法 catch 收成 StreamError（errorMessage 逐字，已流块保留）。
                if (AnthropicResponseExtras.isFallbackBlock(block)) {
                    if (builder.hasContentBlocks()) {
                        throw new IllegalStateException(
                            "Anthropic performed an unsupported mid-output model fallback");
                    }
                    return null;
                }
                if (block.isToolUse()) {
                    var tu = block.toolUse().orElseThrow();
                    isToolBlock[0] = true;
                    isThinkingBlock[0] = false;
                    // B20：这里原来还置一个 `toolCallSeen` 标志，收尾据此二选一
                    // （`tool_use` / `end_turn`）。pi 不看工具块、只看
                    // `message_delta.stop_reason` ⇒ 该标志随本包一并删除。
                    // B81（pi :725-730）：OAuth 车道模型回的是 CC 规范名（Read…），
                    // 在当前工具集内大小写不敏感 demap 回本仓真名；不命中原样透传。
                    pendingToolName[0] = oauth
                        ? ClaudeCodeNames.fromWire(tu.name(), currentTools) : tu.name();
                    pendingToolId[0] = tu.id();
                    // 包⑥：起点即带身份 —— pi 在此刻块已带 id/name 入 content
                    // （anthropic-messages.ts:648-660），两个值上一行刚取到。
                    return builder.emitToolCallStart(pendingToolId[0], pendingToolName[0]);
                }
                if (block.isRedactedThinking()) {
                    // B7（原 docs/31 §8.33）：pi 把 redacted 映射成 thinking 块 ——
                    // 文本固定 "[Reasoning redacted]"、thinkingSignature = data、redacted: true
                    // （anthropic-messages.ts:638-647），且同样「先入 content、后 push 事件」。
                    // 落到 text 分支会留下一个空 TextContent，那个空块会被原样发给 Anthropic。
                    isToolBlock[0] = false;
                    isThinkingBlock[0] = true;
                    // pi 在 :642 是 `thinkingSignature: event.content_block.data` 直取 ——
                    // TS 类型谎报 required，缺字段会拼出字面量 "undefined"。此处**故意不复刻**，
                    // 用非抛异常的 _data()（与下面的 signature 同一口径）。
                    return builder.emitThinkingStart("[Reasoning redacted]",
                            block.redactedThinking().orElseThrow()._data().asString().orElse(""),
                            true);
                }
                if (block.isThinking()) {
                    // 签名必须**容忍缺失**（P2，原 docs/31 §8.31）：Anthropic 的 thinking 块其
                    // signature 由后续 signature_delta 补，relay/兼容端点为非 Anthropic 模型
                    // 合成思考时更可能整个流都不给。SDK 的严格访问器 signature() 会抛
                    // AnthropicInvalidDataException("`signature` is not set") 打死整轮 run；
                    // pi 在同一位置是 `event.content_block.signature ?? ""`
                    // （anthropic-messages.ts:633）。_signature() 是非抛异常面：
                    // 字段缺失即 JsonMissing ⇒ asString() 为空 ⇒ 取空串。
                    //
                    // B6/B9：初始**文本**同签名一道随首个 ThinkingStart.partial 投影
                    // （pi :630-637 是先建好带初值的块、再 push 事件）。此前只补了签名，
                    // 且是在 snapshot() 之后补的 —— 既丢了文本，签名也进不了首个 partial。
                    isToolBlock[0] = false;
                    isThinkingBlock[0] = true;
                    var tb = block.thinking().orElseThrow();
                    return builder.emitThinkingStart(
                            tb._thinking().asString().orElse(""),
                            tb._signature().asString().orElse(""),
                            false);
                }
                isToolBlock[0] = false;
                isThinkingBlock[0] = false;
                return builder.emitTextStart();
            }
            if (event.isContentBlockDelta()) {
                var delta = event.asContentBlockDelta().delta();
                if (delta.isText()) {
                    return builder.emitTextDelta(delta.asText().text());
                }
                if (delta.isInputJson()) {
                    return builder.emitToolCallDelta(pendingToolId[0],
                            delta.asInputJson().partialJson());
                }
                if (delta.isThinking()) {
                    return builder.emitThinkingDelta(delta.asThinking().thinking());
                }
                if (delta.isSignature()) {
                    // 同上的容忍规则（P2，原 docs/31 §8.31）：缺字段 ⇒ 空串，不抛。
                    // pi 的 `block.thinkingSignature += event.delta.signature`（anthropic-messages.ts:773-779）
                    // 在 JS 里会把 undefined 拼成字面量 "undefined" —— 那是 pi 的事故
                    // （TS 类型谎报 required），这里**故意不复制**；真 Anthropic 的
                    // signature_delta 恒带该字段，该分支不可达。
                    // 批1/#23：pi 对 signature_delta **只改块、不 push 事件**；返回 null ⇒
                    // 本事件不 submit（mapEvent 链对 null 有既有处理），签名随下一帧 partial 可见。
                    builder.appendThinkingSignature(
                            delta.asSignature()._signature().asString().orElse(""));
                    return null;
                }
                return null;
            }
            if (event.isContentBlockStop()) {
                if (isToolBlock[0]) {
                    return builder.emitToolCallEnd(
                            pendingToolId[0], pendingToolName[0]);
                }
                if (isThinkingBlock[0]) {
                    return builder.emitThinkingEnd();
                }
                return builder.emitTextEnd();
            }
            if (event.isMessageDelta()) {
                // B148（pi :815-817）：input_transformations 在事件**顶层**（不在 delta 里），
                // 是数组即整体替换 start 的列表（末值胜）。
                var deltaEvent = event.asMessageDelta();
                var deltaTransformations =
                    deltaEvent._additionalProperties().get("input_transformations");
                if (deltaTransformations != null) {
                    responseExtras.replaceTransformations(deltaTransformations);
                }
                var delta = deltaEvent.delta();
                // pi `:738-744`：`if (event.delta.stop_reason)` 是 JS 真值判断 ⇒
                // 键缺失、JSON null、空串三种都按「本事件没观测到 stop reason」处理。
                var rawStopReason = delta._stopReason();
                if (!rawStopReason.isMissing() && !rawStopReason.isNull()) {
                    var raw = rawStopReason.asKnown()
                            .map(com.anthropic.models.messages.StopReason::asString).orElse("");
                    if (!raw.isEmpty()) {
                        // pi `:744`：原值先落消息（⑨/D5），映射结果再落 stopReason。
                        builder.noteRawStopReason(raw);
                        var mapped = AnthropicStopReasons.map(
                            raw, AnthropicStopReasons.refusalExplanation(delta));
                        stop.reason = mapped.reason();
                        if (mapped.errorMessage() != null) {
                            stop.errorMessage = mapped.errorMessage();
                        }
                    }
                }
                // pi `:763-787`：四字段**各自 `!= null`** 才覆盖（注释逐字 "Only update
                // usage fields if present (not null). Preserves input_tokens from
                // message_start when proxies omit it in message_delta."）⇒ `0` 是合法值。
                // `reasoning` 走 `output_tokens_details.thinking_tokens`（与四字段不同层）；
                // `cacheWrite1h` **不在覆盖列表里**。`totalTokens` 重算与计价在
                // `if (event.usage)` **块外**，无条件执行（在 AnthropicUsageState 里）。
                var deltaUsage = event.asMessageDelta().usage();
                usageState.onMessageDelta(
                        deltaUsage.inputTokens().orElse(null),
                        deltaUsage._outputTokens().asKnown().orElse(null),
                        deltaUsage.cacheReadInputTokens().orElse(null),
                        deltaUsage.cacheCreationInputTokens().orElse(null),
                        deltaUsage.outputTokensDetails()
                                .flatMap(d -> d._thinkingTokens().asKnown()).orElse(null));
                return builder.emitUsage(usageState.usage());
            }
            if (event.isMessageStop()) {
                return null; // StreamDone emitted in streamInternal finally
            }
        } catch (Exception e) {
            return builder.emitError(StopReason.ERROR, e);
        }
        return null;
    }
}
