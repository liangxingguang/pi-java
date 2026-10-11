package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B223（{@code 原 docs/47}）：{@link SettingsAccessors} 的 compaction 三件套 ——
 * enabled 的 {@code ?? true} 默认、预算三级回退（modelOverrides 精确键
 * {@code "provider/modelId"} → ordinary → 16384/20000）、负数逐字报错、
 * setter 写全局并即时落盘。
 *
 * <p>每个用例都走真 {@link FileSettingsStorage} + @TempDir，不用内存替身
 * （同 B139 {@code FileSettingsRoundTripTest}）。pi 锚点：
 * settings-manager.ts:914-974（{@code 200387122}）。</p>
 */
class CompactionSettingsAccessorTest {

    private static final ObjectMapper MAPPER = Json.mapper();
    private static final ModelId<?> SMALL = ModelId.of("faux", "small");
    private static final ModelId<?> BIG = ModelId.of("faux", "big");
    private static final ModelId<?> OTHER_SMALL = ModelId.of("other", "small");

    private static FileSettingsStorage storage(Path dir) {
        return new FileSettingsStorage(dir, dir);
    }

    private static void writeGlobal(Path dir, String json) throws Exception {
        Files.writeString(dir.resolve("settings.json"), json);
    }

    /** T1：无 compaction 块 ⇒ enabled 默认 true、预算 16384/20000；null model 不查 override。 */
    @Test
    void absentBlockDefaultsToEnabledAndStandardBudgets(@TempDir Path dir) {
        var accessors = SettingsManager.withStorage(storage(dir)).accessors();

        assertThat(accessors.getCompactionEnabled()).isTrue();
        var resolved = accessors.getCompactionSettings(SMALL);
        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.reserveTokens()).isEqualTo(16_384);
        assertThat(resolved.keepRecentTokens()).isEqualTo(20_000);
        // pi model 缺席形状：model == null 时不查 modelOverrides，其余解析相同。
        assertThat(accessors.getCompactionSettings(null)).isEqualTo(resolved);
    }

    /** T2：普通字段直取。 */
    @Test
    void ordinaryFieldsAreUsed(@TempDir Path dir) throws Exception {
        writeGlobal(dir,
            "{\"compaction\":{\"reserveTokens\":100,\"keepRecentTokens\":9}}");

        var resolved = SettingsManager.withStorage(storage(dir)).accessors()
            .getCompactionSettings(SMALL);

        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.reserveTokens()).isEqualTo(100);
        assertThat(resolved.keepRecentTokens()).isEqualTo(9);
    }

    /** T3：enabled=false 不影响预算解析（自动路的门在消费侧读 enabled）。 */
    @Test
    void disabledKeepsDefaultBudgets(@TempDir Path dir) throws Exception {
        writeGlobal(dir, "{\"compaction\":{\"enabled\":false}}");

        var accessors = SettingsManager.withStorage(storage(dir)).accessors();

        assertThat(accessors.getCompactionEnabled()).isFalse();
        var resolved = accessors.getCompactionSettings(SMALL);
        assertThat(resolved.enabled()).isFalse();
        assertThat(resolved.reserveTokens()).isEqualTo(16_384);
        assertThat(resolved.keepRecentTokens()).isEqualTo(20_000);
    }

    /** T4：modelOverrides 精确键 {@code provider/id} 全串匹配，无层级回退。 */
    @Test
    void modelOverrideMatchesExactProviderAndId(@TempDir Path dir) throws Exception {
        writeGlobal(dir, """
            {"compaction":{"modelOverrides":{"faux/small":{
              "reserveTokens":7,"keepRecentTokens":3}}}}""");

        var accessors = SettingsManager.withStorage(storage(dir)).accessors();

        assertThat(accessors.getCompactionSettings(SMALL))
            .as("精确命中 faux/small")
            .isEqualTo(new com.pijava.agent.compaction.CompactionSettings(true, 7, 3));
        assertThat(accessors.getCompactionSettings(BIG))
            .as("同 provider 不同 id 不命中")
            .isEqualTo(new com.pijava.agent.compaction.CompactionSettings(true, 16_384, 20_000));
        assertThat(accessors.getCompactionSettings(OTHER_SMALL))
            .as("不同 provider 同 id 不命中")
            .isEqualTo(new com.pijava.agent.compaction.CompactionSettings(true, 16_384, 20_000));
    }

    /** T5：override 半覆盖 —— 缺的字段回退 ordinary，再缺回默认。 */
    @Test
    void partialOverrideFallsBackFieldByField(@TempDir Path dir) throws Exception {
        writeGlobal(dir, """
            {"compaction":{"reserveTokens":100,"modelOverrides":{
              "faux/small":{"keepRecentTokens":9}}}}""");

        var accessors = SettingsManager.withStorage(storage(dir)).accessors();

        assertThat(accessors.getCompactionSettings(SMALL))
            .as("override 给 keepRecent；reserve 回退 ordinary")
            .isEqualTo(new com.pijava.agent.compaction.CompactionSettings(true, 100, 9));
        assertThat(accessors.getCompactionSettings(BIG))
            .as("未命中模型：ordinary reserve + 默认 keepRecent")
            .isEqualTo(new com.pijava.agent.compaction.CompactionSettings(true, 100, 20_000));
    }

    /**
     * T5b：同一字段 override 必须压过 ordinary —— pi 每字段独立的
     * {@code override ?? ordinary ?? default}（非整块替换）。
     */
    @Test
    void overrideBeatsOrdinaryForTheSameField(@TempDir Path dir) throws Exception {
        writeGlobal(dir, """
            {"compaction":{"reserveTokens":100,"keepRecentTokens":9,
              "modelOverrides":{"faux/small":{"reserveTokens":7,"keepRecentTokens":3}}}}""");

        var accessors = SettingsManager.withStorage(storage(dir)).accessors();

        assertThat(accessors.getCompactionSettings(SMALL))
            .as("命中模型：两个字段都取 override")
            .isEqualTo(new com.pijava.agent.compaction.CompactionSettings(true, 7, 3));
        assertThat(accessors.getCompactionSettings(BIG))
            .as("未命中模型：两个字段都取 ordinary")
            .isEqualTo(new com.pijava.agent.compaction.CompactionSettings(true, 100, 9));
    }

    /** T6：负数抛 pi 逐字文案（决策点解析时抛，与 pi 同）。 */
    @Test
    void invalidNegativeValuesThrowVerbatimPiMessages(@TempDir Path dir) throws Exception {
        writeGlobal(dir, "{\"compaction\":{\"reserveTokens\":-1}}");
        var ordinary = SettingsManager.withStorage(storage(dir)).accessors();

        assertThatThrownBy(() -> ordinary.getCompactionSettings(SMALL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid compaction.reserveTokens setting: -1."
                + " Expected a non-negative safe integer.");

        writeGlobal(dir, """
            {"compaction":{"modelOverrides":{"faux/small":{
              "keepRecentTokens":-5}}}}""");
        var override = SettingsManager.withStorage(storage(dir)).accessors();

        assertThatThrownBy(() -> override.getCompactionSettings(SMALL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid compaction.modelOverrides[\"faux/small\"]"
                + ".keepRecentTokens setting: -5."
                + " Expected a non-negative safe integer.");
        // 未命中该 override 的模型不受影响（回退默认）。
        assertThat(override.getCompactionSettings(BIG).keepRecentTokens())
            .isEqualTo(20_000);
    }

    /** T7a：setter 即时落盘（重启可重读），保留其余组件与 modelOverrides，可翻回。 */
    @Test
    void setterFlushesImmediatelyAndPreservesComponents(@TempDir Path dir) throws Exception {
        var initial = new Settings();
        var overrides = new LinkedHashMap<String, Settings.Compaction.ModelOverride>();
        overrides.put("faux/small", new Settings.Compaction.ModelOverride(7, 3));
        initial.compaction = new Settings.Compaction(null, 100, 9, overrides);
        storage(dir).writeGlobal(initial);

        var manager = SettingsManager.withStorage(storage(dir));
        manager.accessors().setCompactionEnabled(false);

        JsonNode root = MAPPER.readTree(dir.resolve("settings.json").toFile());
        var compaction = root.path("compaction");
        assertThat(compaction.path("enabled").asBoolean()).isFalse();
        assertThat(compaction.path("reserveTokens").asInt()).isEqualTo(100);
        assertThat(compaction.path("keepRecentTokens").asInt()).isEqualTo(9);
        assertThat(compaction.path("modelOverrides").path("faux/small")
            .path("reserveTokens").asInt()).isEqualTo(7);
        // 即时落盘 ⇒ 全新管理器（≈重启）读得到 false。
        assertThat(SettingsManager.withStorage(storage(dir)).accessors()
            .getCompactionEnabled()).isFalse();

        manager.accessors().setCompactionEnabled(true);
        assertThat(MAPPER.readTree(dir.resolve("settings.json").toFile())
            .path("compaction").path("enabled").asBoolean()).isTrue();
        assertThat(manager.accessors().getCompactionEnabled()).isTrue();
    }

    /** T7b：写 global 不盖 project 层 —— project {@code enabled:false} 仍决定 effective。 */
    @Test
    void globalSetterDoesNotOverrideProjectScope(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".pi-java"));
        Files.writeString(dir.resolve(".pi-java/settings.json"),
            "{\"compaction\":{\"enabled\":false}}");
        var manager = SettingsManager.withStorage(storage(dir));
        assertThat(manager.accessors().getCompactionEnabled()).isFalse();

        manager.accessors().setCompactionEnabled(true);

        assertThat(manager.effective().compaction.enabled())
            .as("project 层 enabled:false 压过新写的 global（pi 合并语义）")
            .isFalse();
    }
}
