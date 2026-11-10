package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.skill.SkillManager;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionContext;

import org.junit.jupiter.api.Test;

class CustomAnthropicProviderExtensionTest {

    private static ExtensionContext context(ProviderRegistry registry) {
        var services = new SessionServices(null, null, registry, null, null, null, null, null);
        return new DefaultExtensionContext(services, new SkillManager());
    }

    @Test
    void registersCustomAnthropicProviderShape() {
        var extension = new CustomAnthropicProviderExtension();
        var registry = ProviderRegistry.create();
        extension.register(context(registry).forExtension(extension.name()));

        assertThat(extension.name()).isEqualTo("custom-provider-anthropic");
        Provider provider = registry.get("custom-anthropic").orElseThrow();
        assertThat(provider.name()).isEqualTo("custom-anthropic");
        assertThat(provider.displayName()).isEqualTo("Custom Anthropic");
        assertThat(provider.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(provider.supportedProtocols()).containsExactly(Protocol.ANTHROPIC_MESSAGES);
        assertThat(((ConfigurableProvider) provider).providerConfig().apiKeyEnvVar())
                .isEqualTo("CUSTOM_ANTHROPIC_API_KEY");

        List<ModelInfo> models = provider.getModels();
        assertThat(models).hasSize(2);
        assertThat(models).extracting(info -> info.id().modelName())
                .containsExactly("claude-opus-4-5", "claude-sonnet-4-5");
        assertThat(models).allSatisfy(info -> {
            assertThat(info.id().provider()).isEqualTo("custom-anthropic");
            assertThat(info.api()).isNull();
            assertThat(info.maxInputTokens()).isEqualTo(200000);
        });
    }

    @Test
    void oauthContractAnnouncesUrlThenPromptsAndShapesCredentials() {
        List<String> calls = new ArrayList<>();
        CustomAnthropicProviderExtension.OAuthCallbacks callbacks =
                new CustomAnthropicProviderExtension.OAuthCallbacks() {
                    @Override
                    public void onAuth(String url) {
                        calls.add("auth:" + url);
                    }

                    @Override
                    public String onPrompt(String message) {
                        calls.add("prompt:" + message);
                        return "code#state";
                    }
                };

        String code = CustomAnthropicProviderExtension.OAuth
                .requestAuthorizationCode(callbacks, "challenge-1", "state-1");

        assertThat(code).isEqualTo("code#state");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).startsWith("auth:https://claude.ai/oauth/authorize?")
                .contains("client_id=")
                .contains("code_challenge=challenge-1")
                .contains("state=state-1");
        assertThat(calls.get(1)).startsWith("prompt:");

        var credential = CustomAnthropicProviderExtension.OAuth
                .credentials("access", "refresh", 3600, 1_000_000L);
        assertThat(credential.accessToken()).isEqualTo("access");
        assertThat(credential.refreshToken()).isEqualTo("refresh");
        assertThat(credential.expiresAtEpochSec())
                .isEqualTo((1_000_000L + 3_600_000L - 300_000L) / 1000L);
        assertThat(CustomAnthropicProviderExtension.OAuth.apiKey(credential)).isEqualTo("access");
    }

    @Test
    void assemblesTextThinkingAndToolEventsInOrder() {
        AssistantMessage template = AssistantMessage.empty();
        List<CustomAnthropicStream.Input> inputs = List.of(
                new CustomAnthropicStream.Input.BlockStart(
                        0, CustomAnthropicStream.BlockKind.TEXT, "", "", false),
                new CustomAnthropicStream.Input.TextDelta(0, "Hel"),
                new CustomAnthropicStream.Input.TextDelta(0, "lo"),
                new CustomAnthropicStream.Input.BlockStop(0),
                new CustomAnthropicStream.Input.BlockStart(
                        1, CustomAnthropicStream.BlockKind.THINKING, "", "", false),
                new CustomAnthropicStream.Input.ThinkingDelta(1, "ponder"),
                new CustomAnthropicStream.Input.SignatureDelta(1, "sig"),
                new CustomAnthropicStream.Input.BlockStop(1),
                new CustomAnthropicStream.Input.BlockStart(
                        2, CustomAnthropicStream.BlockKind.TOOL_USE, "tool-1", "edit", false),
                new CustomAnthropicStream.Input.InputJsonDelta(2, "{\"path\":"),
                new CustomAnthropicStream.Input.InputJsonDelta(2, "\"/tmp/a\"}"),
                new CustomAnthropicStream.Input.BlockStop(2),
                new CustomAnthropicStream.Input.MessageDelta("tool_use"),
                new CustomAnthropicStream.Input.MessageStop());

        List<StreamEvent> events = CustomAnthropicStream.assemble(template, inputs);

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("Start",
                        "TextStart", "TextDelta", "TextDelta", "TextEnd",
                        "ThinkingStart", "ThinkingDelta", "ThinkingEnd",
                        "ToolCallStart", "ToolCallDelta", "ToolCallDelta", "ToolCallEnd",
                        "StreamDone");

        StreamEvent.TextEnd textEnd = (StreamEvent.TextEnd) events.get(4);
        assertThat(textEnd.text()).isEqualTo("Hello");

        StreamEvent.ThinkingEnd thinkingEnd = (StreamEvent.ThinkingEnd) events.get(7);
        assertThat(thinkingEnd.thinking()).isEqualTo("ponder");

        StreamEvent.ToolCallEnd toolEnd = (StreamEvent.ToolCallEnd) events.get(11);
        assertThat(toolEnd.arguments()).containsEntry("path", "/tmp/a").hasSize(1);
        assertThat(toolEnd.argumentsError()).isNull();

        AssistantMessage done = ((StreamEvent.StreamDone) events.get(12)).partial();
        assertThat(done.stopReason()).isEqualTo(com.pijava.ai.message.StopReason.TOOL_USE);
        assertThat(done.content()).hasSize(3);
        ContentBlock.ToolUseContent toolBlock =
                (ContentBlock.ToolUseContent) done.content().get(2);
        assertThat(toolBlock.arguments()).containsEntry("path", "/tmp/a");
        assertThat(toolBlock.argumentsError()).isNull();
    }

    @Test
    void malformedToolArgumentsSurfaceErrorAndEmptyArguments() {
        AssistantMessage template = AssistantMessage.empty();
        List<CustomAnthropicStream.Input> inputs = List.of(
                new CustomAnthropicStream.Input.BlockStart(
                        0, CustomAnthropicStream.BlockKind.TOOL_USE, "tool-2", "write", false),
                new CustomAnthropicStream.Input.InputJsonDelta(0, "{\"path\":"),
                new CustomAnthropicStream.Input.InputJsonDelta(0, "\"/tmp/b\""),
                new CustomAnthropicStream.Input.BlockStop(0),
                new CustomAnthropicStream.Input.MessageDelta("tool_use"),
                new CustomAnthropicStream.Input.MessageStop());

        List<StreamEvent> events = CustomAnthropicStream.assemble(template, inputs);
        StreamEvent.ToolCallEnd toolEnd = (StreamEvent.ToolCallEnd) events.get(4);

        assertThat(toolEnd.argumentsError()).isNotNull();
        assertThat(toolEnd.arguments()).isEmpty();
        assertThat(toolEnd.partial().content().get(0)).isInstanceOf(ContentBlock.ToolUseContent.class);
        ContentBlock.ToolUseContent block =
                (ContentBlock.ToolUseContent) toolEnd.partial().content().get(0);
        assertThat(block.arguments()).isEmpty();
        assertThat(block.argumentsError()).isNotNull();
    }
}
