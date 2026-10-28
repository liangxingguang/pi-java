package com.pijava.web;

import java.nio.file.Files;
import java.util.function.Consumer;

import com.pijava.coding.agent.export.HtmlExporter;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.web.WebProtocol.WebServerMessage;

/**
 * {@link WebDispatcher} 的会话生命周期 / 终端 / fork / 导出命令簇（结构债拆分：
 * 行为零变化纯移动）。会话实例与事件订阅存于 {@link WebDispatcher}（translator 的
 * lambda 迟读 {@code session} 字段），本类经包私有钩子读写。
 */
final class WebSessionCommands {

    private final WebDispatcher base;
    private final Args args;
    private final Consumer<WebServerMessage> send;
    private final AgentEventTranslator translator;
    private long bashSeq;

    WebSessionCommands(WebDispatcher base, Args args, Consumer<WebServerMessage> send,
        AgentEventTranslator translator) {
        this.base = base;
        this.args = args;
        this.send = send;
        this.translator = translator;
    }

    // ── 会话生命周期 ─────────────────────────────────────────────────────

    void newSession() {
        closeSubscription();
        var previous = base.session();
        var previousId = previous.sessionId();
        // docs/15（B250）：替换式新建＋替换式关闭（shutdown{new, 新 id}）。
        base.session(AgentSession.createReplacing(args, previousId));
        try {
            previous.closeForReplacement("new", base.session().sessionId());
        } catch (Exception ignored) {
            // 忽略旧会话关闭失败
        }
        resubscribe();
        send.accept(new WebServerMessage.SessionChanged(base.session().sessionId()));
        send.accept(base.stateSync());
    }

    void loadSession(String sessionPath) {
        if (sessionPath == null || sessionPath.isBlank()) {
            send.accept(new WebServerMessage.Error("Session path required"));
            return;
        }
        // docs/15（B250）：switchTo 集中 before_switch/shutdown/start 序列。
        var result = base.session().switchTo(WebDispatcher.idFromPath(sessionPath));
        if (result.outcome() == AgentSession.SwitchOutcome.NOT_FOUND) {
            send.accept(new WebServerMessage.Error("Session not found: " + sessionPath));
            return;
        }
        if (result.outcome() == AgentSession.SwitchOutcome.CANCELLED) {
            send.accept(new WebServerMessage.Error("Session switch cancelled by extension"));
            return;
        }
        closeSubscription();
        resubscribe();
        send.accept(new WebServerMessage.SessionChanged(base.session().sessionId()));
        send.accept(base.stateSync());
    }

    void resubscribe() {
        closeSubscription();
        base.eventSubscription(base.session().subscribe(ev -> {
            for (var msg : translator.translate(ev)) {
                send.accept(msg);
            }
        }));
    }

    void closeSubscription() {
        if (base.eventSubscription() != null) {
            try {
                base.eventSubscription().close();
            } catch (Exception ignored) {
                // 忽略退订失败
            }
            base.eventSubscription(null);
        }
    }

    // ── 终端 / fork / 导出 ───────────────────────────────────────────────

    void bash(String command) {
        if (command == null || command.isBlank()) {
            send.accept(new WebServerMessage.Error("Command required"));
            return;
        }
        String id = "web-bash-" + (bashSeq++);
        Thread.startVirtualThread(() -> {
            try {
                var result = base.session().executeBash(id, command, false);
                send.accept(new WebServerMessage.BashResult(
                    command, result.exitCode(), result.output(),
                    result.truncated(), base.session().bashAborted()));
            } catch (Exception e) {
                send.accept(new WebServerMessage.BashResult(
                    command, -1,
                    e.getMessage() == null ? String.valueOf(e) : e.getMessage(),
                    false, false));
            }
        });
    }

    /** fork：{@code entryId} 之后的会话拷贝为新会话（docs/16：旧会话关闭收进 fork 内部）。 */
    void forkFrom(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            send.accept(new WebServerMessage.Error("Entry id required"));
            return;
        }
        var forked = base.session().forkFromEntry(entryId);
        if (forked.isEmpty()) {
            // docs/15：session_before_fork cancel。
            send.accept(new WebServerMessage.Error("Fork cancelled by extension"));
            return;
        }
        closeSubscription();
        base.session(forked.get());
        resubscribe();
        send.accept(new WebServerMessage.SessionChanged(base.session().sessionId()));
        send.accept(base.stateSync());
    }

    /** clone：整会话拷贝（无 leaf 时用当前会话名 clone）。 */
    void cloneSession() {
        var entries = base.session().harness().snapshot(base.session().laneName()).transcript();
        String leaf = entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
        var forked = leaf == null
            ? base.session().forkCopy(base.session().sessionName() + "-clone")
            : base.session().forkFromEntry(leaf);
        if (forked.isEmpty()) {
            send.accept(new WebServerMessage.Error("Clone cancelled by extension"));
            return;
        }
        closeSubscription();
        base.session(forked.get());
        resubscribe();
        send.accept(new WebServerMessage.SessionChanged(base.session().sessionId()));
        send.accept(base.stateSync());
    }

    void exportHtml() {
        try {
            var tmp = Files.createTempFile("pi-java-web-export", ".jsonl");
            base.session().exportJsonl(tmp);
            var html = new HtmlExporter().export(tmp);
            send.accept(new WebServerMessage.ExportPath(html.toString()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Export failed: " + e.getMessage());
        }
    }
}
