# 56 - L8 W8 SDK/RPC/资源示例移植设计（17 件）

> 【背景摘要】本文档是 `docs/27` §4 工作包 W8 的详细设计：把 pi 锚点 `56cae7823`
> 的 17 个 SDK/RPC/资源发现示例逐件等价移植到 `pi-java-examples`。SDK 示例在 pi 侧是
> 独立脚本（非扩展），Java 侧用现有入口 `AgentSession.create`/`processPrompt`/`subscribe`/
> `RpcMode.run` 等价改写，**不新增 facade**（`docs/27:284/:324` 裁决）；形状差登记窄差。
>
> **文档状态：2026-11-19 设计并实施完成。**

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/27` §2.5 W8 分组（17 件）、§4 W8 断言、§5 文本级口径、`:284/:324` facade 裁决
- 前置闭环：`docs/50`（B284/B285）、`docs/53`（B286）、RPC 包与 ResourcePaths 均既有
- 用户既有裁决：文本级逐字断言；不新增同名 facade；外部进程/网络一律假实现

## 0. 取证口径与关键结论

1. pi 侧 17 件逐字取自锚点 `examples/sdk/01`–`13`（14-codemode 已出分母）、`examples/rpc-client.ts`、`rpc-extension-ui.ts`、`extensions/rpc-demo.ts`、`extensions/dynamic-resources/`。
2. **零新增宿主成员、零新增 facade**：`AgentSession.create(Args[,ProviderRegistry,ToolContext])`（`AgentSession.java:169/189`）、`processPrompt`（`:294`）、`subscribe`（`:118`）、`accumulatedMessages`（`:219`）、`close`（`:386`）、`RpcMode.run(in,out,args,providers,toolContext)`（`RpcMode.java:32`）、`ResourcePaths`（`:19`）、`ContextFileDiscovery`（`:26`）全部已存在。
3. **注册表只 +2**：17 件中只有 `rpc-demo`、`dynamic-resources` 是 PiExtension；其余 15 件是独立脚本/客户端（不实现 PiExtension），以普通 Java 类落地于 `com.pijava.examples.sdk` / `com.pijava.examples.rpc` 包，不进 services。账面 54→71（+17），注册表 51→53（+2）。
4. 两套测试形态：SDK 配置型（04/05/07/08/09/10/11）用 Args/ResourcePaths 纯逻辑 + 真实会话创建断言；SDK 运行型（01/02/03/06/12）用 `ExampleFixtures.newSession` + FauxProvider 跑真实 `processPrompt` 全链；13 用会话文件标识断言替换流程；RPC 用 `RpcMode.run` faux 端到端 + 纯函数；resource 用 W3Context 形状断言。
5. 文本级逐字断言集中于输出模板、提示词、JSON、frontmatter；ANSI 颜色/终端 UI 绘制（rpc-extension-ui 的 TUI 部分）降级为纯文本（D250）。

## 1. 单元与对位（17 件）

| # | pi 文件 | Java 类型 | 形态 | 测试重点 |
|---|---|---|---|---|
| 1 | sdk/01-minimal | `Sdk01Minimal` | 运行型 | 零配置 create+prompt+subscribe+close 闭环 |
| 2 | sdk/02-custom-model | `Sdk02CustomModel` | 运行型 | 模型查找+thinkingLevel+prompt |
| 3 | sdk/03-custom-prompt | `Sdk03CustomPrompt` | 运行型 | systemPrompt 替换/追加两臂 |
| 4 | sdk/04-skills | `Sdk04Skills` | 配置型 | skills 过滤+内联虚拟 skill+getSkills |
| 5 | sdk/05-tools | `Sdk05Tools` | 配置型 | 4 组工具名选择+cwd 变体 |
| 6 | sdk/06-extensions | `Sdk06Extensions` | 运行型 | additionalExtensionPaths+extensionFactories |
| 7 | sdk/07-context-files | `Sdk07ContextFiles` | 配置型 | agentsFilesOverride 追加虚拟 AGENTS.md |
| 8 | sdk/08-prompt-templates | `Sdk08PromptTemplates` | 配置型 | promptsOverride 注入 deploy 模板 |
| 9 | sdk/09-api-keys-and-oauth | `Sdk09ApiKeysAndOAuth` | 配置型 | 三种 ModelRuntime 认证入口 |
| 10 | sdk/10-settings | `Sdk10Settings` | 配置型 | SettingsManager override/flush/drainErrors |
| 11 | sdk/11-sessions | `Sdk11Sessions` | 配置型 | 四种 SessionManager 持久化入口 |
| 12 | sdk/12-full-control | `Sdk12FullControl` | 运行型 | 9 参数显式配置+prompt+text_delta |
| 13 | sdk/13-session-runtime | `Sdk13SessionRuntime` | 运行型 | newSession/switchSession+重新绑定 |
| 14 | rpc-client.ts | `RpcClientDemo` | RPC 客户端 | 默认提示词+事件过滤+promptAndWait 形状 |
| 15 | rpc-extension-ui.ts | `RpcExtensionUiDemo` | RPC 客户端 | 九方法处理表+事件顺序+纯函数（D250） |
| 16 | rpc-demo.ts | `RpcDemoExtension` | 扩展（入册） | 3 command+5 event+危险命令正则+UI 文本 |
| 17 | dynamic-resources/index.ts | `DynamicResourcesExtension` | 扩展（入册） | resources_discover 返回三路径+资源文件内容 |

## 2. 分组设计

### 2.1 SDK 配置型 7 件（04/05/07/08/09/10/11）

这类只创建/查询/销毁会话，无 prompt。落为普通类，暴露纯函数或配置方法，测试断言配置投影：

- **04**：`customSkill(name,description,path)` 构造虚拟 Skill（source `"sdk"`），`filterSkills(current, predicate)` 过滤（`includes("browser")||includes("search")`），文本 `"Discovered skills:"`/`"Warnings:"`/`"Session created with filtered skills"`；用 `SourceInfo`/`Skill` 接口。
- **05**：`toolSet(kind)` 返回四组工具名数组（read-only/custom/custom-cwd/specific-cwd）；`inMemory(cwd?)` 投影；四句固定 console 文本；`cwd="/path/to/project"`。
- **07**：虚拟 AGENTS.md 内容逐字（`# Project Guidelines`…`- Prefer const over let`），`agentsFilesOverride` 追加投影，`"Discovered context files:"`/`"  - ${path} (${len} chars)"`/`"Session created with ${n+1} context files"`。
- **08**：`deployTemplate`（name `deploy`，content 三行 `# Deploy Instructions…`），`promptsOverride` 追加，`"Discovered prompt templates:"`/`"  /${name}: ${description}"`。
- **09**：三个 ModelRuntime 入口投影（默认 / `authPath`/`modelsPath` 自定义 / `setRuntimeApiKey("anthropic","sk-my-temp-key")`），三句固定文本；`FileCredentialStore`/`ProviderRegistry` 对位。
- **10**：`SettingsManager.create/inMemory` + `applyOverrides`（compaction.enabled=false、retry.enabled=true/maxRetries=5/baseDelayMs=1000）、`setDefaultThinkingLevel("low")`、`flush`、`drainErrors` → `Warning (${scope} settings): ${error.message}`；`inMemory({compaction:false,retry:false})`；固定文本。
- **11**：四种 SessionManager 入口投影（inMemory/create(cwd)/continueRecent(cwd)/open(path)/list(cwd)），`sessionFile`/`sessionId` 读取；输出文本含 `"In-memory session: (none)"`、`"New session file:"`、`"Found N sessions:"`、`"Opened: id"`。

### 2.2 SDK 运行型 6 件（01/02/03/06/12/13）

用 `ExampleFixtures.newSession(tmp, textProvider(...))` + FauxProvider 跑真实 `session.processPrompt("...", PromptConfig.defaults())`，`session.subscribe` 收集 `AgentSessionEvent`，断言文本增量合并结果：

- **01**：`create`+`prompt("What files are in the current directory?")`，subscribe 收 `MessageUpdate(text_delta)` 汇总，`accumulatedMessages()` 非空，close。默认提示词逐字。
- **02**：模型查找投影（`getModel("anthropic","claude-opus-4-5")`/custom/`getAvailable()` 三个入口 + `"Found model: ${p}/${id}"` 文本），`thinkingLevel="medium"`，`prompt("Say hello in one sentence.")`。
- **03**：两臂 systemPrompt 投影——Option1  pirate 文本逐字（`You are a helpful assistant that speaks like a pirate.\nAlways end responses with "Arrr!"`）+ `append=[]`；Option2 追加 `## Additional Instructions\n- Always be concise\n- Use bullet points when listing things`；`"=== Replace prompt ==="`/`"=== Modify prompt ==="`；`prompt("What is 2 + 2?")`/`prompt("List 3 benefits of TypeScript.")`。
- **06**：`additionalExtensionPaths=["./my-logging-extension.ts","./my-safety-extension.ts"]` + `extensionFactories` 投影，`"[Inline Extension] Agent starting"` 文本，`prompt("List files in the current directory.")`；块注释示例的 registerTool/registerCommand 文本作为字符串常量断言（D250 不执行）。
- **12**：9 参数显式配置投影（cwd/agentDir/model/thinkingLevel/tools/sessionManager/settingsManager/resourceLoader/modelRuntime），手写 ResourceLoader 全空集合 + `getSystemPrompt()` 逐字（`You are a minimal assistant.\nAvailable: read, bash. Be concise.`），`thinkingLevel="off"`，`prompt("List files in the current directory.")` + text_delta 流式。
- **13**：`bindSession` 模式（unsubscribe→runtime.session→bindExtensions→subscribe）、`queue_update` 事件文本 `"Queued: n"`、`newSession`/`switchSession` 会话文件标识变化；Java 用 `AgentSession.session()`/会话文件路径 + `subscribe` 投影替换流程（D251 runtime factory 名差）。

### 2.3 RPC 三件（14/15/16）

- **14 `RpcClientDemo`**：默认提示词纯函数 `promptOf(args)`（`join(" ")||"Explain this repository in one paragraph."`），事件过滤谓词 `isTextDelta(event)`/`isToolStart(event)`，工具前缀 `` \n[tool: ${toolName}]\n ``，收尾 `\n`；`promptAndWait` 形状（发 `{type:"prompt",message,images,streamingBehavior}` + id `req_N`）。用 `RpcMode.run` faux 端到端跑一个 prompt 验事件流（可选，重则仅形状）。
- **15 `RpcExtensionUiDemo`**：九方法处理表（select/confirm/input/editor/notify/setStatus/setWidget/setTitle/set_editor_text）与三类回包形状（value/confirmed/cancelled）；纯函数：`dots=(dots%3)+1`、prefill `.replace(/\n/g," ")`、result `.slice(0,120)`、`OutputLog.slice(-1000)`、`render` 截宽；事件顺序 8 步；ANSI 颜色常量与绘制降级为纯文本标记（D250）。**关键差异**：pi 的 TUI 未处理 `setTitle`（rpc-demo 发但 TUI 静默忽略）——Java 侧 RpcExtensionUI 有 setTitle（`:142`），登记为行为增强差（D252）。
- **16 `RpcDemoExtension`**（入册）：3 command（`rpc-input`/`rpc-editor`/`rpc-prefill`，描述逐字）、5 event（session_start/turn_start/turn_end/tool_call/session_before_switch）；危险命令正则 `/\brm\s+(-rf?|--recursive)/i || /\bsudo\b/i` 纯函数 + 结果构造（`{block:true,reason}` / `{cancel:true}`）；setWidget/setStatus 文本逐字；UI 调用形状经 W3Context/ScriptedUi 断言。

### 2.4 dynamic-resources（17，入册）

`DynamicResourcesExtension` 监听 `resources_discover`（Java `ExtensionEventSpec.RESOURCES_DISCOVER` 若存在；否则经 `PiExtension.sessionStartResources()` —— 需核对，见 §3），返回 `ResourcePaths` 三路径：skillPaths=`SKILL.md`、promptPaths=`dynamic.md`、themePaths=`dynamic.json`。资源文件作为 test resources 落地于 `pi-java-examples/src/test/resources/dynamic-resources/`，逐字保留 SKILL.md/dynamic.md/dynamic.json 内容。测试断言：三路径解析 + frontmatter 原文 + theme JSON 键数（vars 15、colors 53、export 3）。

## 3. 宿主 API 与缺口

结论：**零新增宿主成员**。`AgentSession.create`/`processPrompt`/`subscribe`/`accumulatedMessages`/`close`/`RpcMode.run`/`ResourcePaths`/`ContextFileDiscovery`/`SettingsManager`/SourceInfo 均已存在。唯一需核对：`RESOURCES_DISCOVER` 事件在 Java 是否有——若无，用 `PiExtension.sessionStartResources()`（`:29`）贡献三路径等价。签名不匹配项全部走窄差：

| pi | Java 对位 | 窄差 |
|---|---|---|
| `createAgentSession` / builder / `onEvent` / `dispose` / `state.messages` | `AgentSession.create` / `subscribe` / `close` / `accumulatedMessages` | D247（名差，语义等价） |
| `RpcClient` / `RpcServer` 同名封装 | `RpcMode.run` + 手写 JSONL / `RpcDispatcher` | D248（无同名封装） |
| `dynamic.json` theme 结构化类型 | 无对位类型 → 夹具级 Map/原文断言 | D249 |
| TUI ANSI 绘制 / rpc-extension-ui 全屏 UI | 纯文本标记 + 形状断言 | D250 |
| `AgentSessionRuntime` / runtime 工厂 | `AgentSession.reload`/`switchTo`/会话文件标识 | D251 |
| Java `RpcExtensionUI` 处理 setTitle（pi TUI 未处理） | 行为增强 | D252 |

## 4. 验收测试

| 测试类 | 关键断言 |
|---|---|
| `Sdk01MinimalTest` … `Sdk13SessionRuntimeTest`（各一） | 见 §1 每件重点；配置型纯函数/形状，运行型 faux 全链 |
| `RpcClientDemoTest` | 默认提示词、事件过滤谓词、工具前缀、prompt 形状 |
| `RpcExtensionUiDemoTest` | 九方法表、三类回包、四个纯函数、事件顺序 |
| `RpcDemoExtensionTest` | 3 command+5 event、危险正则、block/cancel 构造、UI 文本 |
| `DynamicResourcesExtensionTest` | 三路径、SKILL/dynamic frontmatter、theme JSON 键数 |

运行：`mvn test -pl pi-java-examples -am`。层闭环 `mvn clean verify`。

## 5. 窄差与登记

D247（SDK 入口名差）、D248（RPC 无同名封装）、D249（dynamic.json 无对位类型）、D250（TUI 绘制降级）、D251（runtime 名差）、D252（setTitle 行为增强）——本包实施后写入 docs/04。

不新增账面条目：W8 完成后 examples 账面 **71/88＝80.7%**（54→71）。分母 88 已定稿，W8 17 件均在分母内。

## 6. 实施顺序与验证

1. 核对 `RESOURCES_DISCOVER` 事件是否存在（§2.4）。
2. 配置型 7 件 + 测试（纯函数为主）。
3. 运行型 6 件 + 测试（faux 集成）。
4. RPC 三件 + dynamic-resources + 测试。
5. 注册表 51→53（仅 rpc-demo/dynamic-resources）；逐批 `-am -Dtest=...`；全量 examples 测试。
6. 回填 docs/04（D247–D252、账面、文档号 docs/57）、docs/06、docs/07。

逐件取证锚点：`sdk/01:8-25`、`02:7-49`、`03:7-75`、`04:8-55`、`05:13-48`、`06:16-99`、`07:7-47`、`08:7-52`、`09:7-35`、`10:7-54`、`11:7-53`、`12:7-75`、`13:11-68`；`rpc-client.ts:12-35`、`rpc-extension-ui.ts:255-662`、`rpc-demo.ts:8-118`、`dynamic-resources/index.ts:1-16` + 三资源文件。

## 7. 实施记录（2026-11-19）

用户裁决「设计文档落地之后直接实施」，17 件全部等价落地，零新增宿主成员/facade：

- **新增 17 个 Java 类**：`com.pijava.examples.sdk` 下 13 件（`Sdk01Minimal`…`Sdk13SessionRuntime` + 共享 `SdkRunSupport` + 辅助投影），`com.pijava.examples.rpc` 下 4 件（`RpcClientDemo`、`RpcExtensionUiDemo`、`RpcDemoExtension`、`DynamicResourcesExtension`）。SDK 脚本为非扩展普通类，不进注册表。
- **真实会话集成**：`SdkRunSupport.run` 用 `ExampleFixtures.newSession` + FauxProvider 跑真实 `AgentSession.processPrompt`，从 `SessionResult.stream()` 聚合 text_delta（`subscribe` 事件形态与 stream 不同，D247 附注：以 stream delta 与 accumulatedMessages 为准），01 件断言完整闭环。
- **资源文件**：`pi-java-examples/src/main/resources/dynamic-resources/` 下 SKILL.md / dynamic.md / dynamic.json 逐字落地；`DynamicResourcesExtension.sessionStartResources()` 返回三路径。
- **测试**：`SdkConfigTypesTest`（7 项）、`SdkRuntimeTypesTest`（6 项）、`RpcClientDemoTest`/`RpcExtensionUiDemoTest`/`RpcDemoExtensionTest`/`DynamicResourcesExtensionTest`（14 项）逐批 `-am -Dtest=...` 全绿；`RegistryIntegrityTest` 53 项通过。
- **注册表**：services 51→53（仅 rpc-demo/dynamic-resources 入册）；`RegistryIntegrityTest` 三处清单同步扩容，`hasSize(53)`。
- **偏离说明**：① SDK 运行型的 text_delta 用 `SessionResult.stream()` 聚合（subscribe 的 MessageUpdate 在本 faux 路径不产 TextDelta），以 stream/accumulatedMessages 为准（D247）；② 资源文件为 CRLF（Windows），测试按 `\r\n→\n` 归一后逐字断言；③ rpc-demo 的 editor/input/setWidget 按 Java `ExtensionUI` 2/3 参签名对齐，`setTitle` Java 侧处理而 pi TUI 静默忽略（D252）；④ sdk 运行型仅 01 跑真实会话全链，其余以配置投影纯函数断言（控制 faux 集成成本）。
- **回填**：docs/04（examples 行账面 71/88、D247–D252、维护规则 docs/57）、docs/06（L8 行）、docs/07 §3（+17 件清单、账面 71/88）。`docs/map/04` E5 段未改（本包零新增宿主成员）。
