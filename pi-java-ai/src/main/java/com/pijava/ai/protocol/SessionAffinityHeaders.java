package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.ai.catalog.SessionAffinityFormat;

/**
 * Responses 车道的会话亲和头纯函数组装（包 B103）—— 对齐 pi
 * {@code openai-responses.ts:258-267}。
 *
 * <p>Anthropic 与 completions 车道的非标准亲和头在官方 Java SDK 的 streaming transport
 * 不可达（登记 B140），故这里只承担 responses 这一条。</p>
 */
public final class SessionAffinityHeaders {

    /** openrouter 形头。 */
    public static final String X_SESSION_ID = "x-session-id";
    /** openai 形会话头。 */
    public static final String SESSION_ID = "session_id";
    public static final String X_CLIENT_REQUEST_ID = "x-client-request-id";

    private SessionAffinityHeaders() {}

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
