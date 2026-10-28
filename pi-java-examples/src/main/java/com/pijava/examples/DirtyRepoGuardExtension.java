package com.pijava.examples;

import java.util.List;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeForkResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeSwitchResult;

/**
 * pi {@code examples/extensions/dirty-repo-guard.ts:10-56} 移植（docs/40 P5）——
 * 会话切换/fork 前查 {@code git status --porcelain}：非 git 仓库/干净 ⇒ 放行；
 * 脏＋无 UI ⇒ cancel；脏＋有 UI 选 No ⇒ cancel＋warning 通知。
 *
 * <p>pi 的 {@code checkDirtyRepo} 返回 {@code {cancel:boolean}|undefined} 两事件共用
 * （TS 结构类型兼容）；java 两事件结果类型不同 ⇒ 核心返回 {@code boolean}
 * （{@code true} ⇒ cancel；pi 的 undefined 放行臂＝false），两处理器各包一层。
 * （返回 {@code Boolean} null 会撞 spotbugs NP_BOOLEAN_RETURN_NULL——批 agent-core-4
 * 教训；pi 语义本就两态，无需三态。）</p>
 */
public final class DirtyRepoGuardExtension implements PiExtension {

    @Override
    public String name() {
        return "dirty-repo-guard";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_BEFORE_SWITCH, (event, c) -> {
            var action = "new".equals(event.reason()) ? "new session" : "switch session";
            return checkDirtyRepo(c, action) ? new SessionBeforeSwitchResult(true) : null;
        });
        ctx.on(ExtensionEventSpec.SESSION_BEFORE_FORK, (event, c) ->
            checkDirtyRepo(c, "fork") ? new SessionBeforeForkResult(true, null) : null);
    }

    /** pi {@code checkDirtyRepo} 核心：{@code true} ⇒ cancel（pi {@code {cancel:true}} 臂）。 */
    static boolean checkDirtyRepo(ExtensionContext ctx, String action) {
        // Check for uncommitted changes
        var result = ctx.exec("git", List.of("status", "--porcelain"), null);
        if (result.code() != 0) {
            // Not a git repo, allow the action
            return false;
        }
        var trimmed = result.stdout().trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (!ctx.hasUi()) {
            // In non-interactive mode, block by default
            return true;
        }
        // Count changed files（pi：trim 后按 \n 切再滤空串——trim 非空则切不出空串）
        var changedFiles = trimmed.split("\n").length;
        var choice = ctx.ui().select(
            "You have " + changedFiles + " uncommitted file(s). " + action + " anyway?",
            List.of("Yes, proceed anyway", "No, let me commit first"), null);
        if (!"Yes, proceed anyway".equals(choice)) {
            ctx.ui().notify("Commit your changes first", NotifyType.WARNING);
            return true;
        }
        return false;
    }
}
