package com.pijava.coding.agent.extension.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.mcp.config.McpConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 24（docs/35 §6）：/mcp 子命令面（map/07 #86）——formatStatus 逐字文案、
 * usage 警告、pickServer 各臂、enable/disable 写回（＋项目 override 臂）、
 * logout 无凭证文案。
 */
class McpManagerActionsTest {

    @TempDir
    Path agentDir;

    @TempDir
    Path cwd;

    private McpExtensionTestRig rigWith(String mcpJson) throws Exception {
        if (mcpJson != null) {
            Files.writeString(agentDir.resolve("mcp.json"), mcpJson);
        }
        var rig = McpExtensionTestRig.create(agentDir, cwd, true, null);
        rig.startSession();
        return rig;
    }


    @Test
    void emptyStatusTextIsVerbatim() throws Exception {
        var rig = rigWith(null);
        // /mcp 无参（print 模式 ⇒ notify 臂）
        rig.commands.get("mcp").execute("", slashCtx(rig));
        assertThat(rig.ui.notifications).hasSize(1);
        assertThat(rig.ui.notifications.get(0)[0])
            .isEqualTo("No MCP servers configured. Add them to "
                + agentDir.resolve("mcp.json") + " or .pi-java/mcp.json.");
        rig.shutdownSession();
    }

    @Test
    void statusLinesPerServer() throws Exception {
        var rig = rigWith("{\"mcpServers\":{"
            + "\"off\":{\"command\":\"run-x\",\"enabled\":false},"
            + "\"docs\":{\"command\":\"run-y\",\"exposure\":\"direct\"}}}");
        rig.commands.get("mcp").execute("", slashCtx(rig));
        // 启动的 reportProblems（失败服务器）在前 ⇒ 状态通知取末条
        var text = rig.ui.notifications.get(rig.ui.notifications.size() - 1)[0];
        assertThat(text).contains("off: disabled (codemode)");
        assertThat(text).containsPattern("docs: (starting|connecting…|failed.*) \\(direct\\)");
        rig.shutdownSession();
    }

    @Test
    void unknownActionWarnsUsage() throws Exception {
        var rig = rigWith(null);
        rig.commands.get("mcp").execute("bogus", slashCtx(rig));
        assertThat(rig.ui.hasNotification(McpManagerActions.MCP_USAGE, "warning")).isTrue();
        rig.shutdownSession();
    }

    @Test
    void extraArgsWarnUsage() throws Exception {
        var rig = rigWith(null);
        rig.commands.get("mcp").execute("login a b", slashCtx(rig));
        assertThat(rig.ui.hasNotification(McpManagerActions.MCP_USAGE, "warning")).isTrue();
        rig.shutdownSession();
    }

    @Test
    void unknownServerNameNotifiesError() throws Exception {
        var rig = rigWith(null);
        rig.commands.get("mcp").execute("logout ghost", slashCtx(rig));
        assertThat(rig.ui.hasNotification("No MCP server named \"ghost\".", "error")).isTrue();
        rig.shutdownSession();
    }

    @Test
    void logoutWithoutOAuthServerNotifiesNone() throws Exception {
        // 唯一 stdio 服务器不用 OAuth ⇒ eligible 全空 ⇒ none 文案（单候选直返不适用）
        var rig = rigWith("{\"mcpServers\":{\"docs\":{\"command\":\"run-x\"}}}");
        rig.commands.get("mcp").execute("logout docs", slashCtx(rig));
        assertThat(rig.ui.hasNotification(
            "No enabled MCP server uses OAuth. Only HTTP servers without an"
                + " Authorization header do.", "error")).isTrue();
        rig.shutdownSession();
    }

    @Test
    void reconnectWithoutConnectionSaysDisabled() throws Exception {
        // 禁用 ⇒ 无连接 ⇒ pickServer eligible（connection != null）不通过 ⇒ none 文案
        var rig = rigWith("{\"mcpServers\":{\"docs\":{\"command\":\"run-x\","
            + "\"enabled\":false}}}");
        rig.commands.get("mcp").execute("reconnect docs", slashCtx(rig));
        assertThat(rig.ui.hasNotification("No enabled MCP server to reconnect.", "error"))
            .isTrue();
        rig.shutdownSession();
    }

    /** SlashContext 只需 session 为空可容——命令走捕获的扩展上下文。 */
    private static com.pijava.coding.agent.core.slash.SlashContext slashCtx(
            McpExtensionTestRig rig) {
        return com.pijava.coding.agent.core.slash.SlashContext.of(null);
    }
}
