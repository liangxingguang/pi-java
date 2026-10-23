package com.pijava.tui.support;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderFactory;
import com.pijava.ai.stream.StreamEvent;

/**
 * 剧本 provider（docs/18 树导航流夹具）：逐 call 消费回复队列（空 ⇒ "ok"）；
 * {@code <conversation>} 请求＝摘要类调用（分支摘要/压缩同形），可被闩住等测试放行；
 * {@link #delayNextCall} 给下一次调用加时延（在飞 run 窗口）。
 *
 * <p>tui 模块摸不到 coding-agent 的包级持久仓装配 ⇒ 公共 {@code AgentSession.create(args)}
 * 的 defaultProviders 经 ServiceLoader 发现 {@link Factory}（TCCL 作用域注入），
 * 实例由 {@link Factory#lastInstance()} 回取。</p>
 */
public final class ScriptedChatProvider implements Provider {

    /** provider SPI 工厂（ServiceLoader 发现）。 */
    public static final class Factory implements ProviderFactory {
        private static volatile ScriptedChatProvider lastInstance;

        /** ServiceLoader 无参构造。 */
        public Factory() {}

        /** 最近一次 {@link #create()} 的实例（测试回取口）。 */
        public static ScriptedChatProvider lastInstance() {
            return lastInstance;
        }

        @Override public Provider create() {
            var p = new ScriptedChatProvider("scripted");
            lastInstance = p;
            return p;
        }

        @Override public String providerName() { return "scripted"; }
    }

    private final String name;
    private final Queue<String> replies = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    private volatile CountDownLatch summaryStarted;
    private volatile CountDownLatch summaryGate;
    private volatile long nextDelayMs;

    /** 已发起的 provider 调用数（streamInternal 入口计数）——时序门用。 */
    public int callCount() {
        return calls.get();
    }

    private ScriptedChatProvider(String name) {
        this.name = name;
    }

    /** 追加一条剧本回复（按调用序消费）。 */
    public void enqueue(String text) {
        replies.add(text);
    }

    /** 下一次调用（含摘要）延迟 {@code ms} 毫秒——用于保持在飞窗口。 */
    public void delayNextCall(long ms) {
        nextDelayMs = ms;
    }

    /** 武装摘要闩：下一次摘要类调用先 {@link #awaitSummaryStarted} 再被闩住。 */
    public void armSummaryGate() {
        summaryStarted = new CountDownLatch(1);
        summaryGate = new CountDownLatch(1);
    }

    /** 等摘要类调用发起（15s 防挂死；超时由断言器报错）。 */
    public void awaitSummaryStarted() throws InterruptedException {
        if (!summaryStarted.await(15, TimeUnit.SECONDS)) {
            throw new AssertionError("摘要调用未在 15s 内发起");
        }
    }

    /** 放行被闩住的摘要调用。 */
    public void releaseSummary() {
        summaryGate.countDown();
    }

    @Override public String name() { return name; }
    @Override public String displayName() { return name; }
    @Override public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(com.pijava.ai.api.ChatApi.class);
    }

    @Override
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        return apiType.cast(new Api(this));
    }

    @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

    private static final class Api extends AbstractChatApi {
        private final ScriptedChatProvider provider;

        Api(ScriptedChatProvider provider) {
            this.provider = provider;
        }

        @Override public String apiName() { return "scripted"; }
        @Override protected boolean resolvesRequestOptions() { return false; }

        @Override
        protected void streamInternal(StreamRequest request,
                java.util.concurrent.SubmissionPublisher<StreamEvent> publisher) {
            provider.calls.incrementAndGet();
            boolean summarization = false;
            for (Message message : request.messages()) {
                if (message instanceof Message.UserMessage user) {
                    var parts = new java.util.ArrayList<String>();
                    for (ContentBlock block : user.content()) {
                        if (block instanceof ContentBlock.TextContent text) {
                            parts.add(text.text());
                        }
                    }
                    if (String.join("\n", parts).contains("<conversation>")) {
                        summarization = true;
                    }
                }
            }
            if (summarization) {
                var started = provider.summaryStarted;
                if (started != null) {
                    started.countDown();
                }
                var gate = provider.summaryGate;
                if (gate != null) {
                    try {
                        gate.await(15, TimeUnit.SECONDS); // 防挂死：超时放行
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            long delay = provider.nextDelayMs;
            provider.nextDelayMs = 0;
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String text = provider.replies.poll();
            if (text == null) {
                text = "ok";
            }
            var msg = AssistantMessage.empty().withContent(List.of(
                new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
            publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
            publisher.submit(new StreamEvent.TextDelta(0, text,
                msg.withStopReason((StopReason) null)));
            publisher.submit(StreamEvent.StreamDone.settle(StopReason.STOP, msg));
        }
    }
}
