package com.pijava.web;

import java.util.List;

import com.pijava.coding.agent.core.source.SourceInfo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扩展面板数据源：会话已加载视图（sourceInfo）与 resolve 已安装视图（JAR 清单）
 * 的对齐、命名回落与「已加载 / 未加载」分档。
 */
class WebExtensionsTest {

    private static SourceInfo loaded(String path, String source, SourceInfo.Scope scope) {
        return new SourceInfo(path, source, scope, SourceInfo.Origin.TOP_LEVEL, null);
    }

    @Test
    void builtinExtensionTakesNameFromSyntheticPath() {
        var rows = WebExtensions.list(
            List.of(loaded("builtin:hello", "builtin", SourceInfo.Scope.TEMPORARY)), List.of());

        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.name()).isEqualTo("hello");
        assertThat(row.path()).isEqualTo("builtin:hello");
        assertThat(row.source()).isEqualTo("builtin");
        assertThat(row.scope()).isEqualTo("temporary");
        assertThat(row.origin()).isEqualTo("top-level");
        assertThat(row.builtin()).isTrue();
        assertThat(row.loaded()).isTrue();
        // 合成身份没有 JAR 清单：内容四表为空串/空表，不抛异常。
        assertThat(row.version()).isEmpty();
        assertThat(row.description()).isEmpty();
        assertThat(row.tools()).isEmpty();
    }

    @Test
    void jarExtensionWithoutManifestFallsBackToFileName() {
        var jar = "/tmp/extensions/demo.jar";
        var rows = WebExtensions.list(
            List.of(loaded(jar, "local", SourceInfo.Scope.USER)), List.of(jar));

        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.name()).isEqualTo("demo");
        assertThat(row.builtin()).isFalse();
        assertThat(row.loaded()).isTrue();
        assertThat(row.scope()).isEqualTo("user");
    }

    @Test
    void installedButNotLoadedIsReportedAsIdleRow() {
        var rows = WebExtensions.list(List.of(), List.of("/tmp/extensions/ghost.jar"));

        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.name()).isEqualTo("ghost");
        assertThat(row.loaded()).isFalse();
        // 没有 sourceInfo ⇒ 来源三档缺席（wire 上省略键）。
        assertThat(row.source()).isNull();
        assertThat(row.scope()).isNull();
        assertThat(row.origin()).isNull();
    }

    @Test
    void loadedAndInstalledViewsAreMatchedByPathWithoutDuplicates() {
        var jar = "/tmp/extensions/both.jar";
        var rows = WebExtensions.list(
            List.of(loaded(jar, "local", SourceInfo.Scope.PROJECT)), List.of(jar));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).loaded()).isTrue();
        assertThat(rows.get(0).scope()).isEqualTo("project");
    }

    @Test
    void loadedRowsSortBeforeIdleRows() {
        var loadedJar = "/tmp/extensions/zeta.jar";
        var rows = WebExtensions.list(
            List.of(loaded(loadedJar, "local", SourceInfo.Scope.USER)),
            List.of(loadedJar, "/tmp/extensions/alpha.jar"));

        assertThat(rows).extracting(WebProtocol.ExtensionInfo::name)
            .containsExactly("zeta", "alpha");
        assertThat(rows).extracting(WebProtocol.ExtensionInfo::loaded)
            .containsExactly(true, false);
    }

    @Test
    void nullInputsYieldEmptyList() {
        assertThat(WebExtensions.list(null, null)).isEmpty();
    }
}
