package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.skill.Skill;
import com.pijava.coding.agent.core.PromptConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P12：prompt-customizer——before_agent_start 按活跃工具/技能生成
 * {@code tool_guidance} 节；有内容 ⇒ 写入 sections，无 ⇒ 删除该键。
 */
class PromptCustomizerExtensionTest {

    @TempDir Path tmp;

    /** 最小 Skill 桩。 */
    private static Skill skill(String name) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return name; }
            @Override public String systemPrompt() { return ""; }
        };
    }

    @Test
    void guidanceSectionWrittenFromActiveToolsAndSkills() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PromptCustomizerExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-pc", "ok"))) {
                session.harness().skillManager().register(skill("tdd"));
                session.harness().skillManager().register(skill("code-review"));

                var result = session.processPrompt("work", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var guidance = session.harness().getPromptSections().get("tool_guidance");
                assertThat(guidance).isNotNull();
                assertThat(guidance).contains(
                    "- Use `read` for file contents; it supports text and images.");
                assertThat(guidance).contains(
                    "- For large files, use `offset` and `limit` to read in chunks.");
                assertThat(guidance).contains(
                    "- Use `bash` for file operations such as `ls`, `find`, and `grep`.");
                assertThat(guidance).contains(
                    "- Use `edit` for precise text replacements that match existing content exactly.");
                assertThat(guidance).contains(
                    "- Use `write` to create new files or replace existing files completely.");
                assertThat(guidance).contains("- Available skills: tdd, code-review.");
            }
        }
    }

    @Test
    void emptyGuidanceRemovesSection() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PromptCustomizerExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-pc2", "ok"))) {
                // 四个产出工具全部移出活跃集 + 预置旧 section ⇒ 运行后该键被删
                session.setActiveToolsByName(List.of());
                var sections = new LinkedHashMap<String, String>();
                sections.put("tool_guidance", "stale");
                session.harness().setPromptSections(sections);

                var result = session.processPrompt("work", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(session.harness().getPromptSections())
                    .doesNotContainKey("tool_guidance");
            }
        }
    }
}
