package com.pijava.coding.agent.pkg;

/**
 * pi {@code ConfiguredPackage}（package-manager.ts:108-113）——{@code list} 命令的
 * 配置包视图：{@code filtered}＝对象形（带资源过滤）；{@code installedPath} 未装
 * ⇒ {@code null}。
 */
public record ConfiguredPackage(String source, SourceScope scope, boolean filtered,
        String installedPath) {}
