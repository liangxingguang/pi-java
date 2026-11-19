package com.pijava.examples;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/git-checkpoint.ts:1-53} 移植（docs/52 §2.9）——
 * 每个 turn 用 git stash 建 checkpoint，/fork 时询问是否恢复。执行经 {@link GitExecutor} 接缝。
 */
public final class GitCheckpointExtension implements PiExtension {

    private final Map<String, String> checkpoints = new LinkedHashMap<>();
    private String currentEntryId;
    private GitExecutor git;

    @Override
    public String name() {
        return "git-checkpoint";
    }

    @Override
    public void register(ExtensionContext ctx) {
        this.git = (cmd, args) -> ctx.exec(cmd, args, null);
        ctx.on(ExtensionEventSpec.TOOL_RESULT, (event, c) -> {
            onToolResult(c);
            return null;
        });
        ctx.on(ExtensionEventSpec.TURN_START, (event, c) -> {
            onTurnStart();
            return null;
        });
        ctx.on(ExtensionEventSpec.SESSION_BEFORE_FORK, (event, c) -> {
            onBeforeFork(((ExtensionEvent.SessionBeforeForkEvent) event).entryId(), c);
            return null;
        });
        ctx.on(ExtensionEventSpec.AGENT_SETTLED, (event, c) -> {
            checkpoints.clear();
            return null;
        });
    }

    void git(GitExecutor git) {
        this.git = git;
    }

    /** tool_result：记录当前 leaf entry id。 */
    void onToolResult(ExtensionContext ctx) {
        var leaf = ctx.sessionManager().getLeafEntry();
        if (leaf != null) {
            currentEntryId = leaf.id();
        }
    }

    /** turn_start：git stash create 并关联当前 entry。 */
    void onTurnStart() {
        var ref = git.exec("git", List.of("stash", "create")).stdout().trim();
        if (!ref.isEmpty() && currentEntryId != null) {
            checkpoints.put(currentEntryId, ref);
        }
    }

    /** session_before_fork：有 checkpoint 且交互时询问是否恢复。 */
    void onBeforeFork(String entryId, ExtensionContext ctx) {
        var ref = checkpoints.get(entryId);
        if (ref == null || !ctx.hasUi()) {
            return;
        }
        var choice = ctx.ui().select("Restore code state?",
            List.of("Yes, restore code to that point", "No, keep current code"), null);
        if (choice != null && choice.startsWith("Yes")) {
            git.exec("git", List.of("stash", "apply", ref));
            ctx.ui().notify("Code restored to checkpoint", NotifyType.INFO);
        }
    }

    Map<String, String> checkpoints() {
        return checkpoints;
    }
}
