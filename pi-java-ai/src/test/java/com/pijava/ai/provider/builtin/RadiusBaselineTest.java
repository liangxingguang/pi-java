package com.pijava.ai.provider.builtin;

import java.util.List;
import java.util.Optional;

import com.pijava.ai.provider.RadiusGatewayClient;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #21 阶段 A：默认网关随包携带 28 条公共基线；自定义网关没有基线，
 * 首次账号目录到达前保持空目录。
 */
class RadiusBaselineTest {

    @Test
    void defaultGatewayStartsWithThePublicBaseline() {
        var models = new RadiusProvider().getModels();

        assertThat(models).hasSize(28);
        assertThat(models.getFirst().id().modelName()).isEqualTo("kimi-k3");
        assertThat(models).extracting(m -> m.id().modelName()).contains("balanced");
    }

    @Test
    void aSchemeLessDefaultGatewayStillCountsAsDefault() {
        var provider = new RadiusProvider(
            new RadiusGatewayClient("radius.pi.dev", null), Optional::empty);

        assertThat(provider.getModels()).hasSize(28);
    }

    @Test
    void customGatewaysHaveNoBaseline() {
        var provider = new RadiusProvider(
            new RadiusGatewayClient("https://radius.example.test", null), Optional::empty);

        assertThat(provider.getModels()).isEmpty();
    }

    @Test
    void baselineModelsArePiMessagesModelsOfThisProvider() {
        List<String> apis = new RadiusProvider().getModels().stream()
            .map(m -> m.id().provider()).distinct().toList();

        assertThat(apis).containsExactly("radius");
    }
}
