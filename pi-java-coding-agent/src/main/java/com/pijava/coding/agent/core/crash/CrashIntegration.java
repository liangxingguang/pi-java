package com.pijava.coding.agent.core.crash;

import java.util.List;

/**
 * 崩溃集成文案件（pi {@code modes/interactive/interactive-mode.ts} 的
 * {@code crashReportInstructions}（:2160-2162）与
 * {@code formatCrashExtensionHint}（:304-319）逐字对位；{@code APP_NAME}＝
 * {@code pi-java}）。全部文案【用户可见】。
 */
public final class CrashIntegration {

    private static final String APP_NAME = "pi-java";

    private CrashIntegration() {}

    /**
     * pi {@code crashReportInstructions}：有 sessionFile ⇒
     * {@code run `pi-java -r` to resume the session, then}；无 ⇒
     * {@code start pi-java and}。
     */
    public static String crashReportInstructions(String sessionFile) {
        var resume = sessionFile != null
            ? "run `" + APP_NAME + " -r` to resume the session, then"
            : "start " + APP_NAME + " and";
        return "To report this crash: " + resume
            + " run /bug. The crash details are attached automatically.";
    }

    /**
     * pi {@code formatCrashExtensionHint}：空表 ⇒ {@code null}（pi 的
     * undefined）；1 条 {@code `x`}、2 条 {@code `x` and `y`}、3+ 条
     * {@code `a`, `b`, and `c`}；noun/pronoun 随单复数。
     */
    public static String formatCrashExtensionHint(List<String> extensionMatches) {
        var matches = extensionMatches == null
            ? List.<String>of()
            : extensionMatches.stream().filter(m -> m != null && !m.isEmpty()).toList();
        if (matches.isEmpty()) {
            return null;
        }
        var quoted = matches.stream().map(m -> "`" + m + "`").toList();
        String labels;
        if (quoted.size() == 1) {
            labels = quoted.getFirst();
        } else if (quoted.size() == 2) {
            labels = String.join(" and ", quoted);
        } else {
            labels = String.join(", ", quoted.subList(0, quoted.size() - 1))
                + ", and " + quoted.getLast();
        }
        var noun = matches.size() == 1 ? "extension" : "extensions";
        var pronoun = matches.size() == 1 ? "it" : "them";
        return "A stack frame came from loaded " + noun + " " + labels
            + ", which may be involved. Try disabling " + pronoun + " with `"
            + APP_NAME + " config`, or run `" + APP_NAME + " -ne` to confirm.";
    }
}
