package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.openai.core.JsonValue;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ChatTemplateKwargValue;
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
 * 的先例开新文件，converter 只留一行调用（{@code 原 docs/58 §4.5}）。</p>
 *
 * <h2>从链条形状读出来、必须照抄的三条事实（{@code 原 docs/58 §2.2}）</h2>
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
 *       探测同值），switch 形状不复制该穿透（{@code docs/05 B131} 登记）。</li>
 * </ol>
 *
 * <p>⚠️ 两派 null 语义（R4，{@code 原 docs/58 §2.4}）：{@code map?.[k] ?? k}（qwen／deepseek／
 * openrouter／together／string-thinking）与 {@code map?.[k] === undefined ? k : map[k]}
 * ＋{@code typeof === "string"}（zai／baseten）在「值是显式 null」时**结果相反**
 * （前者发级别名、后者不写）⇒ 分成 {@link #orLevel} 与 {@link #strictEffort} 两个助手，
 * 逐形状照抄、不许统一。⚠️ 实施期发现：夹取先行使「显式 null」到不了写点 ⇒ 两派在
 * 生产路径**语义等价**（R4 探针零红，见 {@link #strictEffort} 的 javadoc）。</p>
 *
 * <p>级别是**夹取后**的（A-10 的归属前移，{@link SimpleOptions#clampedReasoningEffort}
 * ≙ pi {@code :741-742}）—— 本类不再夹一次（{@code 原 docs/57 §12.3}）。</p>
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
            case ZAI -> {
                var thinking = new LinkedHashMap<String, Object>();
                thinking.put("type", level.isPresent() ? "enabled" : "disabled");
                if (level.isPresent()) {
                    thinking.put("clear_thinking", false);                    // pi :878
                }
                put(builder, "thinking", thinking);
                if (level.isPresent() && supportsEffort) {                    // pi :879-885
                    strictEffort(map, level.get())
                        .ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            case QWEN -> {
                put(builder, "enable_thinking", level.isPresent());           // pi :887
                if (level.isPresent() && supportsEffort) {                    // pi :888-892
                    orLevel(map, level.get())
                        .ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            case DEEPSEEK -> {
                if (level.isPresent()) {
                    put(builder, "thinking", Map.of("type", "enabled"));      // pi :922
                } else if (map.supportsExplicitOff()) {                       // pi :924
                    put(builder, "thinking", Map.of("type", "disabled"));
                }
                if (level.isPresent() && supportsEffort) {                    // pi :927-929
                    orLevel(map, level.get())
                        .ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            case ANT_LING ->
                // ⚠️ 唯一**不回落级别名**的形状（原 docs/58 §2.4 第三行）：pi :942-944
                // 只认 map[level] 的字符串值 —— 键缺席／显式 null 都不写，且本臂
                // 从不写 reasoning_effort、也不读 supportsReasoningEffort。
                level.map(ModelThinkingLevel::of).flatMap(map::mapped)
                    .ifPresent(s -> put(builder, "reasoning", Map.of("effort", s)));
            case OPENROUTER -> {
                // pi :934-940 —— 本臂不读 supportsReasoningEffort，也不写顶层 effort。
                if (level.isPresent()) {
                    put(builder, "reasoning",
                        Map.of("effort", orLevel(map, level.get()).orElseThrow()));
                } else if (map.supportsExplicitOff()) {                       // pi :938-940
                    // `?? "none"` 派：off 缺席 ⇒ "none" 兜底；显式 null ⇒ 整键不发。
                    put(builder, "reasoning", Map.of("effort",
                        map.mapped(ModelThinkingLevel.off()).orElse("none")));
                }
            }
            case TOGETHER -> {
                put(builder, "reasoning", Map.of("enabled", level.isPresent())); // pi :951
                if (level.isPresent() && supportsEffort) {                    // pi :952-954
                    orLevel(map, level.get())
                        .ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
            case STRING_THINKING -> {
                // pi :957-961 —— 顶层 thinking 是**字符串**（与 zai/deepseek 的对象相对）。
                if (level.isPresent()) {
                    put(builder, "thinking", orLevel(map, level.get()).orElseThrow());
                } else if (map.supportsExplicitOff()) {                       // pi :959-961
                    put(builder, "thinking",
                        map.mapped(ModelThinkingLevel.off()).orElse("none"));
                }
            }
            case QWEN_CHAT_TEMPLATE -> {
                // pi :895-898 —— 固定形状、无门恒发，且不写 reasoning_effort。
                var kwargs = new LinkedHashMap<String, Object>();
                kwargs.put("enable_thinking", level.isPresent());
                kwargs.put("preserve_thinking", true);                        // pi :897
                put(builder, "chat_template_kwargs", kwargs);
            }
            case CHAT_TEMPLATE ->
                // pi :900-902 —— 空声明 ⇒ 什么都不写、**且不回落 openai**（事实 2）。
                chatTemplateValues(map, level, compat.chatTemplateKwargs(), thinkingBudget)
                    .ifPresent(values -> put(builder, "chat_template_kwargs", values));
            case BASETEN -> {
                // pi :909-911 —— args 走与 chat-template 同一个 $var 解析。
                chatTemplateValues(map, level, compat.chatTemplateArgs(), thinkingBudget)
                    .ifPresent(values -> put(builder, "chat_template_args", values));
                if (supportsEffort) {                                         // pi :913-918
                    // 无级别时取 map.off，且同 `=== undefined` 语义：缺席／显式 null ⇒
                    // 不写，字符串 ⇒ 写（pi 夹具 baseten-models.test.ts 的三态）。
                    var effort = level.isPresent()
                        ? strictEffort(map, level.get())
                        : map.mapped(ModelThinkingLevel.off());
                    effort.ifPresent(s -> builder.reasoningEffort(ReasoningEffort.of(s)));
                }
            }
        }
    }

    /**
     * pi {@code buildChatTemplateValues:1026-1042} —— 逐键解析声明表；
     * 空表（或全部键被删）⇒ {@link Optional#empty()}（pi 的 {@code undefined}
     * ⇒ 调用点不写 {@code chat_template_kwargs}/{@code chat_template_args}）。
     */
    private static Optional<Map<String, Object>> chatTemplateValues(
            ThinkingLevelMap map, Optional<ThinkingLevel> level,
            Map<String, ChatTemplateKwargValue> declared, OptionalInt thinkingBudget) {
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        var out = new LinkedHashMap<String, Object>();
        for (var entry : declared.entrySet()) {
            resolveKwarg(out, entry.getKey(), map, level, entry.getValue(), thinkingBudget);
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /**
     * pi {@code resolveChatTemplateKwargValue:1044-1067}。
     *
     * <p>⚠️ <b>直接写进 {@code out}、不用 {@code Optional} 收返回值</b>（R11）：pi 的
     * 字面量分支（{@code :1050} 的早返回）里 {@code null} 是<b>合法的上线值</b>
     * （{@code "key": null}），{@code Optional} 表达不了「值是 null」⇒ 用返回值就会把
     * {@code Literal(null)} 静默变成「不写」。本方法的 {@code return} 一律表示
     * 「这个键不写」。</p>
     */
    private static void resolveKwarg(Map<String, Object> out, String key, ThinkingLevelMap map,
                                     Optional<ThinkingLevel> level, ChatTemplateKwargValue value,
                                     OptionalInt thinkingBudget) {
        switch (value) {
            case ChatTemplateKwargValue.Literal literal ->
                out.put(key, literal.value());                                // pi :1050
            case ChatTemplateKwargValue.Var var -> {
                if (level.isEmpty() && var.omitWhenOff()) {
                    return;                                                   // pi :1055
                }
                switch (var.var()) {
                    case ENABLED -> out.put(key, level.isPresent());          // pi :1058
                    case BUDGET ->
                        // pi :1061 —— budget undefined ⇒ 键被删。
                        thinkingBudget.ifPresent(budget -> out.put(key, budget));
                    case EFFORT -> {
                        var target = level.map(ModelThinkingLevel::of);       // pi :1065
                        if (target.isPresent()) {
                            if (!map.hasEntry(target.get())) {
                                out.put(key, level.get().label());  // mappedValue === undefined
                            } else {
                                map.mapped(target.get()).ifPresent(s -> out.put(key, s));
                            }                                                 // 显式 null ⇒ 不写
                        } else {
                            map.mapped(ModelThinkingLevel.off())
                                .ifPresent(s -> out.put(key, s));
                        }                          // off 缺席／null ⇒ 连 undefined 都不写
                    }
                }
            }
        }
    }

    /**
     * {@code map?.[k] ?? k} 派（{@code 原 docs/58 §2.4} 第一行）：键缺席与显式 null
     * **同样**回落级别名。
     */
    private static Optional<String> orLevel(ThinkingLevelMap map, ThinkingLevel level) {
        return Optional.of(map.mapped(ModelThinkingLevel.of(level)).orElse(level.label()));
    }

    /**
     * {@code map?.[k] === undefined ? k : map[k]} 派 ＋ {@code typeof === "string"}
     * （{@code 原 docs/58 §2.4} 第二行，zai／baseten）：键缺席回落级别名，**显式 null 不写**。
     *
     * <p>⚠️ 与 {@link #orLevel} 的分歧只在「显式 null」一格，而夹取会把显式 null 的级别
     * 踢出可用集（{@code getSupportedThinkingLevels} 的 {@code mapped === null ⇒ false}）
     * ⇒ 在夹取后的级别上两个助手**语义等价**、R4 反向探针零红（变异体语义等价，
     * {@code 原 docs/58 §12} 记录）。照抄 pi 的两个表达式是**文本保真**，不是行为分歧。</p>
     */
    private static Optional<String> strictEffort(ThinkingLevelMap map, ThinkingLevel level) {
        if (!map.hasEntry(ModelThinkingLevel.of(level))) {
            return Optional.of(level.label());
        }
        return map.mapped(ModelThinkingLevel.of(level));      // 显式 null ⇒ 空 ⇒ 不写
    }

    /** 无 inclusion 设置的 mapper：建树保 {@code NullNode}（{@link #put} 的说明）。 */
    private static final ObjectMapper PLAIN_JSON = new ObjectMapper();

    /**
     * {@code putAdditionalBodyProperty} 的薄包装 —— ⚠️ **必须经树**
     * （{@code SdkJsonEscapeHatchTest.openAiAdditionalBodyPropertiesCanCarryAnExplicitNull}
     * 的实测）：SDK 自己的 mapper 带 NON_NULL inclusion ⇒ {@code JsonValue.from(map 含 null)}
     * 会**静默丢掉** null 值，而 R11 的 {@code Literal(null)} 恰恰要上线成
     * {@code "key": null}（pi 的 {@code JSON.stringify} 保留它）。先以无 inclusion 的
     * mapper 建树，{@code NullNode} 在树序列化里存活。
     */
    private static void put(ChatCompletionCreateParams.Builder builder, String key,
                            Object value) {
        builder.putAdditionalBodyProperty(key, JsonValue.from(PLAIN_JSON.valueToTree(value)));
    }
}
