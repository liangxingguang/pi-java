package com.pijava.tui.component.session;

import java.util.List;

import com.pijava.agent.session.jsonl.RichSessionInfo;
import com.pijava.tui.component.session.SessionSelectorSearch.NameFilter;
import com.pijava.tui.theme.SemanticColors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionListRenderer（docs/25 §7 #5）：行公式——光标 2 列/颜色优先
 * （确认删除&gt;当前&gt;有名&gt;默认）/右侧三段（count age、cwd、path 前缀）/
 * 可视窗居中公式/滚动指示出现条件/空表四文案。
 */
class SessionListRendererTest {

    private static final String KEY = "ctrl+n";

    private static RichSessionInfo session(String id, String name, String cwd, long modifiedMs) {
        return new RichSessionInfo(id, id, name, cwd, 0L, modifiedMs, 3, "first-" + id, "text", null);
    }

    private static String rowText(List<SessionListRenderer.Span> row) {
        return row.stream().map(SessionListRenderer.Span::text).reduce("", String::concat);
    }

    private SessionListRenderer rendererOf(SessionListModel model) {
        return new SessionListRenderer(model, KEY);
    }

    @Test
    void selectedRowGetsAccentCursorAndBoldOthersPlain() {
        var model = new SessionListModel(null);
        long now = System.currentTimeMillis();
        model.setSessions(List.of(session("a", null, "", now), session("b", null, "", now)),
            false, "");
        // threaded 树按活动降序，同刻 ⇒ 稳定入序后 sort 保持（相等不比）——用 RECENT 免歧义
        model.setSortMode(SessionSelectorSearch.SortMode.RECENT, "");
        var rows = rendererOf(model).layout(80);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get(0).text()).isEqualTo("› ");
        assertThat(rows.get(0).get(0).style()).isEqualTo(SemanticColors.ACCENT);
        assertThat(rows.get(1).get(0).text()).isEqualTo("  ");
        // RECENT 保入序 ⇒ 行 0 是 a（选中，消息加粗）、行 1 是 b（不加粗）
        assertThat(rows.get(0).stream().filter(s -> s.text().contains("first-a"))
            .findFirst().orElseThrow().style()).isEqualTo(SemanticColors.PLAIN.bold());
        assertThat(rows.get(1).stream().filter(s -> s.text().contains("first-b"))
            .findFirst().orElseThrow().style()).isEqualTo(SemanticColors.PLAIN);
    }

    @Test
    void colorPriorityConfirmingDeleteThenCurrentThenNamedThenDefault() {
        var model = new SessionListModel(null);
        long now = System.currentTimeMillis();
        model.setSessions(List.of(
            session("named", "My Session", "", now),
            session("plain", null, "", now)), false, "");
        model.setSortMode(SessionSelectorSearch.SortMode.RECENT, "");
        var renderer = rendererOf(model);

        var rows = renderer.layout(80);
        // 有名 ⇒ warning（行 0 选中加粗）；无名 ⇒ 默认
        assertThat(rows.get(0).stream().filter(s -> s.text().contains("My Session"))
            .findFirst().orElseThrow().style()).isEqualTo(SemanticColors.WARNING.bold());
        assertThat(rows.get(1).stream().filter(s -> s.text().contains("first-plain"))
            .findFirst().orElseThrow().style()).isEqualTo(SemanticColors.PLAIN);

        // 确认删除中 ⇒ error（message 与右侧两段同色；选中行叠加加粗）
        model.touchSelection();
        model.startDeleteConfirmationForSelectedSession();
        rows = renderer.layout(80);
        assertThat(rows.get(0).stream().filter(s -> s.text().contains("My Session"))
            .findFirst().orElseThrow().style()).isEqualTo(SemanticColors.ERROR.bold());
        assertThat(rows.get(0).get(rows.get(0).size() - 1).style()).isEqualTo(SemanticColors.ERROR);
    }

    @Test
    void rightSideComposesCountAgeCwdAndPath() {
        var model = new SessionListModel(null);
        long now = System.currentTimeMillis();
        model.setSessions(List.of(session("a", null, "/work/proj", now)), true, "");
        model.setSortMode(SessionSelectorSearch.SortMode.RECENT, "");
        model.setShowPath(true);

        var row = rendererOf(model).layout(120).get(0);
        var right = row.get(row.size() - 1).text();
        // showPath 前缀 path、showCwd 前缀 cwd、本体 {msgCount} {age}（pi :473-481 序）
        assertThat(right).isEqualTo("a /work/proj 3 now");
    }

    @Test
    void viewportCentersOnSelectionAndScrollIndicatorAppears() {
        var model = new SessionListModel(null);
        long now = System.currentTimeMillis();
        var sessions = new java.util.ArrayList<RichSessionInfo>();
        for (int i = 0; i < 15; i++) {
            sessions.add(session("s" + i, null, "", now));
        }
        model.setSessions(sessions, false, "");
        model.setSortMode(SessionSelectorSearch.SortMode.RECENT, "");
        for (int i = 0; i < 12; i++) {
            model.moveDown();
        }

        var rows = rendererOf(model).layout(80);
        // startIndex = max(0, min(12-5, 15-10)) = 5；endIndex = 15 ⇒ 10 行＋滚动指示
        assertThat(rows).hasSize(11);
        assertThat(rowText(rows.get(0))).contains("first-s5");
        assertThat(rowText(rows.get(10))).isEqualTo("  (13/15)");
    }

    @Test
    void emptyMessagesCoverTheFourCorners() {
        // 宽度 120 ⇒ 不触发 pi 的 truncateToWidth（ellipsis "…"）
        var model = new SessionListModel(null);
        model.setSessions(List.of(), false, "");
        model.setNameFilter(NameFilter.NAMED, "");
        assertThat(rowText(rendererOf(model).layout(120).get(0))).isEqualTo(
            "  No named sessions in current folder. Press ctrl+n to show all, or Tab to view all.");
        // named × all
        model.setSessions(List.of(), true, "");
        assertThat(rowText(rendererOf(model).layout(120).get(0))).isEqualTo(
            "  No named sessions found. Press ctrl+n to show all.");
        // all × all-scope
        model.setNameFilter(NameFilter.ALL, "");
        model.setSessions(List.of(), true, "");
        assertThat(rowText(rendererOf(model).layout(120).get(0))).isEqualTo("  No sessions found");
        // all × current
        model.setSessions(List.of(), false, "");
        assertThat(rowText(rendererOf(model).layout(120).get(0))).isEqualTo(
            "  No sessions in current folder. Press Tab to view all.");
    }

    @Test
    void treePrefixRendersBranchGlyphs() {
        var model = new SessionListModel(null);
        long now = System.currentTimeMillis();
        var parent = new RichSessionInfo("p", "p", null, "", 0L, now, 1, "parent", "text", null);
        var child = new RichSessionInfo("c", "c", null, "", 0L, now - 1000, 1, "child", "text", "p");
        model.setSessions(List.of(parent, child), false, "");

        var rows = rendererOf(model).layout(80);
        assertThat(rows).hasSize(2);
        assertThat(rowText(rows.get(1))).contains("└─ ");
        assertThat(rows.get(1).stream().filter(s -> s.text().contains("└─ "))
            .findFirst().orElseThrow().style()).isEqualTo(SemanticColors.DIM);
    }
}
