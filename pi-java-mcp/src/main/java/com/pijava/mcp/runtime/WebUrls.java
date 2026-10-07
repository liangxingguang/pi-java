package com.pijava.mcp.runtime;

import java.net.URI;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The parts of WHATWG {@code URL} the JDK's {@link URI} does not reproduce.
 *
 * <p>pi reads and builds URLs with {@code new URL}, which lower-cases the host, drops a port
 * that is the scheme's default, and always fills in a path. {@link URI} keeps the host's case,
 * reports the default port as written, and leaves an empty path empty.</p>
 */
final class WebUrls {

    private WebUrls() {
    }

    /**
     * {@code new URL(url).hostname}, which strips the IPv6 brackets and lower-cases
     * ({@code oauth.ts:86}).
     *
     * @param uri the parsed URL
     * @return the host name without brackets
     */
    static String hostname(URI uri) {
        var host = uri.getHost();
        if (host == null) {
            return "";
        }
        var lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            return lower.substring(1, lower.length() - 1);
        }
        return lower;
    }

    /**
     * {@code new URL(url).pathname}, which is {@code "/"} when the URL has no path.
     *
     * @param uri the parsed URL
     * @return the raw path
     */
    static String pathname(URI uri) {
        var path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    /**
     * {@code new URL(url).port}, which is empty for a missing port and for the scheme's default.
     *
     * @param uri the parsed URL
     * @return the port as written, or {@code null}
     */
    static @Nullable Integer port(URI uri) {
        var port = uri.getPort();
        return port < 0 || port == defaultPort(uri.getScheme()) ? null : port;
    }

    /**
     * {@code String(new URL(url))}: the href pi keys stored credentials by
     * ({@code oauth.ts:128}).
     *
     * @param url the URL text
     * @return the normalized href, or the input when it is not an absolute URL
     */
    static String normalize(String url) {
        return href(URI.create(url.trim()), true);
    }

    /**
     * {@code String(new URL(url))} with the fragment removed ({@code oauth.ts:230-231}).
     *
     * @param url the URL text
     * @return the normalized href without a fragment
     */
    static String normalizeWithoutFragment(String url) {
        return href(URI.create(url.trim()), false);
    }

    private static String href(URI uri, boolean withFragment) {
        var scheme = uri.getScheme();
        var host = uri.getHost();
        if (scheme == null || host == null) {
            return uri.toString();
        }
        var lowerScheme = scheme.toLowerCase(Locale.ROOT);
        var out = new StringBuilder(lowerScheme).append("://")
                .append(host.toLowerCase(Locale.ROOT));
        var port = port(uri);
        if (port != null) {
            out.append(':').append(port);
        }
        out.append(pathname(uri));
        if (uri.getRawQuery() != null) {
            out.append('?').append(uri.getRawQuery());
        }
        if (withFragment && uri.getRawFragment() != null) {
            out.append('#').append(uri.getRawFragment());
        }
        return out.toString();
    }

    /**
     * {@code url.href} after replacing the path, as {@code callbackSettings} does when it fills
     * a port into a configured redirect URI ({@code oauth.ts:92-93}).
     *
     * @param uri the parsed URL
     * @param port the port to write in
     * @return the href
     */
    static String withPort(URI uri, int port) {
        var out = new StringBuilder(uri.getScheme().toLowerCase(Locale.ROOT)).append("://")
                .append(uri.getRawAuthority()).append(':').append(port).append(pathname(uri));
        if (uri.getRawQuery() != null) {
            out.append('?').append(uri.getRawQuery());
        }
        return out.toString();
    }

    /**
     * {@code url.href} with the path replaced ({@code oauth.ts:257-259}).
     *
     * @param uri the parsed URL
     * @param path the new raw path
     * @return the href
     */
    static String withPath(URI uri, String path) {
        var out = new StringBuilder(uri.getScheme().toLowerCase(Locale.ROOT)).append("://")
                .append(uri.getRawAuthority()).append(path);
        if (uri.getRawQuery() != null) {
            out.append('?').append(uri.getRawQuery());
        }
        return out.toString();
    }

    private static int defaultPort(@Nullable String scheme) {
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : -1;
    }
}
