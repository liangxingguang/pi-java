package com.pijava.tui.app;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.SessionBranchSerializer;
import com.pijava.coding.agent.core.bugreport.BugReport;
import com.pijava.coding.agent.core.bugreport.BugReportCollect;
import com.pijava.coding.agent.core.bugreport.BugReportUpload;
import com.pijava.coding.agent.core.crash.CrashLog;
import com.pijava.coding.agent.core.util.ZipArchives;
import com.pijava.tui.screen.BugReportChoiceScreen;
import com.pijava.tui.screen.BugReportInputScreen;
import com.pijava.tui.screen.BugReportLoaderScreen;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TuiEventDispatcher;

/**
 * {@code /bug} 故障上报流（批 15 T7，pi {@code modes/interactive/bug-report.ts}
 * 的 {@code reportBug} 逐段对位）：描述输入 ⇒ 转录选择 ⇒（不共享时）摘要选择
 * ⇒ 递交方式 ⇒ 可选摘要 ⇒ 组包 ⇒ 上传（失败回落 ZIP）或导出 ⇒ 会话尾记录。
 *
 * <p>编排同 {@link TreeNavigationFlow}：overlay 一屏一换（pi showOverlay/
 * restoreEditor 对位），阻塞工作（摘要/上传）跑 worker 虚拟线程，UI 写经
 * dispatcher 回渲染线程。全部文案【用户可见】逐字。裁决⑩：上传恒匿名
 * （token 缝预留在 {@link BugReportUpload}）。</p>
 */
public final class BugReportFlow {

    /** pi {@code DISCLAIMER} 逐字。 */
    static final String DISCLAIMER =
        "This report goes to the Pi developers (Earendil) and is not shared publicly."
            + " It includes your pi version, operating system, the current model and"
            + " provider configuration (without API keys), loaded extensions, settings,"
            + " and provider error diagnostics from this session.";
    /** pi {@code TRANSCRIPT_NOTE} 逐字。 */
    static final String TRANSCRIPT_NOTE =
        "The transcript contains your messages, model output, tool calls and their"
            + " results, including file contents and command output read during this session.";

    private static final String YES_TRANSCRIPT = "Yes, include the transcript";
    private static final String YES_SUMMARY = "Yes, generate a summary";
    private static final String NO = "No";
    private static final String UPLOAD = "Upload Report";
    private static final String EXPORT_ZIP = "Export as Zip";
    private static final String CANCEL = "Cancel";

    private final AgentSession session;
    private final ChatScreen chatScreen;
    private final TuiEventDispatcher dispatcher;
    private final Consumer<ScreenOverlay> openOverlay;
    private final Supplier<BugReportUpload> uploader;
    private final BooleanSupplier offline;
    private final Supplier<Path> zipDir;

    /** 生产构造：真上传件、PI_OFFLINE 环境门（pi 只查 env）、cwd 落 ZIP。 */
    public BugReportFlow(AgentSession session, ChatScreen chatScreen,
                         TuiEventDispatcher dispatcher,
                         Consumer<ScreenOverlay> openOverlay) {
        this(session, chatScreen, dispatcher, openOverlay,
            BugReportUpload::new,
            // pi 的门是 process.env.PI_OFFLINE（JS 真值＝非空串）。
            () -> {
                var env = System.getenv("PI_OFFLINE");
                return env != null && !env.isBlank();
            },
            () -> Path.of(System.getProperty("user.dir")));
    }

    /** 测试缝：上传件／离线门／ZIP 落盘目录注入。 */
    BugReportFlow(AgentSession session, ChatScreen chatScreen,
                  TuiEventDispatcher dispatcher, Consumer<ScreenOverlay> openOverlay,
                  Supplier<BugReportUpload> uploader, BooleanSupplier offline,
                  Supplier<Path> zipDir) {
        this.session = session;
        this.chatScreen = chatScreen;
        this.dispatcher = dispatcher;
        this.openOverlay = openOverlay;
        this.uploader = uploader;
        this.offline = offline;
        this.zipDir = zipDir;
    }

    /** pi {@code reportBug(context, initialHint)} 入口。 */
    public void begin(String initialHint) {
        openOverlay.accept(new BugReportInputScreen("Report a bug",
            DISCLAIMER + "\n\nWhat went wrong? (optional)", initialHint,
            hint -> {
                if (hint == null) {
                    cancelled();
                    return;
                }
                askTranscript(hint);
            }));
    }

    private void askTranscript(String hint) {
        openOverlay.accept(new BugReportChoiceScreen("Include the session transcript?",
            List.of(YES_TRANSCRIPT, NO), TRANSCRIPT_NOTE,
            choice -> {
                if (choice == null) {
                    cancelled();
                    return;
                }
                if (NO.equals(choice)) {
                    askSummary(hint);
                } else {
                    askDelivery(hint, true, false);
                }
            }));
    }

    private void askSummary(String hint) {
        openOverlay.accept(new BugReportChoiceScreen(
            "Attach a summary written by " + modelName() + " instead?",
            List.of(YES_SUMMARY, NO),
            "The transcript is sent to " + providerName() + " with your credentials and"
                + " tokens. Only the generated summary is attached; the transcript stays"
                + " on your machine.",
            choice -> {
                if (choice == null) {
                    cancelled();
                    return;
                }
                askDelivery(hint, false, !NO.equals(choice));
            }));
    }

    private void askDelivery(String hint, boolean includeSession, boolean includeSummary) {
        var description = hint.trim();
        openOverlay.accept(new BugReportChoiceScreen("Bug report",
            List.of(UPLOAD, EXPORT_ZIP, CANCEL),
            "Description: " + (description.isEmpty() ? "none" : description)
                + "\nTranscript: " + (includeSession ? "included" : "not included")
                + "\nSummary: " + (includeSummary
                    ? "written by " + modelName() : "none")
                + "\n\nUpload sends the report to " + gatewayHost()
                + ". Export writes a zip archive to the current directory instead.",
            choice -> {
                if (choice == null || CANCEL.equals(choice)) {
                    cancelled();
                    return;
                }
                var options = new Options(description.isEmpty() ? null : description,
                    includeSession, includeSummary, UPLOAD.equals(choice));
                // pi：upload 臂的离线门前检（摘要之前）。
                if (options.upload() && offline.getAsBoolean()) {
                    chatScreen.showError("Uploading bug reports requires online mode."
                        + " Use Export as Zip instead.");
                    return;
                }
                if (options.includeSummary()) {
                    runSummary(options);
                } else {
                    buildAndDeliver(options, null);
                }
            }));
    }

    private void runSummary(Options options) {
        var loader = new BugReportLoaderScreen(
            "Writing summary with " + modelName() + "...", () -> { });
        openOverlay.accept(loader);
        var worker = Thread.startVirtualThread(() -> {
            String summary;
            try {
                summary = session.summarizeForBugReport(options.hint());
            } catch (Exception e) {
                dispatcher.dispatch(() -> {
                    openOverlay.accept(null);
                    if (loader.aborted()) {
                        cancelled();
                    } else {
                        chatScreen.showError("Failed to write bug report summary: "
                            + messageOf(e));
                    }
                });
                return;
            }
            dispatcher.dispatch(() -> {
                openOverlay.accept(null);
                if (loader.aborted()) {
                    cancelled();
                    return;
                }
                buildAndDeliver(options, summary);
            });
        });
        // loader 的 Esc ⇒ interrupt worker（pi signal.abort 对位）。
        loaderSetAbort(loader, worker);
    }

    private void buildAndDeliver(Options options, String summary) {
        BugReport.Bundle bundle;
        try {
            bundle = buildBundle(options, summary);
        } catch (Exception e) {
            chatScreen.showError("Failed to build bug report: " + messageOf(e));
            return;
        }
        if (options.upload()) {
            runUpload(bundle);
        } else {
            exportZip(bundle);
        }
    }

    private void runUpload(BugReport.Bundle bundle) {
        var loader = new BugReportLoaderScreen("Uploading bug report...", () -> { });
        openOverlay.accept(loader);
        var worker = Thread.startVirtualThread(() -> {
            try {
                // 裁决⑩：恒匿名（java 无 Radius provider；token 缝预留）。
                var result = uploader.get().upload(bundle, null);
                dispatcher.dispatch(() -> {
                    openOverlay.accept(null);
                    recordInSession(bundle, "upload", null);
                    chatScreen.appendSystemText(
                        "Bug report uploaded. Report ID: " + result.id());
                });
            } catch (Exception e) {
                dispatcher.dispatch(() -> {
                    openOverlay.accept(null);
                    if (loader.aborted()) {
                        cancelled();
                        return;
                    }
                    askUploadFallback(bundle, messageOf(e));
                });
            }
        });
        loaderSetAbort(loader, worker);
    }

    private void askUploadFallback(BugReport.Bundle bundle, String failure) {
        openOverlay.accept(new BugReportChoiceScreen("Upload failed",
            List.of(EXPORT_ZIP, CANCEL),
            failure + "\n\nExport the report as a zip archive instead?",
            choice -> {
                if (!EXPORT_ZIP.equals(choice)) {
                    cancelled();
                    return;
                }
                exportZip(bundle);
            }));
    }

    private void exportZip(BugReport.Bundle bundle) {
        var archivePath = zipDir.get().resolve(
            BugReport.archiveFileName(bundle.metadata().path("id").textValue()));
        try {
            ZipArchives.write(archivePath, BugReport.files(bundle));
        } catch (Exception e) {
            chatScreen.showError("Failed to write bug report: " + messageOf(e));
            return;
        }
        recordInSession(bundle, "zip", archivePath.toString());
        chatScreen.appendSystemText("Bug report exported to: " + archivePath
            + "\nReport ID: " + bundle.metadata().path("id").textValue());
    }

    /**
     * pi {@code recordInSession}：会话尾 {@code pi.bug-report} 条目；
     * 诊断带崩溃 ⇒ 清 crash log（已随包上报）。
     */
    private void recordInSession(BugReport.Bundle bundle, String delivery, String path) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("id", bundle.metadata().path("id").textValue());
        data.put("createdAt", bundle.metadata().path("createdAt").textValue());
        if (bundle.metadata().path("hint").isTextual()) {
            data.put("hint", bundle.metadata().path("hint").textValue());
        }
        data.put("sessionIncluded", bundle.metadata().path("session").path("included").booleanValue());
        data.put("summaryIncluded",
            bundle.metadata().path("session").path("summaryIncluded").booleanValue());
        data.put("delivery", delivery);
        if (path != null) {
            data.put("path", path);
        }
        session.appendCustomEntry(BugReport.CUSTOM_ENTRY_TYPE, data);
        if (!bundle.diagnostics().path("crashes").isEmpty()) {
            CrashLog.clear(CrashLog.crashLogPath());
        }
    }

    /** pi {@code buildBundle}：metadata＋diagnostics（含 crash log）＋可选转录/摘要。 */
    private BugReport.Bundle buildBundle(Options options, String summary) {
        var settings = session.services().settings();
        var metadata = BugReportCollect.collectMetadata(
            new BugReportCollect.CollectOptions(
                null, options.hint(), session.sessionId(), session.cwd(),
                options.includeSession(), summary != null,
                session.accumulatedMessages().size(),
                currentModelInfo(), currentProvider(), authTypes(),
                thinkingLevelLabel(), installedExtensions(),
                settings.global(), settings.project()));
        var diagnostics = BugReportCollect.collectDiagnostics(
            session.accumulatedEntries(), session.sessionId(),
            CrashLog.read(CrashLog.crashLogPath()));
        return new BugReport.Bundle(metadata, diagnostics,
            options.includeSession() ? SessionBranchSerializer.serialize(session) : null,
            summary);
    }

    private void cancelled() {
        chatScreen.appendSystemText("Bug report cancelled");
    }

    // ── 装配件（buildBundle 的操作数）─────────────────────────

    private com.pijava.ai.catalog.ModelInfo currentModelInfo() {
        var model = session.harness().getModel();
        return model == null ? null : session.modelCatalog().find(model).orElse(null);
    }

    private com.pijava.ai.provider.Provider currentProvider() {
        var model = session.harness().getModel();
        return model == null ? null
            : session.services().providers().get(model.provider()).orElse(null);
    }

    /** 裁决⑧：凭证存储现读（api_key／oauth 有序，pi authTypes 序）。 */
    private List<String> authTypes() {
        var model = session.harness().getModel();
        if (model == null) {
            return List.of();
        }
        var types = new java.util.ArrayList<String>();
        if (new FileCredentialStore().resolveApiKey(model.provider()).isPresent()) {
            types.add("api_key");
        }
        if (new OAuthCredentialStore().resolve(model.provider()).isPresent()) {
            types.add("oauth");
        }
        return types;
    }

    private String thinkingLevelLabel() {
        var level = session.harness().getThinkingLevel();
        return level == null ? null : level.label();
    }

    private List<com.pijava.coding.agent.extension.InstalledExtension> installedExtensions() {
        var all = new java.util.ArrayList<com.pijava.coding.agent.extension.InstalledExtension>();
        try {
            all.addAll(com.pijava.coding.agent.extension.ExtensionPackageManager
                .global().list());
            all.addAll(com.pijava.coding.agent.extension.ExtensionPackageManager
                .project().list());
        } catch (Exception ignored) {
            // best-effort（上报自身不能再崩）。
        }
        return all;
    }

    // ── 文案件 ──────────────────────────────────────────────

    /** pi {@code context.session.model?.name ?? "the current model"}。 */
    private String modelName() {
        var info = currentModelInfo();
        return info != null ? info.displayName() : "the current model";
    }

    /** pi {@code model?.provider ?? "your provider"}。 */
    private String providerName() {
        var model = session.harness().getModel();
        return model != null ? model.provider() : "your provider";
    }

    /** pi {@code new URL(getRadiusGatewayUrl()).host}。 */
    private static String gatewayHost() {
        try {
            var host = java.net.URI.create(BugReportUpload.gatewayUrl()).getHost();
            return host != null ? host : BugReportUpload.gatewayUrl();
        } catch (Exception e) {
            return BugReportUpload.gatewayUrl();
        }
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? String.valueOf(e) : e.getMessage();
    }

    /** loader 的 Esc ⇒ worker interrupt（pi {@code signal.abort()} 对位）。 */
    private void loaderSetAbort(BugReportLoaderScreen loader, Thread worker) {
        loader.setOnAbort(worker::interrupt);
    }

    /** pi {@code BugReportOptions}（hint 已 trim；空 ⇒ null ≙ undefined）。 */
    private record Options(String hint, boolean includeSession,
                           boolean includeSummary, boolean upload) {}
}
