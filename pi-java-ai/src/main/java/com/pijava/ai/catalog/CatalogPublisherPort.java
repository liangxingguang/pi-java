package com.pijava.ai.catalog;

/**
 * pi {@code RefreshModelsContext.publish} 的 Java 端口（docs/70 §4.1）。
 *
 * <p>返回「本次发布是否被接受」—— 实现方按 {@code providerId} 的世代号检查：
 * 过期刷新（refresh 期间又发起了新的 refresh）的发布被拒，{@code update}
 * 不跑。落点是 {@code CatalogRefreshCoordinator}。</p>
 *
 * <p>与 pi 的差异：pi 返回 {@code Promise<boolean>}（持久化是异步的）；
 * Java 侧 coordinator 的持久化是同步文件写，故端口是同步的。</p>
 */
@FunctionalInterface
public interface CatalogPublisherPort {

    /** 发布一次目录变更；返回世代检查是否接受。 */
    boolean publish(ModelsPublication publication);
}
