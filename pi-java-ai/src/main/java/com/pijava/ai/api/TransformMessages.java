package com.pijava.ai.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * Shared message pre-pass, mirroring pi's {@code transform-messages.ts}.
 *
 * <p>pi 在**每次请求**上跑它，六个请求构建器都调（`anthropic-messages.ts:1029`、
 * `openai-completions.ts:1212`、`openai-responses-shared.ts:172`、`google-shared.ts:138`、
 * `mistral-conversations.ts:139`、`bedrock-converse-stream.ts:935`）—— 所以它**不属于任何一条车道**：
 * 它先决定哪些块能活到重放，再由各适配器决定活下来的块长成什么线格
 * （docs/31 §8.34.4 决策 1）。pi-java 此前没有对应物，重放规则住在
 * {@code AnthropicMessagesApi} 里 —— 位置就不对：以后每接一条车道都要再抄一遍。</p>
 *
 * <p><b>已落地的变换</b>：thinking 五分支（`transform-messages.ts:99-116`，包② docs/31 §8.34）、
 * 图片降级（`:12-57`，包 H2 docs/44）、跨模型归一 toolCall id（`:136-142`，包B14 步3 ——
 * 含 toolResult 侧的 id 换名 `:84-90`）。孤儿 toolCall 合成 toolResult（`:158-220`）由
 * 同包内的 {@code OrphanToolResults}（步 4）承担。{@code thoughtSignature} 剥离
 * （`:131-134`）在 java 上结构性不可达（{@code ToolUseContent} 无该字段），**不做**，
 * 见 B14b / docs/47 §7-R1。</p>
 *
 * <p>pi 的第一、二遍共用一个 {@code transformMessages} 函数；java 侧按 D7 拆分：
 * 第二遍在包内可见的 {@code OrphanToolResults} 中，由 5 参 {@link #apply} 串联。</p>
 *
 * @see <a href="https://github.com/earendil-works/pi">pi</a> {@code packages/ai/src/api/transform-messages.ts}
 */
public final class TransformMessages {

    /** pi {@code transform-messages.ts:12} —— user 消息的占位文案（逐字）。 */
    private static final String NON_VISION_USER_IMAGE_PLACEHOLDER =
            "(image omitted: model does not support images)";

    /** pi {@code transform-messages.ts:13} —— 工具结果的占位文案（逐字，比 user 多一个 "tool "）。 */
    private static final String NON_VISION_TOOL_IMAGE_PLACEHOLDER =
            "(tool image omitted: model does not support images)";

    private TransformMessages() {}

    /**
     * Transform {@code messages} for a request to {@code target}.
     *
     * @param messages the conversation history
     * @param target   the model the request is going to (identity of the *target*, which is what
     *                 per-model replay rules compare against)
     * @param apiName  the wire protocol of the lane being used, e.g. {@code "anthropic-messages"}.
     *                 pi's {@code model.api} — in pi-java the api is a property of the adapter,
     *                 not of {@link ModelId}
     * @param targetModel the target's full metadata, needed by the image gate
     *                 ({@code model.input.includes("image")}, pi {@code :36}). {@code null} is
     *                 treated as **unknown ⇒ images are kept** ({@link ModelInfo#supportsImageInput()})
     * @return the transformed history
     */
    public static List<Message> apply(List<Message> messages, ModelId<?> target, String apiName,
                                      ModelInfo targetModel) {
        // 镜像 pi 形参的可选性（normalizeToolCallId?）：无归一器 ＝ 不做 id 归一。
        return apply(messages, target, apiName, targetModel, null);
    }

    /**
     * Transform {@code messages} for a request to {@code target}.
     *
     * <p>4 参版即「无归一器」：镜像 pi 的 {@code normalizeToolCallId?} 可选性，
     * 本步不改任何调用点（接线是步 5）。</p>
     *
     * @param normalize pi {@code :67} 的归一器；{@code null} ＝ 不做 id 归一
     *                  （pi 的 {@code normalizeToolCallId?} 缺席）
     * @return the transformed history
     */
    public static List<Message> apply(List<Message> messages, ModelId<?> target, String apiName,
                                      ModelInfo targetModel, ToolCallIdNormalizer normalize) {
        // pi :73-74 —— 先降级（java 的紧凑构造器已保证 content 非 null，无需先补空）。
        var messages0 = downgradeUnsupportedImages(messages, targetModel);
        // pi :68 —— 原始 toolCall id → 归一后 id 的映射表，由本遍更早处理的助手消息填充。
        var toolCallIdMap = new HashMap<String, String>();
        var out = new ArrayList<Message>(messages0.size());
        for (var msg : messages0) {
            if (msg instanceof Message.AssistantMessage a) {
                out.add(gateAssistant(a, isSameModel(a, target, apiName), target, normalize,
                    toolCallIdMap));
            } else if (msg instanceof Message.ToolResultMessage t) {
                // P2（pi :84-90）：有映射且 ≠ 原值才换。java 的 Map.get 返回 null 与
                // pi 的 `normalizedId && …` 真值门同义：映射不存在 ⇒ 不改。
                var normalizedId = toolCallIdMap.get(t.toolUseId());
                if (normalizedId != null && !normalizedId.equals(t.toolUseId())) {
                    out.add(withToolUseId(t, normalizedId));
                } else {
                    out.add(t);
                }
            } else {
                // pi :79-81 user 消息原样放行。
                out.add(msg);
            }
        }
        return List.copyOf(out);
    }

    /**
     * pi {@code transform-messages.ts:35-57} —— **非视觉模型才跑**：把 user 与 toolResult 里的
     * 图片块换成占位文本，其余字段全带。
     *
     * <p>判据是 {@link ModelInfo#supportsImageInput()}（＝ pi 的 {@code model.input.includes("image")}），
     * 不是「capabilities 里有没有 IMAGE_INPUT」—— 两者在「目录未命中」时**故意**不同，理由写在该方法上。</p>
     *
     * <p>⚠️ 与 pi 的**一处刻意偏差**（{@code docs/44 D4}）：java 的 {@link ContentBlock.UrlImageContent}
     * 是 java 扩展（pi 无此类型），此处**与 ImageContent 同等对待** —— 它对非视觉模型同样不可用。
     * pi 只认 {@code type === "image"}，照抄反而会让 URL 图片绕过闸。</p>
     *
     * <p>⚠️ 无改动时**返回原对象/原列表**：pi 的 {@code messages.map(msg => ({...msg, content}))}
     * 恒造新对象，但对象身份不是可观察行为（与 {@link #gateAssistant} 同一取舍）。</p>
     */
    private static List<Message> downgradeUnsupportedImages(List<Message> messages, ModelInfo model) {
        if (model == null || model.supportsImageInput()) {
            return messages;
        }
        var out = new ArrayList<Message>(messages.size());
        for (var msg : messages) {
            if (msg instanceof Message.UserMessage user) {
                out.add(withContent(user, replaceImagesWithPlaceholder(user.content(),
                        NON_VISION_USER_IMAGE_PLACEHOLDER)));
            } else if (msg instanceof Message.ToolResultMessage tool) {
                out.add(withContent(tool, replaceImagesWithPlaceholder(tool.content(),
                        NON_VISION_TOOL_IMAGE_PLACEHOLDER)));
            } else {
                out.add(msg);
            }
        }
        return List.copyOf(out);
    }

    /**
     * pi {@code transform-messages.ts:15-33} —— 逐块替换，**带两处去重**：
     *
     * <pre>
     * 图片块：若上一块不是占位符 ⇒ 先补一个占位符；上一块是占位符 ⇒ 什么都不补（连续图片只出一个）
     * 其它块：原样留下，并记下「这一块的文本**恰好等于**占位符」
     * </pre>
     *
     * <p>第二处去重（{@code :29}）是容易被漏掉的：一条**自带**占位文案的文本块，
     * 其后紧跟的图片**不会**再补一个 —— 否则会出两个一模一样的占位符。照抄。</p>
     */
    private static List<ContentBlock> replaceImagesWithPlaceholder(List<ContentBlock> content,
                                                                  String placeholder) {
        var result = new ArrayList<ContentBlock>(content.size());
        boolean previousWasPlaceholder = false;
        for (var block : content) {
            if (isImageBlock(block)) {
                if (!previousWasPlaceholder) {
                    result.add(new ContentBlock.TextContent(placeholder));
                }
                previousWasPlaceholder = true;
                continue;
            }
            result.add(block);
            // pi :29 `previousWasPlaceholder = block.text === placeholder`：
            // 非文本块的 block.text 是 undefined ⇒ 恒 false。
            previousWasPlaceholder = block instanceof ContentBlock.TextContent tc
                    && placeholder.equals(tc.text());
        }
        return List.copyOf(result);
    }

    /** pi 只认 {@code type === "image"}；java 的 URL 图片同等对待（见 {@link #downgradeUnsupportedImages}）。 */
    private static boolean isImageBlock(ContentBlock block) {
        return block instanceof ContentBlock.ImageContent
                || block instanceof ContentBlock.UrlImageContent;
    }

    /** 内容未变则返回原消息（pi 恒造新对象，见 {@link #downgradeUnsupportedImages}）。 */
    private static Message withContent(Message.UserMessage msg, List<ContentBlock> content) {
        return content.equals(msg.content()) ? msg : new Message.UserMessage(content);
    }

    /** 内容未变则返回原消息；变了则**七个字段全带**（只搬 content 会丢 details/usage/isError）。 */
    private static Message withContent(Message.ToolResultMessage msg, List<ContentBlock> content) {
        if (content.equals(msg.content())) {
            return msg;
        }
        return new Message.ToolResultMessage(msg.toolUseId(), msg.toolName(), content,
                msg.details(), msg.usage(), msg.addedToolNames(), msg.isError());
    }

    /**
     * P2 的换 id 复制（pi {@code :86-88} 的 {@code {...msg, toolCallId: normalizedId}}）。
     * 内容等价则返回原消息；换 id 时七个字段全带（只搬 id 会丢 details/usage/isError）。
     */
    private static Message withToolUseId(Message.ToolResultMessage msg, String toolUseId) {
        if (toolUseId.equals(msg.toolUseId())) {
            return msg;
        }
        return new Message.ToolResultMessage(toolUseId, msg.toolName(), msg.content(),
                msg.details(), msg.usage(), msg.addedToolNames(), msg.isError());
    }

    /**
     * pi {@code :95-98}：{@code assistantMsg.provider === model.provider && assistantMsg.api ===
     * model.api && assistantMsg.model === model.id}。
     *
     * <p>⚠️ 两处刻意的取舍：</p>
     * <ul>
     *   <li>**不 trim、不做非空前置**：pi 用的是 {@code ===}，所以老会话（身份三元组为
     *       {@code null}）判**异** ⇒ 无签名 thinking 降级 text，与 pi-java 今天的行为**逐字相同**
     *       （§8.34.4 决策 C，实测风险为 0）。</li>
     *   <li>{@code target == null} 时判**异**（pi 的 {@code undefined === undefined} 本会判同）：
     *       目标未知时「降级 text」是安全方向 —— 宁可不重放签名，也不发一条注定被拒的请求。</li>
     * </ul>
     */
    private static boolean isSameModel(Message.AssistantMessage msg, ModelId<?> target,
                                       String apiName) {
        if (target == null) {
            return false;
        }
        return Objects.equals(msg.provider(), target.provider())
            && Objects.equals(msg.api(), apiName)
            && Objects.equals(msg.model(), target.modelName());
    }

    /**
     * 对一条助手消息跑闸；**无改动时返回原对象**（内容值相等即无改动）。
     *
     * <p>pi 侧对 assistant 恒造一个新对象（`{...msg, content}`），但那是 JS 的写法，
     * 对象身份不是可观察行为；这里在有改动时才复制，复制时**九个字段全带**
     * —— 只搬 content 会丢掉身份三元组，而那正是闸自己的判据来源。</p>
     */
    private static Message gateAssistant(Message.AssistantMessage msg, boolean same,
                                         ModelId<?> target, ToolCallIdNormalizer normalize,
                                         Map<String, String> toolCallIdMap) {
        var content = new ArrayList<ContentBlock>(msg.content().size());
        for (var block : msg.content()) {
            var gated = gateBlock(block, same, target, normalize, msg, toolCallIdMap);
            if (gated != null) {
                content.add(gated);
            }
        }
        if (content.equals(msg.content())) {
            return msg;
        }
        return new Message.AssistantMessage(content, msg.stopReason(), msg.deferred(),
            msg.api(), msg.provider(), msg.model(), msg.usage(), msg.timestamp(),
            msg.errorMessage(), msg.rawStopReason());
    }

    /**
     * pi {@code :99-116} 的 thinking 五分支，逐条对照：
     *
     * <pre>
     * if (block.redacted)                       → isSameModel ? block : []          // :102-105  a
     * if (isSameModel &amp;&amp; block.thinkingSignature) → block                              // :107-109  b
     * if (!block.thinking || trim() === "")     → []                                  // :111      c
     * if (isSameModel)                          → block                              // :112      d1
     * return {type:"text", text: block.thinking}                                       // :113-116  d2
     * </pre>
     *
     * <p>⚠️ `:109` 用**真值**判签名而落线 `:1297` 用 {@code trim} —— pi 自己不自洽，
     * 但**该不对称在出参上不可观察**（落线 `:1298` 又用 trim 判文本、`:1304` 把两个来源
     * 都收敛成 text），故此处**照 pi 的真值语义**（{@code !isEmpty()}）而不额外 trim，
     * 保持与 pi 逐字一致即可（§8.34.6-2）。</p>
     *
     * <p>非 thinking 的块一律原样返回 —— 其中 text 分支是 pi 明写的
     * {@code {type:"text", text: block.text}}，**与直接放行同形**（结构性惰性，§8.34.10-4）。</p>
     *
     * @return 该块的替换物，或 {@code null} 表示**丢弃**
     */
    private static ContentBlock gateBlock(ContentBlock block, boolean same, ModelId<?> target,
                                          ToolCallIdNormalizer normalize, Message.AssistantMessage msg,
                                          Map<String, String> toolCallIdMap) {
        if (block instanceof ContentBlock.ToolUseContent tu) {
            // P5（pi :136-142）：同模型或无归一器 ⇒ 原样。
            if (same || normalize == null) {
                return block;
            }
            var normalizedId = normalize.normalize(tu.id(), target, msg);
            // P5 关键半边：归一器返回原值 ⇒ 不记映射、不改写。
            if (normalizedId.equals(tu.id())) {
                return block;
            }
            toolCallIdMap.put(tu.id(), normalizedId);
            return new ContentBlock.ToolUseContent(normalizedId, tu.name(), tu.arguments());
        }
        if (!(block instanceof ContentBlock.ThinkingContent th)) {
            return block;
        }
        if (th.redacted()) {
            return same ? th : null;
        }
        if (same && !th.signature().isEmpty()) {
            return th;
        }
        if (th.text() == null || th.text().trim().isEmpty()) {
            return null;
        }
        if (same) {
            return th;
        }
        return new ContentBlock.TextContent(th.text());
    }
}
