# 03 Agent 运行时核心（pi-java-agent-core）

> 【背景摘要】范围：pi `packages/agent/src` 的 loop 本体＋`packages/coding-agent/src/core/*` 主流副本 → `pi-java-agent-core`。
> 读数：**178/178＝100%**（88 个计分单元全对齐；#77／#24 权重 0 转出 L4）。
> 差异：只剩内置工具窄差 D1–D8；另有 23 条 R1／R5 作废（权重 0，不进分母）。

## 口径

**权重**：权重 = 用户可观察影响 × 频率，不掺排期优先级。
`3` = 每轮对话都走 / 默认路径 · `2` = 每次会话走 / 常用命令 / 常用配置键 · `1` = 低频 / 边缘 / 纯内部 · `0` = 非目标（移出分母）。
**完成系数**：对齐 `1.0` ／ 部分对齐（形状差） `0.5` ／ 缺失 `0`。

## 工具窄差（唯一在册差异）

pi 的工具定义只有一处：`coding-agent/src/core/tools/`（`harness/tools/` 已删），`allToolNames` 8 个。pi-java 的 8 个工具由 `ToolSetFactory` 组装。

| 编号 | 窄差 |
|---|---|
| D1 | grep 正则方言：Java `Pattern` 比 Rust regex 多 lookaround／反向引用；字面、字符类、量词主流一致 |
| D2 | gitignore／`.fdignore`：grep 侧不读 `.fdignore`；嵌套 git 仓库边界不停；搜索根之上的父 `.gitignore` 不读 |
| D3 | 二进制 NUL 嗅探（首 8 KiB）近似 rg 默认；grep 整文件入内存（rg 流式） |
| D4 | ls 排序：`toLowerCase(ROOT)` 码点序 vs `localeCompare`（ASCII 全等） |
| D5 | find 绝对 glob（`/` 开头）的 Windows 分隔符不逐字 `[/\\]`；普通文件作 find 根报错（fd 会列入自身）；`\`→`/`、剥 `./`、相对模式锚定已对齐 |
| D6 | bash `Command terminated without an exit code` 不可达（`ShellResult.exitCode` 恒 int，结构性） |
| D7 | fd／rg 遍历序 vs `Files.walkFileTree` 序：两侧都不保证顺序 |
| D8 | find／grep 的 minimatch 扩展方言（`+(a\|b)`、嵌套花括号）不逐字；主流 glob（`**/` 零段、smart case、字符类、支选）已由共享 `GlobPattern` 对齐 |

## 分母外单元

判据：harness 专有行为在 pi 侧已删除或只活在扩展事件里（R1）；`packages/durable` 与 `pico3` 的 import 者全在 `coding-agent/src/experimental/**`（R5）。

- **作废 23 条（权重 0）**：`before_run`、`before_drive`、`before_payload`、`after_response`、`before_compaction`、`before_navigation`、`before_resume`、effect gate、HarnessEvent、`run_suspend`／`DeferredHandle`、`LaneConfiguration`、`PendingEntry`／`InboxItem`、`AdaptivePublisher`、`applyShellOutputUpdate`／`ShellOutputView`、`streamProxy`、`SessionSearchService`、`reduceLaneSnapshot`、`TaggedError`、存储 conformance 套件、`_emitSessionCompactFailed`、`ActiveToolsChange`、`pico3`、`durable`。
- **转出 2 条（权重 0，计入 L4）**：会话恢复（#77）、branch summary 生成（#24）。

> 钩子类作废只针对 harness 专有行为；`agent/src/*` 存活的 `beforeToolCall`／`afterToolCall`／`transformContext`／`prepareNextTurn`／`finishTurn`／`getFollowUpMessages` 一律保留。`before_payload`／`after_response`／`before_compaction`／`session_compact_failed` 的替身是扩展事件，属 coding-agent 域。

## 读数

- 未加权：**88/88＝100%**。
- 加权：**178/178＝100%**（发布读数，与 `docs/05` §1 一致）。
