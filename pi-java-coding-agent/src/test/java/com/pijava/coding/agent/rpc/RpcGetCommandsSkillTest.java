package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.skill.Skill;
import com.pijava.agent.skill.SkillSource;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B244（docs/12 §3.6）：RPC {@code get_commands} 的 skill 臂 —— pi
 * {@code rpc-mode.ts:699-707}。java 现形状（内置条目保留＋
 * {@code (name, description, argumentHint)}）为扩面，钉死在此；形状对齐
 * （source/sourceInfo、模板臂）随 B247。
 */
class RpcGetCommandsSkillTest {

    @Test
    void skillAppearsInGetCommandsPayload() throws Exception {
        try (var session = createSession()) {
            session.harness().skillManager().register(
                skill("demo-skill", "Demo skill"));

            var payload = getCommands(session);

            var names = commandNames(payload);
            assertThat(names).contains("skill:demo-skill");
            var entry = findCommand(payload, "skill:demo-skill");
            assertThat(entry.get("description").asText()).isEqualTo("Demo skill");
            assertThat(!entry.has("argumentHint") || entry.get("argumentHint").isNull())
                .as("skill 条目 argumentHint 恒 null").isTrue();
        }
    }

    @Test
    void builtinCommandsAreRetainedAlongsideSkills() throws Exception {
        try (var session = createSession()) {
            session.harness().skillManager().register(
                skill("demo-skill", "Demo skill"));

            var payload = getCommands(session);

            // java 扩面（pi 不收内置——形状差随 B247）：内置与 skill 条目并存。
            assertThat(commandNames(payload)).contains("help", "skill:demo-skill");
        }
    }

    @Test
    void noSkillsNoSkillEntries() throws Exception {
        try (var session = createSession()) {
            var payload = getCommands(session);

            assertThat(commandNames(payload))
                .noneMatch(name -> name.startsWith("skill:"));
        }
    }

    /** 发 {@code get_commands} 并返回载荷（data 直接是命令数组）。 */
    private static JsonNode getCommands(AgentSession session) throws Exception {
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(session, new JsonlWriter(out),
            ArgsParser.parse(new String[0]));
        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"get_commands\"}");
        var payload = payloadOf(out.toString(StandardCharsets.UTF_8), "1");
        assertThat(payload != null && payload.isArray())
            .as("get_commands 的 data 直接是命令数组").isTrue();
        return payload;
    }

    private static java.util.List<String> commandNames(JsonNode commands) {
        var names = new ArrayList<String>();
        commands.forEach(c -> names.add(c.get("name").asText()));
        return names;
    }

    private static JsonNode findCommand(JsonNode commands, String name) {
        for (var c : commands) {
            if (name.equals(c.get("name").asText())) {
                return c;
            }
        }
        throw new AssertionError("get_commands 无条目 " + name);
    }

    /** 与 {@code RpcDispatcherTest.payloadOf} 同法。 */
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

    /** 隔离 user.home 的 {@code --no-session} 夹具会话。 */
    private static AgentSession createSession() throws Exception {
        var cwd = Files.createTempDirectory("pi-java-rpc-skill-cwd");
        var home = Files.createTempDirectory("pi-java-rpc-skill-home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var args = ArgsParser.parse(new String[] {"--no-session"});
            return AgentSession.create(args, ProviderRegistry.create(),
                new ToolContext(cwd.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
        } finally {
            System.setProperty("user.home", savedHome);
        }
    }

    private static Skill skill(String name, String description) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return description; }
            @Override public String systemPrompt() { return ""; }
            @Override public SkillSource sourceInfo() { return SkillSource.PROJECT; }
        };
    }
}
