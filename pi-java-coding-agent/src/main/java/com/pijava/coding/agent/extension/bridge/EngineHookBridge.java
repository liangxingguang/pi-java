package com.pijava.coding.agent.extension.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.hook.AfterToolPatch;
import com.pijava.agent.hook.BeforeToolResult;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.hook.ToolCallContext;
import com.pijava.agent.hook.ToolResultContext;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ContextEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ContextWithSystemEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolCallEvent;
import com.pijava.coding.agent.extension.event.ExtensionResults.ToolCallEventResult;
import com.pijava.coding.agent.extension.event.ExtensionEvent.ToolResultEvent;
import com.pijava.coding.agent.extension.event.ExtensionResults.ToolResultEventResult;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.CONTEXT;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.CONTEXT_WITH_SYSTEM;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.TOOL_CALL;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.TOOL_RESULT;

/**
 * 引擎钩子族桥（docs/14 §3.4）—— 把 agent-core {@link HookSystem} 的三个活 firing 点
 * 接到 {@link ExtensionEventBus} 上，pi runner 语义逐字：
 *
 * <ul>
 *   <li>{@code transform_context} ⇐ pi {@code emitContext}（runner.ts:1298-1359，两阶段）；</li>
 *   <li>{@code before_tool} ⇐ pi {@code emitToolCall}（:1242-1260，异常向上传播）；</li>
 *   <li>{@code after_tool} ⇐ pi {@code emitToolResult}（:1183-1240，逐字段合并状态机）。</li>
 * </ul>
 *
 * <p>裁决②：<b>装配期恒装</b>（pi 的 Agent 选项同形），适配器内部空订阅快走 ——
 * 不存在「订阅晚于 firing 点建立」的时序窗口。</p>
 */
public final class EngineHookBridge {

    /** pi 阶段二丢头系统的错误文本（runner.ts:1348 逐字）。 */
    static final String LEADING_SYSTEM_REMOVED =
        "Handler removed the leading system message; the request has no prompt or initial "
      + "tool declarations. Keep it at index 0 or replace a dropped prefix with "
      + "getCurrentSystemMessage().";

    private final ExtensionEventBus bus;
    private final ExtensionContext ctx;

    private EngineHookBridge(ExtensionEventBus bus, ExtensionContext ctx) {
        this.bus = bus;
        this.ctx = ctx;
    }

    /** 装配期恒装三个适配器（每个引擎钩子一个）。 */
    public static void install(HookSystem hooks, String laneName,
                               ExtensionEventBus bus, ExtensionContext ctx) {
        var bridge = new EngineHookBridge(bus, ctx);
        hooks.onTransformContext(laneName, bridge::transformContext);
        hooks.onBeforeTool(laneName, bridge::beforeTool);
        hooks.onAfterTool(laneName, bridge::afterTool);
    }

    // ═══════════════════════════════════════════════════════════
    // ContextAdapter：pi emitContext（runner.ts:1298-1359 逐字）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi 的两阶段：先 {@code context}（处理器看不到系统消息，可原地改或返回新列表），
     * 后 {@code context_with_system}（全量，返回即采用，弄丢头系统只上报不拦）。
     *
     * @return 变换后的列表；无订阅者 ⇒ {@code null}（HookSystem 保留原列表）
     */
    List<Message> transformContext(List<Message> messages) {
        if (!bus.hasHandlers(CONTEXT) && !bus.hasHandlers(CONTEXT_WITH_SYSTEM)) {
            return null;
        }
        // pi 的 structuredClone 不需要：java Message 是不可变 record（裁决④附注）。
        List<Message> current = new ArrayList<>(messages);
        // 阶段一：context（pi :1310-1336）
        for (var h : bus.snapshot(CONTEXT)) {
            try {
                var visible = new ArrayList<Message>();
                for (var m : current) {
                    if (!(m instanceof Message.SystemMessage)) {
                        visible.add(m);
                    }
                }
                var snapshot = new ArrayList<>(visible); // pi visibleSnapshot
                var result = h.handle(new ContextEvent(visible), ctx);
                // pi：handlerResult?.messages ?? (sameMessages ? undefined : visible)
                List<Message> returned = result != null && result.messages() != null
                    ? result.messages()
                    : sameByReference(visible, snapshot) ? null : visible;
                if (returned != null) {
                    current = restoreSystemMessages(current, snapshot, returned);
                }
            } catch (Throwable e) { // pi 的 catch 捕获一切：逐处理器上报 ⇒ 继续
                bus.emitError(h.error(e));
            }
        }
        // 阶段二：context_with_system（pi :1338-1357）
        for (var h : bus.snapshot(CONTEXT_WITH_SYSTEM)) {
            try {
                boolean hadLeading = !current.isEmpty()
                    && current.get(0) instanceof Message.SystemMessage;
                var result = h.handle(new ContextWithSystemEvent(current), ctx);
                if (result != null && result.messages() != null) {
                    current = new ArrayList<>(result.messages());
                }
                if (hadLeading && (current.isEmpty()
                        || !(current.get(0) instanceof Message.SystemMessage))) {
                    bus.emitError(h.error(LEADING_SYSTEM_REMOVED)); // pi 逐字
                }
            } catch (Throwable e) { // pi 的 catch 捕获一切
                bus.emitError(h.error(e));
            }
        }
        return current;
    }

    /**
     * pi {@code restoreSystemMessages}（runner.ts:283-292 逐字）：会话未变（引用相等）
     * 则整条 {@code current} 原样保留（含会话中间的系统消息）；变了则重放头部系统消息。
     */
    private static List<Message> restoreSystemMessages(
            List<Message> current, List<Message> visibleSnapshot, List<Message> returned) {
        if (sameByReference(returned, visibleSnapshot)) {
            return current;
        }
        var head = Transcripts.getCurrentSystemMessage(current);
        if (head == null) {
            return new ArrayList<>(returned);
        }
        var restored = new ArrayList<Message>(returned.size() + 1);
        restored.add(head);
        restored.addAll(returned);
        return restored;
    }

    /**
     * pi {@code sameMessages}（runner.ts:273-275）的 {@code ===} 同构 —— 逐元素<b>引用</b>
     * 比较（裁决④：java record 的 {@code equals} 会把「重建的同值对象」误判为未变，
     * 跳过 restore，与 pi 不符）。
     */
    private static boolean sameByReference(List<Message> left, List<Message> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i) != right.get(i)) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════
    // ToolCallAdapter：pi emitToolCall（runner.ts:1242-1260 逐字）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi：处理器异常**直接传播**（全函数无 try/catch —— 经 HookSystem 记账重抛、
     * PiToolRunner.prepare 的 catch 转工具错误结果，pi agent-loop.ts:668-673 同链）；
     * {@code block} 短路；无 block 时最后一个发言的处理器赢（其结果除 block 外无消费）。
     * {@code input} 原地可变链：后续处理器看到前面的改写，执行拿改后参数。
     */
    BeforeToolResult beforeTool(ToolCallContext call) {
        if (!bus.hasHandlers(TOOL_CALL)) {
            return null;
        }
        var input = new LinkedHashMap<String, Object>(call.arguments()); // pi event.input
        for (var h : bus.snapshot(TOOL_CALL)) {
            ToolCallEventResult r;
            try {
                r = h.handle(new ToolCallEvent(call.toolCallId(), call.toolName(), input), ctx);
            } catch (Exception e) {
                // 裁决③：不捕获、向上传播。sneaky throw —— BeforeToolHook 接口无
                // throws 声明，原样抛出保住 pi 的错误文本（messageOf 取 getMessage）。
                throw throwAsIs(e);
            } catch (Error e) {
                // pi 的 catch 捕获一切（agent-loop.ts:668-673 把 error.message 转成
                // 工具错误结果）；java 的 HookSystem/PiToolRunner 只 catch Exception ——
                // Error 在这里包成 RuntimeException（保 message），走同一条转换链，
                // 而不是炸穿 prepare 逃出工具 worker。
                throw new RuntimeException(e.getMessage(), e);
            }
            if (r != null && Boolean.TRUE.equals(r.block())) {
                return new BeforeToolResult(false,
                    Map.of("reason", r.reason() != null ? r.reason() : ""),
                    Boolean.TRUE.equals(r.terminate()));
            }
        }
        // 原地改写链的回流（pi 靠同一对象引用；java ToolCallContext 防御拷贝 ⇒ 显式返回）。
        return input.equals(call.arguments()) ? null : new BeforeToolResult(true, input);
    }

    // ═══════════════════════════════════════════════════════════
    // ToolResultAdapter：pi emitToolResult（runner.ts:1183-1240 逐字）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi 的合并状态机：逐处理器 try/catch（异常 ⇒ emitError ⇒ 继续）；content 被替换而
     * structuredContent 未同换 ⇒ 丢弃；五字段独立合并；全程无修改 ⇒ {@code null}
     * （调用方不动原结果）。
     */
    AfterToolPatch afterTool(ToolResultContext call) {
        if (!bus.hasHandlers(TOOL_RESULT)) {
            return null;
        }
        var currentContent = call.result().content();
        var currentDetails = call.result().details();
        Object currentStructured = call.result().structuredContent();
        boolean currentIsError = call.isError();
        var currentUsage = call.result().usage();
        boolean modified = false;
        for (var h : bus.snapshot(TOOL_RESULT)) {
            try {
                var r = h.handle(new ToolResultEvent(call.toolCallId(), call.toolName(),
                    call.arguments(), currentContent, currentDetails, currentStructured,
                    currentIsError, currentUsage), ctx);
                if (r == null) {
                    continue;
                }
                if (r.content() != null) {
                    currentContent = r.content();
                    // pi :1196-1197：content 换而 structured 未同换 ⇒ delete
                    if (r.structuredContent() == null) {
                        currentStructured = null;
                    }
                    modified = true;
                }
                if (r.details() != null) {
                    currentDetails = r.details();
                    modified = true;
                }
                if (r.structuredContent() != null) {
                    currentStructured = r.structuredContent();
                    modified = true;
                }
                if (r.isError() != null) {
                    currentIsError = r.isError();
                    modified = true;
                }
                if (r.usage() != null) {
                    currentUsage = r.usage();
                    modified = true;
                }
            } catch (Throwable e) { // pi 的 catch 捕获一切：逐处理器上报 ⇒ 继续
                bus.emitError(h.error(e));
            }
        }
        if (!modified) {
            return null;
        }
        // 转引擎补丁：pi 的返回对象恒带 content/details/isError；structured 的
        // 「被 delete」经 drop 臂表达（patch 的 null=保留 表达不了清空）——
        // 合并终值为 null 时置 drop（原本就 null 时 drop 无害：结果同为 null）。
        return new AfterToolPatch(currentContent, currentDetails, currentUsage, null,
            currentIsError, currentStructured, currentStructured == null);
    }

    /** sneaky throw：原样抛出受检异常（{@link #beforeTool} 的 pi 传播语义需要）。 */
    @SuppressWarnings("unchecked") // T 的擦除使受检异常绕过编译期检查，运行时原样
    private static <T extends Throwable> RuntimeException throwAsIs(Throwable t) throws T {
        throw (T) t;
    }
}
