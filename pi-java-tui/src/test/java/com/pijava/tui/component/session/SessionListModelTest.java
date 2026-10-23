package com.pijava.tui.component.session;

import java.util.List;

import com.pijava.agent.session.jsonl.RichSessionInfo;
import com.pijava.tui.component.session.SessionSelectorSearch.SortMode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionListModel（docs/25 §7 #4）：filterSessions threaded 无查询⇒树、有查询⇒平表；
 * selection clamp；setSessions 未摸⇒0、摸过⇒按 path 找回；删除确认态转移；
 * 导航 clamp 不环绕、pageUp/pageDown ±10。
 */
class SessionListModelTest {

    private static RichSessionInfo session(String id, long modifiedMs) {
        return new RichSessionInfo(id, id, null, "", 0L, modifiedMs, 1, "first-" + id, "text-" + id,
            null);
    }

    @Test
    void threadedWithoutQueryBuildsTreeWithQueryGoesFlat() {
        var model = new SessionListModel(null);
        model.setSessions(List.of(
            session("a", 100), session("b", 200)), false, "");
        // threaded＋空查询 ⇒ 树（此表无父子 ⇒ 全 depth 0 但按活动降序 b,a）
        assertThat(model.filtered().stream().map(n -> n.session().id())).containsExactly("b", "a");

        model.filterSessions("text-a");
        // 有查询 ⇒ 平表（depth 0、isLast true、无祖级）
        assertThat(model.filtered()).allMatch(n -> n.depth() == 0 && n.isLast()
            && n.ancestorContinues().isEmpty());
        assertThat(model.filtered().stream().map(n -> n.session().id())).containsExactly("a");
    }

    @Test
    void selectionIsClampedAfterFiltering() {
        var model = new SessionListModel(null);
        var sessions = new java.util.ArrayList<RichSessionInfo>();
        for (int i = 0; i < 20; i++) {
            sessions.add(session("s" + i, i));
        }
        model.setSessions(sessions, false, "");
        for (int i = 0; i < 19; i++) {
            model.moveDown();
        }
        assertThat(model.selectedIndex()).isEqualTo(19);

        model.filterSessions("s1");   // 命中 s1, s10–s19 ⇒ 11 条
        assertThat(model.selectedIndex()).isLessThanOrEqualTo(model.filtered().size() - 1);
    }

    @Test
    void setSessionsResetsUntouchedSelectionAndRestoresTouchedByPath() {
        var model = new SessionListModel(null);
        model.setSessions(List.of(session("a", 100), session("b", 200), session("c", 300)),
            false, "");
        // 未摸过 ⇒ 重置 0
        model.setSessions(List.of(session("x", 400), session("b", 200)), false, "");
        assertThat(model.selectedIndex()).isZero();

        // 摸过 ⇒ 按 path 找回（当前 filtered＝[x(400), b(200)]，moveDown 选中 b；
        // 新表 threaded 降序＝[c(300), b(200)]，b 找回在下标 1）
        model.touchSelection();
        model.moveDown();
        model.setSessions(List.of(session("b", 200), session("c", 300)), false, "");
        assertThat(model.selectedIndex()).isEqualTo(1);
        assertThat(model.selectedNode().session().id()).isEqualTo("b");
    }

    @Test
    void navigationClampsWithoutWrapping() {
        var model = new SessionListModel(null);
        var sessions = new java.util.ArrayList<RichSessionInfo>();
        for (int i = 0; i < 25; i++) {
            sessions.add(session("s" + i, i));
        }
        model.setSessions(sessions, false, "");
        model.moveUp();
        assertThat(model.selectedIndex()).isZero();   // 到顶不环绕

        model.pageDown();
        assertThat(model.selectedIndex()).isEqualTo(10);
        model.pageUp();
        assertThat(model.selectedIndex()).isZero();
        for (int i = 0; i < 30; i++) {
            model.moveDown();
        }
        assertThat(model.selectedIndex()).isEqualTo(24);   // 到底不环绕
    }

    @Test
    void deleteConfirmationTransitions() {
        var model = new SessionListModel(null);
        // 空表 ⇒ NO_SELECTION
        assertThat(model.startDeleteConfirmationForSelectedSession())
            .isEqualTo(SessionListModel.DeleteStart.NO_SELECTION);

        model.setSessions(List.of(session("a", 100)), false, "");
        assertThat(model.startDeleteConfirmationForSelectedSession())
            .isEqualTo(SessionListModel.DeleteStart.STARTED);
        assertThat(model.confirmingDeletePath()).isEqualTo("a");
        model.clearConfirmingDelete();
        assertThat(model.confirmingDeletePath()).isNull();
    }

    @Test
    void sortAndNameFilterSettersRefilterWithCurrentQuery() {
        var model = new SessionListModel(null);
        model.setSessions(List.of(session("a", 100), session("b", 200)), false, "");
        model.setSortMode(SortMode.RECENT, "");
        assertThat(model.sortMode()).isEqualTo(SortMode.RECENT);
        // recent 保入序（a 先入）
        assertThat(model.filtered().stream().map(n -> n.session().id())).containsExactly("a", "b");
    }
}
