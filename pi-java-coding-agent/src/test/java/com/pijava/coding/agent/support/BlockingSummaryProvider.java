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
 * Test provider whose <b>compaction-summary requests block</b> until
 * {@link #release()} (B176, docs/27): keeps the compaction window open while
 * the command thread must stay responsive. Normal turn requests stream an
 * {@code "ok"} reply at once.
 *
 * <p>Summary detection: the summary user-text contains "Conversation"
 * ({@code <conversation>} history prompt / {@code # Conversation} turn-prefix
 * prompt); ordinary prompts in the fixtures never do.</p>
 */
public final class BlockingSummaryProvider implements Provider {

    private final String name;
    private final List<String> userTexts = new CopyOnWriteArrayList<>();
    private final boolean failSummary;
    private volatile boolean released;

    public BlockingSummaryProvider(String name) {
        this(name, false);
    }

    /** @param failSummary summary requests emit StreamError instead of blocking */
    public BlockingSummaryProvider(String name, boolean failSummary) {
        this.name = name;
        this.failSummary = failSummary;
    }

    /** Every user-message text observed, in request order. */
    public List<String> userTexts() {
        return List.copyOf(userTexts);
    }

    /** Unblock every waiting summary request. */
    public synchronized void release() {
        released = true;
        notifyAll();
    }

    /** Register this provider on a fresh registry under {@link #name}. */
    public ProviderRegistry register() {
        var registry = ProviderRegistry.create();
        registry.register(this);
        return registry;
    }

    @Override public String name() { return name; }
    @Override public String displayName() { return "BlockingSummary (" + name + ")"; }
    @Override public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(com.pijava.ai.api.ChatApi.class);
    }

    @Override
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        return apiType.cast(new BlockingApi(this));
    }

    @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

    private static final class BlockingApi extends AbstractChatApi {

        private final BlockingSummaryProvider provider;

        BlockingApi(BlockingSummaryProvider provider) {
            this.provider = provider;
        }

        @Override public String apiName() { return "blocking"; }
        @Override protected boolean resolvesRequestOptions() { return false; }

        @Override
        protected void streamInternal(StreamRequest request,
                                      java.util.concurrent.SubmissionPublisher<StreamEvent> publisher) {
            String text = userText(request);
            provider.userTexts.add(text);
            // 摘要请求的精确标记：历史路小写 <conversation>（buildPrompt），
            // turn-prefix 路 "# Conversation"（summarizeTurnPrefix）。
            if (text.contains("<conversation>") || text.contains("# Conversation")) {
                if (provider.failSummary) {
                    publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                    publisher.submit(StreamEvent.StreamError.settle("error",
                        new RuntimeException("summary boom"), AssistantMessage.empty()));
                    return;
                }
                provider.awaitRelease();
            }
            var msg = AssistantMessage.empty().withContent(List.of(
                new ContentBlock.TextContent("ok"))).withStopReason("stop");
            publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
            publisher.submit(new StreamEvent.TextDelta(0, "ok", msg.withStopReason(null)));
            publisher.submit(StreamEvent.StreamDone.settle("stop", msg));
        }

        private static String userText(StreamRequest request) {
            var parts = new java.util.ArrayList<String>();
            for (Message message : request.messages()) {
                if (message instanceof Message.UserMessage user) {
                    for (ContentBlock block : user.content()) {
                        if (block instanceof ContentBlock.TextContent text) {
                            parts.add(text.text());
                        }
                    }
                }
            }
            return String.join("\n", parts);
        }
    }

    private synchronized void awaitRelease() {
        //  guarded wait; interrupted while the test releases ⇒ fall through.
        boolean interrupted = false;
        while (!released) {
            try {
                wait(1000);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
