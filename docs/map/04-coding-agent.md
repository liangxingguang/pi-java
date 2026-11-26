# 04 CLI 宿主 / 产品层（pi-java-coding-agent）

> 【背景摘要】范围：pi `packages/coding-agent` ↔ `pi-java-coding-agent`（CLI 参数、子命令、slash 命令、扩展系统、settings 键、会话管理、包管理器、RPC）。
> 读数：**458.0/458＝100%**（settings 四条已清：E2-32／E2-54 对齐、E2-43 重判对齐、E2-50 按 R5 出分母）。
> 差异：在册 D 窄差（新增 D322）＋D233/D234/D274/D275/D277/D278；转出 3 条（E3-3／E5-31／E5-33）；面外 G-6/G-7（R5）、N-4（R2）；缺失 E8-4／E8-6（不计入本层）。

## 口径

- 判定三档：**对齐**（两侧都有且行为相同）/ **缺失**（pi 有、pi-java 无）/ **存疑**（形状存在但语义/可达性有差）。
- **权重**：用户可观察影响 × 频率，不含排期优先级。`3`＝每轮主路；`2`＝每次会话或常用；`1`＝低频/内部；`0`＝非目标（出分母）。完成系数：对齐 1、存疑 0.5、缺失 0。
- **R5 可达性**：只算 `bin pi` 主流可达的；`coding-agent/src/experimental/**` 权重 0。
- **R1 作废**：`src/experimental/micro/**` 与 `.../mini/**` 已被 pi 删除，相关单元权重 0、出分母。
- **MCP 面**由 [`07-mcp.md`](07-mcp.md) 覆盖；本图只保留判定指针，不重复计分。

## 读数

| 域 | 单元 | Σ权重 | Σ(w×c) | 完成度 |
|---|---:|---:|---:|---:|
| A CLI 参数 | 43 | 62 | 62.0 | 100% |
| B 子命令 | 8 | 15 | 15.0 | 100% |
| C slash 命令 | 25 | 38 | 38.0 | 100% |
| D 扩展事件 | 41 | 83 | 83.0 | 100% |
| E1 配置/清单 | 5 | 10 | 10.0 | 100% |
| E2 settings 键 | 58（56 对齐／2 出分母） | 84 | 84.0 | 100% |
| E3 会话管理 | 24（23 对齐／1 转出） | 38 | 38.0 | 100% |
| E4 资源发现 | 7 | 14 | 14.0 | 100% |
| E5 扩展 API | 35（33 对齐／2 转出） | 53 | 53.0 | 100% |
| E6 包管理器 | 18 | 34 | 34.0 | 100% |
| E7 RPC | 7 | 13 | 13.0 | 100% |
| G 新增子系统 | 10（8 对齐／2 面外） | 5 | 5.0 | 100% |
| N 新单元 | 7（6 计分／llama 出分母） | 9 | 9.0 | 100% |
| **合计** | — | **458** | **458.0** | **100%** |

## D 窄差（在册）

| # | 内容 | 等级 |
|---|---|---|
| D1 | `context` 快照用引用相等（pi `===` 同构）；java 无 `structuredClone`（Message 不可变） | 无影响 |
| D2 | `tool_call`/`tool_result` 不做 pi 的逐工具判别子类型（9 支），`input` 恒 map 形 | [内部] |
| D3 | `message_update` 的 `message` 提取自 StreamEvent partial；无快照帧不发射 | 边缘 |
| D9 | `message_end` 观察处理器在 sink 时点与结果处理器同批火一次；替换对宿主/TUI/RPC 的效果与 pi 原地改写一致 | [内部] |
| D11 | `before_agent_start` 的 `systemPromptOptions` 不移植；`systemPrompt` 覆盖是 harness 全局态（粘性），非 pi 的 per-prompt options | [模型可见：覆盖持续生效] |
| D12 | `input` 事件 `images` 恒 null；扩展来源消息以 `source="extension"` 进入 prompt | [内部，边缘] |
| D14 | `previousSessionFile`/`targetSessionFile` 载荷装会话 **id**（仓层无文件路径面） | [内部] |
| D15 | boundary 预览的 `contextMessages`/`llmMessages` 同源（无 AgentMessage→LLM 二次转换缝） | [内部] |
| D16 | `session_before_fork` 的 `skipConversationRestore` 结果臂收下不消费 | [内部] |
| D17 | `forkCopy` 映射 `before_fork{entryId:当前叶, position:"at"}`；pi 恒显式 entryId | [内部] |
| D18 | pi 的 emitUserBash 模式间不对称；java 单发射点（executeBash），异常沿调用方错误链 | [内部] |
| D20 | `/new` 臂的 shutdown 在 start 之后（java 需先建会话才知目标 id） | [内部] |
| D21 | boundary 预览 `pendingMessages` 由队列文本重建 UserMessage（custom 队列项不进预览） | [内部] |
| D22 | `session_before_compact` 载荷 `preparation.fileOps` 不译（compact() 内才收集） | [内部] |
| D23 | 原地 resume 的 shutdown/start 在同一条 bus 上发射（pi 为旧/新两条 runner） | [内部] |
| D24 | fork 携带当前 model/thinkingLevel（pi 经 persist→settings 重解析，效果同）；systemPrompt/activeTools/skills 重解析＋扩展 `start{fork}` 重激活 | [内部] |
| D25 | 内存路 `switchTo` 旧会话对象不关闭（内存注册表的会话对象即存储本体） | [内部] |
| D26 | 内存 CLI `--fork` 经 `forkCopy` ⇒ 发射 `start{fork}`（pi CLI fork 恒 `startup`） | [内部，测试路] |
| D27 | 内存 CLI `--fork` 的初始会话空转 `start{startup}` 后被弃置不 close | [内部，测试路] |
| D28 | 内存 fork/clone 后旧会话已关但仍列在注册表（`/resume` 可选到 closed 会话） | [内部，边缘] |
| D31 | 替换 payload 类型不符 ⇒ 车道抛 `IllegalStateException`；pi 会把垃圾形状发给 provider 收 400——同属请求失败，失败点提前 | [内部，边缘] |
| D32 | google 车道 payload 载体是 `GooglePayload(model, contents, config)` record（pi 是单一 `GenerateContentParameters`） | [内部] |
| D33 | `before_provider_headers` 头集＝models.json 三层（provider⨁model⨁override）合并值；pi 另有 `auth.headers` 与调用方 `options.headers` 两层（java 无生产者）；attribution 已并入钩前头集 | [内部] |
| D34 | null 抑制 **SDK 默认头**不可达（通道只作用于 extra["headers"] 合并集）；合并集内 null＝删除 | [内部，边缘] |
| D322 | custom 队列项的 LLM 投影是 `UserMessage` ⇒ `message_end` 扩展事件里 role 为 `user`（pi 为 `custom`）；条目、落盘序与上下文不受影响 | [内部，边缘] |

**其他窄差**：D233（无 `findOfType`/`classify`）、D234（无 `newSession.setup`）、D274（重复信号强退）、D275（`/export` OSC 8 不转 `<a>`）、D277（会话内 cwd 缺失只走非交互错误臂）、D278（capture 输出无 TUI 安装触发点）。

**非窄差说明**：google 车道不发 `after_provider_response` 与 pi 同缺；SDK 车道只在成功获取后发、fetch 车道含错误状态——pi 自身两族口径即如此。

## E2 settings 处置（4 条已清）

`enableInstallTelemetry` 字段、访问器与 provider 归因头均已对齐；pi 的安装 ping 发往 `pi.dev` 自有端点，复刻会污染其遥测 ⇒ 有意不移植，不扣分。

| # | 键 | 处置 | 依据 |
|---|---|---|---|
| E2-32 | `terminal` | **对齐** | 设置侧能力覆盖（hyperlinks/images/trueColor）叠进 `TerminalCapabilities`；OSC 9;4 进度接 turn/agent/compaction 事件（docs/12）。`clearOnShrink` 两臂在 pi 终态均逐行清空缩短行，Java `InlineDisplay.deleteLines` 同终态，设置只影响重绘策略 ⇒ 无行为差。 |
| E2-43 | `markdown` | **对齐（重判）** | `codeBlockIndent` 已消费；mermaid 渲染缺口已由 L7 R7／D309 单计，按「整块与细单元不重复扣分」不在 settings 键重复扣分。 |
| E2-50 | `websocketConnectTimeoutMs` | **出分母（R5/R2）** | 唯一消费链是 codex WebSocket 车道（`openai-codex-responses`），本仓无该车道；与 E2-45 codemode 同口径 weight 0。 |
| E2-54 | `fullscreenCopyOnSelect` | **对齐** | 拖拽释放按开关复制（`ViewportInteraction.finish`＋`ChatViewportElement` 现读源）；Ctrl+X 优先选区分支已对位（docs/12）。 |

`E2-45 codemode` 权重 2，出分母（R5：java 无 codemode 子系统）。`queueMode`/`websockets` 只存在于 schema 输入侧，不列单元。

## 转出 / 面外 / 缺失

| 项 | 去向 | 说明 |
|---|---|---|
| E3-3 `-r` 会话选择器 | L7（TUI） | 选择器本体与搜索排序都在 `pi-java-tui`；权重 0 |
| E5-31 扩展 UI 注入（28 成员） | L7（TUI） | 13 个已有 TUI 实现，其余 default＝noOp；权重 0 |
| E5-33 扩展示例库 | L8（examples） | 88/88；权重 0 |
| G-6/G-7 codemode | 面外（R5） | 沙箱运行时与 codemode 工具不对齐、不进分母 |
| N-4 llama.cpp 默认端口 | 出分母（R2） | java 无 llama 扩展；本地 OpenAI 兼容服务经 `models.json` 可接 |
| E8-4 扩展加载器 jiti lazy/Bun·SEA static | 缺失（不适用） | 不计入本层合计 |
| E8-6 WSL 工具 | 缺失 | zip 已收编为 `core/util/ZipArchives`（JDK 形）；java 无 WSL 调用面 |

> E8-5 `experimental/micro` 整块被 pi 删除 ⇒ R1 作废，权重 0。
