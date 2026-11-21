package com.pijava.ai.provider.builtin;

import java.util.List;

import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.provider.ConfigurableProvider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/11 S1–S6：五个 Token Plan provider 的元数据、密钥链与目录条数。
 *
 * <p>值逐字照 pi 锚点 {@code a00a5048d}：{@code packages/ai/src/providers/*.ts:8-17}
 * 与 {@code packages/ai/src/env-api-keys.ts:84-90,121-124}。</p>
 */
class TokenPlanProvidersTest {

    @Test
    void qwenTeamInternationalMetadataAndChain() {
        assertConfig(new QwenTokenPlanProvider(), "qwen-token-plan", "Qwen Token Plan Team",
            "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1",
            List.of("QWEN_TOKEN_PLAN_TEAM_API_KEY", "QWEN_TOKEN_PLAN_API_KEY"));
    }

    @Test
    void qwenTeamChinaMetadataAndChain() {
        assertConfig(new QwenTokenPlanCnProvider(), "qwen-token-plan-cn", "Qwen Token Plan Team CN",
            "https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1",
            List.of("QWEN_TOKEN_PLAN_TEAM_CN_API_KEY", "QWEN_TOKEN_PLAN_CN_API_KEY"));
    }

    @Test
    void qwenIndividualInternationalMetadataAndChain() {
        assertConfig(new QwenTokenPlanIndividualProvider(), "qwen-token-plan-individual",
            "Qwen Token Plan Individual",
            "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1",
            List.of("QWEN_TOKEN_PLAN_INDIVIDUAL_API_KEY", "QWEN_TOKEN_PLAN_API_KEY"));
    }

    @Test
    void qwenIndividualChinaMetadataAndChain() {
        assertConfig(new QwenTokenPlanIndividualCnProvider(), "qwen-token-plan-individual-cn",
            "Qwen Token Plan Individual CN",
            "https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1",
            List.of("QWEN_TOKEN_PLAN_INDIVIDUAL_CN_API_KEY", "QWEN_TOKEN_PLAN_CN_API_KEY"));
    }

    @Test
    void xiaomiRegionalMetadataAndSingleKeys() {
        assertConfig(new XiaomiTokenPlanAmsProvider(), "xiaomi-token-plan-ams",
            "Xiaomi Token Plan AMS", "https://token-plan-ams.xiaomimimo.com/v1",
            List.of("XIAOMI_TOKEN_PLAN_AMS_API_KEY"));
        assertConfig(new XiaomiTokenPlanSgpProvider(), "xiaomi-token-plan-sgp",
            "Xiaomi Token Plan SGP", "https://token-plan-sgp.xiaomimimo.com/v1",
            List.of("XIAOMI_TOKEN_PLAN_SGP_API_KEY"));
    }

    @Test
    void catalogsCarryTheFilteredChatCountsAndFirstEntries() {
        // 条数由 vendor 规则（skipDeprecated ＋ Individual 白名单）决定；
        // 首条钉住当前提交快照，防止 --fetch 重排后静默换序。
        assertShard("qwen-token-plan", 20, "deepseek-v4.1-flash-1017");
        assertShard("qwen-token-plan-individual", 13, "deepseek-v4.1-flash-1017");
        assertShard("qwen-token-plan-individual-cn", 13, "glm-5.2");
        assertShard("xiaomi-token-plan-ams", 2, "mimo-v2.6-flash");
        assertShard("xiaomi-token-plan-sgp", 2, "mimo-v2.6-flash");
    }

    private static void assertConfig(ConfigurableProvider provider, String name, String displayName,
                                     String baseUrl, List<String> chain) {
        var config = provider.providerConfig();
        assertThat(config.name()).isEqualTo(name);
        assertThat(config.displayName()).isEqualTo(displayName);
        assertThat(config.defaultBaseUrl()).isEqualTo(baseUrl);
        assertThat(config.apiKeyEnvVars()).containsExactlyElementsOf(chain);
        assertThat(config.apiKeyEnvVar()).isEqualTo(chain.getFirst());
    }

    private static void assertShard(String provider, int chatCount, String firstModel) {
        var models = BuiltinModelCatalogs.catalog(provider).listModels();
        assertThat(models).as("%s chat count", provider).hasSize(chatCount);
        assertThat(models.getFirst().id().modelName()).isEqualTo(firstModel);
    }
}
