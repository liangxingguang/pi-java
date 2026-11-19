package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

import com.pijava.agent.tool.ShellResult;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;

import org.junit.jupiter.api.Test;

/** docs/54 §2.8：ssh 验收——flag 解析、bash 远程执行器、user_bash Operations、cwd 改写、远程命令。 */
class SshExtensionTest {

    private static ShellResult ok(String out) {
        return new ShellResult(out, 0, false, false, 0, 0);
    }

    @Test
    void registrationAndFlagWithPath() {
        var w = new W3Context();
        w.flagValue = "user@host:/remote/path";
        var log = new ArrayList<String>();
        var ext = new SshExtension();
        ext.backend(w.ctx, (cmd, cwd) -> {
            log.add(cmd);
            return ok("/remote/path");
        });

        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);

        assertThat(w.tools.toolNames()).contains("bash");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("SSH mode: user@host:/remote/path");
    }

    @Test
    void flagWithoutPathUsesPwd() {
        var w = new W3Context();
        w.flagValue = "user@host";
        var log = new ArrayList<String>();
        var ext = new SshExtension();
        ext.backend(w.ctx, (cmd, cwd) -> {
            log.add(cmd);
            return ok("/home/user\n");
        });
        ext.register(w.ctx);

        ext.onSessionStart(w.ctx);

        assertThat(log).contains("pwd");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("SSH mode: user@host:/home/user");
    }

    @Test
    void bashExecutorOverrideWrapsCd() throws Exception {
        var w = new W3Context();
        w.flagValue = "user@host:/srv";
        var log = new ArrayList<String>();
        var ext = new SshExtension();
        ext.backend(w.ctx, (cmd, cwd) -> {
            log.add(cmd);
            return ok("done");
        });
        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);

        @SuppressWarnings("rawtypes")
        com.pijava.agent.tool.AgentTool bash = w.tools.get("bash");
        var input = new com.pijava.agent.tool.builtin.BashTool.BashInput("ls -la",
            java.util.Optional.empty());
        var tc = new com.pijava.agent.tool.ToolContext("/local", java.util.Map.of(), null, null);
        @SuppressWarnings("unchecked")
        var ignored = ((com.pijava.agent.tool.AgentTool) bash).execute("c1", input, null, null, tc);

        assertThat(log).anySatisfy(cmd -> assertThat(cmd).isEqualTo("cd /srv && ls -la"));
    }

    @Test
    void userBashReturnsOperations() {
        var w = new W3Context();
        var ext = new SshExtension();
        ext.register(w.ctx);
        var results = w.fire(ExtensionEventSpec.USER_BASH,
            new com.pijava.coding.agent.extension.event.ExtensionEvent.UserBashEvent(
                "git status", false, w.cwd));
        assertThat(results).hasSize(1);
        assertThat(results[0]).isInstanceOf(UserBashEventResult.Operations.class);
    }

    @Test
    void beforeAgentStartReplacesCwd() {
        var w = new W3Context();
        w.cwd = "/local";
        w.flagValue = "user@host:/remote";
        var ext = new SshExtension();
        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);

        var event = new com.pijava.coding.agent.extension.event.ExtensionEvent.BeforeAgentStartEvent(
            "text", "Current working directory: /local\nOther text", java.util.Map.of());
        var r = w.fire(ExtensionEventSpec.BEFORE_AGENT_START, event);
        var result = (com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult) r[0];
        assertThat(result.systemPrompt()).contains(
            "Current working directory: /remote (via SSH: user@host)").doesNotContain("/local");
    }

    @Test
    void remoteCommandHelpers() {
        assertThat(SshExtension.toRemote("src/a.java", "/local", "/remote"))
            .isEqualTo("/remote/src/a.java");
        assertThat(SshExtension.toRemote("a.java", "/local", "/remote/"))
            .isEqualTo("/remote/a.java");
        assertThat(SshExtension.toRemote("/abs/a", "/local", "/remote"))
            .isEqualTo("/abs/a");
        assertThat(SshExtension.catCommand("/r/a b"))
            .isEqualTo("cat '/r/a b'");
        assertThat(SshExtension.testReadableCommand("/r/f")).isEqualTo("test -r '/r/f'");
        assertThat(SshExtension.writeCommand("/r/f", "YQ=="))
            .isEqualTo("mkdir -p $(dirname '/r/f') && echo 'YQ==' | base64 -d > '/r/f'");
        assertThat(SshExtension.sshExecError(new ShellResult("denied", 5, false, false, 0, 0)))
            .isEqualTo("SSH failed (5): denied");
    }
}
