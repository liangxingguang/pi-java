package com.pijava.ai.provider.faux;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.stream.StreamEvent;

/**
 * docs/20 §3：可编程核心的 {@link AbstractChatApi} 适配器。
 *
 * <p>身份挂载与 {@code send()} 仍由基类提供；这里只把流式生产与 deferred 三入口
 * 委托给 {@link FauxCore}。deferred 取回流先在生产者启动前挂 {@code pi.ai.poll}
 * 观察者（D203 的生产入口）。</p>
 */
final class FauxChatApi extends AbstractChatApi {

    private final FauxCore core;

    FauxChatApi(FauxProgrammable owner) {
        this.core = owner.core();
    }

    @Override
    public String apiName() {
        return core.api();
    }

    /** 测试替身不参与请求侧选项解析（与旧重放模式同判据）。 */
    @Override
    protected boolean resolvesRequestOptions() {
        return false;
    }

    @Override
    protected void streamInternal(StreamRequest request,
                                  SubmissionPublisher<StreamEvent> publisher) {
        core.stream(request, ApiOptions.defaults(), publisher);
    }

    @Override
    protected void streamInternal(StreamRequest request, ApiOptions options,
                                  SubmissionPublisher<StreamEvent> publisher) {
        core.stream(request, options, publisher);
    }

    @Override
    public Flow.Publisher<StreamEvent> fetchDeferred(
            ModelInfo model, DeferredHandle handle, ApiOptions options) {
        var publisher = new SubmissionPublisher<StreamEvent>();
        var started = new AtomicBoolean();
        return subscriber -> {
            boolean first = started.compareAndSet(false, true);
            if (first) {
                attachPollTelemetry(publisher, model, options, apiName());
            }
            publisher.subscribe(subscriber);
            if (first) {
                Thread.startVirtualThread(() -> {
                    try {
                        core.fetchDeferred(model, handle, options, publisher);
                        publisher.close();
                    } catch (RuntimeException e) {
                        publisher.closeExceptionally(e);
                    }
                });
            }
        };
    }

    @Override
    public void cancelDeferred(ModelInfo model, DeferredHandle handle, ApiOptions options) {
        core.cancelDeferred(model, handle, options);
    }
}
