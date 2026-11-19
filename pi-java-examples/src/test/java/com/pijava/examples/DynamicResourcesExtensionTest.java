package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.examples.rpc.DynamicResourcesExtension;

import org.junit.jupiter.api.Test;

/** docs/56 §2.4：dynamic-resources 验收——三路径、frontmatter 原文、theme JSON 键数。 */
class DynamicResourcesExtensionTest {

    @Test
    void contributesThreePaths() {
        var paths = new DynamicResourcesExtension().sessionStartResources();
        assertThat(paths.skillPaths()).hasSize(1);
        assertThat(paths.promptPaths()).hasSize(1);
        assertThat(paths.themePaths()).hasSize(1);
        assertThat(paths.skillPaths().get(0).getFileName().toString()).isEqualTo("SKILL.md");
        assertThat(paths.promptPaths().get(0).getFileName().toString()).isEqualTo("dynamic.md");
        assertThat(paths.themePaths().get(0).getFileName().toString()).isEqualTo("dynamic.json");
        assertThat(Files.exists(paths.skillPaths().get(0))).isTrue();
    }

    @Test
    void resourceFrontmatterVerbatim() throws Exception {
        var skill = read("SKILL.md").replace("\r\n", "\n");
        assertThat(skill).startsWith("---\nname: dynamic-resources\n")
            .contains("description: Example skill loaded from resources_discover")
            .contains("# Dynamic Resources Skill")
            .contains("This skill is provided by the dynamic-resources extension.");
        var prompt = read("dynamic.md").replace("\r\n", "\n");
        assertThat(prompt)
            .contains("description: Example prompt template loaded from resources_discover")
            .contains("Summarize the current repository structure and mention any build or test commands.");
    }

    @Test
    void themeJsonShape() throws Exception {
        var json = read("dynamic.json");
        var root = new ObjectMapper().readTree(json);
        assertThat(root.get("name").asText()).isEqualTo("dynamic-resources");
        assertThat(root.get("$schema").asText())
            .isEqualTo("https://pi.dev/schemas/theme.schema.json");
        assertThat(root.get("vars").size()).isEqualTo(15);
        assertThat(root.get("colors").size()).isEqualTo(53);
        assertThat(root.get("export").size()).isEqualTo(3);
        assertThat(root.get("vars").get("cyan").asText()).isEqualTo("#00d7ff");
        assertThat(root.get("colors").get("bashMode").asText()).isEqualTo("green");
    }

    private static String read(String name) throws Exception {
        var p = Path.of("src", "main", "resources", "dynamic-resources", name);
        return Files.readString(p);
    }
}
