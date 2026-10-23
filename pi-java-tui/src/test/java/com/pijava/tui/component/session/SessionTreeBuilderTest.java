package com.pijava.tui.component.session;

import java.util.List;

import com.pijava.agent.session.jsonl.RichSessionInfo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionTreeBuilder（docs/25 §7 #3）：认父（canonical）/孤儿⇒根/latestActivity
 * 子树最大/降序/flatten 的 ancestorContinues（depth&gt;0 规则）与 isLast/
 * formatSessionDate 七档/shortenPath home。
 */
class SessionTreeBuilderTest {

    private static RichSessionInfo session(String path, long modifiedMs, String parentPath) {
        return new RichSessionInfo(path, path, null, "", 0L, modifiedMs, 1, "first", "text",
            parentPath);
    }

    @Test
    void parentsAreLinkedOrphansBecomeRootsAndActivitySortsDescending() {
        var a = session("a", 100, null);
        var b = session("b", 400, "a");   // child of a
        var c = session("c", 200, "a");   // child of a
        var d = session("d", 300, null);
        var orphan = session("orphan", 50, "not-in-set");

        var roots = SessionTreeBuilder.buildSessionTree(List.of(a, b, c, d, orphan));

        // latestActivity＝子树最大：a=400；降序：a(400) > d(300) > orphan(50)
        assertThat(roots.stream().map(n -> n.session().path())).containsExactly("a", "d", "orphan");
        assertThat(roots.get(0).latestActivity()).isEqualTo(400);
        // children 降序：b(400) > c(200)
        assertThat(roots.get(0).children().stream().map(n -> n.session().path()))
            .containsExactly("b", "c");
        assertThat(roots.get(1).children()).isEmpty();
    }

    @Test
    void flattenFollowsTheDepthRuleForAncestorContinues() {
        var r = session("r", 100, null);
        var x1 = session("x1", 90, "r");
        var x2 = session("x2", 80, "r");
        var y = session("y", 70, "x1");

        var flat = SessionTreeBuilder.flattenSessionTree(
            SessionTreeBuilder.buildSessionTree(List.of(r, x1, x2, y)));

        // 序：r, x1, y, x2（深度先序；children 降序 x1 > x2）
        assertThat(flat.stream().map(n -> n.session().path())).containsExactly("r", "x1", "y", "x2");
        assertThat(flat.stream().map(SessionTreeBuilder.FlatSessionNode::depth))
            .containsExactly(0, 1, 2, 1);
        assertThat(flat.stream().map(SessionTreeBuilder.FlatSessionNode::isLast))
            .containsExactly(true, false, true, true);
        // ancestorContinues：depth 0 的 r 不产延续线（[false]）；x1 非末 ⇒ y 见 [false, true]
        assertThat(flat.get(1).ancestorContinues()).containsExactly(false);
        assertThat(flat.get(2).ancestorContinues()).containsExactly(false, true);
        assertThat(flat.get(3).ancestorContinues()).containsExactly(false);
    }

    @Test
    void formatSessionDateCoversAllSevenTiers() {
        long now = 1_000_000_000_000L;
        long m = 60_000L;
        long h = 3_600_000L;
        long d = 86_400_000L;
        assertThat(SessionTreeBuilder.formatSessionDate(now, now)).isEqualTo("now");
        assertThat(SessionTreeBuilder.formatSessionDate(now - 59 * m, now)).isEqualTo("59m");
        assertThat(SessionTreeBuilder.formatSessionDate(now - 3 * h, now)).isEqualTo("3h");
        assertThat(SessionTreeBuilder.formatSessionDate(now - 2 * d, now)).isEqualTo("2d");
        assertThat(SessionTreeBuilder.formatSessionDate(now - 14 * d, now)).isEqualTo("2w");
        assertThat(SessionTreeBuilder.formatSessionDate(now - 60 * d, now)).isEqualTo("2mo");
        assertThat(SessionTreeBuilder.formatSessionDate(now - 800 * d, now)).isEqualTo("2y");
    }

    @Test
    void shortenPathReplacesHomePrefixWithTilde() {
        var home = System.getProperty("user.home");
        assertThat(SessionTreeBuilder.shortenPath(home + "/sub/x.jsonl")).isEqualTo("~/sub/x.jsonl");
        assertThat(SessionTreeBuilder.shortenPath("/other/y.jsonl")).isEqualTo("/other/y.jsonl");
        assertThat(SessionTreeBuilder.shortenPath("")).isEmpty();
    }
}
