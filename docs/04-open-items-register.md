# 04 - 未结项与有效裁决

> 【背景摘要】本文档只保留当前未结项、待用户决定事项和仍有效的范围裁决。已闭环批次、实施过程与旧账面沿革不在这里保存，历史在 git。
>
> 当前目标锚点为 pi `56cae7823`。下列旧锚点开放项必须随 docs/08 的所属层重新取证；未经重测，不自动翻成新基线结论。

## 0. 口径

- **B**：功能或行为缺口。新工单从 B260 起。
- **D**：已登记的窄差。新窄差从 D201 起，明细存于对应 `docs/map/`。
- **F**：需要用户拍板的事项。
- **R1**：pi 已删除的面，不为旧参照物补功能。
- **R2**：Provider 只覆盖已批准的主流范围。
- **R5**：codemode、durable、chord、pico3、micro 等面外子系统不做对齐、不进分母。
- **模型可见面**：模型能读到的提示、工具声明、参数、结果和错误文本，优先逐字对齐。

## 1. 待用户拍板

| 编号 | 问题 | 待决定事项 |
|---|---|---|
| B58 | 固定 pi 与当前 pi-java 的 OpenAI 默认 wire 均已是 `openai-responses`；旧“Java 默认 completions”的描述已过期 | 是否确认按现状对齐并销号，还是登记刻意偏差。AI 层设计时单独提交裁决，不擅自修改。 |

Chord plugin 已按 R5 决定不做，不再是待拍板项。

## 2. 主流功能与协议开放项

| 编号 | 当前结论 | 所属层 |
|---|---|---|
| B263 | AI 缺少 `AI_TELEMETRY_SCHEMA`、`pi.ai.request`/`pi.ai.poll`、content-free block events、request/first-chunk histogram、四类 token counter、cost counter、`provider_error` 500 个 UTF-16 单元上限及 abort 保持 ok 的行为；L1 单独设计。 | L1 AI |
| B265b | models.dev v2 目录数据、图像目录、OpenRouter/Radius 运行时目录未完成；目录复现输入已定为「仓内 vendor 锚点生成器＋提交输入快照」。 | L1 AI（docs/12） |
| B266 | 缺少 ClientIdentity 的 tri-state merge、User-Agent/归因头、Models wrapper 与上层 identity 输入。 | L1 AI |
| B267 | Provider wire、usage、retry/overflow、OAuth/device/env、pi-ai CLI 权限与 Faux 余量未对齐；须按依赖继续拆设计。 | L1 AI |
| B116 | 对外 JSON 的 usage 已改为 pi 扁平形状；Java 内部 `StreamDone` 仍保留嵌套方言，AI 重测时决定是否继续保留。 | L1 AI |
| B156 | `ModelCompat` 逐组件重建存在漏字段风险；新增组件时必须同时检查构造点和 `withPartial` 类重建点。 | L1 AI |
| B259 | RPC `cycle_thinking_level` 对不支持 thinking 的模型仍返级别；`get_available_thinking_levels` 也未按模型能力过滤。pi 对应结果为 `null` 或可用子集。 | L5 coding-agent |
| E5-31 | 扩展 UI 仍有 working 指示器、终端输入、补全、编辑器替换等 TUI 成员未收口；与 D′ 旧行去重重测。 | L7 TUI |
| examples | 旧 16/102 不是新基线分母。必须在 L8 按 `56cae7823` 重新清点，主流示例继续全部做 Java 移植与验收测试。 | L8 examples |

B264 已于 2026-11-02 按 docs/10 实施并闭环：流式工具参数、malformed final、grammar、responseId、消息形状、`argumentsError` 传播与执行前拒绝均有 RED→GREEN 和全仓验证证据；L1 账面见 `docs/map/02-ai.md`。

B265 行为面（#31/#61/#83/#84）已于 2026-11-02 按 docs/11 实施并闭环：跨模型 thinking 丢弃与 textSignature 剥离、`normalizeContext` 无条件前置、transcript tools 三态＋`foldedSystemMessages`、`ModelsStore` operation signal。L1 账面 187.0/240＝77.9%。B263/B265b/B266/B267 仍开放。

## 3. MCP 开放项与复核项

以下条目全部在 L2 按新锚点重测。新 MCP OAuth、取消传播和 `structuredContent` 变化可能改变旧结论，不得原样照抄。

| 编号 | 当前待复核结论 |
|---|---|
| B181 | Java record 不保留 MCP 配置未知键；写路径虽重读 JSON 树，仍需核对新锚点行为。 |
| B182 | 项目配置目录硬编码为 `.pi-java`；pi 支持 `package.json` 的 `piConfig.configDir`。 |
| B183 | `mcp.json` 解析错误结构与 pi 接近，但空文件和部分错误文案不同。 |
| B184 | Java 特有错误类型、JSON 数字写出和键序与 pi 有差异。 |
| B185 | `OAuthMetadataParsers.optionalBoolean` 的 SpotBugs 排除仍需保留或有代码级替代。 |
| B190 | Java 以 `IOException` 判瞬断，范围宽于 pi 的网络错误分类。 |
| B191/B197 | 自建 WHATWG URL 归一化尚未完整互读验证；相对引用和剩余 URL 入口需逐点检查。 |
| B192 | CIMD 文档仍依托 pi.dev；需结合新 `clientRegistration: auto | cimd | dcr` 决策树重新裁决。 |
| B193 | `mcp-auth.json` 的嵌套键、null 省略和字节形态与 pi 不同。 |
| B194 | OAuth 请求的超时、abort 和 transport `signal` 需按新实现重测。 |
| B196 | 工具与资源当前顺序拉取；新锚点已改变连接期资源枚举行为，需整体复核。 |
| B198 | OAuth 页面仅有 CSS 空白差异，计算样式等价。 |
| B202 | `structuredContent` 消费链未完整。codemode 消费者按 R5 排除；`after_tool` 等主流消费者不能一并排除。 |
| B205 | MCP 的 AgentTool 适配位于 coding-agent，这是模块依赖方向决定的现状，不强行搬入 mcp 模块。 |
| B208 | `ImageContent(mediaType, data)` 与 MCP 转换链的参数序不同，必须维持换序点测试。 |
| B210 | `pi mcp` 选项解析为自建实现，需核对位置参数后 flag 透传语义。 |
| B211 | `BrowserLauncher` 是 pi-java 平台替代件，headless 环境只展示 URL。 |
| B212 | TTY 检测以 `System.console()` 替代，readline/abort 以虚拟线程竞速替代，并非完全等价。 |
| B213 | CLI 使用 `pi-java` 品牌与 `.pi-java` 目录，不做 pi 的 chalk 着色。 |
| B216 | 跨包公开面包括 OAuth settings、challenge 清理、状态 wire 和指定目录 settings 装载；重测是否仍是最小面。 |
| B217 | 缺少真实授权服务器的 MCP login 端到端夹具。 |
| B219 | `mcp_servers` 排序使用 `String.compareTo`，非 ASCII 名称可能与 `localeCompare` 不同。 |
| B220 | 尾部裁剪字符集与 pi 不同，窄角空格等边界需逐字测试。 |

## 4. TUI 开放项

TUI 在 L7 统一重测，不沿用旧批次顺序。

| 编号 | 当前缺口 |
|---|---|
| B38 | compaction 后缺少整表重建和完整压缩摘要消息卡。 |
| B39 | 压缩窗口的 Esc 行为尚未完成换绑。 |
| B46 | Bash `onUpdate` 已有生产链和 web 消费，TUI 实时输出消费者仍缺。 |
| B47 | TUI `tool_execution_*` 计数来源仍应按真实工具执行事件修正。 |
| B78 | 模糊匹配的子序列、分词和排序语义需与新锚点重对齐。 |
| B145 | 启动、登录后等定向模型目录刷新点未全部接线。 |
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

| 条目 | 最终结论 |
|---|---|
| B18 | PiMessages 是 pi-java 自有 wire，不按 pi 不存在的对应面改造。 |
| D201 | telemetry typed schema 保留运行时 identity、parent binding 与 metadata 合并；Java 无法复刻 TS 条件类型对 exact attributes、required keys、schema 名与 instrument kind 的编译期约束，不新增 pi 没有的运行时校验。 |
| D202 | malformed 工具参数沿用 Jackson syntax-error 前缀；非对象文案、`{}`＋raw suffix 与 raw 原文逐字保持，不引入 V8 文案兼容层。 |
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
6. 下一新文档号为 **docs/12**；`docs/11` 为 B265 行为面设计，待审核。
