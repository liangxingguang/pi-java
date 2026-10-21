package com.pijava.ai.api;

import java.util.Map;

import com.pijava.ai.catalog.ModelInfo;

/**
 * pi {@code StreamOptions} 请求面四钩（{@code ai/src/types.ts:178-223}）的
 * {@link ApiOptions#extra()} 通道对位 —— docs/17（coding-agent 批 7，B248）。
 *
 * <h2>三键一缺</h2>
 *
 * <p>pi 的 {@code onPayload}/{@code onResponse}/{@code onProviderStreamEvent} 是
 * per-request 选项键，java 经 extra 三键（键名逐字）过桥，车道<b>构造期</b>读取存字段
 * （车道每请求新建，构造期＝请求期，{@code cacheRetention} 先例）。pi 的第四键
 * {@code transformHeaders} <b>不设 extra 键</b>：它在 pi 的唯一消费点是 Models 层
 * {@code applyAuth}（{@code models.ts:866-871}，消费后剥离键），java 无 Models 层，
 * 等价消费点是宿主侧 {@code DefaultProviders.streamBlocking} 的 createApi 前缝
 * （裁决①——钩本体不下车道，pi 测试 "without forwarding the transform" 对位）。</p>
 *
 * <h2>载荷载体</h2>
 *
 * <p>pi 的 {@code payload: unknown} 是车道原生 params；java 同样是车道原生对象
 * （SDK params 类／{@code Map}/{@code ObjectNode}／
 * {@link com.pijava.ai.protocol.GooglePayload}），替换必须同型——车道单点
 * {@code instanceof} 守卫，不符 ⇒ {@link IllegalStateException}（裁决⑤：pi 会把
 * 垃圾形状发给 provider 收 400，同属「扩展弄坏请求 ⇒ 请求失败」，失败点提前）。</p>
 */
public final class ProviderHooks {

    /** extra 键：pi {@code onPayload}（types.ts:178）。 */
    public static final String ON_PAYLOAD = "onPayload";
    /** extra 键：pi {@code onResponse}（types.ts:217）。 */
    public static final String ON_RESPONSE = "onResponse";
    /** extra 键：pi {@code onProviderStreamEvent}（types.ts:223）。 */
    public static final String ON_PROVIDER_STREAM_EVENT = "onProviderStreamEvent";

    private ProviderHooks() {}

    /**
     * pi {@code onPayload}：请求载荷构建后、HTTP 调用前 firing。
     *
     * <p>非 null 返回 ⇒ <b>替换</b>当前载荷（pi：{@code nextParams !== undefined}）；
     * null ⇒ 保留。载荷是车道原生对象，替换必须同型（见类 javadoc）。</p>
     */
    @FunctionalInterface
    public interface PayloadHook {
        /** @return 替换载荷；{@code null} ≙ pi 的 {@code undefined}（保留原载荷） */
        Object onPayload(Object payload, ModelInfo model);
    }

    /**
     * pi {@code onResponse}：响应到达、流消费前 firing（观察型，返回值忽略）。
     *
     * <p>SDK 车道只在<b>成功获取</b>后发（重试耗尽在重试器内抛 ⇒ 不发，pi 同形）；
     * 自备 HTTP 车道收到<b>终态</b>响应即发（含错误状态，pi fetch 车道同形）。
     * google 车道不发（pi 的 google-generative-ai.ts/google-vertex.ts 全文件零命中）。</p>
     *
     * @param headers 摊平后的响应头（同名多值逗号连接，名按 SDK/JDK 原样——
     *                pi fetch {@code Headers.entries()} 是小写，窄差 D35）
     */
    @FunctionalInterface
    public interface ResponseHook {
        void onResponse(int status, Map<String, String> headers, ModelInfo model);
    }

    /**
     * pi {@code onProviderStreamEvent}：每个原始 provider 流事件、归一化前 firing
     * （观察型）。{@code data} 是适配器所有的原始事件对象（SDK 事件联合类／
     * {@code GenerateContentResponse}／解析后 {@code Map}／{@code PiMessagesEvent}），
     * <b>只读</b>（pi types.ts:223 注释同义）。
     */
    @FunctionalInterface
    public interface StreamEventHook {
        void onEvent(Object data, ModelInfo model);
    }

    /** 读取 {@link #ON_PAYLOAD} 钩（缺席/类型不符 ⇒ {@code null} ≙ pi 的 undefined）。 */
    public static PayloadHook payloadHookOf(ApiOptions options) {
        return hookOf(options, ON_PAYLOAD, PayloadHook.class);
    }

    /** 读取 {@link #ON_RESPONSE} 钩。 */
    public static ResponseHook responseHookOf(ApiOptions options) {
        return hookOf(options, ON_RESPONSE, ResponseHook.class);
    }

    /** 读取 {@link #ON_PROVIDER_STREAM_EVENT} 钩。 */
    public static StreamEventHook streamEventHookOf(ApiOptions options) {
        return hookOf(options, ON_PROVIDER_STREAM_EVENT, StreamEventHook.class);
    }

    private static <T> T hookOf(ApiOptions options, String key, Class<T> type) {
        var extra = options == null ? null : options.extra();
        Object raw = extra == null ? null : extra.get(key);
        return type.isInstance(raw) ? type.cast(raw) : null;
    }

    /**
     * 响应头摊平（pi {@code headersToRecord}，utils/headers.ts）：同名多值逗号连接
     * （≈fetch {@code Headers.entries()} 行为）；名按来源原样（SDK/JDK 保留 wire
     * 大小写，pi fetch 是小写——窄差 D35）。anthropic/openai 两 SDK 的 {@code Headers}
     * 同为 {@code names()×values(name)} 形状，JDK {@code HttpHeaders} 走
     * {@link #flattenHeaders(Map)} 重载。
     */
    public static Map<String, String> flattenHeaders(Iterable<String> names,
            java.util.function.Function<String, java.util.List<String>> values) {
        var out = new java.util.LinkedHashMap<String, String>();
        for (var name : names) {
            out.put(name, String.join(",", values.apply(name)));
        }
        return out;
    }

    /** JDK {@code HttpHeaders.map()} 形重载（自备 HTTP 车道用）。 */
    public static Map<String, String> flattenHeaders(Map<String, java.util.List<String>> headers) {
        var out = new java.util.LinkedHashMap<String, String>();
        headers.forEach((name, values) -> out.put(name, String.join(",", values)));
        return out;
    }

    /**
     * 替换载荷的同型守卫（裁决⑤，pi 的 TS 结构类型展开在 java 不可达）：
     * 不符 ⇒ {@link IllegalStateException} 请求失败（pi 会把垃圾形状发给
     * provider 收 400——同属「扩展弄坏请求 ⇒ 请求失败」，失败点提前，窄差 D31）。
     */
    public static <T> T requirePayloadType(Object payload, Class<T> type) {
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        throw new IllegalStateException(
            "before_provider_request handler returned " + payload.getClass().getName()
                + ", expected " + type.getName());
    }
}
