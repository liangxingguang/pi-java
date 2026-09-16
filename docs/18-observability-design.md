# 18 - 可观察性层设计（trace / 审计 / 指标 / 日志）

## 1. 背景与问题

pi-java 此前的运行时是一个黑盒子：关键链路没有日志输出，一次 run 内发生了什么
（几轮 LLM 调用、每个工具耗时多少、token 花在哪里、为何重试）无从回溯。

现状是「三轨并行、互不打通」：

| 轨道 | 现状 | 缺口 |
|------|------|------|
| 日志 | slf4j+logback 已落地（`10-logging-design.md`），文件落 `~/.pi-java/logs/` | 全项目仅 8 个类有 logger，§7 分层埋点清单未落实；无 trace 上下文关联 |
| 遥测 | telemetry 模块有 `TelemetryContext/Span` 接口 + Noop + OTel 适配器（P6-20） | 仅 `AgentHarness` 一处 counter 调用；无 exporter 装配；`ExecutionContext` 无 telemetry 字段，埋点链路不通 |
| 审计 | `LaneRecord`（"for debugging, audit and recovery"）已随 session JSONL/SQLite 持久化 | 缺 `ToolFinished`（`ToolStarted.resultEntryId` 恒传空串）；所有环节无耗时字段；无 LLM 请求摘要 |

目标：

1. **监控**：全链路 trace、每轮 LLM 调用与工具调用埋点；指标（循环次数、token、失败率、耗时）
2. **审计**：每步动作完整日志、可回溯复现（payload 可选落盘、逐调用离线重放）
3. **调试**：主链路 SLF4J 日志输出

已确认的方案决策：trace 数据落**本地 JSONL 文件**（零新依赖；`OtelTelemetryContext`
保留，需要外接 Jaeger 时自行挂 OTel SDK exporter）；审计轨补全 + `--trace-payloads`
可选 payload 开关（默认关闭）；每次 run 结束打印 summary + 落盘；不动 TUI。

## 2. 三轨分工

| 轨道 | 载体 | 生命周期 | 消费者 |
|------|------|----------|--------|
| SLF4J 日志 | 人读文本行 | 运行时调试，滚转即弃 | 开发者 tail 文件 |
| Trace JSONL | 结构化 span/event 行 | 每进程一个文件，事后分析 | 离线分析脚本、外接 OTel |
| LaneRecord | session 审计轨 | 随 session 永久持久化 | session 回放、统计 |

同一事件（如一次 LLM 调用的耗时/tokens）三处记录是**刻意冗余**：日志给人看、
trace 供机器聚合、LaneRecord 是持久审计契约（随 session 可移植）。实现上埋点
代码物理相邻（同方法内相邻语句），改一处必见另两处。

## 3. 模块归属与接线

### 3.1 telemetry 模块

新增 `com.pijava.telemetry.JsonlFileTelemetry`（实现 `TelemetryContext`）：

- pom 加 `jackson-databind`（BOM 管 2.19.2）+ `slf4j-api`（写失败告警用）
- 纯 JDK NIO + jackson，native 兼容（jackson 已在 session 持久化 native 链路验证）
- 文件 IO 全部 best-effort：写失败 `LOG.warn` 一次后静默降级，**永不向上抛**
  （telemetry 不能杀死主链路）

### 3.2 埋点分工

| 层 | 职责 |
|----|------|
| agent-core（ActionExecutor / ToolExecutionPipeline / AgentHarness） | 打 span、写 LaneRecord、发 SLF4J 日志 |
| telemetry | 定义接口 + exporter 实现，不知道 agent 存在 |
| coding-agent（AgentSession.assemble / SessionRunner） | 装配：构造 exporter、注入 sessionId 维度、payload 捕获 StreamFn 包装、`--trace-payloads` 开关、run summary |

**关键接线**：`ExecutionContext` 加 `TelemetryContext telemetry` 字段，
`AgentHarness` 构造时传入——当前 ActionExecutor/ToolExecutionPipeline 完全摸不到
telemetry，这是埋点的先决条件。

## 4. TelemetryContext 接口扩展（最小增量）

回调式 `startSpan` 无法表达跨 action 边界的 `harness.run` span（run 从
`ActionExecutor.run()` 开始、在 `executeTryFinishRun()` 结束，中间穿过
SessionRunner 的 while 循环）。新增两个默认方法：

```java
// TelemetryContext:
/** 打开一个不由回调关闭的 span；调用方负责 close()（end）。 */
default TelemetrySpan openSpan(SpanOptions options) { ... }

// TelemetrySpan:
/** span 结束前补属性（token 数、stopReason 只有结束时才知道）。 */
default void addAttribute(String key, Object value) { }
```

三个实现同步改：

- `NoopTelemetryContext`：openSpan 返回单例 NoopSpan
- `OtelTelemetryContext`：openSpan 创建 OTel span 不立即 end，close() 时
  `span.end()`；addAttribute → `span.setAttribute`（P6-20 资产天然契合）
- `JsonlFileTelemetry`：见 §5

不变式：`startSpan(options, body)` = openSpan + try/finally close（默认方法），
两套 API 共存不冗余。

## 5. Trace 数据模型（JSONL 行格式）

### 5.1 文件与行类型

`~/.pi-java/logs/traces/trace-<sessionId>-<yyyyMMdd-HHmmss>.jsonl`
（每进程一个文件，追加；runId 在每行，跨 run 不碎文件）：

```jsonl
{"kind":"span_start","ts":"...","traceId":"<runId>","spanId":"a1b2c3d4","parentSpanId":"e5f6a7b8","name":"llm.request","sessionId":"...","lane":"default","attrs":{...}}
{"kind":"span_end","ts":"...","spanId":"a1b2c3d4","durationMs":2314,"status":"ok","attrs":{"inputTokens":12340,"outputTokens":1021,"stopReason":"tool_use"}}
{"kind":"event","ts":"...","traceId":"<runId>","spanId":"a1b2c3d4","name":"llm.payload.request","payload":{...}}
{"kind":"counter","ts":"...","sessionId":"...","name":"harness.turn","delta":1}
```

- `traceId` = runId（pi 对齐：operation id IS runId）；`sessionId` 经
  `telemetry.with("sessionId", id)` 注入，exporter 把维度并进每行
- spanId：8 字节 hex
- status：`ok` / `error` —— 与 pi 的 `SpanStatus` 同词汇（`packages/telemetry/src/index.ts:12`）。
  **中止不是状态，是结果属性**：`harness.run` 的结束属性 `outcome` 记
  `completed` / `aborted` / `failed` / `declined`，与 pi 的 `pi.operation.outcome` 同口径。
  （本节原写的第三种状态 `aborted` 从未被任何发射点写过，其死码已于 2026-09-17 删除；
  见 `docs/31 §8.28`。`span_end` 行自动带 `durationMs` 字段，不是 attribute。）
- span_end 缺失（进程崩溃）：离线分析按文件尾悬挂判定，可接受

### 5.2 parent-child 表达

**parent 关系不依赖 ThreadLocal**：span 对象自带 traceId/spanId/parentSpanId，子 span 以
parent 对象打开——天然解决 `runRawBatch` 虚拟线程并行（worker 线程直接引用
parent span 对象开子 span）。

**event 归属才是线程局部的**：`llm.payload.request/response` 这类 event 行没有自己的
span 对象，靠 `pushCurrent/popCurrent` 绑定的**当前线程栈顶**取 traceId/spanId。
该栈在 `JsonlFileTelemetry` 里是 `ThreadLocal`（`docs/31 §8.25`，2026-09-16 起）——
**绑与读必须同线程**；跨线程读不到他人的绑定（此前是共享 `ArrayDeque`，
跨线程会**读到别人的 span**，属静默错配，且读写全在文件锁之外）。

### 5.3 Span 清单

五个跨度名**全部是 pi-java 自有词汇**。pi 声明了 12 个 `pi.*` 名，但 v0.85.1 的实况是
**11/12 一个发射点都没有**（唯一有发射点的 `pi.harness.hook` 覆盖的是另一件事），两份 schema
没有 `events:` 声明，生产路径**从不安装真 adapter**。故按本分支判据（**行为**，不是文档）
**不采用 `pi.*` 名** —— 取证与裁决见 `docs/31 §8.28.1` / `§8.28.3`（选项 A）。

| span name | 打开点 / 关闭点 | 关键 attributes |
|-----------|----------------|-----------------|
| `harness.run` | `RunSpanFactory.openRunSpan`（`RunLifecycle.startRun:56`、`startContinue:134` 两处起手）；`RunSpanFactory.closeRunSpan`（`RunLifecycle.finishRun:187` 终局收口） | start：lane、promptChars；end：outcome（`completed`/`aborted`/`failed`/`declined`）、stopReason |
| `llm.request` | 回调式：`PiLaneSink.beginRequest:185` 开并 `pushCurrent`（事件行靠它归属），`PiLaneSink.endRequest:206-215` 关并 `popCurrent` | start：attempt、model、messageCount、toolCount、thinking；end：inputTokens、outputTokens、stopReason |
| `tool.execute` | `PiLaneSink.noteToolStart:237` 每 call 一个（父级 = 该次运行的 `harness.run` 跨度）；`closeToolSpan` 在**结果消息**落定时关 | start：toolCallId、toolName、toolIndex、argsChars；end：batchSize、allowed、isError、terminate、durationMs |
| `compaction.apply` | `openSpan`：`CompactionExecutor.applyCompaction:240`（父级同上；`finally` 里 `close`） | start：reason（`manual`/`threshold`/`overflow`）、estimatedTokens、entriesBefore；end：entriesAfter |
| `compaction.summary` | `openSpan` + `pushCurrent`：`CompactionExecutor.compactTranscript:385`（父级 = `compaction.apply`；摘要生成期间绑定，`finally` 里 `close` 后 `popCurrent`） | start：reason；end：summaryChars、inputTokens、outputTokens（后两者仅当生成器报了用量） |

> 2026-09-17 更正：本表原先写的打开/关闭点是 `ActionExecutor.run()` /
> `executeTryFinishRun()` / `executeStreamAssistant` / `ToolExecutionPipeline.executeStages`
> —— 那**四个类都已随 `PiLoop` 驱动（`docs/28`）删除**，属性列也与实测不符
> （`harness.run` 从来没有 `attemptCount`/累计 token 属性，`compaction.apply` 没有
> `tokensBefore`）；同一轮里 `compaction.apply` 的「回调式」与 reason 取值 `auto`
> 也是错的（它是 `openSpan` + `finally`，reason 是 `threshold`）。上表按现役代码逐处重写。
>
> `compaction.summary` 是 **2026-09-17** 新加的（`docs/31 §8.29`）：摘要是一次真正的
> LLM 调用（走同一个 `streamFn`），此前它的负载行是**孤儿** —— 无 `traceId`/`spanId`，
> 且是链路上唯一花掉 token 却不留痕的调用。它**不**复用 `llm.request` 名，正是为了让
> 「按名数 `llm.request` = agent 轮数」这条既有聚合口径不被改写；同理，摘要的用量
> **不**计入 `llm.requests`/`llm.tokens.*` 三个计数器（那是主循环的聚合），要聚合按这个
> 跨度名取。

计数器：`harness.turn`（已有）、`harness.run`、`llm.requests`、
`llm.tokens.input`、`llm.tokens.output`、`tool.executions`、`tool.errors`、
`compactions`。计时：`llm.request.duration`、`tool.execute.duration`——与 span
的 durationMs 刻意冗余：span 行服务单次 trace 回放，metric 行服务跨 run 聚合。

## 6. 审计轨补全（LaneRecord）

### 6.1 新变体 `tool_finished`

```java
record ToolFinished(String id, long seq, String lane, Instant timestamp,
    String runId, String toolCallId, String toolName,
    boolean isError, boolean terminate, String resultEntryId,
    long durationMs) implements LaneRecord {}
```

写入点：ActionExecutor.executeTool/executeToolBatch，紧跟 ToolStarted。
顺手修正 `appendEntry` 返回 entry id（现 void），让 ToolStarted 的
resultEntryId 不再传空串。

### 6.2 durationMs 加点（全 optional，NON_NULL 序列化）

| 变体 | 加什么 |
|------|--------|
| `OperationFinished` | `Long durationMs`（run 起点→终态） |
| `StepAttempt` | `Long durationMs`（LLM 步耗时） |
| `ToolFinished`（新） | 内含 |
| `ToolStarted` | 不加（耗时归 finished） |

### 6.3 LLM 请求摘要 → 扩展 StepAttempt（不新开变体）

StepAttempt 语义即 "A single LLM call attempt"，新字段全 optional：

```
model(String, "provider/name"), messageCount(Integer), toolCount(Integer),
thinking(String, "off"|"budget=8000"|"effort=high"), durationMs(Long)
```

token 已有 `UsageRecord`（每步已写），不重复。

### 6.4 兼容性

- **旧文件可读**：新字段全 optional → 旧文件零影响
- **新文件被旧 reader 读**：`tool_finished` 命中 `JsonlCodec.RECORD_TYPES`
  白名单 + RecordJsonCodec 的 strict decode 会抛 `DecodeError.schema`。
  **决策：接受**（同仓发布，交叉读仅发生在手动拷贝 session 场景），不做宽容
  解码（保持 pi codec 严格性对齐），docs 记录
- **SQLite**：`records.payload` 整体 JSON，零 DDL。`SqliteCodecs.recordRunId`
  加 ToolFinished case（否则 run_id 列为 null，破坏 run 索引查询）
- **穷举 switch 清单**（编译器强制找齐）：`LaneRecord.type()`、
  `LaneRecord.committed()`、`RecordJsonCodec.decode()`、`SqliteCodecs.recordRunId`

## 7. `--trace-payloads` 开关

### 7.1 传递路径

```
Args(@Option "--trace-payloads") → AgentSession.assemble() 直接读
  → new JsonlFileTelemetry(dir, payloads=...)   // 开关落 exporter 构造参数
  → streamFn = new PayloadRecordingStreamFn(inner, exporter, payloads)
```

不加 HarnessConfig 字段：唯一消费者（StreamFn 包装）在 coding-agent、args 就在
手边，往 agent-core 的 record 加没人读的字段是死配置。

### 7.2 记录点（PayloadRecordingStreamFn，coding-agent 内 ~60 行）

- **请求**：`stream()` 内序列化 {model, messages, tools(名+schema), maxTokens,
  temperature, extra} 发 `llm.payload.request` event 行。
  **wire 原文拿不到**（`AbstractChatApi.streamInternal` 内部组装不外露），
  StreamRequest 级别已足够离线重放（把 payload 里的 messages 喂回 streamFn）
- **响应**：包装 StreamIterator 透传，终结时（StreamDone/StreamError）用最后
  事件的 `partial()` 序列化为 `llm.payload.response`
- **脱敏**：`StreamRequest` 不含 ApiOptions，API key 天然不落；约定 extra 禁放
  凭证。payload 文件含用户代码/对话原文，属预期行为，文档提示敏感

### 7.3 关联机制

主循环：`PiLaneSink.beginRequest`（`llm.request` span 的打开点，`:185`）push、
`endRequest`（`:211`）pop；记录点（`PayloadRecordingStreamFn` 的请求/响应两处）取
**当前线程**栈顶 = 本次请求的 `llm.request` span，event 行因此自动带正确 traceId/spanId。

压缩：摘要走的是**同一个** `streamFn`，归属也走同一套机制 ——
`CompactionExecutor.compactTranscript:389` 在摘要生成期间 push `compaction.summary`
跨度、`finally` 里 pop，于是摘要的请求/响应行绑到**压缩自己**的跨度上
（2026-09-17 之前这里没有 push，那些行是孤儿行；见 `docs/31 §8.29`）。

⚠️ 这两处同线程是**调用形状的产物，不是结构保证**：生产每次 prompt 换一条新虚拟线程
（`AgentSession:545`），运行中 `/compact` 会落到另一条线程上（`RunLifecycle.compact`
没有 `isRunning` 门，`docs/31 §8.25.5-7`），并发 prompt 同样没有门。栈是 `ThreadLocal`
（`docs/31 §8.25`）⇒ 谁都没绑定时**读不到绑定**（event 行缺 traceId），而不会读到
别人的 span；压缩那一路因为自带绑定，即便与在飞请求重叠也记在自己名下。

## 8. Run Summary

### 8.1 数据源

| 指标 | 来源 |
|------|------|
| attempts / willRetry 次数 | SessionRunner.drive 局部变量 |
| input/output tokens / cost | drive 循环内 stream 监听器累计 `UsageInfo`（**TokenCounter 是 session 累计值不能直接用**）；cost 取 `usage.cost().total()`（null→0） |
| 工具成功/失败数 | `snapshot(lane).records()` 过滤 runId 匹配的 `ToolFinished.isError` |
| durationMs | drive 循环墙钟 |
| stopReason / exitCode | `RunStatus`（已有） |
| 循环次数 | 本次 run 的 StepAttempt 计数 |

`RunSummaryAggregator`（coding-agent/core，~80 行纯聚合）。

### 8.2 打印点

`SessionRunner.drive()` 的 `statusFuture.complete(...)` 之前（覆盖 -p/TUI/RPC
全模式）。输出通道：`LOG.info`（恒写文件）+ 仅非 TUI 模式 `System.err.println`
（stdout 被 assistant 正文占用，绝不能混）。TUI 不动。

格式：

```
[pi-java] run summary: attempts=1 durationMs=5234ms stopReason=completed
  tokens: in=12,340 out=1,021 cost=$0.0432
  tools: 6 ok, 1 failed | retries: 0
```

## 9. 主链路 SLF4J 日志

### 9.1 埋点清单（对齐 `10-logging-design.md` §7，控制在关键节点）

**agent-core**（新增 logger：`ActionExecutor`、`ToolExecutionPipeline`）：

| 位置 | 级别 | 内容 |
|------|------|------|
| run 开始/结束 | INFO | runId、lane、promptChars / outcome、durationMs、tokens |
| LLM 调用结束 | DEBUG | model、messageCount、toolCount、耗时、in/out tokens、stopReason |
| LLM 流错误 catch | WARN | 异常对象入参 `LOG.warn("...", e)` |
| 工具开始/结束 | DEBUG | toolName、durationMs、isError |
| compaction | INFO | reason、tokensBefore→After、耗时 |
| abort | INFO | lane、runId |

**coding-agent**：SessionRunner 重试 WARN（attempt、delayMs、错误摘要）。
**ai 模块**（新增 logger：`PiHttpClient`）：HTTP 重试 WARN（status、attempt、
Retry-After）；流式错误 WARN。ai pom 已有 slf4j-api，零依赖变更。

### 9.2 规范

- 库模块只用 slf4j-api（现状如此）；`{}` 占位符不拼接
- **敏感信息不落日志**：API key、prompt/messages 全文永不进日志——payload
  落盘走 `--trace-payloads` 的 trace 文件；工具参数只落 toolName+argsChars
- 级别约定：INFO=run 生命周期、DEBUG=每步细节、WARN=重试与降级、ERROR=失败终点

## 10. 实施顺序

| # | Commit | 模块 | 内容 |
|---|--------|------|------|
| 1 | `docs: 18-observability-design.md` | docs | 本文档 |
| 2 | `feat(telemetry): openSpan/addAttribute + JSONL file exporter` | telemetry | 接口扩展、Noop/Otel 同步、JsonlFileTelemetry、pom |
| 3 | `feat(agent-core): LaneRecord ToolFinished + duration + LLM summary` | agent-core, session-backend-sqlite | §6 全部 |
| 4 | `feat(agent-core): harness.run + llm.request + compaction spans` | agent-core | ExecutionContext 注入、run span 存 LaneState、埋点 + 计数器 + 日志 |
| 5 | `feat(agent-core): tool.execute span + tool lifecycle logging` | agent-core | 每-call span（批量 worker push/pop）、工具日志 |
| 6 | `feat(coding-agent): trace exporter assembly + --trace-payloads` | coding-agent | Args 开关、assemble 装配、PayloadRecordingStreamFn |
| 7 | `feat(coding-agent): run summary` | coding-agent | RunSummaryAggregator + drive 打印 |
| 8 | `feat(ai): HTTP retry + stream error logging` | ai | PiHttpClient WARN、流错误日志 |

每步 `mvn clean verify` 零错误（JAVA_HOME 指向 GraalVM 25；底层模块改动带 `-am`）。

## 11. 风险

| 风险 | 评估 | 缓解 |
|------|------|------|
| native image | 低：仅 JDK NIO + jackson（已在 native 链路） | 无需新增 reflect-config。native+TUI 下 slf4j-simple 无法 detach console，新 WARN 会写 stderr——既有约束的放大，记录不改 |
| TUI 与 trace 冲突 | 无 | trace 文件独立于 logback appender |
| JSONL v4 前向兼容 | 旧二进制读新 session 抛 schema 错 | 接受并文档化（§6.4） |
| 高并发 span 写 | exporter 单文件多线程追加 | 单锁 + 每次 span_end/event flush（崩溃丢尾可接受） |
| SpanOptions Map.copyOf 拒 null | 埋点传 null 会 NPE | 埋点统一 null→省略，exporter 兜底过滤 |
| payload 体积 | --trace-payloads 长会话可达百 MB | 默认关闭；单文件按进程切分；文档提示 |
