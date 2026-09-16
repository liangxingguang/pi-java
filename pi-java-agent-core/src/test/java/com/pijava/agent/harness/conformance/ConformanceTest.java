package com.pijava.agent.harness.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
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
 * <p>比对口径见 {@code docs/23c §2.3}（归一化）与 {@code §2.4}（P0/P1/P2 归档）：
 * 全部剧本**严格**逐帧比较 —— 曾有的唯一放宽规则（S4 的
 * {@code PARALLEL_TOOL_END_ORDER}）已随 {@code ToolRunner} 的两相拆分删除。</p>
 *
 * <p><b>剧本里的延迟是声明出来的</b>（{@code docs/31 §8.23.7}）：并行批次的
 * {@code tool_execution_end} 按**完成序**发射，两个等延迟的调用谁先完成在两侧都不可约
 * （pi 侧是 JS 微任务队列的副产品，Java 侧是真线程竞速）。所以凡是同一批里有多个调用
 * 会同跑的剧本，都要用 {@code delayMs} 把完成序写死 —— 否则差分测的是运行时运气，
 * 而不是被测对象。</p>
 */
class ConformanceTest {

    private static final List<String> SCENARIOS = List.of(
        "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10", "S11", "S12", "S13", "S14");

    @TestFactory
    Stream<DynamicTest> runsEveryScenarioAgainstPi() {
        return SCENARIOS.stream().map(id -> DynamicTest.dynamicTest(id, () -> verify(id)));
    }

    private void verify(String id) throws IOException {
        var script = ConformanceScript.load(scriptsDir().resolve(id + ".json"));
        var actual = ConformanceRunner.run(script);
        writeJavaOut(id, actual);

        var differences = ConformanceDiff.compare(readFrames(piOutFile(id)), actual);

        assertThat(differences)
            .as("%s（%s）与 pi 的帧序不一致", id, script.name())
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
