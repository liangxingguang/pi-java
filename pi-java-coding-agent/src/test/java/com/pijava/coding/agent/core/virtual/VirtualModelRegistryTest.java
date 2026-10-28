package com.pijava.coding.agent.core.virtual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.Message;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;

/**
 * 批 27（B162/T9）：{@link VirtualModelRegistry} 单测——pi model-runtime.ts
 * :952-1033 的注册/注销/解析语义与五条错误文案逐字钉住。
 */
class VirtualModelRegistryTest {

    /** 物理目录夹具：faux/phys-1、faux/phys-2 在册。 */
    private static VirtualModelRegistry registry() {
        return new VirtualModelRegistry((provider, id) ->
            "faux".equals(provider) && id.startsWith("phys-"));
    }

    private static VirtualModelDefinition auto(BiFunctionCapture capture) {
        return VirtualModelDefinition.of("jev", "auto", "Auto", request -> {
            capture.requests.add(request);
            return capture.next;
        });
    }

    /** 路由器夹具：记录请求，返回编程好的路由。 */
    private static final class BiFunctionCapture {
        final List<ModelRouteRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        ModelRoute next = ModelRoute.of(ModelId.of("faux", "phys-1"), ModelThinkingLevel.off());
    }

    // ── 注册/注销（pi :952-985） ──────────────────────────────────────

    @Test
    void rejectsEmptyProviderOrIdVerbatim() {
        var registry = registry();
        assertThatThrownBy(() -> registry.register(
                VirtualModelDefinition.of(" ", "x", "X", r -> null)))
            .hasMessage("Virtual model provider and id must not be empty.");
        assertThatThrownBy(() -> registry.register(
                VirtualModelDefinition.of("jev", "", "X", r -> null)))
            .hasMessage("Virtual model provider and id must not be empty.");
    }

    @Test
    void rejectsConflictsWithPhysicalModelsVerbatim() {
        var registry = registry();
        assertThatThrownBy(() -> registry.register(
                VirtualModelDefinition.of("faux", "phys-1", "X", r -> null)))
            .hasMessage("Virtual model faux/phys-1 conflicts with a physical model.");
    }

    @Test
    void duplicateRegistrationReplacesAndUnregisterCleansUp() {
        var registry = registry();
        var first = new BiFunctionCapture();
        registry.register(auto(first));
        registry.register(auto(new BiFunctionCapture())); // 同 provider+id ⇒ 替换
        assertThat(registry.list()).hasSize(1);
        assertThat(registry.isVirtual(ModelId.of("jev", "auto"))).isTrue();
        // 注册即在 auth 豁免集（pi :967-973）
        assertThat(registry.isAuthExempt("jev")).isTrue();

        registry.unregister("jev", "auto");
        assertThat(registry.isVirtual(ModelId.of("jev", "auto"))).isFalse();
        assertThat(registry.isAuthExempt("jev")).isFalse();
        // 幂等：再删直接返回（pi :979-981）
        registry.unregister("jev", "auto");
        registry.unregister("jev", "never-registered");
    }

    // ── resolveModel（pi :996-1027） ──────────────────────────────────

    @Test
    void resolvesPreviousFromLatestSuccessfulResponse() {
        var registry = registry();
        registry.authGate(p -> true);
        var capture = new BiFunctionCapture();
        registry.register(auto(capture));
        var ok = new Message.AssistantMessage(
            List.<ContentBlock>of(new ContentBlock.TextContent("hi")), StopReason.STOP, null,
            "faux-api", "faux", "phys-2", null, null, null, null);
        var failed = new Message.AssistantMessage(
            List.<ContentBlock>of(new ContentBlock.TextContent("boom")), StopReason.ERROR, null,
            "faux-api", "faux", "phys-1", null, null, "boom", null);

        registry.resolve(ModelId.of("jev", "auto"),
            List.of(new com.pijava.ai.message.Message.UserMessage(
                    List.<ContentBlock>of(new ContentBlock.TextContent("u1")), null),
                ok, failed),
            ModelThinkingLevel.off(), ModelRouteReason.CONTINUATION, null, null, null);

        // pi :1002-1006：findLatestResponse 跳过 error ⇒ previous 是 phys-2
        assertThat(capture.requests.get(0).previous().model())
            .isEqualTo(ModelId.of("faux", "phys-2"));
    }

    @Test
    void resolveErrorsAreVerbatim() {
        var registry = registry();
        // 未注册
        assertThatThrownBy(() -> registry.resolve(ModelId.of("jev", "ghost"),
                List.of(), ModelThinkingLevel.off(), ModelRouteReason.USER,
                null, null, null))
            .hasMessage("Virtual model jev/ghost is not registered.");

        // 路由到非物理模型
        var capture = new BiFunctionCapture();
        capture.next = ModelRoute.of(ModelId.of("faux", "ghost"), ModelThinkingLevel.off());
        registry.register(auto(capture));
        assertThatThrownBy(() -> registry.resolve(ModelId.of("jev", "auto"),
                List.of(), ModelThinkingLevel.off(), ModelRouteReason.USER,
                null, null, null))
            .hasMessage("Virtual model jev/auto routed to faux/ghost,"
                + " which is not a physical model.");

        // 物理但无凭据（默认闸走 Credentials——faux 无配置）
        capture.next = ModelRoute.of(ModelId.of("faux", "phys-1"), ModelThinkingLevel.off());
        assertThatThrownBy(() -> registry.resolve(ModelId.of("jev", "auto"),
                List.of(), ModelThinkingLevel.off(), ModelRouteReason.USER,
                null, null, null))
            .hasMessage("Virtual model jev/auto routed to faux/phys-1,"
                + " which has no credentials.");

        // 闸开后通
        registry.authGate(provider -> true);
        var route = registry.resolve(ModelId.of("jev", "auto"),
            List.of(), ModelThinkingLevel.off(), ModelRouteReason.USER, null, null, null);
        assertThat(route.model()).isEqualTo(ModelId.of("faux", "phys-1"));
    }

    @Test
    void failedNamingVirtualModelIsAbsentFromTheRequest() {
        // pi :1009-1010：失败路由指虚拟名 ⇒ 无物理请求可报（failed 缺席化）
        var registry = registry();
        registry.authGate(p -> true);
        var capture = new BiFunctionCapture();
        registry.register(auto(capture));
        var failedMessage = new Message.AssistantMessage(
            List.<ContentBlock>of(new ContentBlock.TextContent("err")), StopReason.ERROR, null,
            "pi-virtual", "jev", "auto", null, null, "err", null);

        registry.resolve(ModelId.of("jev", "auto"), List.of(),
            ModelThinkingLevel.off(), ModelRouteReason.RETRY, null,
            new ModelRouteRequest.RouteFailure(
                ModelId.of("jev", "auto"), null, failedMessage), null);

        assertThat(capture.requests.get(0).failed()).isNull();
        assertThat(capture.requests.get(0).reason()).isEqualTo(ModelRouteReason.RETRY);
    }

    @Test
    void stateAndReasonFlowThrough() {
        var registry = registry();
        registry.authGate(provider -> true);
        var capture = new BiFunctionCapture();
        registry.register(auto(capture));
        var state = com.pijava.coding.agent.core.Json.mapper()
            .valueToTree(Map.of("phase", "planning"));

        registry.resolve(ModelId.of("jev", "auto"), List.of(),
            ModelThinkingLevel.off(), ModelRouteReason.USER, state, null, null);

        assertThat(capture.requests.get(0).state()).isEqualTo(state);
        assertThat(capture.requests.get(0).reason()).isEqualTo(ModelRouteReason.USER);
    }
}
