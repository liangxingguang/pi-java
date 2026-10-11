package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.ai.catalog.SessionAffinityFormat;

/**
 * 三条车道的会话亲和头纯函数组装（B103，docs/52）—— 对齐 pi
 * {@code openai-responses.ts:277-286}、{@code openai-completions.ts:776-786}、
 * {@code anthropic-messages.ts:1038-1043}。
 *
 * <p>params 级 {@code putAdditionalHeader} 经官方 Java SDK 的 streaming 路径透传
 * （responses 车道早已使用；javap 实证，B140 旧结论作废）。入参 sessionId 一律是过了
 * cacheRetention=none 门的 cacheSessionId（pi 三车道 :159/:348/:625）。</p>
 */
public final class SessionAffinityHeaders {

    /** openrouter 形头。 */
    public static final String X_SESSION_ID = "x-session-id";
    /** openai 形会话头。 */
    public static final String SESSION_ID = "session_id";
    public static final String X_CLIENT_REQUEST_ID = "x-client-request-id";

    /** openai 形会话头里的 x-session-affinity（completions 车道专用）。 */
    public static final String X_SESSION_AFFINITY = "x-session-affinity";

    private SessionAffinityHeaders() {}

    /**
     * Completions 车道（pi {@code openai-completions.ts:776-786}）：
     * <pre>
     * openrouter       → x-session-id
     * openai           → session_id + x-client-request-id + x-session-affinity
     * openai-nosession → x-client-request-id + x-session-affinity
     * </pre>
     * 门（sessionId 非空 ＋ send）由调用方按解析后 compat 给；入参 sessionId 须已过
     * cacheRetention=none 门（pi :348 的 cacheSessionId）。
     */
    public static Map<String, String> completions(String sessionId, boolean send,
                                                  SessionAffinityFormat format,
                                                  String provider, String baseUrl) {
        if (!send || sessionId == null || sessionId.isBlank()) {
            return Map.of();
        }
        var effective = format != null
            ? format
            : (isOpenRouter(provider, baseUrl) ? SessionAffinityFormat.OPENROUTER
                                               : SessionAffinityFormat.OPENAI);
        var headers = new LinkedHashMap<String, String>();
        switch (effective) {
            case OPENROUTER -> headers.put(X_SESSION_ID, sessionId);
            case OPENAI -> {
                headers.put(SESSION_ID, sessionId);
                headers.put(X_CLIENT_REQUEST_ID, sessionId);
                headers.put(X_SESSION_AFFINITY, sessionId);
            }
            case OPENAI_NOSESSION -> {
                headers.put(X_CLIENT_REQUEST_ID, sessionId);
                headers.put(X_SESSION_AFFINITY, sessionId);
            }
        }
        return headers;
    }

    /**
     * Anthropic 车道（pi {@code anthropic-messages.ts:1038-1043}）：单头 —— openrouter 形
     * （显式 format 或探测命中）发 {@code x-session-id}，其余一律 {@code x-session-affinity}
     * （显式 openai/openai-nosession 也走它，pi 只比 {@code === "openrouter"}）。
     */
    public static Map<String, String> anthropic(String sessionId, boolean send,
                                                SessionAffinityFormat format,
                                                String provider, String baseUrl) {
        if (!send || sessionId == null || sessionId.isBlank()) {
            return Map.of();
        }
        boolean openRouter = format == SessionAffinityFormat.OPENROUTER
            || (format == null && isOpenRouter(provider, baseUrl));
        return Map.of(openRouter ? X_SESSION_ID : X_SESSION_AFFINITY, sessionId);
    }

    /**
     * openrouter 探测判据（pi 各车道 {@code detectSessionAffinityFormat}）：
     * provider 等于 openrouter 或 baseUrl 含 {@code openrouter.ai}。
     */
    public static boolean isOpenRouter(String provider, String baseUrl) {
        return "openrouter".equals(provider)
            || (baseUrl != null && baseUrl.contains("openrouter.ai"));
    }

    /**
     * Responses 车道（<b>无 send 开关</b>，sessionId 非空即发；<b>不发</b>
     * x-session-affinity）：
     * <pre>
     * openrouter       → x-session-id
     * openai           → session_id + x-client-request-id
     * openai-nosession → x-client-request-id
     * </pre>
     */
    public static Map<String, String> responses(String sessionId, SessionAffinityFormat format,
                                                 String provider, String baseUrl) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of();
        }
        boolean openRouter = isOpenRouter(provider, baseUrl);
        var effective = format != null
            ? format
            : (openRouter ? SessionAffinityFormat.OPENROUTER : SessionAffinityFormat.OPENAI);
        var headers = new LinkedHashMap<String, String>();
        switch (effective) {
            case OPENROUTER -> headers.put(X_SESSION_ID, sessionId);
            case OPENAI -> {
                headers.put(SESSION_ID, sessionId);
                headers.put(X_CLIENT_REQUEST_ID, sessionId);
            }
            case OPENAI_NOSESSION -> headers.put(X_CLIENT_REQUEST_ID, sessionId);
        }
        return headers;
    }
}
