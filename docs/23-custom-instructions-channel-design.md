# 包 23：压缩 customInstructions 通道接线（B174 销号）

> **状态：✅ 已闭环（2026-10-06，R-A）—— B174 销号。commits `f62ed75` / `2d7f8ca`。**

## 1. 问题

B174（包 21 登记）：包 21 让 `customInstructions` 能被摘要 prompt 正确消费
（`Additional focus:` 后缀），但 pi-java 没有任何生产者 ——
`CompactionService.compact` 在两处 `summarize` 调用硬编码 null
（`CompactionService.java:151,166`）。pi 有两个生产入口，都要接线：

1. **RPC**：`compact` 命令带可选 `customInstructions`，且响应返回压缩结果；
2. **交互模式**：`/compact <text>` 把 trailing 文本作为 customInstructions。

## 2. pi 锚点事实（`200387122`，`git show` 取证）

### 2.1 RPC 命令形状：`src/modes/rpc/rpc-types.ts:47`

```ts
| { id?: string; type: "compact"; customInstructions?: string }
```

### 2.2 RPC 动作：`src/modes/rpc/rpc-mode.ts:533-536`

```ts
case "compact": {
	const result = await session.compact(command.customInstructions);
	return success(id, "compact", result);
}
```

### 2.3 结果形状：`src/core/compaction/compaction.ts:926-931`

```ts
return {
	summary,
	firstKeptEntryId,
	tokensBefore,
	usage: summaryUsage,
	details: { readFiles, modifiedFiles } as CompactionDetails,
};
```

**5 个键**，无 estimatedTokensAfter（pi 侧该量只用于内部判断）。

### 2.4 交互 `/compact <text>`：`src/modes/interactive/interactive-mode.ts:3263-3267`

```ts
if (text === "/compact" || text.startsWith("/compact ")) {
	const customInstructions = text.startsWith("/compact ") ? text.slice(9).trim() : undefined;
	this.editor.setText("");
	await this.handleCompactCommand(customInstructions);
	return;
}
```

要点：无 trailing ⇒ undefined；trim 后空串 ⇒ `""`，pi prompt 门
（`if (customInstructions)`）对空串为假，不产生后缀。

## 3. pi-java 现状（逐处核实）

调用链（全为透传，无一消费 customInstructions）：

```
RpcDispatcher:165  session.compact(settings)，响应无载荷
MiscCommands:127   ctx.session().compact(settings)
  → AgentSession.compact:663 (void)
    → AgentHarness.compact:379 (void)
      → RunLifecycle.compact:259
        → CompactionExecutor.compact:80
          → applyCompaction:256 → compactTranscript:407
            → CompactionService.compact:132（customInstructions 硬编码 null :151,166）
```

- `RpcCommand.Compact`（`RpcCommand.java:139-142`）只有 `id`；
- `CompactionResult`（agent-core）有第 4 个内部字段 `estimatedTokensAfter`，直接上线
  会比 pi 多一个键；
- 自动压缩路径在 pi 恒无 customInstructions（`agent-session.ts:3082` 明文
  undefined）⇒ 该路径继续传 null，不进本包面。

## 4. 方案（R-A：全链接通，返回 pi 5 键 wire）

### 4.1 agent-core：CompactionService 消费参数

`compact(...)` 加形参 `String customInstructions`，`:151` 与 `:166` 两处
`summarize(..., null, ...)` 的 null 改为该形参。其余不动。

### 4.2 agent-core：执行链透传 ＋ 返回值

- `compactTranscript(...)`、`applyCompaction(...)` 加 `customInstructions` 形参
  （`CompactionService.compact` 调用点传入）；
- `CompactionExecutor.compact(laneName, settings, customInstructions)` 返回值由
  void 改 **`CompactionResult`**（成功 ⇒ `run.result()`；`run.aborted()` ⇒
  抛 "Compaction cancelled" 路径不变，无返回值）；
- `runAutoCompaction` 调 `applyCompaction` 处传 **null**（自动路）；
- `RunLifecycle.compact(...)`、`AgentHarness.compact(...)` 透传并把返回值改为
  `CompactionResult`。旧无参/两参重载保留（传 null＝旧行为）。

### 4.3 coding-agent：AgentSession ＋ RPC

`AgentSession.compact(settings, customInstructions)` 返回 `CompactionResult`。

`RpcCommand`：

```java
@JsonTypeName("compact")
record Compact(String id, String customInstructions) implements RpcCommand { ... }
```

（缺键 ⇒ null ≙ pi 的 `undefined`。）

Dispatcher：

```java
case RpcCommand.Compact c -> {
    var result = session.compact(CompactionSettings.defaults(), c.customInstructions());
    out.write(RpcResponse.ok(c.id(), "compact", CompactResultWire.of(result)));
}
```

新增 wire DTO（pi §2.3 逐字 5 键）：

```java
/** pi compact 响应载荷（compaction.ts:926-931）：5 键，无 estimatedTokensAfter。 */
record CompactResultWire(
    String summary, String firstKeptEntryId, long tokensBefore,
    com.pijava.ai.Usage usage, Map<String, Object> details) {
    static CompactResultWire of(CompactionResult r) {
        return new CompactResultWire(r.summary(), r.firstKeptEntryId(),
            r.tokensBefore(), r.usage(), r.details());
    }
}
```

### 4.4 slash：trailing 文本

`MiscCommands` 的 `/compact`：`simple(..., "compact", ..., "<text>"?, ...)` ——
现有 `simple` 形态已把 args 文本传进 lambda（参数 `args`，当前未用）；直接
`ctx.session().compact(CompactionSettings.defaults(), args.isBlank() ? null : args.trim())`，
与 pi `slice+trim` 同形。返回文案不变。

行为面：只有 customInstructions 非空时摘要 prompt 末尾多 `Additional focus:`，
以及 RPC compact 响应多了结果载荷；其余压缩语义零改动。

## 5. 实施步骤（严格 RED-first）

| # | 内容 | 预期 |
|---|---|---|
| 1 | 新增 3 组测试（见下） | 先红 |
| 2 | 落 §4 生产改动 | 转绿；存量套件连带绿 |
| 3 | 变异探针 §5.1 → 全部还原 | 有牙 |
| 4 | `mvn -o clean verify`（全 14 模块） | 零错误 |

测试：

1. **agent-core `CompactionCustomInstructionsTest`**（recording SummaryGenerator，
   断言 `summarize` 收到的 customInstructions）：
   - 普通路：`compact(..., "Focus on auth")` ⇒ 收到 "Focus on auth"；
   - split-turn 路：历史 `summarize` 同样收到（pi 两路都透传，compaction.ts:1005）。
   RED：收到 null。
2. **coding-agent RPC**（dispatcher handleLine，断言 response 行 JSON）：
   - `{"id":"1","type":"compact","customInstructions":"Focus on auth"}` ⇒ data
     恰含 5 个键 `{summary, firstKeptEntryId, tokensBefore, usage, details}`，
     无 estimatedTokensAfter；录制到的摘要请求 prompt 以
     `Additional focus: Focus on auth` 结尾；
   - 不带 customInstructions ⇒ 同样成功、有 data。
3. **slash**：`/compact Focus on tests` ⇒ recording generator 收到
   "Focus on tests"；`/compact` ⇒ null；`/compact   ` ⇒ null（trim）。

### 5.1 变异探针（红数以实测为准）

| 探针 | 变异 | 预期红灯 |
|---|---|---|
| M1 | CompactionService 两处改回 null | ①×2＋②＋③＝5 |
| M2 | dispatcher 丢弃 `c.customInstructions()` | ②（focus 断言） |
| M3 | slash 忽略 args（恒传 null） | ③ |
| M4 | wire 直传 CompactionResult（多 estimatedTokensAfter 键） | ②（键断言） |

## 6. 明确不做

- **自动压缩路不加 customInstructions**：pi 恒 undefined；
- **扩展通道**（`session_before_compact` / extension compact API）：随扩展包另立，
  本包只做 RPC＋交互两条主流路径；
- 不动摘要 prompt/maxTokens/思考级别门/缓存门/重试环/落盘合并。

## 7. 收口清单

- docs/23 banner/§8 回填；docs/05：B174 行关闭（未结 −1）；CLAUDE.md 文档表
  加 docs/23；memory 更新；全 reactor 验证后汇报。

## 8. 闭环记录（2026-10-06）

**裁决：R-A 全链接通 customInstructions，B174 销号。**

RED 形态：夹具先以**编译失败/行为缺失**落地 —— agent-core 新签名不存在（编译红），
slash/RPC 通道先红于行为断言；生产落地后全绿。

探针实测（4 颗；M1 与预案不符，如实记）：

- **M1 = 4 红**（预案 5）：CompactionService 两处 summarize 改回 null ⇒
  CompactionCustomInstructionsTest 2 ＋ RPC focus 断言 1 ＋ slash focus 断言 1；
  split 负向断言与两条 slash 负向断言本就绿，预案高估 1；
- **M2 = 1 红**：dispatcher 丢弃 `c.customInstructions()`，恰 RPC focus 用例；
- **M3 = 1 红**：slash 忽略 args，恰 trailing 用例；
- **M4 = 1 红**：wire 直传内部 CompactionResult（多 estimatedTokensAfter 键），
  恰 5 键用例。

实施插曲两处：① 调试 slash 夹具时发现三轮才足够（Usage 条目回吸让两轮 transcript
的 split 历史为空 —— pi `compaction.ts:858-862` 同形，已核实是夹具问题非移植
问题）；② RPC focus 断言最初没有观测点，抽出共享测试零件
`RecordingChatProvider`（`support` 包，slash/RPC 共用）。

教训沿用：**预案红数按每条断言实际读到的字段与正负向计数**；以及新通道要有
真观测点，不能只断言响应外壳。

### 证据

- 新增 `CompactionCustomInstructionsTest` 2 条、`CompactSlashCommandTest` 3 条，
  RPC compact 夹具 2 条（含 focus prompt 与 5 键载荷）；
- compaction 存量套件与全模块测试连带绿；
- 全 reactor `mvn -o clean verify` **14/14 SUCCESS**（2026-10-06）。

### 提交

- `f62ed75` feat(agent-core): wire customInstructions through RPC and slash compaction (B174)
- `2d7f8ca` test(coding-agent): share recording provider for compaction focus fixtures

压缩/省略域 B2 家族（包 13–23）至此全部销号。
