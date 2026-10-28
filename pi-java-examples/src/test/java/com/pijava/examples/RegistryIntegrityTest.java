package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceLoader;

import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.Test;

/**
 * docs/40 §6：注册表完整性——main 资源 services 名单 == 批 28 的 16 件（防漏登）；
 * 全量 ServiceLoader 发现可实例化且 name() 与 pi 文件名 stem 对位。
 */
class RegistryIntegrityTest {

    /** 批 28 的 16 件（pi 文件名 stem ⇒ 类名映射）。 */
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
        "com.pijava.examples.DynamicToolsExtension");

    private static final List<String> EXPECTED_NAMES = List.of(
        "hello", "permission-gate", "protected-paths", "confirm-destructive",
        "dirty-repo-guard", "project-trust", "input-transform", "input-transform-streaming",
        "inline-bash", "pirate", "claude-rules", "prompt-customizer", "event-bus",
        "provider-payload", "structured-output", "dynamic-tools");

    @Test
    void servicesFileListsExactlyTheBatch28Sixteen() throws Exception {
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
        assertThat(found).hasSize(16);
        assertThat(found.stream().map(PiExtension::name).toList())
            .containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES);
    }
}
