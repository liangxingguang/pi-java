package com.pijava.coding.agent.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.NestedToolCallRecord;
import com.pijava.ai.message.NestedToolCalls;

/**
 * Collects every nested call made under one model-issued call (at any depth)
 * — pi {@code NestedCallRecorder}（{@code core/nested-tool-calls.ts:47-101}）。
 *
 * <p><b>四条有界规则</b>（pi {@code NESTED_CALL_LIMITS}，:26-31 逐字）：</p>
 * <ul>
 *   <li>最多 {@value #MAX_CALLS} 条调用，超出丢弃并置 {@code complete=false}</li>
 *   <li>单条参数超 {@value #MAX_ARGUMENT_BYTES_PER_CALL} 字节只记 {@code argumentsBytes}</li>
 *   <li>参数累计超 {@value #MAX_ARGUMENT_BYTES_TOTAL} 字节同样只记字节数</li>
 *   <li>错误文本截 {@value #MAX_ERROR_CHARS} 字符</li>
 * </ul>
 *
 * <p><b>并发方言</b>（批 26 spotbugs 钉）：pi 单线程数组对位；java 的工具以虚拟线程
 * 真并发执行（共享 recorder 经子 scope 归并），全部方法进 synchronized 临界区。</p>
 *
 * <p>计时用 {@link System#nanoTime()}（pi 的 {@code performance.now()}，窄差 D162）。</p>
 */
public final class NestedCallRecorder {

    /** pi {@code maxCalls}（:27）。 */
    public static final int MAX_CALLS = 256;

    /** pi {@code maxArgumentBytesPerCall}（:28）。 */
    public static final int MAX_ARGUMENT_BYTES_PER_CALL = 8 * 1024;

    /** pi {@code maxArgumentBytesTotal}（:29）。 */
    public static final int MAX_ARGUMENT_BYTES_TOTAL = 32 * 1024;

    /** pi {@code maxErrorChars}（:30）。 */
    public static final int MAX_ERROR_CHARS = 500;

    private final List<NestedToolCallRecord> calls = new ArrayList<>();
    /** pi 的 {@code startedAt: Map<record, number>}（对象身份键，:49）。 */
    private final Map<NestedToolCallRecord, Long> startedAt = new IdentityHashMap<>();
    private boolean complete = true;
    private int argumentBytes;
    /** 嵌套结果的计量合计（含被丢弃调用的计量，pi :51 注释）。java 方言：
     *  {@code ToolResult.UsageInfo}（input/output 两字段；pi 是全量 Usage，窄差 D166）。 */
    private ToolResult.UsageInfo usage;

    /**
     * pi {@code start}（:56-77）：超限丢弃 / 省参数 / 否则深拷贝参数并计时。
     *
     * @return 登记的记录（{@code finish} 的操作数）；超 {@link #MAX_CALLS} 时返回
     *         {@code null}（该调用只丢记录，仍计入 usage）
     */
    public synchronized NestedToolCallRecord start(PiLoop.ToolCall toolCall) {
        if (calls.size() >= MAX_CALLS) {
            complete = false;
            return null;
        }
        NestedToolCallRecord record;
        try {
            var json = Json.mapper().writeValueAsString(toolCall.args());
            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_ARGUMENT_BYTES_PER_CALL
                    || argumentBytes + bytes > MAX_ARGUMENT_BYTES_TOTAL) {
                complete = false;
                record = new NestedToolCallRecord(toolCall.toolCallId(), toolCall.toolName(),
                    null, (long) bytes, NestedToolCallRecord.Status.UNFINISHED, null, null);
            } else {
                argumentBytes += bytes;
                // pi 的 JSON.parse(json) 深拷贝（:70）
                record = new NestedToolCallRecord(toolCall.toolCallId(), toolCall.toolName(),
                    Json.mapper().readTree(json), null,
                    NestedToolCallRecord.Status.UNFINISHED, null, null);
            }
        } catch (Exception e) {
            // 参数不可序列化（java 方言：JS 万物可 stringify）——按「只记字节数」臂降级，
            // 字节数不可知记 0；记录本身不丢（调用确实发生了）。
            complete = false;
            record = new NestedToolCallRecord(toolCall.toolCallId(), toolCall.toolName(),
                null, 0L, NestedToolCallRecord.Status.UNFINISHED, null, null);
        }
        calls.add(record);
        startedAt.put(record, System.nanoTime());
        return record;
    }

    /** pi {@code finish}（:79-85）：定局状态/耗时/截断错误文本。 */
    public synchronized void finish(NestedToolCallRecord record, boolean isError,
                                    String errorText) {
        if (record == null) {
            return; // 被丢弃的调用没有记录可定局（pi 同：start 返回 undefined 后 finish 跳过）
        }
        var started = startedAt.remove(record);
        long durationMs = started == null ? 0L
            : Math.round((System.nanoTime() - started) / 1_000_000.0);
        int index = indexOfIdentity(record);
        if (index < 0) {
            return; // snapshot/takeRecord 后晚到的 finish（防御；pi 同引用即中）
        }
        calls.set(index, new NestedToolCallRecord(record.id(), record.name(),
            record.arguments(), record.argumentsBytes(),
            isError ? NestedToolCallRecord.Status.ERROR : NestedToolCallRecord.Status.OK,
            durationMs,
            isError && errorText != null
                ? errorText.substring(0, Math.min(MAX_ERROR_CHARS, errorText.length()))
                : null));
    }

    /** pi {@code addUsage}（:87-89）：含被丢弃调用的计量。 */
    public synchronized void addUsage(ToolResult.UsageInfo delta) {
        if (delta == null) {
            return;
        }
        usage = usage == null ? delta
            : new ToolResult.UsageInfo(usage.inputTokens() + delta.inputTokens(),
                usage.outputTokens() + delta.outputTokens());
    }

    /** pi {@code totalUsage}（:91-93）。 */
    public synchronized ToolResult.UsageInfo totalUsage() {
        return usage;
    }

    /**
     * pi {@code snapshot}（:96-100）：空调用且 complete ⇒ {@code null}（≙ undefined）；
     * 否则浅拷贝，{@code complete} 附带「无 unfinished」条件。
     */
    public synchronized NestedToolCalls snapshot() {
        if (calls.isEmpty() && complete) {
            return null;
        }
        boolean allFinished = true;
        for (var call : calls) {
            if (call.status() == NestedToolCallRecord.Status.UNFINISHED) {
                allFinished = false;
                break;
            }
        }
        return new NestedToolCalls(List.copyOf(calls), complete && allFinished);
    }

    private int indexOfIdentity(NestedToolCallRecord record) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i) == record) {
                return i;
            }
        }
        return -1;
    }
}
