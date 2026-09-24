package com.pijava.ai.api;

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
 * 包B14 步4（docs/47 §4.4 + §5-步4）：{@link OrphanToolResults} 的第二遍 ——
 * P7–P16 孤儿 toolCall 合成 toolResult ＋ error/aborted 助手整条跳过（pi
 * {@code transform-messages.ts:158-232}）。
 *
 * <p><b>有牙声明</b>：行为用例在桩（{@code return transformed;} 直通）下红；
 * 各用例 javadoc 标注它钉的命题与「什么情况下会红」；实测红灯记录见 task-4-report。</p>
 */
class OrphanToolResultsTest {

    private static final ModelId<?> TARGET = ModelId.of("anthropic", "claude-sonnet-5");
    private static final String API = "anthropic-messages";

    /** 本文件全部用例喂视觉模型，让图片闸恒不触发 —— 这里钉的是孤儿合成，
     * 不是图片降级。 */
    private static final ModelInfo VISION_TARGET = new ModelInfo(
        TARGET, "Claude Sonnet 5",
        Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_INPUT),
        200_000, 8_192, false, PricingInfo.UNKNOWN);

    private static Message.AssistantMessage assistant(ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), "stop", null);
    }

    private static Message.AssistantMessage assistantWithStopReason(String stopReason,
                                                                    ContentBlock... blocks) {
        return new Message.AssistantMessage(List.of(blocks), stopReason, null);
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message.ToolResultMessage toolResult(String toolUseId, String toolName) {
        return new Message.ToolResultMessage(toolUseId, toolName,
            List.of(new ContentBlock.TextContent("ok")), false);
    }

    private static ContentBlock.ToolUseContent toolUse(String id, String name) {
        return new ContentBlock.ToolUseContent(id, name, Map.of());
    }

    /** 收集输出里全部 toolResult 消息（按出现序）。 */
    private static List<Message.ToolResultMessage> toolResults(List<Message> messages) {
        return messages.stream()
            .filter(Message.ToolResultMessage.class::isInstance)
            .map(Message.ToolResultMessage.class::cast)
            .toList();
    }

    // ------------------------------------------------------------------ 用例

    /**
     * <b>用例 1</b> —— 钉 <b>P16</b>（pi {@code :232}）：转录以未答 toolCall 结尾 ⇒
     * 循环后的最后一次 close 在末尾追加合成条
     * {@code toolUseId=call_1, toolName=read, isError=true, content=[text "No result provided"]}。
     *
     * <p><b>会红</b>：P16 缺席/桩直通 ⇒ 输出里根本没有合成条。</p>
     */
    @Test
    void trailingUnansweredToolCallGetsSyntheticResult() {
        var out = OrphanToolResults.apply(
            List.of(assistant(toolUse("call_1", "read"))));

        assertThat(out).hasSize(2);
        var synthetic = toolResults(out).getFirst();
        assertThat(synthetic.toolUseId()).isEqualTo("call_1");
        assertThat(synthetic.toolName()).isEqualTo("read");
        assertThat(synthetic.isError()).isTrue();
        assertThat(synthetic.content())
            .containsExactly(new ContentBlock.TextContent("No result provided"));
    }

    /**
     * <b>用例 2</b> —— 钉 <b>P12 ＋ P7 的 has 检查</b>（pi {@code :213-215} ＋ {@code :171}）：
     * call_1 已被 toolResult 覆盖 ⇒ close 时不再为它合成（输出恰 2 条）。
     *
     * <p><b>会红</b>：P12 的 ids.add 缺席 ⇒ 重复合成（输出 3 条）。</p>
     */
    @Test
    void answeredToolCallDoesNotDuplicate() {
        var out = OrphanToolResults.apply(
            List.of(assistant(toolUse("call_1", "read")), toolResult("call_1", "read")));

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(Message.AssistantMessage.class);
        assertThat(out.get(1)).isInstanceOf(Message.ToolResultMessage.class);
    }

    /**
     * <b>用例 3</b> —— 钉 <b>P8 ＋ P7</b>（pi {@code :191-193}）：close 在第二条 assistant
     * 处发生（call_1 无结果 ⇒ 先合成，再压入 assistant2）：输出 = assistant,
     * toolResult(合成), assistant2。
     *
     * <p><b>会红</b>：P8 的 close 缺席 ⇒ 合成条被挪到转录末尾、顺序颠倒。</p>
     */
    @Test
    void answeredBeforeNextAssistantClosesAtAssistantBoundary() {
        var first = assistant(toolUse("call_1", "read"));
        var second = assistant();

        var out = OrphanToolResults.apply(List.of(first, second));

        var synthetic = toolResults(out).getFirst();
        assertThat(toolResults(out)).hasSize(1);
        assertThat(out).containsExactly(first, synthetic, second);
    }

    /**
     * <b>用例 4</b> —— 钉 <b>P7 的选择性</b>（pi {@code :167-170} 的 has 过滤）：
     * 两个块只有 call_2 被真结果覆盖 ⇒ 只为 call_1 合成；call_2 的真结果原位保留
     * （合成条排在已答条目**之后**，不插在它前面）。
     *
     * <p><b>会红</b>：has 检查缺失/写反 ⇒ call_2 被重复合成或顺序颠倒。</p>
     */
    @Test
    void twoToolCallsOneAnsweredSynthesizesOnlyTheOther() {
        var out = OrphanToolResults.apply(
            List.of(assistant(toolUse("call_1", "read"), toolUse("call_2", "write")),
                toolResult("call_2", "write")));

        assertThat(out).hasSize(3);
        assertThat(toolResults(out)).hasSize(2);
        assertThat(out.get(1)).isInstanceOf(Message.ToolResultMessage.class);
        assertThat(((Message.ToolResultMessage) out.get(1)).toolUseId()).isEqualTo("call_2");
        assertThat(((Message.ToolResultMessage) out.get(2)).toolUseId()).isEqualTo("call_1");
        assertThat(((Message.ToolResultMessage) out.get(2)).isError()).isTrue();
    }

    /**
     * <b>用例 5</b> —— 钉 <b>P9</b>（pi {@code :201-203}）：error 助手带 toolCall
     * call_e ⇒ 整条不进 result、且 call_e 从未进 pending ⇒ 也不产生合成。
     *
     * <p><b>会红</b>：P9 缺席 ⇒ 消息进了 result 且 call_e 被合成。</p>
     */
    @Test
    void errorAssistantSkippedEntirely() {
        var errorAssistant = assistantWithStopReason("error", toolUse("call_e", "read"));

        var out = OrphanToolResults.apply(List.of(errorAssistant));

        assertThat(out).isEmpty();
    }

    /**
     * <b>用例 6</b> —— 钉 <b>P9 另一半</b>：aborted 与 error 同形跳过。
     *
     * <p><b>会红</b>：只判 {@code "error"} 漏掉 {@code "aborted"}（或反之）⇒ 红。</p>
     */
    @Test
    void abortedAssistantSkippedEntirely() {
        var abortedAssistant = assistantWithStopReason("aborted", toolUse("call_a", "read"));

        var out = OrphanToolResults.apply(List.of(abortedAssistant));

        assertThat(out).isEmpty();
    }

    /**
     * <b>用例 7</b> —— 钉 <b>P8 边界</b>（pi {@code :191-193}）：无 toolCall 的助手也先触发
     * P8 close —— call_1 此时仍未答 ⇒ 在 assistant2 **之前**合成；随后到达的真结果原样放行
     * （输出 = assistant1, 合成条, assistant2, 真结果）。
     *
     * <p>⚠️ P10 的「空则不触碰」门仍按 pi 实现，但在此转录中 P8 已先清空 pending，
     * 因而其效果不可单独观察。原 brief 把这个场景描述成「不产生合成」与 P8 顺序冲突；
     * 本夹具按 pi 的实际可观察行为钉住 P8 边界。</p>
     *
     * <p><b>会红</b>：P8/P16 的 close 缺席 ⇒ 无合成条；或 P8 顺序错误 ⇒ 输出顺序
     * 变化。</p>
     */
    @Test
    void emptyToolCallAssistantClosesPendingAtAssistantBoundary() {
        var second = assistant();
        var out = OrphanToolResults.apply(
            List.of(assistant(toolUse("call_1", "read")), second,
                toolResult("call_1", "read")));

        assertThat(out).hasSize(4);
        var synthetic = (Message.ToolResultMessage) out.get(1);
        assertThat(synthetic.toolUseId()).isEqualTo("call_1");
        assertThat(synthetic.isError()).isTrue();
        assertThat(out.get(2)).isSameAs(second);
        var real = (Message.ToolResultMessage) out.get(3);
        assertThat(real.toolUseId()).isEqualTo("call_1");
        assertThat(real.isError()).isFalse();
    }

    /**
     * <b>用例 8</b> —— 钉 <b>P10 重置</b>（pi {@code :207-208} 的赋值语义）：
     * 第二个 assistant 在自己入列前先 close（call_1 已答、无合成），pending **换成**
     * call_2 ⇒ 转录尾为 call_2 的合成条；call_1 的真结果原位保留、不被重复合成。
     *
     * <p><b>会红</b>：pending 累积旧条目（不替换）⇒ 输出尾多出一条 call_1 的合成。</p>
     */
    @Test
    void secondAssistantReplacesPending() {
        var out = OrphanToolResults.apply(
            List.of(assistant(toolUse("call_1", "read")), toolResult("call_1", "read"),
                assistant(toolUse("call_2", "write"))));

        assertThat(out).hasSize(4);
        // call_1 的真结果原位保留（isError=false），且没有为它重复合成。
        assertThat(((Message.ToolResultMessage) out.get(1)).toolUseId()).isEqualTo("call_1");
        assertThat(((Message.ToolResultMessage) out.get(1)).isError()).isFalse();
        // 转录尾恰一条合成条，属于 call_2。
        var synthetic = (Message.ToolResultMessage) out.get(3);
        assertThat(synthetic.toolUseId()).isEqualTo("call_2");
        assertThat(synthetic.toolName()).isEqualTo("write");
        assertThat(synthetic.isError()).isTrue();
    }

    /**
     * <b>用例 9</b> —— 钉 <b>P7 载荷</b>：toolName 来自 toolCall 块的 name（不是空串）、
     * content 是单条 {@code TextContent("No result provided")}（逐字）、isError true。
     *
     * <p><b>会红</b>：载荷字段错/文案漂移（如丢 name、content 复用其它构造）。</p>
     */
    @Test
    void syntheticResultCarriesToolNameAndErrorFlag() {
        var out = OrphanToolResults.apply(
            List.of(assistant(toolUse("call_x", "bash"))));

        var synthetic = toolResults(out).getFirst();
        assertThat(synthetic.toolName()).isEqualTo("bash");
        assertThat(synthetic.toolUseId()).isEqualTo("call_x");
        assertThat(synthetic.content())
            .containsExactly(new ContentBlock.TextContent("No result provided"));
        assertThat(synthetic.isError()).isTrue();
        // 4 参紧凑构造的固定形状：details/usage null、addedToolNames 空。
        assertThat(synthetic.details()).isNull();
        assertThat(synthetic.usage()).isNull();
        assertThat(synthetic.addedToolNames()).isEmpty();
    }

    /**
     * <b>用例 10</b> —— 钉 <b>P14</b>（pi {@code :222-225}）：user 处理时**先** close
     * 再压入 ⇒ 输出 = assistant, 合成 toolResult, user —— 合成条在 user 之前。
     *
     * <p><b>会红</b>：close 在 push 之后/缺失 ⇒ 顺序颠倒或缺合成条。</p>
     */
    @Test
    void userTurnClosesPendingBeforeItself() {
        var go = user("go");

        var out = OrphanToolResults.apply(
            List.of(assistant(toolUse("call_1", "read")), go));

        assertThat(out).hasSize(3);
        assertThat(out.get(0)).isInstanceOf(Message.AssistantMessage.class);
        assertThat(out.get(1)).isInstanceOf(Message.ToolResultMessage.class);
        assertThat(out.get(2)).isSameAs(go);
    }

    /**
     * <b>用例 11</b> —— 钉 <b>端到端串联顺序＝第一遍→第二遍</b>：走 5 参
     * {@link TransformMessages#apply}（跨模型助手 + {@code call_123|fc_123} 未答）⇒
     * 输出里合成条的 toolUseId 是**归一后的** {@code call_123_fc_123}（P5 记映射用原始 id、
     * 块换成了归一 id ⇒ 第二遍 pending 里是归一 id）。
     *
     * <p><b>会红</b>：串联反序（先第二遍后第一遍）或第二遍没接上。</p>
     */
    @Test
    void transformMessagesChainsSecondPass() {
        ToolCallIdNormalizer pipeToUnderscore = (id, target, source) -> id.replace('|', '_');
        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent("call_123|fc_123", "read", Map.of())),
            "stop", null, API, "teamorouter", "deepseek-v4-flash", null, null, null, null);

        var out = TransformMessages.apply(List.of(assistant), TARGET, API, VISION_TARGET,
            pipeToUnderscore);

        assertThat(out).hasSize(2);
        assertThat(toolResults(out).getFirst().toolUseId()).isEqualTo("call_123_fc_123");
        assertThat(toolResults(out).getFirst().isError()).isTrue();
    }
}
