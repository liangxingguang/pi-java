package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.slash.SlashContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * debug-provider：启用后每轮收集 provider 流事件并以 {@code provider-debug}
 * custom 条目追加；渲染器给出摘要行，expanded 追加原始 JSON。禁用时零追加。
 */
class DebugProviderExtensionTest {

    private static final String PROVIDER = "debug-lane";

    private static final String MODEL = "debug-model";

    @TempDir Path tmp;

    @Test
    void appendsProviderDebugEntryAndRendersSummaryAndExpandedJson() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, DebugProviderExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp, new HookProvider(), MODEL)) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");

                var command = session.services().slashCommands().get("debug-provider");
                assertThat(command).isNotNull();
                command.execute("on", SlashContext.of(session)).toCompletableFuture().join();
                assertThat(ui.notices()).anySatisfy(notice ->
                        assertThat(notice.message()).contains("enabled"));

                var result = session.processPrompt("hello", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var entries = session.accumulatedEntries().stream()
                        .filter(e -> e instanceof Entry.Custom custom
                                && DebugProviderExtension.ENTRY_TYPE.equals(custom.customType()))
                        .map(Entry.Custom.class::cast)
                        .toList();
                assertThat(entries).hasSize(1);
                Map<String, Object> data = entries.get(0).data();
                assertThat(data.get("provider")).isEqualTo(PROVIDER);
                assertThat(data.get("model")).isEqualTo(MODEL);
                assertThat((List<?>) data.get("events")).hasSize(2);
                assertThat(((Map<?, ?>) data.get("message")).get("text"))
                        .isEqualTo("debug reply");

                var renderer = session.entryRendererFor(DebugProviderExtension.ENTRY_TYPE);
                assertThat(renderer).isNotNull();
                var compact = renderer.render(DebugProviderExtension.ENTRY_TYPE, data, false);
                assertThat(compact).hasSize(1);
                assertThat(compact.get(0))
                        .startsWith("[provider-debug] " + PROVIDER + "/" + MODEL)
                        .contains("2 event(s)");

                var expanded = renderer.render(DebugProviderExtension.ENTRY_TYPE, data, true);
                assertThat(expanded).hasSize(2);
                assertThat(expanded.get(1)).contains("raw-chunk").contains("two");
            }
        }
    }

    @Test
    void disabledCaptureAppendsNothing() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, DebugProviderExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp, new HookProvider(), MODEL)) {
                var result = session.processPrompt("hello", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(session.accumulatedEntries()).noneMatch(
                        e -> e instanceof Entry.Custom custom
                                && DebugProviderExtension.ENTRY_TYPE.equals(custom.customType()));
            }
        }
    }

    private static List<StreamEvent> textEvents(String text) {
        var done = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(text)))
                .withStopReason(StopReason.STOP);
        return List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, text, done),
                new StreamEvent.TextEnd(0, text, done),
                new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    /** 走 provider_stream_event 钩的桩车道：发两条原始事件再回文本。 */
    private static final class HookProvider implements Provider {

        @Override
        public String name() {
            return PROVIDER;
        }

        @Override
        public String displayName() {
            return "Debug Lane";
        }

        @Override
        public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }

        @Override
        public ModelCatalog builtinModels() {
            return BuiltinCatalog.of(List.of(
                    new ModelInfo(ModelId.of(PROVIDER, MODEL), MODEL,
                            ModelCapability.frontierDefaults(), 200000, 8192, false,
                            PricingInfo.UNKNOWN, Map.of(), Map.of())));
        }

        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            var hook = ProviderHooks.streamEventHookOf(options);
            return apiType.cast(new ChatApi() {
                @Override
                public Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions opts) {
                    throw new UnsupportedOperationException("test uses streamBlocking");
                }

                @Override
                public StreamIterator streamBlocking(StreamRequest request, ApiOptions opts) {
                    if (hook != null) {
                        hook.onEvent(Map.of("raw-chunk", "one"), request.model());
                        hook.onEvent(Map.of("raw-chunk", "two"), request.model());
                    }
                    return StreamIterator.from(textEvents("debug reply"));
                }

                @Override
                public Message send(StreamRequest request, ApiOptions opts) {
                    throw new UnsupportedOperationException("test uses streamBlocking");
                }
            });
        }
    }
}
