package com.pijava.coding.agent.prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.coding.agent.core.source.SourceInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B247（docs/13 §3.2）：模板加载期 sourceInfo 打标 —— pi
 * {@code prompt-templates.ts:235-261} {@code getSourceInfo}。
 */
class PromptTemplatesSourceInfoTest {

    @Test
    void loadDefaultsTagsUserAndProjectScopes(@TempDir Path tmp) throws Exception {
        Path agentDir = tmp.resolve("agent");
        Path cwd = tmp.resolve("proj");
        Files.createDirectories(agentDir.resolve("prompts"));
        Files.createDirectories(cwd.resolve(".pi-java/prompts"));
        Files.writeString(agentDir.resolve("prompts/global.md"), "global body");
        Files.writeString(cwd.resolve(".pi-java/prompts/local.md"), "local body");

        var result = PromptTemplates.loadDefaults(
            new DefaultFileSystem(), agentDir.toString(), cwd.toString());

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.templates()).hasSize(2);
        var global = result.templates().stream()
            .filter(t -> t.name().equals("global")).findFirst().orElseThrow();
        var local = result.templates().stream()
            .filter(t -> t.name().equals("local")).findFirst().orElseThrow();

        // pi :248-256：全局目录 ⇒ scope=user、baseDir=全局 prompts 目录。
        assertThat(global.sourceInfo().scope()).isEqualTo(SourceInfo.Scope.USER);
        assertThat(global.sourceInfo().baseDir())
            .isEqualTo(agentDir.resolve("prompts").toString());
        assertThat(global.sourceInfo().path()).isEqualTo(global.filePath());
        assertThat(global.sourceInfo().source()).isEqualTo("local");
        assertThat(global.sourceInfo().origin()).isEqualTo(SourceInfo.Origin.TOP_LEVEL);

        // pi :250-256：项目目录 ⇒ scope=project、baseDir=项目 prompts 目录。
        assertThat(local.sourceInfo().scope()).isEqualTo(SourceInfo.Scope.PROJECT);
        assertThat(local.sourceInfo().baseDir())
            .isEqualTo(cwd.resolve(".pi-java/prompts").toString());
    }

    @Test
    void loadDefaultsMissingDirsAreSilent(@TempDir Path tmp) {
        // pi :164-166 existsSync 短路：缺失目录零模板零诊断。
        var result = PromptTemplates.loadDefaults(new DefaultFileSystem(),
            tmp.resolve("no-agent").toString(), tmp.resolve("no-proj").toString());
        assertThat(result.templates()).isEmpty();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void explicitDirTagsTemporaryWithDirAsBaseDir(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("review.md"), "body");

        var result = PromptTemplates.load(new DefaultFileSystem(), List.of(tmp.toString()));

        assertThat(result.templates()).hasSize(1);
        var info = result.templates().get(0).sourceInfo();
        // pi :257-261：显式路径 source="local"、scope 缺省落 temporary；
        // 目录入参 baseDir=目录自身（pi :259 isDirectory 分支）。
        assertThat(info.source()).isEqualTo("local");
        assertThat(info.scope()).isEqualTo(SourceInfo.Scope.TEMPORARY);
        assertThat(info.origin()).isEqualTo(SourceInfo.Origin.TOP_LEVEL);
        assertThat(info.baseDir()).isEqualTo(tmp.toString());
        assertThat(info.path()).endsWith("review.md");
    }

    @Test
    void explicitFileTagsTemporaryWithParentAsBaseDir(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("single.md");
        Files.writeString(file, "body");

        var result = PromptTemplates.load(new DefaultFileSystem(), List.of(file.toString()));

        assertThat(result.templates()).hasSize(1);
        var info = result.templates().get(0).sourceInfo();
        // 文件入参 baseDir=父目录（pi :259 dirname 分支）。
        assertThat(info.scope()).isEqualTo(SourceInfo.Scope.TEMPORARY);
        assertThat(info.baseDir()).isEqualTo(tmp.toString());
        assertThat(info.path()).isEqualTo(file.toString());
    }

    @Test
    void compatConstructorLeavesSourceInfoAbsent() {
        var template = new PromptTemplates.PromptTemplate("n", "d", "c");
        assertThat(template.sourceInfo()).isNull();
    }
}
