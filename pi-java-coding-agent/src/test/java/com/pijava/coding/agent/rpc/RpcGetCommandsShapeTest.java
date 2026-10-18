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
 * B247（docs/13 §3.6）：RPC {@code get_commands} 形状对齐 —— pi
 * {@code rpc-mode.ts:679-710}／{@code rpc-types.ts:77-89,241}。
 * 三点线格式钉死：① {@code data} 包 {@code {commands}} 键；② 内置不在其中；
 * ③ 条目 {@code (name, description?, source, sourceInfo)}、无 argumentHint。
 * （前身为批 2 的 {@code RpcGetCommandsSkillTest}——彼时钉的是 java 自造
 * 形状，本批随对齐整体改写。）
 */
class RpcGetCommandsShapeTest {

    @Test
    void dataWrapsCommandsKey() throws Exception {
        try (var session = createSession(null)) {
            var payload = getCommands(session);

            // pi rpc-types.ts:241：data = { commands: [...] }（批 2 偏差②回本）。
            assertThat(payload.isObject()).as("data 是对象").isTrue();
            assertThat(payload.get("commands").isArray())
                .as("data.commands 是数组").isTrue();
        }
    }

    @Test
    void builtinsAreNotListed() throws Exception {
        try (var session = createSession(null)) {
            session.harness().skillManager().register(
                skill("demo-skill", "Demo skill", SkillSource.PROJECT));

            var names = commandNames(getCommands(session).get("commands"));

            // pi 语义＝可经 prompt 调用（rpc-types.ts:73-74），内置 TUI 硬编码不收。
            assertThat(names).doesNotContain("help", "model", "quit");
            assertThat(names).contains("skill:demo-skill");
        }
    }

    @Test
    void promptArmCarriesSourceAndSourceInfo() throws Exception {
        // 全局 prompts 目录预置模板 ⇒ loadDefaults 打 user 标（B247 §3.2）。
        var templateBody = "---\ndescription: Review the change\n---\nReview $1";
        try (var session = createSession(templateBody)) {
            var entry = findCommand(getCommands(session).get("commands"), "review-change");

            assertThat(entry.get("source").asText()).isEqualTo("prompt");
            assertThat(entry.get("description").asText()).isEqualTo("Review the change");
            var info = entry.get("sourceInfo");
            assertThat(info.get("scope").asText()).isEqualTo("user");
            assertThat(info.get("source").asText()).isEqualTo("local");
            assertThat(info.get("origin").asText()).isEqualTo("top-level");
            assertThat(info.get("path").asText()).endsWith("review-change.md");
            assertThat(info.get("baseDir").asText()).endsWith("prompts");
        }
    }

    @Test
    void skillArmCarriesSourceAndSourceInfo() throws Exception {
        try (var session = createSession(null)) {
            session.harness().skillManager().register(
                skill("demo-skill", "Demo skill", SkillSource.EXPLICIT));

            var entry = findCommand(
                getCommands(session).get("commands"), "skill:demo-skill");

            assertThat(entry.get("source").asText()).isEqualTo("skill");
            assertThat(entry.get("description").asText()).isEqualTo("Demo skill");
            var info = entry.get("sourceInfo");
            // pi skills.ts:150-154：显式路径 scope 缺省落 temporary。
            assertThat(info.get("scope").asText()).isEqualTo("temporary");
            assertThat(info.get("source").asText()).isEqualTo("local");
            assertThat(info.get("origin").asText()).isEqualTo("top-level");
        }
    }

    @Test
    void noArgumentHintAnywhereAndEmptySessionStillWraps() throws Exception {
        try (var session = createSession(null)) {
            var commands = getCommands(session).get("commands");

            assertThat(commands).isEmpty();
            // 注册一条技能再查：任意条目都无 argumentHint 键（pi 形状无此字段）。
            session.harness().skillManager().register(
                skill("demo-skill", "Demo skill", SkillSource.USER));
            for (var entry : getCommands(session).get("commands")) {
                assertThat(entry.has("argumentHint"))
                    .as("条目无 argumentHint 键").isFalse();
            }
        }
    }

    /** 发 {@code get_commands} 并返回 {@code data} 载荷。 */
    private static JsonNode getCommands(AgentSession session) throws Exception {
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(session, new JsonlWriter(out),
            ArgsParser.parse(new String[0]));
        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"get_commands\"}");
        return payloadOf(out.toString(StandardCharsets.UTF_8), "1");
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

    /**
     * 隔离 user.home 的 {@code --no-session} 夹具会话；{@code globalTemplate}
     * 非 null 时预置 {@code <home>/.pi-java/agent/prompts/review-change.md}。
     */
    private static AgentSession createSession(String globalTemplate) throws Exception {
        var cwd = Files.createTempDirectory("pi-java-rpc-shape-cwd");
        var home = Files.createTempDirectory("pi-java-rpc-shape-home");
        if (globalTemplate != null) {
            var prompts = home.resolve(Path.of(".pi-java", "agent", "prompts"));
            Files.createDirectories(prompts);
            Files.writeString(prompts.resolve("review-change.md"), globalTemplate);
        }
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

    private static Skill skill(String name, String description, SkillSource source) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return description; }
            @Override public String systemPrompt() { return ""; }
            @Override public SkillSource sourceInfo() { return source; }
        };
    }
}
