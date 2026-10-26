package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B236（docs/11 §4.1）：移植 pi {@code default-tools-setting.test.ts} 可达子集 ——
 * §3.6 对账表 12 行全走真装配（{@code AgentSession.create}＋内存 SettingsManager）。
 *
 * <p>观察面：注册面 {@code services().tools().all()}（pi {@code getAllTools()}）与
 * 激活面 {@code harness().getActiveTools()}（pi {@code getActiveToolNames()}）。
 * java 的系统提示工具段由 agent-core 在请求期从激活装配构建 ⇒ 激活面即提示侧输入，
 * 不另断 pi 的 {@code "- grep:"} 字面（设计 §4.1 口径）。</p>
 *
 * <p>不可移植（登记，不进夹具）：reload 两臂（裁决④/B246）；扩展工具/SDK customTools 行
 * （无扩展面）；{@code _isActivatable} 的 tool_search 臂（R5）。</p>
 */
class DefaultToolsSelectionTest {

    private static final List<String> PI_EIGHT = List.of(
        "bash", "edit", "find", "grep", "ls", "powershell", "read", "write");
    private static final List<String> PI_FOUR = List.of("read", "bash", "edit", "write");

    @TempDir Path tmp;

    private static List<StreamEvent> reply(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    /** 真装配：CLI 旗标＋可选 settings defaultTools（global 层）。 */
    private AgentSession rig(String tag, String[] cliArgs, List<String> defaultTools)
            throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(tag));
        var full = new ArrayList<>(List.of(cliArgs));
        full.addAll(List.of("--session-dir", dir.toString(),
            "--provider", "faux-tools", "--model", "hello"));
        var args = ArgsParser.parse(full.toArray(String[]::new));
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-tools", List.of(reply("r"))));
        var storage = new InMemorySettingsStorage();
        if (defaultTools != null) {
            var global = new Settings();
            // 批 24（D3）：字段为原始 JsonNode 承载
            global.defaultTools = Json.mapper().valueToTree(defaultTools);
            storage.writeGlobal(global);
        }
        var handle = PersistentSessionRepositories.jsonl(dir);
        return AgentSession.create(args, handle, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(storage));
    }

    private static List<String> registeredNames(AgentSession session) {
        return session.services().tools().all().stream()
            .map(AgentTool::name).sorted().toList();
    }

    private static List<String> activeNames(AgentSession session) {
        return session.harness().getActiveTools().stream()
            .map(AgentTool::name).sorted().toList();
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted().toList();
    }

    // ── §3.6 对账表 12 行 ─────────────────────────────────────────

    @Test
    void bareRunRegistersEightAndActivatesDefaultFour() throws Exception {
        // 行 1（pi default-tools-setting.test.ts:241 实证）。
        try (var session = rig("bare", new String[] {}, null)) {
            assertThat(registeredNames(session)).isEqualTo(PI_EIGHT);
            assertThat(activeNames(session)).isEqualTo(sorted(PI_FOUR));
        }
    }

    @Test
    void defaultToolsSettingReplacesTheActiveSelection() throws Exception {
        // 行 2（pi "uses the configured list as the initial built-in selection"）。
        try (var session = rig("cfg", new String[] {}, List.of("grep", "find"))) {
            assertThat(registeredNames(session)).isEqualTo(PI_EIGHT);
            assertThat(activeNames(session)).containsExactly("find", "grep");
        }
    }

    @Test
    void defaultToolsModifierAddsPowerShell() throws Exception {
        // 行 3：settings ["+powershell"] ⇒ 默认四件＋powershell。
        try (var session = rig("cfgmod", new String[] {}, List.of("+powershell"))) {
            assertThat(registeredNames(session)).isEqualTo(PI_EIGHT);
            assertThat(activeNames(session))
                .containsExactly("bash", "edit", "powershell", "read", "write");
        }
    }

    @Test
    void toolsModifierStacksOnDefaults() throws Exception {
        // 行 4：--tools +powershell（pi "applies +name and -name tool options"）。
        try (var session = rig("mod", new String[] {"--tools", "+powershell"}, null)) {
            assertThat(registeredNames(session)).isEqualTo(PI_EIGHT);
            assertThat(activeNames(session))
                .containsExactly("bash", "edit", "powershell", "read", "write");
        }
    }

    @Test
    void toolsAllowlistNarrowsTheRegistry() throws Exception {
        // 行 5：--tools read,grep ⇒ 注册面只留点名者。
        try (var session = rig("allow", new String[] {"--tools", "read,grep"}, null)) {
            assertThat(registeredNames(session)).containsExactly("grep", "read");
            assertThat(activeNames(session)).containsExactly("grep", "read");
        }
    }

    @Test
    void cliToolsOptionWinsOverSettingsDefaultTools() throws Exception {
        // pi "preserves explicit tool option precedence"：allowlist 名单整个盖过
        // settings 默认选择（sdk.ts:286 的 selectedToolNames = options.tools）。
        try (var session = rig("wins", new String[] {"--tools", "read"},
                List.of("grep", "find"))) {
            assertThat(activeNames(session)).containsExactly("read");
        }
    }

    @Test
    void toolsPatternMatchesAndActivates() throws Exception {
        // 行 6：--tools 'gr*' ⇒ grep 注册并激活（模式点名激活，含默认不激活者）。
        try (var session = rig("pat", new String[] {"--tools", "gr*"}, null)) {
            assertThat(registeredNames(session)).containsExactly("grep");
            assertThat(activeNames(session)).containsExactly("grep");
        }
    }

    @Test
    void excludedWinsOverAllowlisted() throws Exception {
        // 行 7：--tools read --exclude-tools read ⇒ 注册面空。
        try (var session = rig("excl", new String[] {
                "--tools", "read", "--exclude-tools", "read"}, null)) {
            assertThat(registeredNames(session)).isEmpty();
            assertThat(activeNames(session)).isEmpty();
        }
    }

    @Test
    void excludeToolsDenylistKeepsRest() throws Exception {
        // 行 8：--exclude-tools bash ⇒ 注册七件，激活 read/edit/write。
        try (var session = rig("deny", new String[] {"--exclude-tools", "bash"}, null)) {
            assertThat(registeredNames(session))
                .containsExactly("edit", "find", "grep", "ls", "powershell", "read", "write");
            assertThat(activeNames(session)).containsExactly("edit", "read", "write");
        }
    }

    @Test
    void noToolsEmptiesTheRegistry() throws Exception {
        // 行 9（pi "preserves explicit tool option precedence" 末断言：
        // getAllTools()==[] / getActiveToolNames()==[]）。
        try (var session = rig("none", new String[] {"--no-tools"}, null)) {
            assertThat(registeredNames(session)).isEmpty();
            assertThat(activeNames(session)).isEmpty();
        }
    }

    @Test
    void noBuiltinToolsKeepsRegistryButActivatesNothing() throws Exception {
        // 行 10：--no-builtin-tools ⇒ 注册面保留，激活面空。
        try (var session = rig("nobin", new String[] {"--no-builtin-tools"}, null)) {
            assertThat(registeredNames(session)).isEqualTo(PI_EIGHT);
            assertThat(activeNames(session)).isEmpty();
        }
    }

    @Test
    void blankToolsValueEmptiesTheRegistry() throws Exception {
        // 行 11：--tools "" ⇒ 空 allowlist，零工具（pi 的 [] 非 undefined）。
        try (var session = rig("blank", new String[] {"--tools", ""}, null)) {
            assertThat(registeredNames(session)).isEmpty();
            assertThat(activeNames(session)).isEmpty();
        }
    }

    @Test
    void noToolsPlusModifierActivatesTheNamedTool() throws Exception {
        // 行 12（pi "applies +name and -name tool options" 末断言：noTools:"all" 叠
        // +name ⇒ 零基础上点名激活，sdk.ts:287-290 例外臂）。
        try (var session = rig("ex", new String[] {
                "--no-tools", "--tools", "+grep"}, null)) {
            assertThat(registeredNames(session)).containsExactly("grep");
            assertThat(activeNames(session)).containsExactly("grep");
        }
    }
}
