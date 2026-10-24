package com.pijava.coding.agent.spi;

import com.pijava.coding.agent.pkg.ResolvedPaths;

/**
 * pi {@code ScopedResolvedPaths}（config-selector.ts:31）——config TUI 的双份
 * resolve 结果：global 份（信任恒拒，project 资源全剔除）＋ project 份
 * （真信任；未信任 ⇒ 复用 global 副本）。
 */
public record ScopedResolvedPaths(ResolvedPaths global, ResolvedPaths project) {}
