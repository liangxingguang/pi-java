package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.core.source.SourceInfo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 23（docs/34 T4，B221⑥＋销批 3 窄差 D5）：RPC {@code get_commands}
 * <b>扩展臂</b>——pi rpc-mode.ts:684-690 对位：第一源序（扩展→模板→技能）、
 * name 用 invocationName（撞名 {@code :N} 编号）、source "extension"、
 * 每条携带注册期盖章的 sourceInfo。
 */
class RpcGetCommandsExtensionArmTest {

    private static SlashCommand cmd(String name, String description) {
        return new SlashCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String argumentHint() {
                return "";
            }

            @Override
            public java.util.concurrent.CompletableFuture<String> execute(
                    String args, SlashContext context) {
                return CompletableFuture.completedFuture("ran");
            }
        };
    }

    @Test
    void extensionArmFirstWithSourceInfoAndNumbering() throws Exception {
        var cwd = Files.createTempDirectory("pi-java-rpc-ext-cwd");
        var home = Files.createTempDirectory("pi-java-rpc-ext-home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try (var session = AgentSession.create(ArgsParser.parse(new String[] {"--no-session"}),
                ProviderRegistry.create(),
                new ToolContext(cwd.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()))) {
            // 模拟两个扩展的注册（生产路径＝per-extension 上下文的盖章视图）。
            var stampA = new SourceInfo("builtin:ext-a", "builtin",
                SourceInfo.Scope.TEMPORARY, SourceInfo.Origin.TOP_LEVEL, null);
            var stampB = new SourceInfo("/ext/b.jar", "local",
                SourceInfo.Scope.TEMPORARY, SourceInfo.Origin.TOP_LEVEL, null);
            session.services().slashCommands()
                .extensionView("builtin:ext-a", stampA).register(cmd("hello", "from a"));
            var stampC = new SourceInfo("/ext/c.jar", "local",
                SourceInfo.Scope.TEMPORARY, SourceInfo.Origin.TOP_LEVEL, null);
            session.services().slashCommands()
                .extensionView("/ext/b.jar", stampB).register(cmd("dup", "from b"));
            session.services().slashCommands()
                .extensionView("/ext/c.jar", stampC).register(cmd("dup", "from c"));

            var out = new ByteArrayOutputStream();
            new RpcDispatcher(session, new JsonlWriter(out),
                ArgsParser.parse(new String[0]))
                .handleLine("{\"id\":\"1\",\"type\":\"get_commands\"}");
            var commands = payloadOf(out.toString(StandardCharsets.UTF_8), "1")
                .get("commands");

            // 第一源序：扩展臂在最前（模板/技能空 ⇒ 全是扩展条目）。
            assertThat(commands).hasSize(3);
            assertThat(commands.get(0).get("name").asText()).isEqualTo("hello");
            assertThat(commands.get(0).get("source").asText()).isEqualTo("extension");
            assertThat(commands.get(0).get("sourceInfo").get("source").asText())
                .isEqualTo("builtin");
            // 撞名编号：跨扩展同名 ⇒ dup:1/dup:2（runner.ts:805-840 逐字）。
            assertThat(commands.get(1).get("name").asText()).isEqualTo("dup:1");
            assertThat(commands.get(1).get("description").asText()).isEqualTo("from b");
            assertThat(commands.get(2).get("name").asText()).isEqualTo("dup:2");
            assertThat(commands.get(2).get("sourceInfo").get("path").asText())
                .isEqualTo("/ext/c.jar");
        } finally {
            System.setProperty("user.home", savedHome);
        }
    }

    /** 与 {@code RpcGetCommandsShapeTest.payloadOf} 同法。 */
    private static JsonNode payloadOf(String output, String id) throws Exception {
        var mapper = new ObjectMapper();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            var node = mapper.readTree(line);
            var type = node.get("type");
            var nodeId = node.get("id");
            if (type != null && "response".equals(type.asText())
                    && nodeId != null && id.equals(nodeId.asText())) {
                return node.get("data");
            }
        }
        throw new AssertionError("no response payload for id=" + id + " in:\n" + output);
    }
}
