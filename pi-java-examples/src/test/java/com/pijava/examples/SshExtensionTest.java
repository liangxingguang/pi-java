package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ShellOptions;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.builtin.BashTool;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;

import org.junit.jupiter.api.Test;

/** docs/54 §2.8：ssh 验收——flag 解析、四工具远程后端、user_bash、cwd 改写。 */
class SshExtensionTest {

    private static SshExtension.SshResult ok(String out) {
        return SshExtension.SshResult.ok(out);
    }

    private static SshExtension remote(String flag, List<String> log,
            java.util.function.Function<String, SshExtension.SshResult> responder) {
        var ext = new SshExtension();
        ext.backend((remote, command, options) -> {
            log.add(command);
            return responder.apply(command);
        });
        var w = new W3Context();
        w.cwd = "/local";
        w.flagValue = flag;
        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);
        return ext;
    }

    @Test
    void registrationAndFlagWithPath() {
        var w = new W3Context();
        w.flagValue = "user@host:/remote/path";
        var log = new ArrayList<String>();
        var ext = new SshExtension();
        ext.backend((remote, command, options) -> {
            log.add(command);
            return ok("");
        });

        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);

        assertThat(w.tools.toolNames()).contains("bash", "read", "write", "edit");
        assertThat(w.ui.statuses()).containsEntry("ssh", "SSH: user@host:/remote/path");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("SSH mode: user@host:/remote/path");
        assertThat(log).isEmpty();
    }

    @Test
    void flagWithoutPathUsesPwd() {
        var w = new W3Context();
        w.flagValue = "user@host";
        var log = new ArrayList<String>();
        var ext = new SshExtension();
        ext.backend((remote, command, options) -> {
            log.add(command);
            return ok("/home/user\n");
        });
        ext.register(w.ctx);

        ext.onSessionStart(w.ctx);

        assertThat(log).containsExactly("pwd");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("SSH mode: user@host:/home/user");
    }

    @Test
    void noFlagLeavesHooksInactive() {
        var w = new W3Context();
        var ext = new SshExtension();
        ext.backend((remote, command, options) -> {
            throw new AssertionError("backend must not run without --ssh");
        });
        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);

        var userBash = w.fire(ExtensionEventSpec.USER_BASH,
            new ExtensionEvent.UserBashEvent("git status", false, w.cwd));
        assertThat(userBash).hasSize(1);
        assertThat(userBash[0]).isNull();

        var before = w.fire(ExtensionEventSpec.BEFORE_AGENT_START,
            new ExtensionEvent.BeforeAgentStartEvent(
                "text", "Current working directory: /local", Map.of()));
        assertThat(before).hasSize(1);
        assertThat(before[0]).isNull();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bashExecutorOverrideWrapsCd() throws Exception {
        var log = new ArrayList<String>();
        var w = new W3Context();
        w.cwd = "/local";
        w.flagValue = "user@host:/srv";
        var ext = new SshExtension();
        ext.backend((remote, command, options) -> {
            log.add(command);
            return ok("done");
        });
        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);

        AgentTool bash = w.tools.get("bash");
        var input = new BashTool.BashInput("ls -la", Optional.empty());
        var context = new ToolContext("/local", Map.of(), null, null);
        ToolResult<?> result = ((AgentTool) bash)
            .execute("c1", input, null, null, context);

        assertThat(log).containsExactly("cd '/srv' && ls -la");
        assertThat(ExampleFixtures.textOf(result.content())).isEqualTo("done");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void remoteBashTimedOutMatchesShellContract() {
        var w = new W3Context();
        w.cwd = "/local";
        w.flagValue = "user@host:/srv";
        var ext = new SshExtension();
        ext.backend((r, command, options) ->
            new SshExtension.SshResult(new byte[0], "", -1, true));
        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);

        AgentTool bash = w.tools.get("bash");
        var input = new BashTool.BashInput("sleep 10", Optional.of(1.0));
        var context = new ToolContext("/local", Map.of(), null, null);
        assertThatThrownBy(() -> ((AgentTool) bash)
            .execute("c1", input, null, null, context))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Command timed out after 1 seconds");
    }

    @Test
    void userBashReturnsOperations() {
        var w = new W3Context();
        w.cwd = "/local";
        w.flagValue = "user@host:/srv";
        var ext = new SshExtension();
        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);
        var results = w.fire(ExtensionEventSpec.USER_BASH,
            new ExtensionEvent.UserBashEvent("git status", false, w.cwd));
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

        var event = new ExtensionEvent.BeforeAgentStartEvent(
            "text", "Current working directory: /local\nOther text", Map.of());
        var r = w.fire(ExtensionEventSpec.BEFORE_AGENT_START, event);
        var result = (com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult) r[0];
        assertThat(result.systemPrompt()).contains(
            "Current working directory: /remote (via SSH: user@host)").doesNotContain("/local");
    }

    // ── 文件工具远程后端 ──

    @Test
    void remoteReadOperations() throws Exception {
        var log = new ArrayList<String>();
        var ext = remote("user@host:/remote", log, command -> ok("hello"));

        var ops = ext.remoteReadOperations();
        assertThat(new String(ops.readFile("/remote/a.txt"), StandardCharsets.UTF_8))
            .isEqualTo("hello");
        assertThat(new String(ops.createReadStream("/remote/a.txt").readAllBytes(),
            StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(log).containsExactly("cat '/remote/a.txt'", "cat '/remote/a.txt'");
    }

    @Test
    void remoteAccessFailureThrowsPiMessage() {
        var ext = remote("user@host:/remote", new ArrayList<>(),
            command -> new SshExtension.SshResult(new byte[0], "denied", 1, false));

        assertThatThrownBy(() -> ext.remoteReadOperations().access("/remote/a"))
            .isInstanceOf(IOException.class)
            .hasMessage("SSH failed (1): denied");
    }

    @Test
    void remoteDetectImageMimeType() throws Exception {
        var ext = remote("user@host:/remote", new ArrayList<>(), command -> switch (command) {
            case "file --mime-type -b '/remote/a.png'" -> ok("image/png\n");
            case "file --mime-type -b '/remote/a.txt'" -> ok("text/plain\n");
            default -> new SshExtension.SshResult(new byte[0], "boom", 1, false);
        });

        var ops = ext.remoteReadOperations();
        assertThat(ops.detectImageMimeType("/remote/a.png")).contains("image/png");
        assertThat(ops.detectImageMimeType("/remote/a.txt")).isEmpty();
        assertThat(ops.detectImageMimeType("/remote/missing")).isEmpty();
    }

    @Test
    void remoteReadStreamKeepsBytes() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        var ext = remote("user@host:/remote", new ArrayList<>(),
            command -> new SshExtension.SshResult(png, "", 0, false));

        var stream = ext.remoteReadOperations().createReadStream("/remote/a.png");
        assertThat(stream).isInstanceOf(ByteArrayInputStream.class);
        assertThat(stream.readAllBytes()).isEqualTo(png);
    }

    @Test
    void remoteWriteOperations() throws Exception {
        var log = new ArrayList<String>();
        var ext = remote("user@host:/remote", log, command -> ok(""));

        var ops = ext.remoteWriteOperations();
        ops.writeFile("/remote/a b.txt", "hi");
        ops.mkdir("/remote/dir");

        assertThat(log).containsExactly(
            "mkdir -p $(dirname '/remote/a b.txt') && echo 'aGk=' | base64 -d > '/remote/a b.txt'",
            "mkdir -p '/remote/dir'");
    }

    @Test
    void remoteEditOperationsReuseReadAndWrite() throws Exception {
        var log = new ArrayList<String>();
        var ext = remote("user@host:/remote", log, command -> ok("old"));

        var ops = ext.remoteEditOperations();
        assertThat(new String(ops.readFile("/remote/a.txt"), StandardCharsets.UTF_8))
            .isEqualTo("old");
        ops.writeFile("/remote/a.txt.bak", "old");

        assertThat(log).containsExactly(
            "cat '/remote/a.txt'",
            "mkdir -p $(dirname '/remote/a.txt.bak') && echo 'b2xk' | base64 -d > '/remote/a.txt.bak'");
    }

    // ── 纯函数 ──

    @Test
    void remoteCommandHelpers() {
        assertThat(SshExtension.toRemote("/local/src/a.java", "/local", "/remote"))
            .isEqualTo("/remote/src/a.java");
        assertThat(SshExtension.toRemote("src/a.java", "/local", "/remote"))
            .isEqualTo("src/a.java");
        assertThat(SshExtension.toRemote("/abs/a", "/local", "/remote"))
            .isEqualTo("/abs/a");
        assertThat(SshExtension.toRemote("", "/local", "/remote/"))
            .isEqualTo("/remote/");
        assertThat(SshExtension.toRemote("D:\\proj\\src\\A.java", "D:\\proj", "/remote"))
            .isEqualTo("/remote/src/A.java");

        assertThat(SshExtension.catCommand("/r/a b")).isEqualTo("cat '/r/a b'");
        assertThat(SshExtension.testReadableCommand("/r/f")).isEqualTo("test -r '/r/f'");
        assertThat(SshExtension.fileMimeCommand("/r/f"))
            .isEqualTo("file --mime-type -b '/r/f'");
        assertThat(SshExtension.mkdirCommand("/r/dir")).isEqualTo("mkdir -p '/r/dir'");
        assertThat(SshExtension.writeCommand("/r/f", "YQ=="))
            .isEqualTo("mkdir -p $(dirname '/r/f') && echo 'YQ==' | base64 -d > '/r/f'");
        assertThat(SshExtension.quote("a'b")).isEqualTo("'a'\\''b'");
        assertThat(SshExtension.sshExecError(
            new SshExtension.SshResult(new byte[0], "denied", 5, false)))
            .isEqualTo("SSH failed (5): denied");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bashBackendReceivesToolOptions() throws Exception {
        var seen = new ArrayList<ShellOptions>();
        var ext = new SshExtension();
        ext.backend((remote, command, options) -> {
            seen.add(options);
            return ok("ok");
        });
        var w = new W3Context();
        w.cwd = "/local";
        w.flagValue = "user@host:/srv";
        ext.register(w.ctx);
        ext.onSessionStart(w.ctx);

        AgentTool bash = w.tools.get("bash");
        var context = new ToolContext("/local", Map.of(), null, null);
        ((AgentTool) bash).execute("c1",
            new BashTool.BashInput("pwd", Optional.empty()), null, null, context);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).cwd()).isEqualTo("/local");
    }
}
