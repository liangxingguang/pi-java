package com.pijava.ai.utils;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.models.ErrorObject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProviderErrors} 单元表 —— 对应 pi {@code packages/ai/test/error-body.test.ts}
 * 的真值表（normalize/format/truncate）加 openai-java 4.42.0 适配器
 * （{@link ProviderErrors#fromOpenAi}）的 SDK 夹具。
 *
 * <p>车道级真 HTTP 夹具在 {@code protocol/ProviderErrorBodyLanesTest}。</p>
 */
class ProviderErrorsTest {

    // ── normalize/format 四分支真值表（pi error-body.test.ts）──────────────

    /** body 未折进 message ⇒ 无前缀格式 {@code "<status>: <body>"}。 */
    @Test
    void bodyNotFoldedIntoMessageRendersStatusColonBody() {
        var normalized = ProviderErrors.normalize(403, "{\"error\":\"x\"}", "403 something else");

        assertThat(normalized.messageCarriesBody()).isFalse();
        assertThat(normalized.body()).isEqualTo("{\"error\":\"x\"}");
        assertThat(ProviderErrors.format(normalized)).isEqualTo("403: {\"error\":\"x\"}");
    }

    /** message 已含 body ⇒ carries=true，format 回裸 message。 */
    @Test
    void messageAlreadyCarryingBodyIsReturnedBare() {
        var normalized = ProviderErrors.normalize(403, "boom", "failure: boom");

        assertThat(normalized.messageCarriesBody()).isTrue();
        assertThat(ProviderErrors.format(normalized)).isEqualTo("failure: boom");
    }

    /** body trim；空白/缺席 ⇒ 无 body。 */
    @Test
    void blankBodyIsAbsent() {
        assertThat(ProviderErrors.normalize(400, "   ", "msg").body()).isNull();
        assertThat(ProviderErrors.normalize(400, null, "msg").body()).isNull();
    }

    /** 截长后缀逐字（pi {@code truncateErrorText}）：{@code ... [truncated N chars]}。 */
    @Test
    void truncationUsesPiSuffix() {
        String truncated = ProviderErrors.truncate("x".repeat(4050), 4000);

        assertThat(truncated).startsWith("x".repeat(4000));
        assertThat(truncated).endsWith("... [truncated 50 chars]");
        assertThat(truncated).hasSize(4000 + "... [truncated 50 chars]".length());
    }

    /** 前缀 + 齐 status/body ⇒ {@code "<prefix> (<status>): <body>"}。 */
    @Test
    void prefixWithBodyWrapsBody() {
        var normalized = ProviderErrors.normalize(403, "{\"error\":\"x\"}", "403 something else");

        assertThat(ProviderErrors.format(normalized, "OpenAI API error"))
            .isEqualTo("OpenAI API error (403): {\"error\":\"x\"}");
    }

    /** 前缀 + carries ⇒ body 换成 message。 */
    @Test
    void prefixWithCarriedBodyWrapsMessage() {
        var normalized = ProviderErrors.normalize(403, "boom", "failure: boom");

        assertThat(ProviderErrors.format(normalized, "OpenAI API error"))
            .isEqualTo("OpenAI API error (403): failure: boom");
    }

    /**
     * 前缀但 status 缺（车道收尾 throw 的内部错误）⇒ <b>裸 message，不加前缀</b>
     * （pi {@code formatProviderError} 的 {@code status !== undefined} 第二道门）。
     */
    @Test
    void prefixWithoutStatusStaysBare() {
        var normalized = ProviderErrors.normalize(null, null,
            "Stream ended without finish_reason");

        assertThat(ProviderErrors.format(normalized, "OpenAI API error"))
            .isEqualTo("Stream ended without finish_reason");
    }

    // ── openai-java 4.42.0 适配器（makeMessage 重放）───────────────────────

    /**
     * 直接用 SDK builder 造异常：空体 ⇒ 重放 openai-node 的
     * {@code "400 status code (no body)"} 逐字（不是 SDK 自己的 {@code "400: Unknown"}）；
     * 嵌套 error 仅 message 字段 ⇒ message 折叠为 {@code "400 x"}、body 取整对象 JSON。
     */
    @Test
    void fromOpenAiReplaysNodeMakeMessage() {
        Headers headers = Headers.builder().build();

        var bodyless = BadRequestException.builder().headers(headers).build();
        var noBody = ProviderErrors.fromOpenAi(bodyless);
        assertThat(noBody.status()).isEqualTo(400);
        assertThat(noBody.body()).isNull();
        assertThat(noBody.messageCarriesBody()).isTrue();
        assertThat(noBody.message()).isEqualTo("400 status code (no body)");
        assertThat(ProviderErrors.format(noBody)).isEqualTo("400 status code (no body)");

        // ErrorObject.builder() 按线格校验 code/type 必填（传输层走 JSON 反序列化不经此校验）。
        var withError = BadRequestException.builder().headers(headers)
            .error(ErrorObject.builder().code("c").message("x").param("p")
                .type("bad_request").build())
            .build();
        var withBody = ProviderErrors.fromOpenAi(withError);
        assertThat(withBody.status()).isEqualTo(400);
        assertThat(withBody.message()).isEqualTo("400 x");
        assertThat(withBody.body())
            .isEqualTo("{\"code\":\"c\",\"message\":\"x\",\"param\":\"p\","
                + "\"type\":\"bad_request\"}");
        assertThat(ProviderErrors.format(withBody))
            .isEqualTo("400: {\"code\":\"c\",\"message\":\"x\",\"param\":\"p\","
                + "\"type\":\"bad_request\"}");
    }

    /** 非 SDK 异常 ⇒ status/body 缺、message 透传；前缀车道也不套前缀。 */
    @Test
    void nonSdkThrowablePassesThroughWithoutStatus() {
        var normalized = ProviderErrors.fromOpenAi(
            new IllegalStateException("Stream ended without finish_reason"));

        assertThat(normalized.status()).isNull();
        assertThat(normalized.body()).isNull();
        assertThat(normalized.message()).isEqualTo("Stream ended without finish_reason");
        assertThat(ProviderErrors.format(normalized, "OpenAI API error"))
            .isEqualTo("Stream ended without finish_reason");
    }

    // ── pi-messages 车道格式（B224，pi pi-messages.ts:124-131 真值表）──────

    /** T1：message + code 齐 ⇒ {@code "<status> <phrase>: <message> (<code>)"}。 */
    @Test
    void piMessagesMessageAndCodeSuffix() {
        assertThat(ProviderErrors.formatPiMessagesError(401,
            "{\"error\":{\"message\":\"Token expired\",\"code\":\"unauthorized\"}}"))
            .isEqualTo("401 Unauthorized: Token expired (unauthorized)");
    }

    /** T2：只有 message ⇒ 无 code 后缀。 */
    @Test
    void piMessagesMessageWithoutCode() {
        assertThat(ProviderErrors.formatPiMessagesError(400,
            "{\"error\":{\"message\":\"bad\"}}"))
            .isEqualTo("400 Bad Request: bad");
    }

    /** T3：message/code 都是空串 ⇒ suffix 空（?? 不回落 body）、code 真值门不加后缀。 */
    @Test
    void piMessagesEmptyMessageAndCodeGivesEmptySuffix() {
        assertThat(ProviderErrors.formatPiMessagesError(400,
            "{\"error\":{\"message\":\"\",\"code\":\"\"}}"))
            .isEqualTo("400 Bad Request: ");
    }

    /** T4：空体 ⇒ parse 失败 ⇒ suffix 裸 body（空串）。 */
    @Test
    void piMessagesEmptyBody() {
        assertThat(ProviderErrors.formatPiMessagesError(400, "")).isEqualTo("400 Bad Request: ");
        assertThat(ProviderErrors.formatPiMessagesError(400, null)).isEqualTo("400 Bad Request: ");
    }

    /** T5：非 JSON 纯文本 ⇒ 裸 body 兜底。 */
    @Test
    void piMessagesPlainTextFallsBackToBody() {
        assertThat(ProviderErrors.formatPiMessagesError(413, "policy overflow"))
            .isEqualTo("413 Payload Too Large: policy overflow");
    }

    /** T6：error 是标量 ⇒ 解析门不通过 ⇒ 裸 body。 */
    @Test
    void piMessagesScalarErrorFallsBackToBody() {
        assertThat(ProviderErrors.formatPiMessagesError(400, "{\"error\":\"boom\"}"))
            .isEqualTo("400 Bad Request: {\"error\":\"boom\"}");
    }

    /** T7：error 是数组 ⇒ 同上，裸 body。 */
    @Test
    void piMessagesArrayErrorFallsBackToBody() {
        assertThat(ProviderErrors.formatPiMessagesError(400, "{\"error\":[]}"))
            .isEqualTo("400 Bad Request: {\"error\":[]}");
    }

    /** T8：message 非字符串（数字）⇒ message 缺席，回落裸 body；code 非串同理。 */
    @Test
    void piMessagesNonStringMessageFallsBackToBody() {
        assertThat(ProviderErrors.formatPiMessagesError(400, "{\"error\":{\"message\":42}}"))
            .isEqualTo("400 Bad Request: {\"error\":{\"message\":42}}");
    }

    /** T9：抛出文案不截断（8192 截断属于诊断 details，B225）。 */
    @Test
    void piMessagesLongBodyIsNotTruncated() {
        String raw = "x".repeat(10000);
        String formatted = ProviderErrors.formatPiMessagesError(400, raw);
        assertThat(formatted).startsWith("400 Bad Request: ");
        assertThat(formatted.substring("400 Bad Request: ".length())).isEqualTo(raw);
        assertThat(formatted).doesNotContain("…");
    }

    /** T10：未知状态码 ⇒ phrase 空串 ⇒ {@code "599 : <suffix>"}。 */
    @Test
    void piMessagesUnknownStatusHasEmptyPhrase() {
        assertThat(ProviderErrors.formatPiMessagesError(599, "weird"))
            .isEqualTo("599 : weird");
    }

    /** RFC 标准短语表：标准码给短语，未知码给空串（≙ pi 空 statusText）。 */
    @Test
    void reasonPhrasesAreRfcStandardOrEmpty() {
        assertThat(ProviderErrors.reasonPhrase(413)).isEqualTo("Payload Too Large");
        assertThat(ProviderErrors.reasonPhrase(429)).isEqualTo("Too Many Requests");
        assertThat(ProviderErrors.reasonPhrase(500)).isEqualTo("Internal Server Error");
        assertThat(ProviderErrors.reasonPhrase(599)).isEmpty();
    }
}
