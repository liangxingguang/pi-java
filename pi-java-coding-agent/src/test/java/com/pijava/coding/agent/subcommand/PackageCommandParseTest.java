package com.pijava.coding.agent.subcommand;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 19 T4（docs/30 §7）：{@code parsePackageCommand} 真值表——flag 闭集、
 * {@code -l} 限定、update 目标归约全冲突表、{@code --extension} 单次门、
 * 默认 self＋skippedNote 臂、{@code uninstall} 别名（pi :397-595 逐字）。
 */
class PackageCommandParseTest {

    private static PackageCommand.Options parse(String command, String... args) {
        return PackageCommand.parse(command, args);
    }

    @Test
    void uninstallAliasesToRemove() {
        var options = parse("uninstall", "npm:foo");
        assertThat(options.command()).isEqualTo("remove");
        assertThat(options.source()).isEqualTo("npm:foo");
    }

    @Test
    void localFlagOnlyValidForInstallRemove() {
        assertThat(parse("install", "-l", "npm:foo").local()).isTrue();
        assertThat(parse("remove", "-l", "npm:foo").local()).isTrue();
        assertThat(parse("update", "-l").invalidOption()).isEqualTo("-l");
        assertThat(parse("list", "--local").invalidOption()).isEqualTo("--local");
    }

    @Test
    void updateFlagsRejectedElsewhere() {
        assertThat(parse("install", "--self", "x").invalidOption()).isEqualTo("--self");
        assertThat(parse("list", "--extensions").invalidOption()).isEqualTo("--extensions");
        assertThat(parse("list", "--models").invalidOption()).isEqualTo("--models");
        assertThat(parse("remove", "--all", "x").invalidOption()).isEqualTo("--all");
        assertThat(parse("install", "--force", "x").invalidOption()).isEqualTo("--force");
        assertThat(parse("list", "--extension", "x").invalidOption()).isEqualTo("--extension");
    }

    @Test
    void approveFlagsSetTrustOverride() {
        assertThat(parse("install", "-a", "x").projectTrustOverride()).isTrue();
        assertThat(parse("install", "--approve", "x").projectTrustOverride()).isTrue();
        assertThat(parse("install", "-na", "x").projectTrustOverride()).isFalse();
        assertThat(parse("install", "--no-approve", "x").projectTrustOverride()).isFalse();
        assertThat(parse("install", "x").projectTrustOverride()).isNull();
    }

    @Test
    void extensionFlagAcceptsExactlyOneValue() {
        var once = parse("update", "--extension", "npm:foo");
        assertThat(once.updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.Extensions("npm:foo"));

        var twice = parse("update", "--extension", "npm:a", "--extension", "npm:b");
        assertThat(twice.conflictingOptions()).isEqualTo("--extension can only be provided once");
        // pi：第二枚的值仍被消费（index++）
        assertThat(twice.invalidArgument()).isNull();

        var missing = parse("update", "--extension");
        assertThat(missing.missingOptionValue()).isEqualTo("--extension");
        var dashValue = parse("update", "--extension", "--self");
        assertThat(dashValue.missingOptionValue()).isEqualTo("--extension");
    }

    @Test
    void updateDefaultIsSelfWithSkippedNote() {
        var options = parse("update");
        assertThat(options.updateTarget()).isEqualTo(new PackageCommand.UpdateTarget.Self());
        assertThat(options.showExtensionsSkippedNote()).isTrue();
    }

    @Test
    void updateTargetReductionTruthTable() {
        // self/pi 位置形
        assertThat(parse("update", "self").updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.Self());
        assertThat(parse("update", "pi").updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.Self());
        // 带 --extensions 升格 all
        assertThat(parse("update", "pi", "--extensions").updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.All());
        // 无 source 时 --self --extensions 同给 ⇒ all
        assertThat(parse("update", "--self", "--extensions").updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.All());
        assertThat(parse("update", "--all").updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.All());
        assertThat(parse("update", "--self").updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.Self());
        assertThat(parse("update", "--extensions").updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.Extensions(null));
        assertThat(parse("update", "npm:foo").updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.Extensions("npm:foo"));
        assertThat(parse("update", "--models").updateTarget())
            .isEqualTo(new PackageCommand.UpdateTarget.Models());
    }

    @Test
    void updateConflictTable() {
        assertThat(parse("update", "--all", "--self").conflictingOptions()).isEqualTo(
            "--all cannot be combined with --self, --extensions, --models, or --extension");
        assertThat(parse("update", "--all", "npm:x").conflictingOptions()).isEqualTo(
            "--all cannot be combined with a positional source");
        // pi :529 的 --all 冲突检查先于 --models（:537）且用 ?? 保留首条 ⇒ --all 文案胜。
        assertThat(parse("update", "--models", "--all").conflictingOptions()).isEqualTo(
            "--all cannot be combined with --self, --extensions, --models, or --extension");
        assertThat(parse("update", "--models", "--extensions").conflictingOptions()).isEqualTo(
            "--models cannot be combined with --self, --extensions, --all, or --extension");
        assertThat(parse("update", "--models", "npm:x").conflictingOptions()).isEqualTo(
            "--models cannot be combined with a positional source");
        assertThat(parse("update", "--extension", "npm:x", "--self").conflictingOptions())
            .isEqualTo("--extension cannot be combined with --self, --extensions, or --all");
        assertThat(parse("update", "--extension", "npm:x", "npm:y").conflictingOptions())
            .isEqualTo("--extension cannot be combined with a positional source");
        assertThat(parse("update", "npm:x", "--self").conflictingOptions()).isEqualTo(
            "positional update targets cannot be combined with --self, --extensions, or --all");
    }

    @Test
    void unknownFlagAndExtraPositionalRecorded() {
        assertThat(parse("install", "--bogus", "x").invalidOption()).isEqualTo("--bogus");
        assertThat(parse("install", "a", "b").invalidArgument()).isEqualTo("b");
    }

    @Test
    void helpFlagWins() {
        assertThat(parse("update", "-h", "--bogus").help()).isTrue();
        assertThat(parse("list", "--help").help()).isTrue();
    }

    @Test
    void unknownCommandParsesToNull() {
        assertThat(PackageCommand.parse("frobnicate", new String[0])).isNull();
    }
}
