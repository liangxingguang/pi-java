package com.pijava.tui.screen;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 12 T14（docs/23 §3/§7）：两级状态机（model ⇒ level ⇒ done／Esc 回退），
 * change 臂写 override＋当前模型即时 setThinkingLevel，clear 臂删 override＋
 * 当前模型回退全局默认，非当前模型不动会话，写后 flush。
 */
class ModelThinkingLevelScreenTest {

    private static final KeyEvent ENTER = KeyEvent.ofKey(KeyCode.ENTER);
    private static final KeyEvent ESC = KeyEvent.ofKey(KeyCode.ESCAPE);
    private static final KeyEvent DOWN = KeyEvent.ofKey(KeyCode.DOWN);

    @TempDir
    Path sessionDir;

    private String savedHome;
    private Path tempHome;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // user.home 保持到测试结束（settings 懒解析——create 后恢复会让
        // 首次访问与 close flush 落进真实 home，本批已踩过）；session 由
        // 各用例的 try-with-resources 在恢复前关闭。
        if (savedHome != null) {
            System.setProperty("user.home", savedHome);
            savedHome = null;
        }
    }

    /** 目录里第一个支持思考的模型（内置目录必有 claude 系）。 */
    private static ModelInfo thinkingModel() {
        return ModelsJsonConfig.allModels().listModels().stream()
            .filter(m -> m.capabilities().contains(ModelCapability.THINKING))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("目录无思考模型"));
    }

    private AgentSession newSession() throws Exception {
        tempHome = Files.createTempDirectory("pi-mtl-home");
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        return AgentSession.create(ArgsParser.parse(
            new String[] {"--session-dir", sessionDir.toString()}));
    }

    private static String key(ModelInfo model) {
        return model.id().provider() + "/" + model.id().modelName();
    }

    /** change 臂：写 override＋当前模型即时生效＋flush 落盘。 */
    @Test
    void changeArmWritesOverrideAndAppliesToCurrentModel() throws Exception {
        try (var session = newSession()) {
            var model = thinkingModel();
            session.setModel(model.id());   // 当前模型 ⇒ 置顶
            var screen = new ModelThinkingLevelScreen(session);

            screen.onKeyEvent(ENTER);       // model 步：确认当前模型（置顶首项）
            screen.onKeyEvent(ENTER);       // level 步：确认首档
            assertThat(screen.isDone()).isTrue();
            screen.apply(session, s -> { });

            var accessors = session.services().settings().accessors();
            var written = accessors.getModelThinkingLevel(
                model.id().provider(), model.id().modelName());
            assertThat(written).isNotNull();
            // 当前模型即时生效（pi :5012-5016）。
            assertThat(session.harness().getThinkingLevel().label()).isEqualTo(written);
            // flush 落盘：settings.json 含 "provider/model" 键。
            var settingsFile = tempHome.resolve(".pi-java/agent/settings.json");
            assertThat(Files.readString(settingsFile)).contains(key(model));
        }
    }

    /** clear 臂：删 override＋当前模型回退全局默认（?? "off"）。 */
    @Test
    void clearArmRemovesOverrideAndFallsBackToGlobalDefault() throws Exception {
        try (var session = newSession()) {
            var model = thinkingModel();
            session.setModel(model.id());
            var accessors = session.services().settings().accessors();
            accessors.setModelThinkingLevel(
                model.id().provider(), model.id().modelName(), "high");

            var screen = new ModelThinkingLevelScreen(session);
            screen.onKeyEvent(ENTER);       // model 步
            // level 步：已有 override ⇒ 末项 "(clear override)"——Down 到底再 Enter。
            for (int i = 0; i < 16; i++) {
                screen.onKeyEvent(DOWN);
            }
            screen.onKeyEvent(ENTER);
            screen.apply(session, s -> { });

            assertThat(accessors.getModelThinkingLevel(
                model.id().provider(), model.id().modelName())).isNull();
            assertThat(session.harness().getThinkingLevel().label()).isEqualTo("off");
        }
    }

    /** 非当前模型：override 照写，会话思考档不动。 */
    @Test
    void changeOnNonCurrentModelLeavesSessionUntouched() throws Exception {
        try (var session = newSession()) {
            var model = thinkingModel();
            // 不设当前模型（或换一个别的）⇒ 选中模型非当前。
            var before = session.harness().getThinkingLevel();
            var screen = new ModelThinkingLevelScreen(session);

            // model 步：当前模型缺席 ⇒ 目录序首项未必是 thinkingModel——
            // 用 filter 收窄到 "provider/modelName" 全键（防跨 provider 同名）。
            typeFilter(screen, key(model));
            screen.onKeyEvent(ENTER);
            screen.onKeyEvent(DOWN);        // 避开首档，取第二档
            screen.onKeyEvent(ENTER);
            screen.apply(session, s -> { });

            assertThat(session.services().settings().accessors()
                .getModelThinkingLevel(model.id().provider(), model.id().modelName()))
                .isNotNull();
            assertThat(session.harness().getThinkingLevel()).isEqualTo(before);
        }
    }

    /** level 步 Esc ⇒ 回退 model 步（不关屏）；model 步 Esc ⇒ 取消。 */
    @Test
    void escapeInLevelStepBacksOutToModelStep() throws Exception {
        try (var session = newSession()) {
            var model = thinkingModel();
            session.setModel(model.id());
            var screen = new ModelThinkingLevelScreen(session);

            screen.onKeyEvent(ENTER);       // 进 level 步
            screen.onKeyEvent(ESC);         // 回退 model 步
            assertThat(screen.isDone()).isFalse();
            screen.onKeyEvent(ESC);         // 取消
            assertThat(screen.isDone()).isTrue();
            screen.apply(session, s -> { });
            assertThat(session.services().settings().accessors()
                .getModelThinkingLevel(model.id().provider(), model.id().modelName()))
                .isNull();
        }
    }

    private static void typeFilter(ModelThinkingLevelScreen screen, String query) {
        for (char c : query.toCharArray()) {
            screen.onKeyEvent(KeyEvent.ofChar(c));
        }
    }
}
