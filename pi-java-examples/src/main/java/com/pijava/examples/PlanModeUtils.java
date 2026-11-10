package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * pi {@code examples/extensions/plan-mode/utils.ts:1-168} 移植（docs/51 §2.1）——
 * 计划模式的纯工具函数：命令安全判定、步骤文本清洗、计划条目提取与完成标记。
 *
 * <p>JS 正则的 {@code p.test(s)} 对非 global 正则即「任意位置匹配」⇒ Java
 * {@code Pattern.matcher(s).find()}；带 {@code g}/{@code m} 的用在 {@code replaceAll}/
 * {@code Matcher} 上按需加 {@link Pattern#MULTILINE}。JS 的 {@code /.../i} 只作用于
 * 标注它的模式，故大小写不敏感与敏感两组分别建表。</p>
 */
public final class PlanModeUtils {

    /** 计划模式下阻断的破坏性命令（pi 带 {@code /i} 的 31 条逐字）。 */
    private static final List<Pattern> DESTRUCTIVE_CI = compileAll(true,
        "\\brm\\b",
        "\\brmdir\\b",
        "\\bmv\\b",
        "\\bcp\\b",
        "\\bmkdir\\b",
        "\\btouch\\b",
        "\\bchmod\\b",
        "\\bchown\\b",
        "\\bchgrp\\b",
        "\\bln\\b",
        "\\btee\\b",
        "\\btruncate\\b",
        "\\bdd\\b",
        "\\bshred\\b",
        "\\bnpm\\s+(install|uninstall|update|ci|link|publish)",
        "\\byarn\\s+(add|remove|install|publish)",
        "\\bpnpm\\s+(add|remove|install|publish)",
        "\\bpip\\s+(install|uninstall)",
        "\\bapt(-get)?\\s+(install|remove|purge|update|upgrade)",
        "\\bbrew\\s+(install|uninstall|upgrade)",
        "\\bgit\\s+(add|commit|push|pull|merge|rebase|reset|checkout|branch\\s+-[dD]|stash"
            + "|cherry-pick|revert|tag|init|clone)",
        "\\bsudo\\b",
        "\\bsu\\b",
        "\\bkill\\b",
        "\\bpkill\\b",
        "\\bkillall\\b",
        "\\breboot\\b",
        "\\bshutdown\\b",
        "\\bsystemctl\\s+(start|stop|restart|enable|disable)",
        "\\bservice\\s+\\S+\\s+(start|stop|restart)",
        "\\b(vim?|nano|emacs|code|subl)\\b");

    /** 重定向类破坏性模式（无 {@code i} 标志，pi 逐字）。 */
    private static final List<Pattern> DESTRUCTIVE_CS = List.of(
        Pattern.compile("(^|[^<])>(?!>)"),
        Pattern.compile(">>"));

    /** 计划模式下允许的只读命令（pi 带 {@code /i} 的 9 条）。 */
    private static final List<Pattern> SAFE_CI = compileAll(true,
        "^\\s*git\\s+(status|log|diff|show|branch|remote|config\\s+--get)",
        "^\\s*git\\s+ls-",
        "^\\s*npm\\s+(list|ls|view|info|search|outdated|audit)",
        "^\\s*yarn\\s+(list|info|why|audit)",
        "^\\s*node\\s+--version",
        "^\\s*python\\s+--version",
        "^\\s*curl\\s",
        "^\\s*wget\\s+-O\\s*-",
        "^\\s*sed\\s+-n");

    /** 只读白名单（pi 不带标志的 41 条逐字）。 */
    private static final List<Pattern> SAFE_CS = compileAll(false,
        "^\\s*cat\\b",
        "^\\s*head\\b",
        "^\\s*tail\\b",
        "^\\s*less\\b",
        "^\\s*more\\b",
        "^\\s*grep\\b",
        "^\\s*find\\b",
        "^\\s*ls\\b",
        "^\\s*pwd\\b",
        "^\\s*echo\\b",
        "^\\s*printf\\b",
        "^\\s*wc\\b",
        "^\\s*sort\\b",
        "^\\s*uniq\\b",
        "^\\s*diff\\b",
        "^\\s*file\\b",
        "^\\s*stat\\b",
        "^\\s*du\\b",
        "^\\s*df\\b",
        "^\\s*tree\\b",
        "^\\s*which\\b",
        "^\\s*whereis\\b",
        "^\\s*type\\b",
        "^\\s*env\\b",
        "^\\s*printenv\\b",
        "^\\s*uname\\b",
        "^\\s*whoami\\b",
        "^\\s*id\\b",
        "^\\s*date\\b",
        "^\\s*cal\\b",
        "^\\s*uptime\\b",
        "^\\s*ps\\b",
        "^\\s*top\\b",
        "^\\s*htop\\b",
        "^\\s*free\\b",
        "^\\s*jq\\b",
        "^\\s*awk\\b",
        "^\\s*rg\\b",
        "^\\s*fd\\b",
        "^\\s*bat\\b",
        "^\\s*eza\\b");

    private static final Pattern BOLD_ITALIC = Pattern.compile("\\*{1,2}([^*]+)\\*{1,2}");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern PREFIX = Pattern.compile(
        "^(Use|Run|Execute|Create|Write|Read|Check|Verify|Update|Modify|Add|Remove|Delete|Install)"
            + "\\s+(the\\s+)?",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PLAN_HEADER =
        Pattern.compile("\\*{0,2}Plan:\\*{0,2}\\s*\\n", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBERED =
        Pattern.compile("^\\s*(\\d+)[.)]\\s+\\*{0,2}([^*\\n]+)", Pattern.MULTILINE);
    private static final Pattern TRAILING_STARS = Pattern.compile("\\*{1,2}$");
    private static final Pattern DONE_MARK =
        Pattern.compile("\\[DONE:(\\d+)\\]", Pattern.CASE_INSENSITIVE);

    private PlanModeUtils() {}

    /**
     * pi {@code isSafeCommand}：命中任一破坏性模式或未命中只读白名单 ⇒ {@code false}。
     *
     * @param command bash 命令原文
     * @return whether the command is safe in plan mode
     */
    public static boolean isSafeCommand(String command) {
        if (command == null) {
            return false;
        }
        if (matchesAny(DESTRUCTIVE_CI, command) || matchesAny(DESTRUCTIVE_CS, command)) {
            return false;
        }
        return matchesAny(SAFE_CI, command) || matchesAny(SAFE_CS, command);
    }

    /** pi {@code cleanStepText}：去粗体/斜体与行内代码、去动作前缀、折叠空白、首字母大写、50 截断。 */
    public static String cleanStepText(String text) {
        var cleaned = BOLD_ITALIC.matcher(text).replaceAll("$1");
        cleaned = CODE.matcher(cleaned).replaceAll("$1");
        cleaned = PREFIX.matcher(cleaned).replaceAll("");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.strip();
        if (!cleaned.isEmpty()) {
            cleaned = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
        }
        if (cleaned.length() > 50) {
            cleaned = cleaned.substring(0, 47) + "...";
        }
        return cleaned;
    }

    /** pi {@code extractTodoItems}：只收 {@code Plan:} 段之后的编号条目。 */
    public static List<TodoItem> extractTodoItems(String message) {
        var items = new ArrayList<TodoItem>();
        var header = PLAN_HEADER.matcher(message);
        if (!header.find()) {
            return items;
        }
        var planSection = message.substring(header.start() + header.group().length());
        var numbered = NUMBERED.matcher(planSection);
        while (numbered.find()) {
            var text = TRAILING_STARS.matcher(numbered.group(2).strip()).replaceAll("").strip();
            if (text.length() > 5
                    && !text.startsWith("`") && !text.startsWith("/") && !text.startsWith("-")) {
                var cleaned = cleanStepText(text);
                if (cleaned.length() > 3) {
                    items.add(new TodoItem(items.size() + 1, cleaned, false));
                }
            }
        }
        return items;
    }

    /** pi {@code extractDoneSteps}：全部 {@code [DONE:n]} 标记的步骤号。 */
    public static List<Integer> extractDoneSteps(String message) {
        var steps = new ArrayList<Integer>();
        var matcher = DONE_MARK.matcher(message);
        while (matcher.find()) {
            steps.add(Integer.parseInt(matcher.group(1)));
        }
        return steps;
    }

    /**
     * pi {@code markCompletedSteps}：把命中项的 {@code completed} 置真，返回<b>标记总数</b>
     * （含未命中任何条目的标记，pi 逐字）。
     */
    public static int markCompletedSteps(String text, List<TodoItem> items) {
        var doneSteps = extractDoneSteps(text);
        for (var step : doneSteps) {
            for (var item : items) {
                if (item.step() == step) {
                    item.setCompleted(true);
                    break;
                }
            }
        }
        return doneSteps.size();
    }

    private static boolean matchesAny(List<Pattern> patterns, String command) {
        for (var pattern : patterns) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> compileAll(boolean caseInsensitive, String... regexes) {
        var flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
        var patterns = new ArrayList<Pattern>(regexes.length);
        for (var regex : regexes) {
            patterns.add(Pattern.compile(regex, flags));
        }
        return List.copyOf(patterns);
    }

    /** pi {@code TodoItem}——{@code markCompletedSteps} 需原地改 {@code completed}，故用可变类。 */
    public static final class TodoItem {

        private final int step;
        private final String text;
        private boolean completed;

        TodoItem(int step, String text, boolean completed) {
            this.step = step;
            this.text = text;
            this.completed = completed;
        }

        /** 步骤号（从 1 起）。 */
        public int step() {
            return step;
        }

        /** 清洗后的步骤文本。 */
        public String text() {
            return text;
        }

        /** 是否已完成。 */
        public boolean completed() {
            return completed;
        }

        void setCompleted(boolean completed) {
            this.completed = completed;
        }
    }
}
