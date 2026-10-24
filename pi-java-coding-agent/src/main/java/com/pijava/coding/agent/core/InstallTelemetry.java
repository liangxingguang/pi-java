package com.pijava.coding.agent.core;

/**
 * 批 20（B253）：pi {@code isInstallTelemetryEnabled}（telemetry.ts:3-13 逐字）
 * ——安装遥测门。{@code PI_TELEMETRY} 环境变量在场时按 truthy 裁决，否则读
 * settings 的 {@code enableInstallTelemetry}（默认 {@code true}，
 * settings-defaults.ts:31）。
 *
 * <p>本批唯一消费点是 attribution 头层（{@link ProviderAttribution}）；pi 的
 * 另一消费点 {@code reportInstallTelemetry}（interactive-mode.ts:1360-1376，
 * 向 pi.dev 报装）按裁决⑤不做——端点是 pi 产品的安装统计，pi-java 是独立制品。</p>
 */
public final class InstallTelemetry {

    private InstallTelemetry() {}

    /**
     * pi 默认参 {@code telemetryEnv = process.env.PI_TELEMETRY} 对位——
     * <b>逐调用求值</b>（运行期改环境变量即时生效，pi 同形）。
     *
     * @param enableInstallTelemetry settings 生效值（{@code null} ≙ 缺席 ⇒ 默认开）
     */
    public static boolean isEnabled(Boolean enableInstallTelemetry) {
        return isEnabled(enableInstallTelemetry, System.getenv("PI_TELEMETRY"));
    }

    /** pi 第二参全形（测试缝——pi 的默认参注入同手法）。 */
    public static boolean isEnabled(Boolean enableInstallTelemetry, String telemetryEnv) {
        return telemetryEnv != null ? isTruthyEnvFlag(telemetryEnv)
            : enableInstallTelemetry == null || enableInstallTelemetry;
    }

    /** pi {@code isTruthyEnvFlag}（telemetry.ts:3-6 逐字）：{@code "1"/"true"/"yes"}。 */
    private static boolean isTruthyEnvFlag(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return value.equals("1")
            || value.equalsIgnoreCase("true")
            || value.equalsIgnoreCase("yes");
    }
}
