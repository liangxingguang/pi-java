package com.pijava.coding.agent.core.slash.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.skill.SkillGenerator;

/**
 * Skills slash commands（P6-27）：{@code /create-skill} 让模型生成 SKILL.md。
 *
 * <p>写入项目级 {@code <cwd>/.pi-java/skills/<name>/SKILL.md}，下次加载即被
 * {@link com.pijava.coding.agent.skill.SkillDiscovery} 发现。</p>
 */
public final class SkillsCommands {

    private SkillsCommands() {}

    /** 注册 skills 命令。 */
    public static void registerAll(CommandRegistry registry) {
        registry.register(new SlashCommand() {
            @Override public String name() { return "create-skill"; }
            @Override public String description() {
                return "Have the AI generate a skill (SKILL.md)";
            }
            @Override public String argumentHint() { return "<name> [description]"; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                return createSkill(args, ctx);
            }
        });
    }

    private static CompletionStage<String> createSkill(String args, SlashContext ctx) {
        var tokens = args.trim().split("\\s+", 2);
        if (tokens.length == 0 || tokens[0].isBlank()) {
            return completed("Usage: /create-skill <name> [description]");
        }
        var name = tokens[0];
        var description = tokens.length > 1 ? tokens[1].trim() : name;
        if (!SkillGenerator.isValidName(name)) {
            return completed("Invalid skill name '" + name
                + "' (lowercase letters, digits, dashes; <=64 chars, no leading dash)");
        }
        try {
            var prompt = SkillGenerator.prompt(name, description);
            var result = ctx.session().processPrompt(prompt, PromptConfig.defaults());
            var text = lastAssistantText(result.entries());
            if (text == null) {
                return completed("No model output received.");
            }
            var markdown = SkillGenerator.extractMarkdown(text);
            var dir = Path.of("").toAbsolutePath()
                .resolve(".pi-java").resolve("skills").resolve(name);
            Files.createDirectories(dir);
            var file = dir.resolve("SKILL.md");
            Files.writeString(file, markdown);
            return completed("Created skill: " + file);
        } catch (Exception e) {
            return completed("Skill creation failed: " + e.getMessage());
        }
    }

    private static String lastAssistantText(List<Entry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof Entry.Message msg
                    && "assistant".equals(msg.message().role())) {
                var sb = new StringBuilder();
                for (var block : msg.message().content()) {
                    if (block instanceof ContentBlock.TextContent text) {
                        sb.append(text.text());
                    }
                }
                if (!sb.isEmpty()) {
                    return sb.toString();
                }
            }
        }
        return null;
    }

    private static CompletionStage<String> completed(String message) {
        return CompletableFuture.completedFuture(message);
    }
}
