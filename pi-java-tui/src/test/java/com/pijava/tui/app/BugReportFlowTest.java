package com.pijava.tui.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.bugreport.BugReportUpload;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TuiEventDispatcher;
import com.sun.net.httpserver.HttpServer;

import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 15（T7）：pi {@code reportBug} 流对拍（bug-report.ts:51-137）——取消五臂、
 * ZIP 全链（report/diagnostics/session/summary＋会话尾记录）、upload 成功与
 * fallback、offline 门、summary 失败两臂。overlay 直驱（喂键 → isDone → apply，
 * {@link TreeNavigationFlowTest} 同手法）；worker 结局经真 dispatcher 排干断言。
 */
class BugReportFlowTest {

    private static final dev.tamboui.tui.event.KeyEvent ENTER =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ENTER);
    private static final dev.tamboui.tui.event.KeyEvent DOWN =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.DOWN);
    private static final dev.tamboui.tui.event.KeyEvent ESC =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE);

    @TempDir
    Path tmp;

    private String realHome;
    private AgentSession session;
    private ChatScreen chatScreen;
    private TuiEventDispatcher dispatcher;
    private final AtomicReference<ScreenOverlay> overlayRef = new AtomicReference<>();
    private ScriptedProvider provider;

    @BeforeEach
    void redirectHome() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
    }

    @AfterEach
    void tearDown() {
        if (realHome != null) {
            System.setProperty("user.home", realHome);
        }
        if (session != null) {
            session.close();
        }
    }

    // ── 剧本 provider（摘要调用可闩）─────────────────────────

    private static final class ScriptedProvider implements Provider {
        private final java.util.Queue<Object> replies = new ConcurrentLinkedQueue<>();
        private volatile CountDownLatch gate;

        void enqueueText(String text) { replies.add(text); }
        void enqueueError(String message) { replies.add(new RuntimeException(message)); }
        void latchNext() { gate = new CountDownLatch(1); }
        void release() { var g = gate; if (g != null) { g.countDown(); } }

        @Override public String name() { return "scripted"; }
        @Override public String displayName() { return "Scripted"; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(com.pijava.ai.api.ChatApi.class);
        }

        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            return apiType.cast(new Api(this));
        }

        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        private static final class Api extends AbstractChatApi {
            private final ScriptedProvider provider;

            Api(ScriptedProvider provider) { this.provider = provider; }

            @Override public String apiName() { return "scripted"; }
            @Override protected boolean resolvesRequestOptions() { return false; }

            @Override
            protected void streamInternal(StreamRequest request,
                    java.util.concurrent.SubmissionPublisher<StreamEvent> publisher) {
                var g = provider.gate;
                if (g != null) {
                    provider.gate = null;
                    try {
                        if (!g.await(15, TimeUnit.SECONDS)) {
                            throw new AssertionError("闩超时");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        publisher.submit(StreamEvent.StreamError.settle(
                            StopReason.ABORTED, null, AssistantMessage.empty()));
                        return;
                    }
                }
                var next = provider.replies.poll();
                if (next instanceof RuntimeException error) {
                    publisher.submit(StreamEvent.StreamError.settle(
                        StopReason.ERROR, error, AssistantMessage.empty()));
                    return;
                }
                var text = next instanceof String s ? s : "ok";
                var msg = AssistantMessage.empty()
                    .withContent(List.of(new ContentBlock.TextContent(text)))
                    .withStopReason(StopReason.STOP);
                publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                publisher.submit(new StreamEvent.TextDelta(0, text, msg));
                publisher.submit(StreamEvent.StreamDone.settle(StopReason.STOP, msg));
            }
        }
    }

    // ── 夹具 ────────────────────────────────────────────────

    private void newRig() {
        provider = new ScriptedProvider();
        var registry = ProviderRegistry.create();
        registry.register(provider);
        session = AgentSession.create(ArgsParser.parse(new String[] {
                "--provider", "scripted", "--model", "m1"}),
            registry,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(null), new DefaultFileSystem()));
        chatScreen = new ChatScreen();
        dispatcher = new TuiEventDispatcher();
    }

    private BugReportFlow flow(BugReportUpload upload, boolean offline) {
        return new BugReportFlow(session, chatScreen, dispatcher, overlayRef::set,
            () -> upload, () -> offline, () -> tmp);
    }

    /** 默认流（upload 指向「不应被调用」的死端口；调用即失败 ⇒ 可断言没走 upload）。 */
    private BugReportFlow flow() {
        return flow(new BugReportUpload(HttpClient.newHttpClient(),
            "http://127.0.0.1:1"), false);
    }

    private void drive(ScreenOverlay overlay,
                       dev.tamboui.tui.event.KeyEvent... keys) {
        assertThat(overlay).as("overlay 在位").isNotNull();
        for (var key : keys) {
            overlay.onKeyEvent(key);
        }
        assertThat(overlay.isDone()).as("overlay 定案").isTrue();
        overlayRef.set(null);
        overlay.apply(session, s -> { });
        dispatcher.drain();
    }

    private List<String> messageTexts() {
        return chatScreen.messages().stream().map(m -> switch (m) {
            case ChatMessage.System s -> s.text();
            case ChatMessage.Error e -> e.message();
            default -> "";
        }).toList();
    }

    private void awaitText(String text) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            dispatcher.drain();
            if (messageTexts().stream().anyMatch(t -> t.contains(text))) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("聊天区未出现「" + text + "」——现有：" + messageTexts());
    }

    private static HttpServer gateway(int status, String body) throws Exception {
        var server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/bug-reports", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static BugReportUpload uploadTo(HttpServer server) {
        return new BugReportUpload(HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort());
    }

    // ── 取消五臂 ────────────────────────────────────────────

    @Test
    void cancelAtInput() {
        newRig();
        flow().begin(null);
        drive(overlayRef.get(), ESC);
        assertThat(messageTexts()).contains("Bug report cancelled");
    }

    @Test
    void cancelAtTranscriptChoice() {
        newRig();
        flow().begin("hint");
        drive(overlayRef.get(), ENTER);       // input 确认（hint 预填）
        drive(overlayRef.get(), ESC);         // transcript 选择 Esc
        assertThat(messageTexts()).contains("Bug report cancelled");
    }

    @Test
    void cancelAtSummaryChoice() {
        newRig();
        flow().begin(null);
        drive(overlayRef.get(), ENTER);       // input
        drive(overlayRef.get(), DOWN, ENTER); // transcript ⇒ No
        drive(overlayRef.get(), ESC);         // summary 选择 Esc
        assertThat(messageTexts()).contains("Bug report cancelled");
    }

    @Test
    void cancelAtDeliveryChoice() {
        newRig();
        flow().begin(null);
        drive(overlayRef.get(), ENTER);       // input
        drive(overlayRef.get(), ENTER);       // transcript ⇒ Yes
        drive(overlayRef.get(), DOWN, DOWN, ENTER); // delivery ⇒ Cancel
        assertThat(messageTexts()).contains("Bug report cancelled");
    }

    @Test
    void cancelAtUploadFallback() throws Exception {
        newRig();
        var server = gateway(500, "{\"ok\":false,\"error\":\"boom\"}");
        try {
            flow(uploadTo(server), false).begin(null);
            drive(overlayRef.get(), ENTER);   // input
            drive(overlayRef.get(), ENTER);   // transcript ⇒ Yes
            drive(overlayRef.get(), ENTER);   // delivery ⇒ Upload Report
            awaitChoiceScreen("fallback 选择屏出现");
            drive(overlayRef.get(), DOWN, ENTER); // fallback ⇒ Cancel
            assertThat(messageTexts()).contains("Bug report cancelled");
        } finally {
            server.stop(0);
        }
    }

    // ── ZIP 全链 ────────────────────────────────────────────

    @Test
    void transcriptYesExportsZipAndRecordsCustomEntry() throws Exception {
        newRig();
        flow().begin("it broke");
        drive(overlayRef.get(), ENTER);       // input（hint 预填）
        drive(overlayRef.get(), ENTER);       // transcript ⇒ Yes
        drive(overlayRef.get(), DOWN, ENTER); // delivery ⇒ Export as Zip

        awaitText("Bug report exported to: ");
        var zips = java.nio.file.Files.list(tmp)
            .filter(p -> p.getFileName().toString().startsWith("pi-bug-report-")).toList();
        assertThat(zips).hasSize(1);
        var names = zipNames(zips.getFirst());
        assertThat(names).containsExactly(
            "report.json", "diagnostics.json", "session.jsonl");
        // report.json 三键钉形状：hint 预填进 metadata、session.included、cwd。
        var report = readZipEntry(zips.getFirst(), "report.json");
        assertThat(report).contains("\"hint\" : \"it broke\"");
        assertThat(report).contains("\"included\" : true");
        // 会话尾 pi.bug-report 条目（delivery=zip＋path）。
        var customs = session.accumulatedEntries().stream()
            .filter(com.pijava.agent.entry.Entry.Custom.class::isInstance)
            .map(com.pijava.agent.entry.Entry.Custom.class::cast).toList();
        assertThat(customs).hasSize(1);
        assertThat(customs.getFirst().customType()).isEqualTo("pi.bug-report");
        assertThat(customs.getFirst().data())
            .containsEntry("delivery", "zip")
            .containsEntry("hint", "it broke")
            .containsEntry("sessionIncluded", true);
    }

    @Test
    void transcriptNoWithSummaryAttachesSummaryMd() throws Exception {
        newRig();
        provider.enqueueText("# What went wrong\nIt broke.");
        flow().begin(null);
        drive(overlayRef.get(), ENTER);       // input（空 hint——合法）
        drive(overlayRef.get(), DOWN, ENTER); // transcript ⇒ No
        drive(overlayRef.get(), ENTER);       // summary ⇒ Yes, generate
        awaitTrue(() -> overlayRef.get() != null, "摘要后 delivery 选择屏出现");
        drive(overlayRef.get(), DOWN, ENTER); // delivery ⇒ Export as Zip

        awaitText("Bug report exported to: ");
        var zip = java.nio.file.Files.list(tmp)
            .filter(p -> p.getFileName().toString().startsWith("pi-bug-report-"))
            .findFirst().orElseThrow();
        assertThat(zipNames(zip)).containsExactly(
            "report.json", "diagnostics.json", "summary.md");
        assertThat(readZipEntry(zip, "summary.md")).contains("It broke.");
    }

    // ── upload 两臂 ─────────────────────────────────────────

    @Test
    void uploadSuccessRecordsUploadEntry() throws Exception {
        newRig();
        var server = gateway(200, "{\"ok\":true,\"bug_report\":{\"id\":\"br-42\"}}");
        try {
            flow(uploadTo(server), false).begin(null);
            drive(overlayRef.get(), ENTER);   // input
            drive(overlayRef.get(), ENTER);   // transcript ⇒ Yes
            drive(overlayRef.get(), ENTER);   // delivery ⇒ Upload Report
            awaitText("Bug report uploaded. Report ID: br-42");
            var customs = session.accumulatedEntries().stream()
                .filter(com.pijava.agent.entry.Entry.Custom.class::isInstance)
                .map(com.pijava.agent.entry.Entry.Custom.class::cast).toList();
            assertThat(customs).hasSize(1);
            assertThat(customs.getFirst().data()).containsEntry("delivery", "upload");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadFailureFallsBackToZip() throws Exception {
        newRig();
        var server = gateway(500, "{\"ok\":false,\"description\":\"quota\"}");
        try {
            flow(uploadTo(server), false).begin(null);
            drive(overlayRef.get(), ENTER);   // input
            drive(overlayRef.get(), ENTER);   // transcript ⇒ Yes
            drive(overlayRef.get(), ENTER);   // delivery ⇒ Upload Report
            awaitChoiceScreen("fallback 选择屏出现");
            drive(overlayRef.get(), ENTER);   // fallback ⇒ Export as Zip
            awaitText("Bug report exported to: ");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void offlineGateBlocksUploadWithVerbatimError() {
        newRig();
        flow(new BugReportUpload(HttpClient.newHttpClient(), "http://127.0.0.1:1"), true)
            .begin(null);
        drive(overlayRef.get(), ENTER);       // input
        drive(overlayRef.get(), ENTER);       // transcript ⇒ Yes
        drive(overlayRef.get(), ENTER);       // delivery ⇒ Upload Report
        assertThat(messageTexts()).anyMatch(t -> t.contains(
            "Uploading bug reports requires online mode. Use Export as Zip instead."));
    }

    // ── summary 失败两臂 ────────────────────────────────────

    @Test
    void summaryFailureShowsError() {
        newRig();
        provider.enqueueError("model exploded");
        flow().begin(null);
        drive(overlayRef.get(), ENTER);       // input
        drive(overlayRef.get(), DOWN, ENTER); // transcript ⇒ No
        drive(overlayRef.get(), ENTER);       // summary ⇒ Yes
        drive(overlayRef.get(), DOWN, ENTER); // delivery ⇒ Export as Zip ⇒ 摘要开跑
        awaitText("Failed to write bug report summary: ");
    }

    @Test
    void summaryAbortCancels() throws Exception {
        newRig();
        provider.latchNext();                 // 摘要调用闩住 ⇒ loader 可见
        flow().begin(null);
        drive(overlayRef.get(), ENTER);       // input
        drive(overlayRef.get(), DOWN, ENTER); // transcript ⇒ No
        drive(overlayRef.get(), ENTER);       // summary ⇒ Yes
        drive(overlayRef.get(), DOWN, ENTER); // delivery ⇒ Export as Zip ⇒ loader
        awaitTrue(() -> overlayRef.get() != null, "loader 屏出现");
        drive(overlayRef.get(), ESC);         // Esc ⇒ abort（interrupt worker）
        provider.release();
        awaitText("Bug report cancelled");
    }

    // ── helpers ─────────────────────────────────────────────

    /** 等 fallback/delivery 选择屏（与 loader 区分——loader 也是 overlay）。 */
    private void awaitChoiceScreen(String what) {
        awaitTrue(() -> overlayRef.get()
                instanceof com.pijava.tui.screen.BugReportChoiceScreen, what);
    }

    private void awaitTrue(BooleanSupplier cond, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            dispatcher.drain();
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(what + "：等待超时——聊天区：" + messageTexts());
    }

    private static List<String> zipNames(Path zip) throws Exception {
        var names = new java.util.ArrayList<String>();
        try (var zis = new java.util.zip.ZipInputStream(
                java.nio.file.Files.newInputStream(zip))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private static String readZipEntry(Path zip, String name) throws Exception {
        try (var zis = new java.util.zip.ZipInputStream(
                java.nio.file.Files.newInputStream(zip))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                if (e.getName().equals(name)) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("zip 中无 " + name);
    }
}
