package com.pijava.examples.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** docs/56 §2.1：SDK 配置型 7 件（04/05/07/08/09/10/11）纯函数与逐字文本断言。 */
class SdkConfigTypesTest {

    @Test
    void skills04FilterAndDescribe() {
        assertThat(Sdk04Skills.customSkill().name()).isEqualTo("my-skill");
        assertThat(Sdk04Skills.customSkill().filePath()).isEqualTo("/virtual/SKILL.md");
        assertThat(Sdk04Skills.filteredSkillNames(List.of("browser-use", "search-web", "other")))
            .containsExactly("browser-use", "search-web", "my-skill");
        assertThat(Sdk04Skills.describe(List.of("browser-use", "my-skill"), true))
            .containsExactly("Discovered skills:", "browser-use", "my-skill", "Warnings:",
                "Session created with filtered skills");
    }

    @Test
    void tools05FourSessions() {
        var sessions = Sdk05Tools.sessions(Sdk05Tools.CUSTOM_CWD);
        assertThat(sessions).hasSize(4);
        assertThat(sessions.get(0).tools()).containsExactly("read", "grep", "find", "ls");
        assertThat(sessions.get(0).message()).isEqualTo("Read-only session created");
        assertThat(sessions.get(2).cwd()).isEqualTo("/path/to/project");
        assertThat(sessions.get(3).message())
            .isEqualTo("Specific tools with custom cwd session created");
    }

    @Test
    void contextFiles07VirtualAgents() {
        assertThat(Sdk07ContextFiles.VIRTUAL_AGENTS)
            .startsWith("# Project Guidelines").contains("- No any types")
            .endsWith("- Prefer const over let");
        var files = Sdk07ContextFiles.agentsFilesOverride(List.of());
        assertThat(files).hasSize(1);
        assertThat(files.get(0).path()).isEqualTo("/virtual/AGENTS.md");
        assertThat(Sdk07ContextFiles.describe(files))
            .containsExactly("Discovered context files:",
                "  - /virtual/AGENTS.md (" + Sdk07ContextFiles.VIRTUAL_AGENTS.length() + " chars)",
                "Session created with 2 context files");
    }

    @Test
    void prompts08DeployTemplate() {
        var t = Sdk08PromptTemplates.deployTemplate();
        assertThat(t.name()).isEqualTo("deploy");
        assertThat(t.content()).isEqualTo(String.join("\n", "# Deploy Instructions", "",
            "1. Build: npm run build", "2. Test: npm test", "3. Deploy: npm run deploy"));
        assertThat(Sdk08PromptTemplates.promptsOverride(List.of())).hasSize(1);
        assertThat(Sdk08PromptTemplates.describe(List.of(t)))
            .containsExactly("Discovered prompt templates:", "  /deploy: Deploy the application",
                "Session created with 2 prompt templates");
    }

    @Test
    void apiKeys09ThreeRuntimes() {
        var runtimes = Sdk09ApiKeysAndOAuth.runtimes();
        assertThat(runtimes).hasSize(3);
        assertThat(runtimes.get(0).message()).isEqualTo("Session with default model runtime");
        assertThat(runtimes.get(1).authPath()).isEqualTo("/tmp/my-app/auth.json");
        assertThat(runtimes.get(2).runtimeKeyProvider()).isEqualTo("anthropic");
        assertThat(runtimes.get(2).runtimeKeyValue()).isEqualTo("sk-my-temp-key");
    }

    @Test
    void settings10OverridesAndWarning() {
        var o = Sdk10Settings.overrides();
        assertThat(o.compactionEnabled()).isFalse();
        assertThat(o.retryEnabled()).isTrue();
        assertThat(o.maxRetries()).isEqualTo(5);
        assertThat(o.baseDelayMs()).isEqualTo(1000L);
        assertThat(Sdk10Settings.inMemoryOverrides().retryEnabled()).isFalse();
        assertThat(Sdk10Settings.warning(new Sdk10Settings.SettingsError("global", "bad json")))
            .isEqualTo("Warning (global settings): bad json");
        assertThat(Sdk10Settings.MESSAGES).contains("Session created with custom settings");
    }

    @Test
    void sessions11FourEntries() {
        assertThat(Sdk11Sessions.inMemoryLine()).isEqualTo("In-memory session: (none)");
        assertThat(Sdk11Sessions.newSessionLine("/s/abc.json"))
            .isEqualTo("New session file: /s/abc.json");
        assertThat(Sdk11Sessions.continuedLine("/s/x.json"))
            .isEqualTo("Continued session: /s/x.json");
        assertThat(Sdk11Sessions.openedLine("abcdef1234"))
            .isEqualTo("\nOpened: abcdef1234");
        var lines = Sdk11Sessions.describeSessions(List.of(
            new Sdk11Sessions.SessionInfo("abcdef123456", "Hello world this is a long first message"),
            new Sdk11Sessions.SessionInfo("zz", "short")));
        assertThat(lines).contains("\nFound 2 sessions:")
            .contains("  abcdef12... - \"Hello world this is a long fir...\"")
            .contains("  zz... - \"short...\"");
    }
}
