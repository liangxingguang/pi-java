# Phase 2 审查修复 — 设计文档

> **目标**：修复 Phase 2 全量审查发现的 3 项剩余缺口（ThinkingLevelMap、OverflowDetector、ToolExecutor），并将 `run()` 单轮语义结论落实到文档。
> **前置**：Phase 2a/2b/2c 功能已实现，验收测试已补齐（223 测试全绿）。

---

## 1. 背景

Phase 2 全量审查（`git diff 928b3c5`，覆盖 2a+2b+2c）发现 11 项 Spec 问题，其中 8 项已在前几轮修复。剩余 3 项为 Phase 2a/2b 的实质缺口，本轮修复：

| # | 缺口 | 阶段 | 严重度 |
|---|------|------|--------|
| 1 | Thinking 翻译绕过 `ModelInfo.thinkingLevelMap`，硬编码预算 | 2a | 中 |
| 2 | `OverflowDetector`/`ContextEstimator` 未集成，循环用 `CompactionService.estimateTokens`（chars/4）| 2a | 中 |
| 3 | `ToolExecutor` 被删，工具执行内联进 `ActionExecutor.executeTool` | 2b | 中 |

另有 1 项经评估**保留**（非 bug）：`run()` 的 `transcript.clear()`。

---

## 2. 保留结论（落实到文档）

### 2.1 `run()` 清空 transcript 是正确行为

**结论**：`ActionExecutor.run()` 调用 `lane.transcript.clear()` 是 Phase 2 的**正确行为**，非 bug。

**依据**：
- 设计文档 `07-phase2a` §6.1 状态机为 `idle → run(prompt)`，`run()` 在非 idle 时抛异常——即**单轮语义**。
- 多轮连续（steer/followUp 队列消费）在设计文档 §17 明确**推迟到 Phase 3**。
- pi 参考的 `run()` 追加行为对应多轮，但那由 Phase 3 的 steer/followUp 承载，非 Phase 2 的 `run()`。
- 单轮内 transcript 正常累积（user → assistant → tool → assistant），overflow 检测作用于单轮内。

**落实**：在 `docs/07-phase2a-agent-loop-design.md` 的 run() 说明处补充此结论。

---

## 3. 修复设计

### 3.1 Fix 1 — ThinkingLevelMap 接线（2a）

**问题**：`ActionExecutor.executeStreamAssistant` 调 `ThinkingTranslator.translate(level)`（硬编码 1024/2048/8192/16384/32768），忽略模型自带的 `ModelInfo.thinkingLevelMap`。规格 §3.2 要求用 `thinkingLevelMap.forLevel(level)`（含 clamp）。

**设计**：
1. `HarnessConfig` 新增 `ThinkingLevelMap thinkingLevelMap` 字段（默认 `ThinkingLevelMap.empty()`）。
2. `ExecutionContext` 以 `Supplier<ThinkingLevelMap>` 暴露。
3. `executeStreamAssistant` 改用 `ctx.thinkingLevelMap().get().forLevel(level)`。
4. 删除 `ThinkingTranslator`（其硬编码预算逻辑不再需要；空 map 时 `forLevel` 返回 `OFF`，符合「模型必须声明思考支持」）。

**行为变化**：未提供 map 时思考默认关闭（OFF），此前硬编码预算。这是**符合规格的修正**（模型须声明支持）。

**影响文件**：`HarnessConfig.java`、`ExecutionContext.java`、`ActionExecutor.java`、删除 `ThinkingTranslator.java`。

### 3.2 Fix 2 — OverflowDetector 集成（2a）

**问题**：`ContextEstimator`/`OverflowDetector` 已定义且单测通过，但循环未集成。`checkAutoCompact` 用 `CompactionService.estimateTokens`（chars/4，作用于 Entry），与 `ContextEstimator`（chars/3.5，作用于 Message）启发式不一致。

**设计**：
1. `executeStreamAssistant` 在 LLM 调用**后**，用 `OverflowDetector.isOverflow(error, stopReason, usage, maxInputTokens)` 检测溢出（三重检测：错误消息模式、token 数比较、零输出 + length）。
2. 若检测到溢出，触发 `applyCompaction`（复用现有压缩逻辑）。
3. 保留 `checkAutoCompact`（请求前预检查），但将其 token 估算从 `CompactionService.estimateTokens` 改为 `ContextEstimator`（启发式统一）。

**实现要点**：
- 需在 `executeStreamAssistant` 捕获 LLM 调用的异常（当前 `catch` 后未保留 error），保留 error + usage 供 `OverflowDetector` 使用。
- `checkAutoCompact` 需先构建消息列表，再用 `ContextEstimator.estimateTokens(messages)`。

**影响文件**：`ActionExecutor.java`（新增 OverflowDetector 调用 + error 捕获）、`ContextEstimator`/`OverflowDetector`（已存在，无需改）。

### 3.3 Fix 3 — ToolExecutor 恢复（2b）

**问题**：`ToolExecutor`（规格 §7 `executeSequential`/`executeParallel`）被删，逻辑内联进 `ActionExecutor.executeTool`。

**设计**：
1. 恢复 `ToolExecutor` 类（`com.pijava.agent.tool`），含：
   - `List<Entry.Message> executeSequential(List<Action.ExecuteTool>, AbortSignal)` — 批量顺序执行 + 结果包装（原始删除逻辑）。
   - `List<Entry.Message> executeParallel(...)` — 抛 `UnsupportedOperationException`（Phase 3）。
2. `ActionExecutor` 构造时创建 `ToolExecutor`，`executeTool` 中的**原始执行 + 结果包装**委托给 ToolExecutor，**保留** before_tool/after_tool hook（Phase 2c 按单工具粒度触发）。

**架构张力说明**：规格 §7 的 ToolExecutor 是**批量**引擎（executeSequential 返回 `List<Entry.Message>` 已包装），而 Phase 2c 的 before_tool/after_tool hook 需要在**原始 `ToolResult`** 上操作（包装前）。二者粒度不同。本设计采取：

- ToolExecutor 提供**单工具原始执行**能力（`registry.execute` 的封装），供 ActionExecutor 的 hook 流程调用。
- `executeSequential`/`executeParallel` 批量接口按规格保留，供 Phase 3 并行路径使用。

具体：ToolExecutor 增加包内可见的 `executeRaw(String toolName, String toolCallId, Map args, AbortSignal)` 返回 `ToolResult<?>`，ActionExecutor 用它替换直接 `registry.execute` 调用；`executeSequential` 内部复用 `executeRaw`。

**影响文件**：新建 `ToolExecutor.java`、修改 `ActionExecutor.java`、`ExecutionContext.java`（注入 ToolExecutor）。

---

## 4. 验证

```bash
mvn test                                   # 全部模块，223+ 测试全绿
mvn checkstyle:check                       # 0 违规
mvn test -pl pi-java-agent-core -Dtest="*Test"
```

新增测试：
- ThinkingLevelMap 翻译（提供 map 时按 map 翻译；空 map 时返回 OFF）
- OverflowDetector 集成（overflow 触发压缩）
- ToolExecutor.executeSequential 批量执行 + executeParallel 抛异常

---

## 5. 影响范围总结

| 文件 | 操作 |
|------|------|
| `docs/07-phase2a-agent-loop-design.md` | 补充 run() 单轮语义结论 |
| `HarnessConfig.java` | 新增 thinkingLevelMap 字段 |
| `ExecutionContext.java` | 新增 thinkingLevelMap supplier + toolExecutor 引用 |
| `ActionExecutor.java` | 接线 ThinkingLevelMap + OverflowDetector + ToolExecutor |
| `ThinkingTranslator.java` | 删除 |
| `ToolExecutor.java` | 新建（恢复） |
| 测试文件 | 新增 3 项测试 |
