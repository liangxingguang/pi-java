package com.pijava.coding.agent.core;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.ai.model.ScopedModel;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModelScoping} 夹具：patterns 来源（--models 优先于 settings）、诊断
 * stderr 打印面、初始模型选择（pi main.ts:813-816,499-516）。
 */
class ModelScopingTest {

    /** 在临时 user.home 窗口里载入 settings（FileSettingsStorage 构造时捕获 home）。 */
    private static SettingsManager withTempHome(ModelSetup setup) throws Exception {
        var home = Files.createTempDirectory("pi-scoping-home");
        String saved = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            return setup.run();
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    private interface ModelSetup {
        SettingsManager run() throws Exception;
    }

    @Test
    void cliModelsTakePrecedenceOverSettings() throws Exception {
        var catalog = ModelsJsonConfig.allModels();
        var settings = withTempHome(() -> {
            var manager = SettingsManager.load(null);
            manager.accessors().setEnabledModels(List.of("anthropic/*"));
            return manager;
        });
        // gemini-2.5* 裸 id 只在 google 分片（"google/*" 会经双车道命中
        // openrouter 的 google/ 斜杠 id，pi 同形）。
        var args = ArgsParser.parse(new String[] {"--models", "gemini-2.5*"});

        var scope = ModelScoping.resolve(args, settings, catalog);

        assertThat(scope.isEmpty()).isFalse();
        assertThat(scope.models()).allSatisfy(sm ->
            assertThat(sm.model().id().provider()).isEqualTo("google"));
    }

    @Test
    void fallsBackToSettingsEnabledModels() throws Exception {
        var catalog = ModelsJsonConfig.allModels();
        var settings = withTempHome(() -> {
            var manager = SettingsManager.load(null);
            manager.accessors().setEnabledModels(List.of("gemini-2.5*"));
            return manager;
        });
        var args = ArgsParser.parse(new String[] {});

        var scope = ModelScoping.resolve(args, settings, catalog);

        assertThat(scope.isEmpty()).isFalse();
        assertThat(scope.models()).allSatisfy(sm ->
            assertThat(sm.model().id().provider()).isEqualTo("google"));
    }

    @Test
    void noPatternsMeansNoScope() throws Exception {
        var catalog = ModelsJsonConfig.allModels();
        var settings = withTempHome(() -> SettingsManager.load(null));
        var args = ArgsParser.parse(new String[] {});

        assertThat(ModelScoping.resolve(args, settings, catalog)).isSameAs(ModelScoping.EMPTY);
    }

    @Test
    void noMatchDiagnosticGoesToStderrWithWarningPrefix() throws Exception {
        var catalog = ModelsJsonConfig.allModels();
        var settings = withTempHome(() -> SettingsManager.load(null));
        var args = ArgsParser.parse(new String[] {"--models", "no-such-model-xyz"});

        var err = System.err;
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            var scope = ModelScoping.resolve(args, settings, catalog);
            assertThat(scope.isEmpty()).isTrue();
        } finally {
            System.setErr(err);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8))
            .contains("Warning: No models match pattern \"no-such-model-xyz\"");
    }

    @Test
    void initialModelUsesSavedDefaultWhenInScopeOtherwiseFirst() throws Exception {
        var catalog = ModelsJsonConfig.allModels();
        var settings = withTempHome(() -> SettingsManager.load(null));
        var args = ArgsParser.parse(new String[] {"--models", "gemini-2.5*"});
        var scope = ModelScoping.resolve(args, settings, catalog);

        ScopedModel first = scope.models().getFirst();

        // saved 默认在 scope 内 ⇒ 选它（携带该 scope 条目自身的级别）。
        var saved = ModelScoping.initial(scope, "google",
            first.model().id().modelName(), catalog);
        assertThat(saved).contains(first);

        // saved 默认在 scope 外 ⇒ scoped[0]。
        var outOfScope = ModelScoping.initial(scope, "anthropic",
            "definitely-not-in-google-scope", catalog);
        assertThat(outOfScope).contains(first);

        // saved 默认不在目录 ⇒ scoped[0]（pi getModel 缺席分支）。
        var unknown = ModelScoping.initial(scope, "nope", "nope", catalog);
        assertThat(unknown).contains(first);

        assertThat(ModelScoping.initial(ModelScoping.EMPTY, "google",
            first.model().id().modelName(), catalog)).isEmpty();
    }
}
