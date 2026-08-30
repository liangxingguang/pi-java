package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.RequestContext;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 手动复现：web 链路切模型（opus-4-8 → deepseek-v4-flash）+ 开 thinking
 * （off → minimal）后，请求消息列表是否为空（SDK 报
 * "`messages` is required, but was not set"）。走真实 teamorouter API。
 *
 * 运行：mvn test -pl pi-java-coding-agent -Dtest=ModelSwitchRealReproTest
 */
class ModelSwitchRealReproTest {

    @TempDir
    Path tmp;

    @Test
    void switchModelThenThinkingKeepsUserMessage() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "anthropic", "--model", "claude-opus-4-8",
            "--no-session", "--no-skills", "--no-extensions"});
        var providers = ProviderRegistry.create();
        providers.loadBuiltinProviders();

        try (var session = AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()))) {
            var harness = session.harness();

            var captured = new CopyOnWriteArrayList<List<Message>>();
            try (var ignored = harness.hookSystem().onBeforeRequest("default",
                    (RequestContext ctx) -> {
                        captured.add(ctx.messages());
                    })) {
                // 第 1 轮：opus-4-8，thinking off（与用户 web 操作一致）
                var r1 = session.processPrompt("1+1=? 只回答数字", PromptConfig.defaults());
                r1.statusFuture().get();
                System.out.println("[repro] round1 stopReason=" + r1.statusFuture().get());
                System.out.println("[repro] round1 messages sent="
                    + describe(captured.get(captured.size() - 1)));

                // 切换模型 + thinking level（对齐 web setModel/setThinkingLevel）
                harness.setModel(ModelId.of("anthropic", "deepseek-v4-flash"));
                harness.setThinkingLevel(
                    com.pijava.ai.thinking.ModelThinkingLevel.of(
                        new com.pijava.ai.thinking.ThinkingLevel.Minimal()));

                // 第 2 轮：deepseek-v4-flash + minimal
                var r2 = session.processPrompt("2+2=? 只回答数字", PromptConfig.defaults());
                r2.statusFuture().get();
                System.out.println("[repro] round2 stopReason=" + r2.statusFuture().get());
                System.out.println("[repro] round2 messages sent="
                    + describe(captured.get(captured.size() - 1)));
            }

            var transcript = harness.snapshot("default").transcript();
            System.out.println("[repro] transcript after round2:");
            for (var e : transcript) {
                System.out.println("  - " + e.type() + " id=" + e.id().substring(0, 8)
                    + " parent=" + (e.parentId() == null ? "null" : e.parentId().substring(0, 8)));
            }
            // 核心断言：thinking 开启后的请求必须包含 user 消息
            org.assertj.core.api.Assertions.assertThat(
                    captured.get(captured.size() - 1))
                .anyMatch(m -> m instanceof Message.UserMessage);
        }
    }

    private static String describe(List<Message> messages) {
        var sb = new StringBuilder("[");
        for (var m : messages) {
            if (m instanceof Message.SystemMessage) sb.append("system,");
            else if (m instanceof Message.UserMessage) sb.append("user,");
            else if (m instanceof Message.AssistantMessage) sb.append("assistant,");
            else if (m instanceof Message.ToolResultMessage) sb.append("tool,");
        }
        return sb.append("]").toString();
    }
}
