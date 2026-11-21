package com.pijava.ai.protocol;

import java.util.Map;
import java.util.Set;

import com.anthropic.errors.AnthropicServiceException;
import com.pijava.ai.message.AssistantMessageDiagnostic;

/**
 * {@code docs/12} S8（pi {@code sendThreadRequest :401-443}）：线程拒绝的**一次性**分流。
 *
 * <p>三类判定，逐字对齐 pi：</p>
 * <ol>
 * <li>{@code error.details.error_code ∈ NEW_THREAD_ERRORS} ⇒ 旧线程续不上了，**以 {@code create}
 *     完整重发一次**（新线程覆盖旧状态）；</li>
 * <li>{@code error_code === "thread_unsupported_request"}，或 <b>无 code ＋ 400 ＋ 文案含
 *     {@code thread}</b> ⇒ 该端点不认 {@code thread} 字段（典型是 {@code anthropic-beta} 少了
 *     threads beta），**去掉 {@code thread} 整请求重发**，并把会话置 {@code "off"}；</li>
 * <li>其余（prompt too long、unknown model、strict 工具超限……）**原样抛出** —— 与线程无关，
 *     重发只会再失败一次。</li>
 * </ol>
 *
 * <p>⚠️ 诊断在重发**之前**追加（pi 同式）：重发失败也留痕，读者能看出「这一轮试过续线程」。</p>
 */
final class AnthropicThreadFallback {

    /**
     * 旧线程无法继续的四个 code（pi {@code NEW_THREAD_ERRORS}，{@code :318-323}）。
     */
    private static final Set<String> NEW_THREAD_ERRORS = Set.of(
        "thread_not_found",
        "thread_already_continued",
        "thread_continue_in_flight",
        "thread_fingerprint_mismatch");

    /** 端点明确不支 threads（pi {@code :431}）。 */
    private static final String UNSUPPORTED_CODE = "thread_unsupported_request";

    private AnthropicThreadFallback() {
    }

    /** 分流结论 ＋ 诊断里的 {@code reason}（{@code error_code ?? status}，pi {@code :436}）。 */
    record Decision(Kind kind, String reason) {

        boolean none() {
            return kind == Kind.NONE;
        }
    }

    /** 重发形态。 */
    enum Kind {
        /** 与线程无关：原样抛出。 */
        NONE,
        /** 用完整请求以 {@code create} 重发一次。 */
        NEW_THREAD,
        /** 去掉 {@code thread} 重发，并把会话置 {@code off}。 */
        DROP_THREAD
    }

    /**
     * pi {@code :414-433} 的判序：先看 code 命不命中四个「续不上」，再看端点支不支持。
     *
     * <p>⚠️ 第二支的「无 code ＋ 400 ＋ 文案含 thread」是 {@code thread} 字段被当成未知键的场景
     * （{@code "thread: Extra inputs are not permitted"}）—— 它<b>没有</b> code，只能靠三条件合取
     * 识别；只看 400 或只看文案都会误伤无关请求。</p>
     */
    static Decision classify(AnthropicServiceException error) {
        var code = errorCode(error);
        if (code != null && NEW_THREAD_ERRORS.contains(code)) {
            return new Decision(Kind.NEW_THREAD, code);
        }
        if (UNSUPPORTED_CODE.equals(code)) {
            return new Decision(Kind.DROP_THREAD, code);
        }
        if (code == null && error.statusCode() == 400 && mentionsThread(error.getMessage())) {
            return new Decision(Kind.DROP_THREAD, String.valueOf(error.statusCode()));
        }
        return new Decision(Kind.NONE, code != null ? code : String.valueOf(error.statusCode()));
    }

    /**
     * 从响应体读 {@code error.details.error_code}（pi {@code getThreadErrorCode :389-393}）。
     *
     * <p>只有**字符串**才算（pi 的 {@code typeof code === "string"}）；体不是 JSON、形状不同、
     * 值不是字符串一律 {@code null} —— 与「没有 code」同义，交由第二支的文案判定。</p>
     */
    static String errorCode(AnthropicServiceException error) {
        try {
            if (!(error.body().convert(Object.class) instanceof Map<?, ?> root)) {
                return null;
            }
            if (!(root.get("error") instanceof Map<?, ?> body)) {
                return null;
            }
            if (!(body.get("details") instanceof Map<?, ?> details)) {
                return null;
            }
            return details.get("error_code") instanceof String code && !code.isBlank()
                ? code : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * pi {@code :435-441} 的诊断：
     * {@code {type:"anthropic_thread_fallback", timestamp, error:{message}, details:{reason}}}。
     */
    static AssistantMessageDiagnostic diagnostic(Decision decision,
                                                 AnthropicServiceException error) {
        var details = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        details.put("reason", decision.reason());
        return new AssistantMessageDiagnostic(
            "anthropic_thread_fallback",
            System.currentTimeMillis(),
            new AssistantMessageDiagnostic.DiagnosticErrorInfo(null, error.getMessage()),
            details);
    }

    /** pi 的 {@code error.message.includes("thread")}：大小写敏感，逐字。 */
    private static boolean mentionsThread(String message) {
        return message != null && message.contains("thread");
    }
}
