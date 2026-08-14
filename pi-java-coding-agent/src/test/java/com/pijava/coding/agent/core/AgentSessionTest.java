package com.pijava.coding.agent.core;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 review fixes: session-arg resolution, model-pattern thinking,
 * append-system-prompt and --no-builtin-tools.
 */
class AgentSessionTest {

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
                    "--model", "anthropic/claude-sonnet-4-6:high"}))) {
            assertThat(session.harness().getThinkingLevel())
                .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.High()));
        }
    }

    @Test
    void explicitThinkingFlagWinsOverModelSuffix() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {
                    "--model", "anthropic/claude-sonnet-4-6:high",
                    "--thinking", "low"}))) {
            assertThat(session.harness().getThinkingLevel())
                .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.Low()));
        }
    }

    @Test
    void appendSystemPromptIsJoined() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {
                    "--system-prompt", "base",
                    "--append-system-prompt", "extra-one",
                    "--append-system-prompt", "extra-two"}))) {
            assertThat(session.harness().getSystemPrompt())
                .isEqualTo("base\n\nextra-one\n\nextra-two");
        }
    }

    @Test
    void noBuiltinToolsDisablesAllTools() {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--no-builtin-tools"}))) {
            assertThat(session.harness().getActiveTools()).isEmpty();
        }
    }
}
