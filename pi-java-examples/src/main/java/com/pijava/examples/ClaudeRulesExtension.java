package com.pijava.examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;

/**
 * pi {@code examples/extensions/claude-rules.ts:27-86} 移植（docs/40 P11）——
 * session_start 扫 {@code <cwd>/.claude/rules} 递归 .md；无规则 ⇒ before_agent_start
 * 不改 prompt；有规则 ⇒ 追加「## Project Rules」段（逐字模板）＋通知计数。
 *
 * <p>pi {@code readdirSync} 序＝OS 序（不排序）⇒ java {@code Files.list} 同为 OS 序；
 * 相对路径拼 {@code /} 分隔（逐字，不用 File.separator）。</p>
 */
public final class ClaudeRulesExtension implements PiExtension {

    /** pi 的模块级 {@code ruleFiles} ⇒ 实例字段。 */
    private volatile List<String> ruleFiles = List.of();

    @Override
    public String name() {
        return "claude-rules";
    }

    @Override
    public void register(ExtensionContext ctx) {
        // Scan for rules on session start
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            var rulesDir = Path.of(c.cwd(), ".claude", "rules");
            ruleFiles = findMarkdownFiles(rulesDir, "");
            if (!ruleFiles.isEmpty()) {
                c.ui().notify("Found " + ruleFiles.size() + " rule(s) in .claude/rules/",
                    NotifyType.INFO);
            }
            return null;
        });

        // Append available rules to system prompt
        ctx.on(ExtensionEventSpec.BEFORE_AGENT_START, (event, c) -> {
            if (ruleFiles.isEmpty()) {
                return null;
            }
            var rulesList = ruleFiles.stream()
                .map(f -> "- .claude/rules/" + f)
                .collect(Collectors.joining("\n"));
            return new BeforeAgentStartEventResult(null,
                event.systemPrompt() + "\n\n## Project Rules\n\n"
                    + "The following project rules are available in .claude/rules/:\n\n"
                    + rulesList + "\n\n"
                    + "When working on tasks related to these rules, use the read tool"
                    + " to load the relevant rule files for guidance.\n");
        });
    }

    /** pi {@code findMarkdownFiles} 递归（目录先探、.md 收尾；OS 序）。 */
    static List<String> findMarkdownFiles(Path dir, String basePath) {
        var results = new ArrayList<String>();
        if (!Files.exists(dir)) {
            return results;
        }
        try (var stream = Files.list(dir)) {
            stream.forEach(entry -> {
                var name = entry.getFileName().toString();
                var relativePath = basePath.isEmpty() ? name : basePath + "/" + name;
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    results.addAll(findMarkdownFiles(entry, relativePath));
                } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && name.endsWith(".md")) {
                    results.add(relativePath);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return results;
    }
}
