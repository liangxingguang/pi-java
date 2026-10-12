package com.pijava.ai.protocol;

import java.util.List;
import java.util.Map;

/**
 * 流式 {@code delta.content} 数组单项的解析结果（B80）——
 * pi {@code mistral-conversations.ts:643-706} 三支的静态对应。
 *
 * <p>Mistral 的 {@code CompletionChunk} 上 {@code delta.content} 既可能是字符串
 * （经典 Chat Completions 形状），也可能是块数组（逐块流式的 reasoning 模型、
 * Mistral 托管的 GLM 模型）。数组里 pi 认三种元素：</p>
 * <ul>
 *   <li>裸字符串 ⇒ {@link Text}（{@code :646}）；</li>
 *   <li>{@code {type:"thinking", thinking:[{text?}]}} ⇒ {@link Thinking}，
 *       parts 按 {@code :668-671} 的 {@code map(text ?? "").filter(非空).join("")} 拼好；</li>
 *   <li>{@code {type:"text", text?}} ⇒ {@link Text}（{@code :690}）；</li>
 *   <li>其余 type / 非串非对象元素 ⇒ {@link Ignored}（pi 无 else 支，静默忽略）。</li>
 * </ul>
 *
 * <p><b>净化不在这里做</b>：pi 在各调用点 {@code sanitizeSurrogates}（{@code :647/:672/:691}），
 * 本类只取原文；空串/跳过判定（含 GLM 的空 delta）也在车道调用点，与 pi 同位置。</p>
 */
sealed interface MistralContentDelta permits MistralContentDelta.Text,
        MistralContentDelta.Thinking, MistralContentDelta.Ignored {

    /** 串支①与类型化 text 支③合一；{@code text} 是**未净化**原文。 */
    record Text(String text) implements MistralContentDelta {}

    /** 支②：thinking parts 已拼接（未净化）。 */
    record Thinking(String text) implements MistralContentDelta {}

    /** 未知 type / 非串非对象元素（pi 无 else ⇒ 静默忽略）。 */
    record Ignored() implements MistralContentDelta {}

    /**
     * pi {@code mistral-conversations.ts:646-706} 的单项分派。
     *
     * @param item 串形态归一后的数组元素，或数组形态的一个 JSON 节点（{@code Map}）
     */
    static MistralContentDelta ofItem(Object item) {
        if (item instanceof String s) {
            return new Text(s);
        }
        if (item instanceof Map<?, ?> map && map.get("type") instanceof String type) {
            return switch (type) {
                case "thinking" -> new Thinking(joinThinking(map.get("thinking")));
                case "text" -> new Text(map.get("text") instanceof String s ? s : "");
                default -> new Ignored();
            };
        }
        return new Ignored();
    }

    /**
     * pi {@code mistral-conversations.ts:668-671}：
     * {@code (item.thinking ?? []).map(part => part.text ?? "").filter(非空).join("")}。
     * 非数组/空 ⇒ 空串；part 不是对象、{@code text} 不是字符串都按缺席处理。
     */
    private static String joinThinking(Object raw) {
        if (!(raw instanceof List<?> parts)) {
            return "";
        }
        var sb = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> p && p.get("text") instanceof String s && !s.isEmpty()) {
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
