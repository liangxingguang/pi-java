package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.ToolReference;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B271（docs/24 W1）：pi {@code _restoreToolsFromTranscript}
 * （{@code agent-session.ts:538,1851-1882}）——恢复会话时按分支**全部** system
 * 消息重放工具装配，再叠 {@code +name/-name}，未被提及的扩展工具按注册即激活。
 */
class RecoverToolsOnResumeTest {

    @TempDir Path tmp;

    private static String[] args(Path dir, String... extra) {
        var all = new java.util.ArrayList<String>();
        all.add("--session-dir");
        all.add(dir.toString());
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    private static String[] resumeArgs(Path dir, String... extra) {
        var all = new java.util.ArrayList<String>();
        all.add("--session-dir");
        all.add(dir.toString());
        all.add("-c");
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    private AgentSession create(Args args) {
        var handle = PersistentSessionRepositories.jsonl(Path.of(args.sessionDir()));
        return AgentSession.create(args, handle, ProviderRegistry.create(),
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
    }

    /** 注入一条带 toolsAdded/toolsRemoved 的系统消息并落盘。 */
    private static void systemMessage(AgentSession session, String id,
                                      List<ToolDefinition> added,
                                      List<ToolReference> removed) {
        session.harness().appendLaneEntry(session.laneName(), new Entry.Message(
            id, 0, null, Instant.now(),
            new Message.SystemMessage(List.of(), Instant.now(), Map.of(), added, removed), null));
        SessionPersistence.persistPending(session, session.session(), session.laneName());
    }

    private static List<ToolReference> removed(String name) {
        return List.of(new ToolReference(name));
    }

    private static void userMessage(AgentSession session, String id) {
        session.harness().appendLaneEntry(session.laneName(), new Entry.Message(
            id, 0, null, Instant.now(),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hello"))), null));
        SessionPersistence.persistPending(session, session.session(), session.laneName());
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, name + " tool", Map.of());
    }

    private static List<String> active(AgentSession session) {
        return session.harness().getActiveTools().stream()
            .map(AgentTool::name).sorted().toList();
    }

    @Test
    void restoreUsesAllSystemMessagesNotJustTheLatest() throws Exception {
        Path dir = Files.createTempDirectory("pi-resume-tools");
        try (var session = create(ArgsParser.parse(args(dir)))) {
            systemMessage(session, "s1", List.of(tool("read"), tool("grep")), List.of());
            userMessage(session, "u1");
            systemMessage(session, "s2", List.of(tool("edit")), List.of());
        }

        try (var resumed = create(ArgsParser.parse(resumeArgs(dir)))) {
            assertThat(active(resumed))
                .as("历史 toolsAdded 全部重放，叠加后是 read/grep/edit")
                .containsExactly("edit", "grep", "read");
        }
    }

    @Test
    void restoreAppliesToolsRemoved() throws Exception {
        Path dir = Files.createTempDirectory("pi-resume-tools-removed");
        try (var session = create(ArgsParser.parse(args(dir)))) {
            userMessage(session, "u0");
            systemMessage(session, "s1", List.of(tool("read")), List.of());
            systemMessage(session, "s2", List.of(), removed("read"));
        }

        try (var resumed = create(ArgsParser.parse(resumeArgs(dir)))) {
            assertThat(active(resumed))
                .as("被移除的工具保持未激活")
                .doesNotContain("read");
        }
    }

    @Test
    void restoreAppliesCommandLineToolModifiers() throws Exception {
        Path dir = Files.createTempDirectory("pi-resume-tools-modifiers");
        try (var session = create(ArgsParser.parse(args(dir, "--tools", "+grep,-read")))) {
            userMessage(session, "u0");
            systemMessage(session, "s1", List.of(tool("read")), List.of());
        }

        try (var resumed = create(ArgsParser.parse(
                resumeArgs(dir, "--tools", "+grep,-read")))) {
            assertThat(active(resumed))
                .as("恢复结果再叠 +grep/-read")
                .containsExactly("grep");
        }
    }

    @Test
    void explicitToolListSkipsRestore() throws Exception {
        Path dir = Files.createTempDirectory("pi-resume-tools-explicit");
        try (var session = create(ArgsParser.parse(args(dir, "--tools", "read")))) {
            userMessage(session, "u0");
            systemMessage(session, "s1", List.of(tool("grep")), List.of());
        }

        try (var resumed = create(ArgsParser.parse(resumeArgs(dir, "--tools", "read")))) {
            assertThat(active(resumed))
                .as("usesDefaultTools=false ⇒ 恢复臂整臂跳过")
                .containsExactly("read");
        }
    }

    @Test
    void unregisteredRecordedToolsStayPending() throws Exception {
        Path dir = Files.createTempDirectory("pi-resume-tools-pending");
        try (var session = create(ArgsParser.parse(args(dir)))) {
            userMessage(session, "u0");
            systemMessage(session, "s1",
                List.of(tool("read"), tool("mcp__late")), List.of());
        }

        try (var resumed = create(ArgsParser.parse(resumeArgs(dir)))) {
            assertThat(active(resumed))
                .as("未注册工具不进激活集")
                .containsExactly("read");
            assertThat(resumed.harness().pendingToolNames())
                .as("未注册名进 pending，注册后激活")
                .contains("mcp__late");
        }
    }

    @Test
    void noSystemMessagesKeepCurrentTools() throws Exception {
        Path dir = Files.createTempDirectory("pi-resume-tools-bare");
        try (var session = create(ArgsParser.parse(args(dir)))) {
            userMessage(session, "u1");
        }

        try (var resumed = create(ArgsParser.parse(resumeArgs(dir)))) {
            assertThat(active(resumed))
                .as("无系统消息 ⇒ 保持 CLI 装配的默认工具")
                .isNotEmpty();
        }
    }
}
