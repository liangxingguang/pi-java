package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.skill.SkillManager;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.SessionEventFirer;
import com.pijava.coding.agent.extension.event.ExtensionResults.ProjectTrustEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.TrustDecision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.PROJECT_TRUST;

/**
 * 批 23（docs/34 T3）：{@link ProjectTrustResolver} 七级链（pi project-trust.ts:47-97
 * 逐字序）＋ firer 的 first-wins/undecided 穿透（runner.ts:292-320）。
 */
class ProjectTrustResolverTest {

    @TempDir
    Path agentDir;

    @TempDir
    Path cwd;

    private ProjectTrustStore store;
    private ExtensionEventBus bus;
    private SessionEventFirer firer;
    private List<String> warnings;

    @BeforeEach
    void setUp() {
        store = new ProjectTrustStore(agentDir);
        bus = new ExtensionEventBus();
        var ctx = new DefaultExtensionContext(null, new SkillManager(),
            ExtensionUI.noop(), bus);
        firer = new SessionEventFirer(bus, ctx);
        warnings = new CopyOnWriteArrayList<>();
    }

    /** 种一个信任敏感资源（.pi-java/settings.json）。 */
    private void plantResources() throws Exception {
        var dir = Files.createDirectories(cwd.resolve(".pi-java"));
        Files.writeString(dir.resolve("settings.json"), "{}");
    }

    private boolean resolve(Boolean override, String defaultTrust, boolean hasUi,
                            ProjectTrustPrompter prompter) {
        return ProjectTrustResolver.resolve(cwd, override, store, defaultTrust,
            firer, hasUi, prompter, warnings::add);
    }

    // ① override 短路
    @Test
    void overrideWinsOverEverything() throws Exception {
        plantResources();
        store.set(cwd, false);
        assertThat(resolve(Boolean.TRUE, "never", false, null)).isTrue();
        assertThat(resolve(Boolean.FALSE, "always", true, (c, o) -> o.getFirst())).isFalse();
    }

    // ② 无敏感资源恒 true
    @Test
    void noResourcesAlwaysTrue() {
        bus.on("ext", PROJECT_TRUST, (e, c) -> new ProjectTrustEventResult(TrustDecision.NO, null));
        assertThat(resolve(null, "never", false, null)).isTrue();
    }

    // ③ 扩展事件：first-wins＋undecided 穿透＋remember 落盘
    @Test
    void extensionVoteFirstWinsAndRememberPersists() throws Exception {
        plantResources();
        bus.on("undecided-ext", PROJECT_TRUST,
            (e, c) -> new ProjectTrustEventResult(TrustDecision.UNDECIDED, null));
        bus.on("voter", PROJECT_TRUST,
            (e, c) -> new ProjectTrustEventResult(TrustDecision.NO, Boolean.TRUE));
        bus.on("later", PROJECT_TRUST,
            (e, c) -> new ProjectTrustEventResult(TrustDecision.YES, null));
        assertThat(resolve(null, "ask", true, (c, o) -> o.getFirst())).isFalse();
        assertThat(store.get(cwd)).isFalse(); // remember=true ⇒ 落盘
    }

    @Test
    void allUndecidedFallsThroughToStore() throws Exception {
        plantResources();
        store.set(cwd, true);
        bus.on("ext", PROJECT_TRUST,
            (e, c) -> new ProjectTrustEventResult(TrustDecision.UNDECIDED, null));
        assertThat(resolve(null, "ask", false, null)).isTrue();
    }

    @Test
    void handlerErrorReportedAndNextHandlerRuns() throws Exception {
        plantResources();
        bus.on("bad", PROJECT_TRUST, (e, c) -> {
            throw new IllegalStateException("boom");
        });
        bus.on("good", PROJECT_TRUST,
            (e, c) -> new ProjectTrustEventResult(TrustDecision.YES, null));
        assertThat(resolve(null, "ask", false, null)).isTrue();
        assertThat(warnings).containsExactly(
            "Extension \"bad\" project_trust error: boom");
    }

    // ④ 已存决策（最近祖先）
    @Test
    void storedAncestorDecisionWinsBeforeDefault() throws Exception {
        plantResources();
        store.set(cwd.getParent(), false);
        assertThat(resolve(null, "always", true, (c, o) -> o.getFirst())).isFalse();
    }

    // ⑤ 默认策略
    @Test
    void defaultAlwaysAndNever() throws Exception {
        plantResources();
        assertThat(resolve(null, "always", false, null)).isTrue();
        assertThat(resolve(null, "never", true, (c, o) -> o.getFirst())).isFalse();
        // 非法值归一 ask（settings-manager.ts:993-994）。
        assertThat(resolve(null, "garbage", false, null)).isFalse();
    }

    // ⑥ 无 UI 恒 false
    @Test
    void askWithoutUiIsFalse() throws Exception {
        plantResources();
        assertThat(resolve(null, "ask", false, null)).isFalse();
    }

    // ⑦ 弹窗：选中才写回；session-only 不落盘；取消 false
    @Test
    void promptSelectionPersists() throws Exception {
        plantResources();
        var chosen = resolve(null, "ask", true, (c, options) -> options.getFirst()); // Trust
        assertThat(chosen).isTrue();
        assertThat(store.get(cwd)).isTrue();
    }

    @Test
    void sessionOnlySelectionNotPersisted() throws Exception {
        plantResources();
        var chosen = resolve(null, "ask", true,
            (c, options) -> options.get(2)); // Trust (this session only)
        assertThat(chosen).isTrue();
        assertThat(store.get(cwd)).isNull();
    }

    @Test
    void cancelledPromptIsFalse() throws Exception {
        plantResources();
        assertThat(resolve(null, "ask", true, (c, o) -> null)).isFalse();
    }

    // 无发射器（--no-extensions）跳过 ③
    @Test
    void nullFirerSkipsExtensionLevel() throws Exception {
        plantResources();
        store.set(cwd, true);
        assertThat(ProjectTrustResolver.resolve(cwd, null, store, "ask",
            null, false, null, warnings::add)).isTrue();
    }
}
