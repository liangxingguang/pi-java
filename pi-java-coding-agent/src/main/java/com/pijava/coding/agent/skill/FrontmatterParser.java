package com.pijava.coding.agent.skill;

import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Markdown 技能文件的前言解析（对齐 pi {@code utils/frontmatter.ts}）。
 *
 * <p>识别文件开头的 {@code ---} 分隔 YAML 块；无前言时返回空 Map（整文件视为正文）。
 * 解析失败（未闭合/非法 YAML）抛 {@link FrontmatterException} 由调用方转诊断。</p>
 */
final class FrontmatterParser {

    private FrontmatterParser() {}

    /** 前言 + 正文切分结果。 */
    record Parsed(Map<String, Object> frontmatter, String body) {}

    /**
     * 解析前言。{@code ---} 需在首行或首行空白后；未闭合抛异常。
     *
     * @param markdown 完整技能文件内容
     * @return 前言字段与正文（无前言时 frontmatter 为空 Map）
     * @throws FrontmatterException 前言起始标记存在但未闭合 / YAML 非法
     */
    static Parsed parse(String markdown) throws FrontmatterException {
        if (markdown == null || markdown.isBlank()) {
            return new Parsed(Map.of(), "");
        }
        String[] lines = markdown.split("\n", -1);
        int start = firstContentLine(lines);
        if (start < 0 || !lines[start].trim().equals("---")) {
            // 无前言：整文件为正文
            return new Parsed(Map.of(), markdown);
        }
        int end = -1;
        for (int i = start + 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                end = i;
                break;
            }
        }
        if (end == -1) {
            throw new FrontmatterException("Frontmatter not closed");
        }
        StringBuilder yaml = new StringBuilder();
        for (int i = start + 1; i < end; i++) {
            yaml.append(lines[i]).append('\n');
        }
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.length; i++) {
            body.append(lines[i]).append('\n');
        }
        return new Parsed(parseYaml(yaml.toString()), body.toString());
    }

    private static int firstContentLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String yamlText) throws FrontmatterException {
        if (yamlText.isBlank()) {
            return Map.of();
        }
        try {
            var loader = new LoaderOptions();
            loader.setAllowDuplicateKeys(false);
            Object value = new Yaml(loader).load(yamlText);
            if (value == null) {
                return Map.of();
            }
            if (!(value instanceof Map)) {
                throw new FrontmatterException("Frontmatter must be a YAML mapping");
            }
            return (Map<String, Object>) value;
        } catch (FrontmatterException e) {
            throw e;
        } catch (Exception e) {
            throw new FrontmatterException("Invalid frontmatter YAML: " + e.getMessage());
        }
    }

    /** 前言解析失败。 */
    static final class FrontmatterException extends Exception {
        FrontmatterException(String message) {
            super(message);
        }
    }
}
