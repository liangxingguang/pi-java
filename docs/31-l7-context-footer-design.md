# L7 context footer 状态行（B293）设计与评分回填

> 【状态】已批准，实施完成（2026-11-24）。代码与文档回填均按本稿执行。
>
> 【背景】P12 收口时 L6 评测 #20（pi `tui.docs.eval.ts` 的 context-footer oracle）记为唯一存疑，
> 立 **B293**。此前的根因表述是「`SessionSnapshot` 无上下文窗口字段」，但该字段只是必要管道之一：
> 真正的缺口是 **TUI 内置 context footer 状态行未实现**（pi footer 显示 `42.2%/272k (auto)`，
> Java `StatusBar` 只显示 `⚡ N tokens`）。本批同时实现该状态行并修正 B293 的表述与评分读数。

## 1. pi 参照物（当前锚 `a00a5048d`）

| 位置 | 语义 |
|---|---|
| `packages/coding-agent/src/core/agent-session.ts:4416-4459` | `getContextUsage()`：无模型 ⇒ `undefined`；`contextWindow <= 0` ⇒ `undefined`；存在最新 compaction 时，只有「压缩点之后存在有效 assistant 用量」才可信，否则返回 `{tokens:null, contextWindow, percent:null}`；否则 `estimateProjectedContextTokens(...).tokens` 与 `percent = tokens / window * 100`。 |
| `packages/coding-agent/src/modes/interactive/components/footer.ts:25-31` | `formatTokens`：`<1000` 原样；`<10000` 一位小数 `k`；`<1e6` 四舍五入 `k`；`<1e7` 一位小数 `M`；否则四舍五入 `M`。 |
| 同上 `:160-212` | `contextWindow = contextUsage?.contextWindow ?? model?.contextWindow ?? 0`；`percent` 为 `null` ⇒ `?`，否则一位小数；`autoIndicator = autoCompactEnabled ? " (auto)" : ""`；`>90` error、`>70` warning；usage 缺席时按 `0.0`/`0` 显示。 |
| `packages/coding-agent/src/core/extensions/types.ts:327-333` | `ContextUsage { tokens: number \| null; contextWindow: number; percent: number \| null }`。 |
| `packages/evals/evals/tui.docs.eval.ts:15-16` | oracle：`/\d+(?:\.\d+)?%\/272k(?: \(auto\))?/`。 |

Java 已有可复用件：`ContextEntries.projectEntries/pathToLeaf`、`ContextUsageEstimator.estimateProjectedContextTokens/calculateContextTokens`、
`HarnessConfig.contextWindow()`（默认 `ignored -> maxInputTokens`）与 `CompactionSettings.enabled()`。

## 2. 裁决

| 编号 | 裁决 |
|---|---|
| D1 | 新增 `com.pijava.agent.context.ContextUsage`：`record ContextUsage(Double tokens, int contextWindow, Double percent)`，字段语义逐字对位 pi（`tokens`/`percent` 可空）。 |
| D2 | `SessionSnapshot` 追加两个字段：`ContextUsage contextUsage`（可空）与 `boolean autoCompact`。record 直接改 canonical 形状，同步更新 3 处构造点（`SnapshotService`＋2 个 TUI 测试），不加兼容重载。 |
| D3 | `SnapshotService` 按 pi `getContextUsage` 逐条计算：模型空/窗口≤0 ⇒ `null`；有最新 compaction 且压缩点后无有效 assistant 用量 ⇒ `tokens=null, percent=null`；否则投影估算＋百分比。窗口取自 `config.contextWindow()`；`autoCompact` 取自压缩设置晚读口（`settings != null && settings.enabled()`；resolver 未接 ⇒ false）。 |
| D4 | `StatusBar` 增加 context 段：`percent%/window` ＋ 可选 ` (auto)`；percent 为 null ⇒ `?/window`；usage 整体缺席 ⇒ `0.0%/0`（pi 的 `undefined` 分支行为）；`>70` warning、`>90` error 配色；现有 name/tokens/model 段保留。`formatTokens` 逐字移植。 |
| D5 | 不改 `TuiDocsEval` 的问答形态、不新增 `evals → tui` 依赖；#20 按「对齐（形状不同）」改判，D183 保留为登记窄差（eval 仍以文档问答替代代码修改任务），D189 不变。 |
| D6 | B293 表述改为「内置 context footer 状态行未实现；`SessionSnapshot` 缺 `contextWindow`/`percent` 只是必要管道之一」。销号后 L6 **49/49＝100%**，全仓 **1,369.5/1,389＝98.6%**（分母不变）。 |

## 3. 改动清单

| 文件 | 改动 |
|---|---|
| `pi-java-agent-core/.../agent/context/ContextUsage.java` | 新增 record（D1）。 |
| `pi-java-agent-core/.../agent/harness/SessionSnapshot.java` | 追加 `contextUsage`、`autoCompact`（D2）。 |
| `pi-java-agent-core/.../agent/harness/SnapshotService.java` | 构造参数增加 `ToIntFunction<ModelId<?>> contextWindow` 与 `Supplier<CompactionSettings> compactionSettings`；新增 `buildContextUsage()`，并写入会话快照（D3）。 |
| `pi-java-agent-core/.../agent/harness/HarnessBootstrap.java` | 将压缩设置晚读口初始化上移；构造 `SnapshotService` 时传 `config.contextWindow()` 与 autoCompact 读口（D3）。 |
| `pi-java-tui/.../component/StatusBar.java` | 增加 context 段、`formatTokens`、`?`/`0.0` 分支与 warning/error 配色（D4）。 |
| `pi-java-tui/src/test/.../component/StatusBarTest.java` | 新增：格式规则、`42.2%/272k (auto)`、`?/272k`、`0.0%/0`。 |
| `pi-java-agent-core/src/test/.../harness/SnapshotServiceContextUsageTest.java` | 新增：空会话、正常用量、压缩后无 assistant ⇒ null、压缩后有 assistant ⇒ 非 null。 |
| `pi-java-tui/src/test/.../TuiAssemblyTest.java`、`.../ChatScreenSessionEventTest.java` | `new SessionSnapshot(...)` 补两个参数。 |
| `docs/04-open-items-register.md` | B293 文案修正＋销号；§0 增加 L7 context footer 补记；P12 读数改 98.6%。 |
| `docs/map/06-session-evals-chord.md` | #20 改判「对齐（形状不同）」；对齐 24／存疑 0；Σ(w×c)=49.0／100%；同步汇总与 S 清单。 |
| `docs/05-module-alignment-map.md` | §1 L6 行 93/93.0/100%；合计 1,369.5/1,389＝98.6%；§1.2 历史注与 §2 现状同步。 |
| `docs/09-pi-latest-reanchor-plan.md` | §3 L6 行与合计行、现状速览、收口注记改 98.6%（历史段保留并加「后由 B293 改判」）。 |
| `docs/map/ANCHOR.md` | 发布读数 1,369.5/1,389＝98.6%。 |

## 4. 验证

1. `mvn test -pl pi-java-agent-core,pi-java-tui -am` 全绿（新增/更新测试通过）。
2. 全仓 `mvn clean verify "-Dfrontend.skip=true"` 零失败、零告警（含 SpotBugs/Checkstyle）。
3. 读数复算：L6 93 分母 → 93.0 分子；全仓 1,369.5/1,389＝98.596%≈98.6%。
4. 口径自检：`docs/05` §1、`docs/map/06`、`docs/09` §3、`docs/map/ANCHOR.md` 四处一致；`docs/04` §0/§4 无「仍缺字段」旧表述。

## 5. 不做项

- 不把 `TuiDocsEval` 重写成「让 agent 修改 footer 代码」的 eval（pi 原 eval 的任务形态）；不新增 `evals → tui` 依赖 → D183 保留。
- 不改 protocol/server 的 `SessionSnapshot`（同名但不同包，与本缺口无关）。
- 不实现 pi footer 的 pwd/git/usage/cost/thinking 整行（本批只补 context 段，其余为既有窄差）。
