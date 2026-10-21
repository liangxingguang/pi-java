package com.pijava.coding.agent.extension.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AfterProviderResponseEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.BeforeProviderHeadersEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.BeforeProviderRequestEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ProviderStreamEvent;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.AFTER_PROVIDER_RESPONSE;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.BEFORE_PROVIDER_HEADERS;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.BEFORE_PROVIDER_REQUEST;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.PROVIDER_STREAM_EVENT;

/**
 * provider 请求面桥（docs/17 §3.10，B248）—— pi 的 {@code extensionRunnerRef}
 * （sdk.ts:333）＋四闭包（sdk.ts:340-410）对位：把 {@link ExtensionEventBus} 上的
 * 四个事件线接到 ai 车道的 {@link ProviderHooks} 三钩＋宿主侧头变换缝上。
 *
 * <p><b>晚绑定</b>（裁决③）：StreamFn 在 {@code wireSession} 创建、bus 在
 * {@code loadExtensions} 才建（fresh per 会话/fork）⇒ 桥先建后装
 * （{@link #install}）；未安装（含 {@code --no-extensions}）⇒ 全钩空跳
 * （pi 空 runner/空表同效）。{@code hasHandlers} 门<b>调用时现查</b>（pi 逐字），
 * 装配后的运行期订阅同生效。</p>
 *
 * <p><b>层位</b>（裁决②）：pi 这四件走 Agent 选项→streamFn→车道，不经
 * agent-loop 钩子——桥放 StreamFn 层（{@code DefaultProviders}），不动引擎
 * {@code HookSystem}（旧 {@code before_payload}/{@code after_response} 钩是
 * legacy 遗物，形状不符，维持不 firing）。</p>
 *
 * <p>异常纪律同 {@code EngineHookBridge} 的非工具族：逐处理器
 * {@code catch (Throwable)} ⇒ {@code emitError} 后继续（pi runner.ts:1380-1387／
 * 通用 emit :1103-1113 逐字）。</p>
 */
public final class ProviderEventBridge {

    private final AtomicReference<ExtensionEventBus> busRef = new AtomicReference<>();
    private volatile ExtensionContext ctx;

    /** pi {@code extensionRunnerRef.current = runner}（装配回填点）。 */
    public void install(ExtensionEventBus bus, ExtensionContext ctx) {
        this.ctx = ctx;
        this.busRef.set(bus);
    }

    // ── ai 车道三钩（ProviderHooks 接口语义）─────────────────────────────

    /**
     * pi {@code emitBeforeProviderRequest}（runner.ts:1360-1392 逐字）：
     * {@code hasHandlers} 门 ⇒ 无订阅原样跳过；链式替换——后处理器见到前者的
     * 替换值；任一处理器抛错 ⇒ {@code emitError} 后继续、当前值不丢。
     *
     * @return 链终替换载荷；{@code null} 表示无替换（调用方保留原载荷并跳过同型守卫）
     */
    public Object onPayload(Object payload, ModelInfo model) {
        var bus = busRef.get();
        if (bus == null || !bus.hasHandlers(BEFORE_PROVIDER_REQUEST)) {
            return null;
        }
        Object current = payload;
        boolean modified = false;
        for (var h : bus.snapshot(BEFORE_PROVIDER_REQUEST)) {
            try {
                var result = h.handle(new BeforeProviderRequestEvent(current), ctx);
                if (result != null) {
                    current = result;
                    modified = true;
                }
            } catch (Throwable e) { // pi 的 catch 捕获一切：逐处理器上报 ⇒ 继续
                bus.emitError(h.error(e));
            }
        }
        return modified ? current : null;
    }

    /**
     * pi {@code handleProviderResponse}（sdk.ts:387-395，通用 emit 对位）：
     * 观察型，逐处理器异常隔离。
     */
    public void onResponse(int status, Map<String, String> headers, ModelInfo model) {
        var bus = busRef.get();
        if (bus == null || !bus.hasHandlers(AFTER_PROVIDER_RESPONSE)) {
            return;
        }
        for (var h : bus.snapshot(AFTER_PROVIDER_RESPONSE)) {
            try {
                h.handle(new AfterProviderResponseEvent(status, headers), ctx);
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
    }

    /**
     * pi {@code handleProviderStreamEvent}（sdk.ts:396-410）：事件五键
     * {@code {data, type, provider, api, model}}；{@code api} 在目录未命中时为
     * {@code null}（java 数据面缺席，pi 必填字段无对位）。
     */
    public void onStreamEvent(Object data, ModelInfo model) {
        var bus = busRef.get();
        if (bus == null || !bus.hasHandlers(PROVIDER_STREAM_EVENT)) {
            return;
        }
        var event = new ProviderStreamEvent(
            model == null ? null : model.id().provider(),
            model == null ? null : model.api(),
            model == null ? null : model.id().modelName(),
            data);
        for (var h : bus.snapshot(PROVIDER_STREAM_EVENT)) {
            try {
                h.handle(event, ctx);
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
    }

    // ── 宿主缝：transformHeaders（pi sdk.ts:354-363）─────────────────────

    /**
     * pi {@code buildRequestOptions} 的 {@code transformHeaders} 闭包对位：
     * {@code hasHandlers} 门 ⇒ 无订阅原样返回；处理器<b>原位改写</b>（返回值忽略），
     * {@code null} 值条目在派发后删除（pi {@code ProviderHeaders} 的 null＝删除；
     * 删除只及合并集内——SDK 默认头压不到，窄差 D34）。
     */
    public Map<String, String> transformHeaders(Map<String, String> headers) {
        var bus = busRef.get();
        if (bus == null || !bus.hasHandlers(BEFORE_PROVIDER_HEADERS)) {
            return headers;
        }
        for (var h : bus.snapshot(BEFORE_PROVIDER_HEADERS)) {
            try {
                h.handle(new BeforeProviderHeadersEvent(headers), ctx);
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
        headers.values().removeIf(java.util.Objects::isNull);
        return headers;
    }

    // ── DefaultProviders 注入辅助（docs/17 §3.11）────────────────────────

    /**
     * 三钩入 {@code extra}（键名逐字对位 pi {@code StreamOptions} 键）。
     * 车道构造期经 {@link ProviderHooks} 读取。
     */
    public ApiOptions withRequestHooks(ApiOptions options) {
        var extra = new LinkedHashMap<String, Object>(options.extra());
        extra.put(ProviderHooks.ON_PAYLOAD, (ProviderHooks.PayloadHook) this::onPayload);
        extra.put(ProviderHooks.ON_RESPONSE, (ProviderHooks.ResponseHook) this::onResponse);
        extra.put(ProviderHooks.ON_PROVIDER_STREAM_EVENT,
            (ProviderHooks.StreamEventHook) this::onStreamEvent);
        return new ApiOptions(options.baseUrl(), options.apiKey(), options.timeout(),
            options.maxRetries(), extra, options.authKind());
    }

    /**
     * pi {@code models.ts:866-871} 对位：合并头集（{@code extra["headers"]}，models.json
     * 三层合并值；缺席 ⇒ 空 map 起步＝pi 的 {@code headers ?? {}}）过
     * {@link #transformHeaders} 后写回；<b>钩本体不入 extra</b>（pi :870 剥离
     * {@code transformHeaders} 键／测试 "without forwarding the transform" 对位）。
     * 无订阅 ⇒ 原样返回（pi 的 {@code (headers ?? {})} 臂）。
     */
    public ApiOptions applyHeaderTransform(ApiOptions options) {
        var bus = busRef.get();
        if (bus == null || !bus.hasHandlers(BEFORE_PROVIDER_HEADERS)) {
            return options;
        }
        var extra = options.extra();
        Object raw = extra == null ? null : extra.get("headers");
        var headers = new LinkedHashMap<String, String>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> headers.put(
                String.valueOf(k), v == null ? null : v.toString()));
        }
        var transformed = transformHeaders(headers);
        var newExtra = new LinkedHashMap<String, Object>(
            extra == null ? Map.of() : extra);
        newExtra.put("headers", transformed);
        return new ApiOptions(options.baseUrl(), options.apiKey(), options.timeout(),
            options.maxRetries(), newExtra, options.authKind());
    }
}
