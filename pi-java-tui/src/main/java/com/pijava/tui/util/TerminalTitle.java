package com.pijava.tui.util;

import java.nio.file.Path;
import java.util.Locale;

/**
 * docs/65（L7 W5，map/05 **R23**）：终端标题——pi {@code packages/tui/src/terminal.ts:600-604}
 * {@code setTitle}（OSC 0 ＋ BEL）与 {@code utils.ts:372-377} 的控制字符过滤。
 *
 * <p>过滤规则逐字：{@code [\u0000-\u001f\u007f-\u009f]+}（C0＋DEL＋C1）每段连续字符
 * 折成**一个空格**，其余原样保留；**无长度截断**。</p>
 *
 * <p>标题形状（pi {@code interactive-mode.ts:1157-1165}）：
 * {@code <app> - <session name> - <cwd basename>}，无会话名时省略中段。</p>
 */
public final class TerminalTitle {

    /** 应用名（pi 用配置名或 {@code π}；Java 与欢迎卡一致）。 */
    public static final String APP_TITLE = "pi-java";

    private static final java.util.regex.Pattern CONTROL_CHARACTERS =
        java.util.regex.Pattern.compile("[\\u0000-\\u001f\\u007f-\\u009f]+");

    private TerminalTitle() {}

    /** pi {@code replaceControlCharacters}：连续控制字符段 ⇒ 单个空格。 */
    public static String sanitize(String text) {
        return CONTROL_CHARACTERS.matcher(text == null ? "" : text).replaceAll(" ");
    }

    /**
     * 标题文本（不含转义序列）。
     *
     * @param sessionName 会话名（可空/空白 ⇒ 省略）
     * @param cwd         工作目录（取 basename；null ⇒ 省略）
     */
    public static String title(String sessionName, String cwd) {
        var builder = new StringBuilder(APP_TITLE);
        if (sessionName != null && !sessionName.isBlank()) {
            builder.append(" - ").append(sessionName.strip());
        }
        var base = basename(cwd);
        if (base != null) {
            builder.append(" - ").append(base);
        }
        return sanitize(builder.toString());
    }

    /** OSC 0 序列（pi {@code \x1b]0;…\x07}）。 */
    public static String escapeSequence(String title) {
        return "\u001b]0;" + sanitize(title) + "\u0007";
    }

    /** 直接写终端（pi {@code process.stdout.write} 对位）。 */
    public static void write(String title) {
        System.out.print(escapeSequence(title));
        System.out.flush();
    }

    private static String basename(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return null;
        }
        try {
            var name = Path.of(cwd).getFileName();
            return name == null ? cwd : name.toString();
        } catch (java.nio.file.InvalidPathException e) {
            return cwd;
        }
    }

    /** 键位展示形（pi {@code capitalize:true}）：{@code ctrl+p} ⇒ {@code Ctrl+P}。 */
    public static String capitalizeKey(String keyText) {
        if (keyText == null || keyText.isEmpty()) {
            return keyText;
        }
        var parts = keyText.split("\\+");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                parts[i] = parts[i].substring(0, 1).toUpperCase(Locale.ROOT)
                    + parts[i].substring(1);
            }
        }
        return String.join("+", parts);
    }
}
