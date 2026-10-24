package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;

/**
 * npm 包解析/安装 seam（批 18 裁决③）。本批实装 {@link HttpNpmResolver}
 * （HTTP registry API 直拉）；将来 {@code SpawnNpmResolver}（pi 逐字的 npm CLI
 * 臂，{@code npmCommand} 键的消费点）实现同一接口插入。
 */
public interface NpmResolver {

    /** 包的全部已发布版本（pi {@code npm view <name> version --json} 数组臂对位）。 */
    List<String> versions(String name) throws IOException;

    /**
     * pi {@code getLatestNpmVersion}（:1545-1564）对位：有 range 取
     * maxSatisfying，无则按 rcompare 取首；无满足 ⇒ 抛。
     */
    String latestSatisfying(String name, String range) throws IOException;

    /**
     * 安装到 {@code <installRoot>/node_modules/<name>}，返回安装目录。
     * pinned（精确版本）源不查元数据直接下载（docs/29 §3 T3）。
     */
    Path install(NpmSource source, Path installRoot) throws IOException;

    /**
     * 卸载（批 19 T1，pi {@code uninstallNpm} :1874-1890 对位）。pi 走 npm CLI
     * {@code uninstall <name> --prefix <root>}；HTTP 臂对位＝删
     * {@code <installRoot>/node_modules/<name>}（窄差 D113）。{@code installRoot}
     * 不存在 ⇒ no-op（pi {@code existsSync} 门）。
     */
    void uninstall(String name, Path installRoot) throws IOException;
}
