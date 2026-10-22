package com.pijava.coding.agent.core;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * pi {@code applyHttpProxySettings}（http-dispatcher.ts:45-49）对位：把
 * {@code settings.httpProxy} 应用为全进程代理。pi 写进程 env（HTTP_PROXY/HTTPS_PROXY
 * 的 {@code ??=}——env 已有值则不覆盖）；JVM 不可写进程 env ⇒ 写系统属性
 * {@code http.proxyHost/http.proxyPort/https.proxyHost/https.proxyPort}（同 {@code ??=}
 * 语义：env 或属性已有值则不覆盖）。
 *
 * <p>jdk {@code HttpClient} 未显式 {@code .proxy()} 时走
 * {@code ProxySelector.getDefault()}，其默认实现读上述系统属性——本仓全部
 * {@code HttpClient} 构造点都未显式设代理 ⇒ 一处设置、全部生效，与 pi「全局
 * dispatcher」的触及面等价。边缘面（URL 内嵌凭证、{@code nonProxyHosts}、socks
 * scheme）不解析——窄差 D52。</p>
 */
public final class HttpProxySupport {

    private HttpProxySupport() {}

    /** Apply the configured proxy process-wide; no-op when blank or already configured. */
    public static void apply(String httpProxy) {
        apply(httpProxy, System::getenv, System::setProperty);
    }

    /** 可注入面（单测不碰 JVM 全局态）：env 查询与属性写入。 */
    static void apply(String httpProxy, Function<String, String> env,
                      BiConsumer<String, String> setter) {
        var proxy = httpProxy == null ? null : httpProxy.trim();
        if (proxy == null || proxy.isEmpty()) {
            return;
        }
        if (env.apply("HTTP_PROXY") != null || env.apply("HTTPS_PROXY") != null) {
            return; // pi ??= 语义：env 优先
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(proxy);
        } catch (IllegalArgumentException e) {
            return; // 非法 URL：静默跳过（pi 对非法值也不报错——env 照写但 undici 才解析）
        }
        var host = uri.getHost();
        if (host == null) {
            return;
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        setIfAbsent("http.proxyHost", host, setter);
        setIfAbsent("http.proxyPort", Integer.toString(port), setter);
        setIfAbsent("https.proxyHost", host, setter);
        setIfAbsent("https.proxyPort", Integer.toString(port), setter);
    }

    private static void setIfAbsent(String key, String value,
                                    BiConsumer<String, String> setter) {
        if (System.getProperty(key) == null) {
            setter.accept(key, value);
        }
    }
}
