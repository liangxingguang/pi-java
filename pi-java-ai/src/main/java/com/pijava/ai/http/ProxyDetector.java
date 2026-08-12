package com.pijava.ai.http;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.Optional;

/**
 * Detect system HTTP/HTTPS proxy settings from environment variables
 * and system properties.
 */
public final class ProxyDetector {

    private ProxyDetector() {}

    /**
     * Detect proxy from environment variables and system properties.
     * Checks: https_proxy → HTTPS_PROXY → http_proxy → HTTP_PROXY →
     * https.proxyHost / http.proxyHost system properties.
     *
     * @return a configured ProxySelector, or empty if no proxy is detected
     */
    public static Optional<ProxySelector> detectProxy() {
        String httpsProxy = firstNonEmpty(
            System.getenv("https_proxy"),
            System.getenv("HTTPS_PROXY"),
            System.getProperty("https.proxyHost") != null
                ? buildProxyUrl("https") : null
        );

        if (httpsProxy != null) {
            return Optional.of(createSelector(httpsProxy));
        }

        String httpProxy = firstNonEmpty(
            System.getenv("http_proxy"),
            System.getenv("HTTP_PROXY"),
            System.getProperty("http.proxyHost") != null
                ? buildProxyUrl("http") : null
        );

        if (httpProxy != null) {
            return Optional.of(createSelector(httpProxy));
        }

        return Optional.empty();
    }

    private static String buildProxyUrl(String scheme) {
        String host = System.getProperty(scheme + ".proxyHost");
        String port = System.getProperty(scheme + ".proxyPort", "80");
        if (host != null && !host.isEmpty()) {
            return scheme + "://" + host + ":" + port;
        }
        return null;
    }

    private static ProxySelector createSelector(String proxyUrl) {
        try {
            URI uri = URI.create(proxyUrl);
            return ProxySelector.of(
                new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 80));
        } catch (Exception e) {
            return ProxySelector.getDefault();
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
