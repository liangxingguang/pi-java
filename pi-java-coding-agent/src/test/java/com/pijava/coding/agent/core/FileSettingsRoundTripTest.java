package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B139: settings 嵌套块经真 {@link FileSettingsStorage} 的持久化往返。
 *
 * <p>mapper 关了 GETTER 可见性，record 组件访问器被忽略 ⇒ 嵌套块序列化成
 * {@code {}}。每个用例都走真实文件（@TempDir），不走内存替身。</p>
 */
class FileSettingsRoundTripTest {

    private static final ObjectMapper MAPPER = Json.mapper();

    private FileSettingsStorage storage(Path dir) {
        return new FileSettingsStorage(dir, dir);
    }

    private Settings filledSettings() {
        var s = new Settings();
        var overrides = new LinkedHashMap<String, Settings.Compaction.ModelOverride>();
        overrides.put("faux/small", new Settings.Compaction.ModelOverride(1_024, 512));
        s.compaction = new Settings.Compaction(true, 16_384, 20_000, overrides);
        s.terminal = new Settings.Terminal(true, 60, false, false,
                "auto", "auto", "auto");
        s.images = new Settings.Image(true, false);
        s.markdown = new Settings.Markdown("  ", "streaming");
        s.tui = new Settings.Tui("wheel", 3, 3, 1, 30, 3, false, 12, 200, false);
        return s;
    }

    @Test
    void allFiveBlocksRoundTripThroughRealFile(@TempDir Path dir) {
        storage(dir).writeGlobal(filledSettings());

        var reloaded = SettingsManager.withStorage(storage(dir)).effective();

        assertThat(reloaded.compaction.enabled()).isTrue();
        assertThat(reloaded.compaction.reserveTokens()).isEqualTo(16_384);
        assertThat(reloaded.compaction.keepRecentTokens()).isEqualTo(20_000);
        var override = reloaded.compaction.modelOverrides().get("faux/small");
        assertThat(override.reserveTokens()).isEqualTo(1_024);
        assertThat(override.keepRecentTokens()).isEqualTo(512);

        assertThat(reloaded.terminal.showImages()).isTrue();
        assertThat(reloaded.terminal.imageWidthCells()).isEqualTo(60);
        assertThat(reloaded.terminal.clearOnShrink()).isFalse();
        assertThat(reloaded.terminal.showTerminalProgress()).isFalse();
        assertThat(reloaded.terminal.hyperlinks()).isEqualTo("auto");
        assertThat(reloaded.terminal.images()).isEqualTo("auto");
        assertThat(reloaded.terminal.trueColor()).isEqualTo("auto");

        assertThat(reloaded.images.autoResize()).isTrue();
        assertThat(reloaded.images.blockImages()).isFalse();
        assertThat(reloaded.markdown.codeBlockIndent()).isEqualTo("  ");
        assertThat(reloaded.markdown.mermaid()).isEqualTo("streaming");

        assertThat(reloaded.tui.scrollMode()).isEqualTo("wheel");
        assertThat(reloaded.tui.scrollEventsPerTick()).isEqualTo(3);
        assertThat(reloaded.tui.scrollWheelLines()).isEqualTo(3);
        assertThat(reloaded.tui.scrollTrackpadLines()).isEqualTo(1);
        assertThat(reloaded.tui.scrollTrackpadAccelEvents()).isEqualTo(30);
        assertThat(reloaded.tui.scrollTrackpadAccelMax()).isEqualTo(3);
        assertThat(reloaded.tui.scrollInvert()).isFalse();
        assertThat(reloaded.tui.scrollWheelTickDetectMaxMs()).isEqualTo(12);
        assertThat(reloaded.tui.scrollWheelLikeMaxDurationMs()).isEqualTo(200);
        assertThat(reloaded.tui.disableMouseCapture()).isFalse();
    }

    @Test
    void partialNestedBlocksMergeWithoutZeroClobber(@TempDir Path dir) throws Exception {
        // global：只给 compaction 两个子键
        Files.writeString(dir.resolve("settings.json"), """
            {"compaction":{"enabled":true,"reserveTokens":16384},
             "images":{"autoResize":false}}""");
        // project：只给 compaction 的第三个子键
        Files.createDirectories(dir.resolve(".pi-java"));
        Files.writeString(dir.resolve(".pi-java/settings.json"),
            """
            {"compaction":{"keepRecentTokens":5000}}""");

        var effective = SettingsManager.load(dir, dir, true).effective();

        assertThat(effective.compaction.enabled()).isTrue();
        assertThat(effective.compaction.reserveTokens()).isEqualTo(16_384);
        assertThat(effective.compaction.keepRecentTokens()).isEqualTo(5_000);
        // 未设置的子键必须仍是 null，不能被 primitive 默认值塌成 false。
        assertThat(effective.images.autoResize()).isFalse();
        assertThat(effective.images.blockImages()).isNull();
    }

    @Test
    void piShapedFileSurvivesAnUnrelatedFlush(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), """
            {"compaction":{"enabled":true,"reserveTokens":16384,"keepRecentTokens":20000,
              "modelOverrides":{"faux/small":{"reserveTokens":1024}}},
             "terminal":{"showImages":true,"imageWidthCells":60,"clearOnShrink":false,
               "showTerminalProgress":false,"hyperlinks":"auto","images":"auto","trueColor":"auto"},
             "images":{"autoResize":true,"blockImages":false},
             "markdown":{"codeBlockIndent":"  ","mermaid":"streaming"},
             "tui":{"scrollMode":"wheel","scrollEventsPerTick":3,"scrollWheelLines":3,
               "scrollTrackpadLines":1,"scrollTrackpadAccelEvents":30,"scrollTrackpadAccelMax":3,
               "scrollInvert":false,"scrollWheelTickDetectMaxMs":12,
               "scrollWheelLikeMaxDurationMs":200,"disableMouseCapture":false},
             "futureField":42}""");

        var manager = SettingsManager.withStorage(storage(dir));
        manager.accessors().setTheme("dark");
        manager.flush();

        JsonNode root = MAPPER.readTree(dir.resolve("settings.json").toFile());
        assertThat(root.path("theme").asText()).isEqualTo("dark");
        assertThat(root.path("futureField").asInt()).isEqualTo(42);
        var compaction = root.path("compaction");
        assertThat(compaction.path("enabled").asBoolean()).isTrue();
        assertThat(compaction.path("reserveTokens").asInt()).isEqualTo(16_384);
        assertThat(compaction.path("keepRecentTokens").asInt()).isEqualTo(20_000);
        assertThat(compaction.path("modelOverrides").path("faux/small").path("reserveTokens")
            .asInt()).isEqualTo(1_024);
        var terminal = root.path("terminal");
        assertThat(terminal.path("showImages").asBoolean()).isTrue();
        assertThat(terminal.path("imageWidthCells").asInt()).isEqualTo(60);
        assertThat(terminal.path("hyperlinks").asText()).isEqualTo("auto");
        assertThat(terminal.path("images").asText()).isEqualTo("auto");
        assertThat(terminal.path("trueColor").asText()).isEqualTo("auto");
        var images = root.path("images");
        assertThat(images.path("autoResize").asBoolean()).isTrue();
        assertThat(images.path("blockImages").asBoolean()).isFalse();
        var markdown = root.path("markdown");
        assertThat(markdown.path("codeBlockIndent").asText()).isEqualTo("  ");
        assertThat(markdown.path("mermaid").asText()).isEqualTo("streaming");
        var tui = root.path("tui");
        assertThat(tui.path("scrollMode").asText()).isEqualTo("wheel");
        assertThat(tui.path("disableMouseCapture").asBoolean()).isFalse();
    }

    @Test
    void markdownAcceptsPiTypes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"),
            """
            {"markdown":{"codeBlockIndent":"   ","mermaid":"final"}}""");

        var effective = SettingsManager.withStorage(storage(dir)).effective();

        assertThat(effective.markdown.codeBlockIndent()).isEqualTo("   ");
        assertThat(effective.markdown.mermaid()).isEqualTo("final");
    }

    @Test
    void partialBlocksEmitNoNullPlaceholderKeys(@TempDir Path dir) throws Exception {
        var s = new Settings();
        s.images = new Settings.Image(null, true);
        storage(dir).writeGlobal(s);

        String text = Files.readString(dir.resolve("settings.json"));
        JsonNode images = MAPPER.readTree(text).path("images");
        assertThat(images.fieldNames()).toIterable()
            .containsExactly("blockImages");
        assertThat(images.path("blockImages").asBoolean()).isTrue();
    }
}
