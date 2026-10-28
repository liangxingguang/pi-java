package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.ReplayKind;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

/**
 * {@link PiLaneSink} 的**工具簇**抽离（结构债拆分：行为零变化纯移动，
 * javadoc/注释逐字随代码搬 —— volatile/跨线程字段的可见性注释同迁）：
 * 工具调用的终止/起算/放行三张回填表、{@code tool.execute} 跨度表、批次成员与
 * 批次总数，以及 {@code ToolStarted}/{@code ToolFinished} 记录的发射。
 *
 * <p>生命周期与 sink 一致（每 pass 一份，随 sink 构造）。引擎侧经
 * {@code sink.toolRecords()} 直达；sink 的 {@code message_end} 臂改调
 * {@link #onAssistantSettled} 与 {@link #emitToolRecords}。</p>
 */
final class PiLaneToolRecords {

    /** 只用来量参数规模的 JSON 序列化器。 */
    private static final ObjectMapper ARGS_MAPPER = new ObjectMapper();

    private final ExecutionContext ctx;
    private final String laneName;

    /**
     * 本次工具调用的终止标记与起算时刻，由 {@link PiToolRunner} 回填。
     *
     * <p>{@code terminate} 是两表中**唯一**会被工具线程写的（执行票在自己的线程上定局，
     * {@code PiLaneEngine.observing} 在 {@code execute} 返回处回填）⇒ 并发映射；
     * 起算表只在准备相与收束后被碰（都在引擎线程），保持 {@code HashMap}。</p>
     */
    private final Map<String, Boolean> toolTerminate = new ConcurrentHashMap<>();
    private final Map<String, Long> toolStartNanos = new HashMap<>();

    /**
     * 本次工具调用的 {@code before_tool} 判定（pi-java 可观测性层：被钩子拒绝与
     * 「工具自己失败」在结果消息上都只是 {@code isError=true}，跨度需要分开）。
     */
    private final Map<String, Boolean> toolAllowed = new HashMap<>();

    /** 本次工具调用的 {@code tool.execute} 跨度，在 {@link #noteToolStart} 开、结果消息处关。 */
    private final Map<String, TelemetrySpan> toolSpans = new HashMap<>();

    /**
     * 本轮的**工具批次**成员（按 start 顺序），用于 {@code toolIndex}。
     *
     * <p>{@code ToolExecutionPipeline} 曾按批次整体处理，所以跨度带 {@code toolIndex} /
     * {@code batchSize}；pi 的驱动是逐调用经过端口，批次形状只在
     * {@code PiLoopTools} 里。这里用「助手消息落定后清空」重建同一个批次 ——
     * 一个助手消息之后的全部 start 恰是同一批，**与 end 的先后无关**。
     * {@code toolIndex} 因此两条路径都正确（列表在准备相单调增长）。</p>
     */
    private final List<String> batchCallIds = new ArrayList<>();

    /**
     * 本批的**调用总数**，{@code batchSize} 属性的取值来源；在助手消息落定时定下。
     *
     * <p>为什么不读 {@link #batchCallIds}{@code .size()}：那个列表在**准备相**逐调用增长，
     * 而 {@code batchSize} 的**写入时刻**由结果消息决定 —— 并行路径在整批 join 之后写
     * （相位③，此刻列表恰好含全批），顺序路径**逐调用成组**写
     * （{@code PiLoopTools.executeSequential}，第 k 个收尾时只 start 过 k 个）
     * ⇒ 读列表拿到的是**前缀数 1,2,…,N**。pi 自己的录制正是那个形状
     * （{@code conformance/pi-out/S10.pi.jsonl:11-18}）。</p>
     *
     * <p>总数在那条助手消息落定时就已知（它携带全部 tool_use 块），而帧序保证
     * {@code message_end(assistant)} **先于**该批任何 {@code tool_execution_start}
     * （{@code agent-loop.ts}；{@code PiLoopTest} 逐帧钉住）⇒ 写入时它必已就位。
     * 详见 {@code 原 docs/31 §8.26.5-11}。</p>
     */
    private int batchCallCount;

    PiLaneToolRecords(ExecutionContext ctx, String laneName) {
        this.ctx = ctx;
        this.laneName = laneName;
    }

    /**
     * 工具调用开始：记起算时刻与批次位置，并打开 {@code tool.execute} 跨度
     * （嵌套在 {@code harness.run} 之下，与旧路径的跨度形状一致）。
     */
    void noteToolStart(PiLoop.ToolCall call) {
        var callId = call.toolCallId();
        toolStartNanos.put(callId, System.nanoTime());
        batchCallIds.add(callId);
        var parent = laneRunSpanOrRoot();
        toolSpans.put(callId, parent.openSpan(new SpanOptions("tool.execute", Map.of(
            "toolCallId", callId,
            "toolName", call.toolName(),
            "toolIndex", batchCallIds.size() - 1,
            "argsChars", safeArgsChars(call.args())))));
    }

    /** 跨度的父级：本次运行的 {@code harness.run} 跨度，缺席时退回遥测根。 */
    private com.pijava.telemetry.TelemetryContext laneRunSpanOrRoot() {
        var lane = ctx.requireLane(laneName);
        return lane.runSpan != null ? lane.runSpan : ctx.telemetry();
    }

    /** 由 {@link PiToolRunner} 回填 {@code terminate}（事件载荷里没有这个字段）。 */
    void noteToolTerminate(String toolCallId, boolean terminate) {
        toolTerminate.put(toolCallId, terminate);
    }

    /** 由 {@link PiToolRunner} 回填 {@code before_tool} 是否放行。 */
    void noteToolAllowed(String toolCallId, boolean allowed) {
        toolAllowed.put(toolCallId, allowed);
    }

    /** 工具参数的字节数（可观测性只记规模，不记参数本身）。 */
    private static int safeArgsChars(Map<String, Object> args) {
        try {
            return ARGS_MAPPER.writeValueAsBytes(args).length;
        } catch (Exception e) {
            return args.toString().length();
        }
    }

    /**
     * 助手消息里的工具调用数 = 本批的调用总数（{@code batchSize} 的取值）。
     *
     * <p>读助手消息而不是数 {@link #batchCallIds}：那里数的是**已经 start 过的**，而
     * 「本批有多少个」在助手消息落定时就已全部可见。</p>
     */
    private static int countToolCalls(Message.AssistantMessage assistant) {
        int count = 0;
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.ToolUseContent) {
                count++;
            }
        }
        return count;
    }

    /**
     * 助手消息落定 ⇒ 本轮的批次结束：清成员表，并把**本批总数**定下来
     * （batchSize 的来源 —— 它携带全部 tool_use 块，而帧序保证本条先于该批任何
     * tool_execution_start；见 batchCallCount 的 javadoc）。
     */
    void onAssistantSettled(Message.AssistantMessage assistant) {
        batchCallIds.clear();
        batchCallCount = countToolCalls(assistant);
    }

    /**
     * 工具步的 {@code ToolStarted} / {@code ToolFinished}。
     *
     * <p>在**结果消息的 {@code message_end}** 上发射，而不是 {@code tool_execution_end}
     * —— pi 的发射顺序是 start → end → 结果消息（{@code agent-loop.ts:442-479}），
     * 到 end 时结果 entry 还不存在。</p>
     *
     * <p><b>已知缺口</b>：{@code effectiveArgs} 记空表 —— 参数已由
     * {@link PiToolRunner} 交给注册表，此处不再持有副本。
     * {@code RunSummaryAggregator} 只读 {@code isError}/{@code runId}，不受影响
     * （原 docs/28 §5.1 已记录该降级）。</p>
     */
    void emitToolRecords(LaneState lane, Entry.Message entry,
                         Message.ToolResultMessage result) {
        String callId = result.toolUseId();
        long startNanos = toolStartNanos.getOrDefault(callId, System.nanoTime());
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        boolean terminate = Boolean.TRUE.equals(toolTerminate.get(callId));
        lane.records.add(new LaneRecord.ToolStarted(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            "", 0, callId, result.toolName(), Map.of(), entry.id(), ReplayKind.NEVER));
        lane.records.add(new LaneRecord.ToolFinished(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            callId, result.toolName(), result.isError(), terminate, entry.id(), durationMs));
        closeToolSpan(callId, result, terminate, durationMs);
        ctx.telemetry().incrementCounter("tool.executions", 1);
        if (result.isError()) {
            ctx.telemetry().incrementCounter("tool.errors", 1);
        }
        ctx.telemetry().recordTiming("tool.execute.duration", durationMs);
        toolStartNanos.remove(callId);
        toolTerminate.remove(callId);
    }

    /**
     * 关闭该调用的 {@code tool.execute} 跨度并补齐属性。
     *
     * <p>{@code batchSize} 取 {@link #batchCallCount} —— 助手消息落定时定下的**本批总数**
     * （见该字段的 javadoc），<b>不读</b> {@link #batchCallIds}{@code .size()}：那个列表在准备相
     * 逐调用增长，而这里的读取时刻由**结果消息**驱动，顺序路径（逐调用成组）读数时会少算
     * （{@code 原 docs/31 §8.26.5-11}，pi 自己的录制 {@code S10.pi.jsonl:11-18} 即该形状）。
     * 并行路径的相位③（结果在整批 join 之后按源序回补）因此不再是这个属性成立的前提，
     * 只是与它一致的一个来源。</p>
     *
     * <p>没有跨度的调用（被 {@code PiLoopTools.failTruncated} 直接失败掉的截断调用
     * ——它**没有经过**工具端口）只是没有可观测性记录，不补一个假的。</p>
     */
    private void closeToolSpan(String callId, Message.ToolResultMessage result,
                               boolean terminate, long durationMs) {
        var span = toolSpans.remove(callId);
        var allowed = toolAllowed.remove(callId);
        if (span == null) {
            return;
        }
        span.addAttribute("batchSize", batchCallCount);
        // 缺席即放行：只有 PiToolRunner 的拒绝分支才回填 false。截断失败掉的调用没有跨度，
        // 走不到这里。
        span.addAttribute("allowed", allowed == null || allowed);
        span.addAttribute("isError", result.isError());
        span.addAttribute("terminate", terminate);
        span.addAttribute("durationMs", durationMs);
        span.close();
    }

    /**
     * 本次请求带的工具数 —— **生效子集**，不是注册表规模。
     *
     * <p>包 A3 的 R6（{@code 原 docs/51 §9}）：设计稿要求「实测后定，不许凭直觉选」。
     * 实测的结论是 <b>pi 侧没有这个属性</b> —— {@code git grep toolCount} 在 pi 的
     * {@code packages/} 下零命中（只有 tui 的一个同名局部量），pi 的 {@code llm.request}
     * 跨度里既没有 {@code toolCount} 也没有 {@code messageCount}。⇒ 本字段是 **pi-java 的
     * 扩展**（{@code 原 docs/31 §8.28} 的口径：adapter 比 pi 多，不是缺口），没有可对齐的 pi 真值
     * ⇒ 定义由我们负责，正确答案是「这次请求带了多少工具」。
     *
     * <p>⚠️ 于是这里从 {@code all().size()} 改成生效子集：注册表里有 20 个工具、本次只装载 5 个时，
     * 旧写法记 20。A3b 之后请求级工具表由转录重放决定、收敛到生效集
     * （{@code declareToolChanges} 的终点），所以生效集才是对的那个数。</p>
     */
    int toolCount() {
        if (ctx.toolRegistry() == null) {
            return 0;
        }
        var names = ctx.activeTools().get().stream().map(tool -> tool.name()).toList();
        return ctx.toolRegistry().activeOf(names).size();
    }
}
