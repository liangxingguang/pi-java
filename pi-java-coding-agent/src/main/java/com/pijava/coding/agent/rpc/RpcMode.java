package com.pijava.coding.agent.rpc;

import java.io.InputStream;
import java.io.OutputStream;

import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.core.AgentSession;

/**
 * RPC 模式入口 —— 从 stdin 读 JSONL 命令、向 stdout 写 JSONL 响应与事件，
 * 常驻直到 EOF。headless 集成用（对齐 pi {@code rpc-mode.ts}）。
 */
public final class RpcMode {

    private RpcMode() {}

    /**
     * 阻塞处理 stdin 直到 EOF。
     *
     * @return 进程退出码
     */
    public static int run(InputStream in, OutputStream out, Args args) {
        return run(in, out, args, null, null);
    }

    /**
     * 测试用重载：注入 {@link ProviderRegistry} 与 {@link ToolContext}
     * （FauxProvider 驱动的端到端测试避免真实网络）。
     */
    static int run(InputStream in, OutputStream out, Args args,
                   ProviderRegistry providers, ToolContext toolContext) {
        try (var session = providers == null
                ? AgentSession.create(args)
                : AgentSession.create(args, providers, toolContext)) {
            var reader = new JsonlReader(in);
            var writer = new JsonlWriter(out);
            var dispatcher = new RpcDispatcher(session, writer, args);
            String line;

            while ((line = reader.readLine()) != null) {
                dispatcher.handleLine(line);
            }
            dispatcher.close();
            return 0;
        } catch (Exception e) {
            System.err.println("rpc error: " + e.getMessage());
            return 1;
        }
    }
}
