package com.pijava.coding.agent.extension.mcp;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.extension.mcp.McpManagerUi.McpMenu;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 24（docs/35 §6）：/mcp 管理循环端到端（map/07 #85 载荷＋#86 管理动作）——
 * 脚本化 {@link McpManagerUi} 直驱 {@code manage} 双循环：菜单模型逐字、
 * disable/enable 写回 mcp.json、项目 override 臂、exposure 改档重注册。
 */
class McpManagerFlowTest {

    @TempDir
    Path agentDir;

    @TempDir
    Path cwd;

    /** 脚本化答案队列的 McpManagerUi（menu 按序吐答案；null 收尾退出循环）。 */
    private static final class ScriptedUi implements McpManagerUi {
        // LinkedList 容 null 答案（取消/返回臂）；ArrayDeque 不收 null
        final java.util.LinkedList<String> answers = new java.util.LinkedList<>();
        final java.util.List<McpMenu> seenMenus = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<String> menu(Supplier<McpMenu> build,
                                              Function<Runnable, Runnable> subscribe) {
            seenMenus.add(build.get());
            return CompletableFuture.completedFuture(answers.poll());
        }

        @Override
        public void status(String title, String message, Runnable onCancel) {
        }

        @Override
        public CompletableFuture<String> redirectUrl(String title, String authorizationUrl,
                                                     AbortSignal signal) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private McpExtensionTestRig rigWithServer(String name) throws Exception {
        Files.writeString(agentDir.resolve("mcp.json"),
            "{\"mcpServers\":{\"" + name + "\":{\"command\":\"run-x\"}}}");
        var rig = McpExtensionTestRig.create(agentDir, cwd, true, null);
        rig.startSession();
        return rig;
    }

    @Test
    void disableWritesBackToDefiningMcpJson() throws Exception {
        var rig = rigWithServer("docs");
        var ui = new ScriptedUi();
        // 服务器菜单选 docs ⇒ 动作菜单选 disable ⇒ 返回 ⇒ 关闭
        ui.answers.addAll(List.of("docs", "disable"));
        ui.answers.add(null);
        ui.answers.add(null);
        rig.ext.actions().manage(ui);
        // disable 走 runInBackground（异步）⇒ 轮询落盘
        var written = awaitFile(agentDir.resolve("mcp.json"), "\"enabled\": false");
        assertThat(written).contains("\"enabled\": false");
        // 服务器菜单的 saved 描述（disable 项）
        var actionMenu = ui.seenMenus.stream()
            .filter(m -> m.title().equals("MCP server docs")).findFirst().orElseThrow();
        assertThat(actionMenu.items().stream()
            .filter(i -> i.value().equals("disable")).findFirst().orElseThrow().description())
            .isEqualTo("saved to the global mcp.json");
        rig.shutdownSession();
    }

    @Test
    void disableInProjectWritesOverride() throws Exception {
        var rig = rigWithServer("docs");
        var ui = new ScriptedUi();
        ui.answers.addAll(List.of("docs", "disable-project"));
        ui.answers.add(null);
        ui.answers.add(null);
        rig.ext.actions().manage(ui);
        // 项目 override 落 <cwd>/.pi-java/mcp.json（pi inProject 臂；异步臂轮询）
        var override = cwd.resolve(".pi-java").resolve("mcp.json");
        var written = awaitFile(override, "\"enabled\": false");
        assertThat(written).contains("\"docs\"").contains("\"enabled\": false");
        // 全局文件不动
        assertThat(Files.readString(agentDir.resolve("mcp.json")))
            .doesNotContain("\"enabled\"");
        rig.shutdownSession();
    }

    @Test
    void exposureChangePersistsAndReregisters() throws Exception {
        var server = ScriptedMcpServer.withTools("search");
        Files.writeString(agentDir.resolve("mcp.json"),
            "{\"mcpServers\":{\"docs\":{\"command\":\"run-x\"}}}");
        var rig = McpExtensionTestRig.create(agentDir, cwd, true, server);
        rig.startSession();
        var pending = rig.ext.state().pending;
        if (pending != null) {
            pending.join();
        }
        var ui = new ScriptedUi();
        ui.answers.addAll(List.of("docs", "exposure", "direct"));
        ui.answers.add(null);
        ui.answers.add(null);
        rig.ext.actions().manage(ui);
        assertThat(Files.readString(agentDir.resolve("mcp.json")))
            .contains("\"exposure\": \"direct\"");
        // exposure 菜单当前值打勾（pi ✓ 前缀逐字）
        var exposureMenu = ui.seenMenus.stream()
            .filter(m -> m.title().equals("Exposure of docs")).findFirst().orElseThrow();
        assertThat(exposureMenu.items().get(0).label()).isEqualTo("✓ codemode");
        assertThat(exposureMenu.selected()).isEqualTo("codemode");
        rig.shutdownSession();
    }

    /** 轮询文件内容出现（runInBackground 异步臂），超时 3s。 */
    private static String awaitFile(Path path, String fragment) throws Exception {
        for (var i = 0; i < 300; i++) {
            if (Files.exists(path)) {
                var content = Files.readString(path);
                if (content.contains(fragment)) {
                    return content;
                }
            }
            Thread.sleep(10);
        }
        return Files.exists(path) ? Files.readString(path) : "<missing>";
    }

    @Test
    void serversMenuSortsByAttentionAndShowsNotices() throws Exception {
        Files.writeString(agentDir.resolve("mcp.json"),
            "{\"mcpServers\":{\"docs\":{\"command\":\"run-x\"}}}");
        var rig = rigWithServer("docs");
        var ui = new ScriptedUi();
        ui.answers.add(null); // 直接关闭
        rig.ext.actions().manage(ui);
        var menu = ui.seenMenus.get(0);
        assertThat(menu.title()).isEqualTo("MCP servers");
        assertThat(menu.confirmLabel()).isEqualTo("manage");
        assertThat(menu.cancelLabel()).isEqualTo("close");
        var item = menu.items().get(0);
        assertThat(item.value()).isEqualTo("docs");
        assertThat(item.description()).contains("· codemode · global");
        rig.shutdownSession();
    }

    @Test
    void emptyManagerShowsEmptyText() throws Exception {
        var rig = McpExtensionTestRig.create(agentDir, cwd, true, null);
        rig.startSession();
        var ui = new ScriptedUi();
        ui.answers.add(null);
        rig.ext.actions().manage(ui);
        assertThat(ui.seenMenus.get(0).empty())
            .isEqualTo("No MCP servers configured. Add them to "
                + agentDir.resolve("mcp.json") + " or .pi-java/mcp.json.");
        rig.shutdownSession();
    }
}
