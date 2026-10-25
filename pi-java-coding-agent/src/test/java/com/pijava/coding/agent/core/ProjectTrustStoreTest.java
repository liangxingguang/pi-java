package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 23（docs/34 T3，裁决②）：{@link ProjectTrustStore}——pi trust-manager.ts
 * 的线格式（键排序＋2 空格缩进＋尾换行）、最近祖先继承、null 删键、读校验、
 * 并发 RMW（文件锁）。
 */
class ProjectTrustStoreTest {

    @TempDir
    Path agentDir;

    @Test
    void wireFormatIsSortedIndentedTrailingNewline() throws Exception {
        var store = new ProjectTrustStore(agentDir);
        store.set(Path.of("/b/proj"), true);
        store.set(Path.of("/a/proj"), false);
        var text = Files.readString(store.trustPath());
        var keyA = ProjectTrustStore.normalizeCwd(Path.of("/a/proj"));
        var keyB = ProjectTrustStore.normalizeCwd(Path.of("/b/proj"));
        // 逐字节对位 pi 的 JSON.stringify(sorted, null, 2) + "\n"。
        var expected = "{\n  \"" + keyA.replace("\\", "\\\\") + "\": false,\n  \""
            + keyB.replace("\\", "\\\\") + "\": true\n}\n";
        assertThat(text).isEqualTo(expected);
    }

    @Test
    void nearestAncestorInherits() {
        var store = new ProjectTrustStore(agentDir);
        var parent = Path.of(System.getProperty("java.io.tmpdir")).resolve("pi-trust-parent");
        var child = parent.resolve("child");
        store.set(parent, true);
        assertThat(store.get(child)).isTrue();
        // 子级自有决策压过祖先。
        store.set(child, false);
        assertThat(store.get(child)).isFalse();
        assertThat(store.get(parent)).isTrue();
    }

    @Test
    void nullDecisionDeletesKey() {
        var store = new ProjectTrustStore(agentDir);
        var cwd = Path.of("/x/proj");
        store.set(cwd, true);
        assertThat(store.get(cwd)).isTrue();
        store.set(cwd, null);
        assertThat(store.get(cwd)).isNull();
        assertThat(store.snapshotForTests()).isEmpty();
    }

    @Test
    void missingFileReadsEmpty() {
        var store = new ProjectTrustStore(agentDir);
        assertThat(store.get(Path.of("/anything"))).isNull();
    }

    @Test
    void invalidFileThrowsVerbatim() throws Exception {
        Files.writeString(agentDir.resolve("trust.json"), "[1,2]");
        var store = new ProjectTrustStore(agentDir);
        assertThatThrownBy(() -> store.get(Path.of("/x")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expected an object");
    }

    @Test
    void nonBooleanValueThrowsVerbatim() throws Exception {
        Files.writeString(agentDir.resolve("trust.json"), "{\"/x\": \"yes\"}");
        var store = new ProjectTrustStore(agentDir);
        assertThatThrownBy(() -> store.get(Path.of("/x")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be true, false, or null");
    }

    @Test
    void concurrentReadModifyWriteKeepsBothKeys() throws Exception {
        var store = new ProjectTrustStore(agentDir);
        int n = 8;
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int idx = i;
                pool.submit(() -> {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    store.set(Path.of("/proj/" + idx), idx % 2 == 0);
                });
            }
            latch.countDown();
        }
        assertThat(store.snapshotForTests()).hasSize(n);
    }

    @Test
    void parentFolderOptionDeletesSelfKey() {
        // pi trust-manager.ts:73-82：Trust parent ⇒ 父=true＋cwd 自身删键。
        var cwd = Path.of("/deep/nested/proj");
        var options = ProjectTrustOption.of(cwd, true);
        var parentOption = options.stream()
            .filter(o -> o.label().startsWith("Trust parent folder")).findFirst().orElseThrow();
        var store = new ProjectTrustStore(agentDir);
        store.set(cwd, false); // 既有自身条目
        store.setMany(parentOption.updates());
        assertThat(store.snapshotForTests())
            .doesNotContainKey(ProjectTrustStore.normalizeCwd(cwd));
        assertThat(store.get(cwd)).isTrue(); // 经父级继承
    }

    @Test
    void sessionOnlyOptionsHaveNoUpdates() {
        var options = ProjectTrustOption.of(Path.of("/x/y"), true);
        var sessionOnly = options.stream()
            .filter(o -> o.label().contains("this session only")).toList();
        assertThat(sessionOnly).hasSize(2);
        assertThat(sessionOnly).allMatch(o -> o.updates().isEmpty());
        assertThat(sessionOnly).allMatch(o -> o.savedPath() == null);
        // 五选项序逐字（trust-manager.ts:70-93）；parent 标签用规范化绝对路径的父级
        //（Windows 上 "/x/y" 绝对化补当前盘符）。
        var normalizedParent = Path.of(
            ProjectTrustStore.normalizeCwd(Path.of("/x/y"))).getParent();
        assertThat(options.stream().map(ProjectTrustOption::label).toList())
            .containsExactly("Trust",
                "Trust parent folder (" + normalizedParent + ")",
                "Trust (this session only)",
                "Do not trust",
                "Do not trust (this session only)");
    }
}
