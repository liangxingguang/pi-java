package com.pijava.tui.component.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.pijava.agent.session.jsonl.RichSessionInfo;
import com.pijava.tui.component.FuzzyMatch;
import com.pijava.tui.component.session.SessionSelectorSearch.ParsedQuery.Mode;
import com.pijava.tui.component.session.SessionSelectorSearch.ParsedQuery.Token;
import com.pijava.tui.component.session.SessionSelectorSearch.ParsedQuery.Token.Kind;

/**
 * Session-selector search — verbatim port of pi
 * {@code modes/interactive/components/session-selector-search.ts} (anchor ea448f454),
 * coding-agent batch 14 (docs/25 T2). Pure functions over {@link RichSessionInfo}.
 *
 * <p>Search text is {@code id + " " + (name ?? "") + " " + allMessagesText + " " + cwd}
 * (pi :26-28). Query grammar: empty matches everything; {@code re:<pattern>} is a
 * case-insensitive regex (parse failure ⇒ error ⇒ empty result); otherwise quote-aware
 * tokenization into phrase ({@code "..."}) and fuzzy tokens, with unclosed-quote fallback
 * to plain whitespace splitting (pi :99-109).
 */
public final class SessionSelectorSearch {

    /** pi {@code SortMode} (:3); {@link #piName} is pi's lowercase literal. */
    public enum SortMode {
        THREADED("threaded"), RECENT("recent"), RELEVANCE("relevance");

        private final String piName;

        SortMode(String piName) {
            this.piName = piName;
        }

        /** pi literal ({@code "threaded" | "recent" | "relevance"}). */
        public String piName() {
            return piName;
        }
    }

    /** pi {@code NameFilter} (:5); {@link #piName} is pi's lowercase literal. */
    public enum NameFilter {
        ALL("all"), NAMED("named");

        private final String piName;

        NameFilter(String piName) {
            this.piName = piName;
        }

        /** pi literal ({@code "all" | "named"}). */
        public String piName() {
            return piName;
        }
    }

    /**
     * pi {@code ParsedSearchQuery} (:9-15). {@code error} non-null ⇒ parsing failed and
     * the query must be treated as non-matching (filter returns empty, pi :168).
     */
    public record ParsedQuery(Mode mode, List<Token> tokens, Pattern regex, String error) {

        public enum Mode { TOKENS, REGEX }

        public record Token(Kind kind, String value) {

            public enum Kind { FUZZY, PHRASE }
        }
    }

    /** pi {@code MatchResult} (:17-21); score is only meaningful when {@code matches}. */
    public record MatchResult(boolean matches, double score) {}

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private SessionSelectorSearch() {}

    /** pi {@code hasSessionName} (:30-32). */
    public static boolean hasSessionName(RichSessionInfo session) {
        return session.name() != null && !session.name().strip().isEmpty();
    }

    /** pi {@code getSessionSearchText} (:26-28) verbatim. */
    public static String searchText(RichSessionInfo session) {
        return session.id() + " " + (session.name() == null ? "" : session.name())
            + " " + session.allMessagesText() + " " + session.cwd();
    }

    /** pi {@code normalizeWhitespaceLower} (:22-24). */
    private static String normalizeWhitespaceLower(String text) {
        return WHITESPACE_RUN.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** pi {@code parseSearchQuery} (:39-114) verbatim. */
    public static ParsedQuery parseSearchQuery(String query) {
        var trimmed = query.strip();
        if (trimmed.isEmpty()) {
            return new ParsedQuery(Mode.TOKENS, List.of(), null, null);
        }

        // Regex mode: re:<pattern>
        if (trimmed.startsWith("re:")) {
            var pattern = trimmed.substring(3).strip();
            if (pattern.isEmpty()) {
                return new ParsedQuery(Mode.REGEX, List.of(), null, "Empty regex");
            }
            try {
                return new ParsedQuery(Mode.REGEX, List.of(),
                    Pattern.compile(pattern, Pattern.CASE_INSENSITIVE), null);
            } catch (PatternSyntaxException e) {
                // D75: error text is the PatternSyntaxException form (pi: V8 form);
                // behaviour identical (error ⇒ empty result).
                return new ParsedQuery(Mode.REGEX, List.of(), null, e.getMessage());
            }
        }

        // Token mode with quote support. Example: foo "node cve" bar
        var tokens = new ArrayList<Token>();
        var buf = new StringBuilder();
        boolean inQuote = false;
        boolean hadUnclosedQuote = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '"') {
                if (inQuote) {
                    flush(tokens, buf, Kind.PHRASE);
                    inQuote = false;
                } else {
                    flush(tokens, buf, Kind.FUZZY);
                    inQuote = true;
                }
                continue;
            }
            if (!inQuote && Character.isWhitespace(ch)) {
                flush(tokens, buf, Kind.FUZZY);
                continue;
            }
            buf.append(ch);
        }

        if (inQuote) {
            hadUnclosedQuote = true;
        }

        // Unbalanced quotes: fall back to plain whitespace tokenization (quote chars kept).
        if (hadUnclosedQuote) {
            var fallback = Arrays.stream(trimmed.split("\\s+"))
                .map(String::strip)
                .filter(t -> !t.isEmpty())
                .map(t -> new Token(Kind.FUZZY, t))
                .toList();
            return new ParsedQuery(Mode.TOKENS, fallback, null, null);
        }

        flush(tokens, buf, inQuote ? Kind.PHRASE : Kind.FUZZY);
        return new ParsedQuery(Mode.TOKENS, List.copyOf(tokens), null, null);
    }

    private static void flush(List<Token> tokens, StringBuilder buf, Kind kind) {
        var v = buf.toString().strip();
        buf.setLength(0);
        if (v.isEmpty()) {
            return;
        }
        tokens.add(new Token(kind, v));
    }

    /** pi {@code matchSession} (:116-154) verbatim. */
    public static MatchResult matchSession(RichSessionInfo session, ParsedQuery parsed) {
        var text = searchText(session);

        if (parsed.mode() == Mode.REGEX) {
            if (parsed.regex() == null) {
                return new MatchResult(false, 0);
            }
            var m = parsed.regex().matcher(text);
            if (!m.find()) {
                return new MatchResult(false, 0);
            }
            return new MatchResult(true, m.start() * 0.1);
        }

        if (parsed.tokens().isEmpty()) {
            return new MatchResult(true, 0);
        }

        double totalScore = 0;
        String normalizedText = null;

        for (var token : parsed.tokens()) {
            if (token.kind() == Kind.PHRASE) {
                if (normalizedText == null) {
                    normalizedText = normalizeWhitespaceLower(text);
                }
                var phrase = normalizeWhitespaceLower(token.value());
                if (phrase.isEmpty()) {
                    continue;
                }
                var idx = normalizedText.indexOf(phrase);
                if (idx < 0) {
                    return new MatchResult(false, 0);
                }
                totalScore += idx * 0.1;
                continue;
            }

            var match = FuzzyMatch.fuzzyMatch(token.value(), text);
            if (!match.matches()) {
                return new MatchResult(false, 0);
            }
            totalScore += match.score();
        }

        return new MatchResult(true, totalScore);
    }

    /**
     * pi {@code filterAndSortSessions} (:156-194) verbatim. {@code RECENT} filters only,
     * keeping incoming order; everything else (incl. {@code THREADED} with a query) sorts
     * by ascending score, ties broken by modified descending.
     */
    public static List<RichSessionInfo> filterAndSortSessions(
            List<RichSessionInfo> sessions, String query, SortMode sortMode, NameFilter nameFilter) {
        var nameFiltered = nameFilter == NameFilter.ALL
            ? sessions
            : sessions.stream().filter(SessionSelectorSearch::hasSessionName).toList();
        var trimmed = query.strip();
        if (trimmed.isEmpty()) {
            return List.copyOf(nameFiltered);
        }

        var parsed = parseSearchQuery(query);
        if (parsed.error() != null) {
            return List.of();
        }

        // Recent mode: filter only, keep incoming order.
        if (sortMode == SortMode.RECENT) {
            var filtered = new ArrayList<RichSessionInfo>();
            for (var s : nameFiltered) {
                if (matchSession(s, parsed).matches()) {
                    filtered.add(s);
                }
            }
            return List.copyOf(filtered);
        }

        // Relevance mode: sort by score, tie-break by modified desc.
        var scored = new ArrayList<ScoredSession>();
        for (var s : nameFiltered) {
            var res = matchSession(s, parsed);
            if (!res.matches()) {
                continue;
            }
            scored.add(new ScoredSession(s, res.score()));
        }

        scored.sort((a, b) -> {
            if (a.score() != b.score()) {
                return Double.compare(a.score(), b.score());
            }
            return Long.compare(b.session().modifiedMs(), a.session().modifiedMs());
        });

        return scored.stream().map(ScoredSession::session).toList();
    }

    private record ScoredSession(RichSessionInfo session, double score) {}
}
