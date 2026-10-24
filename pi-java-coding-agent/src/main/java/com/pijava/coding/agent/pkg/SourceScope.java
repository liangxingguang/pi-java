package com.pijava.coding.agent.pkg;

import java.util.Locale;

/**
 * pi {@code SourceScope}（package-manager.ts：{@code "user" | "project" |
 * "temporary"}）——纯常量闭集 ⇒ enum（ADT 规范）。
 *
 * <ul>
 *   <li>{@code user}：装进 agentDir 下的安装根。</li>
 *   <li>{@code project}：装进 {@code <cwd>/.pi-java} 下（过信任门）。</li>
 *   <li>{@code temporary}：{@code -e} 等 CLI 显式源；装进
 *       {@code agentDir/tmp/extensions/} 下的哈希目录，不写 settings。</li>
 * </ul>
 */
public enum SourceScope {
    USER,
    PROJECT,
    TEMPORARY;

    /** pi 字符串形（日志/诊断用）。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
