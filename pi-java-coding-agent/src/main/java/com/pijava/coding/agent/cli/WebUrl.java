package com.pijava.coding.agent.cli;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Login URLs with a safety frame (pi {@code utils/web-url.ts}, anchor {@code a00a5048d}).
 *
 * <p>An untrusted string printed as is could end a terminal hyperlink early or send
 * its own escape sequences, and the shared launcher also opens files and custom
 * protocols, so login only opens {@code http:} / {@code https:} URLs.</p>
 */
public final class WebUrl {

    /** C0 and C1 control characters, as one or more per run (pi {@code replaceControlCharacters}). */
    private static final String CONTROL_CHARACTERS = "[\u0000-\u001f\u007f-\u009f]+";

    private WebUrl() {}

    /**
     * The {@code http:} or {@code https:} URL {@code value} names, normalized, or empty.
     *
     * <p>Normalizing percent-encodes control characters, which would otherwise end a
     * terminal hyperlink early.</p>
     *
     * @param value the URL to inspect
     * @return the normalized URL, or empty when it is not a web URL
     */
    public static Optional<String> webUrl(String value) {
        if (value == null) {
            return Optional.empty();
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!uri.isAbsolute()) {
            return Optional.empty();
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("https") || scheme.equals("http")
            ? Optional.of(uri.toASCIIString()) : Optional.empty();
    }

    /**
     * {@code value} normalized as a web URL, else with control characters replaced.
     *
     * @param value the untrusted string
     * @return the display form
     */
    public static String displayUrl(String value) {
        return webUrl(value).orElseGet(() -> replaceControlCharacters(value));
    }

    /** Replace each run of C0 and C1 control characters (ESC, BEL, newlines, ...) with one space. */
    public static String replaceControlCharacters(String value) {
        return value == null ? "" : value.replaceAll(CONTROL_CHARACTERS, " ");
    }
}
