package com.pijava.ai.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIServiceException;

/**
 * Provider 错误归一化 —— pi {@code packages/ai/src/utils/error-body.ts} 的逐条移植。
 *
 * <p>各家 SDK 抛出的网关错误形状不一，pi 在车道 catch 里统一经过
 * {@code normalizeProviderError}（取 status / body / message 三件套）再由
 * {@code formatProviderError} 决定终局 errorMessage 文本。本类是同一套规则的
 * Java 版，供 openai 系四条车道（completions / responses / azure /
 * openrouter-images）共用。</p>
 *
 * <h3>openai-java 4.42.0 适配器（{@link #fromOpenAi}）为什么是「重放」</h3>
 *
 * <p>pi 钉的 openai-node 7.19.0 由 {@code APIError.makeMessage} 造异常文案，
 * bodyless 时逐字为 {@code "<status> status code (no body)"}；openai-java 同状态
 * 却给 {@code "400: Unknown"}（{@code BadRequestException.kt}）。好在 Java SDK 的
 * {@link OpenAIServiceException} 暴露了 {@code statusCode()} 与 {@code body()}
 * 两件原料：传输层 {@code ErrorHandler.errorBodyHandler} 已做过与 openai-node
 * {@code makeStatusError} 同构的归一化 —— 有 {@code error} 外层取嵌套节点，
 * 无外层则整个响应体，空体/不可解析为 {@link JsonMissing}。所以这里<b>不沿用 SDK
 * 文案</b>，而是用 status + body 节点重放 makeMessage，再过 normalize/format，
 * 与 pi 逐字对齐。</p>
 *
 * <p><b>已知方言点（不可恢复，不登记为差距）</b>：openai-node 对非 JSON 纯文本
 * 错误体会把原文透传进 makeMessage（{@code "<status> <raw text>"}）；Java SDK
 * 解析失败只留 {@link JsonMissing} 且无原始体文本 API，该形状只能落 no-body
 * 文案。主流可达的 provider 网关错误体均为 JSON（Cerebras bodyless 是本包
 * B141 的实际发火形状）。</p>
 */
public final class ProviderErrors {

    /** pi {@code MAX_PROVIDER_ERROR_BODY_CHARS}（error-body.ts:16）。 */
    public static final int MAX_PROVIDER_ERROR_BODY_CHARS = 4000;

    private ProviderErrors() {}

    /**
     * pi {@code NormalizedProviderError}（error-body.ts:20-27）。
     *
     * @param status             HTTP 状态码；{@code null} ≙ pi 的 {@code undefined}
     * @param body               清洗后的响应体（trim/空体缺席/截到 cap），可 {@code null}
     * @param message            异常原文案
     * @param messageCarriesBody pi {@code body === undefined || error.message.includes(body)}
     */
    public record Normalized(Integer status, String body, String message,
                             boolean messageCarriesBody) {}

    /**
     * pi {@code normalizeProviderError} 的核心：status/body/message 已由各 SDK
     * 适配器取出后的归一化。body 先 trim、空串视为缺席，再截到
     * {@link #MAX_PROVIDER_ERROR_BODY_CHARS}。
     */
    public static Normalized normalize(Integer status, String rawBody, String message) {
        String body = cleanBody(rawBody);
        boolean carries = body == null
            || (message != null && message.contains(body));
        return new Normalized(status, body, message == null ? "" : message, carries);
    }

    /**
     * pi {@code formatProviderError(norm)}（无前缀车道：completions/images）。
     */
    public static String format(Normalized normalized) {
        return format(normalized, null);
    }

    /**
     * pi {@code formatProviderError(norm, prefix)} 四分支真值表：
     *
     * <ul>
     *   <li>carries 或 status 缺 或 body 缺：前缀与 status 齐 ⇒
     *       {@code "<prefix> (<status>): <message>"}；否则裸 {@code message}
     *       （<b>内部错误即使在前缀车道也不套前缀</b>）。</li>
     *   <li>carries=false 且 status/body 齐：前缀 ⇒
     *       {@code "<prefix> (<status>): <body>"}；无前缀 ⇒ {@code "<status>: <body>"}。</li>
     * </ul>
     */
    public static String format(Normalized n, String prefix) {
        if (n.messageCarriesBody() || n.status() == null || n.body() == null) {
            if (prefix != null && n.status() != null) {
                return prefix + " (" + n.status() + "): " + n.message();
            }
            return n.message();
        }
        if (prefix != null) {
            return prefix + " (" + n.status() + "): " + n.body();
        }
        return n.status() + ": " + n.body();
    }

    /**
     * pi {@code truncateErrorText}：超长给 {@code 前 maxChars... [truncated N chars]}，
     * N 是砍掉的字符数（不是总长）。
     */
    public static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars)
            + "... [truncated " + (text.length() - maxChars) + " chars]";
    }

    /**
     * openai-java 4.x 适配器（completions/responses/azure/openrouter-images 共用）。
     *
     * <p>{@link OpenAIServiceException} ⇒ 取 {@code statusCode()}/ {@code body()}
     * 重放 openai-node 的 {@code makeMessage} 后再过 {@link #normalize}；其他
     * Throwable ⇒ status/body 缺席、message 透传（{@code getMessage()} 为 null 时
     * 回落 {@code toString()}，pi 侧 Error.message 恒为串、Java 可为 null）。</p>
     */
    public static Normalized fromOpenAi(Throwable error) {
        if (error instanceof OpenAIServiceException service) {
            JsonNode errorNode = toJsonNode(service.body());
            String message = makeNodeMessage(service.statusCode(), errorNode);
            // pi extractBody：只认真·plain 非空对象（error-body.ts:100-124）。
            String bodyText = isPlainNonEmptyObject(errorNode) ? writeJson(errorNode) : null;
            return normalize(service.statusCode(), bodyText, message);
        }
        String message = error.getMessage() != null ? error.getMessage() : error.toString();
        return normalize(null, null, message);
    }

    /**
     * B135：OpenRouter 把上游补充信息放在嵌套 error 节点的 {@code metadata.raw}
     * （pi {@code openai-completions.ts:723}：{@code error?.error?.metadata?.raw}）。
     * {@code service.body()} 已是嵌套 error 节点，故直接从 {@code metadata.raw} 读。
     *
     * <p>只认标量（文本/数字/布尔），且按 JS 真值过滤（空串/{@code 0}/{@code false}
     * 与缺席同）；其他类型给 {@code null}（pi 侧 {@code String(object)} 是
     * {@code "[object Object]"}，主流不可达，不移植）。</p>
     */
    public static String openRouterRawMetadata(Throwable error) {
        if (!(error instanceof OpenAIServiceException service)) {
            return null;
        }
        JsonNode node = toJsonNode(service.body());
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode metadata = node.get("metadata");
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        JsonNode raw = metadata.get("raw");
        if (raw == null || raw.isNull()) {
            return null;
        }
        if (raw.isTextual()) {
            return raw.asText().isEmpty() ? null : raw.asText();
        }
        if (raw.isBoolean()) {
            return raw.booleanValue() ? "true" : null;
        }
        if (raw.isNumber()) {
            return raw.numberValue().doubleValue() != 0.0 ? raw.asText() : null;
        }
        return null;
    }

    /**
     * pi {@code formatMistralError}（mistral-conversations.ts:271-281）。
     *
     * <p>Mistral 是裸 fetch 车道（异常是 {@link com.pijava.ai.http.PiHttpException}
     * 而非 SDK 异常），格式自成一家：有 body ⇒
     * {@code "Mistral API error (N): <body 原文截断>"}；只有状态码 ⇒
     * {@code "… : Request failed with status N"}（pi {@code MistralHttpError}
     * 的兜底 message，<b>不</b>回显 Java 异常里的 {@code "HTTP N"}）；
     * 非 HTTP 错误（statusCode 0 的网络错误/内部 throw）⇒ message 透传。</p>
     */
    public static String formatMistralError(Throwable error) {
        if (error instanceof com.pijava.ai.http.PiHttpException http) {
            int statusCode = http.statusCode();
            String rawBody = http.body();
            if (statusCode > 0 && rawBody != null && !rawBody.isBlank()) {
                return "Mistral API error (" + statusCode + "): "
                    + truncate(rawBody.trim(), MAX_PROVIDER_ERROR_BODY_CHARS);
            }
            if (statusCode > 0) {
                return "Mistral API error (" + statusCode
                    + "): Request failed with status " + statusCode;
            }
        }
        return error.getMessage();
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** body trim、空体缺席、截断（次序照 pi：trim/空判在截长之前）。 */
    private static String cleanBody(String rawBody) {
        if (rawBody == null) {
            return null;
        }
        String trimmed = rawBody.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return truncate(trimmed, MAX_PROVIDER_ERROR_BODY_CHARS);
    }

    /**
     * {@link JsonValue} 体 ⇒ Jackson 树；{@link JsonMissing}/JSON null/缺席 ⇒ {@code null}
     * （≙ openai-node 侧 {@code errorResponse?.['error']} 为 undefined）。
     */
    private static JsonNode toJsonNode(JsonValue body) {
        if (body == null || body instanceof JsonMissing) {
            return null;
        }
        JsonNode node = ObjectMappers.jsonMapper().convertValue(body, JsonNode.class);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node;
    }

    /**
     * 逐字重放 openai-node 7.19.0 {@code APIError.makeMessage(status, error, message)}。
     * 入参 {@code error} 是已归一化的嵌套节点（见类 javadoc）：
     * 文本 message 取文本、非文本 JSON 化；否则节点 truthy 整体 JSON 化；
     * status 与 msg 齐 ⇒ {@code "<status> <msg>"}，只有 status ⇒ no-body 文案。
     */
    private static String makeNodeMessage(int status, JsonNode error) {
        String msg = null;
        if (error != null && error.isObject() && error.has("message")
                && !error.get("message").isNull()) {
            JsonNode messageNode = error.get("message");
            msg = messageNode.isTextual() ? messageNode.asText() : writeJson(messageNode);
        } else if (jsTruthy(error)) {
            msg = writeJson(error);
        }
        if (status > 0 && msg != null && !msg.isEmpty()) {
            return status + " " + msg;
        }
        if (status > 0) {
            return status + " status code (no body)";
        }
        if (msg != null) {
            return msg;
        }
        return "(no status code or body)";
    }

    /**
     * JS truthiness（{@code Boolean(value)}）：null/false/0/空串为假，
     * 其余为真 —— <b>空对象 {@code {}} 与空数组也是真</b>，别按 size 判。
     */
    private static boolean jsTruthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue().doubleValue() != 0.0;
        }
        if (node.isTextual()) {
            return !node.textValue().isEmpty();
        }
        return true;
    }

    /**
     * pi {@code isPlainNonEmptyObject}：对象 ∧ 原型是 Object.prototype ∧ 至少一键。
     * Jackson {@code convertValue} 只产出标准 {@link ObjectNode}（类实例检查对应
     * 原型检查；流对象在 JSON 树里本就不存在）。
     */
    private static boolean isPlainNonEmptyObject(JsonNode node) {
        return node != null && node.getClass() == ObjectNode.class && node.size() > 0;
    }

    /** JSON 树 ⇒ JSON 文本（JsonNode 实际不会抛，catch 回落 toString 对应 pi safeJsonStringify）。 */
    private static String writeJson(JsonNode node) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return node.toString();
        }
    }
}
