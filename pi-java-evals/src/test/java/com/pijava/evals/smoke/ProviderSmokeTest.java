package com.pijava.evals.smoke;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live ping of each built-in provider. Skipped unless {@code -Dpi.eval.smoke=true}
 * and the provider credential (or local Ollama) is available.
 */
@Tag(SmokeTestTags.SMOKE)
class ProviderSmokeTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void pingReceivesStreamDone(String name, Provider provider) {
        Assumptions.assumeTrue(
            Boolean.parseBoolean(System.getProperty("pi.eval.smoke", "false")),
            "set -Dpi.eval.smoke=true to run live smoke tests");
        assumeReachable(provider);

        var api = provider.createApi(ChatApi.class, ApiOptions.defaults());
        var modelName = provider.builtinModels().listModels().isEmpty()
            ? "default"
            : provider.builtinModels().listModels().get(0).id().modelName();
        var request = StreamRequest.of(
            ModelId.of(provider.name(), modelName),
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("ping")))));
        var events = new ArrayList<StreamEvent>();
        try (var iterator = api.streamBlocking(request, ApiOptions.defaults())) {
            while (iterator.hasNext()) {
                events.add(iterator.next());
            }
        }
        assertThat(events).anyMatch(e -> e instanceof StreamEvent.StreamDone);
    }

    static Stream<Arguments> providers() {
        return ProviderCatalog.all().stream()
            .map(provider -> Arguments.of(provider.name(), provider));
    }

    private static void assumeReachable(Provider provider) {
        if ("ollama".equals(provider.name())) {
            Assumptions.assumeTrue(portOpen("127.0.0.1", 11434), "Ollama is not running");
            return;
        }
        if (provider instanceof ConfigurableProvider configurable) {
            var envVar = configurable.providerConfig().apiKeyEnvVar();
            Assumptions.assumeTrue(
                envVar != null && System.getenv(envVar) != null && !System.getenv(envVar).isBlank(),
                "missing " + envVar);
        }
    }

    private static boolean portOpen(String host, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 200);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
