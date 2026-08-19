package com.pijava.coding.agent.extension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.ai.provider.FauxProvider;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * 测试用扩展 —— ServiceLoader 装配验证：注册一个 /sample 命令 + 一个 FauxProvider。
 */
public final class TestExtension implements PiExtension {

    @Override
    public String name() {
        return "test-ext";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(new SlashCommand() {
            @Override
            public String name() {
                return "sample";
            }

            @Override
            public String description() {
                return "Test extension command";
            }

            @Override
            public String argumentHint() {
                return "";
            }

            @Override
            public CompletionStage<String> execute(String args, SlashContext context) {
                return CompletableFuture.completedFuture("done");
            }
        });
        ctx.providers().register(FauxProvider.text("extension-hello"));
    }
}
