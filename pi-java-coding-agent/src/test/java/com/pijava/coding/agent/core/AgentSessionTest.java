package com.pijava.coding.agent.core;

import java.nio.file.Path;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 review fixes: session-arg resolution, model-pattern thinking,
 * append-system-prompt and --no-builtin-tools.
 */
class AgentSessionTest {

    /**
     * 夹具会话落盘到临时目录（docs/39 裁决 B）。
     *
     * <p>不带 {@code --session-dir} 的用例会走持久路径，在开发者**真实 home** 的
     * {@code --<模块目录>--} 下每次跑测试留一个会话文件 —— A12 自放大回路的输入端。</p>
     */
    @TempDir
    Path sessionDir;

    @Test
    void continueInFreshProcessFailsClearly() {
        assertThatThrownBy(() -> AgentSession.create(
            ArgsParser.parse(new String[] {"-c"}),
            InMemorySessionRepository.create()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No previous session");
    }

    @Test
    void noSessionDoesNotRegister() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--no-session"}),
                InMemorySessionRepository.create())) {
            assertThat(session.listSessions()).isEmpty();
        }
    }

    @Test
    void sessionIdCreatesWhenMissing() {
        var repo = InMemorySessionRepository.create();
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-id", "proj-123"}),
                repo)) {
            assertThat(repo.find("proj-123")).contains(session);
            assertThat(repo.list()).hasSize(1);
        }
    }

    @Test
    void sessionIdReusesExistingExactId() {
        var repo = InMemorySessionRepository.create();
        try (var first = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-id", "proj-123"}),
                repo)) {
            var second = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-id", "proj-123"}),
                repo);
            assertThat(repo.find("proj-123")).contains(first);
            assertThat(repo.list()).hasSize(1);
        }
    }

    @Test
    void modelPatternThinkingSuffixSetsThinkingLevel() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {
                    "--session-dir", sessionDir.toString(),
                    "--model", "anthropic/claude-sonnet-4-6:high"}))) {
            assertThat(session.harness().getThinkingLevel())
                .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.High()));
        }
    }

    @Test
    void explicitThinkingFlagWinsOverModelSuffix() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {
                    "--session-dir", sessionDir.toString(),
                    "--model", "anthropic/claude-sonnet-4-6:high",
                    "--thinking", "low"}))) {
            assertThat(session.harness().getThinkingLevel())
                .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.Low()));
        }
    }

    /**
     * 包 A4b：{@code --system-prompt} 与 {@code --append-system-prompt} 现在落在**两个槽**里
     * —— 前者是 pi 的 {@code customPrompt}（替换默认 preamble 并抑制 tools/rules/docs 三段），
     * 后者是 pi 的 {@code appendSystemPrompt}（渲染成 {@code addendum} 段）。
     *
     * <p>本条包之前两者被拼成**一个**基础串，所以这里原本断言的是拼接结果。</p>
     */
    @Test
    void appendSystemPromptIsSentAsTheAddendumSection() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {
                    "--session-dir", sessionDir.toString(),
                    "--system-prompt", "base",
                    "--append-system-prompt", "extra-one",
                    "--append-system-prompt", "extra-two"}))) {
            assertThat(session.harness().getSystemPrompt()).isEqualTo("base");
            assertThat(session.harness().getAppendSystemPrompt()).isEqualTo("extra-one\n\nextra-two");
        }
    }

    /** 没给 {@code --system-prompt} ⇒ 空串 ＝ 走默认 preamble（不再是原来那份常量）。 */
    @Test
    void withoutAnExplicitPromptTheCustomPromptIsEmpty() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}))) {
            assertThat(session.harness().getSystemPrompt()).isEmpty();
            assertThat(session.harness().getAppendSystemPrompt()).isEmpty();
            assertThat(session.harness().getPromptGuidelines()).isNotEmpty();
        }
    }

    @Test
    void noBuiltinToolsDisablesAllTools() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString(),
                    "--no-builtin-tools"}))) {
            assertThat(session.harness().getActiveTools()).isEmpty();
        }
    }
}
