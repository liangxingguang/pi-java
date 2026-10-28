package com.pijava.coding.agent.core;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.message.Message;

/**
 * 批 29（结构债拆分）：{@link AgentSession} 的虚拟模型／嵌套调用簇（批 27
 * 注记的既定可拆块）——virtualModels 注册表、每请求路由（routeVirtualRequest）、
 * direct 臂（routeDirectIfVirtual/summaryRoutedModel）、嵌套工具调用宿主
 * （executeNestedToolCall/runNestedToolCall/mountNestedCalls）。
 * 自 {@code AgentSession} 纯移动（行为零变化）；javadoc/注释逐字随迁。
 */
final class VirtualModelRouting {

    private final AgentSession owner;

    // ── 批 27（docs/38 T9/T10）：虚拟模型（B162；pi core/virtual-models.ts＋
    //     model-runtime.ts:952-1033＋agent-session.ts:787-844 对位）──

    /**
     * 虚拟模型注册表（裁决①：判别式外在化——注册表查找）。目录查物理模型的
     * 晚读口：{@code modelCatalog} 字段在 assemble 期回填（:880），lambda 调用时
     * 现读。
     */
    private final com.pijava.coding.agent.core.virtual.VirtualModelRegistry virtualModels =
        new com.pijava.coding.agent.core.virtual.VirtualModelRegistry(this::physicalModelKnown);

    // ── 批 27（docs/38 T3）：嵌套工具调用宿主（pi :726-764/:1102-1116 对位）──

    /** pi :473-474 逐字注释："Created on the first ctx.executeTool() call." */
    private NestedToolCallRunner nestedToolCalls;

    VirtualModelRouting(AgentSession owner) {
        this.owner = owner;
    }

    /**
     * pi {@code getPhysicalModel} 的目录臂（model-runtime.ts:1029-1033）——java 的
     * 运行池＝会话目录 ∪ 已注册 provider 的 {@code getModels()}（pi 的 ModelRuntime
     * 注册表同构：provider 供给的模型也在册）。
     */
    private boolean physicalModelKnown(String provider, String id) {
        if (owner.modelCatalog().find(com.pijava.ai.model.ModelId.of(provider, id)).isPresent()) {
            return true;
        }
        var registered = owner.services().providers().get(provider);
        return registered.isPresent() && registered.get().getModels().stream()
            .anyMatch(m -> m.id().modelName().equals(id));
    }

    /** 虚拟模型注册表（扩展面与路由钩共享）。 */
    com.pijava.coding.agent.core.virtual.VirtualModelRegistry virtualModels() {
        return virtualModels;
    }

    /** pi {@code isVirtualModel} 的会话面（注册表判别式，裁决①）。 */
    boolean isVirtualModel(com.pijava.ai.model.ModelId<?> model) {
        return virtualModels.isVirtual(model);
    }

    /**
     * pi {@code _installAgentRequestProjection} 的路由段（agent-session.ts:807-842
     * 逐字序）：每次 provider 请求前，虚拟选型 ⇒ 路由到物理模型。
     *
     * <p>对位要点：</p>
     * <ul>
     *   <li>:814 物理直通 ⇒ 非虚拟选型返回 null（弃权）</li>
     *   <li>:819-820 用户回合判定逐字（最后一条 assistant 之后有 user 消息；
     *       无 assistant 时 findLastIndex=-1 ⇒ 全列表扫 ⇒ 首 prompt 恒 user）</li>
     *   <li>:821 路由态读分支；:822-828 reason 三臂；:829-835 态写回（值判等，
     *       裁决②/D160）＋entry_appended＋落盘（appendCustomEntry 既有全链）</li>
     *   <li>:842 选型留在 agent state，只有本请求用路由模型（RequestUpdate 的
     *       per-request 语义同形）</li>
     *   <li>路由失败 ⇒ 异常传出（onPrepareRequestRouted 不隔离）⇒ run 错误收场
     *       （pi :808-809 的 reject 对位）</li>
     *   <li>:838-841 路由后压缩阈值检查不做（窄差 D171：阈值门对虚拟选型按
     *       未知窗口跳过〔目录无虚拟条目〕；溢出恢复兜底）</li>
     * </ul>
     */
    com.pijava.agent.harness.PiLoop.RequestUpdate routeVirtualRequest(
            com.pijava.agent.harness.PiLoop.PrepareRequestContext request) {
        var model = request.model();
        if (model == null || !virtualModels.isVirtual(model)) {
            return null;
        }
        var messages = request.context().messages();
        // pi :819-820（逐字语义）：最后一条 assistant（含 error）之后有 user ⇒ user 回合
        int lastAssistant = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage) {
                lastAssistant = i;
                break;
            }
        }
        boolean userTurn = false;
        for (int i = lastAssistant + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.UserMessage) {
                userTurn = true;
                break;
            }
        }
        // pi :790-791 的 failed（缝已消费清零；java 方言注记：重试时工作副本经持久
        // 省略重建，失败消息不在 messages 里——与 pi 同形）
        var failedMessage = request.failedResponse();
        var failed = failedMessage == null
            || failedMessage.provider() == null || failedMessage.model() == null
            ? null
            : new com.pijava.coding.agent.core.virtual.ModelRouteRequest.RouteFailure(
                com.pijava.ai.model.ModelId.of(failedMessage.provider(),
                    failedMessage.model()),
                failedMessage.thinkingLevel(), failedMessage);
        var reason = failed != null
            ? com.pijava.coding.agent.core.virtual.ModelRouteReason.RETRY
            : userTurn
                ? com.pijava.coding.agent.core.virtual.ModelRouteReason.USER
                : com.pijava.coding.agent.core.virtual.ModelRouteReason.CONTINUATION;
        var branch = ContextEntries.pathToLeaf(owner.accumulatedEntries(), owner.currentLeafId());
        var state = com.pijava.coding.agent.core.virtual.VirtualModelSelections
            .getVirtualModelState(branch, model.provider(), model.modelName());
        var route = virtualModels.resolve(model, messages, request.thinking(), reason,
            state, failed, null);
        // pi :829-835：新态（且值不等）才写 custom 条目（裁决② 值判等对位引用判等）
        if (route.state() != null && !route.state().equals(state)) {
            owner.appendCustomEntry(
                com.pijava.coding.agent.core.virtual.VirtualModelRegistry.STATE_ENTRY,
                com.pijava.coding.agent.core.virtual.VirtualModelSelections.stateData(
                    model.provider(), model.modelName(), route.state()));
        }
        return new com.pijava.agent.harness.PiLoop.RequestUpdate(null, route.model(),
            route.thinkingLevel());
    }

    /**
     * pi :583-589 的 direct 臂（摘要/压缩请求先路由——"summaries size their input
     * and output from the model they get"）：loop 外请求的选型解析。direct 不写态、
     * 不带 previous/failed（pi :716-742 的 streamSimple 臂同语义）。
     */
    com.pijava.ai.model.ModelId<?> routeDirectIfVirtual(
            com.pijava.ai.model.ModelId<?> model, java.util.List<Message> messages) {
        if (model == null || !virtualModels.isVirtual(model)) {
            return model;
        }
        var route = virtualModels.resolve(model, messages, owner.harness().getThinkingLevel(),
            com.pijava.coding.agent.core.virtual.ModelRouteReason.DIRECT, null, null, null);
        return route.model();
    }

    /** 摘要接线的路由包装：会话未回填（装配期）⇒ 原样；否则虚拟选型先路由。 */
    static com.pijava.ai.model.ModelId<?> summaryRoutedModel(
            AgentSession session, com.pijava.ai.model.ModelId<?> model) {
        return session == null ? model : session.routeDirectIfVirtual(model,
            session.routing().currentBranchMessages());
    }

    /** 当前分支的消息投影（路由器的 messages 入参）。 */
    java.util.List<Message> currentBranchMessages() {
        return ContextEntries.toMessages(
            ContextEntries.pathToLeaf(owner.accumulatedEntries(), owner.currentLeafId()));
    }

    /**
     * pi {@code _executeNestedToolCall}（agent-session.ts:726-764）：扩展工具经
     * {@code ctx.executeTool()} 发起嵌套调用——走与模型调用完全相同的校验/钩子/
     * 权限管线，事件带 {@code parentToolCallId}，记录挂在父调用的 tool result 上。
     */
    com.pijava.agent.harness.PiLoop.ToolOutcome executeNestedToolCall(
            String parentToolCallId, String name, java.util.Map<String, Object> args,
            NestedToolCallRunner.Options options) {
        if (nestedToolCalls == null) {
            nestedToolCalls = new NestedToolCallRunner(new NestedToolCallRunner.Host() {
                @Override
                public java.util.List<AgentTool<?, ?>> getTools() {
                    return owner.callableTools();
                }

                @Override
                public boolean isSequential() {
                    return owner.harness().toolExecution()
                        instanceof com.pijava.agent.harness.ToolExecution.Sequential;
                }

                @Override
                public com.pijava.agent.harness.PiLoop.ToolOutcome runToolCall(
                        com.pijava.agent.harness.PiLoop.ToolCall toolCall, String parentId,
                        com.pijava.ai.AbortSignal signal,
                        java.util.function.Consumer<Object> onUpdate) {
                    return runNestedToolCall(toolCall, parentId, signal, onUpdate);
                }

                @Override
                public void emit(AgentSessionEvent event) {
                    // pi :758-761 是「先扩展 runner 再会话订阅者」两跳；java 经
                    // 会话事件单通道广播，SessionEventBridge 在订阅链内转发扩展总线
                    // ——两通道都同步到达，扩展/TUI 的相对次序与 pi 异（窄差 D167）。
                    owner.emitSessionEvent(event);
                }
            });
        }
        return nestedToolCalls.execute(parentToolCallId, name, args, options);
    }

    /**
     * 嵌套调用的管线跑法（pi {@code host.runToolCall}，:739-757 对位）：无 assistant
     * 消息立即错误（文案逐字）；否则以<b>可调用集</b>为查找面跑同一条
     * {@code PiToolRunner} 管线（钩子上下文带父 id）。
     */
    private com.pijava.agent.harness.PiLoop.ToolOutcome runNestedToolCall(
            com.pijava.agent.harness.PiLoop.ToolCall toolCall, String parentId,
            com.pijava.ai.AbortSignal signal, java.util.function.Consumer<Object> onUpdate) {
        if (findLastAssistantMessage() == null) {
            // pi :741-745 逐字错文
            var result = new com.pijava.agent.tool.ToolResult<>(
                java.util.List.of(new com.pijava.ai.message.ContentBlock.TextContent(
                    "No assistant message issued this call")),
                java.util.Map.of(), null, false, java.util.List.of());
            return new com.pijava.agent.harness.PiLoop.ToolOutcome(
                new Message.ToolResultMessage(toolCall.toolCallId(), toolCall.toolName(),
                    result.content(), null, null, java.util.List.of(), true,
                    java.time.Instant.now(), null),
                result, true);
        }
        var runner = new com.pijava.agent.harness.PiToolRunner(owner.laneName(),
            owner.services().tools(),
            owner.harness().hookSystem(), owner.discovery().baseToolContext(),
            signal, null, () -> new java.util.LinkedHashSet<>(owner.callableTools()), parentId);
        var preparation = runner.prepare(toolCall);
        if (preparation instanceof com.pijava.agent.harness.PiLoop.ImmediateOutcome immediate) {
            return immediate.outcome();
        }
        return runner.execute((com.pijava.agent.harness.PiLoop.Prepared) preparation, event -> {
            if (event instanceof com.pijava.agent.harness.PiLoop.Event.ToolExecutionUpdate u) {
                onUpdate.accept(u.partialResult());
            }
        });
    }

    /** pi {@code _findLastAssistantMessage} 的 transcript 扫描对位。 */
    private Message.AssistantMessage findLastAssistantMessage() {
        var transcript = owner.harness().snapshot(owner.laneName()).transcript();
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (transcript.get(i) instanceof Entry.Message entry
                    && entry.message() instanceof Message.AssistantMessage assistant) {
                return assistant;
            }
        }
        return null;
    }

    /**
     * pi :1102-1116 的挂载钩（message_end 替换臂——sink 在钩子后追加条目，
     * 挂载先于落盘；扩展 message_end 处理器看到已挂载消息，因为本钩先于
     * 扩展桥注册〔loadExtensions 序〕）。pi 在 message_start 挂载；java 的
     * message_start 事件不武装消息体（窄差 D168：MessageStart 事件的订阅者
     * 看到未挂载的消息——记录/落盘/上下文均不受影响）。
     */
    Message mountNestedCalls(Message message) {
        var runner = nestedToolCalls;
        if (runner == null || !(message instanceof Message.ToolResultMessage result)) {
            return null;
        }
        var summary = runner.takeRecord(result.toolUseId());
        if (summary == null) {
            return null;
        }
        var mounted = result;
        if (summary.calls() != null) {
            mounted = mounted.withNestedCalls(summary.calls());
        }
        if (summary.usage() != null) {
            mounted = mounted.withUsage(
                mounted.usage() instanceof com.pijava.agent.tool.ToolResult.UsageInfo existing
                    ? new com.pijava.agent.tool.ToolResult.UsageInfo(
                        existing.inputTokens() + summary.usage().inputTokens(),
                        existing.outputTokens() + summary.usage().outputTokens())
                    : summary.usage());
        }
        return mounted;
    }

    /** pi :1113-1115 的 {@code agent_end} 清空（防泄漏）。 */
    void clearNestedToolCalls() {
        var runner = nestedToolCalls;
        if (runner != null) {
            runner.clear();
        }
    }
}
