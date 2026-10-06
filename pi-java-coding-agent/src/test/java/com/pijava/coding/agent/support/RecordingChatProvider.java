package com.pijava.coding.agent.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;

/**
 * Test provider that records the user-message text of every request (main turns
 * and compaction summary calls alike) and streams a fixed {@code "ok"} reply.
 * Shared by the slash and RPC compaction fixtures (B174, {@code docs/23}).
 */
public final class RecordingChatProvider implements Provider {

    private final String name;
    private final List<String> userTexts = new CopyOnWriteArrayList<>();

    public RecordingChatProvider(String name) {
        this.name = name;
    }

    /** Every user-message text observed, in request order. */
    public List<String> userTexts() {
        return List.copyOf(userTexts);
    }

    /** Register this provider on a fresh registry under {@link #name()}. */
    public ProviderRegistry register() {
        var registry = ProviderRegistry.create();
        registry.register(this);
        return registry;
    }

    @Override public String name() { return name; }
    @Override public String displayName() { return "Recording (" + name + ")"; }
    @Override public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(com.pijava.ai.api.ChatApi.class);
    }

    @Override
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        return apiType.cast(new RecordingApi(this));
    }

    @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

    private static final class RecordingApi extends AbstractChatApi {

        private final RecordingChatProvider provider;

        RecordingApi(RecordingChatProvider provider) {
            this.provider = provider;
        }

        @Override public String apiName() { return "recording"; }
        @Override protected boolean resolvesRequestOptions() { return false; }

        @Override
        protected void streamInternal(StreamRequest request,
                                      java.util.concurrent.SubmissionPublisher<StreamEvent> publisher) {
            for (Message message : request.messages()) {
                if (message instanceof Message.UserMessage user) {
                    var parts = new java.util.ArrayList<String>();
                    for (ContentBlock block : user.content()) {
                        if (block instanceof ContentBlock.TextContent text) {
                            parts.add(text.text());
                        }
                    }
                    provider.userTexts.add(String.join("\n", parts));
                }
            }
            var msg = AssistantMessage.empty().withContent(List.of(
                new ContentBlock.TextContent("ok"))).withStopReason("stop");
            publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
            publisher.submit(new StreamEvent.TextDelta(0, "ok", msg.withStopReason(null)));
            publisher.submit(StreamEvent.StreamDone.settle("stop", msg));
        }
    }
}
