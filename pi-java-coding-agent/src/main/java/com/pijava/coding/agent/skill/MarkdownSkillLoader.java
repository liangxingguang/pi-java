package com.pijava.coding.agent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.pijava.agent.skill.SkillSource;

/**
 * Markdown 技能文件加载（前言 + 正文 + 校验，对齐设计 §6.4）。
 *
 * <p>校验规则：{@code name} 缺失回落父目录名，≤64 字符、仅 {@code [a-z0-9-]}、
 * 无首尾/连续连字符；{@code description} 必需非空且 ≤1024 字符，否则诊断错误且
 * 技能被跳过；{@code disable-model-invocation: true} 不进系统提示；{@code label}
 * 缺失回落 {@code name}。</p>
 */
public final class MarkdownSkillLoader {

    /** name 长度上限（pi validateName）。 */
    static final int MAX_NAME_LENGTH = 64;
    /** description 长度上限（pi validateDescription）。 */
    static final int MAX_DESCRIPTION_LENGTH = 1024;

    private static final String NAME_PATTERN = "[a-z0-9-]+";

    /** 默认构造。 */
    public MarkdownSkillLoader() {
    }

    /** 解析单个技能文件。 */
    public LoadSkillResult loadFile(Path file, SkillSource source) {
        String markdown;
        try {
            markdown = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return LoadSkillResult.skipped(List.of(
                ResourceDiagnostic.error(file.toString(), "Cannot read file: " + e.getMessage())));
        }

        FrontmatterParser.Parsed parsed;
        try {
            parsed = FrontmatterParser.parse(markdown);
        } catch (FrontmatterParser.FrontmatterException e) {
            return LoadSkillResult.skipped(List.of(
                ResourceDiagnostic.error(file.toString(), "Invalid frontmatter: " + e.getMessage())));
        }

        var diagnostics = new ArrayList<ResourceDiagnostic>();
        var fm = parsed.frontmatter();

        // description 必需
        String description = str(fm.get("description"));
        String nameError = validateDescription(description);
        if (nameError != null) {
            diagnostics.add(ResourceDiagnostic.error(file.toString(), nameError));
            return LoadSkillResult.skipped(diagnostics);
        }

        // name：缺失回落父目录名
        String name = str(fm.get("name"));
        if (name == null || name.isBlank()) {
            name = fallbackName(file);
        }
        if (name == null || !isValidName(name)) {
            diagnostics.add(ResourceDiagnostic.error(file.toString(),
                "Invalid skill name '" + name + "': must be " + MAX_NAME_LENGTH
                    + " chars max, [a-z0-9-], no leading/trailing/consecutive dashes"));
            return LoadSkillResult.skipped(diagnostics);
        }

        boolean disable = Boolean.TRUE.equals(fm.get("disable-model-invocation"));
        String label = str(fm.get("label"));

        var skill = new MarkdownSkill(name, label, description,
            parsed.body(), file.getParent(), disable, source);
        return LoadSkillResult.of(skill);
    }

    // ── 校验 ─────────────────────────────────────────────────────────────

    static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        if (!name.matches(NAME_PATTERN)) {
            return false;
        }
        if (name.startsWith("-") || name.endsWith("-") || name.contains("--")) {
            return false;
        }
        return true;
    }

    static String validateDescription(String description) {
        if (description == null || description.isBlank()) {
            return "Skill description is required";
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            return "Skill description exceeds " + MAX_DESCRIPTION_LENGTH + " characters";
        }
        return null;
    }

    private static String fallbackName(Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return null;
        }
        String dirName = parent.getFileName() == null ? null : parent.getFileName().toString();
        return dirName == null ? null : dirName.toLowerCase(Locale.ROOT);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
