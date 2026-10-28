package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionExec;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.InputEventResult;

/**
 * pi {@code examples/extensions/inline-bash.ts:19-94} 移植（docs/40 P9）——
 * 提示词内联 {@code !{cmd}} 展开为命令输出；整行 {@code !cmd} 不碰（保留既有
 * bash 行为）；30s 超时；非零退出＋stderr ⇒ 替换为 stderr 文本；exec 异常 ⇒
 * {@code [error: <msg>]}；有 UI 发汇总通知。
 *
 * <p>平台依赖（窄差 D174）：固定 {@code bash -c}（pi 同），Windows 需 Git Bash 在
 * PATH。JS 正则 {@code g} 全局态（test/exec 推进 lastIndex）⇒ java Matcher 无全局态，
 * 先全量 find 再逐个执行（语义等价）。</p>
 */
public final class InlineBashExtension implements PiExtension {

    /** pi 的 {@code /!\{([^}]+)\}/g} 逐字。 */
    static final Pattern PATTERN = Pattern.compile("!\\{([^}]+)\\}");

    /** pi 的 TIMEOUT_MS。 */
    static final long TIMEOUT_MS = 30000;

    /** pi 的 {@code expansions} 条目（{@code {command, output, error?}}）。 */
    record Expansion(String command, String output, String error) {}

    @Override
    public String name() {
        return "inline-bash";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.INPUT, (event, c) -> {
            var text = event.text();

            // Don't process if it's a whole-line bash command (starts with !)
            // This preserves the existing !command behavior
            var strippedLeading = text.stripLeading();
            if (strippedLeading.startsWith("!") && !strippedLeading.startsWith("!{")) {
                return new InputEventResult.Continue();
            }

            // Check if there are any inline bash patterns（java Matcher 无 lastIndex 态）
            var matcher = PATTERN.matcher(text);
            if (!matcher.find()) {
                return new InputEventResult.Continue();
            }

            // Find all matches first (to avoid issues with replacing while iterating)
            var matches = new ArrayList<String[]>();
            matcher.reset();
            while (matcher.find()) {
                matches.add(new String[] {matcher.group(), matcher.group(1)});
            }

            // Execute each command and collect results
            var result = text;
            var expansions = new ArrayList<Expansion>();
            for (var match : matches) {
                var full = match[0];
                var command = match[1];
                try {
                    var bashResult = c.exec("bash", List.of("-c", command),
                        new ExtensionExec.ExecOptions(null, TIMEOUT_MS, null));

                    // JS 空串假值：stdout || stderr || ""
                    var output = !bashResult.stdout().isEmpty() ? bashResult.stdout()
                        : bashResult.stderr();
                    var trimmed = output.trim();

                    if (bashResult.code() != 0 && !bashResult.stderr().isEmpty()) {
                        expansions.add(new Expansion(command, trimmed,
                            "exit code " + bashResult.code()));
                    } else {
                        expansions.add(new Expansion(command, trimmed, null));
                    }

                    result = replaceFirstLiteral(result, full, trimmed);
                } catch (RuntimeException e) {
                    var errorMsg = e.getMessage() == null ? e.toString() : e.getMessage();
                    expansions.add(new Expansion(command, "", errorMsg));
                    result = replaceFirstLiteral(result, full, "[error: " + errorMsg + "]");
                }
            }

            // Show what was expanded (if UI available)
            if (c.hasUi() && !expansions.isEmpty()) {
                var summary = new StringBuilder();
                for (var expansion : expansions) {
                    if (summary.length() > 0) {
                        summary.append('\n');
                    }
                    var status = expansion.error() != null ? " (" + expansion.error() + ")" : "";
                    var preview = expansion.output().length() > 50
                        ? expansion.output().substring(0, 50) + "..." : expansion.output();
                    summary.append("!{").append(expansion.command()).append('}')
                        .append(status).append(" -> \"").append(preview).append('"');
                }
                c.ui().notify("Expanded " + expansions.size() + " inline command(s):\n"
                    + summary, NotifyType.INFO);
            }

            return new InputEventResult.Transform(result, event.images());
        });
    }

    /** JS {@code String.replace(searchString, ...)} 的首个匹配<b>字面</b>替换对位。 */
    static String replaceFirstLiteral(String text, String literal, String replacement) {
        var index = text.indexOf(literal);
        if (index < 0) {
            return text;
        }
        return text.substring(0, index) + replacement + text.substring(index + literal.length());
    }
}
