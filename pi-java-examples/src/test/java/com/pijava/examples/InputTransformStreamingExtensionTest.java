package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.StreamingBehavior;
import com.pijava.ai.provider.FauxProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P8：input-transform-streaming——streamingBehavior=steer ⇒ continue（跳过 exec）；
 * 文本命中触发词且 {@code git diff --stat} 有输出 ⇒ transform 拼接；无输出 ⇒ continue。
 */
class InputTransformStreamingExtensionTest {

    @TempDir Path tmp;

    private Path sessionCwd() throws IOException {
        return Files.createDirectories(tmp.resolve("repo"));
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).directory(dir.toFile())
            .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
    }

    /** 种一个「有已跟踪文件改动」的仓库（git diff --stat 有输出）。 */
    private Path dirtyRepo() throws Exception {
        var cwd = sessionCwd();
        git(cwd, "init", "-q");
        Files.writeString(cwd.resolve("tracked.txt"), "v1");
        git(cwd, "add", "tracked.txt");
        git(cwd, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
        Files.writeString(cwd.resolve("tracked.txt"), "v2 changed");
        return cwd;
    }

    private static List<String> userTexts(com.pijava.coding.agent.core.AgentSession session) {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.Message m
                && m.message() instanceof Message.UserMessage)
            .map(e -> ExampleFixtures.textOf(
                ((Message.UserMessage) ((Entry.Message) e).message()).content()))
            .toList();
    }

    @Test
    void triggerWordWithDirtyDiffTransforms() throws Exception {
        var cwd = dirtyRepo();
        try (var scope = ExampleFixtures.scopeExtensions(tmp,
                InputTransformStreamingExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd,
                    ExampleFixtures.textProvider("faux-its", "ok"))) {
                var result = session.processPrompt("show me the changes", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(userTexts(session).get(0))
                    .startsWith("show me the changes\n\nCurrent uncommitted changes:\n```\n")
                    .contains("tracked.txt")
                    .endsWith("```");
            }
        }
    }

    @Test
    void triggerWordWithoutDiffOutputContinues() throws Exception {
        var cwd = sessionCwd(); // 非 git 仓库 ⇒ code != 0 ⇒ continue
        try (var scope = ExampleFixtures.scopeExtensions(tmp,
                InputTransformStreamingExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd,
                    ExampleFixtures.textProvider("faux-its2", "ok"))) {
                var result = session.processPrompt("show me the changes", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(userTexts(session)).containsExactly("show me the changes");
            }
        }
    }

    @Test
    void nonTriggerTextContinues() throws Exception {
        var cwd = dirtyRepo();
        try (var scope = ExampleFixtures.scopeExtensions(tmp,
                InputTransformStreamingExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd,
                    ExampleFixtures.textProvider("faux-its3", "ok"))) {
                var result = session.processPrompt("hello", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(userTexts(session)).containsExactly("hello");
            }
        }
    }

    @Test
    void steerBehaviorSkipsExecAndContinues() throws Exception {
        var cwd = dirtyRepo();
        try (var scope = ExampleFixtures.scopeExtensions(tmp,
                InputTransformStreamingExtension.class)) {
            // 慢速 faux 保持首个 run 窗口（事件间隔 300ms ≈ 1.5s）
            var faux = FauxProvider.sequence("faux-its4",
                List.of(ExampleFixtures.textEvents("one"),
                    ExampleFixtures.textEvents("two")), 300);
            try (var session = ExampleFixtures.newSession(cwd, faux)) {
                var first = session.processPrompt("start", PromptConfig.defaults());
                // 确定性等待 run 起跑（轮询代替固定 sleep，docs/04 规则 7 同族）
                var deadline = System.currentTimeMillis() + 5000;
                while (!session.isRunning() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertThat(session.isRunning()).isTrue();
                var steered = session.processPrompt("show me the changes",
                    PromptConfig.withStreamingBehavior(StreamingBehavior.STEER));
                assertThat(steered.disposition()).isEqualTo("queued");
                first.status();
                steered.status();

                // steer 臂不 transform（跳过 exec）——第二条用户消息原文
                assertThat(userTexts(session)).containsExactly("start", "show me the changes");
            }
        }
    }
}
