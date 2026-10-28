package com.pijava.examples;

import java.util.List;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.ProjectTrustEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.TrustDecision;

/**
 * pi {@code examples/extensions/project-trust.ts:18-64} 移植（docs/40 P6）——
 * project_trust 五选项映射（yes+remember／yes+remember〔带 note〕／yes／no／undecided）；
 * 无 UI ⇒ undecided；session_start 通知含 ctx.cwd 与 load 计数。
 *
 * <p>pi 的 {@code loadCount} 是模块级（每次模块求值 ++）⇒ java 对位静态计数在构造器 ++
 * （每次实例化 ≈ 每次加载——ServiceLoader 每会话装配新建实例）。</p>
 */
public final class ProjectTrustExtension implements PiExtension {

    private static int loadCount;

    /** pi 模块求值臂：每次加载（实例化）递增。 */
    public ProjectTrustExtension() {
        loadCount++;
    }

    @Override
    public String name() {
        return "project-trust";
    }

    @Override
    public void register(ExtensionContext ctx) {
        // Multiple handlers in one extension are allowed. The first handler that returns
        // { trusted: "yes" } or { trusted: "no" } wins and suppresses the built-in
        // trust prompt.
        ctx.on(ExtensionEventSpec.PROJECT_TRUST, (event, c) -> {
            c.ui().notify("project_trust fired for " + event.cwd()
                + " (mode: " + c.mode() + ", load: " + loadCount + ")", NotifyType.INFO);

            if (!c.hasUi()) {
                return new ProjectTrustEventResult(TrustDecision.UNDECIDED, null);
            }

            var choice = c.ui().select("Project trust for:\n" + event.cwd(), List.of(
                "Trust and remember",
                "Trust with note and remember",
                "Trust this session",
                "Do not trust this session",
                "Let built-in prompt decide"), null);

            if ("Trust with note and remember".equals(choice)) {
                var note = c.ui().input("Project trust note", "Optional note for this demo", null);
                // JS 空串假值：note="" 同 null ⇒ "No demo note entered"
                c.ui().notify(note != null && !note.isEmpty()
                    ? "Recorded demo note: " + note : "No demo note entered", NotifyType.INFO);
                return new ProjectTrustEventResult(TrustDecision.YES, Boolean.TRUE);
            }
            if ("Trust and remember".equals(choice)) {
                return new ProjectTrustEventResult(TrustDecision.YES, Boolean.TRUE);
            }
            if ("Trust this session".equals(choice)) {
                return new ProjectTrustEventResult(TrustDecision.YES, null);
            }
            if ("Do not trust this session".equals(choice)) {
                return new ProjectTrustEventResult(TrustDecision.NO, null);
            }
            return new ProjectTrustEventResult(TrustDecision.UNDECIDED, null);
        });

        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            c.ui().notify("project-trust example loaded after trust resolution in " + c.cwd(),
                NotifyType.INFO);
            return null;
        });
    }
}
