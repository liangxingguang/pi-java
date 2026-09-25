# 50 - 包 B87/B88：系统消息回读与 Responses 车道工具声明（设计）

**状态：设计待审核** —— 审核通过前不写任何实现代码（`docs/00 §3` 步骤 3、`docs/48 §4` 审核门）。
**基准：** pi-java `52eeab2`（包 A2 收尾）；pi `3390bd936`（工作树就在该提交上，`git status` 空）
**设计期实测：** B88 的四条机制探针已在本机跑过 —— **真出站体**逐字记在 §7.1；第五条（助手形态）记在 §7.2。探针是临时文件，跑完即删（`git status` 空）
**上游：** [`32-open-items-register.md`](32-open-items-register.md) **B87**（①②）/**B88**；[`49-ai-provider-transcript-migration-design.md`](49-ai-provider-transcript-migration-design.md) §10 L-A、§12.3 F7/F8
**下游/相邻：** A3（工具增删状态线的**生产者**）、A4（`sections` 删除语义）、A7（`Model.compat` 扩展）、`docs/48 §5` 的 A2 行回填

---

## 1. 范围

### 1.1 本包做什么

三件事，全是「登记在案的既有落差」，两处是**今天就能撞到**、一处是**边写边读的一对不对称**：

1. **B88（P0，ai）**：Responses 车道的工具声明**根本不发请求**。`ResponsesMessageConverter` 从不设 `strict`，而 OpenAI SDK 的 `FunctionTool.Builder.build()` 把它标成必填 ⇒ `IllegalStateException: \`strict\` is required, but was not set`（§7.1 实测复现，**桩服务器零请求**）。加 `strictField` 助手：按 pi 的车道缺省**省略**（openai-responses）或**发 `strict:false`**（azure-openai-responses）。
2. **B87①（P1，agent-core）**：`SessionJson.messageNode` 写得出的系统消息，`MessageJsonCodec.decode` 读不回来（`role:"system"` 落 `default → has unknown message role`）。补 `system` 分支 + 三个解码助手 ⇒ **写得出就读得回**。
3. **B87②（P1，agent-core/ai）**：`SessionJson` 把 `toolsAdded` 按 `ToolDefinition` 全形（**7 组件、字段名 `inputSchema`**）落线，而 pi 的 `toolsAdded` 是 ai 层 `Tool[]`（**三键 `{name, description, parameters}`**，`types.ts:600-605`）。把落线收敛到 pi 形状：抽 `Transcripts.toToolDeclaration`（pi `transcript.ts:123-130` 的位置）供 `SessionJson` 与 `PiMessagesApi` **两处共用**，读侧兼容 A1 的 7 键旧形。

**外加台账**：`docs/48 §5` 的 A2 行仍写「🟡 实施中（provider 消费迁移未开始）」——A2 已闭环（`docs/49 §12`），本包顺带回填为 🟢；`docs/32` B87/B88 行按完成情况改写。

### 1.2 本包不做什么

- **B89**（Anthropic 顶层 `system` 的字符串形态）：不是本包问题，且**必须与 A-01 `cache_control` 一并裁决**（缓存断点挂在块上）⇒ 保持登记不修。
- **B90**（缺 `eager_input_streaming`）：属 compat/线格编码（A-07/A-09 一类），与「工具声明从哪来」无关 ⇒ 保持登记不修。
- **`constrainedSampling` / `supportsStrictMode` 的完整移植**（pi 的 `makeStrictJsonSchema`、grammar 工具、`resolveJsonSchemaStrictSampling` 的 `require` 抛错支）：本包只落**缺省路径**（见 §9 R4、L-A）。
- **B87③**（`sections` 值 `null` = 删除）：形状变更归 A4（`docs/49 §9 R3①` 已裁）；本包只决定**读到它时怎么办**（§9 R1）。
- **B87④**（TUI/web 不认系统消息）：宿主面，与持久化/车道无关。
- **`SystemMessage.content` 的裸字符串形态**（§3 F6）：写侧恒数组，援引 `docs/37 §6-2` 对 `UserMessage` 的同类裁决（pi 两形态都收 ⇒ 不做），只登记。
- 不新增 / 不删除任何 Maven 依赖（`docs/48 §4` 审核门 8 无触发）。

---

## 2. 命题表（pi 侧，逐条已核，`file:line` 为准）

> 核验方式：`git show 3390bd936:<path>`（**不读工作树**，见 `pi-anchor-and-drift`）。带 ✅ 的是**设计期已实测**；带 ✅✅ 的是同时有 pi 自己的测试文件作 oracle。

| # | 命题 | 出处 | 核 |
|---|---|---|---|
| **P1** | openai-responses 车道的 compat 缺省是 **`supportsStrictMode ?? false`** | `openai-responses.ts:74`（`getCompat`） | ✅ |
| **P2** | **azure 车道的缺省相反**：`?? true`（两处：消息转换的 `toolOptions` 与 `params.tools`） | `azure-openai-responses.ts:296`、`:319` | ✅ |
| **P3** | `convertResponsesTools` 的落法：`defaultStrict = options?.strict ?? false`；`supportsStrictMode = options?.supportsStrictMode ?? true`（**helper 自身缺省 true**）；`strict = constrainedStrict ?? defaultStrict`；**`if (supportsStrictMode) functionTool.strict = strict;`** —— 即「不支持 ⇒ **整个键不发**」，支持 ⇒ 明确发 `true`/`false` | `openai-responses-shared.ts:359-395`（关键三行 `:391-393`） | ✅ |
| **P4** | pi 自己的 oracle ①：无约束工具 + `supportsStrictMode:false` ⇒ **`"strict" in tool === false`** | `constrained-sampling.test.ts:118-122` | ✅✅ |
| **P5** | pi 自己的 oracle ②：`constrainedSampling.strict="prefer"` ⇒ `strict: true`；`"require"` + 不支持 ⇒ **抛** `Tool "…" requires JSON-schema constrained sampling` | `constrained-sampling.test.ts:88-98` | ✅✅ |
| **P6** | pi 自己的 oracle ③：azure 车道在 `compat.supportsStrictMode:false` 时 `not.toHaveProperty("strict")` | `azure-openai-base-url.test.ts:190-209` | ✅✅ |
| **P7** | SDK 把 `strict` 标成**必填**：`checkRequired("strict", strict)`；消息由 `checkNotNull { "\`$name\` is required, but was not set" }` 产生 | `FunctionTool.kt:437`、`Check.kt:11-12` | ✅ |
| **P8** | SDK 的**省略**机制是公开 API：`JsonMissing` —— 「will cause a JSON field to be omitted from the serialized JSON entirely」；`JsonValue : JsonField<Nothing>` ⇒ 可赋给任意 `JsonField<T>` | `Values.kt:433-445`、`:262-275` | ✅ |
| **P9** | pi 的 `SystemMessage`：`content: string \| TextContent[]`、`sections?: Record<string, string \| null>`、`toolsAdded?: Tool[]`、`toolsRemoved?: ToolReference[]`、`timestamp` **必填** | `types.ts:491-507` | ✅ |
| **P10** | pi 的 `Tool` = `{name, description, parameters, constrainedSampling?}`；`ToolReference` = `{name}` | `types.ts:600-609` | ✅ |
| **P11** | 前导系统消息由 `createInitialSystemMessage` 造：`content: systemPrompt ?? ""`（**裸字符串**）、`timestamp: 0` | `transcript.ts:12-22` | ✅ |
| **P12** | pi 的 JSONL **回读无类型校验**：`codec.ts` 共 63 行，只校验 header/版本，消息与 entry 原样直通 ⇒ **pi 侧不存在「写得进、读不出」这类不对称** | `harness/session/jsonl/codec.ts`（全文） | ✅ |
| **P13** | `toToolDeclaration(tool) = {name, description, parameters(JSON 往返), ...constrainedSampling?}` —— pi 用它做「比较/持久化前剥掉可执行与展示字段」 | `transcript.ts:123-130` | ✅ |

---

## 3. java 侧现状与发现

| # | 事实 | 出处 | 备注 |
|---|---|---|---|
| **F1** | 工具声明循环：`FunctionTool.builder()…parameters(…)` —— **没有 `strict`**，`build()` 抛异常 | `ResponsesMessageConverter.java:73-85` | B88 的根因 |
| **F2** | 两个 responses 车道**共用**一个构建器（`buildParams`），而 pi 的两车道 compat 缺省**相反**（P1/P2）⇒ 本包必须按车道传参，不能一条 `if` 打发 | `OpenAIResponsesApi.java:54-55`、`AzureOpenAIResponsesApi.java:62-63`、`ResponsesMessageConverter.java:62-63` | 这是 pi 的事实，不是笔误（见 §9 R5） |
| **F3** | 写得出读不回：`SessionJson` 有完整的 system 分支（含三个可选字段的缺席规则），`MessageJsonCodec.decode` 的 switch 里**没有** `case "system"` ⇒ `default → "has unknown message role"` | `SessionJson.java:123-142`、`MessageJsonCodec.java:39-61`（`:60` 抛） | 「响亮失败」，同一 codec 也覆盖 `retainedTail`（`EntryJsonCodec.java:47`）与 `originalPrompt`（`RecordJsonCodec.java:96`） |
| **F4** | `toolsAdded` 落线是 **Jackson 对 record 的默认序列化**：7 个组件、JSON Schema 的键名是 `inputSchema` | `SessionJson.java:136-138`、`ToolDefinition.java:19-47` | 与 pi 的 P10 三键形状不符 |
| **F5** | 同仓**已有**一处按 pi 形状写的实现 —— `PiMessagesApi.toolDeclarations`（三键 + `parameters`），且它的 javadoc 自己就点名了「与 `SessionJson` 的 7 组件形状并存待收敛」 | `PiMessagesApi.java:226-244` | B87② 要收敛的就是这一对 |
| **F6** | java 没有 `constrainedSampling` / `supportsStrictMode` ⇒ P3 的取值集合在本仓**退化为**「省略 / `false`」两态（`prefer`/`require` 两支不可达） | `grep -rn "constrainedSampling" pi-java-ai/src/main` 零命中 | 见 §9 R4 |
| **F7** | `Message.SystemMessage.content` 恒为**块数组**（`List<ContentBlock>`），pi 的前导系统消息是**裸字符串**（P11）⇒ 持久化形状与 `PiMessagesApi` 的线格都差这一处 | `Message.java:52-65`、`PiMessagesApi.java:253-262` | 新登记（§10 L-B），援引 `docs/37 §6-2` 对 `UserMessage` 的同类裁决 |
| **F8** | A1 的测试类自己写明了「只做落线、不做回读，回读是后续设计包的活」 | `SessionJsonSystemMessageTest.java:27-30` | 本包就是那个包 |

---

## 4. 设计

### 4.1 步骤 B88（ai）：工具声明按车道发出 `strict` 或不发

`ResponsesMessageConverter.buildParams` 增一个形参 `boolean supportsStrictMode`（pi 的两车道 compat **缺省相反**，这是车道属性不是模型属性 —— 在 java 的 catalog 里也没有 `supportsStrictMode` 这个字段可用，见 §9 R4）。工具循环里：

```java
.strict(strictField(supportsStrictMode))
```

配一个私有助手，语义就是 pi 的 `:391-393`：

```java
/** pi openai-responses-shared.ts:391-393：不支持 ⇒ 整个键不发；支持 ⇒ 明确发 false。 */
private static JsonField<Boolean> strictField(boolean supportsStrictMode) {
    return supportsStrictMode ? JsonField.of(false) : JsonMissing.of();
}
```

- `OpenAIResponsesApi` 传 **`false`**（pi `openai-responses.ts:74` 的 `?? false`）⇒ 线上**没有 `strict` 键**（P4 的 oracle 同形）。
- `AzureOpenAIResponsesApi` 传 **`true`**（pi `azure-openai-responses.ts:296`/`:319` 的 `?? true`）⇒ 线上 `"strict":false`。
- 两处调用点都带**各自 pi 行号**的注释，免得下一个人以为是笔误（缺省相反是 pi 的事实，不是我的选择）。
- `JsonMissing.of()` 的用法要写清来路：它**不是**绕过 SDK 的技巧，而是 SDK 公开的「本字段缺席」表达（P8 的类文档明文）；**线格上的结果**（该键整个消失）是 §7.1 实测的，**SDK 侧的接线**（`FunctionTool.strict` 的 `@ExcludeMissing`）是读源码得知的 —— 注释里两者分开说，别把读来的当测到的（`docs/32 §10.7`）。

### 4.2 步骤 B87a（agent-core）：系统消息的回读

`MessageJsonCodec.decode` 的 switch 增一支（顺序放在 `"tool"` 之后、`default` 之前）：

```java
case "system" -> new Message.SystemMessage(
    content,
    decodeTimestamp(node.get("timestamp")),
    decodeSections(node.get("sections")),
    decodeToolsAdded(node.get("toolsAdded")),
    decodeToolReferences(node.get("toolsRemoved")));
```

四个字段的读法（每条都在实现里注明理由）：

| 字段 | 缺席 | 形状 | 说明 |
|---|---|---|---|
| `content` | — | 数组（复用 `decodeBlocks`） | 与 `UserMessage`/`ToolResultMessage` 同口径：**只收数组**（F7 的裸字符串形态只登记，见 L-B） |
| `timestamp` | `null` | number（epoch ms） | 复用既有 `decodeTimestamp`（与 assistant 一致；pi 必填、java 可为 null，写侧缺席即省略 ⇒ 读侧缺席即 null，round-trip 自洽） |
| `sections` | `Map.of()` | object of string | 用 `LinkedHashMap` 逐键读入**保序**（`orderedSections` 会再拷一次但保序）；值必须是字符串 —— **值是 `null` 的处置见 §9 R1** |
| `toolsAdded` | `List.of()` | array of object | `name` 必填、`description` 可缺（`ToolDefinition` 允许 null）；schema 取 **`parameters`**，**缺席时回落到 `inputSchema`**（A1 旧形）；两者都无 ⇒ schema 错。构造走三参便捷器（`label`=`name`、`promptGuidelines` 空、`renderShell` 默认）—— A1 的四个元数据键**刻意忽略**（见 L-F） |
| `toolsRemoved` | `List.of()` | array of `{name}` | `name` 必填 ⇒ `ToolReference` |

一处刻意的**不对称要写进注释**：读侧认**两种** `toolsAdded` 形状（pi 三键 / A1 七键），而写侧（步骤 B87b）只写 pi 形状 —— 因为读侧要能读旧文件，写侧只该有一种真相。

### 4.3 步骤 B87b（agent-core/ai）：`toolsAdded` 落线收敛

新增 `Transcripts.toToolDeclaration(ToolDefinition)`：返回 pi 的三键表（`LinkedHashMap`，键序 `name`/`description`/`parameters` 与 pi 的 `{name, description, parameters}` 一致）。它落在 `Transcripts` 是有意的 —— pi 的同名函数就在 `utils/transcript.ts`（P13），java 的 `Transcripts` 正是那个文件的移植点，A3 的 `getToolStateChanges`/`declarationsEqual` 也会加在这里。

两个调用点收敛：

- `SessionJson.messageNode`：`node.set("toolsAdded", MAPPER.valueToTree(system.toolsAdded().stream().map(Transcripts::toToolDeclaration).toList()))`
- `PiMessagesApi.toolDeclarations`：改用同一助手（删掉自己那份手写循环），线格不变。

⚠️ `agent-core` 引用 `com.pijava.ai.api.Transcripts` 是**向下**依赖（`ai ← agent`，`CLAUDE.md` 的依赖方向），没有环。

---

## 5. 步骤拆分与提交

| 步骤 | 模块 | 提交 | 内容 |
|---|---|---|---|
| **B88** | `pi-java-ai` | `fix(ai): stop the responses lanes from failing on tool declarations` | `strictField` + `buildParams` 形参 + 两个调用点 |
| **B87a** | `pi-java-agent-core` | `feat(agent-core): read system messages back from the session JSONL` | `case "system"` + 三个解码助手 |
| **B87b** | `pi-java-ai` ＋ `pi-java-agent-core` | `feat(ai): persist toolsAdded in pi's tool shape` | `Transcripts.toToolDeclaration` + 两个调用点收敛 |
| **文档** | `docs/` | `docs: record the B87/B88 package and backfill the A2 row` | 本文 §12 ＋ `docs/48 §5` ＋ `docs/32` ＋ `docs/41:57-58` |

每个步骤**独立可编译、独立可测**，互不阻塞（B87a 与 B87b 顺序可换：读侧两种形状都认）。

---

## 6. 先红矩阵与变异探针

### 6.1 先红（今天就能拿到，不需要 stash 手法）

| 步骤 | 夹具 | 今天的红 |
|---|---|---|
| B88 | 新 `ResponsesToolsStrictWireTest`（真出站体，`RecordingHttpServer`）：openai 车道「`tools[0]` 无 `strict` 键」、azure 车道「`strict` 恰好是 `false`」 | 两条都**今天必红**，且不是断言失败而是 `IllegalStateException`（桩零请求）—— 失败信息要连异常一起断言，写清「不是本夹具的期望错了」 |
| B87a | 新 `MessageJsonCodecSystemReadbackTest`：`SessionJson.messageNode` 造节点 → `MessageJsonCodec.decode` 回读 → 逐字段比对（含 `sections` 保序、`toolsAdded` 两种形状） | 今天必红：`has unknown message role` |
| B87b | `SessionJsonSystemMessageTest` 增一条：`toolsAdded[0]` 的**键集**恰为 `{name, description, parameters}` | 今天必红：键集是 7 个 |

### 6.2 变异探针（红集必须精确）

| 变异 | 期望红集 |
|---|---|
| **M1** `strictField` 恒返回 `JsonMissing.of()`（azure 也省略） | 恰 1 红（azure 一条） |
| **M2** openai 车道也传 `true` | 恰 1 红（openai 一条） |
| **M3** 回读时不读 `sections` | 恰 1 红（round-trip 的 sections 断言） |
| **M4** `toolsAdded` 只认 `parameters`（去掉 `inputSchema` 兜底） | 恰 1 红（A1 旧形那条） |
| **M5** `toToolDeclaration` 用 `inputSchema` 作键名 | 恰 2 红（`SessionJsonSystemMessageTest` ＋ PiMessages 线格各一） |
| **M6** `toToolDeclaration` 多带 `label` | 恰 2 红（同上两条） |

---

## 7. 设计期实测记录

### 7.1 B88：四条机制探针（**真出站体**，`RecordingHttpServer`）

观测方式与 A2 相同：不读源码下结论，把参数交给**真客户端**打本机桩，录到的就是请求字节。

| 写法 | 出站 `tools[0]` | 判定 |
|---|---|---|
| **现状**（不设 `strict`） | —— `HttpServer` **零请求**；`FunctionTool.Builder.build()` 抛 `IllegalStateException: \`strict\` is required, but was not set` | 硬故障复现 ✅ |
| `.strict(false)` | `{"name":"lookup","parameters":{"type":"object"},"strict":false,"type":"function","description":"d"}` | 多一个 pi 默认路径**不发**的键 |
| **`.strict(JsonMissing.of())`** | `{"name":"lookup","parameters":{"type":"object"},"type":"function","description":"d"}` | **与 pi 同形**（P3/P4），且留在类型模型内 |
| `putAdditionalBodyProperty("tools", …)` | `{"type":"function","name":"lookup","description":"…","parameters":{…}}` | 同形，但绕过 SDK 的类型模型（A2 的 `betas` 注入是「补 SDK 没有的键」，这里 SDK 有该键 ⇒ 用不上的重锤） |

> ⚠️ 观测面校准：**SDK 自己的 Jackson mapper 不是出站面**（`ObjectMappers.jsonMapper().writeValueAsString(params)` 对 `ResponseCreateParams` 打出 `{}`）。第一次探针就是踩了这个 —— 所有变体都"通过"了。凡「某 SDK 的默认行为」，**先把实测值打出来**（`docs/43 §9` 的同类教训再现）。

### 7.2 助手形态探针

`strictField(boolean)` 这个**三元表达式返回 `JsonField<Boolean>`** 的写法实测可编译、语义正确：`Optional.empty`（缺席）/ `Optional[false]`（在场）。**不给裁决留下未验的代码形状。**

### 7.3 pi 侧 oracle

本包**没有新造 pi 探针** —— 需要的三条期望值 pi 自己就有：P4（`constrained-sampling.test.ts:118-122`）、P5（`:88-98`）、P6（`azure-openai-base-url.test.ts:190-209`）。它们的输入形状（`supportsStrictMode` 的显式值、`constrainedSampling`）与本仓的退化取值不同，引用时**只取「键在不在」这半条**。

---

## 8. 验收门槛

1. `mvn -pl pi-java-ai,pi-java-agent-core -am clean test` 全绿；新增用例数 ≥ 3 个夹具（§6.1）。
2. 全 reactor `mvn clean verify` 绿（`docs/48 §10.4` 的环境 flake 若复现，按那里的办法复核）。
3. 回归计数：`ai` 849 → ≥ 852、`agent-core` 484 → ≥ 488（收尾时把实测值写进 §12）。
4. checkstyle 0 新违规（注意 `severity=warning` ⇒ **退出码 0 不代表干净**，看 XML）；无残留 `System.out.println`；改动文件均 ≤ 500 行。
5. `grep -rn "JsonMissing" pi-java-ai/src/main` 的落点只有 `strictField` 一处（防止机制被复制成习惯用法）。
6. 两条 grep 门：`grep -rn "has unknown message role" pi-java-*` 只剩**不可达的** default 兜底；`grep -rn "inputSchema" pi-java-agent-core/src/main` 在 `SessionJson`/解码器里只剩**读侧兼容**那一条。

---

## 9. 裁决点（请审核时给结论）

| # | 问题 | 我的建议 | 备选 |
|---|---|---|---|
| **R1** | 回读到 `sections` 的**值 `null`**（pi 的「删除具名段」语义）怎么办 | **抛 schema 错**（消息点名 pi 的删除语义 + 归 A4）。理由：`Map<String,String>` 表达不了删除，静默丢键会**静默改变 prompt**；而「响亮」在本包是**可以做到**的（写侧永远不产 null，见 `Message.java:79-89`） | 静默跳过键（❌ 静默错 prompt）／读成 `""`（❌ 把删除读成空段，改的是语义） |
| **R2** | `toolsAdded` 落线**是否在本包**收敛到 pi 三键 | **收敛**（+ 抽 `Transcripts.toToolDeclaration` 共享，读侧兼容 7 键旧形）。理由：F4/F5 是同仓自相矛盾，且 F5 的 javadoc 早已点名要一起收敛；不收敛的话「回读」读的仍是一个 pi 不认的形状 | 只改读侧、写侧不动（两处形状长期并存，B87② 继续挂账） |
| **R3** | B88 的机制三选一（§7.1 四条实测） | **`.strict(JsonMissing.of())`**：保住类型路径 + 给出 pi 的字节；SDK 文档明文支持 | `.strict(false)`（多一个键，且 OpenAI Responses 的 `strict` 缺省语义我**没有实测过**，不敢拿它当等价）／`putAdditionalBodyProperty`（绕过类型模型） |
| **R4** | 是否同时移植 `ModelCompat.supportsStrictMode` ＋ `constrainedSampling` | **不移植**，登记 L-A。理由：java 没有 `constrainedSampling`（F6）⇒ 取值恒 `false`，字段与分支都是**死支**；B2「`Model.compat` 字段、JSON 映射与 request consumers」才是它的家 | 现在就加字段（死支 + 目录侧无数据源） |
| **R5** | azure 车道是否照 pi 发 `strict:false`（两车道缺省相反，P2） | **照发**：判据是 pi 的行为，两侧缺省相反是 pi 的**事实**（`:296`/`:319` 对 `openai-responses.ts:74`）。备选方案会让 azure 侧与 pi 差一个键 | 两车道都省略（省一个键，azure 偏离 pi） |
| **R6** | 是否接受 §5 的**三**步拆分（B88 / B87a / B87b）与「读侧先行、写侧收口」的顺序 | 接受（每步独立可编译；读侧两种形状都认 ⇒ 顺序可换） | 一个原子提交（diff 跨两模块、超 500 行） |

---

## 10. 遗留登记（预计产出，实施后落 `docs/32`）

| 号 | 内容 | 处理 |
|---|---|---|
| **L-A** | **`constrainedSampling` / `supportsStrictMode` 未移植**：pi 的 `prefer`（发 `strict:true` + `makeStrictJsonSchema` 的 `additionalProperties:false`/全量 `required`）、`require`（不支持则**抛**）、grammar 工具（`type:"custom"` + lark/regex） | 新登记；归 B2（compat 字段）＋ 独立包。**B88 只落缺省路径** |
| **L-B** | **`SystemMessage.content` 的裸字符串形态**（pi `transcript.ts:15` 写 `systemPrompt ?? ""`，java 恒块数组；影响持久化形状与 `PiMessagesApi` 线格） | 新登记，**不做**：援引 `docs/37 §6-2` 对 `UserMessage` 的同类裁决（pi 两种形态都收 ⇒ 写数组不违反读法） |
| **L-C** | `sections` 值 `null` = 删除，表达不了（B87③） | 归 A4（`docs/49 §9 R3①`）；本包只定「读到就响亮抛」（R1） |
| **L-D** | TUI/web 不认系统消息（B87④，渲染成 `Unknown message role: system`） | 归宿主面，本包不碰 |
| **L-E** | **B89 / B90** 保持登记不修 | 理由见 §1.2（B89 与 A-01 一并裁决；B90 属 compat 线格编码） |
| **L-F** | 回读时**忽略** A1 四键（`label`/`promptSnippet`/`promptGuidelines`/`renderShell`） | 刻意：pi 的 `toolsAdded` 是 ai 层 `Tool`（P10），本就不带这些；持久化形状以 pi 为准（R2），元数据不回读是它的**语义**而非缺省 |

---

## 11. 本次设计的取证方式与已知不足

- pi 侧行号全部取自 `git show 3390bd936:<path>`（**不读工作树**，见 `pi-anchor-and-drift`）；pi-java 侧取自 `52eeab2`。
- **已实测**：§7.1 四条机制探针（含一条失败复现）、§7.2 助手形态探针。探针为临时文件，跑完已删，`git status` 空。
- **未实测**：
  1. **azure 的真实服务**是否接受 `strict:false`（pi 会发它，但 pi 的测试断言的是 mock 收到的参数 ⇒ 两侧都只有「pi 会发」这半条证据，没有「服务收得下」）。
  2. OpenAI Responses 的 `strict` **缺省语义**（是不是 `true`）。本包不需要它 —— 正因为不知道，R3 才选「逐字节复刻 pi 的省略」而不是「发 `false` 以为等价」。
  3. pi 的 vitest 本包**未跑**（oracle 是 pi 自己测试文件里的逐字断言，不是新探针）。
- 本文档不含实现代码（§4 的片段是**形状草稿**，其中 `strictField` 已在 §7.2 验过可编译），符合 `docs/00 §3` 步骤 3 与 `docs/32 §10.7` 的「设计先行、审核后才写码」。

---

## 12. 实施记录（2026-09-26）

**提交**：`47e7f57`（B88）· `9f86e8c`（B87a 系统消息回读）· `6275f13`（B87b `toolsAdded` 落线收敛）· 本文回填（docs）

### 12.1 裁决与执行

用户 2026-09-26 裁决「按照建议实施」⇒ §9 的 R1–R6 **全部按建议执行**：R1 读到 section 值为 `null` 时**响亮抛错**；R2 `toolsAdded` 落线**本包收敛**；R3 用 **`JsonMissing.of()`**（保住类型路径 ＋ 给出 pi 的字节）；R4 **不移植** `constrainedSampling`/`supportsStrictMode`（登记 L-A）；R5 azure 车道**照 pi 发** `strict:false`；R6 接受三步拆分。

**步序与 §5 一致**（B88 → B87a → B87b），**无需调整** —— 与包 A2 不同，本包三件事互不构成前置条件。

### 12.2 实测校正与实现偏离

1. **M3 的红集是 4，不是设计稿预测的 1**。变异体把 `decodeSections(node.get("sections"))` 整个换成 `Map.of()`，**校验也一起消失**（null section 的抛错来自同一个 helper）⇒ 两条负向用例失去抛错源。实测红集：`roundTripsAFullSystemMessage`、`roundTripPreservesSectionOrder`、`rejectsNullSectionValueInsteadOfDroppingTheKey`、`rejectsMalformedSystemFields` —— 一条变异让四条断言现形，牙齿比预测的多。
2. **M5/M6 的红集各 2 红（1 ＋ 1），与设计稿预测一致** —— M5（线格键名写成 `inputSchema`）与 M6（投影多带一个 `label`）都同时在**两个写者**的夹具上现形：`PiMessagesRequestShapeTest:68`/`:75`（ai 侧线格）与 `SessionJsonSystemMessageTest.toolsAddedIsWrittenInPiToolShape:95`（agent-core 侧落线）。这正是 B87② 收敛的意义 —— 形状漂移在哪一侧发生都会被抓到。
   ⚠️ 取证代价：**跨模块的两个类名不能放进同一轮 `-Dtest`**（见下条），M5/M6 因此各跑了两次（模块分开）。
3. **实现偏离设计一处**：`toToolDeclaration` 返回 `ToolDeclaration` **record**，不是 §4.3 草稿写的 `LinkedHashMap`。理由：① `Map.copyOf` 会打乱键序（A2 的 F3 同型），而返回裸 `LinkedHashMap` 又把不变性的责任推给调用方；② A3 的 `declarationsEqual` 需要一个可比较的类型。**线上字节不变**（键名与键集由两侧夹具钉住）。
4. **设计期没预见到的连带面**：`buildParams` 加一个形参，撞出**五处反射 invoke**（`OpenAIResponsesApiTest` ×2、`OpenAIResponsesImageContentTest`、`OpenAIResponsesSurrogateSanitizeTest`、`AzureOpenAIResponsesApiTest`），全部以**运行期 `NoSuchMethod`** 报出来（21 个测试错误），**编译期毫无提示**。已顺带改成**同包直调** —— 这批夹具与被测类同在 `com.pijava.ai.protocol`，反射本就是多余的。
5. **我自己写夹具时踩了「缺陷态恒真」**：`rejectsMalformedSystemFields` 初版每条只断言 `isInstanceOf(DecodeError.class)` —— 缺陷态抛的**也是** `DecodeError`（只是文案是 `has unknown message role`），于是它在先红那一轮里**绿着过去了**（与另两条红并排才看出来）。已改成每条都断言**消息里点名字段**。`docs/45 §10` 的 B84 同型教训在本包第二次兑现，这次是**写着夹具的人自己踩的**。
6. **取证手法（跨模块 `-Dtest`）**：`mvn -pl pi-java-agent-core -am -Dtest='A(agent-core 的类),B(ai 的类)'` 时**只有 ai 侧那个类跑** —— agent-core 侧静默 0 测试（实测三次：M5 两次、M6 一次；每轮都是 `Tests run: 2` 且只有 `PiMessagesRequestShapeTest` 的类行），而 `-Dsurefire.failIfNoSpecifiedTests=false` 让整轮 **BUILD SUCCESS**。⇒ **跨模块取证必须按模块分别跑**；同一模块内多类名的逗号列表是正常的（`-Dtest='X,Y'` 在 agent-core 内实测跑出 9＋5）。这条与「假绿」同族，记在这里免得下一个人拿一个静默 0 测试的绿灯当证据。

### 12.3 每步的证据

**B88（`47e7f57`）** — 先红：新夹具 2/2 失败，失败点是「请求必须真的发出去」那条断言，异常逐字为 `` `strict` is required, but was not set ``（**桩零请求**）—— 与 B88 的症状同形。变异：M1（`strictField` 恒 `JsonMissing`）⇒ 恰 1 红（azure）；M2（openai 车道传 `true`）⇒ 恰 1 红（openai）。

**B87a ＋ B87b（`9f86e8c`、`6275f13`）** — 先红（**两个实现同时 `git stash`**）：读侧 6 error `has unknown message role`（round-trip 与两种形状共 6 例）＋ 1 failure（null section 那条的文案不匹配）＋ 写侧 1 failure（`toolsAdded` 键集是 7 个）。两组红互不重叠，归因清楚。

### 12.4 门禁结果

- **focused 全绿**：`ResponsesToolsStrictWireTest` 2/2、`MessageJsonCodecSystemReadbackTest` 9/9、`SessionJsonSystemMessageTest` 5/5、`PiMessagesRequestShapeTest` 2/2（另 `OpenAIResponsesApiTest`／`AzureOpenAIResponsesApiTest`／`OpenAIResponsesImageContentTest`／`OpenAIResponsesSurrogateSanitizeTest` 随反射改直调一并复核）。
- **模块回归**：`pi-java-ai` 849 ⇒ **851**（新夹具 2 例，无其他增减）；`pi-java-agent-core` 484 ⇒ **494**（回读夹具 9 例 ＋ `SessionJsonSystemMessageTest` 的键集 1 例）。**全绿**。
- **全 reactor**：`mvn clean verify`（串行）**BUILD SUCCESS**，14/14 模块，**7:14**（TUI 3:19、Web 1:21）—— 未复现 `docs/48 §10.4` 的 TUI 临时 JSONL 环境失败。
- **静态门禁**：checkstyle **0 新违规**（ai 29 条、agent-core 9 条 warning 全是既有条目 —— 包括 `ResponsesMessageConverter:237` 那条 170 字符的 `inputMessage` 既有行）；无新增 `System.out.println`（全仓 17 处均为既有）；改动/新增文件最长 432 行（`ResponsesMessageConverter`，门限 500）；`git diff --check` clean。
- **§8 的六条一致性检查**：①～⑥ 全过（`JsonMissing` 的落点只有 `strictField` 一处；`has unknown message role` 只剩不可达的 default 兜底；`inputSchema` 在 agent-core 只剩读侧兼容那一行）。

### 12.5 遗留与未做

1. **L-A**（`constrainedSampling`/`supportsStrictMode`/grammar 工具）**仍未做** —— B88 只落缺省路径；它的家是 `docs/48` 的 B2 行（compat 字段）＋ 独立包。
2. **L-B…L-F 不变**（`SystemMessage.content` 裸字符串形态、`sections` 的 null 删除语义、TUI/web、B89/B90 保持登记、A1 四个元数据键不回读）。
3. **azure 真服务是否收得下 `strict:false` 仍未实测**（§11-2 的不确定性没变：两侧都只有「pi 会发」这半条证据）。
4. **本包未触及 `docs/49 §12.6-3` 的「四条非 Anthropic 车道的 pi 侧探针」** —— 那是 A2 的遗留，与本包无关。
