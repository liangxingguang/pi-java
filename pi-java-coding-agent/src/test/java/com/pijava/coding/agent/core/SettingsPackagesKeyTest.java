package com.pijava.coding.agent.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.coding.agent.pkg.NpmCommand;

/**
 * 批 18 T1（docs/29 §3）：settings {@code packages}/{@code npmCommand} 两键的
 * 声明＋解析＋校验面（E2-20/E2-26）。
 */
class SettingsPackagesKeyTest {

    @Test
    void packagesAbsentYieldsEmptyList() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());

        assertThat(manager.accessors().getPackages()).isEmpty();
    }

    @Test
    void packagesRoundTripThroughAccessor() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.packages = List.of(PackageSource.of("npm:foo"));
        storage.writeGlobal(global);
        var manager = SettingsManager.withStorage(storage);

        var packages = manager.accessors().getPackages();
        assertThat(packages).hasSize(1);
        assertThat(packages.get(0).source()).isEqualTo("npm:foo");
        // 防御性拷贝（pi :1077 展开拷贝对位）。
        assertThatThrownBy(() -> packages.add(PackageSource.of("npm:bar")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void npmCommandAbsentYieldsNull() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());

        assertThat(manager.accessors().getNpmCommand()).isNull();
    }

    @Test
    void npmCommandReturnsDefensiveCopy() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.npmCommand = new java.util.ArrayList<>(List.of("mise", "exec", "--", "npm"));
        storage.writeGlobal(global);
        var manager = SettingsManager.withStorage(storage);

        var command = manager.accessors().getNpmCommand();
        assertThat(command).containsExactly("mise", "exec", "--", "npm");
        assertThatThrownBy(() -> command.add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void npmCommandResolveDefaultsToNpm() {
        assertThat(NpmCommand.resolve(null)).isEqualTo(NpmCommand.DEFAULT);
        assertThat(NpmCommand.resolve(List.of())).isEqualTo(NpmCommand.DEFAULT);
        assertThat(NpmCommand.DEFAULT.command()).isEqualTo("npm");
        assertThat(NpmCommand.DEFAULT.args()).isEmpty();
    }

    @Test
    void npmCommandResolveSplitsCommandAndArgs() {
        var resolved = NpmCommand.resolve(List.of("mise", "exec", "node@20", "--", "npm"));

        assertThat(resolved.command()).isEqualTo("mise");
        assertThat(resolved.args()).containsExactly("exec", "node@20", "--", "npm");
    }

    @Test
    void npmCommandEmptyFirstEntryThrowsVerbatim() {
        // pi package-manager.ts:1787-1789 模型可见文案逐字。
        assertThatThrownBy(() -> NpmCommand.resolve(List.of("", "install")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid npmCommand: first array entry must be a non-empty command");
    }
}
