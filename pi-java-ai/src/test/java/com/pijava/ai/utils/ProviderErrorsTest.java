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
}
