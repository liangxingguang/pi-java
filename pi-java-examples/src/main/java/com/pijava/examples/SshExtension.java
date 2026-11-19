package com.pijava.examples;

import java.util.Map;

import com.pijava.agent.tool.ShellResult;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;

/**
 * pi {@code examples/extensions/ssh.ts:1-220} 移植（docs/54 §2.8）——
 * ssh 模式下同名覆盖 bash（经 B286 executorOverride 接远程后端）、user_bash 返回
 * Operations、session_start/before_agent_start 改写 cwd。文件工具远程后端未闭环（B287），
 * 降级为远程命令纯函数演示（D243）。
 */
public final class SshExtension implements PiExtension {

    /** 注入式 ssh 后端；返回 ShellResult，测试 fake。 */
    @FunctionalInterface
    interface SshBackend {
        ShellResult execute(String command, String cwd);
    }

    private String remote = "";
    private String remoteCwd = "";
    private String localCwd = "";
    private SshBackend backend = (cmd, cwd) ->
        new ShellResult("SSH failed: no backend", 1, false, false, 0, 0);

    @Override
    public String name() {
        return "ssh";
    }

    @Override
    public void register(ExtensionContext ctx) {
        backend(ctx, null);
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            onSessionStart(c);
            return null;
        });
        ctx.on(ExtensionEventSpec.USER_BASH, (event, c) -> {
            var e = (ExtensionEvent.UserBashEvent) event;
            return new UserBashEventResult.Operations(remoteExecutor());
        });
        ctx.on(ExtensionEventSpec.BEFORE_AGENT_START, (event, c) -> {
            var replaced = event.systemPrompt().replace(
                "Current working directory: " + localCwd,
                "Current working directory: " + remoteCwd + " (via SSH: " + remote + ")");
            return new BeforeAgentStartEventResult(null, replaced);
        });
    }

    /** 注入后端（同时用 executorOverride 覆盖 bash）。 */
    void backend(ExtensionContext ctx, SshBackend backend) {
        if (backend != null) {
            this.backend = backend;
        }
        if (ctx != null) {
            ctx.tools().register(com.pijava.agent.tool.builtin.BashTool.create(
                null, true, this::remoteExecutorExec));
        }
    }

    private ShellResult remoteExecutorExec(String command,
            com.pijava.agent.tool.ShellOptions options) {
        var cwd = options == null ? null : options.cwd();
        return backend.execute("cd " + remoteCwd + " && " + command, cwd);
    }

    private com.pijava.agent.tool.ShellExecutor remoteExecutor() {
        return this::remoteExecutorExec;
    }

    /** session_start：解析 ssh flag，取远程 pwd，注册覆盖工具。 */
    void onSessionStart(ExtensionContext ctx) {
        localCwd = ctx.cwd();
        var config = ctx.getFlag("ssh") instanceof String s ? s : "";
        if (config.contains(":")) {
            var i = config.indexOf(':');
            remote = config.substring(0, i);
            remoteCwd = config.substring(i + 1);
        } else {
            remote = config;
            var pwd = backend.execute("pwd", null);
            remoteCwd = pwd.output() == null ? "" : pwd.output().trim();
        }
        ctx.ui().notify("SSH mode: " + remote + ":" + remoteCwd, NotifyType.INFO);
    }

    /** pi sshExec 错误文案。 */
    static String sshExecError(ShellResult r) {
        return "SSH failed (" + r.exitCode() + "): " + r.output();
    }

    /** D243：远程命令构造（纯函数）。 */
    static String toRemote(String path, String localCwd, String remoteCwd) {
        if (path == null || path.isEmpty()) {
            return remoteCwd;
        }
        if (path.startsWith("/")) {
            return path;
        }
        var normalized = path.startsWith(localCwd) ? path.substring(localCwd.length()) : path;
        return remoteCwd.replaceAll("/$", "") + "/" + normalized.replaceAll("^/", "");
    }

    static String catCommand(String remotePath) {
        return "cat " + quote(remotePath);
    }

    static String testReadableCommand(String remotePath) {
        return "test -r " + quote(remotePath);
    }

    static String writeCommand(String remotePath, String contentBase64) {
        return "mkdir -p $(dirname " + quote(remotePath) + ") && echo "
            + quote(contentBase64) + " | base64 -d > " + quote(remotePath);
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
