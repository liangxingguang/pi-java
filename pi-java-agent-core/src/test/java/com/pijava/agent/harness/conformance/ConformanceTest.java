package com.pijava.agent.harness.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 差分：把同一份剧本分别喂给 pi 与 pi-java，逐帧比对（{@code docs/23c §2}）。
 *
 * <p>剧本 {@code conformance/scripts/S*.json} 两侧共用；pi 的真相由
 * {@code conformance/pi/run.test.ts} 在 pi 检出（tag {@code v0.85.1}）里生成，落在
 * {@code conformance/pi-out/S*.pi.jsonl}；本测试跑出 {@code conformance/java-out/S*.java.jsonl}
 * 并与前者比对。</p>
 *
 * <p>重新生成 pi 侧真相：</p>
 * <pre>
 *   cd &lt;pi 检出&gt;/packages/agent
 *   CONFORMANCE_SCRIPTS=&lt;pi-java&gt;/conformance/scripts \
 *   CONFORMANCE_OUT=&lt;pi-java&gt;/conformance/pi-out \
 *   npx vitest --run --config vitest.conformance.config.ts test/conformance/run.test.ts
 * </pre>
 *
 * <p>比对口径见 {@code docs/23c §2.3}（归一化）与 {@code §2.4}（P0/P1/P2 归档）。</p>
 */
class ConformanceTest {

    private static final List<String> SCENARIOS = List.of(
        "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10");

    /** 按剧本声明的放宽规则；未列出的剧本走**严格**逐帧比较。 */
    private static final Map<String, List<ConformanceDiff.Relaxation>> RELAXATIONS = Map.of(
        "S4", List.of(ConformanceDiff.Relaxation.PARALLEL_TOOL_END_ORDER));

    @TestFactory
    Stream<DynamicTest> runsEveryScenarioAgainstPi() {
        return SCENARIOS.stream().map(id -> DynamicTest.dynamicTest(id, () -> verify(id)));
    }

    /** 放宽条款必须仍然生效，否则它就是一条被遗忘的豁免。 */
    @Test
    void declaredRelaxationsAreStillEffective() throws IOException {
        for (var entry : RELAXATIONS.entrySet()) {
            var expected = readFrames(piOutFile(entry.getKey()));
            for (var relaxation : entry.getValue()) {
                assertThat(ConformanceDiff.changes(expected, relaxation))
                    .as("%s 的放宽规则 %s 已不再改变帧序，应当删除", entry.getKey(), relaxation)
                    .isTrue();
            }
        }
    }

    private void verify(String id) throws IOException {
        var script = ConformanceScript.load(scriptsDir().resolve(id + ".json"));
        var actual = ConformanceRunner.run(script);
        writeJavaOut(id, actual);

        var expected = readFrames(piOutFile(id));
        var relaxations = RELAXATIONS.getOrDefault(id, List.of());
        var differences = ConformanceDiff.compare(
            ConformanceDiff.relax(expected, relaxations),
            ConformanceDiff.relax(actual, relaxations));

        assertThat(differences)
            .as("%s（%s）与 pi 的帧序不一致；放宽规则：%s", id, script.name(), relaxations)
            .isEmpty();
    }

    private static Path piOutFile(String id) {
        var file = conformanceRoot().resolve("pi-out").resolve(id + ".pi.jsonl");
        assertThat(Files.exists(file))
            .as("缺少 pi 侧真相 %s —— 先在 pi 检出跑 conformance/pi/run.test.ts", file)
            .isTrue();
        return file;
    }

    private static List<String> readFrames(Path file) throws IOException {
        return List.copyOf(Files.readAllLines(file));
    }

    private static void writeJavaOut(String id, List<String> frames) {
        try {
            var directory = conformanceRoot().resolve("java-out");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(id + ".java.jsonl"),
                String.join("\n", frames) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write java-out/" + id, e);
        }
    }

    private static Path scriptsDir() {
        return conformanceRoot().resolve("scripts");
    }

    /**
     * 差分资料放在仓库根的 {@code conformance/}；Surefire 的工作目录是模块目录，故默认取
     * 上一级。{@code -Dconformance.dir=…} 可覆盖。
     */
    private static Path conformanceRoot() {
        var override = System.getProperty("conformance.dir");
        return override != null ? Path.of(override) : Path.of("..", "conformance");
    }
}
