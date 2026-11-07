package com.pijava.coding.agent.pkg;

import java.util.List;

/**
 * pi {@code ResolvedPaths}：四类资源的有序路径表。
 *
 * <p>L5 W5 §6.1（pi package-manager.ts:92-98）：{@code installFailures} 记录
 * resolve 期间装不上的配置包——启动不中止，消费方（{@code SessionSetup}
 * 汇总进启动诊断、{@code ConfigCommand} 打黄字警告）据此报告。</p>
 */
public record ResolvedPaths(
    List<ResolvedResource> extensions,
    List<ResolvedResource> skills,
    List<ResolvedResource> prompts,
    List<ResolvedResource> themes,
    List<PackageInstallFailure> installFailures
) {

    /** 四表旧形状（无失败表——pi 的 {@code installFailures?} 可缺省同义）。 */
    public ResolvedPaths(List<ResolvedResource> extensions, List<ResolvedResource> skills,
                         List<ResolvedResource> prompts, List<ResolvedResource> themes) {
        this(extensions, skills, prompts, themes, List.of());
    }

    public ResolvedPaths {
        installFailures = installFailures == null ? List.of() : List.copyOf(installFailures);
    }

    public List<ResolvedResource> ofType(ResourceType type) {
        return switch (type) {
            case EXTENSIONS -> extensions;
            case SKILLS -> skills;
            case PROMPTS -> prompts;
            case THEMES -> themes;
        };
    }
}
