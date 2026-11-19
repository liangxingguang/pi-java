package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceLoader;

import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.Test;

/** docs/40 §6 + docs/51 W2 + docs/52 W3 + docs/54 W4 + docs/55 W7 + docs/56 W8 +
 * docs/27 W5/W6：注册表完整性——main 资源 services 名单 == 70 件（防漏登）；全量
 * ServiceLoader 发现可实例化且 name() 与 pi 文件名 stem 对位。subagent/agents.ts 为纯逻辑
 * 模块（SubagentAgents，不单独注册），故 W4 的 10 件对应 9 个扩展类；W8 的 17 件中仅
 * rpc-demo/dynamic-resources 是扩展，其余 15 件为独立 SDK/RPC 脚本（不进注册表）。
 * W5 的 13 件与 W6 的 4 件全部是扩展（overlay 两件已按 D237 出分母）。
 */
class RegistryIntegrityTest {

    /** 70 件（pi 文件名 stem ⇒ 类名映射）。 */
    private static final List<String> EXPECTED_CLASSES = List.of(
        "com.pijava.examples.HelloExtension",
        "com.pijava.examples.PermissionGateExtension",
        "com.pijava.examples.ProtectedPathsExtension",
        "com.pijava.examples.ConfirmDestructiveExtension",
        "com.pijava.examples.DirtyRepoGuardExtension",
        "com.pijava.examples.ProjectTrustExtension",
        "com.pijava.examples.InputTransformExtension",
        "com.pijava.examples.InputTransformStreamingExtension",
        "com.pijava.examples.InlineBashExtension",
        "com.pijava.examples.PirateExtension",
        "com.pijava.examples.ClaudeRulesExtension",
        "com.pijava.examples.PromptCustomizerExtension",
        "com.pijava.examples.EventBusExtension",
        "com.pijava.examples.ProviderPayloadExtension",
        "com.pijava.examples.StructuredOutputExtension",
        "com.pijava.examples.DynamicToolsExtension",
        "com.pijava.examples.CustomAnthropicProviderExtension",
        "com.pijava.examples.CustomProviderGitlabDuoExtension",
        "com.pijava.examples.DebugProviderExtension",
        "com.pijava.examples.JevRouterExtension",
        "com.pijava.examples.PlanModeExtension",
        "com.pijava.examples.ReloadRuntimeExtension",
        "com.pijava.examples.TriggerCompactExtension",
        "com.pijava.examples.CustomCompactionExtension",
        "com.pijava.examples.SendUserMessageExtension",
        "com.pijava.examples.SystemPromptHeaderExtension",
        "com.pijava.examples.HandoffExtension",
        "com.pijava.examples.PresetExtension",
        "com.pijava.examples.TodoExtension",
        "com.pijava.examples.ToolsExtension",
        "com.pijava.examples.BookmarkExtension",
        "com.pijava.examples.SessionNameExtension",
        "com.pijava.examples.QnaExtension",
        "com.pijava.examples.SummarizeExtension",
        "com.pijava.examples.AutoCommitOnExitExtension",
        "com.pijava.examples.GitCheckpointExtension",
        "com.pijava.examples.TicTacToeExtension",
        "com.pijava.examples.MinimalModeExtension",
        "com.pijava.examples.SubagentExtension",
        "com.pijava.examples.BashSpawnHookExtension",
        "com.pijava.examples.BuiltInToolRendererExtension",
        "com.pijava.examples.ToolOverrideExtension",
        "com.pijava.examples.TruncatedToolExtension",
        "com.pijava.examples.SshExtension",
        "com.pijava.examples.InteractiveShellExtension",
        "com.pijava.examples.GithubIssueAutocompleteExtension",
        "com.pijava.examples.NotifyExtension",
        "com.pijava.examples.TitlebarSpinnerExtension",
        "com.pijava.examples.FileTriggerExtension",
        "com.pijava.examples.GitMergeAndResolveExtension",
        "com.pijava.examples.ShutdownCommandExtension",
        "com.pijava.examples.rpc.RpcDemoExtension",
        "com.pijava.examples.rpc.DynamicResourcesExtension",
        "com.pijava.examples.StatusLineExtension",
        "com.pijava.examples.ModelStatusExtension",
        "com.pijava.examples.HiddenThinkingLabelExtension",
        "com.pijava.examples.WidgetPlacementExtension",
        "com.pijava.examples.WorkingMessageTestExtension",
        "com.pijava.examples.WorkingIndicatorExtension",
        "com.pijava.examples.CustomFooterExtension",
        "com.pijava.examples.CustomHeaderExtension",
        "com.pijava.examples.MessageRendererExtension",
        "com.pijava.examples.EntryRendererExtension",
        "com.pijava.examples.ModalEditorExtension",
        "com.pijava.examples.RainbowEditorExtension",
        "com.pijava.examples.BorderStatusEditorExtension",
        "com.pijava.examples.TimedConfirmExtension",
        "com.pijava.examples.CommandsExtension",
        "com.pijava.examples.QuestionExtension",
        "com.pijava.examples.QuestionnaireExtension");

    private static final List<String> EXPECTED_NAMES = List.of(
        "hello", "permission-gate", "protected-paths", "confirm-destructive",
        "dirty-repo-guard", "project-trust", "input-transform", "input-transform-streaming",
        "inline-bash", "pirate", "claude-rules", "prompt-customizer", "event-bus",
        "provider-payload", "structured-output", "dynamic-tools",
        "custom-provider-anthropic", "custom-provider-gitlab-duo", "debug-provider",
        "jev-router", "plan-mode", "reload-runtime", "trigger-compact",
        "custom-compaction", "send-user-message", "system-prompt-header", "handoff",
        "preset", "todo", "tools", "bookmark", "session-name", "qna", "summarize",
        "auto-commit-on-exit", "git-checkpoint", "tic-tac-toe",
        "minimal-mode", "subagent", "bash-spawn-hook", "built-in-tool-renderer",
        "tool-override", "truncated-tool", "ssh", "interactive-shell",
        "github-issue-autocomplete",
        "notify", "titlebar-spinner", "file-trigger", "git-merge-and-resolve",
        "shutdown-command", "rpc-demo", "dynamic-resources",
        "status-line", "model-status", "hidden-thinking-label", "widget-placement",
        "working-message-test", "working-indicator", "custom-footer", "custom-header",
        "message-renderer", "entry-renderer", "modal-editor", "rainbow-editor",
        "border-status-editor", "timed-confirm", "commands", "question", "questionnaire");

    @Test
    void servicesFileListsExactlyTheRegistered() throws Exception {
        List<String> lines;
        try (var in = getClass().getClassLoader().getResourceAsStream(
                "META-INF/services/com.pijava.coding.agent.extension.PiExtension")) {
            assertThat(in).isNotNull();
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank()).toList();
        }
        assertThat(lines).containsExactlyInAnyOrderElementsOf(EXPECTED_CLASSES);
    }

    @Test
    void serviceLoaderInstantiatesAllWithStemNames() {
        var found = ServiceLoader.load(PiExtension.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(e -> e.getClass().getName().startsWith("com.pijava.examples."))
            .toList();
        assertThat(found).hasSize(70);
        assertThat(found.stream().map(PiExtension::name).toList())
            .containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES);
    }
}
