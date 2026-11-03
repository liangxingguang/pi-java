package com.pijava.ai.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * pi {@code auth/oauth/trusted-url.ts}：服务端 URL 的 HTTP(S) 白名单与归一。
 *
 * <p>登录流会把服务端给出的 URL 交给平台 URL 处理器打开；不校验的话
 * {@code file:}、自定义协议与 {@code javascript:} 同样会被打开。本类只接受
 * 可解析、协议为 {@code http:}／{@code https:} 且带 host 的绝对 URL，返回
 * WHATWG {@code URL.href} 形态的归一字符串，否则 {@code null}。</p>
 *
 * <p>docs/19 裁决 1：只对齐登录流实际会遇到的形态——大小写、默认端口、空路径、
 * 空格与 ISO 控制字符、非 ASCII 百分号编码、点段折叠；IDN 与反斜杠不模拟。</p>
 */
public final class TrustedHttpUrl {

    private TrustedHttpUrl() {
    }

    /** Return the normalized http(s) URL, or {@code null} when untrusted. */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        // WHATWG：TAB/CR/LF 直接删除；空格与其余 ISO 控制字符百分号编码。
        var encodable = stripAndEncodeControls(value);
        URI uri;
        try {
            uri = new URI(encodable);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            return null;
        }
        var scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        var rawHost = uri.getHost();
        if (rawHost == null || rawHost.isEmpty()) {
            return null;
        }
        // JDK 对 IPv6 返回带方括号的形态（实测 "[::1]"），重建时原样保留。
        var host = rawHost.toLowerCase(Locale.ROOT);
        var normalized = uri.normalize();
        var path = normalized.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        int port = uri.getPort();
        if (port == 80 && scheme.equals("http") || port == 443 && scheme.equals("https")) {
            port = -1;
        }
        var href = new StringBuilder();
        href.append(scheme).append("://");
        var userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            href.append(userInfo).append('@');
        }
        href.append(host);
        if (port != -1) {
            href.append(':').append(port);
        }
        href.append(path);
        var query = normalized.getRawQuery();
        if (query != null) {
            href.append('?').append(query);
        }
        var fragment = normalized.getRawFragment();
        if (fragment != null) {
            href.append('#').append(fragment);
        }
        try {
            // toASCIIString 把路径里的非 ASCII 字符百分号编码。
            return new URI(href.toString()).toASCIIString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** WHATWG：删除 TAB/CR/LF；空格与 C0/DEL 控制字符 → {@code %XX}。 */
    private static String stripAndEncodeControls(String value) {
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c == ' ' || c < 0x20 || c == 0x7f) {
                out.append('%')
                    .append(Character.toUpperCase(hexDigit(c / 16)))
                    .append(Character.toUpperCase(hexDigit(c % 16)));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static char hexDigit(int value) {
        return (char) (value < 10 ? '0' + value : 'A' + value - 10);
    }
}
