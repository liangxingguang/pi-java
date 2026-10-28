package com.pijava.examples;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;

/**
 * pi {@code examples/extensions/pirate.ts:15-47} 移植（docs/40 P10）——
 * {@code /pirate} 切换开关（通知文案逐字）；开启后 before_agent_start 追加海盗段
 * （前导 {@code \n\n} 与尾随 {@code \n} 逐字保留）；关闭返回 null（≙ undefined）。
 */
public final class PirateExtension implements PiExtension {

    /**
     * pi 的模板字面量逐字（前导两换行＋尾随一换行——文本块首换行被剥，
     * 两个空行补回 {@code \n\n}）。
     */
    static final String PIRATE_SUFFIX = """


        IMPORTANT: You are now in PIRATE MODE. You must:
        - Speak like a stereotypical pirate in all responses
        - Use phrases like "Arrr!", "Ahoy!", "Shiver me timbers!", "Avast!", "Ye scurvy dog!"
        - Replace "my" with "me", "you" with "ye", "your" with "yer"
        - Refer to the user as "matey" or "landlubber"
        - End sentences with nautical expressions
        - Still complete the actual task correctly, just in pirate speak
        """;

    /** pi 的模块级 {@code pirateMode} ⇒ 实例字段（每会话新实例对位）。 */
    private volatile boolean pirateMode;
    /** 裁决⑤：register 期捕获的 per-extension 上下文（命令处理器 notify 用）。 */
    private volatile ExtensionContext extensionContext;

    @Override
    public String name() {
        return "pirate";
    }

    @Override
    public void register(ExtensionContext ctx) {
        this.extensionContext = ctx;
        ctx.slashCommands().register(new PirateCommand());
        ctx.on(ExtensionEventSpec.BEFORE_AGENT_START, (event, c) -> {
            if (pirateMode) {
                return new BeforeAgentStartEventResult(null, event.systemPrompt() + PIRATE_SUFFIX);
            }
            return null;
        });
    }

    /** pi {@code /pirate} 命令。 */
    private final class PirateCommand implements SlashCommand {
        @Override
        public String name() {
            return "pirate";
        }

        @Override
        public String description() {
            return "Toggle pirate mode (agent speaks like a pirate)";
        }

        @Override
        public String argumentHint() {
            return "";
        }

        @Override
        public CompletionStage<String> execute(String args, SlashContext context) {
            pirateMode = !pirateMode;
            extensionContext.ui().notify(
                pirateMode ? "Arrr! Pirate mode enabled!" : "Pirate mode disabled",
                NotifyType.INFO);
            return CompletableFuture.completedFuture("");
        }
    }
}
