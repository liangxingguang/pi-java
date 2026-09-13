package com.pijava.ai.utils;

import java.util.List;
import java.util.regex.Pattern;

import com.pijava.ai.message.Message;

/**
 * 上下文溢出判定 —— pi {@code packages/ai/src/utils/overflow.ts} 的逐条移植
 * （package 3c，{@code docs/31 §8.21}）。与 pi 同层（ai 工具层）：溢出是
 * <b>provider 响应形状</b>的知识，不是循环策略 —— harness 的自动压缩
 * （{@code _checkCompaction}）与宿主的自动重试排除（{@code _isRetryableError} 首行）
 * 都调这一个谓词。
 *
 * <p><b>三种情形</b>（pi 注释原文的分类）：</p>
 * <ol>
 *   <li><b>显式错误溢出</b>：{@code stopReason == "error"} 且 errorMessage 命中
 *       {@code OVERFLOW_PATTERNS} 之一且不命中 {@code NON_OVERFLOW_PATTERNS}
 *       （限流/ throttle 伪装成溢出文本的排除项，overflow.ts:74-78）。</li>
 *   <li><b>静默溢出</b>（z.ai 型）：请求被接受、正常收尾，但
 *       {@code usage.input + usage.cacheRead > contextWindow}。需要窗口值；
 *       窗口缺席（pi 的 {@code undefined}）⇒ 判不出，返回 false。</li>
 *   <li><b>length 截断型</b>（Xiaomi MiMo 型）：服务端把输入截满上下文窗口后以
 *       {@code stopReason == "length"} + {@code output == 0} 收尾，
 *       判据是输入占满窗口（{@code >= window * 0.99}）。</li>
 * </ol>
 *
 * <p><b>Java 方言映射</b>：JS 正则的 {@code test()} 是非锚定搜索 ⇒ Java
 * {@code Matcher.find()}（锚定模式里的 {@code ^} 两边同为「串首」语义）；
 * {@code /i} ⇒ {@code CASE_INSENSITIVE}。pi 的 {@code contextWindow?: number}
 * 的真值门（{@code contextWindow &&}）在 JS 里 null/undefined/0 皆假 ⇒ Java 取
 * {@code window != null && window > 0}（解析器只会给非负值，负数情形不存在）。
 * pi 的 usage 在类型上必填，pi-java 的 {@code null ≙ 缺数据} ⇒ case 2/3 跳过。</p>
 */
public final class ContextOverflow {

    /** pi {@code OVERFLOW_PATTERNS}（overflow.ts:37-63），顺序与注释逐条对应。 */
    private static final List<Pattern> OVERFLOW_PATTERNS = List.of(
        Pattern.compile("prompt is too long", Pattern.CASE_INSENSITIVE), // Anthropic token overflow
        Pattern.compile("request_too_large", Pattern.CASE_INSENSITIVE), // Anthropic request byte-size overflow (HTTP 413)
        Pattern.compile("input is too long for requested model", Pattern.CASE_INSENSITIVE), // Amazon Bedrock
        Pattern.compile("exceeds the context window", Pattern.CASE_INSENSITIVE), // OpenAI (Completions & Responses API)
        Pattern.compile("exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))", Pattern.CASE_INSENSITIVE), // OpenAI-compatible proxies (LiteLLM)
        Pattern.compile("input token count.*exceeds the maximum", Pattern.CASE_INSENSITIVE), // Google (Gemini)
        Pattern.compile("maximum prompt length is \\d+", Pattern.CASE_INSENSITIVE), // xAI (Grok)
        Pattern.compile("reduce the length of the messages", Pattern.CASE_INSENSITIVE), // Groq
        Pattern.compile("maximum context length is \\d+ tokens", Pattern.CASE_INSENSITIVE), // OpenRouter (most backends)
        Pattern.compile("exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?", Pattern.CASE_INSENSITIVE), // OpenRouter/Poolside
        Pattern.compile("input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)", Pattern.CASE_INSENSITIVE), // Together AI
        Pattern.compile("exceeds the limit of \\d+", Pattern.CASE_INSENSITIVE), // GitHub Copilot
        Pattern.compile("exceeds the available context size", Pattern.CASE_INSENSITIVE), // llama.cpp server
        Pattern.compile("greater than the context length", Pattern.CASE_INSENSITIVE), // LM Studio
        Pattern.compile("context window exceeds limit", Pattern.CASE_INSENSITIVE), // MiniMax
        Pattern.compile("exceeded model token limit", Pattern.CASE_INSENSITIVE), // Kimi For Coding
        Pattern.compile("too large for model with \\d+ maximum context length", Pattern.CASE_INSENSITIVE), // Mistral
        Pattern.compile("prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?", Pattern.CASE_INSENSITIVE), // DS4 server
        Pattern.compile("model_context_window_exceeded", Pattern.CASE_INSENSITIVE), // z.ai non-standard finish_reason surfaced as error text
        Pattern.compile("prompt too long; exceeded (?:max )?context length", Pattern.CASE_INSENSITIVE), // Ollama explicit overflow error
        Pattern.compile("range of input length should be", Pattern.CASE_INSENSITIVE), // DashScope / Qwen Token Plan
        Pattern.compile("context[_ ]length[_ ]exceeded", Pattern.CASE_INSENSITIVE), // Generic fallback
        Pattern.compile("too many tokens", Pattern.CASE_INSENSITIVE), // Generic fallback
        Pattern.compile("token limit exceeded", Pattern.CASE_INSENSITIVE), // Generic fallback
        Pattern.compile("^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)", Pattern.CASE_INSENSITIVE) // Cerebras: 400/413 with no body
    );

    /**
     * pi {@code NON_OVERFLOW_PATTERNS}（overflow.ts:74-78）：命中任何一条即
     * <b>不算</b>溢出 —— 例如 Bedrock 限流文本 "Too many tokens, please wait"
     * 会误撞 overflow 的 {@code /too many tokens/}，靠这里排除。
     */
    private static final List<Pattern> NON_OVERFLOW_PATTERNS = List.of(
        Pattern.compile("^(Throttling error|Service unavailable):", Pattern.CASE_INSENSITIVE), // AWS Bedrock non-overflow errors
        Pattern.compile("rate limit", Pattern.CASE_INSENSITIVE), // Generic rate limiting
        Pattern.compile("too many requests", Pattern.CASE_INSENSITIVE) // Generic HTTP 429 style
    );

    private ContextOverflow() {}

    /**
     * pi {@code isContextOverflow(message, contextWindow?)}（overflow.ts:134-163）。
     *
     * @param message       待判定的助手消息（终局 {@link Message.AssistantMessage}）
     * @param contextWindow 当前模型的上下文窗口；{@code null} 或 0 ≙ pi 的
     *                      {@code undefined}（{@code ?? 0} 的 falsy）⇒ case 2/3
     *                      短路，只剩 case 1
     */
    public static boolean isContextOverflow(Message.AssistantMessage message, Integer contextWindow) {
        // Case 1: error-message patterns.
        if ("error".equals(message.stopReason()) && message.errorMessage() != null) {
            String errorMessage = message.errorMessage();
            boolean isNonOverflow = false;
            for (var pattern : NON_OVERFLOW_PATTERNS) {
                if (pattern.matcher(errorMessage).find()) {
                    isNonOverflow = true;
                    break;
                }
            }
            if (!isNonOverflow) {
                for (var pattern : OVERFLOW_PATTERNS) {
                    if (pattern.matcher(errorMessage).find()) {
                        return true;
                    }
                }
            }
        }

        // Case 2: silent overflow (z.ai style) — finished successfully but input fills past the window.
        boolean windowKnown = contextWindow != null && contextWindow > 0;
        if (windowKnown && "stop".equals(message.stopReason()) && message.usage() != null) {
            double inputTokens = message.usage().input() + message.usage().cacheRead();
            if (inputTokens > contextWindow) {
                return true;
            }
        }

        // Case 3: length-stop overflow (Xiaomi MiMo style) — input truncated to exactly fill
        // the window, leaving no room for output: stopReason "length", output 0, input filling it.
        if (windowKnown && "length".equals(message.stopReason()) && message.usage() != null
                && message.usage().output() == 0) {
            double inputTokens = message.usage().input() + message.usage().cacheRead();
            if (inputTokens >= contextWindow * 0.99) {
                return true;
            }
        }

        return false;
    }

    /**
     * pi {@code isRecoverableLength(message, desiredMaxOutput)}（overflow.ts:171-173）：
     * length 收尾且输出低于<b>钳制前</b>的意图上限 ⇒ 可能被上下文压力截短，
     * 允许一次有界的 compact-and-retry。
     *
     * @param desiredMaxOutput 原始输出上限（pi 的 {@code model.maxTokens}，未经
     *                         context 钳制）；0/负 ⇒ 与 pi 同样恒 false
     */
    public static boolean isRecoverableLength(Message.AssistantMessage message, long desiredMaxOutput) {
        return "length".equals(message.stopReason()) && desiredMaxOutput > 0
            && message.usage() != null && message.usage().output() < desiredMaxOutput;
    }

    /** pi {@code getOverflowPatterns()}：只为测试暴露，不移植 —— 哨兵测试直接断言行为表。 */
}
