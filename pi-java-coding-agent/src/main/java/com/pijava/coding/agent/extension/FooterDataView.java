package com.pijava.coding.agent.extension;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 批 17（T1，docs/28 §3-T6.2）：footer 工厂的数据视图——pi
 * {@code ReadonlyFooterDataProvider} 的 java 窄化投影（窄差 D100）。
 *
 * @param gitBranch         git 分支名源（无仓库信息 ⇒ null）
 * @param extensionStatuses 扩展状态表只读视图（{@code setStatus} 的累积，现读）
 */
public record FooterDataView(
    Supplier<String> gitBranch,
    Supplier<Map<String, String>> extensionStatuses) {
}
