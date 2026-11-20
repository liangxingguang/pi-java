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

    // docs/63（L7 W3，pi session-selector-search.ts:30-33）：档位绝对支配分数。
    /** pi {@code TIER_NAME}。 */
    private static final int TIER_NAME = 0;
    /** pi {@code TIER_TEXT}。 */
    private static final int TIER_TEXT = 1;
    /** pi {@code TIER_FUZZY}。 */
    private static final int TIER_FUZZY = 2;
    /** pi {@code TIER_WEIGHT}：档内分数最大绝对值远小于它，跨档不会互串。 */
    private static final double TIER_WEIGHT = 1_000_000d;

    private static final MatchResult NO_MATCH = new MatchResult(false, 0);

    private SessionSelectorSearch() {}

    /** pi {@code hasSessionName} (:30-32). */
    public static boolean hasSessionName(RichSessionInfo session) {
        return session.name() != null && !session.name().strip().isEmpty();
    }

    /**
     * pi {@code getSessionSearchText}（{@code :26-28}）的拼接形——仅作诊断/契约面。
     *
     * <p>docs/63 起打分改为<b>逐字段</b>（{@link #matchLiteralToken}），不再对拼接串
     * 做整体模糊匹配；保留本方法是为了让"可搜索语料"这件事仍有可测的表达。</p>
     */
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
                    Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), null);
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

    /**
     * pi {@code matchSession}（{@code :195-220}）：token 逐个独立匹配、分数累加，任一
     * token 不中即整条不匹配。字段分级见 {@link #matchLiteralToken} 等（docs/63）。
     */
    public static MatchResult matchSession(RichSessionInfo session, ParsedQuery parsed) {
        if (parsed.mode() == Mode.REGEX) {
            return parsed.regex() == null ? NO_MATCH : matchRegex(session, parsed.regex());
        }
        if (parsed.tokens().isEmpty()) {
            return new MatchResult(true, 0);
        }
        double total = 0;
        for (var token : parsed.tokens()) {
            var result = token.kind() == Kind.PHRASE
                ? matchPhraseToken(session, token.value())
                : matchFuzzyToken(session, token.value());
            if (!result.matches()) {
                return NO_MATCH;
            }
            total += result.score();
        }
        return new MatchResult(true, total);
    }

    /** 档位分（pi {@code tierScore}，{@code :62-64}）：档位绝对支配。 */
    private static MatchResult hit(int tier, double score) {
        return new MatchResult(true, tier * TIER_WEIGHT + score);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * pi {@code matchRegex}（{@code :97-103}）：逐字段 {@code find}，命中即按字段定档
     * （{@code name} ⇒ 名字档，其余 ⇒ 文本档）。
     */
    private static MatchResult matchRegex(RichSessionInfo session, Pattern regex) {
        if (regex.matcher(lower(session.name())).find()) {
            return hit(TIER_NAME, 0);
        }
        for (var field : textFields(session)) {
            if (regex.matcher(field).find()) {
                return hit(TIER_TEXT, 0);
            }
        }
        return NO_MATCH;
    }

    /**
     * pi {@code matchLiteralToken}（{@code :67-74}）：大小写不敏感精确子串，字段序
     * {@code name → id → allMessagesText → cwd}；命中 {@code name} 时子分取模糊分，
     * 其余字段子分固定 0。
     */
    private static MatchResult matchLiteralToken(RichSessionInfo session, String token) {
        var query = lower(token);
        var name = lower(session.name());
        if (name.contains(query)) {
            return hit(TIER_NAME, FuzzyMatch.fuzzyMatch(query, name).score());
        }
        for (var field : textFields(session)) {
            if (field.contains(query)) {
                return hit(TIER_TEXT, 0);
            }
        }
        return NO_MATCH;
    }

    /** pi {@code matchPhraseToken}（{@code :88-95}）：折叠空白后逐字段命中。 */
    private static MatchResult matchPhraseToken(RichSessionInfo session, String token) {
        var phrase = normalizeWhitespaceLower(token);
        if (phrase.isEmpty()) {
            return new MatchResult(true, 0);
        }
        if (normalizeWhitespaceLower(lower(session.name())).contains(phrase)) {
            return hit(TIER_NAME, 0);
        }
        for (var field : textFields(session)) {
            if (normalizeWhitespaceLower(field).contains(phrase)) {
                return hit(TIER_TEXT, 0);
            }
        }
        return NO_MATCH;
    }

    /**
     * pi {@code matchFuzzyToken}（{@code :76-86}）：先退回精确匹配；未中才在
     * <b>{@code name}/{@code cwd}</b> 上取最小模糊分。{@code id}/消息文本只允许精确
     * 匹配（回归 #11024）。
     */
    private static MatchResult matchFuzzyToken(RichSessionInfo session, String token) {
        var literal = matchLiteralToken(session, token);
        if (literal.matches()) {
            return literal;
        }
        var query = lower(token);
        double best = Double.POSITIVE_INFINITY;
        for (var field : new String[] {lower(session.name()), lower(session.cwd())}) {
            if (field.isEmpty()) {
                continue;
            }
            var match = FuzzyMatch.fuzzyMatch(query, field);
            if (match.matches()) {
                best = Math.min(best, match.score());
            }
        }
        return best == Double.POSITIVE_INFINITY ? NO_MATCH : hit(TIER_FUZZY, best);
    }

    /** 文本档字段（小写）：{@code id → allMessagesText → cwd}。 */
    private static String[] textFields(RichSessionInfo session) {
        return new String[] {lower(session.id()), lower(session.allMessagesText()), lower(session.cwd())};
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
