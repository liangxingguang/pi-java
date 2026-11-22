# 04 - 未结项与有效裁决

> 【背景摘要】本文档只保留当前未结项、待用户决定事项、已闭环批次的最终读数与仍有效的范围裁决。实施过程与旧账面沿革不在这里保存，历史在 git。
>
> 当前锚点为 pi `56cae7823`（2026-11-20 七层 L0–L8 重测闭环后切换，见 `docs/map/ANCHOR.md`）。下方条目均已随所属层重测；个别行仍引 `ea448f454` 作对照证据，冲突时以新锚点为准。
>
> **历史设计稿引用**：现行保留的设计/计划稿只有三份——`docs/08-b161-codemode-mechanism-design.md`（B161 codemode 机制设计，**设计待审、尚未实施；已从原 `docs/61` 重编号**）、`docs/09-pi-latest-reanchor-plan.md`（pi 换锚与对齐计划，实施中）、`docs/10-schema-tool-validation-design.md`（P1 工具参数 pi-schema 校验设计）。其余批次设计/进度稿（原 `docs/06`、`docs/11`–`docs/60`、`docs/62`–`docs/66` 等）已删除。正文与代码注释中残留的 `docs/NN` 只是历史溯源标记，不构成有效链接。**现行结论以本文档 + `docs/map/` + `docs/05` 为准**，历史过程在 git。

## 0. 口径

- **B**：功能或行为缺口。已用到 **B292**（B289–B292 是 L1 收口四项，随 [`docs/11`](11-l1-ai-completion-design.md) 直接在代码里落地、未在 §1 立项，故无销号动作；L1 余项复验（strict 无类型节点判据、暖请求 `disableContinuation`）与第二次复验（Anthropic 非 strict 根 schema、`session-resources` 清理接线）同样直接落地、未新开号，见 [`14`](14-l1-residual-alignment-design.md)、[`15`](15-l1-residual-audit-followup-design.md)；B288 仍是 codemode 提案拟新增接缝，未批准）；新工单从 **B293** 起。
- **D**：已登记的窄差。已用到 **D273**（codemode 原稿预占号无效）；新窄差从 **D274** 起，明细存于对应 `docs/map/`。
- **读数视图（2026-11-22 起）**：真源是各层地图 `docs/map/NN-*.md`；`docs/05` §1.2 是**汇总视图**、`docs/09` §3 是**进度视图**（含「当前」列），两者必须与真源同步改。设计稿（`docs/11`／`docs/12`…）只记设计与收口结论、**不发布读数**，其中的分数一律视为各自写作时的基线。
- **F**：需要用户拍板的事项。
- **R1**：pi 已删除的面，不为旧参照物补功能。
- **R2**：Provider 只覆盖已批准的主流范围。Qwen／Xiaomi Token Plan 家族（团队／个人、国际／中国、AMS／SGP）已按 `docs/09` P3 余项纳入（26 个内置 provider）；其余长尾 provider 仍排除。
- **R5**：codemode、durable、chord、pico3、micro 等面外子系统不做对齐、不进分母。
- **模型可见面**：模型能读到的提示、工具声明、参数、结果和错误文本，优先逐字对齐。

## 1. 待用户拍板

| 编号 | 问题 | 待决定事项 |
|---|---|---|
| B58 | 固定 pi 与当前 pi-java 的 OpenAI 默认 wire 均已是 `openai-responses`；旧“Java 默认 completions”的描述已过期 | 是否确认按现状对齐并销号，还是登记刻意偏差。AI 层设计时单独提交裁决，不擅自修改。 |

Chord plugin 已按 R5 决定不做，不再是待拍板项。

### codemode 机制提案（尚未批准、尚未实施；R5 现状仍是「不做、不进分母」）

完整设计保留在 `docs/08-b161-codemode-mechanism-design.md`（设计待审、未实施），下列 F1–F5 是其待拍板摘要。

| 编号 | 问题 | 建议与依据 |
|---|---|---|
| F1 | R5 是否改为「实现 codemode **能力版**、仍不进对齐分母」？ | 建议同意。模型可见语言保持 JavaScript；B161（机制）与 B177（MCP 默认激活）随之重开。 |
| F2 | 引擎选型 | 建议 **Endive 1.1.0 + quickjs-wasi** 优先（2026-11-20 实测运行时全能力可用，压缩体积仅 +0.95 MB；1.2.0 有编译器 bug 故锁 1.1.0），**GraalJS 兜底**（+64 MB、零 ABI 绑定），Chicory 备选；若 Java 绑定超预算回落 GraalJS。 |
| F3 | 是否批准新接缝 `B288 AgentTool.prepareLoadout(ToolLoadout)` 对位 `mode`/内联目录？ | 建议批准；否则降级并另登新窄差。原稿曾预占编号 D264–D272，该批编号已被 L7 正式占用，若采纳必须重新分配。 |
| F4 | `models` 命名空间 v1 是否直接不做？ | 建议不做：classifier 无对位；**provider 级图像生成已具备**（`OpenRouterImagesApi`／`OpenAIImagesApi`），但脚本面按 R5 不暴露，调用报 `models is not defined`。 |
| F5 | web 模式是否默认允许 codemode？ | 建议与 CLI 同策略：本地单用户默认可用、可关闭；多租户/远程默认关闭或改用进程后端。 |

## 2. 主流功能与协议开放项

| 编号 | 当前结论 | 所属层 |
|---|---|---|
| B116 | 对外 JSON usage 已改为 pi 扁平形状；Java 内部 `StreamDone` 嵌套方言**已由 AI 重测裁决保留**（`docs/map/02-ai.md:238`），出口由 `JsonEventMapper` 展平；新增投影不得把内部方言直接序列化。 | L1 AI |
| B156 | `ModelCompat` 逐组件重建存在漏字段风险；新增组件时必须同时检查构造点和 `withPartial` 类重建点。 | L1 AI |
| E5-31 | 扩展 UI 仍有 working 指示器、终端输入、补全、编辑器替换等 TUI 成员未收口；与 D′ 旧行去重重测。 | L7 TUI |
| B287 | 文件工具 operations 接缝未对位（B286 的姊妹缺口）：`read`/`write` 走 `ToolContext.fs()` 但工厂不可注入，`edit` 混用 NIO 与 `fs`，`grep`/`find`/`ls` 直接 NIO ⇒ Docker/SSH 类文件后端无法只替换文件工具。范围与 pi `ReadOperations`/`WriteOperations`/`EditOperations` 对位（D243 同族）。**后续路线（用户 2026-11-20 决定）**：先补本项，再写 SSH 扩展提供远程 bash + 文件工具；不迁移 chord/env/durable，详细设计后续完善。 | L3 agent-core |

已闭环批次的账面与台账结论见 `docs/05` §1、`docs/07-examples-alignment.md` 与 `docs/map/`；实施过程按维护规则第 1 条不在此保存，历史在 git。

## 3. MCP 开放项与复核项

以下条目已按新锚点（`56cae7823`）在 L2 重测。开放项与「已判对齐但必须持续保持」的回归约束分列；B210/B211/B216 已判对齐（`docs/map/07-mcp.md:139-140,170`），按维护规则第 3 条从开放表移除，历史在 git。

**仍需动作／补测**

| 编号 | 当前待复核结论 |
|---|---|
| B220 | 尾部裁剪字符集与 pi 不同，窄角空格等边界需补逐字测试（`docs/map/07-mcp.md:128` 仅覆盖 stderr tail 对齐）。 |

**持续约束（已判对齐，改动对应代码时必须回归）**

| 编号 | 当前结论 |
|---|---|
| B185 | `OAuthMetadataParsers.optionalBoolean` 的 SpotBugs 排除仍需保留或有代码级替代（`spotbugs-exclude.xml:22-31`）。 |
| B205 | MCP 的 AgentTool 适配位于 coding-agent，这是模块依赖方向决定的现状，不强行搬入 mcp 模块（`docs/map/04-coding-agent.md:550`）。 |
| B208 | `ImageContent(mediaType, data)` 与 MCP 转换链的参数序不同，必须维持换序点测试（`docs/map/07-mcp.md:112`）。 |
| B219 | `mcp_servers` 排序使用 `String.compareTo`，非 ASCII 名称可能与 `localeCompare` 不同（`docs/map/07-mcp.md:49`）。 |

**跨层转出（docs/22，2026-11-04 用户裁决）**：以下条目不属 MCP 层，保留在 `map/07` 供追溯但权重记 0、不进 MCP 分母；归属由各层重测时确认。

- `#5/B182` → L5 配置子系统（`piConfig.configDir`）
- `#6/B183` → 共享 JSON 解析方言（V8 与 Jackson 错误正文）
- `#27/B190` → 平台异常分类方言（`TypeError` 与 `IOException`）
- `#30/B191/B197` → 平台 URL 归一（WHATWG 与 `java.net.URI`）
- `#38/B193` → 共享 JSON 序列化方言（键序／显式 `null`）
- `#40/B198` → L1 ai 共享 `OAuthPage` 模板
- `#41/B212` → JVM 平台方言（`isatty` 无对位）
- `#43/B192` → OAuth 身份托管基础设施（pi.dev 域名）
- `#71/B213` → 全仓品牌改名方言（`pi-java`／`.pi-java`）

## 4. TUI 开放项

TUI 已在 L7 按新锚点重测；下表只列仍未收口的面。

| 编号 | 当前缺口 |
|---|---|
| B38 | compaction 后缺少整表重建和完整压缩摘要消息卡。 |
| B39 | 压缩窗口的 Esc 行为尚未完成换绑。 |
| B46 | Bash `onUpdate` 已有生产链和 web 消费，TUI 实时输出消费者仍缺。 |
| B145 | 启动、登录后等定向模型目录刷新点未全部接线（登录后已落，其余启动点证据不足）。 |
| B238③ | OSC 7501 程序状态上报未实现。 |
| B238④ | APC 标记假光标未实现。 |

R29 安装变更重启警告维持不做；若新的 `selfUpdateCommand` 形成真实安装变更通道，再重新裁决。

## 5. 面外与删除面结论

### 5.1 R5：不做且不进分母

- codemode 沙箱与工具，包含 B161 和 MCP 默认 codemode 激活 B177。
- durable、chord、pico3、micro。
- 新锚点 telemetry 的 links/propagation、TelemetryStore 与 renderTrace（map/01 T18–T20）：T18 生产消费者为 Chord＋Durable，T19 为 Chord，T20 未见主流生产消费者，均属既定 R5 面；AI 所需 carrier/exemplar 不在此排除项内。主流消费者出现时原位重开。
- 分类器及统一模型设施，除非未来进入不依赖 codemode 的主流路径。
- R2 范围外的长尾 Provider 和对应鉴权。
- `streamProxy`，除非 pi-java 增加服务端托管鉴权代理车道。

共享主流行为不能随 R5 跳过。例如 tool_search、MCP 数据形状和 OAuth 仍按消费链对齐。扩展/API 可达本身也不等于 R5；AI #33/#93/#100 已在 L1 回到分母。

### 5.2 R1：不为已删除参照物补功能

- pi 已删除的 experimental harness、session-backends、旧 server/client/plugin 发布面。
- `PendingEntry`、`InboxItem` 等已删除实验类型。
- 其他删除项必须给 pi 删除提交或新锚点缺席证据后，才允许出分母。

SQLite 后端是 pi-java 独立实现，保留模块，不对齐已删除 schema。

## 6. 仍有效的实现边界

> D 编号是**不可消除的窄差总账**，不是实施过程；统一记为「编号 → 一句当前边界 → 证据指针」。完整源码取证在对应 `docs/map/`，编号不复用，新差异从 **D274** 起。

### 6.1 AI / 协议 / 会话与 CLI（证据：`docs/map/02-ai.md`、`docs/map/03-agent-core.md`、`docs/map/04-coding-agent.md`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| B18（已销号） | pi 锚点已有同一 `POST /messages` SSE 协议；旧「pi-java 自有 wire」描述失效，细差归 #110 | map/02 |
| D201 | telemetry 只保留运行时身份/父绑定/元数据合并；TS 条件类型的编译期约束不复刻 | map/02 |
| D202 | malformed 工具参数沿用 Jackson 前缀，pi 文案逐字保留 | map/02 |
| D203（已关闭） | `pi.ai.poll` 已有生产入口（deferred + Faux）；其他 provider 保持「方法不存在」 | map/02:238 |
| D204 | identity 合并落在 `DefaultProviders`／`ConfigurableProvider`，请求头行为对齐 | map/02 |
| D205 | `User-Agent` 品牌/运行时 token 不同，归因头仍用 pi 品牌值（D77 族工具自述差异） | map/02 |
| D206 | `StreamError.Throwable` 不得进序列化投影，一律走 `textOf` | map/02 |
| D207 | `Model.input/reasoning` → `ModelCapability`；未知模型按支持图像输入（Java 独有状态） | map/02 |
| D208 | `ToolResultMessage.details/usage` 用 `Object`，避免 ai 反向依赖 agent，保持 JSON 兼容 | map/02 |
| D209 | 静态协议注册表按 `api` 派发，不能加载 JS api 模块 | map/02 |
| D210 | 无动态模块加载；setup 失败同步抛错，deferred 懒包装无对位 | map/02 |
| D211 | `PiMessagesEvent` 是 Java record 方言；扩到请求/响应外壳须复核 #7/#63 | map/02 |
| D212 | `DeferredHandle.data` 用 `Map` 承载 `JsonValue`；顶层数组/标量需复核，纵贯测试已补 | map/02 |
| D213 | `contentIndex` 缺键严格拒绝（Jackson 填 `null` 会误判合法下标） | map/02 |
| D214 | Faux 工厂仅同步返回；真异步夹具出现时复核 | map/02 |
| D215 | Faux usage 文本只投影 Tool 四字段；差异仅影响测试 token 数字 | map/02 |
| D216 | 无 `AbortSignal` 通道；deferred 不移植块间中止，终局可显式 `ABORTED` | map/02 |
| D218 | 分支用 Java lane 表达 pi 单 leaf 树；`lane` 是 pi 忽略的内部键（D190），行为由 `TreeCompaction` 对齐 | map/06 |
| D219 | 工具激活序取注册插入序（pi 用 runner `activationOrder`）；loadout 由注册序＋`reachesTools` 决定 | map/04 |
| D220 | `KeybindingHints` 不进扩展渲染 API（D92 文本级方言），键位提示由宿主 TUI 展示；`blocked` 随 E5-31 归 L7 | map/04 |
| D221 | `selfUpdateCommand` 的 `{version}`：pi 为目标版本、Java 为当前版本，配置命令自行处理「已最新」 | map/04 |
| D222 | npm 特有 CLI 行为由 HTTP registry 臂等价实现；批装改为逐包顺装（D113），「版本没变」消息逐字对齐 | map/04 |
| D223 | 无 lockfile 目录层，`auth.json` 用 `FileChannel.lock()`，EPERM 重试分支不适用 | map/07 |
| D224 | tool_search 用 JDK `BreakIterator`（UAX#29）替代 `Intl.Segmenter`；仅无空格连写脚本不同 | map/04 |
| D225 | HTML 导出自带 CJK 强调放宽，不引入 `marked-cjk-friendly`；判定以渲染结果为准 | map/05 |
| D226 | macOS 进程组 `SIGSTOP` 先手不移植；`destroyForcibly()`＋`descendants()` 后序遍历 | map/04 |
| D227 | 模型解析多两条 Java 臂：运行时注册 provider 的自定义 id、`provider/id` 前缀回落；裸未知模型仍报错 | map/04 |

### 6.2 评测（证据：`docs/map/06-session-evals-chord.md`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D182 | Docker 真实构建/执行不进 CI；假进程钉住命令契约与 JUnit XML 读取，两锚点 docker 源逐字节相同 | map/06 |
| D183 | TUI 文档审计用文档问答＋Levenshtein 0.75 替代 pi 的 context-footer oracle；判分核心未变，维持存疑 | map/06 |
| D184 | 扩展作者 eval 额外允许 `bash`（JAR 需编译），其余 docs eval 仍只用六件 | map/06 |
| D185 | eval 用静态注册表发现、Docker 报告读 JUnit XML；字段/语义对齐，文本不逐字 | map/06 |
| D186 | provider/model eval 用内联探针代写 `models.json`（Java read/write 限 workspace） | map/06 |
| D187 | 扩展作者 eval 经 `sessionCustomizer`＋内联 `install_extension` 装载 JAR（pi 用 `/reload` 扫描目录，D90 裁决③） | map/06 |
| D188 | `SmokeEval` 仍会被 CLI 全量 lift 配对；`--audit` 使 `--host` 只跑非审计套件 | map/06 |
| D189 | `RecordingFooterOracle` 自复刻 footer 文本，宽度按每 codepoint 一列近似（无宽字符两列） | map/06 |

### 6.3 评测产物 / 范围裁决（证据：`docs/map/06-session-evals-chord.md`、`docs/07`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D228 | `report.json` 升 `schemaVersion=3`：per-eval-set `comparisons`＋变体 `operationalTotals`，lift 用 pass-rate 差；旧产物不互读 | map/06 |
| D229 | 每臂只写单 `<testcase>`，`readTaskObservation` 的 fullName 预过滤无对应场景，落产物等价 | map/06 |
| D230 | 审计只读种入 workspace 的实现源子集（`AuditSourceIndex` 按页面选源），判分口径不变 | map/06 |
| D231 | Java 独有 `pi.java.agentDir` 属性把 agent 目录指向隔离根；项目配置目录为 `.pi-java` | map/06 |
| D232 | examples 8 个外部运行时文件出分母（沙箱/micro-VM/WASM/JS 解析无对位），不写降级假移植 | docs/07 |
| D233 | `ModelRegistry.findOfType`/`classify` 无 classifier 面；jev-router 用显式模型 id | map/04 |
| D234 | `NewSessionOptions.setup` 不提供（零消费且与只读门面冲突）；只落 `parentSession`/`withSession` | map/04 |
| D237 | examples 另有 5 件范围面出分母（overlay〔D99 裁决不移植〕/game/mac 主题）；分母 93→88，ExtensionUI 槽位仍由余件覆盖 | docs/07 |

### 6.4 examples / 扩展降级（证据：`docs/map/04-coding-agent.md`、`docs/07`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D235/D238/D250/D254 | TUI 颜色/边框/动画/ANSI 绘制统一降级为纯文本或文本前缀（D92/D100 同族）；questionnaire 逐题答案从内容行前缀复原 | map/04 |
| D236/D239/D245/D246 | loader、通知与定时器用同步/注入式方言（`TickDriver`/`FileSource`），三结局与中止语义保持 | map/04 |
| D240 | `ShellExecutor` 返回完整 `ShellResult`，外部后端自填截断/行列计数；`spawnHook` 无对位，改写 `tool_call.input` | map/04 |
| D241/D242/D253 | 工具渲染器/补全/参数补全收敛为纯文本 `ToolCallRenderer` 与空接口（D92 同族）；建议内容不入键盘面 | map/04 |
| D243 | 文件工具 Operations 未注入（B287）；ssh 只作同名注册＋本地执行＋远程命令纯函数演示 | map/04 |
| D244 | subagent 用注入式假 runner 验 single/parallel/chain，不真起子进程 | map/04 |
| D247/D248/D251/D252 | SDK/RPC/runtime 入口名差，用等价投影＋纯函数断言；Java `RpcExtensionUI.setTitle` 是行为增强差 | map/04 |
| D249 | `dynamic.json` theme 仅作测试资源，断言键数与字段存在，无结构化类型 | map/04 |

### 6.5 TUI / Web（证据：`docs/map/05-tui-web.md`）

| 编号 | 当前边界 | 证据 |
|---|---|---|
| D255/D256/D257 | TamboUI 外部库边界：Kitty 键解析、框架层整块面（全屏搜索/选区/flash/图像/OSC）、行级 markup 无 SGR 直写；K5/K32/R3 维持存疑 | map/05 |
| D259 | 工具行计时流中臂（每秒重绘 `Elapsed`）无对位；终局臂取持久化 `durationMs`，逐字对齐且跨重载仍准 | map/05 |
| D260/D261/D262/D263 | 渲染器文本级窄差（D92 同族）：read 分类行不移植、折叠按逻辑行、截断警告不重复产出、edit 不读盘 preview（diff 仍上色） | map/05 |
| D264/D265/D266 | 列表：别名与整行同色（D92）、`capturesViewportInput` 只给补全弹层一个门（D99/D256）、分页按「项」不按可视行 | map/05 |
| D267/D268/D269/D270 | 排序用 `compareTo` 求确定性、消息文本每次按键重算、路径键 UNC/短名边角不保证逐字、`FuzzyMatch` 无命中下标 | map/05 |
| D271 | `--session <id>` 保留跨项目 `find()` 阶梯（pi 仅项目内精确查找）；项目内命中行为一致 | map/05 |
| D272 | 扩展对话框 `blocked` 无状态行/输入拦截消费面；栈化生命周期已对齐 | map/05 |
| D273 | 技能 collision 行尾 ` (source)` 不移植；`--skills` 静态发现的诊断仍未上抛 | map/05 |

### 6.6 其他保留边界（证据：`docs/map/03-agent-core.md`）

| 编号 | 当前边界 |
|---|---|
| B83 | pi 自身三处 sanitize 缺口保持同样行为；pi 修补时同步。 |
| B100 | compat 探测的 baseUrl 在内置模型上行为等价；出现可观察差异再重裁。 |
| B126 | Codex 车道及其 transport 等随 R2/R5 出分母。 |
| B147 | Responses 回放保留分组顺序；发现 provider 配对校验差异再重裁。 |
| B130 | 当前携带的 zai 模型有效 reasoning effort 与 pi 相同；引入新模型时由数据重新判定。 |
| B132 | together/baseten/fireworks 不内置；自定义 models.json 的显式配置能力保留。 |
| map/03 #54/#58/#78/#100 | Entry 表示、投影合并、会话查找/排序和系统提示载体按已登记边界视为行为等价或刻意差异。 |
| map/03 #14/#80/#84/#82/#90/#91 | 队列、prompt 门、重试映射和转录可见性维持已验证结论；新锚点重测时复查。 |
| map/03 #57/#60 | bashExecution 窄差按消费面保留；已删除 PendingEntry/InboxItem 永久出分母。 |

这些结论不是新锚点免检证明。若对应源码已变化，必须在所属层重新判定。

## 7. 维护规则

1. 本文只留当前结论，不追加批次实施过程。
2. 新缺口先给新锚点源码证据，再登记 B；新差异无法消除时登记 D。
3. 条目关闭后从开放表移除，历史由 git 保存，编号不复用。
4. 每层重测后同步更新对应地图；全部层完成前不发布新全仓百分比。
5. 每个实施工作包仍需独立设计稿和用户审核。
6. 批次设计稿不属于本仓库的最终产物：**实施前**的设计稿按需临时编号，闭环后结论回写 `docs/04` 与 `docs/map/`，稿件删除（历史在 git）。截至 2026-11-20 已清理所有**已闭环**批次设计稿；**进行中的 `docs/08`（B161 codemode 机制，原 `docs/61`，设计待审）、`docs/09`（pi 换锚计划）、`docs/10`（P1 工具参数校验设计）例外保留**，直至对应工作闭环后再回写删除。现行结论入口为 `docs/01`–`docs/05`、`docs/07`、`docs/map/`。
