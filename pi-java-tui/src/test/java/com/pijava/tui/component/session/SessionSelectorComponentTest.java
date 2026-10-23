package com.pijava.tui.component.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.pijava.agent.session.jsonl.RichSessionInfo;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.spi.SessionListLoader;
import com.pijava.tui.component.session.SessionSelectorSearch.SortMode;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionSelectorComponent（docs/25 §7 #7）：键分派全序（确认态拦截→Tab→五 toggle
 * →导航→forward）；loadScope isActive 弃旧；toggleScope 懒载；删除流
 * （confirm→delete→status→refresh）；失败 status；rename null no-op。
 *
 * <p>默认 rig：直跑 executor/dispatcher ⇒ 加载全同步确定。</p>
 */
class SessionSelectorComponentTest {

    @TempDir Path tmp;

    private final AtomicLong clock = new AtomicLong(1_000);
    private final KeybindingsManager keys = new KeybindingsManager();

    private static RichSessionInfo session(String path, long modifiedMs) {
        return new RichSessionInfo(path, path, null, "", 0L, modifiedMs, 1, "first-" + path, "text",
            null);
    }

    private SessionSelectorComponent component(SessionListLoader current, SessionListLoader all) {
        return new SessionSelectorComponent(current, all, keys,
            Runnable::run, Runnable::run, clock::get, null);
    }

    private static SessionListLoader fixed(List<RichSessionInfo> sessions) {
        return (progress, signal) -> List.copyOf(sessions);
    }

    @Test
    void tabTogglesScopeAndLazyLoadsAll() {
        var current = List.of(session("c1", 100));
        var all = List.of(session("a1", 200), session("a2", 300));
        int[] allCalls = {0};
        var c = component(fixed(current), (progress, signal) -> {
            allCalls[0]++;
            return all;
        });

        assertThat(allCalls[0]).isZero();   // 构造只载 current
        assertThat(c.model().filtered().stream().map(n -> n.session().id())).containsExactly("c1");

        c.onKeyEvent(KeyEvent.ofKey(KeyCode.TAB));
        assertThat(allCalls[0]).isEqualTo(1);   // 懒载 all
        assertThat(c.model().filtered().stream().map(n -> n.session().id()))
            .containsExactly("a2", "a1");       // threaded 降序
        assertThat(c.model().showCwd()).isTrue();

        c.onKeyEvent(KeyEvent.ofKey(KeyCode.TAB));
        assertThat(allCalls[0]).isEqualTo(1);   // 回切走缓存，不重载
        assertThat(c.model().filtered().stream().map(n -> n.session().id())).containsExactly("c1");
    }

    @Test
    void togglesCycleSortNameFilterAndPath() {
        var c = component(fixed(List.of(session("x", 100))), fixed(List.of()));

        c.onKeyEvent(KeyEvent.ofChar('s', KeyModifiers.CTRL));
        assertThat(c.model().sortMode()).isEqualTo(SortMode.RECENT);
        c.onKeyEvent(KeyEvent.ofChar('s', KeyModifiers.CTRL));
        assertThat(c.model().sortMode()).isEqualTo(SortMode.RELEVANCE);
        c.onKeyEvent(KeyEvent.ofChar('s', KeyModifiers.CTRL));
        assertThat(c.model().sortMode()).isEqualTo(SortMode.THREADED);

        c.onKeyEvent(KeyEvent.ofChar('n', KeyModifiers.CTRL));
        assertThat(c.model().nameFilter()).isEqualTo(
            com.pijava.tui.component.session.SessionSelectorSearch.NameFilter.NAMED);

        assertThat(c.model().showPath()).isFalse();
        c.onKeyEvent(KeyEvent.ofChar('p', KeyModifiers.CTRL));
        assertThat(c.model().showPath()).isTrue();
        // header hint 同步（pi :828-831）——layout 行：0-2 头饰、3 标题、4 hint1、5 hint2
        var rows = c.layout(120);
        assertThat(rows.get(5).stream().map(SessionListRenderer.Span::text)
            .reduce("", String::concat)).contains("path (on)");
    }

    @Test
    void navigationClampsAndConfirmFinishesWithPath() {
        var c = component(fixed(List.of(session("one", 100), session("two", 200))),
            fixed(List.of()));
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.UP));
        assertThat(c.model().selectedIndex()).isZero();
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        assertThat(c.model().selectedIndex()).isEqualTo(1);

        c.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        assertThat(c.isDone()).isTrue();
        assertThat(c.isCancelled()).isFalse();
        // threaded 降序＝[two(200), one(100)]，下标 1 定案 one
        assertThat(c.selectedPath()).contains("one");
    }

    @Test
    void escapeCancels() {
        var c = component(fixed(List.of(session("one", 100))), fixed(List.of()));
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertThat(c.isDone()).isTrue();
        assertThat(c.isCancelled()).isTrue();
        assertThat(c.selectedPath()).isEmpty();
    }

    @Test
    void typingForwardsToSearchAndRefilters() {
        var c = component(fixed(List.of(session("apple", 100), session("banana", 200))),
            fixed(List.of()));
        for (var ch : "ban".toCharArray()) {
            c.onKeyEvent(KeyEvent.ofChar(ch));
        }
        assertThat(c.searchText()).isEqualTo("ban");
        assertThat(c.model().filtered().stream().map(n -> n.session().id()))
            .containsExactly("banana");
    }

    @Test
    void renameKeyIsAConsumedNoOpInPicker() {
        var c = component(fixed(List.of(session("one", 100))), fixed(List.of()));
        var before = c.model().filtered().size();
        assertThat(c.onKeyEvent(KeyEvent.ofChar('r', KeyModifiers.CTRL))).isTrue();
        assertThat(c.model().filtered()).hasSize(before);
        assertThat(c.isDone()).isFalse();
    }

    @Test
    void ctrlBackspaceForwardsWhenQueryNonEmptyElseConfirmsDelete() throws Exception {
        var file = Files.createFile(tmp.resolve("dead.jsonl"));
        var c = component(fixed(List.of(session(file.toString(), 100))), fixed(List.of()));

        // 查询非空 ⇒ 转发输入框（pi :601-604）。pi/java 编辑器都没有 ctrl+backspace
        // 绑定（pi keybindings.ts:124-125 只有 ctrl+w/alt+backspace）⇒ 文本不变、键被消费
        c.onKeyEvent(KeyEvent.ofChar('x'));
        c.onKeyEvent(KeyEvent.ofChar('y'));
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE, KeyModifiers.CTRL));
        assertThat(c.searchText()).isEqualTo("xy");
        assertThat(c.model().confirmingDeletePath()).isNull();

        // 清空查询后再敲 ⇒ 进入删除确认（pi :607）
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE));
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE));
        assertThat(c.searchText()).isEmpty();
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE, KeyModifiers.CTRL));
        assertThat(c.model().confirmingDeletePath()).isEqualTo(file.toString());
    }

    @Test
    void deleteFlowConfirmDeletesFileAndRefreshes() throws Exception {
        var file = Files.createFile(tmp.resolve("victim.jsonl"));
        var keeper = session("keeper", 100);
        var loads = new ArrayList<String>();
        SessionListLoader current = (progress, signal) -> {
            loads.add("load");
            // 第一次返回两条；删除后 refresh 的第二次只返回 keeper（仓已删）
            return loads.size() == 1
                ? List.of(session(file.toString(), 300), keeper)
                : List.of(keeper);
        };
        var c = component(current, fixed(List.of()));

        c.onKeyEvent(KeyEvent.ofChar('d', KeyModifiers.CTRL));   // 进入确认态
        assertThat(c.model().confirmingDeletePath()).isEqualTo(file.toString());
        // 确认态拦截一切（pi :543-557）：普通键被吞
        c.onKeyEvent(KeyEvent.ofChar('z'));
        assertThat(c.searchText()).isEmpty();
        assertThat(c.model().confirmingDeletePath()).isNotNull();

        c.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));   // 定案删除
        assertThat(Files.exists(file)).isFalse();
        assertThat(c.header().statusMessage()).isNotNull();
        assertThat(c.header().statusMessage().message()).isEqualTo("Session deleted");
        // refresh 重载后只剩 keeper
        assertThat(loads).hasSize(2);
        assertThat(c.model().filtered().stream().map(n -> n.session().id()))
            .containsExactly("keeper");
    }

    @Test
    void deleteFlowCancelLeavesEverything() throws Exception {
        var file = Files.createFile(tmp.resolve("spared.jsonl"));
        var c = component(fixed(List.of(session(file.toString(), 100))), fixed(List.of()));
        c.onKeyEvent(KeyEvent.ofChar('d', KeyModifiers.CTRL));
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));   // 退出确认态
        assertThat(c.model().confirmingDeletePath()).isNull();
        assertThat(Files.exists(file)).isTrue();
        assertThat(c.isDone()).isFalse();   // 确认态的 esc 不取消选择器
    }

    @Test
    void loadFailureShowsStatusAndEmptyList() {
        var c = component((progress, signal) -> {
            throw new IllegalStateException("boom");
        }, fixed(List.of()));

        assertThat(c.header().statusMessage()).isNotNull();
        assertThat(c.header().statusMessage().error()).isTrue();
        assertThat(c.header().statusMessage().message())
            .isEqualTo("Failed to load sessions: boom");
        assertThat(c.model().filtered()).isEmpty();
    }

    @Test
    void staleLoadCallbacksAreDroppedByIdentity() throws Exception {
        // 真线程 executor＋闩锁 loader：取消选择 ⇒ cancelLoads 后才放闩——
        // 旧句柄的回调必须被 isActive 丢弃（pi :955 identity 比较）。
        var latch = new CountDownLatch(1);
        var dispatched = new ArrayList<Runnable>();
        SessionListLoader blocking = (progress, signal) -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
            return List.of(session("late", 100));
        };
        SessionSelectorComponent c;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            c = new SessionSelectorComponent(blocking, fixed(List.of()), keys,
                executor, dispatched::add, clock::get, null);
            c.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));   // cancel ⇒ cancelLoads
            latch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                .isTrue();
        }
        dispatched.forEach(Runnable::run);   // 旧句柄回调到达
        assertThat(c.isDone()).isTrue();
        assertThat(c.model().filtered()).isEmpty();   // 弃旧：列表未被迟到的结果填充
    }
}
