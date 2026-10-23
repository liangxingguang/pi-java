package com.pijava.tui.component.session;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.component.session.SessionSelectorHeader.Scope;
import com.pijava.tui.component.session.SessionSelectorHeader.StatusMessage;
import com.pijava.tui.component.session.SessionSelectorSearch.NameFilter;
import com.pijava.tui.component.session.SessionSelectorSearch.SortMode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionSelectorHeader（docs/25 §7 #6）：标题两态/scopeText 三态
 * （Loading n/total 与 ...）/hint 三态（删除确认单行/状态消息/常态两行）/
 * setLoading 清进度/自动隐 deadline。
 */
class SessionSelectorHeaderTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final KeybindingsManager keys = new KeybindingsManager();

    private SessionSelectorHeader header(Scope scope) {
        return new SessionSelectorHeader(scope, SortMode.THREADED, NameFilter.ALL,
            false, keys, clock::get);
    }

    private static String rowText(List<SessionListRenderer.Span> row) {
        return row.stream().map(SessionListRenderer.Span::text).reduce("", String::concat);
    }

    @Test
    void titleFollowsScope() {
        assertThat(rowText(header(Scope.CURRENT).layout(120).get(0)))
            .startsWith("Resume Session (Current Folder)");
        assertThat(rowText(header(Scope.ALL).layout(120).get(0)))
            .startsWith("Resume Session (All)");
    }

    @Test
    void scopeTextThreeStates() {
        // current
        assertThat(rowText(header(Scope.CURRENT).layout(120).get(0)))
            .contains("◉ Current Folder | ○ All");
        // all
        assertThat(rowText(header(Scope.ALL).layout(120).get(0)))
            .contains("○ Current Folder | ◉ All");
        // loading 无进度 ⇒ ...；有进度 ⇒ n/total
        var loading = header(Scope.CURRENT);
        loading.setLoading(true);
        assertThat(rowText(loading.layout(120).get(0))).contains("Loading ...");
        loading.setProgress(3, 10);
        assertThat(rowText(loading.layout(120).get(0))).contains("Loading 3/10");
    }

    @Test
    void setLoadingClearsProgress() {
        var header = header(Scope.CURRENT);
        header.setLoading(true);
        header.setProgress(3, 10);
        header.setLoading(true);   // pi :88-92：再设即清
        assertThat(rowText(header.layout(120).get(0))).contains("Loading ...");
    }

    @Test
    void normalHintsRenderTwoRows() {
        var rows = header(Scope.CURRENT).layout(120);
        assertThat(rowText(rows.get(1))).isEqualTo(
            "tab scope · re:<pattern> regex · \"phrase\" exact");
        assertThat(rowText(rows.get(2))).isEqualTo(
            "ctrl+s sort · ctrl+n named · ctrl+d delete · ctrl+p path (off)");
    }

    @Test
    void confirmingDeleteHintIsASingleErrorRow() {
        var header = header(Scope.CURRENT);
        header.setConfirmingDeletePath("/x.jsonl");
        var rows = header.layout(120);
        assertThat(rowText(rows.get(1))).isEqualTo("Delete session? enter confirm · esc cancel");
        assertThat(rows.get(2)).isEmpty();
    }

    @Test
    void statusMessageReplacesHintsAndAutoHidesAtDeadline() {
        var header = header(Scope.CURRENT);
        header.setStatusMessage(new StatusMessage(false, "Session deleted"), 2000);
        var rows = header.layout(120);
        assertThat(rowText(rows.get(1))).isEqualTo("Session deleted");
        assertThat(rows.get(2)).isEmpty();

        clock.addAndGet(1999);
        header.tick();
        assertThat(header.statusMessage()).isNotNull();
        clock.addAndGet(2);
        header.tick();
        assertThat(header.statusMessage()).isNull();
        // 清除后回到常态两行
        assertThat(rowText(header.layout(120).get(1))).startsWith("tab scope");
    }

    @Test
    void sortLabelShowsFuzzyForRelevance() {
        var header = header(Scope.CURRENT);
        header.setSortMode(SortMode.RELEVANCE);
        assertThat(rowText(header.layout(120).get(0))).contains("Sort: Fuzzy");
    }
}
