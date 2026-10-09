# 45 Anthropic 中段工具改为「定义内联」（`B165`）

> **状态：✅ 已闭环（2026-10-10）。**
> 承接 `docs/41 §6` 的推进次序 **③**（`docs/41 §1.1` w2 最后一条）。
> 与 ①（`docs/43`）、②（`docs/44`）**无耦合** —— 本包只动 anthropic 一条车道的两个文件。
>
> **一句话**：pi 在 `b271b0a52` 把原生工具增删的 beta 从
> `mid-conversation-tool-changes-2026-07-01` 换成 `inline-tools-2026-09-15`，
> 后续工具改为**按值内联**在 `tool_addition` 块的 `tool_definition` 里，
> 顶层工具表**从此不再增长**，且**同名重定义**可以表达了。
> Java 停在旧形状（按名引用 `tool_reference` + 顶层表只增不减 + 重定义一律回退）。
>
> **本稿只补这一条车道的这一个形状**，不顺手做别的。

---

## 0. 一句话

三个可观察变化，全在出站请求体上：

| # | 旧（Java 现状） | 新（pi 锚点） |
|---|---|---|
| **①** | beta 头 `mid-conversation-tool-changes-2026-07-01` | beta 头 `inline-tools-2026-09-15` |
| **②** | 顶层 `tools` = 初始 ＋ 占位符 ＋ **后续工具（deferred）** | 顶层 `tools` = 初始 ＋ 占位符，**到头为止** |
| **③** | `tool_addition` 块 = `{type:"tool_addition", tool:{type:"tool_reference", name}}` | `{type:"tool_addition", tool:{type:"tool_definition", definition:{…}}}` |
| **④** | 同名重定义 ⇒ **关掉整个原生支**（回退完整当前表） | 同名重定义 ⇒ **原生支照走**，重定义即一条 `tool_addition`，且**不发** `tool_removal` |

④ 是这次换形状的**动机**（pi 提交信息逐字：*"same-name redefinitions no longer fall back to resending the full tool list"*）。

---

## 1. 缺口是什么

`docs/41 §1.1` 的 w2 行（逐字）：

> | ✅ Anthropic 中段工具内联（`B165`） | `api/anthropic-messages.ts:195` `INLINE_TOOLS_BETA = "inline-tools-2026-09-15"`；`:1346-1351` 发 `tool_addition`／`tool_removal`，工具**按值内联**在 `{type:"tool_definition", definition}` 里 | `protocol/AnthropicRequestBuilder.java` 有 `defer_loading`（:236）、`DEFERRED_TOOL_PLACEHOLDER`、`tool_reference`（:483） | **旧形状**：`tool_reference`（按名引用）表达不了 pi 的**同名重定义**（pi 注释 `:1137` 明写这是新形状的动机） |

### 1.1 为什么要紧

**③④ 不是「换个写法」**，是两个行为后果：

- **③ 的内联定义带 `input_schema`。** 旧形状下，迟到的工具只在顶层表里有一份**完整**声明（`defer_loading:"" true`），`tool_addition` 块只靠名字引用它。新形状下顶层表**不再包含后续工具**，`tool_addition` 块**自己**必须带完整定义 —— 少了 `input_schema`，模型根本看不到这个工具的参数形状。**这是本包最容易做成「能跑但模型用不了」的地方。**
- **④ 让重定义不再关整个原生支。** Java 现在的四条件门含 `!Transcripts.hasToolRedefinitions(…)`；pi 在 `b271b0a52` 把这一项**删了**。不跟，则凡历史里出现过同名重定义的会话，一律退回「发完整当前工具表」——**每轮都把整个工具表重发**，缓存前缀失效。

### 1.2 ⚠️ 先纠一处：`hasToolRedefinitions` 在 pi 侧**没被删**，是被**弃用**

`docs/41` 与 `docs/04` 的 `B165` 行都只说「Java 停在旧形状」。实测 pi 锚点还有这个函数：

```
$ git grep -n "hasToolRedefinitions" 200387122 -- packages/ai/src/
packages/ai/src/utils/transcript.ts:186:export function hasToolRedefinitions(...)
```

**零生产调用点**（`src/` 下只有定义），只剩弃用注释与测试：

```
$ git grep -n "hasToolRedefinitions" 200387122 -- packages/ai/
packages/ai/CHANGELOG.md:20:- Deprecated `hasToolRedefinitions()`; no built-in transport needs it anymore.
packages/ai/src/utils/transcript.ts:186:export function hasToolRedefinitions(messages: TranscriptMessages): boolean {
packages/ai/test/system-message-replay.test.ts:128:		expect(hasToolRedefinitions(transcript.messages)).toBe(false);
packages/ai/test/system-message-replay.test.ts:143:		expect(hasToolRedefinitions(redeclared.messages)).toBe(true);
```

⇒ 裁决见 §7：**生产调用点删掉**（`AnthropicRequestBuilder` 那唯一的读点），**函数本体保留并加弃用注释**（与 pi 同形，且 `Transcripts.hasToolRedefinitions` 在 `OpenAICompletionsApi` 等侧仍有同名语义的读点族 —— 见 §4）。

---

## 2. 逐条变更（带 pi 逐字代码）

pi 侧的改动集中在一个提交：`b271b0a52 feat(ai): define Anthropic mid-conversation tools inline`（2026-10-02，`@anthropic-ai/sdk` 0.119→**0.129.0**）。
下面每段都是 `git show 200387122:packages/ai/src/api/anthropic-messages.ts` 的**锚点原文**。

### 2.1 pi 侧三个文件（逐字）

#### (a) beta 常量 —— `:195`

```ts
const INLINE_TOOLS_BETA = "inline-tools-2026-09-15";
```

（旧值 `MID_CONVERSATION_TOOL_CHANGES_BETA = "mid-conversation-tool-changes-2026-07-01"` 已删除。`MID_CONVERSATION_OUTPUT_CONFIG_BETA`（`:193`）是**另一个** beta，与工具无关，**不动**。）

#### (b) beta 装配 —— `:1120`

```ts
	if (nativeToolChanges) features.push(INLINE_TOOLS_BETA);
```

#### (c) 门的判据 —— `:1136-1141`（**这一处是 ④ 的核心**）

```ts
	// Native tool changes keep the request-level tool list fixed and define every later tool
	// by value in a `tool_addition` block, which also expresses same-name redefinitions.
	// Anthropic rejects a tool list where every tool is deferred, so there must be an initial
	// active tool to anchor the placeholder. Otherwise the current tool list is sent.
	const initialTools = initialSystemMessage?.toolsAdded ?? [];
	const nativeToolChanges =
		compat.supportsMidConvoSystemMessages && compat.supportsMidConvoToolChanges && initialTools.length > 0;
```

**对比旧形状**（`b271b0a52` 的 removed 侧逐字）：

```ts
-	// Native tool changes reference tools by name, so a redefined name cannot be expressed,
-	// and Anthropic rejects a tool list where every tool is deferred, so there must be an
-	// initial active tool to anchor the deferred ones. Otherwise the current tool list is sent.
 	const initialTools = initialSystemMessage?.toolsAdded ?? [];
 	const nativeToolChanges =
-		compat.supportsMidConvoSystemMessages &&
-		compat.supportsMidConvoToolChanges &&
-		initialTools.length > 0 &&
-		!hasToolRedefinitions(context.messages);
+		compat.supportsMidConvoSystemMessages && compat.supportsMidConvoToolChanges && initialTools.length > 0;
```

⇒ **删掉一项**（`!hasToolRedefinitions`），并把 import 里的 `hasToolRedefinitions`／`getDeclaredTools` 一并移除。

#### (d) 工具表 —— `:1203-1232`

```ts
	const toolCacheControl = compat.supportsCacheControlOnTools ? cacheControl : undefined;
	if (nativeToolChanges) {
		// Initial tools stay active with the cache breakpoint on the last one, followed by the
		// placeholder. The list never changes afterwards: later tools are defined by value in
		// `tool_addition` blocks and withdrawn by `tool_removal`, so the cached prefix survives
		// every tool change.
		params.tools = [
			...convertTools(
				initialTools,
				isOAuthToken,
				compat.supportsEagerToolInputStreaming,
				compat.supportsStrictTools,
				toolCacheControl,
			),
			DEFERRED_TOOL_PLACEHOLDER,
		];
	} else {
		const tools = getCurrentTools(context.messages);
		if (tools.length > 0) {
			params.tools = convertTools(
				tools,
				isOAuthToken,
				compat.supportsEagerToolInputStreaming,
				compat.supportsStrictTools,
				toolCacheControl,
			);
		}
	}
```

**对比旧形状**（removed 侧逐字）：

```ts
-		const initialNames = new Set(initialTools.map((tool) => tool.name));
-		const laterTools = getDeclaredTools(context.messages).filter((tool) => !initialNames.has(tool.name));
 		params.tools = [
 			...convertTools(initialTools, …, toolCacheControl),
 			DEFERRED_TOOL_PLACEHOLDER,
-			...convertTools(laterTools, …).map((tool) => ({ ...tool, defer_loading: true })),
 		];
```

#### (e) 消息侧的块 —— `:1315` ＋ `:1339-1351`

签名多了一个**回调**参数（`:1315`）：

```ts
	/** Converts tool definitions for native `tool_addition` blocks; undefined when tool changes are not native. */
	convertToolDefinitions?: (tools: Tool[]) => BetaTool[],
```

块装配（`:1339-1351`）：

```ts
			if (convertToolDefinitions) {
				const added = msg.toolsAdded ?? [];
				const redefined = new Set(added.map((tool) => tool.name));
				for (const tool of msg.toolsRemoved ?? []) {
					// A new definition under the same name replaces the old one, so no removal is needed.
					if (redefined.has(tool.name)) continue;
					blocks.push({
						type: "tool_removal",
						tool: { type: "tool_reference", name: isOAuthToken ? toClaudeCodeName(tool.name) : tool.name },
					});
				}
				for (const definition of convertToolDefinitions(added)) {
					blocks.push({ type: "tool_addition", tool: { type: "tool_definition", definition } });
				}
			}
```

**三处要点**：
1. **同一条消息里 `toolsRemoved` 与 `toolsAdded` 同名 ⇒ 不发 `tool_removal`**（`redefined` 集合的 `continue`）。这把「重定义 ＝ 先删后加」（`ToolStateChanges` 的纪律）压成**一条** `tool_addition`。
2. `tool_addition` 的载荷换成 `{type:"tool_definition", definition}` —— `definition` 是 `convertTools(...)` 的**返回值**，即完整的 `BetaTool`（`{name, description?, eager_input_streaming?, strict?, input_schema}`）。
3. **块序不变**：`[text?, …tool_removal, …tool_addition]`。`tool_removal` 的载荷形状**没变**（仍是 `tool_reference`）。

#### (f) 回调的实参 —— `:1149` 附近（调用点逐字）

```ts
		nativeToolChanges
			? (tools) =>
					convertTools(tools, isOAuthToken, compat.supportsEagerToolInputStreaming, compat.supportsStrictTools)
			: undefined,
```

⚠️ **内联定义走的是「不带 `cacheControl` 的第四次实参」** —— `convertTools` 的第 5 参缺省 ⇒ 内联的 `definition` **没有** `cache_control`（pi oracle 明写断言：*"Cache control goes on the block, never on the inline definition"*）。而 `input_schema`／`strict`／`eager_input_streaming` **三项全在**（与顶层表同一个 `convertTools`）。

### 2.2 Java 侧改动清单

四个文件（2 主源码 ＋ 2 测试），另加一个共用助手。逐条：

#### 改动 1：`AnthropicRequestBuilder.java:44-45` —— beta 常量换值

**现状**（逐字）：

```java
    /** pi {@code anthropic-messages.ts:186} —— 原生工具增删的 beta 头。 */
    private static final String MID_CONVERSATION_TOOL_CHANGES_BETA =
            "mid-conversation-tool-changes-2026-07-01";
```

**改为**：

```java
    /** pi {@code anthropic-messages.ts:195} —— 原生工具增删的 beta 头（定义内联形状）。 */
    private static final String INLINE_TOOLS_BETA = "inline-tools-2026-09-15";
```

（名字也改，`MID_CONVERSATION_TOOL_CHANGES_BETA` → `INLINE_TOOLS_BETA`，与 pi 同名；`MID_CONVERSATION_OUTPUT_CONFIG_BETA` 与工具无关，本仓尚未落，**不引入**。）

#### 改动 2：`AnthropicRequestBuilder.java:140-143` —— 删门的一项

**现状**（逐字）：

```java
        boolean nativeToolChanges = Boolean.TRUE.equals(compat.supportsMidConvoSystemMessages())
                && Boolean.TRUE.equals(compat.supportsMidConvoToolChanges())
                && !initialTools.isEmpty()
                && !Transcripts.hasToolRedefinitions(transcript.messages());
```

**改为**：

```java
        boolean nativeToolChanges = Boolean.TRUE.equals(compat.supportsMidConvoSystemMessages())
                && Boolean.TRUE.equals(compat.supportsMidConvoToolChanges())
                && !initialTools.isEmpty();
```

⚠️ 上面那段 javadoc（`:137-139`）现写「四条都不能少：块只按名引用（表达不了重定义）、…」—— **逐字改写**，因为第 1、4 两条都已不成立：

```java
        // pi :1136-1141 的三条件门（⚠️ b271b0a52 前是四条，`!hasToolRedefinitions` 已删）：
        // ① 模型接受中途系统消息；② 模型接受原生工具增删块；③ 至少一个初始活跃工具
        //（Anthropic 拒绝「全部 deferred」的工具表 ⇒ 必须有活跃工具给占位符做锚）。
        // 重定义**不再**是回退判据 —— 定义内联形状本身能表达它。
```

#### 改动 3：`AnthropicRequestBuilder.java:152-154` —— beta 名

```java
        if (nativeToolChanges) {
            betas.add(INLINE_TOOLS_BETA);
        }
```

#### 改动 4：`AnthropicRequestBuilder.java:199-227` —— `addTools` 的**原生支砍尾**

**现状**（逐字，`:208-227`）：

```java
        for (int i = 0; i < initialTools.size(); i++) {
            builder.addTool(ToolUnion.ofTool(
                toAnthropicTool(initialTools.get(i), false,
                    atLast(i, initialTools.size(), toolCacheControl), supportsStrictTools,
                    eagerToolInputStreaming)));
        }
        builder.addTool(ToolUnion.ofTool(deferredToolPlaceholder()));
        var initialNames = new HashSet<String>();
        for (var td : initialTools) {
            initialNames.add(td.name());
        }
        for (var td : Transcripts.getDeclaredTools(transcript.messages())) {
            if (initialNames.contains(td.name())) {
                continue;
            }
            builder.addTool(ToolUnion.ofTool(
                toAnthropicTool(td, true, null, supportsStrictTools, eagerToolInputStreaming)));
        }
    }
```

**改为**（删掉 `initialNames` 与整个 `getDeclaredTools` 循环）：

```java
        for (int i = 0; i < initialTools.size(); i++) {
            builder.addTool(ToolUnion.ofTool(
                toAnthropicTool(initialTools.get(i), false,
                    atLast(i, initialTools.size(), toolCacheControl), supportsStrictTools,
                    eagerToolInputStreaming)));
        }
        builder.addTool(ToolUnion.ofTool(deferredToolPlaceholder()));
    }
```

⚠️ 连带影响：
- 参数 `com.pijava.ai.api.TranscriptContext transcript` 在原生支**不再被读**（仍被 `betas` 那处的 `getCurrentTools` 用在 `:157`，但那是 `buildParams` 里，不是 `addTools`）⇒ **`addTools` 的这个形参可删**（方法体两处都不再用它）。省略会留一个未用形参 —— 按项目纪律（无警告）**直接删**。
- 分支里**不再出现** `defer_loading: true` 的工具 ⇒ `toAnthropicTool` 的 `deferLoading` 形参**只剩占位符那条路在用**（`deferredToolPlaceholder()` 自成一体，甚至没走 `toAnthropicTool`）⇒ **`deferLoading` 形参变成恒 `false`** ⇒ 删掉形参与其两支调用点，`toAnthropicTool` 里 `if (deferLoading)` 的整段删除。
  （⚠️ 走查：`toAnthropicTool` 的三个调用点当前分别是 `false`／`false`（`atLast`）／**`true`**（`getDeclaredTools` 那支）／`true`（占位符**不走它**）。删掉 `getDeclaredTools` 支后，剩下全是 `false` ⇒ 形参可去。）
- `import java.util.HashSet;` 与 `import com.pijava.ai.api.ToolDefinition;` 的 `ToolDefinition` 仍在用（`initialTools`），`HashSet` **变成唯一用途消失** ⇒ 删 import。
- javadoc（`:179-192`）里「此后每个声明都是 deferred，只靠 `tool_addition` 块浮出；被删的工具**仍然声明**」**逐字改写**为「顶层表**就此定死**（初始 ＋ 占位符）；后续工具在 `tool_addition` 块里**按值内联**，被删的由 `tool_removal` 撤回」。

#### 改动 5：`AnthropicRequestBuilder.java:319-326` —— 消息侧的块装配

**现状**（逐字，`:319-326`）：

```java
                if (nativeToolChanges) {
                    for (var removed : system.toolsRemoved()) {
                        blocks.add(toolReferenceBlock("tool_removal", removed.name()));
                    }
                    for (var added : system.toolsAdded()) {
                        blocks.add(toolReferenceBlock("tool_addition", added.name()));
                    }
                }
```

**改为**：

```java
                if (nativeToolChanges) {
                    var redefined = new HashSet<String>();
                    for (var added : system.toolsAdded()) {
                        redefined.add(added.name());
                    }
                    for (var removed : system.toolsRemoved()) {
                        // 同名新定义**取代**旧定义 ⇒ 不需要 removal 块（pi :1343）。
                        if (redefined.contains(removed.name())) {
                            continue;
                        }
                        blocks.add(toolReferenceBlock("tool_removal", removed.name()));
                    }
                    for (var added : system.toolsAdded()) {
                        blocks.add(toolDefinitionBlock(added, supportsStrictTools,
                            eagerToolInputStreaming));
                    }
                }
```

⚠️ `addMessages` 因此**需要两个新形参**（`supportsStrictTools`、`eagerToolInputStreaming`）—— 调用点 `:164` 补上。这不是「顺手加参数」：内联定义必须与顶层表**同一套 strict／eager 处理**，否则同一定义在两处不一致（§2.1(f) 的 `convertTools(...)` 是**同一个函数**）。
⚠️ `HashSet` 在新位置**又回来了**（改为引用它 ⇒ 改动 4 里「删 HashSet import」作废 —— **本包 net：`HashSet` import 保留**，只是换个用法）。

#### 改动 6：新增两个私有助手（替换 `toolReferenceBlock` 的一半）

**保留** `toolReferenceBlock`（`tool_removal` 仍用它），**新增**：

```java
    /**
     * {@code {type:"tool_addition", tool:{type:"tool_definition", definition:{…}}}}。
     *
     * <p>pi {@code :1351} —— 载荷是 {@code convertTools(...)} 的返回值，即与顶层表**同一个**
     * 定义构造器（strict／eager 两项全走），但**不带** {@code cache_control}
     *（pi 的内联定义走的是 `convertTools` 的四实参形式，末参缺省）。</p>
     *
     * <p>⚠️ 这个块仍是**非 beta 参数承载 beta 形状**：{@code tool_definition} 不是
     * {@code com.anthropic.models.messages.*} 的已知变体 ⇒ 与 {@code tool_removal} 同路，
     * 靠 SDK 的未知变体直通（原始 JSON 往返）。证据见 {@code SdkJsonEscapeHatchTest}。</p>
     */
    private static ContentBlockParam toolDefinitionBlock(ToolDefinition td,
                                                         boolean supportsStrictTools,
                                                         boolean eagerToolInputStreaming) {
        var definition = toDefinitionJson(td, supportsStrictTools, eagerToolInputStreaming);
        var tool = new LinkedHashMap<String, Object>();
        tool.put("type", "tool_definition");
        tool.put("definition", definition);
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "tool_addition");
        block.put("tool", tool);
        return rawContentBlock(block);
    }
```

其中 `toDefinitionJson` 是**从 `toAnthropicTool` 抽出的共用件**（改动 7），`rawContentBlock` 是把现有 `toolReferenceBlock` 尾部的 mapper 往返**抽出**：

```java
    /** 原始 JSON → 未知变体块（`tool_removal` 与 `tool_addition` 共用）。 */
    private static ContentBlockParam rawContentBlock(Map<String, Object> block) {
        var mapper = com.anthropic.core.ObjectMappers.jsonMapper();
        try {
            return mapper.treeToValue(mapper.valueToTree(block), ContentBlockParam.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot build a raw Anthropic content block", e);
        }
    }
```

（`toolReferenceBlock` 改为 3 行：建两层 map、`return rawContentBlock(block);`。）

#### 改动 7：`toAnthropicTool` 拆成「SDK 对象」与「原始 JSON」两份

这是本包**最关键**的一处。现状 `toAnthropicTool`（`:240-272`）返回一个 `Tool`（SDK 类型），内联定义却需要一份**普通 JSON**（要塞进 `tool_definition` 的 `definition`）。

两份必须**逐字段同源**，否则「顶层表的 late_tool 长什么样」与「内联的 late_tool 长什么样」会漂移。做法：**先建 JSON，顶层表再从 JSON 回读成 `Tool`** —— 与 `tool_reference` 那条通路同构（天然的单一真源）。

```java
    /**
     * 工具定义 → 与 pi {@code convertTools} 同形的**普通 JSON**（`{name, description?,
     * eager_input_streaming?, strict?, input_schema}`）。
     *
     * <p>单一真源：顶层表的 {@link Tool} 由本函数的结果**回读**得到（{@link #toAnthropicTool}），
     * 内联定义直接用它 —— pi 两侧走的也是同一个 {@code convertTools}。</p>
     *
     * <p>⚠️ 键序与 pi **不同**（SDK 类型化路径的已知差异，`docs/05` 的旧教训）⇒
     * 跨实现夹具必须**按键取值**，只有本函数直出的 JSON 才逐字节可比。</p>
     */
    private static Map<String, Object> toDefinitionJson(ToolDefinition td, boolean supportsStrictTools,
                                                        boolean eagerToolInputStreaming) {
        var strict = Boolean.TRUE.equals(StrictSampling.resolveStrict(td, supportsStrictTools));
        var schema = strict ? StrictJsonSchema.convert(td.inputSchema()) : td.inputSchema();
        var definition = new LinkedHashMap<String, Object>();
        definition.put("name", td.name());
        if (td.description() != null && !td.description().isBlank()) {
            definition.put("description", td.description());
        }
        if (eagerToolInputStreaming) {
            definition.put("eager_input_streaming", true);
        }
        if (strict) {
            definition.put("strict", true);
        }
        definition.put("input_schema", schema);
        return definition;
    }
```

⚠️ **`input_schema` 的 strict 形状**：现状 `toAnthropicTool` 把 schema 塞进 `Tool.InputSchema.builder().putAllAdditionalProperties(...)`，SDK 会把它摊平成一棵 JSON object。这里直接放 `schema`（一个 `Map<String,Object>`）—— 序列化后**同形**，但**必须靠探针证明**（见 §6 的 M5）。若探针显示顶层表与内联的 `input_schema` 字节不同，则退回「顶层表也从同一个 JSON 回读」的写法，并把差异记进 §8。

`toAnthropicTool` 改为：

```java
    private static Tool toAnthropicTool(ToolDefinition td, CacheControlEphemeral cacheControl,
                                        boolean supportsStrictTools, boolean eagerToolInputStreaming) {
        var definition = toDefinitionJson(td, supportsStrictTools, eagerToolInputStreaming);
        var builder = Tool.builder()
                .name((String) definition.get("name"))
                .inputSchema(Tool.InputSchema.builder()
                    .putAllAdditionalProperties(
                        AnthropicMessageConverter.toJsonValues(
                            (Map<String, Object>) definition.get("input_schema")))
                    .build());
        if (definition.containsKey("description")) {
            builder.description((String) definition.get("description"));
        }
        if (definition.containsKey("eager_input_streaming")) {
            builder.eagerInputStreaming(true);
        }
        if (cacheControl != null) {
            builder.cacheControl(cacheControl);
        }
        if (definition.containsKey("strict")) {
            builder.strict(true);
        }
        return builder.build();
    }
```

⚠️ 上面两处 `(Map<String, Object>)` 与 `(String)` 是**未经检查的窄化**。若编译器/checkstyle 不容（本仓无 `@SuppressWarnings` 惯例），退回「两份平行构造、字段清单由一条**结构断言**测试守住」（见 §3.1 的替代方案）。**实施时以编译器实测为准**，若走替代方案，§2.2 改动 7 的代码以最终形态回填本节。

#### 改动 8：测试夹具

见 §3。

---

## 3. 文件清单与测试

| 文件 | 动作 |
|---|---|
| `pi-java-ai/src/main/java/com/pijava/ai/protocol/AnthropicRequestBuilder.java` | 改（改动 1–7） |
| `pi-java-ai/src/test/java/com/pijava/ai/protocol/AnthropicToolChangesWireTest.java` | 改（见下） |
| `pi-java-ai/src/test/java/com/pijava/ai/protocol/SdkJsonEscapeHatchTest.java` | 改（加一条 `tool_definition` 往返） |
| `pi-java-ai/src/test/java/com/pijava/ai/protocol/AnthropicCacheControlWireTest.java` | 改（`:338` 的断点落点从 `tool_addition` 不变，但断言块内可能要看 `definition` 没挂断点） |
| `pi-java-ai/src/main/java/com/pijava/ai/api/Transcripts.java` | 改（`hasToolRedefinitions` 加弃用注释，**不动函数体**） |
| `pi-java-ai/src/main/java/com/pijava/ai/catalog/ModelCompat.java:145-149` | 改（javadoc：`supportsMidConvoToolChanges` 现在描述的是内联定义形状） |
| `pi-java-ai/src/main/java/com/pijava/ai/provider/ModelsJsonSchema.java:117` | 改（同上一行 javadoc） |

### 3.1 夹具（`AnthropicToolChangesWireTest`）—— 逐条改

pi 的 oracle 在 `b271b0a52` **整体重写**了这四条用例（`test/transcript-tool-changes.test.ts`，+124/−？）。Java 夹具现镜像的是**旧** oracle，故逐条改：

| Java 现用例 | 改法 |
|---|---|
| `sendsToolChangesInANativeSystemMessage` | ① beta 断言换 `INLINE_TOOLS_BETA`（并**加一条**「不含旧 beta」——pi oracle 逐字有：`expect(payload.betas).not.toContain("mid-conversation-tool-changes-2026-07-01")`）；② `toolNames` 从 `[base_tool, placeholder, late_tool]` 改为 **`[base_tool, placeholder]`** 且**长度 2**；③ 第 3 块的断言从 `blocks.get(2).path("tool").path("name")` 改为 **`blocks.get(2).path("tool").path("type") = "tool_definition"`** ＋ `.path("definition").path("name") = "late_tool"` ＋ `.path("definition").path("description") = "late_tool tool"` |
| `removedToolsStayDeclaredInTheRequestLevelList` | **删除或整体改写** —— 它守的正是「顶层表只增不减」，新形状下**这句不再成立**。改为 pi oracle 用例 2 的靶心：「顶层声明保留**原始**定义（缓存前缀存活）」＋「重定义**照走原生支**」 |
| `thePlaceholderIsDeclaredFromTheVeryFirstRequest` | 不变（`[base_tool, placeholder]` 恰好是新形状的期望） |
| `fallsBackWhenTheHistoryHasAToolRedefinition` | **整条翻转** —— 从「回退支」改为新用例 `redefinesAToolByValueUnderTheSameName`（pi oracle 用例 2 逐字骨架）：断言 beta **含** inline；顶层表 = `[{base_tool, description:"base_tool tool"}, {placeholder}]`；最后一条消息的 `content` **长度 1**（只有 `tool_addition`，**没有** `tool_removal`）；`definition` 为 `{name:"base_tool", description:"changed"}` |
| `fallsBackWhenThereIsNoInitialToolToAnchorTheDeferredOnes` | 不变（仍回退，判据仍成立） |
| `holdsSystemUpdatesBackUntilTheNextAssistantMessage` | 不变（块序仍是 `[text, tool_addition]`） |
| `requiresBothCapabilitiesForNativeToolChanges` | 不变（beta 名换掉即可） |
| `foldsUpdatesIntoTheSystemPromptWithoutNativeSupport` | 不变 |

**新增一条**（中文 javadoc 写清靶心）：

```java
    /**
     * 顶层表的定义与内联定义**逐字段同源** —— 都出自 pi 的同一个 {@code convertTools}。
     *
     * <p>靶心是「顶层表不再是后续工具的家」这件事**不能**顺带丢掉它的参数形状：
     * 内联 `definition` 必须带 `input_schema`（否则模型看不到迟到工具的参数），
     * 且必须与顶层表里**同一工具**的 `input_schema` 相同。</p>
     */
    @Test
    void theInlineDefinitionCarriesTheSameSchemaAsTheRequestLevelTool() throws Exception { … }
```

### 3.2 `SdkJsonEscapeHatchTest` 加一条

现有那条（`:32-55`）钉的是 `tool_reference` 往返。**加**一条 `tool_definition`：

```java
    /** pi {@code anthropic-messages.ts:1351} 的 {@code tool_addition}（内联定义形状）。 */
    @Test
    void anthropicCarriesAnInlineToolDefinitionThroughRawJson() throws Exception {
        var mapper = ObjectMappers.jsonMapper();
        var raw = "{\"type\":\"tool_addition\",\"tool\":{\"type\":\"tool_definition\","
            + "\"definition\":{\"name\":\"late_tool\",\"description\":\"late_tool tool\","
            + "\"input_schema\":{\"type\":\"object\"}}}}";

        ContentBlockParam block = mapper.treeToValue(mapper.readTree(raw), ContentBlockParam.class);

        assertThat(block._json()).isPresent();
        // 逐字节：嵌套的 definition 整棵子树必须原样写出（不能只保住外层 type）。
        assertThat(mapper.writeValueAsString(block)).isEqualTo(raw);
    }
```

⚠️ 这条是**内联定义能落地的唯一依据**（与 `tool_reference` 同路），升级 SDK 必须重跑。

### 3.3 测试计数

`pi-java-ai` 当前 **1398**。本包：`AnthropicToolChangesWireTest` 净增 1（删 1 改 1）、`SdkJsonEscapeHatchTest` ＋1 ⇒ 预估 **1400**。以 Maven 模块汇总行为准（历史教训：surefire 报告求和会混入历次筛选运行）。

---

## 4. 波及面（grep 复核清单 —— ⚠️ docs/44 的教训）

| # | 落点 | 依据 |
|---|---|---|
| 1 | `AnthropicRequestBuilder.java:44-45` 常量 | grep `mid-conversation-tool-changes` |
| 2 | `AnthropicRequestBuilder.java:140-143` 四条件门 | 同上 ＋ 读 `hasToolRedefinitions` |
| 3 | `AnthropicRequestBuilder.java:152-154` betas | — |
| 4 | `AnthropicRequestBuilder.java:199-227` 原生支 | 读 `getDeclaredTools` |
| 5 | `AnthropicRequestBuilder.java:319-326` 块装配 | grep `tool_reference` |
| 6 | `AnthropicToolChangesWireTest.java:62` `BETA` 常量 | grep `mid-conversation-tool-changes` |
| 7 | `AnthropicToolChangesWireTest` 六条用例 | 见 §3.1 |
| 8 | `SdkJsonEscapeHatchTest` | 见 §3.2 |
| 9 | `AnthropicCacheControlWireTest:319-345` | 断点落在 `tool_addition` **块**上（形状不变，但断言里 `content.get(1)` 是 `tool_removal` —— 新形状下**重定义场景不发 removal**，该用例的转录是「删 base ＋ 加 late」**不同名** ⇒ 仍发，**用例不改**，但要加注释说明为何成立） |
| 10 | `ModelCompat.java` / `ModelsJsonSchema.java` 的 `supportsMidConvoToolChanges` javadoc | 文字含「mid-conversation `tool_addition`/`tool_removal` content blocks」，补「inline definitions（`inline-tools-2026-09-15`）」 |
| 11 | `TranscriptTools.java:9` / `ToolStateChanges.java:14` javadoc | 提到 Anthropic 的 `tool_addition`；**语义不变**（仍是「就地追加」），不改或只加一词 |
| 12 | `Transcripts.hasToolRedefinitions` javadoc（`:292-297`） | 现写「那是它们的**回退判据之一**」—— 加弃用注释（π 同形） |

⚠️ **`getDeclaredTools` 的其余 4 个读点不动**：`AzureOpenAIResponsesApi:76`、`OpenAICompletionsApi:185`、`OpenAIResponsesApi:75`、`OpenAICompletionsMessageConverter:114` —— 它们是**别的车道**（Responses 的 `additional_tools`、Completions 的 Kimi 形），pi 锚点上同样还在用（§1.1 的 grep 逐行对得上）。

⚠️ **`hasToolRedefinitions` 的其余读点**：`main` 下**只有** `AnthropicRequestBuilder:143` 一处 ⇒ 本包删掉后，`Transcripts.hasToolRedefinitions` 在 `main` 下**零调用点**（与 pi 同形）。测试 `ToolStateChangesTest:133-179` 仍读它 ⇒ 保留函数本体。

⚠️ **`-pl <module>` 不带 `-am` 会读到 `~/.m2` 的旧构件**（`docs/44 §4` 踩过）—— 本包全部改动都在 `pi-java-ai` 内，单模块跑即可，但**收尾的 `clean verify` 必须带全闭包**。

---

## 5. 台账回填（闭环时）

| 文件 | 动作 |
|---|---|
| `docs/41-ai-alignment-gaps.md` | §1.1 的 w2 行标 ✅；§1.1 头「（2 条，补完 +1.76pp）」→「（1 条）」；§0 头条与 §1.1 的 w2 计数重算；§6 的 ③ 行标 ✅（三行全闭） |
| `docs/map/02-ai.md` | 对应单元的判定改「对齐」；计数与加权分（Provider / 横切 / 总计）重算 |
| `docs/05-module-alignment-map.md` | §1 表行 ＋ 各处的百分比与 margin 重算 |
| `docs/04-open-items-register.md` | `B165` 行标销号；**新增登记**见 §8 |

⚠️ 上面四处是**同一组数字**，任一处漏改都会留下不一致（`docs/44` 的教训：`docs/map/02-ai.md` 的单元 70/71 曾逐行标「对齐」而合计没跟上）。

---

## 6. 探针剧本（写码前先定红）

**共同的纪律**（`docs/43` §5 ＋ `docs/44` 的坑）：
1. 每个变异**必须先确认编译通过**；
2. 每个变异**必须 grep 复核已落地**（CRLF 会让带 `$` 锚点的跨行模式**静默失效**）；
3. 跨行变异默认走 Edit，不用 perl（已失效 6 次）。

| # | 变异 | 预期红点 |
|---|---|---|
| **RED** | 新夹具先写、主源码不写 ⇒ **编译失败**（新用例引用 `tool_definition` 形状的断言恒失败） | 编译 |
| **M1** | 常量值改回 `mid-conversation-tool-changes-2026-07-01` | 1（beta 断言；「不含旧 beta」那条也红 ⇒ 合计 2） |
| **M2** | `addTools` 原生支**加回** `getDeclaredTools` 尾部（顶层表再增长） | 1（`toolNames` 长度 2 ⇒ 3） |
| **M3** | `toolDefinitionBlock` 的 `tool_definition` 改回 `tool_reference`（名字换回 `name` 键） | **≥1**（第 3 块类型断言 ＋ 新增的 schema 同源断言） |
| **M4** | 门里**加回** `!Transcripts.hasToolRedefinitions(...)` | 1（新的重定义用例：beta 不再含 inline） |
| **M5** | `toDefinitionJson` **不写** `input_schema` | 1（schema 同源断言） |
| **M6** | 删掉 `redefined.contains(...)` 的 `continue` | 1（重定义用例：`content` 长度 1 ⇒ 2） |

⚠️ **M3 是本包唯一「预测写成区间」的**：块类型断言与 schema 同源断言**都会**红，但只有在 §3.1 新增那条真的断言了 `definition` 内部与顶层表相同时才 ≥2。**先确认落地，再看红数**，不要把「零红」当成通过 —— `docs/44` 的成因表（变异没落地／夹具没牙／通道没夹具／变异体语义等价／改错了对象／断言覆盖面）逐条排。

---

## 7. 不做什么 / 裁决

| # | 项 | 裁决 |
|---|---|---|
| 1 | **`@anthropic-ai/sdk` 升 0.129.0** | **不做** —— Java 侧走的是**未知变体直通**（`ContentBlockParam` 的 `_json` 往返），不依赖 SDK 的类型化 beta 形状。⚠️ 但这条通路**不是文档化承诺** ⇒ 升级 SDK 时重跑 `SdkJsonEscapeHatchTest`（该文件头已有此纪律）。**不升级**即无需新形状的类型定义。 |
| 2 | **`hasToolRedefinitions` 函数体删除** | **不删** —— pi 锚点也保留（§1.2 逐字），只加弃用注释。删了会让 `ToolStateChangesTest` 的 4 条断言无处可去，且 pi 未删。 |
| 3 | **`hasToolRedefinitions` 的弃用注释** | **加** —— 与 pi `transcript.ts:179-185` 同形（读起来像穷尽真相：「别无内置车道需要它了」）。 |
| 4 | **`MID_CONVERSATION_OUTPUT_CONFIG_BETA` / `THINKING_BINDING_CONTROLS_BETA`** | **不引入** —— 属 `supportsMidConvoEffort` 那一族（`B99`，SDK 钉住版本写不出 `block_binding`），与本包无关。 |
| 5 | **OAuth 工具名映射（`toClaudeCodeName`）** | **不落** —— Java 侧本就没有这条通路（`grep toClaudeCodeName` 零命中），是 `A-15` 的范围。本包的两条块路径沿用既有的「名字直通」现状，**不新增**。 |
| 6 | **`eager_input_streaming` 在内联定义上的有无** | **有** —— pi 的 `convertTools(...)` 四实参版：`supportsEagerToolInputStreaming` **在**，`cacheControl` **不在**。逐字对齐。 |
| 7 | **`strict` 在内联定义上的有无** | **有** —— 同上，`supportsStrictTools` 在 pi 的回调实参里。 |
| 8 | **是否并把 `supportsAdditionalTools` 等 Responses 侧形状** | **不并** —— 别的车道，别的包。 |

---

## 8. 残留 / 待登记

| # | 项 | 处理 |
|---|---|---|
| ① | **`input_schema` 的 strict 形状在两条路径上是否逐字节相同** | §2.2 改动 7 的「从同一 JSON 回读」就是为消除它。若实施时发现 SDK 的回读路径**会改写**（例如把 `Map<String,Object>` 的 legacy 三键摊平），则 **M5 会红** ⇒ 改为「两份平行构造 ＋ 结构断言」并把偏差记进本节。**不预判**。 |
| ② | **`supportsMidConvoToolChanges` 的语义已变**（从「接受按名引用的块」到「接受定义内联的块」） | 名字**不改**（pi 也没改，见 `types.ts:834` 的 javadoc 已更新但字段名不变）。Java 的 `ModelCompat.supportsMidConvoToolChanges` javadoc 同步更新文字。**不是缺口**。 |
| ③ | **`AnthropicCacheControlWireTest:319-345` 的转录是「删 base ＋ 加 late」（不同名）** | 新形状下**仍发** `tool_removal` ⇒ 用例结论不变。加一行注释说明「这里的 removal 之所以还在，是因为两条不同名」。**不是缺口**。 |
| ④ | **升级 SDK 时必须重跑 `SdkJsonEscapeHatchTest`** | 已在文件头写明（既有纪律），本包新增的 `tool_definition` 那条**同样**受约束。**不新增登记**。 |

**本包预计不新增台账条目。** 若 §8① 的实测出现偏差，届时登记。

---

## 9. 复现方法（取证）

```bash
# pi 侧（锚点 200387122，只读，不走工作树）
PI=D:/workplaceForai/pi
git -C $PI show 200387122:packages/ai/src/api/anthropic-messages.ts | sed -n '195p;1120p;1136,1141p;1203,1232p;1335,1356p'
git -C $PI show 200387122:packages/ai/package.json | grep anthropic   # 0.129.0
git -C $PI show b271b0a52 --stat
git -C $PI show b271b0a52 -- packages/ai/src/api/anthropic-messages.ts packages/ai/src/utils/transcript.ts packages/ai/src/types.ts
git -C $PI show 200387122:packages/ai/test/transcript-tool-changes.test.ts | sed -n '70,175p'   # 新 oracle 四条
git -C $PI grep -n "hasToolRedefinitions\|getDeclaredTools" 200387122 -- packages/ai/

# Java 侧
cd D:/workplaceForai/pi-java
grep -rn "mid-conversation-tool-changes\|tool_reference\|tool_definition\|getDeclaredTools\|hasToolRedefinitions" \
  --include=*.java pi-java-ai/src/
```

**验证命令**（⚠️ 必须显式 `JAVA_HOME`；跨模块带 `-am`）：

```bash
export JAVA_HOME=D:/soft/jdk/graalvm-jdk-25
export PATH=/d/soft/apache-maven-3.9.9/bin:$PATH
mvn -q -pl pi-java-ai test -Dtest='AnthropicToolChangesWireTest+SdkJsonEscapeHatchTest'
mvn -q clean verify -pl pi-java-ai,pi-java-evals -am     # 收尾
```

---

## 10. 实施记录（闭环时回填）

**日期**：2026-10-10。**结论**：本稿 §2.2 的八条改动全部落地，探针 RED ＋ M1–M6 全部取到。
`pi-java-ai` **1398 → 1400**（`AnthropicToolChangesWireTest` 净 +1、`SdkJsonEscapeHatchTest` +1）。
收尾 `mvn clean verify -pl pi-java-ai,pi-java-evals -am` **8/8 模块 SUCCESS，零失败**。

### 10.1 落地清单

| 文件 | 动作 |
|---|---|
| `protocol/AnthropicToolWire.java` | **新**（231 行）—— 工具线格自 `AnthropicRequestBuilder` **拆出**（见 10.3） |
| `protocol/AnthropicRequestBuilder.java` | 改（常量／门／betas／`addMessages` 形参／块装配）—— 495 → **371** 行 |
| `api/Transcripts.java` | `hasToolRedefinitions` 加 `@Deprecated` ＋ 弃用 javadoc（**函数体不动**） |
| `catalog/ModelCompat.java` | `supportsMidConvoToolChanges` javadoc 改「三条件门 ＋ 内联形状」 |
| `provider/ModelsJsonSchema.java` | 同上一行（记录 javadoc） |
| `test/.../AnthropicToolChangesWireTest.java` | 改 6、**换 1**、翻 1、**增 1**（schema 同源） |
| `test/.../AnthropicCacheControlWireTest.java` | 改 1（见 10.2） |
| `test/.../SdkJsonEscapeHatchTest.java` | ＋1（`tool_definition` 逐字节往返） |

### 10.2 ⚠️ 实测纠正了本稿三处

**(1) §2.2 改动 4 的「`addTools` 的 `transcript` 形参可删」—— 不成立。**
非原生支仍读它（`Transcripts.getCurrentTools(transcript.messages())`）。若照稿删，非原生支
直接编译失败。**实际做法**：形参保留。（原稿那句走查只看了原生支，漏了 `:200` 那一支。）

**(2) §2.2 改动 7 的「先建 JSON 再回读」不是可选项 —— 是必须。**
探针实测：`Tool.builder().inputSchema(putAllAdditionalProperties(...))` 会让 `input_schema` 里的
`"type"` 键**写两遍**（SDK 的 `InputSchema` 把 `type` 声明成自己的字段，与 additionalProperties
**重叠**）：

```
A_CURRENT_MINIMAL={"input_schema":{"type":"object","type":"object"},"name":"t"}
B_RTL_MINIMAL    ={"input_schema":{"type":"object"},"name":"t"}
```

旧代码一直在发这个重复键。回读路径的产出与 pi `convertTools` **逐字节相同**（`C_RTL_RICH`
保住嵌套 `properties`/`$defs`/`additionalProperties` 且键序一致）。**§8① 的偏差预案因此作废** ——
两条路径不是「可能不同」，而是**回读才是对的**。`deferredToolPlaceholder` 同病同治（原走
`putAllAdditionalProperties`，同发重复键）。

**(3) §4 row 9 漏了一条用例。** `AnthropicCacheControlWireTest:274`
（`theNativeToolShapePutsTheBreakpointOnTheLastInitialToolOnly`）也钉住旧的**三段式**（期望 4 个工具）
⇒ 新形状下必须改判为 3。§4 只点了同文件 `:319-345` 那一条（那条确实无需改，见 §8③）。

### 10.3 文件拆分（守 500 行）

改动 7 加回读 ＋ 两个新助手后，`AnthropicRequestBuilder` 涨到 **576 行**（超 CLAUDE.md 硬规则）。
按「工具」这一层关注点拆出 `AnthropicToolWire`（顶层工具表 ＋ 两个工具块 ＋ 定义构造），
调用点改为 `AnthropicToolWire.addTools(...)` / `.toolRemovalBlock(...)`。两侧均在 500 行内。

### 10.4 探针结果（全部落地复核后计数）

| # | 变异 | 预测 | **实测** | 说明 |
|---|---|---|---|---|
| **RED** | 新夹具先写、主源码不写 | 编译失败 | ✅ | 逐条实施时天然取得 |
| **M1** | beta 常量改回旧值 | 2 | **3** | 三条用例都断言 `contains(BETA)` |
| **M2** | 原生支加回 `getDeclaredTools` 尾部 | 1 | **3** | 含 `AnthropicCacheControlWireTest` 那条 |
| **M3** | `tool_definition` 改回 `tool_reference` | ≥1 | **3** | 块类型断言 ＋ 两条定义断言 |
| **M4** | 门里加回 `!hasToolRedefinitions` | 1 | **2** | 新增的同源用例也断言 beta |
| **M5** | `toDefinitionJson` 不写 `input_schema` | 1 | **1** | 恰中 |
| **M6** | 删掉 `redefined.contains(...)` 的 `continue` | 1 | **1**（**二次**） | 首次**零红** —— 见下 |

⚠️ **M6 首次零红是「夹具没牙」（docs/44 成因表的第 2 种）**：重定义夹具只给了 `toolsAdded`，
`toolsRemoved` 是**空的** ⇒ `continue` 那条分支根本不执行。pi 的 oracle（`:149-158`）
**两边都给同一个名字** —— 那才是「重定义」在这条状态线上的形状（`ToolStateChanges` 的纪律是
「先删后加」）。补上 `List.of(new ToolReference("base_tool"))` 后复跑，**恰 1 红**成立。
⇒ 教训：**「某分支没有夹具」这类零红，只有读 pi 的输入形状才看得出来**，光看红数会被骗。

### 10.5 台账回填

`docs/41`（§0／§1.1／§6／进度 banner）、`docs/map/02-ai.md`（单元 99 ＋ 四处合计数字）、
`docs/05-module-alignment-map.md`（总表 ＋ §3.1 ＋ LOC 校验行）、`docs/04`（`B165` 销号）。
⚠️ 四处是**同一组数字**：加权 **83.48% → 84.36%**、Σ(w×c) **189.5 → 191.5**、
未加权 **66.9% → 67.7%**、w2 **2 → 1 条**。

**本包不新增台账条目**（§8 的四条残留均未成缺口 —— §8① 随 10.2(2) 作废）。
