package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 11 T4（docs/22 §3.7/§3.8，pi {@code http-dispatcher.ts:5/20-36} ＋
 * {@code settings-manager.ts:147-157/900-912/935-937} ＋ {@code sdk.ts:344-354}）：
 * {@code httpIdleTimeoutMs}/{@code websocketConnectTimeoutMs} 的解析、访问器与
 * provider 请求超时链（providerRetry.timeoutMs ?? httpIdleTimeoutMs 有效值；
 * 0/"disabled" ⇒ {@link Integer#MAX_VALUE}；缺席 ⇒ 300s——120s 旧值防回归）。
 */
class TimeoutSettingsTest {

    private static FileSettingsStorage storage(Path dir) {
        return new FileSettingsStorage(dir, dir);
    }

    // ── parseHttpIdleTimeoutMs（pi :20-36 逐字） ──────────────

    @Test
    void parsesNumbersWithFloor() {
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs(60_000)).hasValue(60_000);
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs(1_500.9)).hasValue(1_500);
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs(0)).hasValue(0);
    }

    @Test
    void disabledLiteralResolvesToZero() {
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs("disabled")).hasValue(0);
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs("  Disabled ")).hasValue(0);
    }

    @Test
    void numericStringParses() {
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs("45000")).hasValue(45_000);
    }

    @Test
    void emptyInvalidNegativeAndNonFiniteAreEmpty() {
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs("")).isEmpty();
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs("   ")).isEmpty();
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs("abc")).isEmpty();
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs(-5)).isEmpty();
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs(Double.NaN)).isEmpty();
        assertThat(TimeoutSettings.parseHttpIdleTimeoutMs(null)).isEmpty();
    }

    // ── parseTimeoutSetting（pi :147-157 逐字异常） ───────────

    @Test
    void invalidValueThrowsVerbatim() {
        assertThatThrownBy(() ->
            TimeoutSettings.parseTimeoutSetting("abc", "httpIdleTimeoutMs"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid httpIdleTimeoutMs setting: abc");
    }

    @Test
    void nullPassesThroughAsEmpty() {
        assertThat(TimeoutSettings.parseTimeoutSetting(null, "httpIdleTimeoutMs")).isEmpty();
    }

    // ── 访问器（SettingsNetworkAccessors） ────────────────────

    @Test
    void httpIdleTimeoutDefaultsTo300s(@TempDir Path dir) {
        var network = SettingsManager.withStorage(storage(dir)).network();

        assertThat(network.getHttpIdleTimeoutMs())
            .isEqualTo(TimeoutSettings.DEFAULT_HTTP_IDLE_TIMEOUT_MS);
    }

    @Test
    void httpIdleTimeoutReadsUnionShapes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),
            "{\"httpIdleTimeoutMs\":\"disabled\"}");
        assertThat(SettingsManager.withStorage(storage(dir)).network()
            .getHttpIdleTimeoutMs()).isZero();

        Files.writeString(dir.resolve("settings.json"), "{\"httpIdleTimeoutMs\":45000}");
        assertThat(SettingsManager.withStorage(storage(dir)).network()
            .getHttpIdleTimeoutMs()).isEqualTo(45_000);
    }

    /** pi :904-912 setter：负数抛逐字；合法值写全局域。 */
    @Test
    void setHttpIdleTimeoutValidatesAndPersists(@TempDir Path dir) {
        var manager = SettingsManager.withStorage(storage(dir));

        assertThatThrownBy(() -> manager.network().setHttpIdleTimeoutMs(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid httpIdleTimeoutMs setting: -1");

        manager.network().setHttpIdleTimeoutMs(90_000);
        manager.flush();
        assertThat(SettingsManager.withStorage(storage(dir)).network()
            .getHttpIdleTimeoutMs()).isEqualTo(90_000);
    }

    /** pi :935-937：websocketConnectTimeoutMs 缺席 ⇒ 空（无 300s 兜底）。 */
    @Test
    void webSocketConnectTimeoutHasNoDefault(@TempDir Path dir) throws Exception {
        assertThat(SettingsManager.withStorage(storage(dir)).network()
            .getWebSocketConnectTimeoutMs()).isEmpty();

        Files.writeString(dir.resolve("settings.json"),
            "{\"websocketConnectTimeoutMs\":5000}");
        assertThat(SettingsManager.withStorage(storage(dir)).network()
            .getWebSocketConnectTimeoutMs()).hasValue(5_000);
    }

    // ── 消费点：DefaultProviders.apiOptions（pi sdk.ts:344-354 链序） ──

    private static Duration timeoutOf(Settings settings) {
        return DefaultProviders.apiOptions(ArgsParser.parse(new String[0]),
            "anthropic", settings, null, null).timeout();
    }

    /** 缺席 ⇒ 300s（防回归：java 自造的 120s 兜底已删除）。 */
    @Test
    void apiOptionsFallsBackTo300sWhenAbsent() {
        assertThat(timeoutOf(new Settings())).isEqualTo(Duration.ofSeconds(300));
        assertThat(timeoutOf(null)).isEqualTo(Duration.ofSeconds(300));
    }

    /** "disabled" ⇒ 0 ⇒ {@code 2147483647}ms（pi sdk.ts:346 逐字）。 */
    @Test
    void apiOptionsDisabledMeansMaxInt() {
        var settings = new Settings();
        settings.httpIdleTimeoutMs = "disabled";

        assertThat(timeoutOf(settings)).isEqualTo(Duration.ofMillis(Integer.MAX_VALUE));
    }

    /** 链序：provider retry timeoutMs 优先于 httpIdleTimeoutMs。 */
    @Test
    void apiOptionsProviderRetryWins() {
        var settings = new Settings();
        settings.httpIdleTimeoutMs = 45_000;
        settings.retry = new Settings.Retry(null, null, null, null,
            new Settings.ProviderRetry(10_000L, null, null));

        assertThat(timeoutOf(settings)).isEqualTo(Duration.ofSeconds(10));

        // providerRetry 缺席 ⇒ httpIdleTimeoutMs 有效值接管。
        settings.retry = null;
        assertThat(timeoutOf(settings)).isEqualTo(Duration.ofSeconds(45));
    }
}
