package com.pijava.agent.harness;

import java.util.List;

import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.message.Message;

/**
 * 一次 LLM 请求的上下文 —— pi {@code Context}（{@code packages/ai/src/types.ts:524-528}）
 * 与 {@code AgentContext}（{@code packages/agent/src/types.ts:415-422}）在 pi-java 侧的统一对应物。
 *
 * <p>pi 把这两个类型分开，是因为它有 {@code AgentMessage} / {@code Message} 两套消息模型
 * （多出的 {@code bashExecution} / {@code branchSummary} / {@code compactionSummary} 三个角色）。
 * 那三个角色来自 pi 的 {@code harness/} 层 —— **不在对齐范围内**（{@code docs/31 §1.2}），
 * 且 {@code CustomAgentMessages} 默认为空。因此 pi-java 只有一套消息模型，两个类型合并为本记录。</p>
 *
 * <p><b>三个字段逐字对齐 pi</b>：{@code systemPrompt} / {@code messages} / {@code tools}。
 * 三者缺一不可 —— 系统提示与工具定义**都在这里**，不走在消息列表里：</p>
 *
 * <ul>
 *   <li>pi 的 {@code Message} 只有 user / assistant / toolResult 三个角色
 *       （{@code packages/ai/src/types.ts:470}），**没有 system 角色**；</li>
 *   <li>pi 的 provider 适配层读的是 {@code context.systemPrompt}
 *       （{@code api/anthropic-messages.ts:1074}、{@code api/openai-completions.ts:1214} 等），
 *       从不扫描消息列表找系统消息。</li>
 * </ul>
 *
 * <p>工具同理：pi 的 {@code AgentLoopConfig} **没有** tools 字段，工具只在本记录上。</p>
 *
 * <p><b>工具装的是 {@code AgentTool} 不是 {@code ToolDefinition}</b>（2026-09-13 更正）。
 * pi 的两个 Context 对 tools 的类型不同：ai 层的 {@code Context.tools?: Tool[]} 是
 * 「名字 + 描述 + 参数」的**窄定义**，agent 层的 {@code AgentContext.tools?: AgentTool[]}
 * 是**带行为的本体**；pi 把后者原样塞进前者（结构类型兼容）。本记录合并了两个 Context，
 * 一度取了窄的那份 —— 于是 {@code AgentTool.executionMode()}（决定整批走顺序还是并行，
 * {@code agent-loop.ts:417-421}）在循环里**根本够不着**：生产代码零读者。
 * 现在取宽的那份，provider 边界用 {@code ToolRegistry.definitionsOf} 投影成定义。</p>
 *
 * @param systemPrompt 系统提示（{@code null} 表示不发送）。pi 的 {@code AgentState.systemPrompt}
 *                     是字段，由会话层在启动时注入
 * @param messages     本轮的消息列表。**可变** —— 循环会就地追加助手消息与工具结果，
 *                     与 pi 的 {@code context.messages.push} 一致
 * @param tools        本次请求可用的工具本体（{@code null} 视同空表）
 */
public record Context(String systemPrompt, List<Message> messages, List<AgentTool<?, ?>> tools) {

    /** 只对 {@code tools} 做防御性拷贝；{@code messages} 必须保持可变（见类注释）。 */
    public Context {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 只有消息、无系统提示与工具的上下文。 */
    public static Context of(List<Message> messages) {
        return new Context(null, messages, List.of());
    }

    /** 消息 + 工具的上下文（无系统提示）。 */
    public static Context of(List<Message> messages, List<AgentTool<?, ?>> tools) {
        return new Context(null, messages, tools);
    }

    /** 换系统提示，消息与工具不变。 */
    public Context withSystemPrompt(String systemPrompt) {
        return new Context(systemPrompt, messages, tools);
    }

    /** 换消息列表（整体替换，pi 的 {@code currentContext = snapshot.context ?? currentContext}）。 */
    public Context withMessages(List<Message> messages) {
        return new Context(systemPrompt, messages, tools);
    }

    /** 换工具。 */
    public Context withTools(List<AgentTool<?, ?>> tools) {
        return new Context(systemPrompt, messages, tools);
    }

    /**
     * 按名查工具（pi {@code currentContext.tools?.find(t => t.name === tc.name)}）。
     *
     * <p>找不到返回 {@code null} —— pi 用 {@code ?.} 链，未注册的调用同样走到不了执行。</p>
     */
    public AgentTool<?, ?> toolNamed(String name) {
        for (var tool : tools) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }
}
