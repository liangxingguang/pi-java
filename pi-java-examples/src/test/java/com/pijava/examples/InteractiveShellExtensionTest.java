package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;

import org.junit.jupiter.api.Test;

/** docs/54 §2.9：interactive-shell 验收——59 命令判定、`!i`、非 TUI Result、fake shell。 */
class InteractiveShellExtensionTest {

    @Test
    void defaultCommandsVerbatim() {
        assertThat(InteractiveShellExtension.DEFAULT_COMMANDS).hasSize(59);
        assertThat(InteractiveShellExtension.DEFAULT_COMMANDS)
            .contains("vim", "git rebase", "git add -p", "ssh", "docker exec -it", "ncdu");
    }

    @Test
    void isInteractiveCommandPrefixAndTail() {
        assertThat(InteractiveShellExtension.isInteractiveCommand("vim file.txt")).isTrue();
        assertThat(InteractiveShellExtension.isInteractiveCommand("git rebase -i HEAD~3")).isTrue();
        assertThat(InteractiveShellExtension.isInteractiveCommand("ls -la | vim")).isTrue();
        assertThat(InteractiveShellExtension.isInteractiveCommand("echo hi | grep hi")).isFalse();
        assertThat(InteractiveShellExtension.isInteractiveCommand("rg foo")).isFalse();
    }

    @Test
    void stripForcePrefix() {
        assertThat(InteractiveShellExtension.stripForcePrefix("i vim x")).isEqualTo("vim x");
        assertThat(InteractiveShellExtension.stripForcePrefix("i\tvim")).isEqualTo("vim");
        assertThat(InteractiveShellExtension.stripForcePrefix("vim")).isEqualTo("vim");
    }

    @Test
    void nonInteractiveReturnsNull() {
        var w = new W3Context();
        var ext = new InteractiveShellExtension();
        ext.register(w.ctx);
        assertThat(ext.handle("echo hi", w.ctx)).isNull();
    }

    @Test
    void nonTuiReturnsRequireTuiResult() {
        var w = new W3Context();
        w.mode = "print";
        var ext = new InteractiveShellExtension();
        ext.register(w.ctx);
        var result = ext.handle("vim", w.ctx);
        assertThat(result).isInstanceOf(UserBashEventResult.Result.class);
        var r = (UserBashEventResult.Result) result;
        assertThat(r.result().output()).isEqualTo("(interactive commands require TUI)");
        assertThat(r.result().exitCode()).isEqualTo(1);
    }

    @Test
    void tuiFakeShellSuccessAndFailure() {
        var w = new W3Context();
        var ext = new InteractiveShellExtension();
        ext.register(w.ctx);
        ext.runner(cmd -> 0);
        var ok = (UserBashEventResult.Result) ext.handle("vim", w.ctx);
        assertThat(ok.result().output()).isEqualTo("(interactive command completed successfully)");
        assertThat(ok.result().exitCode()).isZero();

        ext.runner(cmd -> 7);
        var fail = (UserBashEventResult.Result) ext.handle("i vim", w.ctx);
        assertThat(fail.result().output()).isEqualTo("(interactive command exited with code 7)");
    }
}
