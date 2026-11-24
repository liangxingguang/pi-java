# 03 Agent 运行时核心（pi-java-agent-core）

> 【背景摘要】范围：pi `packages/agent/src`（loop 本体 2,519 行）＋ `coding-agent/src/core/*` 主流副本 → `pi-java-agent-core`。
> 当前读数：**178/178＝100%**（88 计分单元全对齐；#77／#24 转出 L4、权重 0）。
> 差异结论：计分单元无缺失/存疑；差异仅在内置工具窄差 D1–D8，另有 23 条 R1／R5 作废（权重 0）与 2 条转出。开放项见 [docs/04](../04-open-items-register.md)。

## 口径

**权重**：权重 = 用户可观察影响 × 频率，不掺排期优先级。
`3` = 每轮对话都走 / 默认路径 · `2` = 每次会话走 / 常用命令 / 常用配置键 · `1` = 低频 / 边缘 / 纯内部 · `0` = 非目标（移出分母）。
**完成系数**：对齐 `1.0` ／ 存疑 `0.5` ／ 缺失 `0`。

### ★ 锚到两个 pi 面

pi 已删除 `packages/agent/src/harness/**`；其 compaction／messages／session／tools 在主流 `coding-agent/src/core/*` 有独立副本。本图锚到两个主流可达面：

| 面 | pi 路径 | 规模 | 承担 |
|---|---|---:|---|
| **agent loop 本体** | `packages/agent/src/{agent-loop,agent,types,proxy,stream-fn,index}.ts` | **2,519 行** | 循环、事件、工具两相、proxy、stream-fn |
| **core 主流副本** | `packages/coding-agent/src/core/*` | 见规模节 | `agent-session` / `session-manager` / `compaction` / `messages` / `system-prompt` / `skills` / `prompt-templates` / `tools` / `settings-manager` / `model-config` / `provider-composer` |

### R1 应用（删掉的参照物 ⇒ 单元作废，移出分母）

- **harness 专有、主流无副本**的行为一律作废（见「作废清单」23 条）。判据是「和 pi 表现一样」：行为在 pi 主流已不存在（删或只活在扩展事件里）⇒ 无从对齐，移出分母。
- `packages/durable`（61 文件 / 17,716 行）与 `pico3` 按 **R5 不可达**（import 者全在 `coding-agent/src/experimental/**`）⇒ 权重 0。

### 判定三档

**对齐** / **缺失** / **存疑**（`（形状）`/`（旧 pi）`/`（扩展）`/`（台账）`）。

---
## 规模

| 模块 | pi 包 | pi LOC | java LOC | 比例 |
|---|---|---:|---:|---:|
| agent loop 本体 | `packages/agent/src` | **2,519**（6 文件） | —— | —— |
| core 主流副本 | `packages/coding-agent/src/core` | —— | `pi-java-agent-core/src/main` **19,795**（185 文件） | —— |
| 测试 | `packages/agent/test` | **4,058**（7 文件） | `src/test` **19,469**（119 文件） | —— |

**agent loop 本体逐文件**：

| 文件 | 行数 |
|---|---:|
| `agent/src/agent-loop.ts` | 949 |
| `agent/src/agent.ts` | 613 |
| `agent/src/types.ts` | 539 |
| `agent/src/proxy.ts` | 393 |
| `agent/src/stream-fn.ts` | 20 |
| `agent/src/index.ts` | 5 |
| **合计** | **2,519** |

**core 主流副本关键文件**：`agent-session.ts` **4,398** · `session-manager.ts` 2,013 ·
`settings-manager.ts` 1,428 · `compaction/compaction.ts` 1,119 · `provider-composer.ts` 732 ·
`skills.ts` 514 · `tools/bash.ts` 442 · `system-prompt.ts` 229 · `prompt-templates.ts` 320 · `messages.ts` 196。

> pi 测试 4,058 行含 `test/utils/` 3 个支持文件；仅 4 个 `*.test.ts` 为 3,969 行。

---

## 内置工具对齐

pi 工具定义现在**只有一处**：`packages/coding-agent/src/core/tools/`（`harness/tools/` 已删）。
`allToolNames`（8 个）在 `core/tools/index.ts:96`。pi-java 的 8 个工具在
`pi-java-agent-core/.../tool/builtin/`，由 `ToolSetFactory.java` 组装（`createAllTools` 8 件／`createCodingTools` 7 件，起）。

已对齐 9 条（逐条证据见 git 历史）。

**Σ权重 21**（对齐 21.0 ／ 存疑 0 ／ 缺失 0 ＝ 21.0，**100%**）。

> **窄差登记**（触发重裁条件随各条）：
> - **D1** grep 正则方言：Java `Pattern` vs Rust regex——lookaround/反向引用 java 多出面；字面/字符类/量词主流一致（触发：模型产出 Rust 方言失配 pattern 的案例）
> - **D2** gitignore 三件全读（grep 侧 rg 不读 `.fdignore`）；嵌套 git 仓库边界不停（pi `--no-require-git` 取舍 `find.ts:171-181`）；**搜索根之上的父 .gitignore 不读**（rg 上溯仓库根）（触发：嵌套仓库/父规则场景的可见误报）
> - **D3** 二进制 NUL 嗅探（首 8 KiB 含 0x00 即跳过）近似 rg 默认；**grep 整文件入内存**（rg 流式，超大文本文件内存画像有别）（触发：二进制误中/超大文件案例）
> - **D4** ls 排序：`toLowerCase(ROOT)` 码点序 vs pi `localeCompare`（ASCII 全等）（触发：非 ASCII 文件名排序差异被踩）
> - **D5** find 绝对 glob（`/` 开头）的 Windows 分隔符不逐字 `[/\\]`；path 不存在错文采 custom-ops 形；**普通文件作 find 根 ⇒ 报错**（fd 会把该文件自身列入候选）。P5 已缩窄：`\`→`/` 归一、剥 `./`、相对模式锚定 fd 语义均落地（触发：Windows 绝对 pattern/文件根实测差异）
> - **D6** bash `Command terminated without an exit code`（`bash.ts:354`）java 不可达（`ShellResult.exitCode` 恒 int）（结构性）
> - **D7** fd/rg 遍历序 vs `Files.walkFileTree` 序——两侧都不保证顺序
> - **D8** find/grep 的 minimatch 扩展方言（`+(a|b)`、嵌套花括号等）不逐字；主流 glob（`**/` 零段、smart case、字符类、支选）已由共享 `GlobPattern` 对齐（P5）（触发：模型使用扩展 glob 语法的实测差异）

---

## 能力单元清单（除工具）

类别：**L**=agent loop · **H**=钩子 · **E**=事件 · **N**=entry/消息 · **P**=prompt/模板 · **C**=上下文管理 · **S**=存储/会话 · **A**=API 面。
pi 侧证据锚定 `agent/src/*` 或 `coding-agent/src/core/*`。

已对齐 79 条（逐条证据见 git 历史）。

| # | 能力单元 | 类别 | 判定 | 权重 | pi 侧证据 | pi-java 侧证据 | 台账 |
|---|---|---|---|---|---|---|---|
| 24 | **branch summary 生成** | C | **转出（L4）** | 0 | 新锚 `core/compaction/branch-summarization.ts:302-360`：摘要请求携带 `thinkingLevel`（`269afc66a`）。按 docs/08 §5 归 L4，登记 **B272** | `core/compaction/branch-summarization.ts:108,195`；`BranchSummarization.java`：公共祖先回溯、两遍扫、三态 Outcome、PREAMBLE/PROMPT 逐字 | 转出后权重 0，不进分母 |
| 30 | ~~钩子 `before_run`~~ | H | **作废（R1）** | 0 | `harness/agent-harness.ts` 已删 | `HookSystem.java:43,112` | 见作废清单 |
| 31 | ~~钩子 `before_drive`~~ | H | **作废（R1）** | 0 | drive 运行时已删 | 零命中 | —— |
| 35 | ~~钩子 `before_payload`~~ | H | **作废（R1）** | 0 | 载荷改写改在扩展事件 `before_provider_request`（`core/extensions/types.ts:888`）→ 属 04 | `HookSystem.java:63,134` | —— |
| 36 | ~~钩子 `after_response`~~ | H | **作废（R1）** | 0 | 扩展事件 `after_provider_response`（`core/extensions/types.ts:904`）→ 属 04 | `HookSystem.java:68,148` | —— |
| 39 | ~~钩子 `before_compaction`~~ | H | **作废（R1）** | 0 | 扩展事件 `session_before_compact`（`core/extensions/types.ts:772`）→ 属 04 | `HookSystem.java:83,221` | —— |
| 40 | ~~钩子 `before_navigation`~~ | H | **作废（R1）** | 0 | 导航 hook 已删（导航本体见 #25） | 死钩三件套（`onBeforeNavigation`/`fireBeforeNavigation`＋`BeforeNavigationHook`/`NavigationContext`）已删除（裁决①：pi 无引擎层 tree 钩，事件由 agent-session 直发） | —— |
| 42 | ~~钩子 `before_resume`（java 独有）~~ | H | **作废（R1）** | 0 | pi 无对应（旧图即标 java 多） | `HookSystem.java:48,118` | —— |
| 43 | ~~effect gate（`gate.admit`）~~ | A | **作废（R1）** | 0 | `git grep effect-gate` **零命中** | 零命中 | —— |
| 44 | ~~HarnessEvent 28 类~~ | E | **作废（R1）** | 0 | `lane_created`/`run_suspend`/`value_update` **零命中** | `HarnessEventBus.java` | A6 |
| 45 | ~~`run_suspend` + `DeferredHandle` 挂起驱动~~ | L | **作废（R1）** | 0 | `run_suspend` 零命中 | 零生产者 | —— |
| 59 | ~~`LaneConfiguration`~~ | N | **作废（R1）** | 0 | `harness/session/types.ts` 已删 | entry 化 | #54 同根 |
| 60 | ~~`PendingEntry` / `InboxItem` ⇒ 队列契约~~ | N | **作废（R1）** | 0 | 现锚点全仓零命中——Pico 实验 harness 类型，随 pi `7fd478a2e`（remove the experimental harness）整包删除 | `QueueManager.java`（队列契约以 pi `PendingMessageQueue` 为参照，#14 已核齐） | 见作废清单 |
| 66 | ~~`AdaptivePublisher`~~ | A | **作废（R1）** | 0 | `harness/utils/adaptive-publisher.ts` 已删 | 无 | —— |
| 67 | ~~`applyShellOutputUpdate` / `ShellOutputView`~~ | A | **作废（R1）** | 0 | `harness/utils/output-capture.ts`/`shell-output.ts` 已删（主流量见 #28） | `ShellOutputSink.java` | —— |
| 68 | ~~`streamProxy`~~ | A | **作废（R5）** | 0 | `agent/src/proxy.ts:107-111`（存活但 **bin pi 主流不可达**：coding-agent 全包零 import，引用点仅 `index.ts` 再导出/单测/构建冒烟 typeof 断言） | 无 | 见作废清单（裁决①） |
| 70 | ~~`SessionSearchService`（6 方法）~~ | A | **作废（R1）** | 0 | `agent/src/search/` 已删；`SessionSearch` 无主流命中 | `SessionSearch.java` | —— |
| 71 | ~~`reduceLaneSnapshot`~~ | A | **作废（R1）** | 0 | `harness/runtime/reducer.ts` 已删 | 无 | —— |
| 72 | ~~错误面 15 个 `TaggedError`~~ | A | **作废（R1）** | 0 | `TaggedError` 零命中 | 4 个异常类 | —— |
| 74 | ~~存储 conformance / benchmark 套件~~ | S | **作废（R1）** | 0 | `harness/session/testing/*` 已删 | agent-core 无 | A17 |
| 77 | 会话恢复（resume seed + record replay） | S | **转出（L4）** | 0 | 新锚 `core/agent-session.ts:538,1846-1882`：`usesDefaultTools` 门；从分支**全部** system 消息恢复 `toolsAdded`/`toolsRemoved`、补晚注册扩展工具、应用 `+name/-name` 修饰（`6f2f164ab`／`bd3c9b823`）。按 docs/08 §5 归 L4，登记 **B271** | `core/agent-session.ts:525,1809,4138`；`RunLifecycle.java:284,330`＋`restoreToolsFromTranscript` | 转出后权重 0，不进分母 |
| 86 | ~~`_emitSessionCompactFailed`~~ | E | **作废（R1）** | 0 | `core/agent-session.ts:1053-1057`：**只**向扩展 runner 发 `session_compact_failed`（`core/extensions/types.ts:796`），无公开会话事件 ⇒ 属 04；失败路公开事件是 `compaction_end{errorMessage}`（见 #51，java 已齐） | 注释同裁决（`PostRunCompactionCheck.java:168`，`CompactionExecutor.java:189`） | 见作废清单 |
| 88 | ~~`ActiveToolsChange` 发射~~ | E | **作废（R1）** | 0 | `git grep active_tools_change` **零命中**—— pi 主流从不发射 | 不发射；裁决已落 `Entry.java:23-24`（R2：变体删除） | 见作废清单 |
| 103 | ~~`experimental/pico3` kernel~~ | S | **作废（R5）** | 0 | 已随 harness 删除 | 无 | —— |
| 104 | ~~`packages/durable`~~ | S | **作废（R5）** | 0 | 61 文件但 import 者全在 `experimental/` | 无 | —— |

> **不在本模块范围的台账条目**：B14/B17/B18/B19/B21/B23/B24（`pi-java-ai`）、
> B28/B29/B31/B32/B33/B48/B50/B51（RPC 线格式）、B38/B39/B47（TUI）、B52/A13/A16（已结案）、B54（web 路径）。

---

## 作废清单（R1／R5 —— 权重 0，移出分母）

**23 条能力单元**（旧编号保留，便于对照）：#30 `before_run` · #31 `before_drive` · #35 `before_payload` ·
#36 `after_response` · #39 `before_compaction` · #40 `before_navigation` · #42 `before_resume` ·
#43 effect gate · #44 HarnessEvent · #45 `run_suspend` · #59 `LaneConfiguration` ·
**#60 `PendingEntry`/`InboxItem`（作废，R1：Pico 实验 harness 类型，随 pi 整包删除）** ·
#66 `AdaptivePublisher` ·
#67 `output-capture`/`shell-output` · **#68 `streamProxy`（作废，R5：pi 侧存活但 bin pi 主流不可达——coding-agent 全包零 import，仅 `index.ts` 再导出/单测/构建冒烟 typeof 断言；裁决①，触发条件：服务端托管鉴权代理车道或 pi 主流接入）** · #70 `SessionSearchService` · #71 `reduceLaneSnapshot` · #72 `TaggedError` ·
#74 存储 conformance 套件 · **#86 `_emitSessionCompactFailed`（本锚新作废：仅扩展事件，归 04）** ·
**#88 `ActiveToolsChange`（本锚新作废：pi 两锚全树零发射）** · #103 pico3 · #104 durable。

**一条整块缺失**：① drive 运行时（`harness/runtime/drive/` 12 文件）—— 参照物被删。

> `before_payload`/`after_response`/`before_compaction`/`session_compact_failed` 的**替身是扩展事件**
> （`before_provider_request`/`after_provider_response`/`session_before_compact`/`session_compact_failed`），
> 属 `04-coding-agent` 的域。

---

## 整块缺失

**无独立计权项**：原整块缺失项已按 R1／R5 作废，或拆为能力单元逐条计权（#24 转出 L4／B272，#25、#68、#61/#62、#65）。

---

## 汇总（未加权）

## 读数

- 未加权：**88/88＝100%**（#77／#24 权重 0 转出，不进分母）。
- 加权：**178/178＝100%**（发布读数，与 `docs/05` §1 一致）。

## 口径备注（可被推翻）

- **钩子作废只作废「harness 专有」的**：凡行为在 `agent/src/*` 存活（`beforeToolCall`/`afterToolCall`/
  `transformContext`/`prepareNextTurn`/`finishTurn`/`getFollowUpMessages`）的一律改指保留；
  harness 有、主流只在**扩展事件**里重生的作废并注记去向 04。
- **#73 的对齐强度**：双向可读由合成夹具（`PiV3WireFormatTest` 等）保证，未拿真实 pi 进程跑往返；
  lane/record 方言行以 pi `custom` 条目挂载并链入树。
