package com.pijava.ai.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包B14 步3（docs/47 §4.3 + §5-步3）：{@link TransformMessages} 的第 5 形参
 * {@link ToolCallIdNormalizer} ＋ 第一遍的两条 id 归一变换 —— P5（pi
 * {@code transform-messages.ts:136-142}，助手 toolCall 块换 id 并记映射）与
 * P2（{@code :84-90}，toolResult 按映射换 {@code toolCallId}）。
 *
 * <p>归一器用<b>确定性的 lambda</b>（不依赖任何车道实现）—— 本文件只隔离步 3 的被测逻辑
 * （签名、映射表的填充/消费、门控），车道归一器本身的规则在各自车道夹具里（步 2）。</p>
 *
 * <p><b>有牙声明</b>：行为用例（1/5/6）在桩（toolCall/toolResult 一律原样）下红；
 * 2/4 是门/回归门形状（桩下即绿）。各用例 javadoc 标注它钉的命题与「什么情况下会红」；
 * 实测红灯记录见 task-3-report。</p>
 */
class TransformMessagesIdNormalizationTest {

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");
    private static final String API = "anthropic-messages";

    /** 本文件全部用例喂视觉模型，让图片闸恒不触发 —— 这里钉的是 id 归一，不是图片降级。 */
    private static final ModelInfo VISION_TARGET = new ModelInfo(
        TARGET, "Claude Sonnet 5",
        Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
        200_000, 8_192, false, PricingInfo.UNKNOWN);

    /**
     * 确定性 lambda（步 3 夹具的默认归一器）：只把 {@code |} 换成 {@code _}，无 {@code |} 的输入
     * <b>原样返回</b> —— 正好同时覆盖「会归一的 id」与「归一器返回原值的 id」两种输入。
     */
    private static final ToolCallIdNormalizer PIPE_TO_UNDERSCORE =
            (id, target, source) -> id.replace('|', '_');

    private static Message.AssistantMessage foreignModel(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            API, "teamorouter", "deepseek-v4-flash", null, null, null, null);
    }

    private static Message.AssistantMessage sameModel(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null,
            API, TARGET.provider(), TARGET.modelName(), null, null, null, null);
    }

    private static Message.ToolResultMessage toolResult(String toolUseId, String toolName) {
        return new Message.ToolResultMessage(toolUseId, toolName,
            List.of(new ContentBlock.TextContent("ok")), false);
    }

    /** 收集全部助手消息里的 toolCall id（按出现序）。 */
    private static List<String> toolCallIds(List<Message> messages) {
        var out = new ArrayList<String>();
        for (var msg : messages) {
            if (msg instanceof Message.AssistantMessage a) {
                for (var block : a.content()) {
                    if (block instanceof ContentBlock.ToolUseContent tu) {
                        out.add(tu.id());
                    }
                }
            }
        }
        return out;
    }

    /** 收集全部 toolResult 的 toolUseId（按出现序）。 */
    private static List<String> toolUseIds(List<Message> messages) {
        var out = new ArrayList<String>();
        for (var msg : messages) {
            if (msg instanceof Message.ToolResultMessage t) {
                out.add(t.toolUseId());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 用例

    /**
     * <b>用例 1</b> —— 钉 <b>P5 ＋ P2 端到端</b>（pi {@code :136-142} ＋ {@code :84-90}）：
     * 跨模型助手的 toolCall id {@code call_123|fc_123} 被归一成 {@code call_123_fc_123}，
     * 且其后的 toolResult 的 {@code toolUseId} 经映射表同步换名。
     *
     * <p><b>会红</b>：P5 缺席/门写反（assistant id 不变）⇒ 第 1 条断言红；P2 缺席
     * （映射表不被消费）⇒ 第 2 条断言红。</p>
     */
    @Test
    void crossModelToolCallIdNormalizedAndResultRewritten() {
        var assistant = foreignModel(
            new ContentBlock.ToolUseContent("call_123|fc_123", "read", Map.of()));
        var result = toolResult("call_123|fc_123", "read");

        var out = TransformMessages.apply(List.of(assistant, result), TARGET, API,
                VISION_TARGET, PIPE_TO_UNDERSCORE);

        assertThat(toolCallIds(out)).containsExactly("call_123_fc_123");
        assertThat(toolUseIds(out)).containsExactly("call_123_fc_123");
        // P2 换 id 走 withToolUseId：七个字段全带 —— 只搬 id 会丢 toolName/content/isError。
        var rewritten = (Message.ToolResultMessage) out.get(1);
        assertThat(rewritten.toolName()).isEqualTo("read");
        assertThat(rewritten.isError()).isFalse();
        assertThat(rewritten.content()).containsExactly(new ContentBlock.TextContent("ok"));
    }

    /**
     * <b>用例 2</b> —— 钉 <b>P5 的 {@code isSameModel} 门</b>（pi {@code :136} 的
     * {@code !isSameModel && normalizeToolCallId}）：同模型助手即使 id 含 {@code |}
     * 也不归一（门在归一器之前）。
     *
     * <p><b>会红</b>：门被删/写反（同模型也跑归一器）⇒ id 变 {@code call_123_fc_123} ⇒ 红。</p>
     */
    @Test
    void sameModelToolCallKeptUntouched() {
        var assistant = sameModel(
            new ContentBlock.ToolUseContent("call_123|fc_123", "read", Map.of()));
        var result = toolResult("call_123|fc_123", "read");

        var out = TransformMessages.apply(List.of(assistant, result), TARGET, API,
                VISION_TARGET, PIPE_TO_UNDERSCORE);

        assertThat(toolCallIds(out)).containsExactly("call_123|fc_123");
        assertThat(toolUseIds(out)).containsExactly("call_123|fc_123");
    }

    /**
     * <b>用例 3</b> —— 钉 <b>P5 的关键半边</b>（pi {@code :138} 的
     * {@code normalizedId !== toolCall.id}）：归一器对 {@code call_123} 返回<b>原值</b>
     *（{@code PIPE_TO_UNDERSCORE} 对无 {@code |} 输入是恒等）⇒ 不记映射、不改写，
     * 助手与 toolResult 都原样。
     *
     * <p><b>会红</b>：归一器返回值被忽略/替换成别的 id ⇒ 助手块 id 变 ⇒ 红。
     * ⚠️ 已知盲区（如实登记）：「记了自映射」这一半在第一遍内<b>观察等价</b> ——
     * 换成同值块与 P2 的 {@code !==} 门都不可见；能咬到这一半的变异不存在（见 task-3-report）。</p>
     */
    @Test
    void normalizerReturningOriginalWritesNoMapping() {
        var assistant = foreignModel(
            new ContentBlock.ToolUseContent("call_123", "read", Map.of()));
        var result = toolResult("call_123", "read");

        var out = TransformMessages.apply(List.of(assistant, result), TARGET, API,
                VISION_TARGET, PIPE_TO_UNDERSCORE);

        assertThat(toolCallIds(out)).containsExactly("call_123");
        assertThat(toolUseIds(out)).containsExactly("call_123");
    }

    /**
     * <b>用例 4</b> —— 钉 <b>4 参重载的 {@code null} 语义</b>（pi 的
     * {@code normalizeToolCallId?} 缺席）：跨模型助手带 {@code |} id 也原样。
     * 同时是 5 个生产调用点（步 5 前仍调 4 参版）的<b>回归门</b>。
     *
     * <p><b>会红</b>：重载链断裂（4 参版不再等价于「无归一器」）或 null 透传丢失。</p>
     */
    @Test
    void fourArgOverloadDoesNotNormalize() {
        var assistant = foreignModel(
            new ContentBlock.ToolUseContent("call_123|fc_123", "read", Map.of()));
        var result = toolResult("call_123|fc_123", "read");

        var out = TransformMessages.apply(List.of(assistant, result), TARGET, API, VISION_TARGET);

        assertThat(toolCallIds(out)).containsExactly("call_123|fc_123");
        assertThat(toolUseIds(out)).containsExactly("call_123|fc_123");
    }

    /**
     * <b>用例 5</b> —— 钉 <b>P2 的顺序依赖</b>（映射表只由<b>本遍更早</b>的助手消息填）：
     * toolResult 排在列表<b>开头</b>，映射表为空 ⇒ 原样 —— 即使归一器对
     * {@code call_999} <b>会</b>返回别的值（{@code id + "_n"}）。P2 查的是映射表，
     * <b>不调</b>归一器。
     *
     * <p><b>会红</b>：P2 改成对 toolResult 直接调归一器（绕过映射表）⇒
     * {@code call_999} 被改成 {@code call_999_n} ⇒ 红。</p>
     */
    @Test
    void toolResultWithoutPrecedingMappedAssistantUnchanged() {
        ToolCallIdNormalizer normalizer = (id, target, source) -> id + "_n";
        var result = toolResult("call_999", "read");

        var out = TransformMessages.apply(List.of(result), TARGET, API, VISION_TARGET, normalizer);

        assertThat(toolUseIds(out)).containsExactly("call_999");
    }

    /**
     * <b>用例 6</b> —— 钉 <b>映射按 id 精确匹配</b>：同一助手两个 toolCall，
     * {@code call_1|fc} 会被归一、{@code call_2} 归一器返回原值（不记映射）；
     * 其后两条 toolResult 只有 {@code call_1|fc} 的那条被改写。
     *
     * <p><b>会红</b>：映射误伤其它 id（如按位置/批量改写）或 P2 未查表 ⇒ 红。</p>
     */
    @Test
    void mappingAppliesOnlyToExactId() {
        var assistant = foreignModel(
            new ContentBlock.ToolUseContent("call_1|fc", "read", Map.of()),
            new ContentBlock.ToolUseContent("call_2", "write", Map.of()));
        var result1 = toolResult("call_1|fc", "read");
        var result2 = toolResult("call_2", "write");

        var out = TransformMessages.apply(List.of(assistant, result1, result2), TARGET, API,
                VISION_TARGET, PIPE_TO_UNDERSCORE);

        assertThat(toolCallIds(out)).containsExactly("call_1_fc", "call_2");
        assertThat(toolUseIds(out)).containsExactly("call_1_fc", "call_2");
    }
}
