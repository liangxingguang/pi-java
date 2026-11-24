package com.pijava.examples;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ShellOptions;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolAnnotations;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolExposure;
import com.pijava.agent.tool.ToolNamespace;
import com.pijava.agent.tool.ToolReach;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.agent.tool.builtin.BashTool;
import com.pijava.agent.tool.builtin.EditTool;
import com.pijava.agent.tool.builtin.ReadTool;
import com.pijava.agent.tool.builtin.WriteTool;
import com.pijava.ai.AbortSignal;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionFlagOptions;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;

/**
 * pi {@code examples/extensions/ssh.ts:1-220} 移植（docs/54 §2.8）——
 * {@code --ssh user@host[:path]} 时 read/write/edit/bash 四个工具改走远程后端，
 * 无 flag 时回落本地工具。
 *
 * <p>与 pi 的对位：</p>
 * <ul>
 *   <li>{@link SshBackend} ≙ {@code sshExec}：默认实现 spawn 本地 {@code ssh}
 *       客户端，stdout 保持原始字节（图片/base64 安全），stderr 供错误文案；</li>
 *   <li>read/write/edit 分别注入 {@code createXxxTool(..., {operations})}
 *       （B287 接缝），路径按 pi 的 {@code p.replace(localCwd, remoteCwd)} 映射；</li>
 *   <li>bash 经 {@code BashTool.create(..., executorOverride)}（B286）执行
 *       {@code cd <remoteCwd> && command}，超时/中止交给后端；</li>
 *   <li>{@code user_bash} 返回同一远程执行器；{@code session_start} 解析 flag
 *       并取远程 {@code pwd}；{@code before_agent_start} 改写系统提示里的 cwd。</li>
 * </ul>
 *
 * <p><b>边界</b>：远程路径按 POSIX 语义拼接；本地为 Windows 时把 {@code \\}
 * 归一为 {@code /}（远端为 POSIX）。验收用注入式 fake 后端，不真起 ssh 进程
 * （与 subagent 的 D244 同族）。</p>
 */
public final class SshExtension implements PiExtension {

    /** 一次远程执行的原始结果（stdout 字节，stderr 文本，退出码，是否超时）。 */
    record SshResult(byte[] stdout, String stderr, int exitCode, boolean timedOut) {
        static SshResult ok(String stdout) {
            return new SshResult(stdout.getBytes(StandardCharsets.UTF_8), "", 0, false);
        }
    }

    /**
     * 注入式 ssh 传输；测试用 fake，默认实现走本地 {@code ssh} 进程。
     * 非零退出<b>不抛</b>——调用方按语义决定（read/write/edit 抛，bash 报退出码）。
     */
    @FunctionalInterface
    interface SshBackend {
        SshResult run(String remote, String command, ShellOptions options) throws Exception;
    }

    /** 解析后的远程目标（pi {@code resolvedSsh}）。 */
    private record Remote(String host, String cwd) {}

    private Remote remote;
    private String localCwd = "";
    private SshBackend backend = SshExtension::runSsh;

    @Override
    public String name() {
        return "ssh";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.registerFlag("ssh",
            ExtensionFlagOptions.ofString("SSH remote: user@host or user@host:/path", null));

        // pi 注册四件：有 ssh 时执行远程工具，否则回落本地（同一份 schema/描述）。
        ctx.tools().register(new DispatchingTool<>(ReadTool.create(), this::remoteReadTool));
        ctx.tools().register(new DispatchingTool<>(WriteTool.create(), this::remoteWriteTool));
        ctx.tools().register(new DispatchingTool<>(EditTool.create(), this::remoteEditTool));
        ctx.tools().register(new DispatchingTool<>(BashTool.create(), this::remoteBashTool));

        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            onSessionStart(c);
            return null;
        });
        ctx.on(ExtensionEventSpec.USER_BASH, (event, c) -> {
            if (remote == null) {
                return null; // 无 SSH ⇒ 本地执行（pi 返回 undefined）
            }
            return new UserBashEventResult.Operations(this::remoteExecutorExec);
        });
        ctx.on(ExtensionEventSpec.BEFORE_AGENT_START, (event, c) -> {
            if (remote == null) {
                return null;
            }
            var replaced = event.systemPrompt().replace(
                "Current working directory: " + localCwd,
                "Current working directory: " + remote.cwd() + " (via SSH: " + remote.host() + ")");
            return new BeforeAgentStartEventResult(null, replaced);
        });
    }

    /** 注入后端（测试用）。 */
    void backend(SshBackend backend) {
        if (backend != null) {
            this.backend = backend;
        }
    }

    // ── 远程工具工厂（pi 每次 execute 现造 createXxxTool(..., {operations})）──

    private AgentTool<ReadTool.ReadInput, ReadTool.ReadDetails> remoteReadTool() {
        return remote == null ? null : ReadTool.create(remoteReadOperations());
    }

    private AgentTool<WriteTool.WriteInput, Void> remoteWriteTool() {
        return remote == null ? null : WriteTool.create(remoteWriteOperations());
    }

    private AgentTool<EditTool.EditInput, EditTool.EditDetails> remoteEditTool() {
        return remote == null ? null : EditTool.create(remoteEditOperations());
    }

    private AgentTool<BashTool.BashInput, BashTool.BashDetails> remoteBashTool() {
        return remote == null ? null : BashTool.create(null, true, this::remoteExecutorExec);
    }

    /** pi {@code createRemoteReadOps}。 */
    ReadTool.Operations remoteReadOperations() {
        return new ReadTool.Operations() {
            @Override public byte[] readFile(String absolutePath) throws IOException {
                return runOrThrow(catCommand(toRemotePath(absolutePath))).stdout();
            }
            @Override public InputStream createReadStream(String absolutePath) throws IOException {
                return new ByteArrayInputStream(readFile(absolutePath));
            }
            @Override public void access(String absolutePath) throws IOException {
                runOrThrow(testReadableCommand(toRemotePath(absolutePath)));
            }
            @Override public Optional<String> detectImageMimeType(String absolutePath) {
                // pi 捕获全部错误 ⇒ null（按文本处理）。
                try {
                    var result = run(fileMimeCommand(toRemotePath(absolutePath)));
                    if (result.exitCode() != 0) {
                        return Optional.empty();
                    }
                    String mime = text(result.stdout()).trim();
                    return switch (mime) {
                        case "image/jpeg", "image/png", "image/gif", "image/webp" ->
                            Optional.of(mime);
                        default -> Optional.empty();
                    };
                } catch (Exception e) {
                    return Optional.empty();
                }
            }
        };
    }

    /** pi {@code createRemoteWriteOps}。 */
    WriteTool.Operations remoteWriteOperations() {
        return new WriteTool.Operations() {
            @Override public void writeFile(String absolutePath, String content) throws IOException {
                String base64 = Base64.getEncoder()
                    .encodeToString(content.getBytes(StandardCharsets.UTF_8));
                runOrThrow(writeCommand(toRemotePath(absolutePath), base64));
            }
            @Override public void mkdir(String directoryPath) throws IOException {
                runOrThrow(mkdirCommand(toRemotePath(directoryPath)));
            }
        };
    }

    /** pi {@code createRemoteEditOps}：read/write 复用远程实现。 */
    EditTool.Operations remoteEditOperations() {
        var read = remoteReadOperations();
        var write = remoteWriteOperations();
        return new EditTool.Operations() {
            @Override public byte[] readFile(String absolutePath) throws IOException {
                return read.readFile(absolutePath);
            }
            @Override public void writeFile(String absolutePath, String content) throws IOException {
                write.writeFile(absolutePath, content);
            }
            @Override public void access(String absolutePath) throws IOException {
                read.access(absolutePath);
            }
        };
    }

    /** pi {@code createRemoteBashOps.exec}：{@code cd <remote(cwd)> && command}。 */
    private ShellResult remoteExecutorExec(String command, ShellOptions options) throws Exception {
        var target = requireRemote();
        String local = options != null && options.cwd() != null ? options.cwd() : localCwd;
        String remoteDir = toRemote(local, localCwd, target.cwd());
        var result = backend.run(target.host(),
            "cd " + quote(remoteDir) + " && " + command, options);
        String output = text(result.stdout()) + result.stderr();
        if (result.timedOut()) {
            return new ShellResult(output, -1, true, false, 0, 0);
        }
        return new ShellResult(output, result.exitCode(), false, false, 0, 0);
    }

    /** session_start：解析 ssh flag，取远程 pwd，落状态通知。 */
    void onSessionStart(ExtensionContext ctx) {
        localCwd = ctx.cwd();
        String config = ctx.getFlag("ssh") instanceof String s ? s : "";
        if (config.isEmpty()) {
            remote = null;
            return;
        }
        if (config.contains(":")) {
            int i = config.indexOf(':');
            remote = new Remote(config.substring(0, i), config.substring(i + 1));
        } else {
            SshResult pwd;
            try {
                pwd = backend.run(config, "pwd", null);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
            if (pwd.exitCode() != 0) {
                throw new RuntimeException(sshExecError(pwd));
            }
            remote = new Remote(config, text(pwd.stdout()).trim());
        }
        ctx.ui().setStatus("ssh", "SSH: " + remote.host() + ":" + remote.cwd());
        ctx.ui().notify("SSH mode: " + remote.host() + ":" + remote.cwd(), NotifyType.INFO);
    }

    // ── 远程命令构造（pi ssh.ts 的模板；纯函数便于单测）──

    private String toRemotePath(String path) {
        return toRemote(path, localCwd, requireRemote().cwd());
    }

    /**
     * pi {@code toRemote = p.replace(localCwd, remoteCwd)}：字面替换<b>首个</b>
     * 出现处；本地 Windows 路径的 {@code \\} 归一为远端 POSIX 的 {@code /}。
     */
    static String toRemote(String path, String localCwd, String remoteCwd) {
        if (path == null || path.isEmpty()) {
            return remoteCwd;
        }
        String mapped = replaceFirstLiteral(path, localCwd, remoteCwd);
        return mapped.indexOf('\\') >= 0 ? mapped.replace('\\', '/') : mapped;
    }

    static String catCommand(String remotePath) {
        return "cat " + quote(remotePath);
    }

    static String testReadableCommand(String remotePath) {
        return "test -r " + quote(remotePath);
    }

    static String fileMimeCommand(String remotePath) {
        return "file --mime-type -b " + quote(remotePath);
    }

    static String writeCommand(String remotePath, String contentBase64) {
        return "mkdir -p $(dirname " + quote(remotePath) + ") && echo "
            + quote(contentBase64) + " | base64 -d > " + quote(remotePath);
    }

    static String mkdirCommand(String remotePath) {
        return "mkdir -p " + quote(remotePath);
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** pi sshExec 错误文案。 */
    static String sshExecError(SshResult result) {
        return "SSH failed (" + result.exitCode() + "): " + result.stderr();
    }

    private SshResult run(String command) throws Exception {
        var target = requireRemote();
        return backend.run(target.host(), command, null);
    }

    private SshResult runOrThrow(String command) throws IOException {
        SshResult result;
        try {
            result = run(command);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
        if (result.exitCode() != 0) {
            throw new IOException(sshExecError(result));
        }
        return result;
    }

    private Remote requireRemote() {
        if (remote == null) {
            throw new IllegalStateException("SSH is not configured");
        }
        return remote;
    }

    private static String replaceFirstLiteral(String text, String target, String replacement) {
        if (target == null || target.isEmpty()) {
            return text;
        }
        int index = text.indexOf(target);
        return index < 0 ? text
            : text.substring(0, index) + replacement + text.substring(index + target.length());
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ── 默认后端：spawn 本地 ssh 客户端（pi sshExec / createRemoteBashOps）──

    /**
     * @param options 非 null（bash 路径）⇒ stdout/stderr 合流（pi 的 onData 两路同汇）；
     *                null（read/write/edit）⇒ 分流，stdout 供内容、stderr 供错误文案
     */
    static SshResult runSsh(String remote, String command, ShellOptions options) throws Exception {
        boolean merge = options != null;
        var builder = new ProcessBuilder("ssh", remote, command);
        builder.redirectErrorStream(merge);
        var process = builder.start();
        process.getOutputStream().close(); // pi stdio: ["ignore", ...]：不留交互 stdin

        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        Runnable abort = process::destroyForcibly;
        Runnable unsubscribe = options != null && options.signal() != null
            ? options.signal().onAbort(abort) : () -> { };
        boolean timedOut = false;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stdoutTask = executor.submit(() -> {
                pump(process.getInputStream(), stdout);
                return null;
            });
            var stderrTask = merge ? null : executor.submit(() -> {
                pump(process.getErrorStream(), stderr);
                return null;
            });
            if (options != null && options.timeoutMillis().isPresent()) {
                if (!process.waitFor(options.timeoutMillis().getAsLong(), TimeUnit.MILLISECONDS)) {
                    timedOut = true;
                    process.destroyForcibly();
                }
            }
            process.waitFor();
            stdoutTask.get();
            if (stderrTask != null) {
                stderrTask.get();
            }
        } finally {
            unsubscribe.run();
        }

        byte[] out = stdout.toByteArray();
        var sink = options == null ? null : options.outputSink();
        if (sink != null && out.length > 0) {
            sink.onOutput(text(out));
        }
        return new SshResult(out, text(stderr.toByteArray()),
            timedOut ? -1 : process.exitValue(), timedOut);
    }

    private static void pump(InputStream in, ByteArrayOutputStream out) throws IOException {
        try (in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * pi 的 {@code registerTool({...localTool, execute: ssh ? remote : local})}：
     * 元数据全部沿用本地工具，只在 execute 时按已解析的 SSH 目标二选一。
     */
    private static final class DispatchingTool<T, D> implements AgentTool<T, D> {
        private final AgentTool<T, D> local;
        private final Supplier<AgentTool<T, D>> remote;

        DispatchingTool(AgentTool<T, D> local, Supplier<AgentTool<T, D>> remote) {
            this.local = local;
            this.remote = remote;
        }

        @Override
        public ToolResult<D> execute(String toolCallId, T params, AbortSignal signal,
                ToolUpdateCallback<D> onUpdate, ToolContext context) throws Exception {
            AgentTool<T, D> target = remote.get();
            return (target != null ? target : local)
                .execute(toolCallId, params, signal, onUpdate, context);
        }

        @Override public String name() { return local.name(); }
        @Override public String label() { return local.label(); }
        @Override public String description() { return local.description(); }
        @Override public Map<String, Object> inputSchema() { return local.inputSchema(); }
        @Override public ExecutionMode executionMode() { return local.executionMode(); }
        @Override public String promptSnippet() { return local.promptSnippet(); }
        @Override public List<String> promptGuidelines() { return local.promptGuidelines(); }
        @Override public T prepareArguments(Map<String, Object> raw) {
            return local.prepareArguments(raw);
        }
        @Override public com.pijava.ai.api.ConstrainedSampling constrainedSampling() {
            return local.constrainedSampling();
        }
        @Override public Map<String, Object> outputSchema() { return local.outputSchema(); }
        @Override public ToolExposure exposure() { return local.exposure(); }
        @Override public ToolNamespace namespace() { return local.namespace(); }
        @Override public java.util.function.Function<Map<String, Object>, ToolReach> reachesTools() {
            return local.reachesTools();
        }
        @Override public ToolAnnotations annotations() { return local.annotations(); }
        @Override public boolean defaultActive() { return local.defaultActive(); }
    }
}
