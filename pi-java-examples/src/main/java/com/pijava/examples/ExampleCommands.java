package com.pijava.examples;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * pi {@code registerCommand(name, {description, handler})} 的最小对位（docs/51）——
 * 命令处理器只做编排，返回值恒为空串（pi 的 {@code Promise<void>}）。
 *
 * <p>pi 的 handler 第二参是 {@code ExtensionCommandContext}；java 侧命令走
 * {@link SlashCommand#execute} 的 {@link SlashContext}，扩展的既有写法是在
 * register 期捕获 per-extension {@code ExtensionContext}（裁决⑤先例），故本工厂
 * 的处理器只接收 {@code args}。</p>
 */
final class ExampleCommands {

    private ExampleCommands() {}

    /** pi 的 {@code async (args, ctx) => void} 形处理器。 */
    @FunctionalInterface
    interface Handler {
        void handle(String args);
    }

    /** 注册形状 {@code {description, handler}} 与 pi 一致（无参数提示）。 */
    static SlashCommand command(String name, String description, Handler handler) {
        return command(name, description, "", handler);
    }

    /** 带参数提示的形（命令帮助用）。 */
    static SlashCommand command(String name, String description, String argumentHint,
                                Handler handler) {
        return new SlashCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String argumentHint() {
                return argumentHint;
            }

            @Override
            public CompletionStage<String> execute(String args, SlashContext context) {
                handler.handle(args);
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
