package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.core.StreamingBehavior;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionContext.SendUserMessageOptions;
import com.pijava.coding.agent.extension.ExtensionExec;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/git-merge-and-resolve.ts:1-116} 移植（docs/55 §2.4）——
 * agent_end 后检测/发起 merge，冲突则发 followUp 报告。git 经 {@link GitExecutor}
 * 假执行断言命令序列；冲突报告与区间为纯函数逐字断言。
 */
public final class GitMergeAndResolveExtension implements PiExtension {

    record ConflictBlock(String file, int startLine, int separatorLine, int endLine) {}

    /** 注入式文件读取（冲突块解析用），默认真实读取，测试 fake。 */
    interface FileReader {
        List<String> readLines(String path);
    }

    private GitExecutor git;
    private FileReader fileReader;

    @Override
    public String name() {
        return "git-merge-and-resolve";
    }

    @Override
    public void register(ExtensionContext ctx) {
        this.git = (cmd, args) -> ctx.exec(cmd, args, null);
        this.fileReader = path -> {
            try {
                return java.nio.file.Files.readAllLines(java.nio.file.Path.of(path));
            } catch (Exception ignored) {
                return List.of();
            }
        };
        ctx.on(ExtensionEventSpec.AGENT_END, (event, c) -> {
            onAgentEnd(c);
            return null;
        });
    }

    void git(GitExecutor git) {
        this.git = git;
    }

    void fileReader(FileReader fileReader) {
        this.fileReader = fileReader;
    }

    /** pi L56-60：区间格式。 */
    static String formatRange(int start, int end) {
        if (start > end) {
            return "empty";
        }
        if (start == end) {
            return String.valueOf(start);
        }
        return start + "-" + end;
    }

    /** pi L62-71：冲突报告（逐字）。 */
    static String formatConflicts(String ref, List<ConflictBlock> blocks) {
        var lines = new ArrayList<String>();
        lines.add("Merged " + ref + " with conflicts:");
        lines.add("");
        for (var b : blocks) {
            var ours = formatRange(b.startLine() + 1, b.separatorLine() - 1);
            var theirs = formatRange(b.separatorLine() + 1, b.endLine() - 1);
            lines.add("  " + b.file() + ":" + b.startLine() + "-" + b.endLine()
                + " (ours " + ours + ", theirs " + theirs + ")");
        }
        lines.add("");
        lines.add("Resolve these conflicts.");
        return String.join("\n", lines);
    }

    /** pi L27-54：冲突标记状态机解析。 */
    static List<ConflictBlock> parseConflictBlocks(String file, List<String> lines) {
        var blocks = new ArrayList<ConflictBlock>();
        Integer blockStart = null;
        Integer separator = null;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            int lineNo = i + 1;
            try {
                if (line.startsWith("<<<<<<<")) {
                    blockStart = lineNo;
                    separator = null;
                } else if (line.startsWith("=======") && blockStart != null) {
                    separator = lineNo;
                } else if (line.startsWith(">>>>>>>") && blockStart != null && separator != null) {
                    blocks.add(new ConflictBlock(file, blockStart, separator, lineNo));
                    blockStart = null;
                    separator = null;
                }
            } catch (Exception ignored) {
            }
        }
        return blocks;
    }

    /** pi L74-114：agent_end 编排。 */
    void onAgentEnd(ExtensionContext ctx) {
        if (git.exec("git", List.of("rev-parse", "--git-dir")).code() != 0) {
            return; // 非 git 仓库
        }
        var ref = "MERGE_HEAD";
        if (git.exec("git", List.of("rev-parse", "MERGE_HEAD")).code() != 0) {
            // 当前不在 merge 中，尝试新 merge
            if (!git.exec("git", List.of("status", "--porcelain")).stdout().trim().isEmpty()) {
                return; // 工作树不干净
            }
            var upstream = git.exec("git", List.of("rev-parse", "--abbrev-ref",
                "--symbolic-full-name", "@{u}"));
            if (upstream.code() != 0) {
                return; // 无 upstream
            }
            ref = upstream.stdout().trim();
            var remote = ref.split("/")[0];
            ctx.ui().notify("git-merge-and-resolve: fetching " + remote + ", merging " + ref,
                NotifyType.INFO);
            var fetch = git.exec("git", List.of("fetch", remote));
            if (fetch.code() != 0) {
                ctx.ui().notify("git-merge-and-resolve: fetch failed: " + fetch.stderr().trim(),
                    NotifyType.WARNING);
                return;
            }
            if (git.exec("git", List.of("merge", "--no-ff", ref)).code() == 0) {
                return; // 干净合并
            }
        }
        // 冲突分支
        var unmerged = git.exec("git", List.of("diff", "--name-only", "--diff-filter=U"));
        if (unmerged.code() != 0 || unmerged.stdout().trim().isEmpty()) {
            return;
        }
        var blocks = new ArrayList<ConflictBlock>();
        for (var file : unmerged.stdout().trim().lines().toList()) {
            blocks.addAll(parseConflictBlocks(file, fileReader.readLines(
                ctx.cwd() + java.io.File.separator + file)));
        }
        if (blocks.isEmpty()) {
            return;
        }
        ctx.sendUserMessage(formatConflicts(ref, blocks),
            new SendUserMessageOptions(StreamingBehavior.FOLLOW_UP, null));
    }
}
