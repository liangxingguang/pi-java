package com.pijava.coding.agent.extension.mcp;

import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.runtime.McpServerConnection;

/**
 * 批 24（docs/35）：一个已配置服务器的会话内状态（pi {@code McpServer} 内部件，
 * {@code index.ts:112-128} 逐字对位）。字段全部可变——生命周期事件就地改写。
 *
 * <p>线程纪律：读写在事件处理线程与虚拟线程后台任务间共享，字段用
 * {@code volatile}；复合不变量由调用序保证（pi 单线程 ⇒ java 侧所有入口
 * 都在会话事件线程或经 {@code attempt} 换代闸守卫）。</p>
 */
final class McpServerHandle {

    /** 当前生效的条目（/mcp 动作会替换 config）。 */
    volatile McpServerEntry entry;

    /** 现连接；disabled ⇒ null。 */
    volatile @Nullable McpServerConnection connection;

    /** 扩展注册服务器的注册时配置快照（JSON 串），侦测再注册（config 变 ⇒ 重连）。 */
    volatile @Nullable String registeredConfig;

    /** 扩展注册服务器的原始 extensionPath（Windows 冒号路径不能进 {@link java.nio.file.Path}，菜单显示用原文）。 */
    volatile @Nullable String extensionPath;

    /** 最近一次失败的 /mcp 动作的结果文案，管理器显示。 */
    volatile @Nullable String message;

    /** 当前连接尝试的令牌；disable/替换使早先尝试失效（pi {@code attempt?: symbol}）。 */
    volatile @Nullable Object attempt;

    /** 当前尝试的连接落地（成功或失败）即完成（永不失败完成）。 */
    volatile @Nullable CompletableFuture<Void> ready;

    /** 上一次关闭的清理；替换连接等它完再开传输。 */
    volatile @Nullable CompletableFuture<Void> closing;

    McpServerHandle(McpServerEntry entry) {
        this.entry = entry;
    }
}
