package com.pijava.evals;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * 测试用示例扩展 —— 注册 EchoTool + /hello 命令 + FauxProvider + SampleSkill，
 * 供 P6-4 ExtensionLifecycleTest 验证 ServiceLoader 装配。
 */
public final class SampleExtension implements PiExtension {

    @Override
    public String name() {
        return "sample-evals-ext";
    }

    @Override
    public String description() {
        return "Sample extension for evals lifecycle tests";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(echoTool());
        ctx.slashCommands().register(helloCommand());
        ctx.providers().register(FauxProvider.text("sample-provider"));
        ctx.skills().register(sampleSkill());
    }

    private static AgentTool<Map<String, Object>, Void> echoTool() {
        return new AgentTool<>() {
            @Override public String name() { return "echo"; }
            @Override public String label() { return "echo"; }
            @Override public String description() { return "Echo the input text."; }
            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Sequential();
            }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object",
                    "properties", Map.of("text", Map.of("type", "string")));
            }

            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                    com.pijava.ai.AbortSignal signal,
                    com.pijava.agent.tool.ToolUpdateCallback<Void> onUpdate,
                    com.pijava.agent.tool.ToolContext context) {
                Object text = params.get("text");
                return ToolResult.success(text == null ? "" : text.toString());
            }
        };
    }

    private static SlashCommand helloCommand() {
        return new SlashCommand() {
            @Override public String name() { return "hello"; }
            @Override public String description() { return "Say hello."; }
            @Override public String argumentHint() { return ""; }

            @Override
            public CompletionStage<String> execute(String args, SlashContext context) {
                return CompletableFuture.completedFuture("hello from extension");
            }
        };
    }

    private static Skill sampleSkill() {
        return new Skill() {
            @Override public String name() { return "sample-skill"; }
            @Override public String label() { return "Sample"; }
            @Override public String description() { return "A sample skill."; }
            @Override public String systemPrompt() { return "Sample skill body."; }
        };
    }
}
