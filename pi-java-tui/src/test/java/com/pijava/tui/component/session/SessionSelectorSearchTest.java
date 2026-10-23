package com.pijava.tui.component.session;

import java.util.List;

import com.pijava.agent.session.jsonl.RichSessionInfo;
import com.pijava.tui.component.session.SessionSelectorSearch.NameFilter;
import com.pijava.tui.component.session.SessionSelectorSearch.ParsedQuery;
import com.pijava.tui.component.session.SessionSelectorSearch.SortMode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * SessionSelectorSearch — mirrors pi {@code test/session-selector-search.test.ts}
 * (anchor ea448f454) plus the docs/25 §7 #2 assertion surface (re: arms, unclosed-quote
 * fallback, named filter, search-text composition).
 */
class SessionSelectorSearchTest {

    private static RichSessionInfo session(String id, String name, String allText, String cwd, long modifiedMs) {
        return new RichSessionInfo(id, "/tmp/" + id + ".jsonl", name, cwd, 0L, modifiedMs, 1,
            "(no messages)", allText, null);
    }

    private static RichSessionInfo session(String id, String allText, long modifiedMs) {
        return session(id, null, allText, "", modifiedMs);
    }

    private static List<String> ids(List<RichSessionInfo> sessions) {
        return sessions.stream().map(RichSessionInfo::id).toList();
    }

    // ---- pi blueprint cases ----

    @Test
    void filtersByQuotedPhraseWithWhitespaceNormalization() {
        var sessions = List.of(
            session("a", "node\n\n   cve was discussed", 1_000L),
            session("b", "node something else", 2_000L));
        var result = SessionSelectorSearch.filterAndSortSessions(sessions, "\"node cve\"", SortMode.RECENT, NameFilter.ALL);
        assertThat(ids(result)).containsExactly("a");
    }

    @Test
    void regexQueryIsCaseInsensitive() {
        var sessions = List.of(
            session("a", "Brave is great", 2_000L),
            session("b", "bravery is not the same", 3_000L));
        var result = SessionSelectorSearch.filterAndSortSessions(sessions, "re:\\bbrave\\b", SortMode.RECENT, NameFilter.ALL);
        assertThat(ids(result)).containsExactly("a");
    }

    @Test
    void recentSortPreservesInputOrder() {
        var sessions = List.of(
            session("newer", "brave", 3_000L),
            session("older", "brave", 1_000L),
            session("nomatch", "something else", 4_000L));
        var result = SessionSelectorSearch.filterAndSortSessions(sessions, "\"brave\"", SortMode.RECENT, NameFilter.ALL);
        assertThat(ids(result)).containsExactly("newer", "older");
    }

    @Test
    void relevanceSortsByScoreThenModifiedDesc() {
        var sessions = List.of(
            session("late", "xxxx brave", 3_000L),
            session("early", "brave xxxx", 1_000L));
        var result = SessionSelectorSearch.filterAndSortSessions(sessions, "\"brave\"", SortMode.RELEVANCE, NameFilter.ALL);
        assertThat(ids(result)).containsExactly("early", "late");

        // equal scores ⇒ modified descending wins
        var tied = List.of(
            session("newer", "brave", 3_000L),
            session("older", "brave", 1_000L));
        var tiedResult = SessionSelectorSearch.filterAndSortSessions(tied, "\"brave\"", SortMode.RELEVANCE, NameFilter.ALL);
        assertThat(ids(tiedResult)).containsExactly("newer", "older");
    }

    @Test
    void invalidRegexReturnsEmptyList() {
        var sessions = List.of(session("a", "brave", 1_000L));
        var result = SessionSelectorSearch.filterAndSortSessions(sessions, "re:[", SortMode.RECENT, NameFilter.ALL);
        assertThat(result).isEmpty();
    }

    // ---- docs/25 §7 #2 additions ----

    @Test
    void emptyQueryMatchesAllKeepingOrder() {
        var sessions = List.of(session("z", "a", 1L), session("y", "b", 2L));
        assertThat(ids(SessionSelectorSearch.filterAndSortSessions(sessions, "", SortMode.RELEVANCE, NameFilter.ALL)))
            .containsExactly("z", "y");
        assertThat(ids(SessionSelectorSearch.filterAndSortSessions(sessions, "   ", SortMode.RECENT, NameFilter.ALL)))
            .containsExactly("z", "y");
    }

    @Test
    void regexHitScoresByMatchIndex() {
        // text = "abc  alltext /cwd" (null name ⇒ empty segment) ⇒ "all" at index 5
        var s = session("abc", null, "alltext", "/cwd", 0L);
        var parsed = SessionSelectorSearch.parseSearchQuery("re:all");
        assertThat(parsed.mode()).isEqualTo(ParsedQuery.Mode.REGEX);
        assertThat(parsed.error()).isNull();
        var result = SessionSelectorSearch.matchSession(s, parsed);
        assertThat(result.matches()).isTrue();
        assertThat(result.score()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void emptyRegexPatternIsError() {
        var parsed = SessionSelectorSearch.parseSearchQuery("re:");
        assertThat(parsed.error()).isEqualTo("Empty regex");
        var sessions = List.of(session("a", "x", 1L));
        assertThat(SessionSelectorSearch.filterAndSortSessions(sessions, "re:", SortMode.RECENT, NameFilter.ALL)).isEmpty();
    }

    @Test
    void unclosedQuoteFallsBackToWhitespaceTokensKeepingQuoteChar() {
        var parsed = SessionSelectorSearch.parseSearchQuery("foo \"bar baz");
        assertThat(parsed.mode()).isEqualTo(ParsedQuery.Mode.TOKENS);
        assertThat(parsed.tokens()).containsExactly(
            new ParsedQuery.Token(ParsedQuery.Token.Kind.FUZZY, "foo"),
            new ParsedQuery.Token(ParsedQuery.Token.Kind.FUZZY, "\"bar"),
            new ParsedQuery.Token(ParsedQuery.Token.Kind.FUZZY, "baz"));
        // the retained quote char makes the token unmatchable against ordinary text
        var sessions = List.of(session("a", "foo bar baz", 1L));
        assertThat(SessionSelectorSearch.filterAndSortSessions(sessions, "foo \"bar baz", SortMode.RECENT, NameFilter.ALL))
            .isEmpty();
    }

    @Test
    void allFuzzyTokensMustMatch() {
        var s = session("a", "alpha beta", 1L);
        assertThat(SessionSelectorSearch.matchSession(s, SessionSelectorSearch.parseSearchQuery("alp bet")).matches())
            .isTrue();
        assertThat(SessionSelectorSearch.matchSession(s, SessionSelectorSearch.parseSearchQuery("alp zzz")).matches())
            .isFalse();
    }

    @Test
    void namedFilterDropsUnnamedSessions() {
        var named = session("a", "my session", "text", "", 1L);
        var unnamed = session("b", null, "text", "", 2L);
        var blankNamed = session("c", "   ", "text", "", 3L);
        var all = List.of(named, unnamed, blankNamed);
        assertThat(SessionSelectorSearch.filterAndSortSessions(all, "", SortMode.RECENT, NameFilter.NAMED))
            .containsExactly(named);
        assertThat(SessionSelectorSearch.hasSessionName(named)).isTrue();
        assertThat(SessionSelectorSearch.hasSessionName(unnamed)).isFalse();
        assertThat(SessionSelectorSearch.hasSessionName(blankNamed)).isFalse();
    }

    @Test
    void searchTextComposesIdNameAllTextAndCwd() {
        var s = session("the-id", "the-name", "the-text", "/the/cwd", 0L);
        assertThat(SessionSelectorSearch.searchText(s)).isEqualTo("the-id the-name the-text /the/cwd");
        // each segment independently reachable via phrase
        assertThat(SessionSelectorSearch.matchSession(s, SessionSelectorSearch.parseSearchQuery("\"the-id\"")).matches()).isTrue();
        assertThat(SessionSelectorSearch.matchSession(s, SessionSelectorSearch.parseSearchQuery("\"the-name\"")).matches()).isTrue();
        assertThat(SessionSelectorSearch.matchSession(s, SessionSelectorSearch.parseSearchQuery("\"the-text\"")).matches()).isTrue();
        assertThat(SessionSelectorSearch.matchSession(s, SessionSelectorSearch.parseSearchQuery("\"/the/cwd\"")).matches()).isTrue();
    }

    @Test
    void threadedWithQueryUsesScoreSortLikeRelevance() {
        var sessions = List.of(
            session("late", "xxxx brave", 3_000L),
            session("early", "brave xxxx", 1_000L));
        var result = SessionSelectorSearch.filterAndSortSessions(sessions, "\"brave\"", SortMode.THREADED, NameFilter.ALL);
        assertThat(ids(result)).containsExactly("early", "late");
    }
}
