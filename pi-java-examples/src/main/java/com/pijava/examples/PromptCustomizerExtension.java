package com.pijava.examples;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Collectors;

import com.pijava.agent.skill.Skill;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/prompt-customizer.ts:14-48} 移植（docs/40 P12）——
 * before_agent_start 按 selectedTools/skills 生成 {@code tool_guidance} 节；
 * 有内容 ⇒ 写入 sections，无 ⇒ 删除该键。
 *
 * <p>语义对位注记（D11 范畴）：pi 读构建期 {@code systemPromptOptions.selectedTools/
 * skills} 快照；java 读事件时活跃集——{@code ctx.getActiveTools()}（批 16，同声明给
 * 模型的工具集）与 {@code ctx.skills()}。同会话同时刻两者一致。</p>
 */
public final class PromptCustomizerExtension implements PiExtension {

    @Override
    public String name() {
        return "prompt-customizer";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.BEFORE_AGENT_START, (event, c) -> {
            var guidance = buildToolGuidance(c);
            if (!guidance.isEmpty()) {
                event.sections().put("tool_guidance", guidance);
            } else {
                event.sections().remove("tool_guidance");
            }
            return null; // pi 处理器不发言（原地改 sections）
        });
    }

    /** pi {@code buildToolGuidance}（选项集 ⇒ 规则行，{@code \n} 连接）。 */
    static String buildToolGuidance(ExtensionContext ctx) {
        var active = new HashSet<>(ctx.getActiveTools());
        var rules = new ArrayList<String>();
        if (active.contains("read")) {
            rules.add("- Use `read` for file contents; it supports text and images.");
            rules.add("- For large files, use `offset` and `limit` to read in chunks.");
        }
        if (active.contains("bash")) {
            rules.add("- Use `bash` for file operations such as `ls`, `find`, and `grep`.");
        }
        if (active.contains("edit")) {
            rules.add("- Use `edit` for precise text replacements that match existing content exactly.");
        }
        if (active.contains("write")) {
            rules.add("- Use `write` to create new files or replace existing files completely.");
        }
        var skills = ctx.skills().all();
        if (!skills.isEmpty()) {
            rules.add("- Available skills: "
                + skills.stream().map(Skill::name).collect(Collectors.joining(", ")) + ".");
        }
        return String.join("\n", rules);
    }
}
