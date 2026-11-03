package com.pijava.ai.identity;

import org.jspecify.annotations.Nullable;

/**
 * How a request identifies the calling app.
 *
 * <p>pi {@code ClientIdentity}（{@code packages/ai/src/utils/identity.ts:26-37}）：
 * 所有字段都可缺席；缺省是 detailed {@code User-Agent} 且无归因头。</p>
 *
 * <p>tri-state 只落在 {@code attribution} 上：{@code null} 的 {@link ClientAttribution}
 * 无法区分「缺席」与「显式关闭」，故用 {@link AttributionSetting}。</p>
 *
 * @param userAgent   UA 模式；{@code null} ≙ pi {@code undefined}（走 detailed）
 * @param attribution 归因三态；永不为 {@code null}（缺省 {@link AttributionSetting#UNSET}）
 * @param version     detailed UA 的版本；{@code null} ≙ pi {@code undefined}（走包版本）
 */
public record ClientIdentity(
    @Nullable UserAgentMode userAgent,
    AttributionSetting attribution,
    @Nullable String version
) {

    public ClientIdentity {
        attribution = attribution == null ? AttributionSetting.UNSET : attribution;
    }

    /** 空身份：三字段全部缺席（≙ pi 的 {@code {}}）。 */
    public static ClientIdentity empty() {
        return new ClientIdentity(null, AttributionSetting.UNSET, null);
    }

    /**
     * pi {@code mergeClientIdentity}（{@code identity.ts:49-60}）：后者逐字段赢。
     *
     * <p>{@code null} 源对象被跳过；全部源为 {@code null} ⇒ 返回 {@code null}
     * （≙ pi 的 {@code undefined}）。非 {@code null} 但三字段全缺席的源仍产出
     * 一个空身份（≙ pi 的 {@code {}}）。</p>
     */
    public static @Nullable ClientIdentity merge(@Nullable ClientIdentity... sources) {
        if (sources == null) {
            return null;
        }
        ClientIdentity merged = null;
        boolean seen = false;
        for (ClientIdentity source : sources) {
            if (source == null) {
                continue;
            }
            seen = true;
            UserAgentMode userAgent = merged == null ? null : merged.userAgent();
            AttributionSetting attribution = merged == null
                ? AttributionSetting.UNSET : merged.attribution();
            String version = merged == null ? null : merged.version();
            if (source.userAgent() != null) {
                userAgent = source.userAgent();
            }
            if (source.attribution() != AttributionSetting.UNSET) {
                attribution = source.attribution();
            }
            if (source.version() != null) {
                version = source.version();
            }
            merged = new ClientIdentity(userAgent, attribution, version);
        }
        return seen ? merged : null;
    }
}
