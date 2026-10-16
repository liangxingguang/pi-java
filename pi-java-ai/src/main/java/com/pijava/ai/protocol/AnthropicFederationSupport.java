package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.config.AuthenticationConfig;
import com.anthropic.config.AuthenticationType;
import com.anthropic.config.IdentityTokenConfig;
import com.anthropic.config.InMemoryProfileConfigProvider;
import com.anthropic.config.ProfileConfig;

import com.pijava.ai.api.ApiOptions;

/**
 * #98：Anthropic workload identity federation 的车道支撑（pi 锚点 {@code ea448f454}：
 * {@code providers/anthropic.ts:49-69} 的末位回落 ＋ {@code api/anthropic-messages.ts:346-370}
 * 的配置构造 ＋ {@code :373-377/:1053-1065} 的跨请求客户端缓存）。
 *
 * <p>协议面（RFC 7523 jwt-bearer 交换、token 缓存三级刷新、401 invalidate、身份 token
 * 文件逐次重读）全部由 anthropic-java 2.66.0 代劳 —— 与 JS SDK 0.124.0 逐字同构，
 * 本类只写<b>接线</b>：env 读取次序、缓存键、per-request 克隆。从
 * {@link AnthropicMessagesApi} 抽出（守「文件 ≤ 500 行」，同 {@link AnthropicStreamSupport}
 * 模式）。</p>
 *
 * <p><b>守卫裁决</b>（pi :330-337 的 {@code PiAnthropic} 等价物）：Java SDK 的环境凭证链是
 * <b>显式 opt-in</b>（{@code fromEnv()}／{@code configurationProvider()}），不设即不跑 ——
 * apiKey/authToken 车道天然无链，无需 guard 类；由 {@code AnthropicFederationTest} 的
 * 守卫夹具钉死。</p>
 */
final class AnthropicFederationSupport {

    /** pi {@code env-api-keys.ts:32-36} 的五变量：3 必需 ＋ 2 可选。 */
    static final String RULE_ID_ENV = "ANTHROPIC_FEDERATION_RULE_ID";
    static final String ORG_ID_ENV = "ANTHROPIC_ORGANIZATION_ID";
    static final String TOKEN_FILE_ENV = "ANTHROPIC_IDENTITY_TOKEN_FILE";
    static final String SERVICE_ACCOUNT_ENV = "ANTHROPIC_SERVICE_ACCOUNT_ID";
    static final String WORKSPACE_ENV = "ANTHROPIC_WORKSPACE_ID";

    private static final List<String> REQUIRED = List.of(RULE_ID_ENV, ORG_ID_ENV, TOKEN_FILE_ENV);
    private static final List<String> OPTIONAL = List.of(SERVICE_ACCOUNT_ENV, WORKSPACE_ENV);

    /** 宿主过桥键（{@code DefaultProviders.apiOptions}；与 {@code headers} 等同通道）。 */
    static final String EXTRA_KEY = "federationEnv";

    /**
     * pi :373-377 的 {@code federationClient}：车道与 pi 一样每请求新建 client，
     * 基座必须跨请求复用，否则每请求都触发一次 token 交换（pi 的 SDK 夹具断言
     * 3 次 stream ⇒ 恰 1 次交换）。
     */
    private static final ConcurrentHashMap<String, AnthropicClient> BASE_CLIENTS =
        new ConcurrentHashMap<>();

    private AnthropicFederationSupport() {
    }

    /**
     * 读五变量：{@code extra["federationEnv"]} 优先，{@code System.getenv} 回落
     * （pi {@code utils/provider-env.ts:44-50} 的 {@code env[name] → process.env[name]}；
     * Bun 的 {@code /proc/self/environ} fallback 是 Bun 编译产物 bug 的变通，java 无此问题
     * 不移植）。三必需缺任一 ⇒ {@code null}（＝ pi 的 {@code undefined}，pi :62-64）；
     * 两可选有则收（pi :66-70）。
     */
    static Map<String, String> federationEnvOf(ApiOptions options) {
        Object raw = options.extra() == null ? null : options.extra().get(EXTRA_KEY);
        Map<?, ?> bridged = raw instanceof Map<?, ?> map ? map : null;
        var resolved = new LinkedHashMap<String, String>();
        for (var name : REQUIRED) {
            var value = read(bridged, name);
            if (value == null) {
                return null;
            }
            resolved.put(name, value);
        }
        for (var name : OPTIONAL) {
            var value = read(bridged, name);
            if (value != null) {
                resolved.put(name, value);
            }
        }
        return resolved;
    }

    /** extra 优先、{@code System.getenv} 回落的单变量读取；blank 一律视为缺席。 */
    private static String read(Map<?, ?> bridged, String name) {
        Object value = bridged == null ? null : bridged.get(name);
        if (value != null && !value.toString().isBlank()) {
            return value.toString();
        }
        var sys = System.getenv(name);
        return sys == null || sys.isBlank() ? null : sys;
    }

    /**
     * pi {@code hasRequestAuth}（:316-322）的 headers 半边：三枚鉴权头名
     * （{@code authorization}／{@code x-api-key}／{@code cf-aig-authorization}），
     * 大小写不敏感、blank 值不算（pi {@code hasHeader} :306-313）。
     */
    static boolean hasAuthHeader(ApiOptions options) {
        Object raw = options.extra() == null ? null : options.extra().get("headers");
        if (!(raw instanceof Map<?, ?> headers)) {
            return false;
        }
        for (var entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue().toString().isBlank()) {
                continue;
            }
            var name = entry.getKey().toString().toLowerCase(Locale.ROOT);
            if (name.equals("authorization") || name.equals("x-api-key")
                    || name.equals("cf-aig-authorization")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本请求的 client（pi :1053-1065）：基座按缓存键复用（token 缓存随基座共享），
     * per-request 的 models.json default headers 经 {@code withOptions} 克隆挂上 ——
     * 与 key 车道的 {@code putExtraHeaders} 同通道同配方（pi :1065 的
     * {@code withOptions({ defaultHeaders })}）。
     */
    static AnthropicClient client(ApiOptions options, Map<String, String> env) {
        var base = BASE_CLIENTS.computeIfAbsent(cacheKey(options.baseUrl(), env),
            key -> buildBase(options.baseUrl(), env));
        return base.withOptions(b -> AbstractChatApi.putExtraHeaders(options, b::putHeader));
    }

    /** 缓存键：baseUrl ＋ 五值定序拼接（pi :1054 的 {@code JSON.stringify([baseUrl, config])}）。 */
    private static String cacheKey(String baseUrl, Map<String, String> env) {
        var sb = new StringBuilder(baseUrl == null ? "" : baseUrl);
        for (var name : REQUIRED) {
            sb.append('\n').append(env.get(name));
        }
        for (var name : OPTIONAL) {
            sb.append('\n').append(env.getOrDefault(name, ""));
        }
        return sb.toString();
    }

    /**
     * 基座 client：{@code configurationProvider} 直给内存 profile —— env-only 链 ⇒ SDK
     * 纯内存 token 缓存，<b>不落盘</b>（不设 {@code credentialsPath}，pi
     * {@code credential-chain.js} 同口径）；SDK 内置重试关到 0（A-14：初始请求由
     * {@code ProviderRetry} 独占）。per-request 头<b>不</b>挂在这里（pi :1056-1062 同）。
     */
    private static AnthropicClient buildBase(String baseUrl, Map<String, String> env) {
        // 字段逐个对应 pi :105-114 的 AnthropicFederationConfig。
        var identityToken = IdentityTokenConfig.builder()
            .source("file").path(env.get(TOKEN_FILE_ENV)).build();
        var authentication = AuthenticationConfig.builder()
            .type(AuthenticationType.OIDC_FEDERATION)
            .federationRuleId(env.get(RULE_ID_ENV))
            .serviceAccountId(Optional.ofNullable(env.get(SERVICE_ACCOUNT_ENV)))
            .identityToken(identityToken)
            .build();
        var profile = ProfileConfig.builder()
            .organizationId(env.get(ORG_ID_ENV))
            .workspaceId(Optional.ofNullable(env.get(WORKSPACE_ENV)))
            .authentication(authentication)
            .build();
        var builder = AnthropicOkHttpClient.builder()
            .configurationProvider(InMemoryProfileConfigProvider.of(profile))
            .maxRetries(0);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }
}
