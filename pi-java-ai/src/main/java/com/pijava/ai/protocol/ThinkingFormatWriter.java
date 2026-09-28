package com.pijava.ai.protocol;

import java.util.Optional;
import java.util.OptionalInt;

import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * 包 A-09：思考开关的**线格形状** —— pi {@code openai-completions.ts:873-970} 的
 * 十二臂 {@code else if} 链条。
 *
 * <p>{@code OpenAICompletionsMessageConverter} 已超 500 行 ⇒ 照 {@code SamplingParamsWriter}
 * 的先例开新文件，converter 只留一行调用（{@code docs/58 §4.5}）。</p>
 *
 * <h2>从链条形状读出来、必须照抄的三条事实（{@code docs/58 §2.2}）</h2>
 * <ol>
 *   <li>十二个臂**每一个**都带 {@code model.reasoning} ⇒ 非推理模型整条链恒不写
 *       （{@link #apply} 的前置门）。</li>
 *   <li>{@code else if} ⇒ 命中即终止：「{@code chat-template} 的 map 为空」**不会**回落成
 *       {@code openai} 的 {@code reasoning_effort}，而是什么都不发 —— 故这里用
 *       <b>exhaustive switch、无 default</b>（编译器守十一臂），而不是查表＋兜底（R6）。</li>
 *   <li>pi 里落在 {@code :962-970} 的只有 {@code thinkingFormat === "openai"}（其余十值
 *       都在前面被吃掉）。⚠️ 唯一例外是手搓 models.json 把 {@code ant-ling} 与
 *       {@code supportsReasoningEffort:true} 写在一起且无级别时，pi 的链条会**穿透**到
 *       {@code :965} —— pi 的生成数据不可达（{@code antLingCompat} 明文写 false，
 *       探测同值），switch 形状不复制该穿透（{@code docs/32 B131} 登记）。</li>
 * </ol>
 *
 * <p>⚠️ 两派 null 语义（R4，{@code docs/58 §2.4}）：{@code map?.[k] ?? k}（qwen／deepseek／
 * openrouter／together／string-thinking）与 {@code map?.[k] === undefined ? k : map[k]}
 * ＋{@code typeof === "string"}（zai／baseten）在「值是显式 null」时**结果相反**
 * （前者发级别名、后者不写）⇒ 分成 {@link #orLevel} 与 {@code strictEffort} 两个助手，
 * 逐形状照抄、不许统一。</p>
 *
 * <p>级别是**夹取后**的（A-10 的归属前移，{@link SimpleOptions#clampedReasoningEffort}
 * ≙ pi {@code :741-742}）—— 本类不再夹一次（{@code docs/57 §12.3}）。</p>
 *
 * @see com.pijava.ai.catalog.ThinkingFormat
 */
final class ThinkingFormatWriter {

    private ThinkingFormatWriter() {}

    /**
     * pi {@code openai-completions.ts:873-970}。
     *
     * @param builder        请求体 builder（唯一出参）
     * @param request        流请求（模型 ＋ 思考级别）
     * @param compat         **解析后**的 compat（{@link com.pijava.ai.catalog.CompatResolver}）
     * @param thinkingBudget 夹取后的思考预算（pi {@code :871} 在链条**之前**算好，
     *                       {@code $var: thinking.budget} 消费它；与顶层预算字段同源）
     */
    static void apply(ChatCompletionCreateParams.Builder builder, StreamRequest request,
                      ModelCompat compat, OptionalInt thinkingBudget) {
        var model = request.model();
        // pi 的十二个臂全部合取 `model.reasoning` ⇒ `!reasoning` 时整条链恒不写（事实 1）。
        // `thinkingFormat == null` ≙ 拿到的是未解析的 compat ⇒ 同样不写（读点纪律，
        // ModelCompat 的类 javadoc）。
        if (model == null || !model.capabilities().contains(ModelCapability.THINKING)
                || compat.thinkingFormat() == null) {
            return;
        }
        // A-10 的归属前移：级别**已经**是夹取过的（pi :741-742）。
        var level = SimpleOptions.clampedReasoningEffort(model, request.reasoning());
        var map = model.thinkingLevelMap();
        var supportsEffort = Boolean.TRUE.equals(compat.supportsReasoningEffort());

        switch (compat.thinkingFormat()) {
            case OPENAI -> {
                if (level.isPresent() && supportsEffort) {                    // pi :962-964
                    orLevel(map, level.get())
                        .ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                } else if (level.isEmpty() && supportsEffort) {               // pi :965-969
                    // `typeof offValue === "string"` 门：缺席／显式 null 都不写。
                    map.mapped(ModelThinkingLevel.off())
                        .ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            // 提交 4-6 逐臂落地（docs/58 §8）：其余十个形状今天在链路上不写任何字段。
            case ZAI, QWEN, QWEN_CHAT_TEMPLATE, CHAT_TEMPLATE, BASETEN, DEEPSEEK,
                 OPENROUTER, ANT_LING, TOGETHER, STRING_THINKING -> { }
        }
    }

    /**
     * {@code map?.[k] ?? k} 派（{@code docs/58 §2.4} 第一行）：键缺席与显式 null
     * **同样**回落级别名。
     */
    private static Optional<String> orLevel(ThinkingLevelMap map, ThinkingLevel level) {
        return Optional.of(map.mapped(ModelThinkingLevel.of(level)).orElse(level.label()));
    }
}
