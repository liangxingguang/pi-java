package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.agent.tool.ToolResult;

/**
 * pi 双循环的 1:1 移植（{@code packages/agent/src/agent-loop.ts} @ pi {@code ea448f454}）。
 *
 * <p><b>为什么有这个类</b>：{@code 原 docs/28} 判定 pi-java 自建的显式状态机
 * （{@code Action} / {@code RunPhase} / {@code peekAction} / {@code executeAction} /
 * record-log fold）在 pi 的产品层没有对应物，且它的三个真源产出了三个真实缺陷。
 * 本类按「等价性由结构保证」的原则一对一译出 pi 的循环，用 Java 的普通同步调用
 * 替代 {@code await}，用 {@link Sink} 替代 pi 的 {@code emit}。</p>
 *
 * <p><b>刻意不做的事</b>：不引入 {@code Action}、不引入 {@code RunPhase}、不写任何记录日志、
 * 不持有持久化状态。状态只有三样，与 pi 一致：消息列表、配置、{@code pendingMessages}。</p>
 *
 * <p><b>工具执行是端口</b>：这里只定义两相 {@link ToolRunner} 端口
 * （{@code prepare} / {@code execute}，实现为 {@link PiToolRunner}），由调用方注入；
 * 发射时序（start 先于校验、immediate 调用在准备循环内收尾、结果消息源序补发、
 * {@code terminate} 取全部）在 {@link PiLoopTools}。</p>
 */
public final class PiLoop {

    // ═══════════════════════════════════════════════════════════════
    // 事件：pi 的 AgentEvent（types.ts:431-446），恰好 10 个变体
    // ═══════════════════════════════════════════════════════════════

    /** pi {@code AgentEvent}。标签名与载荷逐字对齐，不增不减。 */
    public sealed interface Event {

        /** pi {@code { type: "agent_start" }} */
        record AgentStart() implements Event {}

        /** pi {@code { type: "agent_end"; messages }} */
        record AgentEnd(List<Message> messages) implements Event {}

        /** pi {@code { type: "turn_start" }} */
        record TurnStart() implements Event {}

        /** pi {@code { type: "turn_end"; message; toolResults }} */
        record TurnEnd(Message.AssistantMessage message,
                       List<Message.ToolResultMessage> toolResults) implements Event {}

        /** pi {@code { type: "message_start"; message }} */
        record MessageStart(Message message) implements Event {}

        /** pi {@code { type: "message_update"; message; assistantMessageEvent }} */
        record MessageUpdate(Message message, StreamEvent assistantMessageEvent) implements Event {}

        /** pi {@code { type: "message_end"; message }} */
        record MessageEnd(Message message) implements Event {}

        /** pi {@code { type: "tool_execution_start"; toolCallId; toolName; args }} */
        record ToolExecutionStart(String toolCallId, String toolName,
                                  Map<String, Object> args) implements Event {}

        /** pi {@code { type: "tool_execution_update"; ...; args; partialResult }}（{@code :690-704}） */
        record ToolExecutionUpdate(String toolCallId, String toolName,
                                   Map<String, Object> args, Object partialResult) implements Event {}

        /**
         * pi {@code { type: "tool_execution_end"; ...; result; isError; durationMs? }}，
         * result=完整结果对象（{@code :774-782}）。
         * B234：{@code durationMs} 只围工具本体（agent types.ts:531-539）；
         * 未执行（unknown/校验失败/blocked/执行前 abort/截断）⇒ null ⇒ 键省略。
         */
        record ToolExecutionEnd(String toolCallId, String toolName,
                                Object result, boolean isError, Long durationMs)
                implements Event {
            /** B234 之前的四参形状（未执行路径/存量构造点零改签用）。 */
            ToolExecutionEnd(String toolCallId, String toolName,
                             Object result, boolean isError) {
                this(toolCallId, toolName, result, isError, null);
            }
        }
    }

    /** pi 的 {@code AgentEventSink}。 */
    @FunctionalInterface
    public interface Sink {
        /** 同步接收一个事件（pi 的 {@code emit} 是 async，pi-java 侧全部同步）。 */
        void emit(Event event);
    }

    // ═══════════════════════════════════════════════════════════════
    // 端口：工具执行
    // ═══════════════════════════════════════════════════════════════

    /** 一次工具调用的执行请求。{@code truncatedByLength} 对应 pi 的 length 截断分支。 */
    public record ToolCall(String toolCallId, String toolName,
                           Map<String, Object> args, boolean truncatedByLength) {}

    /**
     * 一次工具调用的最终结果 —— pi 的 {@code FinalizedToolCallOutcome}：
     * 消息由 {@code createToolResultMessage(finalized)} 派生（{@code agent-loop.ts:784-797}），
     * 而 {@code result} 就是 pi 的 {@code finalized.result} —— 它原样进
     * {@code tool_execution_end.result}（{@code :779}），**不是** {@code details}。
     * 失败路径的 result 是 {@code createErrorToolResult}：{@code content=[text]} 且
     * {@code details={}}（{@code :767-772}）—— 空对象而非 null 是 pi 的形状。
     *
     * @param message pi 的 {@code ToolResultMessage}（会进上下文与 {@code newMessages}）
     * @param result  pi 的 {@code finalized.result}（完整结果对象；wire 上即
     *                {@code tool_execution_end.result} 与 {@code tool_execution_update.partialResult}）
     * @param isError 是否错误结果（pi 把它放在 result **外面**，{@code :728-729}）
     */
    public record ToolOutcome(Message.ToolResultMessage message, ToolResult<?> result,
                              boolean isError) {

        /** pi 的 {@code result.terminate === true} 判据（批次门，{@code :590}）。 */
        public boolean terminate() {
            return result.terminate();
        }
    }

    /**
     * 准备相的产物（pi 的 {@code prepareToolCall} 返回类型）：要么当场失败
     * （{@link ImmediateOutcome}），要么拿到执行票（{@link Prepared}）。
     *
     * <p><b>为什么端口必须暴露这个区分</b>：pi 的并行分支在准备循环内就给 immediate
     * 调用发 {@code tool_execution_end}（{@code agent-loop.ts:506-517}），已准备好的
     * 调用则要到 {@code Promise.all} 里各自完成时才收尾（{@code :520-545}），
     * 收尾顺序在 {@code :547} 之后才统一。单相端口复现不了这个 end 次序 ——
     * 此前它被 L5 的放宽规则豁免掉了。</p>
     */
    public sealed interface Preparation permits ImmediateOutcome, Prepared {}

    /** 准备相就失败（pi {@code kind:"immediate"}）：未找到 / 参数非法 / 钩子拒绝 / 已中止。 */
    public record ImmediateOutcome(ToolOutcome outcome) implements Preparation {}

    /**
     * 通过了准备相（pi {@code kind:"prepared"}）。对循环**半透明**：循环只取回调用本身，
     * 工具句柄与改写后的参数由签发它的 {@link ToolRunner} 实现自己持有。
     */
    public non-sealed interface Prepared extends Preparation {
        /** 这张票对应的那次调用（pi 的 {@code PreparedToolCall.toolCall}）。 */
        ToolCall call();
    }

    /** 工具执行端口。pi-java 侧由 {@link PiToolRunner} 提供实现。 */
    public interface ToolRunner {
        /**
         * 准备相（pi 的 {@code prepareToolCall}，{@code agent-loop.ts:607-675}）：查找工具、
         * 校验参数、跑 {@code before_tool} 钩子。失败的调用**也要**返回
         * {@link ImmediateOutcome} 而非抛异常 —— pi 对它们同样发出 start 与 end。
         */
        Preparation prepare(ToolCall call);

        /**
         * 执行 + 收尾相（pi 的 {@code executePreparedToolCall} +
         * {@code finalizeExecutedToolCall}，{@code agent-loop.ts:677-764}）：真正跑工具，
         * 随后跑 {@code after_tool} 钩子。工具自身抛出的异常同样转成错误结果。
         *
         * <p>{@code emit} 对应 pi 递给 {@code executePreparedToolCall} 的事件汇
         * （{@code :679}）—— 工具经 update 回调流出的 {@code tool_execution_update}
         * 由实现在这里直接发射（{@code :690-704}），执行结束后不再有更新
         * （pi 的 {@code acceptingUpdates} 闩）。</p>
         */
        ToolOutcome execute(Prepared prepared, Sink emit);

        /** 便捷适配：所有调用都直接进执行相、不存在 immediate 失败，也不流出更新 —— 给测试与简单端口用。 */
        static ToolRunner always(java.util.function.Function<ToolCall, ToolOutcome> execute) {
            return new ToolRunner() {
                @Override public Preparation prepare(ToolCall call) { return new CallPrepared(call); }
                @Override public ToolOutcome execute(Prepared prepared, Sink emit) {
                    return execute.apply(prepared.call());
                }
            };
        }

        /** {@link #always} 的执行票：只携带调用本身。 */
        record CallPrepared(ToolCall call) implements Prepared {}
    }

    // ═══════════════════════════════════════════════════════════════
    // 钩子（pi 的 AgentLoopConfig 可选回调）
    // ═══════════════════════════════════════════════════════════════

    /**
     * pi {@code AgentTurnContext}（{@code types.ts:134-144}）—— {@code finishTurn} 与
     * {@code prepareNextTurn} <b>两个钩子共用这一份形状</b>（pi 的
     * {@code PrepareNextTurnContext extends AgentTurnContext}，{@code types.ts:191}）。
     *
     * <p>pi 传的是 {@code lastCompletedTurn}，其 {@code context} 字段就是**当时**的
     * {@code AgentContext}（{@code agent-loop.ts:280-285}）—— 钩子因此能看到 systemPrompt 与
     * tools，而不只是消息列表。Java 侧由 {@link Context} 承载。</p>
     *
     * @param newMessages pi {@code AgentTurnContext.newMessages}：本次循环调用迄今的新消息
     *                    <b>快照</b>（pi 传的是活数组引用；java 钩子是同步调用，调用点之后
     *                    循环才会继续追加，快照无可观察差异）。
     */
    public record NextTurnContext(Message.AssistantMessage message,
                                  List<Message.ToolResultMessage> toolResults,
                                  Context context,
                                  List<Message> newMessages) {}

    /**
     * pi {@code AgentLoopTurnUpdate}（{@code types.ts:143-152}）：{@code prepareNextTurn}
     * 的返回值。四个字段与 pi 一一对应，全部可选，{@code null} 表示「不改」。
     *
     * <p>{@code context} 是**整体替换**，不是合并 —— pi 的
     * {@code currentContext = nextTurnSnapshot.context ?? currentContext}。
     * 压缩正是靠这条通道把重建后的消息列表交回循环（{@code agent-session.ts:557-577}）。</p>
     *
     * <p>{@code messages} 是 pi 的 {@code preparedMessages}（{@code agent-loop.ts:180}）——
     * 会话层**分段差分**产出的系统消息经由它进入「下一轮请求之前」的那批 pending。
     * 它存在的意义是给 {@code declareToolChanges} 一个**可合并的锚点**：工具增删会写回
     * 这条消息（而不是另起一条），因此模型看到的是一条同时带 sections 与 toolsAdded 的更新
     * （{@code agent-session.ts:603-611} 的 {@code _preparePromptAndToolLoadout} 正是生产者）。
     * 没有这条通道，「合并」那一支在 Java 上结构性不可达（{@code 原 docs/51 §3 F9}）。</p>
     *
     * <p>⚠️ {@code null} 与**空列表**不同：前者＝不改（pi 的 {@code undefined}，
     * 交给 {@code previousSnapshot?.messages}），后者＝「这一轮没有 prepared 消息」——
     * 而 {@code declareToolChanges} 对两者一视同仁（都是空 pending 前缀），故此处不细分。</p>
     */
    public record NextTurnUpdate(ModelId<?> model, ModelThinkingLevel thinking, Context context,
                                 List<Message> messages) {

        /** 只改 model / thinking（包 A3 之前的形状）。 */
        public NextTurnUpdate(ModelId<?> model, ModelThinkingLevel thinking) {
            this(model, thinking, null, null);
        }

        /** 只改 model / thinking / context（包 A3 之前的形状）。 */
        public NextTurnUpdate(ModelId<?> model, ModelThinkingLevel thinking, Context context) {
            this(model, thinking, context, null);
        }

        /** pi 的 {@code messages?: AgentMessage[]} —— {@code null} 视同「没有 prepared 消息」。 */
        public List<Message> preparedMessages() {
            return messages == null ? List.of() : messages;
        }
    }

    /** pi {@code prepareNextTurn}。返回 {@code null} 表示不改。 */
    @FunctionalInterface
    public interface NextTurnHook {
        /** 在上一轮 {@code turn_end} 之后、下一轮请求之前调用（{@code agent-loop.ts:186}）；返回 {@code null} 表示不改。 */
        NextTurnUpdate apply(NextTurnContext context);
    }

    /** pi {@code PrepareRequestContext}（{@code types.ts:171-175}）：每次 provider 请求前的运行时快照。 */
    public record PrepareRequestContext(Context context, ModelId<?> model,
                                        ModelThinkingLevel thinking) {}

    /**
     * pi {@code AgentRequestUpdate}（{@code types.ts:176}，＝ {@code Omit<AgentLoopTurnUpdate,"messages">}）。
     * 三组件全可选，{@code null} ≙ {@code undefined}（不改）。{@code context} 是整体替换。
     * {@code thinking} 的 {@link ModelThinkingLevel.Off} ≙ pi 的 {@code "off"} ⇒ 请求侧「不传」
     * （pi 的 {@code reasoning: undefined} 三分支由密封类型天然编码，无需特判）。
     */
    public record RequestUpdate(Context context, ModelId<?> model, ModelThinkingLevel thinking) {}

    /**
     * pi {@code PrepareRequest}（{@code types.ts:186-190}）：在<b>每一次</b>对话式 provider
     * 请求之前调用（含第一次），此时 pending 消息已 append 进 context 并发过事件。
     * 返回 {@code null} ≙ pi 的 {@code void}（不改）。
     */
    @FunctionalInterface
    public interface PrepareRequestHook {
        /** 裁决本次请求的运行时状态；{@code null} ≙ pi 的 undefined。 */
        RequestUpdate apply(PrepareRequestContext request);
    }

    /** pi {@code AgentTurnDecision}（{@code types.ts:146-147}）；纯常量闭集按规范用 enum。 */
    public enum TurnDecision { CONTINUE, END }

    /**
     * pi {@code FinishTurn}（{@code types.ts:149-158}）：assistant 与其工具结果全部落完后、
     * emit {@code turn_end} <b>之前</b>调用（{@code agent-loop.ts:286-287}）。
     * {@link TurnDecision#END} 直接收口（不再查队列）；{@link TurnDecision#CONTINUE}
     * 保证再来一次 provider 请求 —— 被工具结果/steering/follow-up 满足则不额外发
     * （{@code :294-298, :302-314} 的三段清零）；返回 {@code null} ≙ pi 的
     * {@code undefined}（照常调度）。错误/中止轮照样调用，裁决忽略（{@code :252}）。
     */
    @FunctionalInterface
    public interface FinishTurnHook {
        /** 裁决本轮回合之后的去向；{@code null} ≙ pi 的 undefined。 */
        TurnDecision apply(NextTurnContext context);
    }

    /** pi {@code transformContext}。返回 {@code null} 表示不改。 */
    @FunctionalInterface
    public interface ContextTransform {
        /** 在转成 provider 消息之前改写上下文；返回 {@code null} 表示不改。 */
        List<Message> apply(List<Message> messages);
    }

    // ═══════════════════════════════════════════════════════════════
    // 配置
    // ═══════════════════════════════════════════════════════════════

    /**
     * pi 的 {@code AgentLoopConfig} 在 pi-java 侧的对应物。所有可选字段用
     * {@code null} 表示「未配置」，与 pi 的 {@code ?.} 可选调用一致。
     *
     * <p>{@code streamListener} 是 pi-java 特有的**原始帧旁路**：pi 把用量等信息放在消息
     * 本身的 partial 里，pi-java 的 {@link StreamEvent.UsageInfo} 却是一个独立帧，且不属于
     * 生命周期事件（{@link PiLoopRunner#isUpdateEvent} 不含它）。若不旁路，token 记账与停因推导会
     * 静默丢失 —— 该帧同时喂给 harness 的既有广播链（{@code AgentHarness.onStreamEvent}），
     * 使会话层无需为切换驱动改造记账代码。</p>
     *
     * <p><b>没有 tools 字段</b>，与 pi 的 {@code AgentLoopConfig} 一致（{@code types.ts:145-213}
     * 实测无此字段）：工具定义属于 {@link Context}，只此一处。</p>
     */
    public record Config(
            ModelId<?> model,
            ModelThinkingLevel thinking,
            ThinkingLevelMap thinkingLevelMap,
            ToolExecution toolExecution,
            ToolRunner toolRunner,
            StreamFn streamFn,
            AbortSignal signal,
            Supplier<List<Message>> steeringMessages,
            Supplier<List<Message>> followUpMessages,
            ContextTransform transformContext,
            /** pi {@code AgentLoopConfig.prepareRequest}；请求时序在 transformContext 之前（agent-loop.ts:219/:390）。 */
            PrepareRequestHook prepareRequest,
            NextTurnHook prepareNextTurn,
            FinishTurnHook finishTurn,
            Consumer<StreamEvent> streamListener,
            /** B128：pi {@code AgentLoopConfig.thinkingBudgets}；null ≙ {@code undefined}。 */
            com.pijava.ai.thinking.ThinkingBudgets thinkingBudgets) {}

    private PiLoop() {}

    // ═══════════════════════════════════════════════════════════════
    // 入口：pi runAgentLoop / runAgentLoopContinue（agent-loop.ts:96-141）
    // ═══════════════════════════════════════════════════════════════

    /**
     * pi {@code runAgentLoop}：带一个新 prompt 启动循环。
     *
     * <p>prompt 会先进入上下文并逐条发 {@code message_start}/{@code message_end}。</p>
     *
     * @param prompts 新增的 prompt 消息
     * @param context 起始上下文（**会被就地扩展**，与 pi 一致）。
     *                循环持有一个**可被 {@code prepareNextTurn} 整体替换**的 context
     * @return 本次循环新增的消息（pi 的 {@code newMessages}）
     */
    public static List<Message> run(List<Message> prompts, Context context,
                                    Config config, Sink emit) {
        // pi: initialMessages = declareToolChanges(context, prompts)（agent-loop.ts:109）——
        // 起手先宣告一次工具装载，并把（可能新增的）声明消息**并进上下文**，于是内层循环
        // 再跑 declareToolChanges 时两侧已经相等、不会再重复宣告。少了这一支，工具集在
        // 「起手本来就与可执行集不一致」的上下文里（例如 conformance 那种只有工具的
        // AgentContext）会把声明推迟到第一次内层迭代，帧序与 pi 不同。
        var initialMessages = ToolChangeDeclaration.declare(context, prompts);
        var newMessages = new ArrayList<>(initialMessages);
        // pi: {...context, messages: [...context.messages, ...initialMessages]} —— 扩展**同一**列表
        context.messages().addAll(initialMessages);

        emit.emit(new Event.AgentStart());
        emit.emit(new Event.TurnStart());
        for (var message : initialMessages) {
            emit.emit(new Event.MessageStart(message));
            emit.emit(new Event.MessageEnd(message));
        }

        PiLoopRunner.runLoop(context, newMessages, config, emit);
        return newMessages;
    }

    /**
     * pi {@code runAgentLoopContinue}：不新增消息，从当前上下文续跑（重试用）。
     *
     * <p>前置条件与 pi 相同：上下文非空，且末条不是 assistant
     * —— 否则 provider 会拒绝请求（{@code agent-loop.ts:71-77}）。</p>
     */
    public static List<Message> continueRun(Context context, Config config, Sink emit) {
        var messages = context.messages();
        if (messages.isEmpty()) {
            throw new IllegalStateException("Cannot continue: no messages in context");
        }
        if (messages.get(messages.size() - 1).role().equals("assistant")) {
            throw new IllegalStateException("Cannot continue from message role: assistant");
        }

        var newMessages = new ArrayList<Message>();
        emit.emit(new Event.AgentStart());
        emit.emit(new Event.TurnStart());

        PiLoopRunner.runLoop(context, newMessages, config, emit);
        return newMessages;
    }
}
