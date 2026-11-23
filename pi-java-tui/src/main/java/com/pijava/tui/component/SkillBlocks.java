package com.pijava.tui.component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pi {@code parseSkillBlock}（agent-session.ts:393-403）的 Java 移植（L7 W2/R19）：
 * 从 user 消息文本解析 {@code <skill name="…" location="…">…</skill>} 块；
 * 尾随 {@code \n\n} 后的文本是该技能的 user message（单独渲染 User 气泡）。
 */
public final class SkillBlocks {

    /** pi {@code ParsedSkillBlock}。 */
    public record SkillBlock(String name, String location, String content, String userMessage) {}

    /**
     * pi 逐字正则；{@code [\s\S]} 跨行、{@code $} 允许串尾换行前（Java 默认
     * {@code $} 语义同 JS 非 multiline）。
     */
    private static final Pattern SKILL_BLOCK = Pattern.compile(
        "^<skill name=\"([^\"]+)\" location=\"([^\"]+)\">\\n([\\s\\S]*?)\\n</skill>(?:\\n\\n([\\s\\S]+))?$");

    private SkillBlocks() {}

    /**
     * Parse a skill block from message text.
     *
     * @return {@code null} if the text doesn't contain a skill block
     */
    public static SkillBlock parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = SKILL_BLOCK.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        // pi: match[4]?.trim() || undefined
        var userMessage = matcher.group(4);
        var trimmed = userMessage == null ? null : userMessage.trim();
        return new SkillBlock(matcher.group(1), matcher.group(2), matcher.group(3),
            trimmed == null || trimmed.isEmpty() ? null : trimmed);
    }
}
