package com.pijava.coding.agent;

import java.util.ServiceLoader;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.cli.HelpText;
import com.pijava.coding.agent.cli.ListModelsCommand;
import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.core.Logging;
import com.pijava.coding.agent.core.SessionResolutionExit;
import com.pijava.coding.agent.export.HtmlExporter;
import com.pijava.coding.agent.modes.PrintMode;
import com.pijava.coding.agent.rpc.RpcMode;
import com.pijava.coding.agent.spi.TuiEntryPoint;
import com.pijava.coding.agent.spi.WebEntryPoint;
import com.pijava.coding.agent.subcommand.SubcommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code pi-java} CLI entry point (Phase 3 design §9.5).
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {}

    /** CLI entry point. */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Run the CLI and return the process exit code (testable). */
    public static int run(String[] args) {
        var subcommand = SubcommandHandler.matches(args);
        if (subcommand != null) {
            return SubcommandHandler.dispatch(subcommand, args);
        }

        var parsed = ArgsParser.parse(args);
        // tui=true detaches the CONSOLE appender (alternate-screen safety);
        // only the interactive TUI actually needs that — web/json/rpc keep it.
        boolean tuiMode = !parsed.print()
            && (parsed.mode() == null || "text".equals(parsed.mode()));
        Logging.configure(parsed.debug(), tuiMode);
        LOG.debug("CLI args parsed: mode={} print={} debug={}",
            parsed.mode(), parsed.print(), parsed.debug());
        if (hasErrors(parsed)) {
            printDiagnostics(parsed);
            return 2;
        }
        try {
            return dispatch(parsed);
        } catch (SessionResolutionExit e) {
            // 批 14（docs/25 T6c，裁决②）：会话解析的干净结局——pi 的
            // console.error("Error: <msg>") exit 1 / console.log(<msg>) exit 0 对位。
            if (e.errorPrefix()) {
                System.err.println("Error: " + e.getMessage());
            } else {
                System.out.println(e.getMessage());
            }
            return e.code();
        }
    }

    private static int dispatch(Args parsed) {
        if (parsed.help()) {
            // 批 16（T1e，裁决⑩）：pi 的 --help 在完整运行时（含扩展加载）后打印
            //（main.ts:877-884）——轻量装载收集扩展 flag，追加 "Extension CLI Flags:"
            // 段（无 flag ⇒ 段落消失，args.ts:273-283 逐字）。
            System.out.print(HelpText.text(
                com.pijava.coding.agent.extension.ExtensionFlagSupport.collectForHelp(parsed)));
            return 0;
        }
        if (parsed.version()) {
            System.out.println(Version.VERSION);
            return 0;
        }
        if (parsed.listModels() != null) {
            return ListModelsCommand.run(parsed.listModels());
        }
        if (parsed.mode() != null && "rpc".equals(parsed.mode())) {
            initThemeForHeadless(parsed);
            return RpcMode.run(System.in, System.out, parsed);
        }
        if (parsed.mode() != null && "web".equals(parsed.mode())) {
            var web = ServiceLoader.load(WebEntryPoint.class).findFirst();
            if (web.isEmpty()) {
                System.err.println(
                    "error: web mode requires pi-java-web on the classpath");
                return 1;
            }
            return web.get().runWeb(parsed);
        }
        if (parsed.mode() != null && "json".equals(parsed.mode())) {
            initThemeForHeadless(parsed);
            return PrintMode.runJson(parsed.messages(), parsed);
        }
        if (parsed.mode() != null && !"text".equals(parsed.mode())) {
            System.err.println("error: --mode " + parsed.mode()
                + " is not a supported mode");
            return 2;
        }
        if (parsed.export() != null) {
            // 批 21（T9/T12）：导出前初始化主题（pi main.ts:905 对位）——
            // HTML 导出的 CSS 变量由活跃主题解析（T12）。
            initThemeForHeadless(parsed);
            // pi main.ts:639-651（T13）：第二位置参数是输出路径；文案逐字。
            try {
                var input = java.nio.file.Path.of(parsed.export());
                var exporter = new HtmlExporter();
                var out = parsed.messages().isEmpty()
                    ? exporter.export(input)
                    : exporter.export(input, java.nio.file.Path.of(parsed.messages().getFirst()));
                System.out.println("Exported to: " + out);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + (e.getMessage() != null
                    ? e.getMessage() : "Failed to export session"));
                return 1;
            }
        }
        if (parsed.print()) {
            initThemeForHeadless(parsed);
            return PrintMode.run(parsed.messages(), parsed);
        }

        // Interactive mode (default): discover the TUI via ServiceLoader.
        var entry = ServiceLoader.load(TuiEntryPoint.class).findFirst();
        if (entry.isEmpty()) {
            System.err.println(
                "error: interactive mode requires pi-java-tui on the classpath");
            return 1;
        }
        return entry.get().runInteractive(parsed);
    }

    /**
     * 批 21（T9，docs/32）：非交互模式的主题初始化（pi main.ts:905
     * {@code initTheme(settingsManager.getTheme(), false)} 逐字对位——无 watcher；
     * {@code getTheme()} 对对形返回 null ⇒ initTheme 内落 system，pi 同）。
     * 用途：HTML 导出主题变量（T12）与扩展 {@code getThemeByName}。
     * {@code --use-theme} 在非交互臂作本次覆盖（pi main.ts:681-683 的
     * applyOverrides 在非交互模式下同样生效——经此直接进 initTheme）。
     */
    private static void initThemeForHeadless(Args parsed) {
        try {
            var settings = com.pijava.coding.agent.core.SettingsManager.load(null, null, null);
            var name = parsed.useTheme() != null
                ? com.pijava.coding.agent.theme.ThemeSettings.resolveThemeSetting(
                    parsed.useTheme(), com.pijava.coding.agent.theme.Themes.terminalTheme())
                : settings.accessors().getTheme();
            com.pijava.coding.agent.theme.Themes.initTheme(name, false);
        } catch (RuntimeException e) {
            // 主题初始化失败不阻断 headless 主流程（pi initTheme 内部已静默回落，
            // 此 catch 防 settings 读取等外围失败）。
        }
    }

    private static boolean hasErrors(Args args) {
        return args.diagnostics().stream()
            .anyMatch(d -> "error".equals(d.type()));
    }

    private static void printDiagnostics(Args args) {
        for (var diagnostic : args.diagnostics()) {
            System.err.println(diagnostic.type() + ": " + diagnostic.message());
        }
    }
}
