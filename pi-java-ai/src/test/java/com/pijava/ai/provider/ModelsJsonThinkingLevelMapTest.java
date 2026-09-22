package com.pijava.ai.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步7：models.json 的 {@code thinkingLevelMap} 键（pi {@code ThinkingLevelMapSchema}，
 * {@code model-config.ts:55-64}）。
 *
 * <p>⚠️ <b>改动前这个键会被静默吞掉</b> —— {@code ModelDef} 没列它，而记录是
 * {@code ignoreUnknown = true} ⇒ 用户写了也没用（与 B8 的 {@code compat} 同病，
 * {@code docs/31 §8.34.2-6}）。</p>
 *
 * <p>⚠️ <b>三态必须活下来</b>：pi 的 {@code ThinkingLevelMap} 值是 {@code string | null}，
 * 而「键缺席」与「键在场但值是 null」<b>语义不同</b>（前者用 provider 默认，后者是显式
 * 不支持）。JSON 里 {@code {"low": null}} 与「没有 low 键」在 Jackson 上都会变成
 * {@code null} —— 所以值必须经 {@code Map<String, String>} 读（<b>Map 保留「键在场」</b>），
 * 不能读成 7 个 {@code String} 字段。</p>
 */
class ModelsJsonThinkingLevelMapTest {

    @TempDir
    Path tmp;

    private ModelInfo modelFrom(String thinkingLevelMapJson) throws IOException {
        var json = """
            {
              "providers": {
                "p": {
                  "baseUrl": "https://example.com",
                  "api": "anthropic-messages",
                  "models": [
                    {"id": "m", "reasoning": true, "thinkingLevelMap": %s}
                  ]
                }
              }
            }
            """.formatted(thinkingLevelMapJson);
        var file = Files.createTempFile(tmp, "models-", ".json");
        Files.writeString(file, json);
        try {
            return ModelsJsonConfig.load(file).catalog().listModels().get(0);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static ModelThinkingLevel lvl(ThinkingLevel level) {
        return ModelThinkingLevel.of(level);
    }

    /** 字符串值 ⇒ 该级别的 provider effort 名。 */
    @Test
    void stringValueBecomesTheMappedEffortName() throws IOException {
        var model = modelFrom("""
            {"high": "MAX", "low": "low"}""");

        assertThat(model.thinkingLevelMap().mapped(lvl(new ThinkingLevel.High()))).contains("MAX");
        assertThat(model.thinkingLevelMap().mapped(lvl(new ThinkingLevel.Low()))).contains("low");
    }

    /** ⚠️ {@code null} 值 ⇒ **显式不支持**（键在场），与「键缺席」不同。 */
    @Test
    void nullValueMeansExplicitlyUnsupported() throws IOException {
        var model = modelFrom("""
            {"xhigh": null, "max": "max"}""");

        assertThat(model.thinkingLevelMap().hasEntry(lvl(new ThinkingLevel.XHigh()))).isTrue();
        assertThat(model.thinkingLevelMap().explicitlyUnsupported(lvl(new ThinkingLevel.XHigh()))).isTrue();
        assertThat(model.thinkingLevelMap().explicitlyUnsupported(lvl(new ThinkingLevel.Max()))).isFalse();
    }

    /** 键缺席 ⇒ 既不是不支持、也没有映射。 */
    @Test
    void absentKeyIsNeitherUnsupportedNorMapped() throws IOException {
        var model = modelFrom("""
            {"high": "high"}""");

        assertThat(model.thinkingLevelMap().hasEntry(lvl(new ThinkingLevel.Low()))).isFalse();
        assertThat(model.thinkingLevelMap().explicitlyUnsupported(lvl(new ThinkingLevel.Low()))).isFalse();
        assertThat(model.thinkingLevelMap().mapped(lvl(new ThinkingLevel.Low()))).isEmpty();
    }

    /** {@code off: null} ⇒ {@code supportsExplicitOff()} 为假（Kimi K2.7 Code 那类）。 */
    @Test
    void explicitNullOffDisablesExplicitOff() throws IOException {
        var model = modelFrom("""
            {"off": null}""");

        assertThat(model.thinkingLevelMap().supportsExplicitOff()).isFalse();
    }

    /** 整块缺席 ⇒ 空表（全走 provider 默认）。 */
    @Test
    void absentBlockYieldsAnEmptyMap() throws IOException {
        var model = modelFrom("""
            {}""");

        assertThat(model.thinkingLevelMap()).isEqualTo(
            com.pijava.ai.thinking.ThinkingLevelMap.empty());
    }

    /** 未知键被忽略（不炸、也不进表）。 */
    @Test
    void unknownLevelKeysAreIgnored() throws IOException {
        var model = modelFrom("""
            {"bogus": "x", "high": "high"}""");

        assertThat(model.thinkingLevelMap().entries()).hasSize(1);
        assertThat(model.thinkingLevelMap().mapped(lvl(new ThinkingLevel.High()))).contains("high");
    }
}
