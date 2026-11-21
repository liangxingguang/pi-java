# 09 - pi 最新代码换锚差异与对齐计划

> 【背景摘要】2026-11-20 重新拉取 pi `main` 到 `a00a5048d`（v1.6.0 发布后 12 个提交，距 v1.5.2 约 380 个提交），与 pi-java 当前锚点 `56cae7823` 对比，给出结构差异、七层差异和对齐排期。
>
> **状态：计划已审核通过（2026-11-20）。** 目标锚点已写入 `docs/map/ANCHOR.md`；`CURRENT_PI_PIN` 仍未切换。
>
> **实施进度（2026-11-21）：**
> - P1 已完成：`9cccfe23 feat(ai): validate tool arguments with pi-schema semantics`。
> - P2 首批已完成：`dc59f0c7 feat(ai): align v1.6.0 retry patterns and instruction role`。
> - P2b 已完成：`734c7a21 feat(ai): support v1.6.0 prompt cache key mode and assistant/tools compat keys`
>   （三个新 compat 键贯穿 ModelCompat／models.json／四条车道解析与读点；响应车道 `disabled` 生效）。
> - P2c 已完成（2026-11-21）：`65857596`（token 上限 15% 余量、responses `session-id`、Copilot 顶层 reasoning tokens）、
>   `68eb84ef`（Mistral strict 显式 opt-in）、`14e90c8b`（`openai_input_transformations` 诊断）。
> - P2 余项结论：Codex 专属形状（developer item／`max_output_tokens`／`configuration_update`／WS 60 分钟）
>   **不适用** —— pi-java 无 `openai-codex-responses` 车道（R5 范围外）；共享的 responses 部分已逐项核对
>   （`max_output_tokens ≥16`、显式 prompt cache、亲和头）。Copilot 的 strict/reasoning 两条已落；
>   剩余分类器（Microsoft-Decision-1）按计划属 R5。
> - P3 部分完成（2026-11-21）：`fca70024`（catalog 规则按 `a00a5048d` 重新 vendor、上游快照重取、
>   18 分片重生成：645 chat／74 image，radius 基线 28→39）、`21ec26b0`（默认模型表同步：
>   Anthropic Opus 5.5、OpenAI GPT-6.2 Sol、Google gemini-4-argon 等）。
> - P3 余项已完成（2026-11-21，`docs/11` 闭环）：`753bdc30`（多变量密钥回落链）、`d035ffa0`。
>   Qwen Token Plan Team／Team CN／Individual／
>   Individual CN 与 Xiaomi AMS／SGP 六个 provider 注册（共 26 个内置 provider）；`qwen-token-plan-cn`
>   显示名、中国端点与密钥链按 `a00a5048d` 更新；`ProviderConfig` 支持多变量密钥回落链
>   （`apiKeyEnvVars`），主人确认 `source` 记实际命中的变量；分片 18→23（645→695 chat）、
>   默认模型表 17→22 键。生成器 `--check`／`--verify-vendor` 零差异。models.dev 快照只补进
>   三个新上游键并保留既有 15 键原序（OpenRouter／Radius 快照不重取），故既有 18 个分片
>   逐字节未变；新增分片首条顺序钉在提交快照上。
> - P10 首批已完成（2026-11-21）：`87ae0bfe`（中止批次仍逐调用补 `Operation aborted`
>   的 start/end/结果；准备相顶端中止门先于查找与钩子，pi #9986）。`streamProxy` 的
>   `disableContinuation` 透传随 P4 message threads 一起做（threads 未实施，暂不适用）。
> - 另：`5ee385a8`（Gemini `CONTINUATION` 结束映射为 `length`，pi #12103/`f72f799a4`；
>   Java SDK 无 `automaticContinuation` 开关，该 SDK 特性不存在）。
> - P4 之后（P5–P9、P11–P12）未开始。
>
> 术语释义：
> - **锚点**：pi 的一个固定 git 提交，pi-java 以它作为行为对照基准，取证只走 `git show <sha>:<path>`。
> - **模型可见面**：模型能读到的系统提示、工具声明、工具结果和报错文本；其余算【内部，模型不可见】。
> - **窄差（D 项）**：Java 平台无法消除的差异，登记在 `docs/04` 和对应地图，不重复扣分。
> - **R5**：面外子系统裁决——不做、不进分母；共享主流行为不能借 R5 跳过。
> - **pi-schema**：pi 1.5.0 新增的 JSON Schema 构建/校验包，不用 eval（动态执行代码），替代 TypeBox。

## 0. 结论

1. **新锚点候选为 `a00a5048d`（2026-11-20，main HEAD）**。它包含 v1.6.0 发布提交及其后的 12 个修复提交，比 v1.5.2 多约 380 个提交；建议直接钉 HEAD，不钉发布 tag。
2. **结构上必须对齐的新增项只有一个：`pi-schema` 的校验语义**。它已被 `ai` 和 `agent` 直接依赖，校验结果和报错文本对模型可见，不能按 R5 跳过。
3. **`env`／`chord`／`durable`／`apps/p2`／`apps/p2-mac`／`apps/p3` 继续出分母**。它们不在 `coding-agent`／`agent`／`ai`／`tui` 的依赖链上；建议把 `env` 和 apps 明确写进 R5 清单。
4. **codemode 沙箱仍出分母**，但 `tool_search` 用到的 `resolveSchemaRef`（跟随本地 `$ref`）必须在 Java 侧单独实现，不能随沙箱一起跳过。
5. **规模**：锚点以来 818 个提交、3,463 个文件、+461,742/−41,486 行；剔除 R5 与 apps 后，八个在册包的 `src` 变更约 **15,700 行**（其中新包 schema 4,852 行）。
6. **排期顺序**：先换锚重测（P0），再跨层 schema 语义（P1），然后 AI wire（P2/P3）、coding-agent（P4–P7）、TUI（P8）、MCP/agent/session（P9/P10）、telemetry/evals/examples（P11），最后收尾（P12）。Anthropic message threads 单独成包，可延后。
7. **最大风险**：`pi-schema` 完整方言、Anthropic message threads、TUI 渲染缓存契约、pi 的移动速度。

## 1. 锚点与差异规模

| 项目 | 当前锚点 | 最新代码 |
|---|---|---|
| 提交 | `56cae782341c339d3bb820081f0f9425d8c5b1ec` | `a00a5048dfae964fe89dce593215d0c3d275017c` |
| 日期 | 2026-10-31 | 2026-11-20 |
| `package.json` 版本 | 1.4.0（不是 v1.4.0 tag；该 tag 在 2026-10-21） | 1.6.0（v1.6.0 发布后 12 个提交） |
| 距上一个发布 tag | v1.4.0 之后 265 个提交 | v1.6.0 之后 12 个提交（v1.5.2 之后约 380 个） |
| 两锚点之间 | — | **818 个提交** |

全仓 diff：3,463 个文件，+461,742/−41,486 行。大头来自面外包和 apps：

| 范围 | 源码行数变化（约） | 处置 |
|---|---:|---|
| chord | +38,051 / −14,999 | R5，出分母 |
| schema | +4,852（新包） | **在册，必须对齐校验语义** |
| durable | +3,172 / −965 | R5，出分母 |
| env | 约 +220（包总量 2,575 行） | R5，出分母 |
| apps/p2、p2-mac、p3 | 新增约 4.4 万 TS 行 | 面外，出分母 |
| coding-agent src | +3,742 / −1,983 | 在册 |
| ai src | +1,606 / −982 | 在册 |
| mcp src | +1,540 / −72 | 在册 |
| tui src | +570 / −183 | 在册 |
| agent src | +32 / −25 | 在册 |
| telemetry src | +69 / −11 | 在册 |
| evals src | 0（仅 `package.json`） | 在册，无行为变化 |

> 取证口径：changelog 只用于圈定候选差异。很多 `[1.5.0]` 条目是对锚点前已有代码的补记，不能当作新增。本文差异均以 `56cae7823..a00a5048d` 的提交与源码 diff 为准。

## 2. 结构差异与范围裁决

### 2.1 包与依赖变化

| 包／目录 | 锚点时 | 现在 | 与在册包的依赖关系 | 建议 |
|---|---|---|---|---|
| `packages/schema` | 不存在 | 新包，43 个源文件、4,852 行 | `ai`、`agent` 直接依赖 | **在册**：只对齐校验行为 |
| `packages/env` | 存在 | SSH `ExecutionEnv`，2,575 行 | 只被 `durable` 依赖 | R5 出分母 |
| `packages/chord` | 存在 | 对象/插件运行时，66,984 行 | 只被 apps/durable/env 依赖 | R5 出分母 |
| `packages/durable` | 存在 | 实验性会话/任务运行时 | 只被 apps/env 依赖 | R5 出分母 |
| `packages/codemode` | 存在 | 17 个源文件、3,051 行，几乎未变 | `coding-agent` 静态依赖 | 沙箱 R5；其 `$ref` helper 在册 |
| `apps/p2` | 不存在 | 实验性 Web UI，35k 行 | 只依赖面外包 | 出分母 |
| `apps/p2-mac` | 不存在 | Swift/AppKit 原型 | 无 | 出分母 |
| `apps/p3` | 不存在 | p2 的后继、无页面 | 只依赖面外包 | 出分母 |

关键依赖边（来自各 `package.json` 与源码 import）：

- `ai` → `pi-schema`：`packages/ai/src/utils/validation.ts`、`providers/model-schema.ts`、`providers/compat-schema.ts`、`providers/schema-builders.ts`。
- `agent` → `pi-schema`：只在类型层使用 `Static`；运行时校验在 `ai`。
- `coding-agent` → `pi-codemode`：仅 `extensions/codemode/*` 与 `extensions/tool-search/tool.ts`。
- `coding-agent`／`ai`／`agent`／`tui` 均不依赖 `env`／`durable`／`chord`；因此这些包里的行为变化不进入本仓的对齐分母。

### 2.2 待用户裁决事项

| 编号 | 问题 | 建议 |
|---|---|---|
| F1 | 新锚点钉 `a00a5048d` 还是 v1.6.0 发布提交 `faac58588`？ | 钉 `a00a5048d`。发布后又落了 12 个提交，含重试、编辑器重绘和工具预览可读性修复；钉发布提交会立刻欠一笔。 |
| F2 | `pi-schema` 对齐到什么程度？ | 先对齐**模型可见行为**：参数强制转换、报错文案、点路径、悬空 `$ref` 本地拒绝、标准 format。Java 不移植构建器 API（Java 侧 schema 本来就是 JSON `Map`）。完整方言只在夹具暴露缺口时再扩。 |
| F3 | `env`、apps 是否入 R5？ | 建议入 R5，并把「codemode 沙箱出分母、`resolveSchemaRef` 入册」写成同一行的例外说明。 |
| F4 | Anthropic message threads 是否本周期实施？ | 建议单独设计、排在 P1/P2 之后；它改动请求生命周期，风险独立。若周期预算紧，可先登记 B 延后。 |
| F5 | TUI 旧缺口（73.4%）与新差异如何排序？ | 先按新锚点重测，再把新差异分成「API 契约/性能」和「新 UI 能力」；旧 C5/C6 状态面与渲染长尾维持现有优先级，不因新锚点重新洗牌。 |
| F6 | examples 是否按新锚点重清点？ | 是，但只复核，不重开已完成批次。新锚点 examples 只改了 18 个文件，其中 sandbox 已按 D232 出分母，plan-mode 已按 D237 出分母。 |

## 3. 分层差异

### L0 telemetry（两项变化）

| 变化 | 证据 | 可见性 | 规模 |
|---|---|---|---|
| 新增 `telemetryNow()`：微秒级墙钟时间，内存 span 的 `startedMs`／`endedMs` 改为微秒取整墙钟 | `ff33ef42c`；`packages/telemetry/src/memory.ts:116`、`index.ts:466` | 观察者可见 | M |
| `renderTrace` 把 C0/C1/DEL 转义为 `\uXXXX`（span/event 名、trace/span id、属性键、错误文本） | `ff33ef42c`、`378d45a05`；`render.ts:8,28` | 观察者可见 | S |

telemetry 的事件名和 schema 未变。`renderTrace` 的消费者历史上按 R5／T20 出分母，重测时复核是否仍在分母。

### L1 AI（模型可见面为主）

| 变化 | 证据 | 可见性 | 规模 |
|---|---|---|---|
| **破坏性**：工具参数校验从 TypeBox 改为 pi-schema；新增强制转换（`null`→`"null"`、`"3.5"`→`3`、单值包成数组等）；错误文本改为 `- edits.0.oldText: missing` 形式，外层为 `Validation failed for tool "..."` | `6536f8012`；`packages/ai/src/utils/validation.ts:1-29,54-68`；`packages/agent/src/agent-loop.ts:736` | **模型可见** | L |
| 悬空 `$ref` 只在该位置拒绝，并报 `unresolved reference #/...`，不再让整个工具调用失败 | `packages/ai/src/utils/validation.ts:12-18` | 模型可见 | S |
| Anthropic message threads：`thread` 的 create/continue 两态、beta 头、前缀哈希、每会话上限、线程错误回退整请求、`cleanupSessionResources`；`api.anthropic.com` 默认开启，`disableContinuation` 可关 | `a758f3ae4`、`64c96d00d`；`anthropic-messages.ts:249,847`、约 `:280-430` | 模型可见（请求形状） | L |
| strict 工具降级规则：`prefer` 遇到无类型节点改发非 strict；`require` 本地抛错；裸 `enum` 仍 strict；strict 超限按 session 记忆，后续直接非 strict | `406e719a6`、`45344bd3a`；`constrained-sampling.ts:75,251-271`、`strict-tool-limits.ts:51` | 模型可见（wire） | M |
| Anthropic 非 strict 工具保留根 `additionalProperties`／`$defs`／`definitions` | `aa47cedce`、`4a6a58eb5`；`anthropic-messages.ts:1911-1920` | 模型可见（wire） | S |
| OpenAI completions 默认省略空 `tools: []`；新增 `compat.requiresToolsWithToolHistory` 恢复旧行为 | `7a6bd5a97`；`openai-completions.ts:852` | 模型可见（wire） | S |
| 新增 compat：`requiresNonNullAssistantContent`（空串代替 `null`）、`promptCacheKeyMode`（auto/enabled/disabled） | `14b804d80`、`0f4d5e960`；`openai-completions.ts:1287,808` | 模型可见（wire） | S |
| OpenAI Responses 认可度头改为 `session-id`（连字符） | `c5d397835`；`openai-responses.ts:320` | 模型可见（wire） | S |
| Codex：系统提示改为首个 developer item，不再放顶层 instructions；WS 连接 60 分钟上限重试、45 分钟复用 socket | `edfe6b2ef`、`bb1a2b354`；`openai-codex-responses.ts:555,60,881` | 模型可见（wire） | M |
| GitHub Copilot：strict false/true 分支、`reasoning_effort` 与 reasoning tokens、Microsoft-Decision-1 分类器 | `86918f9ee`、`0ec20e157`；`providers/github-copilot.ts:118-121` | 模型可见（wire）／分类器属 R5 | M |
| Mistral 工具默认非 strict，`compat.supportsStrictMode` 才 strict | `2c2a77f9f`；`mistral-conversations.ts:534` | 模型可见（wire） | S |
| 重试与错误文本：裸 socket 错误码、`Connection closed.`、回复开始后断流；连接底层原因附加到错误文本 | `4699b5b26`、`9b88b94e2`、`566344046`、`4c992154d`；`retry.ts:79-118`、`error-body.ts:60` | 模型可见（错误文本） | S |
| v1.6.0 重试扩展：Cloudflare 525、网关 `provider is temporarily unavailable`／响应未完成／连接重置／生成失败、流内 Anthropic `api_error`、OpenRouter 空/提前停止、`finish_reason: "error"`、`admission ceiling`；backoff 后移除 abort listener | `a00a5048d`、`25190b72c`、`fb62c069b`、`51ba3ab5b`；`retry.ts` | 模型可见（错误文本） | S |
| `openai-completions` 指令角色：推理模型的系统消息只对 `api.openai.com` 发 `developer`，其他主机（Azure/OpenRouter 等）默认 `system`；`compat.supportsDeveloperRole` 可覆盖 | `b82477b7b`；`openai-completions.ts:1226,1641-1648,1706` | 模型可见（wire） | S |
| Codex：`maxTokens` 作为 `max_output_tokens` 发送（最小 16），除非 `compat.supportsMaxOutputTokens: false`；thinking level 改走固定 `reasoning.effort` 的 `configuration_update`，保住提示缓存 | `fcc33744b`；`openai-codex-responses.ts:62-64,535,548,613` | 模型可见（wire） | M |
| OpenAI Responses/Azure/Codex 新增 `openai_input_transformations` 诊断：列出被 API 丢弃的 reasoning 项及原因（`prefix_mismatch`、`organization_mismatch`；不记录 `turn_boundary`） | `03de604b8`；`openai-responses-shared.ts:831,850` | 诊断数据；coding-agent 转录提示 | M |
| Google：关闭 SDK 自动续写，`CONTINUATION` 结束为 `length`；自定义 fetch（Java 不适用） | `f72f799a4`；`google-generative-ai.ts:343,397`、`google-shared.ts:505` | 模型可见（stop reason） | S |
| lazy 鉴权期间被取消从 error 改为 `stopReason: aborted` | `f3a64af07`；`lazy.ts:80` | 模型可见 | S |
| 缓存预热策略从 ai 暴露；`refreshModelsAuth` 对内置 provider 跳过 OAuth 刷新，签出时告警不失败 | `c9552c7d8`、`6382d8706`；`cache-warming.ts`、`models.ts:204,648` | 内部／UI 可见 | M |
| 模型与 Provider 数据：GPT-6.2 Astra/Sol、qwen4-max-preview、Qwen Token Plan 新网关与 Individual CN、Fable 5.2、GLM 5.3、DeepSeek v4.1、默认 Anthropic Opus 5.5、OpenAI/Azure/Codex 默认 GPT-6.2 Sol、models.dev v2 目录 | `e56d35ce5`、`139707002`、`3e4f87265`、`b4afdddf4`；`models.generated.ts`、`providers/qwen*.ts`、`env-api-keys.ts:81-95` | 模型可见（数据/默认值） | M |
| 流式工具参数改为增量解析；OpenRouter 输出上限按输入 15% 留白、接近满窗时最多 32K | `9265`（1.5.0）、`0a4fad548`；`simple-options.ts:18-31` | 内部／wire | M |

### L2 MCP

| 变化 | 证据 | 可见性 | 规模 |
|---|---|---|---|
| 新增 `PipeTransport`：对调用方启动的程序做 MCP stdio 帧，与 `StdioTransport` 共用行读取器 | `c158fa2ee`；`transports/pipe.ts:38`、`lines.ts:19` | 内部（传输能力） | L |
| stdio 健壮性：大消息线性读取；按 `close` 而非 `exit` 清理进程组；POSIX 下服务器已退出也走 SIGTERM | `42849bb84`、`f6278a472`；`stdio.ts:115,161` | 内部（孤儿进程修复） | M |
| Streamable HTTP：`application/json` 也执行 `maxMessageBytes`；超限取消 body；接受 CR-only/拆分行尾；HTTP 错误体只读前 8 KiB | `4a8b60bc3`、`f40984747`、`b64e6badd`；`streamable-http.ts:309-312,131,86-130` | 模型可见（超限报错） | M |
| OAuth：回调服务器关闭时 `closeAllConnections()`；`refreshOnly` 不重复注册；重定向 URI 校验；刷新锁等待可被 abort 取消 | `63ea042ce`、`797e122cb`；`oauth/callback.ts:115`、`flow.ts:96,101,387,396,453` | 内部（签入体验） | M |
| 共享助手从 coding-agent 搬到 pi-mcp：工具命名、input schema 归一、20KB 输出上限、配置校验；OAuth 登录拆成 `startMcpSignIn`／`completeMcpSignIn` | `76c040a2a`、`bb266bf35`；`naming.ts:18,33,41`、`input-schema.ts:7`、`output-limit.ts:9,28`、`config.ts:113,241` | 多为代码搬移；行为不变 | M |
| `pi mcp list` 发送 provider 令牌并按 `/login <provider>` 提示 | `4d72d05bc`；`runtime.ts:173-200` | CLI 可见 | S |

### L3 agent-core

| 变化 | 证据 | 可见性 | 规模 |
|---|---|---|---|
| 工具参数类型从 TypeBox 改为任意 JSON Schema／pi-schema `Static`；移除 `typebox` 依赖 | `6536f8012`、`6291b9bcc`；`types.ts:469,475,485,497` | 扩展作者可见（Java 侧只是泛型形状） | M |
| **Esc 中止批量工具调用时，未开始的调用也逐个补 `Operation aborted` 结果与事件** | `4438c58ba`；`agent-loop.ts:713-726` | **模型可见**（转录） | S |
| `streamProxy()` 透传 `disableContinuation` | `a758f3ae4`；`proxy.ts:55,99` | 模型可见（请求行为） | S |

### L4 session

`packages/durable` 出分母，L4 没有来自新包的行为变化。会话相关变更都落在 `coding-agent`，归 L5 计分，包括：会话目录消失后的 Continue/Fork/Cancel、thinking level 写分支、分支摘要应用 context edits、失败写入回滚（旧锚点已对齐）。L4 重测以「单元归属复核」为主，预计无独立代码包。

### L5 coding-agent

| 主题 | 变化 | 证据 | 可见性 | 规模 |
|---|---|---|---|---|
| 工具语义 | `find` 路径模式锚定搜索目录，`./` 前缀忽略，Windows 分隔符与 fd 版本一致，`limit` 边界修正 | `282b01c80`、`bbfc6c7ce`、`463b8f893`、`676af1d77`；`find.ts:27-29,205-250` | 模型可见 | M |
| 工具语义 | `grep` 的 `glob` 相对 `path`；上下文行改由 ripgrep 提供，不再读整文件；`readFile` 钩子废弃 | `6bf2ab9bb`、`8da141c2a`、`244e5f5db`；`grep.ts:26,61-63,167-172` | 模型可见 | M |
| 工具语义 | `read` 流式读取，只保留展示行；新增 `createReadStream` 可选操作；中止不再因未监听流崩溃 | `3a7bb6f48`、`ef9361ab7`；`read.ts:65,301` | 模型可见／扩展可见 | M |
| 工具语义 | `edit` 保留未改动行的行尾，写前算 diff，只围绕改动窗口 | `d448c51e2`；`edit-diff.ts`、`edit.ts` | 模型可见（结果） | M |
| 工具语义 | bash 超时后报告 shell 退出码/退出时间；超长尾行保留；`pi.exec` 信号退出记为失败；PowerShell 5.1 原生退出码 | `06aa6563e`、`935f0ac0c`、`a83b75a07`、`c9dd2c530`；`bash.ts:97-105,408-413`、`output-accumulator.ts:89-94` | 模型可见 | S |
| 提示文本 | **系统提示收紧**：更短的前言/工具/文档段，编辑指南合并 | `705d047cb`；`system-prompt.ts:156,160,168` | **模型可见** | S |
| 截断 | 不再切断代理对（emoji 等） | `9e870f6f0`；`tools/truncate.ts` | 模型可见 | S |
| 扩展 API | **破坏性**：组件 `render()` 返回只读 `string[]`，`Container` 缓存并复用数组；就地修改须先复制 | `b7bb7ca90`；`tui/src/tui.ts:130`、`docs/tui.md:24` | UI/扩展可见 | M |
| 扩展 API | `registerMarkdownTransformer({messageTypes})` 可进入 custom/summary/changelog 面 | `eebd5d90c`；`extensions/types.ts:1586` | UI 可见 | M |
| 扩展 API | 内联 `ctx.ui.custom()` 的 `blocked` 上报 OSC 7501；overlay 忽略该项 | `7f47a2ed0`；`extensions/types.ts:243-247` | UI 可见 | S |
| 会话/RPC/SDK | 替换会话时 `session_start` 只发一次；`--mode json` 也流式发出；reload 在 `bindExtensions({})` 后补发；替换诊断经 `takeUnreportedDiagnostics()` 上报；空闲 `ctx.shutdown()` 立即处理；进行中的 shutdown/SIGTERM 不被二次信号打断 | `beffcde92`、`6aab551a7`、`e03dca7a3`、`c47ad0432`、`1708b4788`、`ace560c30` | 扩展/宿主可见 | M |
| 会话/RPC/SDK | `/copy` 与 RPC `get_last_assistant_text` 跳过 aborted/error/toolUse/length 且无文本的回合 | `ede360e4d` | 用户/客户端可见 | S |
| 会话/RPC/SDK | 恢复会话时保回被扩展只隐藏自身工具而丢掉的 MCP 工具 | `3345c2925` | 模型可见（工具表） | S |
| 会话 | 原 cwd 消失时可 Continue/Fork/Cancel；Fork 改写 cwd 并生成新 ID；`/import` 残留副本修复 | `4ea883792`；`docs/sessions.md:26-27`、`core/session-cwd.ts` | 用户可见 | M |
| 会话 | 每个提示前把当前 thinking level 记录到新分支；模型回退在 resume 后和所有模式都上报 | `7860ff528`、`c8c456c85` | 用户可见 | S |
| CLI/认证 | 新增 `pi auth login/logout <provider>`：`--api-key`、`--no-browser`；Radius 登录同时配置 Radius MCP | `9b9cb30ae`；`cli/auth-login.ts`（277 行）、`auth-logout.ts`（72 行） | CLI 可见 | L |
| CLI/设置 | `cacheWarming` 默认从 `streaming` 改为 `idle`，最多保温 30 分钟；连续预热合并为一行 | `7bcc15c92`、`a013448fb`；`settings-defaults.ts:61`、`settings-schema.ts:414` | 用户可见（成本/UI） | S |
| CLI/设置 | `pi update` 报告每个包的 npm 版本或 git commit 变化；区分 unchanged/warned/offline；交互模式下捕获 git/npm 输出 | `342514bdf`、`806cdd68e`、`b513b64d8`、`e3da248ea`；`package-manager.ts:130-142,1311` | CLI/UI 可见 | M |
| CLI/设置 | llama.cpp 扩展默认端口从 8080 改为 **9931**（llama-server 当前默认） | `12001`（v1.6.0）；`extensions/llama/*`、`docs/llama-cpp.md` | 用户可见 | S |
| 转录/UI | OpenAI 丢弃 reasoning 项时显示转录提示，例如 `OpenAI dropped 2 reasoning items: prefix_mismatch (details in session)`；受 `showCacheMissNotices` 控制，且只在丢弃数比上一响应多时显示，提示带原因 | `03de604b8`；`interactive-mode.ts:485` | 用户可见 | S |
| UI | 扩展 `ctx.ui.pasteToEditor()`／`setEditorText()` 在非终端输入处理中调用（如命令处理器 `await` 之后）也会重绘编辑器 | `8c1afaae5`；`interactive-mode.ts` | UI 可见 | S |
| UI | 工具预览文本在浅色/深色主题下保持可读（语法高亮主题映射补一项） | `562da75f9`；`theme.ts` | UI 可见 | S |
| 选择器 UX | `/login`、`/logout`、`/scoped-models`、`/resume` 搜索高亮最佳匹配，左右/Home/End 保持选择；渐进加载不跳选；伙伴 Provider 置顶与品牌样式 | `37b74c546`、`5bcf370a5`、`a635f3901`、`683a7a868`；`oauth-selector.ts`、`session-selector.ts`、`preferred-providers.ts` | UI 可见 | M |
| 模型默认值 | Anthropic 默认 Opus 5.5；OpenAI/Azure/Codex 默认 GPT-6.2 Sol；一批同价或降价迁移 | `b425754a2`、`d84123b32`、`e56d35ce5`；`model-resolver.ts:21-58` | 模型可见（默认值） | S |
| 渲染 | Mermaid 支持饼图、作者样式、可点击标签，并进入摘要；HTML 注释隐藏；流式高亮/Markdown 块复用；新增 Dart/Nix/Groovy/Gradle 高亮 | `aceb1bd12`、`54280c4ff`、`0f21a3319`、`c13f6acfb`；`mermaid.ts`、`theme.ts:1008` | UI 可见 | M |
| 导出 | `/export` HTML 把 OSC 8 链接转为可点击 `<a>`，剥离其他转义 | `0e1e3fc81`；`export-html/ansi-to-html.ts` | UI 可见 | S |
| 安全 | glob 放大 DoS 防护；OAuth URL 只允许 `http:`/`https:`；登录 URL/终端标题控制字符清洗；`auth.json` 锁按真实路径共享 | `10947`、`11521` 系列、`11787`；`glob`、`login-dialog`、`auth-storage.ts` | 内部/安全 | S |

### L6 evals

`packages/evals` 只有 `package.json` 的版本与依赖更新，`src` 无行为变化。L6 重测预计零代码，只复核计分单元是否随 AI／coding-agent 语义变化。

### L7 TUI/web

| 变化 | 证据 | 可见性 | 规模 |
|---|---|---|---|
| **破坏性**：`Component.render()` 返回只读 `string[]`；`Container` 在子节点/行未变时复用缓存数组 | `b7bb7ca90`；`tui.ts:130,393,470`、`box.ts:90,100` | UI/扩展可见 | M |
| 新增 `TUI.setSuspendSignalHandler()`：只在 TUI 占有终端期间接管 SIGTSTP，停止时移除；外部 `kill -TSTP` 等同 Ctrl+Z | `3107a58d2`、`e4f50d76d`；`tui.ts:577,1099` | UI 可见 | S |
| 全屏滚动帧改为 O(viewport)：内容未变时复用 `LayoutFrame.renderCache` | `b7bb7ca90`；`tui.ts:613,1163`、`layout.ts:398` | UI 可见（性能） | L |
| Markdown 顶层块渲染缓存：流式只重排最后一块，代码高亮在 resize 后保留；主题切换须 `invalidate()` | `0f21a3319`；`markdown.ts:300,445` | UI 可见 | M |
| Markdown 隐藏 `<!--…-->` 注释并清理两侧空格；多行内联图片按硬换行分行 | `54280c4ff`、`5b4d7d3df`；`markdown.ts:201,212` | UI/内容可见 | S |
| overlay 与图片互斥：overlay 打开时裁掉相交图片、关闭后重绘；Kitty 图片最后绘制并跨全量重绘保留 | `787005a40`、`4622edb79`、`be2744d95`；`tui.ts:520`、`terminal-image.ts:246,276` | UI 可见 | M |
| 编辑器：斜杠命令只在命令名上下文按 Enter 提交，Tab 不再误提交；粘贴标记重编号保留短标记 | `9a7cfba47`、`a77207d9c`；`editor.ts:807,1433` | UI 可见 | S |
| 带搜索的设置列表：左右/Home/End 不再触发重新过滤和选中重置 | `5bcf370a5`；`settings-list.ts:253` | UI 可见 | S |
| 终端识别与渗漏：Alacritty 超链接按 `TERM`/`ALACRITTY_WINDOW_ID` 识别；APC 光标标记不再跨切片泄漏 | `2af8a42f5`、`25490ea49`；`terminal-image.ts:134`、`utils.ts:1440` | UI 可见 | S |
| ASCII 快路径与 `Text.setText()` 相同文本保留换行 | `b7bb7ca90`、`0f21a3319`；`utils.ts:174-280`、`text.ts:27` | UI 可见（性能） | S |

web 呈现层仍是 pi-java 独有，不计入 pi 分母。新 apps 不改变现有的 16 个 web 事件/线格式单元。

### L8 examples

两锚点间 examples 只有 18 个文件变化：

- `extensions/sandbox/` 整个删除（该件已按 D232 出分母）。
- `extensions/subagent/` 大幅重写（真子进程方案仍按 D244 出分母／降级）。
- `plan-mode/` 修改（已按 D237 出分母）。
- 新增 `examples/tsconfig.json`；其余是 lockfile 与 README。
- 注册表条数基本不变；不新增需要重开 W0–W8 的件。

## 4. 执行计划

> 每个 P 是一个可独立编译、可独立审核的工作包。每个提交控制在 200–500 行，按 `feat/fix(module): ...` 提交。每包先设计稿、用户审核、再写码（`docs/00` 流程）。

| 阶段 | 内容 | 依赖 | 产出／验收 |
|---|---|---|---|
| **P0 换锚重测** | 钉 `a00a5048d`；更新 `docs/map/ANCHOR.md` 的目标；按 L0–L8 逐层重测七份地图；登记新 B／D；确认 F1–F6 裁决。**只改文档。** | 用户批准本文 | 七层新读数；新分母与缺口清单；旧读数保留对照 |
| **P1 schema 语义（跨层）** | Java 侧实现 pi-schema 兼容的「强制转换＋校验＋错误模型」：点路径、`Validation failed for tool "..."`、悬空 `$ref` 本地拒绝、标准 format；`agent-core` 的 `ToolArgumentsValidator` 改为委托。 | P0 | 夹具覆盖 coercion 与错误文本；工具参数失败逐字对齐；L1/L3/L5 共用一套校验 |
| **P2 AI wire 一批** | 重试与错误原因（含 v1.6.0 的 525/网关/流内错误）、stop reason、`session-id`、指令角色（developer 仅 api.openai.com）、Codex developer item／`max_output_tokens`／`configuration_update`、Copilot strict/reasoning、Mistral strict opt-in、OpenAI compat 开关、token 上限、`openai_input_transformations` 诊断。 | P1 | 请求体与错误文本夹具 |
| **P3 AI 数据一批** | 模型目录与默认值：GPT-6.2 Astra/Sol、Qwen 网关/Individual CN/env 键、Fable 5.2、GLM 5.3、DeepSeek v4.1、Anthropic Opus 5.5、models.dev v2。 | P2 | vendor 目录重生成；默认模型断言 |
| **P4 Anthropic threads（可选延后）** | 单独设计 thread 创建/续接、前缀哈希、上限、回退、清理与 `disableContinuation`。 | P1、P2 | faux／夹具；真实 provider 冒烟另行安排 |
| **P5 coding-agent 工具语义** | `find`/`grep`/`read`/`edit`/`bash`/`pi.exec`/截断；`grep` 的 `readFile` 废弃与 `createReadStream`；`tool_search` 的 `resolveSchemaRef`（脱沙箱）。 | P1 | 工具输出与错误夹具；B287 operations 接缝复核 |
| **P6 coding-agent CLI/认证/设置** | `pi auth login/logout`、伙伴 Provider 排序、`cacheWarming=idle` 与合并提示、`pi update` 版本摘要、模型默认值接线。 | P5 | CLI 参数与进程退出；settings 默认值 |
| **P7 coding-agent 扩展/生命周期** | `render()` 只读契约、Markdown transformer 面、`blocked`、扩展更新日志、session_start/diagnostics/RPC shutdown、overlay 清理、系统提示收紧。 | P5、P8（TUI 契约） | 扩展夹具；模型提示逐字 |
| **P8 TUI** | render 缓存契约、SIGTSTP、全屏滚动 O(viewport)、Markdown 缓存/注释/图片、编辑器与选择器键位、终端识别。 | P7 契约先行 | TUI 单测＋字体/宽度回归 |
| **P9 MCP** | PipeTransport、stdio/HTTP 健壮性、OAuth 刷新与回调关闭、共享助手落位、`pi mcp list` 令牌。 | P1 | 传输夹具；OAuth 夹具 |
| **P10 agent-core/session** | 中止批量补结果、`streamProxy` 透传、L4 单元归属复核；无新增 L4 代码则只登记结论。 | P1 | 中止转录夹具 |
| **P11 telemetry/evals/examples** | `telemetryNow` 与 `renderTrace` 转义；L6 零代码复核；L8 只复核 18 个文件。 | P0 | 重测结论；无代码或仅窄差 |
| **P12 收尾** | 全仓 `mvn clean verify -Dfrontend.skip=true`；回填 `docs/04`／`docs/05`／各地图；切 `CURRENT_PI_PIN`；删除本计划稿。 | 全部 | 零失败、零告警；总分可逐行复算 |

**建议提交量**：约 22–30 个可独立编译提交。P1 与 P4 是仅有的两个 L 级包；其余以 S/M 为主。

**编号约定**：B288 已被 `docs/08` 的 codemode `prepareLoadout` 提案预占，本计划的新工单从 **B289** 起分配；新窄差从 **D274** 起。若 P0 重测发现某些项只是旧 D 的延续，则原位复核，不新开号。

**排序理由**：

1. 先做 P0，因为现有 95.2% 是按旧锚点算的，不先重测会把「新分母」和「旧缺口」混在一起。
2. P1 必须最先写码：它同时改 AI 和 agent 的工具调用路径，后续所有工具夹具都依赖它的错误文本。
3. P5 的 `find`／`grep`／`read` 是模型每轮都可能触发的工具，排在扩展 API 和 UI 之前。
4. TUI 放在 coding-agent 之后，因为 `render()` 只读契约横跨两层，先定 coding-agent 侧消费点再改 TUI 更省返工。
5. Anthropic threads 是唯一会改请求生命周期的 L 级功能，单独设计、允许延后。

## 5. 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| pi 移动快 | 20 天 818 个提交；本轮刚重新拉取就从 edfe6b2ef 前进到 a00a5048d；锚点一松就再次漂移 | P0 冻结 SHA；本轮期间不追 main；后续按版本另开换锚周期 |
| pi-schema 完整方言 | `$ref`、相对 `$id`、format、coercion 边角多；完整移植成本高 | 先做模型可见行为；用 pi 夹具逐条钉；Java 不移植构建器 API |
| Anthropic message threads | 服务端续接、前缀哈希、回退与清理改动请求生命周期 | 独立设计稿；faux 夹具＋真实 provider 冒烟；可延后 |
| TUI 框架差异 | Java 用 TamboUI；D255–D257 已登记键位、全屏、SGR 面差异；新 `render()` 契约映射不同 | P8 单独设计；只对可见行为计分，框架面按既有 D 项 |
| 旧 TUI 缺口被新差异掩盖 | 旧账面 73.4%，新锚点又加 API/性能项 | P0 先分列「旧缺口」与「新差异」；不混算 |
| 平台面 | Windows/macOS 的 fd、PowerShell、SIGTSTP、终端能力差异 | 沿用 D 项口径；只对可观察行为判定 |
| 验证成本 | 全仓 verify 约 34 分钟；新增夹具多 | 每包先跑模块 `mvn test -pl <module> -am`；收尾一次性全量 |

## 6. 完成定义

只有同时满足以下条件，才允许切锚点并发布新读数：

1. 七份地图均以 `a00a5048d` 重测，逐行有证据。
2. `docs/04` 的新 B／D 与 F 裁决已登记；`docs/05` 总表可由逐行权重复算。
3. `docs/map/ANCHOR.md` 的 `CURRENT_PI_PIN` 切到新 SHA。
4. 全仓 `mvn clean verify -Dfrontend.skip=true` 零错误、零警告、测试通过。
5. 本计划稿删除，历史进 git（`docs/00`／`docs/04` §7 的维护规则）。

## 7. 明确不做

- `chord`、`durable`、`env`、`codemode` 沙箱、`apps/p2`、`apps/p2-mac`、`apps/p3`：不实现、不进分母。
- apps 的 web/durable UI：不追踪；pi-java web 仍是独立实现，只保留 16 个线格式单元。
- TypeBox 构建器 API、Node 打包/lazy import、安装器与 standalone binary：不适用 Java。
- 发布 tag 之外的 Unreleased 变更若在本轮冻结后继续进入 main：不在本轮追。
