package com.pijava.ai.provider.faux;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SubmissionPublisher;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * docs/20 §3：pi {@code createFauxCore}（{@code providers/faux.ts:459-697}）的 Java 落点。
 *
 * <p>队列、usage 估算、增量参数分块与 deferred 提交／取回／取消都在这里汇合：
 * 每次流取走队首响应步，解析为消息后走同一条「估算 → 合成事件」链。空队列产生
 * 一条 {@code No more faux responses queued} 的流内错误（pi 同形）。</p>
 */
final class FauxCore {

    private static final String DEFAULT_BASE_URL = "http://localhost:0";
    private static final int DEFAULT_CONTEXT_WINDOW = 128000;
    private static final int DEFAULT_MAX_TOKENS = 16384;
    private static final double ZERO_PRICE = 0;

    private final String api;
    private final String provider;
    private final List<ModelInfo> models;
    private final FauxDeferredOptions deferredOptions;
    private final FauxChunking chunking;
    private final FauxProviderState state = new FauxProviderState();
    private final FauxUsageEstimator estimator = new FauxUsageEstimator();
    private final Deque<FauxResponseStep> pending = new ArrayDeque<>();
    private final Map<String, DeferredEntry> deferredResponses = new ConcurrentHashMap<>();
    private final FauxStreamSynthesis synthesis = new FauxStreamSynthesis();

    FauxCore(FauxProviderOptions options) {
        this.api = options.api() == null ? FauxMessages.randomId(FauxMessages.DEFAULT_API) : options.api();
        this.provider = options.provider() == null
            ? FauxMessages.DEFAULT_PROVIDER : options.provider();
        this.deferredOptions = options.deferred() == null
            ? FauxDeferredOptions.defaults() : options.deferred();
        this.chunking = FauxChunking.of(options.tokenSize(), options.tokensPerSecond());
        this.models = buildModels(options.models());
    }

    String api() {
        return api;
    }

    String provider() {
        return provider;
    }

    List<ModelInfo> models() {
        return models;
    }

    ModelInfo getModel() {
        return models.get(0);
    }

    Optional<ModelInfo> getModel(String modelId) {
        return models.stream().filter(model -> model.id().modelName().equals(modelId)).findFirst();
    }

    FauxProviderState state() {
        return state;
    }

    void setResponses(List<FauxResponseStep> responses) {
        synchronized (pending) {
            pending.clear();
            pending.addAll(responses);
        }
    }

    void appendResponses(List<FauxResponseStep> responses) {
        synchronized (pending) {
            pending.addAll(responses);
        }
    }

    int pendingResponseCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** 普通（非 deferred）流请求的完整生产逻辑。 */
    void stream(StreamRequest request, ApiOptions options,
                SubmissionPublisher<StreamEvent> publisher) {
        var step = takeStep();
        state.recordCall();
        try {
            fireOnResponse(options, request.model());
            if (step == null) {
                var failure = new IllegalStateException("No more faux responses queued");
                var message = estimator.estimate(
                    errorMessage(failure, request.model()), request.transcript(), options);
                emitError(publisher, failure, message);
                return;
            }
            if (deferredEnabled(options)) {
                submitDeferred(step, request, options, publisher);
                return;
            }
            synthesis.emit(resolve(step, request.transcript(), options, request.model()),
                publisher, chunking);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitError(publisher, e, errorMessage(e, request.model()));
        } catch (RuntimeException e) {
            emitError(publisher, e, errorMessage(e, request.model()));
        }
    }

    /** deferred 取回：pending 次返回原 handle，之后解析并缓存最终消息。 */
    void fetchDeferred(ModelInfo model, DeferredHandle handle, ApiOptions options,
                       SubmissionPublisher<StreamEvent> publisher) {
        state.recordDeferredFetch();
        try {
            fireOnResponse(options, model);
            var entry = handle == null ? null : deferredResponses.get(handle.id());
            if (entry == null || !matches(entry.handle, handle)) {
                emitError(publisher, new IllegalStateException("Unknown faux deferred response: "
                    + (handle == null ? "null" : handle.id())),
                    errorMessage("Unknown faux deferred response: "
                        + (handle == null ? "null" : handle.id()), model));
                return;
            }
            if (entry.cancelled) {
                var text = "Faux deferred response was cancelled: " + handle.id();
                emitError(publisher, new IllegalStateException(text), errorMessage(text, model));
                return;
            }
            synchronized (entry) {
                if (entry.pendingFetches > 0) {
                    entry.pendingFetches--;
                    synthesis.emit(deferredMessage(model, entry.handle), publisher, chunking);
                    return;
                }
                if (entry.finalMessage == null) {
                    try {
                        entry.finalMessage = resolve(entry.step, entry.transcript,
                            stripDeferred(entry.options), entry.model);
                    } catch (RuntimeException e) {
                        // pi 的错误留在流内，不 reject 调用方。
                        entry.finalMessage = errorMessage(e, entry.model);
                    }
                }
                synthesis.emit(entry.finalMessage, publisher, chunking);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitError(publisher, e, errorMessage(e, model));
        } catch (RuntimeException e) {
            emitError(publisher, e, errorMessage(e, model));
        }
    }

    /** 取消：记录 handle、标记 entry，并触发 onResponse（即使 entry 不存在）。 */
    void cancelDeferred(ModelInfo model, DeferredHandle handle, ApiOptions options) {
        if (handle != null) {
            state.recordCancelled(handle);
            var entry = deferredResponses.get(handle.id());
            if (entry != null) {
                entry.cancelled = true;
            }
        }
        fireOnResponse(options, model);
    }

    private FauxResponseStep takeStep() {
        synchronized (pending) {
            return pending.pollFirst();
        }
    }

    private void submitDeferred(FauxResponseStep step, StreamRequest request, ApiOptions options,
                                SubmissionPublisher<StreamEvent> publisher)
            throws InterruptedException {
        var model = request.model();
        var handle = new DeferredHandle(
            model == null ? provider : model.id().provider(),
            model == null ? null : model.id().modelName(),
            model == null || model.api() == null ? api : model.api(),
            FauxMessages.randomId("deferred"),
            null,
            deferredOptions.pollAfterMs(),
            null);
        deferredResponses.put(handle.id(), new DeferredEntry(
            handle, step, request.transcript(), options, model,
            deferredOptions.normalizedPendingFetches()));
        synthesis.emit(deferredMessage(model, handle), publisher, chunking);
    }

    private AssistantMessage resolve(FauxResponseStep step, TranscriptContext transcript,
                                     ApiOptions options, ModelInfo model) {
        var resolved = switch (step) {
            case FauxResponseStep.Message message -> message.message();
            case FauxResponseStep.Factory factory ->
                factory.factory().create(transcript, options, state, model);
        };
        Objects.requireNonNull(resolved, "Faux response factory returned null");
        var cloned = new AssistantMessage(
            resolved.id(), resolved.content(), resolved.usage(), resolved.stopReason(),
            api, provider, model == null ? null : model.id().modelName(),
            resolved.responseModel(), resolved.providerThinkingLevel(), resolved.diagnostics(),
            resolved.timestamp() == null ? Instant.now() : resolved.timestamp(),
            resolved.errorMessage(), resolved.rawStopReason(), resolved.durationMs(),
            resolved.responseId(), resolved.deferred());
        return estimator.estimate(cloned, transcript, options);
    }

    private static boolean deferredEnabled(ApiOptions options) {
        if (options == null) {
            return false;
        }
        var raw = options.extra().get("deferred");
        return raw instanceof Boolean bool ? bool : raw instanceof Map;
    }

    private static ApiOptions stripDeferred(ApiOptions options) {
        var base = options == null ? ApiOptions.defaults() : options;
        var extra = new LinkedHashMap<>(base.extra());
        extra.remove("deferred");
        extra.remove(ProviderHooks.ON_RESPONSE);
        return ApiOptions.withExtra(base, extra);
    }

    private static void fireOnResponse(ApiOptions options, ModelInfo model) {
        var hook = ProviderHooks.responseHookOf(options);
        if (hook != null) {
            hook.onResponse(200, Map.of(), model);
        }
    }

    private AssistantMessage deferredMessage(ModelInfo model, DeferredHandle handle) {
        return new AssistantMessage(FauxMessages.randomId("msg"), List.of(),
            FauxMessages.zeroUsage(), StopReason.DEFERRED,
            model == null || model.api() == null ? api : model.api(),
            model == null ? provider : model.id().provider(),
            model == null ? null : model.id().modelName(),
            null, null, null, Instant.now(), null, null, null, null, handle);
    }

    private AssistantMessage errorMessage(Throwable failure, ModelInfo model) {
        return errorMessage(errorText(failure), model);
    }

    private AssistantMessage errorMessage(String text, ModelInfo model) {
        return new AssistantMessage(FauxMessages.randomId("msg"), List.of(),
            FauxMessages.zeroUsage(), StopReason.ERROR,
            model == null || model.api() == null ? api : model.api(),
            model == null ? provider : model.id().provider(),
            model == null ? null : model.id().modelName(),
            null, null, null, Instant.now(), text, null, null, null, null);
    }

    private static String errorText(Throwable failure) {
        var message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.toString() : message;
    }

    private static void emitError(SubmissionPublisher<StreamEvent> publisher, Throwable failure,
                                  AssistantMessage message) {
        publisher.submit(StreamEvent.StreamError.settle(StopReason.ERROR, failure, message));
    }

    private static boolean matches(DeferredHandle stored, DeferredHandle requested) {
        return Objects.equals(stored.provider(), requested.provider())
            && Objects.equals(stored.modelId(), requested.modelId())
            && Objects.equals(stored.api(), requested.api());
    }

    private List<ModelInfo> buildModels(List<FauxModelDefinition> definitions) {
        var effective = definitions.isEmpty()
            ? List.of(new FauxModelDefinition(FauxMessages.DEFAULT_MODEL_ID,
                FauxMessages.DEFAULT_MODEL_NAME, false, List.of("text", "image"),
                null, null, null, null))
            : definitions;
        var built = new ArrayList<ModelInfo>(effective.size());
        for (var definition : effective) {
            built.add(buildModel(definition));
        }
        return List.copyOf(built);
    }

    private ModelInfo buildModel(FauxModelDefinition definition) {
        var capabilities = new java.util.HashSet<ModelCapability>();
        capabilities.add(ModelCapability.TEXT);
        var modalities = definition.input() == null
            ? List.of("text", "image") : definition.input();
        if (modalities.contains("image")) {
            capabilities.add(ModelCapability.IMAGE_INPUT);
        }
        if (Boolean.TRUE.equals(definition.reasoning())) {
            capabilities.add(ModelCapability.THINKING);
        }
        var pricing = definition.pricing() == null
            ? new PricingInfo(ZERO_PRICE, ZERO_PRICE, ZERO_PRICE, ZERO_PRICE, List.of())
            : definition.pricing();
        return new ModelInfo(
            ModelId.of(provider, definition.id()),
            definition.name() == null ? definition.id() : definition.name(),
            Set.copyOf(capabilities),
            definition.contextWindow() == null ? DEFAULT_CONTEXT_WINDOW : definition.contextWindow(),
            definition.maxTokens() == null ? DEFAULT_MAX_TOKENS : definition.maxTokens(),
            false,
            pricing,
            ThinkingLevelMap.empty(),
            Map.of(),
            Map.of(),
            ModelCompat.NONE,
            api,
            DEFAULT_BASE_URL,
            null,
            definition.inputLimits(),
            Map.of());
    }

    /** deferred map 的一项（pi 的闭包内对象）。 */
    private static final class DeferredEntry {

        private final DeferredHandle handle;
        private final FauxResponseStep step;
        private final TranscriptContext transcript;
        private final ApiOptions options;
        private final ModelInfo model;
        private int pendingFetches;
        private boolean cancelled;
        private AssistantMessage finalMessage;

        DeferredEntry(DeferredHandle handle, FauxResponseStep step,
                      TranscriptContext transcript, ApiOptions options, ModelInfo model,
                      int pendingFetches) {
            this.handle = handle;
            this.step = step;
            this.transcript = transcript;
            this.options = options;
            this.model = model;
            this.pendingFetches = pendingFetches;
        }
    }
}
