# 12 ai 批 3b 详稿：resolver glob/scope 面（minimatch 通配 ＋ 子串 ＋ alias/dated 排序 ＋ scope 诊断 ＋ `--models` 消费链）

> 状态：**待审核**（设计稿，审核通过前不写产品代码）。
> 基准：pi `ea448f454`（2026-10-11，v1.1.0-22）；pi 证据一律 `git show ea448f454:<path>`。
> 唯一例外：minimatch 语义来自 **npm `minimatch@10.2.6` tarball**（pi `packages/coding-agent/package.json`
> 直接依赖钉死版本号；npm 包不在 git 内，同批 3a 的 npm tarball 取证例外）。**只做静态阅读，未执行**。
> 上游判据：`bin pi` 主流发布版行为。
> 路线：[`08`](08-ai-layer-alignment-roadmap.md) §3 批 3b＝台账 **B137 主体**（批 3a 已落防回归半 `DefaultModelIds`）。
> 前序：[`11`](11-ai-batch3a-catalog-data-design.md)（588 chat／17 分片已在 resources/catalog，本稿消费该目录）。

---

## 0. 范围一览

| 条目 | pi 行为 | 本批动作 |
|---|---|---|
| **精确引用匹配** `findExactModelReferenceMatch` | `provider/id` 规范匹配 → provider 限定 → 裸 id；忽略大小写；**裸 id 跨 provider 歧义＝不命中** | 移植为纯函数，替换 `DefaultModelResolver` 内联判据 |
| **子串匹配** | 精确不中 ⇒ id **或 name（显示名）** includes，忽略大小写 | 移植（Java 字段＝`ModelInfo.displayName()`） |
| **alias/dated 排序** `isAlias`＋localeCompare 降序 | 子串多命中时优先 alias（无 `-YYYYMMDD` 尾缀、`-latest` 算 alias），组内 id 降序 | 移植；排序器用根 locale（见 §4 裁决②） |
| **`:level` 后缀解析** `parseModelPattern` | 全串先匹配（保护 openrouter `:batch` 冒号 id）；合法 7 档才切；scope 模式非法后缀＝warning＋继续，CLI 严格模式非法后缀＝id 一部分 | 移植（scope 宽容／CLI 严格两套）；级别字面量集合照 `args.ts:61-65` |
| **glob 通配** `resolveModelScopeFromModels` | 模式含 `*?[` 走 minimatch（先精确引用、后 `provider/id` 与裸 id 双车道，nocase），保目录序、去重 | 手写 minimatch 10.2.6 子集匹配器（§3.2），不引 npm |
| **ModelScopeDiagnostic 两码** | `no-match`、`invalid-thinking-level`，文案逐字 | record＋enum；启动期 `System.err` 打 `Warning: <message>`（同 pi console.warn） |
| **`--models` 消费链** | `parsed.models ?? settings.enabledModels` → scope 解析 → 初始模型（saved-in-scope 优先否则 scoped[0]）→ Ctrl+P cycling | `Args.models()` 现在**零消费**，接通；AgentSession 持 scoped 列表；RPC `cycle_model` 与 TUI Ctrl+P 都走它 |
| **裸 id 歧义硬错误**（resolveCliModel） | 歧义裸 id：唯一已认证 provider 胜，否则报错要求 `--provider` | **不移植硬错误**（Java 无 auth 门），保留确定性首中，记差异（§4 裁决①） |

**明确不做**（§4 另有裁决）：minimatch brace 展开 `{a,b}`／extglob／posix class（窄差登记）；
Ctrl+P **反向** cycling（shift+ctrl+p）；auth 可用性过滤；`findInitialModel` 的认证优先级；
`/scoped-models` 的选择器 UI（map/04 行 5 仍存疑，本批只让它写入的 patterns 被消费）；
RPC `set_model` 的模糊匹配改造；pi `resolveCliModel` 的自定义 id `reasoning:true` 改写。

**加权影响**：B137 是台账行为项、不占 map 加权单元 ⇒ **闭环不改百分比**（同批 2 Mistral）；
销号时 docs/04、docs/06、docs/08、map/02 头部、map/04 行 5 备注回填。

---

## 1. pi 取证（逐字）

### 1.1 入口：patterns 从哪来、怎么切

`packages/coding-agent/src/cli/args.ts:142-147`（逗号切分、trim、去空段）：

```ts
} else if (arg === "--models" && i + 1 < args.length) {
    result.models = args[++i]
        .split(",")
        .map((s) => s.trim())
        .filter((pattern) => pattern.length > 0);
}
```

合法级别闭集（`args.ts:61-65`，**大小写敏感**，逐字包含）：

```ts
const VALID_THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
export function isValidThinkingLevel(level: string): level is ThinkingLevel {
	return VALID_THINKING_LEVELS.includes(level as ThinkingLevel);
}
```

`main.ts:813-816`（CLI 优先，否则 settings 的 enabled models；空表＝不 scope）：

```ts
const modelPatterns = parsed.models ?? settingsManager.getEnabledModels();
const scopedModels =
    modelPatterns && modelPatterns.length > 0
        ? await resolveModelScope(modelPatterns, modelRuntime, { signal: AbortSignal.timeout(15_000) })
        : [];
```

初始模型选择（`main.ts` `buildSessionOptions` :499-530）：

```ts
if (!options.model && scopedModels.length > 0 && !hasExistingSession) {
    const savedProvider = settingsManager.getDefaultProvider();
    const savedModelId = settingsManager.getDefaultModel();
    const savedModel = savedProvider && savedModelId ? modelRuntime.getModel(savedProvider, savedModelId) : undefined;
    const savedInScope = savedModel ? scopedModels.find((sm) => modelsAreEqual(sm.model, savedModel)) : undefined;
    if (savedInScope) {
        options.model = savedInScope.model;
        if (!parsed.thinking && savedInScope.thinkingLevel) options.thinkingLevel = savedInScope.thinkingLevel;
    } else {
        options.model = scopedModels[0].model;
        if (!parsed.thinking && scopedModels[0].thinkingLevel) options.thinkingLevel = scopedModels[0].thinkingLevel;
    }
}
```

### 1.2 精确引用匹配（model-resolver.ts:80-117）

```ts
export function findExactModelReferenceMatch(
	modelReference: string,
	availableModels: Model<Api>[],
): Model<Api> | undefined {
	const trimmedReference = modelReference.trim();
	if (!trimmedReference) return undefined;
	const normalizedReference = trimmedReference.toLowerCase();

	const canonicalMatches = availableModels.filter(
		(model) => `${model.provider}/${model.id}`.toLowerCase() === normalizedReference,
	);
	if (canonicalMatches.length === 1) return canonicalMatches[0];
	if (canonicalMatches.length > 1) return undefined;

	const slashIndex = trimmedReference.indexOf("/");
	if (slashIndex !== -1) {
		const provider = trimmedReference.substring(0, slashIndex).trim();
		const modelId = trimmedReference.substring(slashIndex + 1).trim();
		if (provider && modelId) {
			const providerMatches = availableModels.filter(
				(model) =>
					model.provider.toLowerCase() === provider.toLowerCase() &&
					model.id.toLowerCase() === modelId.toLowerCase(),
			);
			if (providerMatches.length === 1) return providerMatches[0];
			if (providerMatches.length > 1) return undefined;
		}
	}

	const idMatches = availableModels.filter((model) => model.id.toLowerCase() === normalizedReference);
	return idMatches.length === 1 ? idMatches[0] : undefined;
}
```

要点：三段短路，每段「唯一才命中、>1 ＝ undefined」；空串 undefined；**裸 id 跨家重复＝不命中**
（内置目录实测 **16 个**裸 id 跨家重复，全是国内镜像家：`kimi-k2.6` 在 moonshotai／moonshotai-cn／
qwen-token-plan-cn，`glm-5.3` 在 zai／zai-coding-cn／qwen-token-plan-cn 等；清单见 §4 裁决①）。

### 1.3 子串与 alias/dated 排序（:65-76, 123-148）

```ts
function isAlias(id: string): boolean {
	if (id.endsWith("-latest")) return true;
	const datePattern = /-\d{8}$/;
	return !datePattern.test(id);
}

function tryMatchModel(modelPattern: string, availableModels: Model<Api>[]): Model<Api> | undefined {
	const exactMatch = findExactModelReferenceMatch(modelPattern, availableModels);
	if (exactMatch) return exactMatch;

	const matches = availableModels.filter(
		(m) =>
			m.id.toLowerCase().includes(modelPattern.toLowerCase()) ||
			m.name?.toLowerCase().includes(modelPattern.toLowerCase()),
	);
	if (matches.length === 0) return undefined;

	const aliases = matches.filter((m) => isAlias(m.id));
	const datedVersions = matches.filter((m) => !isAlias(m.id));
	if (aliases.length > 0) {
		aliases.sort((a, b) => b.id.localeCompare(a.id));
		return aliases[0];
	} else {
		datedVersions.sort((a, b) => b.id.localeCompare(a.id));
		return datedVersions[0];
	}
}
```

要点：① 子串同时打 id 与 **name 显示名**；② 先精确后子串；③ alias 组整体优先于 dated 组
（不是混排）；④ 组内按**裸 model id** 降序；同 id 稳定序（ES2019 Array.sort 稳定 ⇒ 保目录序）。

### 1.4 `:level` 后缀递归解析（:204-254）

```ts
export function parseModelPattern(
	pattern: string,
	availableModels: Model<Api>[],
	options?: { allowInvalidThinkingLevelFallback?: boolean },
): ParsedModelResult {
	const exactMatch = tryMatchModel(pattern, availableModels);
	if (exactMatch) return { model: exactMatch, thinkingLevel: undefined, warning: undefined };

	const lastColonIndex = pattern.lastIndexOf(":");
	if (lastColonIndex === -1)
		return { model: undefined, thinkingLevel: undefined, warning: undefined };

	const prefix = pattern.substring(0, lastColonIndex);
	const suffix = pattern.substring(lastColonIndex + 1);

	if (isValidThinkingLevel(suffix)) {
		const result = parseModelPattern(prefix, availableModels, options);
		if (result.model) {
			return { model: result.model, thinkingLevel: result.warning ? undefined : suffix, warning: result.warning };
		}
		return result;
	} else {
		const allowFallback = options?.allowInvalidThinkingLevelFallback ?? true;
		if (!allowFallback) {
			// CLI 严格模式：当作 model id 的一部分，不解析。
			return { model: undefined, thinkingLevel: undefined, warning: undefined };
		}
		const result = parseModelPattern(prefix, availableModels, options);
		if (result.model) {
			return {
				model: result.model, thinkingLevel: undefined,
				warning: `Invalid thinking level "${suffix}" in pattern "${pattern}". Using default instead.`,
			};
		}
		return result;
	}
}
```

要点：① 全串先试（openrouter `qwen/qwen3-coder:exacto` 一类 id 自带冒号）；② 只切**最后一个**冒号、
后缀必须是 7 档字面量之一；③ 内层有 warning 则外层级别丢弃；④ scope 模式（默认）非法后缀＝
**逐字 warning**＋前缀继续；⑤ CLI（`--model`）严格模式：非法后缀留在 id 里，后续走自定义 id 兜底，
不会静默切到别的模型（pi 测试 `invalid thinking suffix on custom model is treated as part of model id`）。

### 1.5 scope 解析主体与两码诊断（:264-372）

```ts
export interface ModelScopeDiagnostic {
	type: "warning";
	code: "no-match" | "invalid-thinking-level";
	message: string;
	pattern: string;
}

export function resolveModelScopeFromModels(
	patterns: string[],
	models: readonly Model<Api>[],
): ResolveModelScopeResult {
	const availableModels = [...models];
	const scopedModels: ScopedModel[] = [];
	const diagnostics: ModelScopeDiagnostic[] = [];

	for (const pattern of patterns) {
		if (pattern.includes("*") || pattern.includes("?") || pattern.includes("[")) {
			const colonIdx = pattern.lastIndexOf(":");
			let globPattern = pattern;
			let thinkingLevel: ThinkingLevel | undefined;
			if (colonIdx !== -1) {
				const suffix = pattern.substring(colonIdx + 1);
				if (isValidThinkingLevel(suffix)) {
					thinkingLevel = suffix;
					globPattern = pattern.substring(0, colonIdx);
				}
			}

			const exactMatch = findExactModelReferenceMatch(globPattern, availableModels);
			if (exactMatch) {
				if (!scopedModels.find((sm) => modelsAreEqual(sm.model, exactMatch)))
					scopedModels.push({ model: exactMatch, thinkingLevel });
				continue;
			}

			const matchingModels = availableModels.filter((m) => {
				const fullId = `${m.provider}/${m.id}`;
				return minimatch(fullId, globPattern, { nocase: true }) || minimatch(m.id, globPattern, { nocase: true });
			});
			if (matchingModels.length === 0) {
				diagnostics.push({ type: "warning", code: "no-match",
					message: `No models match pattern "${pattern}"`, pattern });
				continue;
			}
			for (const model of matchingModels) {
				if (!scopedModels.find((sm) => modelsAreEqual(sm.model, model)))
					scopedModels.push({ model, thinkingLevel });
			}
			continue;
		}

		const { model, thinkingLevel, warning } = parseModelPattern(pattern, availableModels);
		if (warning)
			diagnostics.push({ type: "warning", code: "invalid-thinking-level", message: warning, pattern });
		if (!model) {
			diagnostics.push({ type: "warning", code: "no-match",
				message: `No models match pattern "${pattern}"`, pattern });
			continue;
		}
		if (!scopedModels.find((sm) => modelsAreEqual(sm.model, model)))
			scopedModels.push({ model, thinkingLevel });
	}
	return { scopedModels, diagnostics };
}

// 打印侧（:365-372）：
for (const diagnostic of diagnostics) {
	console.warn(chalk.yellow(`Warning: ${diagnostic.message}`));
}
```

要点：
- glob 触发字符闭集＝`*` `?` `[`（**含 `[`**，但精确引用匹配在 glob **之前**——id 里带方括号的
  `custom/bracketed-model[1m]` 走精确命中，不当字符类，pi 有专测）；
- glob 的 `:level` 只剥合法后缀；诊断 message 用**原始 pattern**（带后缀）；
- 匹配双车道：`provider/id` 全限定 或 裸 id（故 `*sonnet*` 不必写 `anthropic/*sonnet*`）；
- 输出保 `availableModels` 序，按 (provider,id) 去重；不报错、只警告。

### 1.6 minimatch 10.2.6 语义（npm tarball 静态取证）

pi 只传 `{ nocase: true }`，其余全默认。取自 tarball `dist/commonjs/`：

| 构造 | 生成正则／语义 | 出处 |
|---|---|---|
| `?` | `[^/]`（**不跨 /**） | `ast.js:127` `const qmark = '[^/]'` |
| `*` | `[^/]*?`（不跨 /；连续多个 `*` 合并为一个） | `ast.js:129`；`#parseGlob` `inStar` 合并 :803-808 |
| `**` | 仅当**整段**（两个斜杠之间）才是 globstar＝`.*`（可跨 /）；段内 `a**b` 等同 `a*b` | `index.js:168-173`；`index.js:331-336`（noglobstar 才降级，默认开启） |
| `[...]` | 字符类：首位 `!` 或 `^` ＝取反；`a-z` 范围；`-]` ＝字面量横线；`[]]` ＝字面 `]`；**未闭合的 `[` 按字面量**；单字符类 `[x]` 非 magic（仍等于字面 x）；空类毒化（不可能命中） | `brace-expressions.js:36-149` `parseClass` |
| 其它字符 | 正则元字符全部转义（`.+(){}^$\\|` 等按字面量） | `ast.js:122-123` `reSpecials`/`regExpEscape` |
| `\` | 转义符（`\*` ＝字面 `*`）；行尾孤 `\` ＝字面 | `ast.js:809-815` |
| 点文件门 | 默认 `dot:false`：每段开头若本可能匹配 `.`（通配符或字符类起头），加 `(?!\.)`；显式字面 `.` 起头不拦 | `ast.js:115-116,654-667` |
| 大小写 | `nocase:true` ⇒ 整模式大小写不敏感 | 调用点 `model-resolver.ts:316` |
| 锚定 | 全串匹配（AST 根绑定 ^/$） | `ast.js` root binds |
| brace | **默认开启** `{a,b}` 展开（brace-expansion ^5，支持嵌套与 `{1..3}`） | `index.js:147-158,251-253` |
| 其它 | `#` 注释模式、`!` 否定模式、extglob `?(...)` 等 | 本批不实现（§4 裁决③） |

实测目录视角：模型 id 字符集为 `[A-Za-z0-9._/-]`，无反斜杠、无 leading-dot 段；点文件门与转义
仍照移植（行为等价＋防未来 id）。

### 1.7 cycling 消费（agent-session.ts:2519-2560）

```ts
async cycleModel(direction: "forward" | "backward" = "forward", options: ModelMutationOptions = {}) {
	if (this._scopedModels.length > 0) return this._cycleScopedModel(direction, options);
	return this._cycleAvailableModel(direction, options);
}

private async _cycleScopedModel(direction, options) {
	const availableIds = new Set(
		this._modelRuntime.getAvailableSnapshot().map((model) => `${model.provider}\0${model.id}`),
	);
	const scopedModels = this._scopedModels.filter((scoped) =>
		availableIds.has(`${scoped.model.provider}\0${scoped.model.id}`),
	);
	if (scopedModels.length <= 1) return undefined;

	const currentModel = this.model;
	let currentIndex = scopedModels.findIndex((sm) => modelsAreEqual(sm.model, currentModel));
	if (currentIndex === -1) currentIndex = 0;
	const len = scopedModels.length;
	const nextIndex = direction === "forward" ? (currentIndex + 1) % len : (currentIndex - 1 + len) % len;
	const next = scopedModels[nextIndex];
	const thinkingLevel = this._getThinkingLevelForModelSwitch(next.model, next.thinkingLevel);
	// …应用 model、appendModelChange、可选 persist；显式 scoped 级覆盖思考档
}
```

键位（`core/keybindings.ts:42-46,238-239`）：正向 **ctrl+p**；反向 shift+ctrl+p（Windows 终端
alt+p）。RPC `rpc-mode.ts:481` 只有正向 `session.cycleModel()`。
pi 切模型还做 `appendModelChange`（transcript 留痕）与 settings persist（`persist` 选项，Ctrl+P
不带）；Java 现状 `ModelSelectorScreen.apply` 只 `harness().setModel(...)` 不留痕——本批与现状一致，
不引入留痕（另面缺口，不在 B137）。

---

## 2. Java 现状（差距确认）

| 面 | 现状 | 文件 |
|---|---|---|
| `--models` 解析 | picocli `--models` String → 逗号 split → `Args.models()` List；**主代码零消费者**（仅 ArgsParserTest 断言） | `cli/ArgsParser.java:84-85,234,244-249`、`cli/Args.java:76`、`HelpText.java:44`（文案已宣称 Ctrl+P cycling） |
| settings 面 | `enabledModels` 字段＋getter/setter 已有；唯一写入者 `/scoped-models`；**无人读去 cycling** | `core/SettingsAccessors.java:101-110`、`slash/builtin/ModelCommands.java:31-50` |
| 单模型解析 | 精确（全限定／裸 id，>1 取首条）→ 切**任意**冒号后缀（不校验合法档）→ 裸 provider 默认表/首条 → 任意 id 自定义兜底（stderr 文案与 pi 近似）。**无子串、无 name 匹配、无 alias/dated 排序、无裸 id 歧义判定** | `pi-java-ai/.../model/DefaultModelResolver.java:141-213` |
| scope 解析 | **不存在**（无 ScopedModel／诊断／glob） | — |
| 思考档后缀 | `ThinkingLevels.parseFromModelPattern` 独立从 `--model` 切合法后缀；`isValid` 走 **toLowerCase**（pi 大小写敏感，窄差） | `cli/ThinkingLevels.java:38-58` |
| RPC cycling | `cycle_model` 在**全目录**（598 条）里轮转；`set_model` 只接受精确 provider/id | `rpc/RpcDispatcher.java:149-152,436-464` |
| TUI cycling | 动作 ID 与默认键 **ctrl+p 已存在**（`MODEL_CYCLE`），但 `PiTuiApp.handleAction` default 空分支丢弃；TUI 不显示当前模型（仅 welcome overlay） | `core/KeybindingsManager.java:22,61`、`tui/app/PiTuiApp.java:450` |
| 初始模型 | `args.model ?? effective.defaultModel ?? providerName` → resolver；从不看 scoped | `core/AgentSession.java:273-284` |
| 目录序 | 批 3a 已把 `BuiltinCatalog` 索引钉成 LinkedHashMap；`ModelsJsonConfig.allModels().listModels()` 序＝分片序＋models.json 叠加，可直接当 `availableModels` | 批 3a |
| auth 门 | Java **无** `hasConfiguredAuth` 等价面（解析期不看密钥） | — |

`AgentSession.java` 已 **1187 行**（超 ≤500 约束的存量大文件），本批新逻辑全部进独立帮手类，
该文件只加薄壳方法与装配段。

---

## 3. 设计（待写代码）

### 3.1 类型与落点（pi-java-ai，`com.pijava.ai.model`）

```java
/** 一条 scope 命中：模型 + 可选的显式思考档（"provider/pattern:high" 的 high）。 */
public record ScopedModel(ModelInfo model, Optional<String> thinkingLevel) {
    public ScopedModel {
        Objects.requireNonNull(model, "model");
        thinkingLevel = thinkingLevel == null ? Optional.empty() : thinkingLevel;
    }
    static ScopedModel of(ModelInfo m) { return new ScopedModel(m, Optional.empty()); }
}

/** pi ModelScopeDiagnostic（model-resolver.ts:264-269）。两码闭集＝纯常量 ⇒ enum。 */
public record ModelScopeDiagnostic(Code code, String message, String pattern) {
    public enum Code { NO_MATCH, INVALID_THINKING_LEVEL }
    static ModelScopeDiagnostic noMatch(String pattern) {
        return new ModelScopeDiagnostic(Code.NO_MATCH,
            "No models match pattern \"" + pattern + "\"", pattern);
    }
    static ModelScopeDiagnostic invalidLevel(String suffix, String pattern) {
        return new ModelScopeDiagnostic(Code.INVALID_THINKING_LEVEL,
            "Invalid thinking level \"" + suffix + "\" in pattern \"" + pattern
                + "\". Using default instead.", pattern);
    }
}

public record ModelScopeResult(List<ScopedModel> scopedModels, List<ModelScopeDiagnostic> diagnostics) {
    public ModelScopeResult {
        scopedModels = List.copyOf(scopedModels);
        diagnostics = List.copyOf(diagnostics);
    }
}
```

合法级别闭集放模型包内部常量（**逐字、大小写敏感**，引 `args.ts:61-65`）：

```java
/** pi VALID_THINKING_LEVELS（cli/args.ts:61-65），大小写敏感。 */
static final Set<String> THINKING_LEVELS =
    Set.of("off", "minimal", "low", "medium", "high", "xhigh", "max");
static boolean isValidThinkingLevel(String s) { return s != null && THINKING_LEVELS.contains(s); }
```

### 3.2 `ModelGlob`（minimatch 10.2.6 子集，纯函数）

```java
/** minimatch 子集：* ? ** [class]，nocase，dot 门；不支持 brace/extglob/posix/否定（§4③）。 */
public final class ModelGlob {
    private ModelGlob() {}

    /** 模式是否含 glob 触发字符（pi :289：*、?、[）。 */
    public static boolean isGlob(String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0 || pattern.indexOf('[') >= 0;
    }

    /** minimatch(text, glob, {nocase:true}) 的等价判定（全串锚定）。 */
    public static boolean matches(String text, String glob) { ... }
}
```

实现＝glob → `java.util.regex.Pattern`（CASE_INSENSITIVE）：

1. 按 `/` 切段逐段翻译，段间插 `\/`；
2. 每段：
   - 整段 `**` ⇒ 段正则 `.*`（仍受点文件门约束：见下）；
   - 否则逐字符扫描：连续 `*`（任意个数）合并输出 `[^/]*`；`?` ⇒ `[^/]`；
     `[` 走字符类翻译（返回消费长度，未闭合/空类规则照 §1.6 表）；`\` 转义下一字符；
     其余字符过正则转义白名单转义；
3. **点文件门**：段首 glob token 为通配符或**合法字符类**时，段前插 `(?!\.)`
   （未闭合 `[` 变字面 `\[`，pi 不加门）；
4. 包 `^...$`，`Pattern.CASE_INSENSITIVE` 编译并缓存（ConcurrentHashMap<String,Pattern>）。

零段差边界（夹具逐条钉）：`*` 不跨 `/`、`a/*` 不匹配 `a/b/c`、`**` 匹配带斜串、
`?` 不吃 `/`、`[1m]`＝{1,m} 两元、`[!1]` 取反、`[a-c]` 范围、`[]]` 字面 `]`、
`model[1`（未闭合）只匹配字面 `model[1`、`.`/`(`/`+`/`{` 按字面、`*` 不匹配 leading-dot 段、
nocase、`x` 不是 `x/y` 的子锚（全串锚定）。

### 3.3 `ModelPatterns`（精确/子串/后缀纯函数）

```java
/** pi model-resolver.ts 的无状态匹配核；全部包可见，供 resolver/scope/test 复用。 */
final class ModelPatterns {
    /** findExactModelReferenceMatch（:80-117）。 */
    static Optional<ModelInfo> findExactReference(String ref, List<ModelInfo> models) { ... }
    /** isAlias（:65-74）：-latest 或无 -YYYYMMDD 尾缀。 */
    static boolean isAlias(String id) { ... }
    /** tryMatchModel（:123-148）：精确 → id/displayName 子串 → alias 优先、组内 id 降序。 */
    static Optional<ModelInfo> tryMatch(String pattern, List<ModelInfo> models) { ... }
    /** parseModelPattern（:204-254）。strict=true＝CLI（非法后缀留 id）；false＝scope（warning）。 */
    static ParsedPattern parse(String pattern, List<ModelInfo> models, boolean strict) { ... }

    record ParsedPattern(Optional<ModelInfo> model, Optional<String> level, Optional<String> warning) {}
}
```

排序器（§4 裁决②）：`Comparator.comparing((ModelInfo m) -> m.id().modelName()).reversed()`
——码点序降序、同 id 保 `listModels()` 稳定序。

### 3.4 `ModelScopeResolver`

```java
/** resolveModelScopeFromModels（model-resolver.ts:276-357）的 Java 面。 */
public final class ModelScopeResolver {
    public ModelScopeResolver(ModelCatalog catalog) { this.models = catalog.listModels(); }
    /** 测试/聚合用：显式模型集（allModels 已保序）。 */
    static ModelScopeResult resolve(List<String> patterns, List<ModelInfo> models) { ... }
    public ModelScopeResult resolve(List<String> patterns) { return resolve(patterns, models); }
}
```

逐行照 §1.5：glob 判定（`ModelGlob.isGlob`）→ 剥合法 `:level` → 精确引用先判 →
双车道 glob 过滤（`provider/id` 与裸 id）→ 保序去重；非 glob 走
`ModelPatterns.parse(..., false)`；诊断两码经 `ModelScopeDiagnostic` 工厂构造（文案逐字）。

### 3.5 `DefaultModelResolver.resolve(String,String)` 改委托

公开签名与返回（`ModelId<?>`）、自定义 id 兜底、裸 provider 默认表（`defaultOrFirst`，批 3a）
全部保留；匹配核改为：

```java
// 全串精确（含 openrouter 冒号 id）→ 严格模式后缀解析（非法后缀不切，整串留作自定义 id）
var parsed = ModelPatterns.parse(trimmed, catalog.listModels(), true);
if (parsed.model().isPresent()) return parsed.model().get().id();

// 无斜杠且整串恰为某 provider 名 → 默认模型/首条（Java 既有，pi 无此支路，保留并注释）
...
// 其余：provider 推断同现状；不中 → 自定义 id 兜底
// 兜底 pattern 的后缀只剥合法 level（照 pi buildFallbackModel 前的 lastColon 处理 :567-580）
System.err.println("Model \"" + fallbackPattern
    + "\" not found for provider \"" + effectiveProvider + "\". Using custom model id.");
return ModelId.of(effectiveProvider, fallbackId);
```

既有 `ModelResolverTest` 全部保持绿（含冒号 id 先精确、`:high` 合法后缀照切、未知 id 兜底、
裸 provider 默认）；新增：严格模式非法后缀保留在自定义 id 里（`x:random` → 自定义 id `x:random`，
对齐 pi CLI）。**裸 id 歧义不报错**，落进子串路后由排序器取确定性首中（§4 裁决①）。

### 3.6 coding-agent 装配（`AgentSession.assemble`）

新增 `core/ModelScoping.java`（帮手，控制 AgentSession 行数）：

```java
/** --models/settings patterns → 解析结果；启动期诊断打 stderr（pi resolveModelScope 打印面）。 */
final class ModelScoping {
    record Scope(List<ScopedModel> models) {
        boolean isEmpty() { return models.isEmpty(); }
    }
    static Scope resolve(Args args, SettingsManager settings, ModelCatalog catalog) {
        var patterns = (args.models() != null && !args.models().isEmpty())
            ? args.models() : settings.accessors().getEnabledModels();
        if (patterns == null || patterns.isEmpty()) return new Scope(List.of());
        var result = new ModelScopeResolver(catalog).resolve(patterns);
        for (var d : result.diagnostics()) {
            System.err.println("Warning: " + d.message());   // pi: chalk.yellow("Warning: " + msg)
        }
        return new Scope(result.scopedModels());
    }

    /** 初始模型（pi main.ts:499-516）：saved 默认在 scope 内则用它，否则 scoped[0]；都无则 empty。 */
    static Optional<ScopedModel> initial(Scope scope, String defaultProvider, String defaultModel,
                                         ModelCatalog catalog) { ... }
}
```

`assemble` 改动（约 20 行）：

```java
var catalog = com.pijava.ai.provider.ModelsJsonConfig.allModels();
var models = new DefaultModelResolver(catalog);
...
var scope = ModelScoping.resolve(args, settings, catalog);   // 诊断此处已打印
ScopedModel initial = null;
String modelPattern = args.model();
if (modelPattern == null || modelPattern.isBlank()) {
    var fromScope = ModelScoping.initial(scope, effective.defaultProvider, effective.defaultModel, catalog);
    if (fromScope.isPresent()) initial = fromScope.get();
}
ModelId<?> model = initial != null ? initial.model().id()
    : models.resolve(modelPattern == null || modelPattern.isBlank() ? providerName : modelPattern,
                     providerName);
session.setScopedModels(scope.models());   // 薄壳字段（见下）
```

resume 语义：pi 在 `hasExistingSession` 时跳过 scoped 初始模型。Java 模型在 assemble 时还不知
session 是否有存量；但 resume 的 record-log fold 随后会把 lane 模型还原（`SessionPersistence`），
assemble 给的初值会被覆盖——实施时加一条「resume 后 model 来自 fold」夹具确认，若 fold 不还原
模型则在 `resolvePersistent` 后补 scoped-skip 判断（两选一见测试结果，稿中明示）。

思考档接线（`SessionSetup.thinkingLevelFor` 加一参 `Optional<String> scopeLevel`，优先级照 pi）：
`--thinking` ＞ `--model :suffix`（现状） ＞ **选中 scoped 模型的显式 level**（新增）＞ settings 默认。

### 3.7 cycling：帮手＋会话薄壳＋RPC＋TUI

`core/ScopedModelCycler.java`（纯函数）：

```java
/** pi agent-session.ts:2519-2560 的正向轮转核（不含 auth 过滤：Java 目录即全集）。 */
final class ScopedModelCycler {
    /** scope 非空：scope 内仍在目录中的模型轮转，≤1 ＝当前；scope 空：全目录轮转。 */
    static ModelId<?> next(ModelId<?> current, List<ScopedModel> scope, ModelCatalog catalog) { ... }
    /** 下一个模型携带的显式思考档（无则 empty＝继承当前档）。 */
    static Optional<String> nextLevel(...) { ... }
}
```

- current 不在 scope ⇒ 下标 0（pi：`currentIndex===-1 → 0`）；`(i+1)%len`；
- scope 模型经 `catalog.find(provider,id)` 过滤（models.json 刷新后消失的剔除）；
- 空 scope ⇒ 全目录序轮转（等同 RPC 现状、pi `_cycleAvailableModel`）。

`AgentSession` 加（字段＋薄壳，不进大段逻辑）：

```java
private List<ScopedModel> scopedModels = List.of();
void setScopedModels(List<ScopedModel> s) { this.scopedModels = List.copyOf(s); }
/** Ctrl+P / cycle_model：scoped 优先，显式 scope 级别覆盖思考档；单模型不动。 */
public void cycleModelForward() {
    var next = ScopedModelCycler.next(harness.getModel(), scopedModels, catalog);
    if (next.equals(harness.getModel())) return;
    harness.setModel(next);
    ScopedModelCycler.levelFor(next, scopedModels)
        .ifPresent(level -> setThinkingLevel(ThinkingLevels.parse(level)));
}
```

（catalog 引用由 assemble 把已构造的 `ModelsJsonConfig.allModels()` 存为字段；resolver 包它。）

接线两处：
- `rpc/RpcDispatcher.java:150-152`：`case CycleModel c -> session.cycleModelForward();`，删私有
  `cycleModel(AgentSession)`（其全目录轮转移入 cycler，行为对空 scope 等价）；
- `tui/app/PiTuiApp.java:450`：default 注释里移除 model.cycle，加
  `case KeybindingsManager.MODEL_CYCLE -> session.cycleModelForward();`
  （ctrl+p 键与动作 ID 已存在；TUI 无模型常驻标签，切换静默——与 Ctrl+L 选择器现状一致）。

### 3.8 文件清单

**新增（8，全 ≤200 行；ai 6＋agent 2）**：

| 文件 | 内容 |
|---|---|
| `pi-java-ai/.../model/ScopedModel.java` | record |
| `pi-java-ai/.../model/ModelScopeDiagnostic.java` | record＋enum＋文案工厂 |
| `pi-java-ai/.../model/ModelScopeResult.java` | record |
| `pi-java-ai/.../model/ModelGlob.java` | minimatch 子集翻译器＋模式缓存 |
| `pi-java-ai/.../model/ModelPatterns.java` | 精确/子串/alias/后缀纯函数（包可见） |
| `pi-java-ai/.../model/ModelScopeResolver.java` | scope 主体 |
| `pi-java-coding-agent/.../core/ModelScoping.java` | patterns 来源＋诊断打印＋初始选择 |
| `pi-java-coding-agent/.../core/ScopedModelCycler.java` | 轮转纯函数 |

**修改（5）**：
`DefaultModelResolver.java`（匹配核换委托）、`AgentSession.java`（装配段＋scoped 字段＋薄壳）、
`SessionSetup.java`（thinkingLevelFor 加 scope 级）、`RpcDispatcher.java`（cycle 改会话方法）、
`PiTuiApp.java`（MODEL_CYCLE 分支）。可能小动 `ModelSelectorScreen` 之外无 TUI 文件。

### 3.9 测试计划

**pi-java-ai**：
1. `ModelGlobTest`（≈20 测）：§3.2 边界清单逐条（`*/?/**/[]/未闭合/转义/点门/nocase/锚定`）；
2. `ModelPatternsTest`（≈14 测）：精确三路唯一性、裸 id 歧义 undefined、子串 id/name、
   alias vs dated 优先、dated 降序、`-latest` alias、同 id 稳定序、合法后缀递归、非法后缀
   warning（宽容模式）、严格模式非法后缀保留、冒号 id 先精确、内层 warning 丢外层级；
3. `ModelScopeResolverTest`（≈12 测，直接移植 pi `model-resolver.test.ts:223-319` 夹具）：
   混合 patterns 的 scoped 序与级别、两码诊断数组（message/pattern 逐字）、console 不打印
   （打印在 coding-agent 装配面）、`[` id 精确先于 glob、`:high` glob 后缀、`anthropic/*`
   展开、双车道（裸 id glob）、去重、空表；
4. `ModelResolverTest` 既有全绿＋新增 CLI 严格后缀 2 测。

**pi-java-coding-agent**：
5. `ModelScopingTest`：`--models` 优先于 settings；settings 回退；诊断逐字到 stderr
   （cap 系统 err，仿现有捕获手法）；saved-in-scope 优先／出 scope 用 scoped[0]／无 scope empty；
6. `ScopedModelCyclerTest`：scope 轮转/回绕/当前不在表归 0/≤1 不动/显式 level 随切换/空 scope
   全目录序/已消失模型过滤；
7. 装配集成：`AgentSession` 带 `--models` 创建 ⇒ harness 初始模型＝scoped[0]（真目录断言
   `--models "google/*"` 一类）；
8. `RpcDispatcherTest`（既有则扩）：scope 非空时 cycle_model 在 scope 内轮转；
**pi-java-tui**：
9. `PiTuiApp` 动作测试：MODEL_CYCLE 触发会话模型轮转（照现有 MODEL_SELECT 测试套路）。

### 3.10 commit 与验证

| commit | 内容 | 验证 |
|---|---|---|
| `feat(ai): port model scope resolver — glob, substring, alias-dated ordering, diagnostics (B137)` | §3.1-3.5＋ai 测试 1-4 | `JAVA_HOME=D:/soft/jdk/graalvm-jdk-25 mvn -pl pi-java-ai -am test` |
| `feat(ai): wire --models patterns into scoped startup selection, cycling and Ctrl+P (B137)` | §3.6-3.7＋agent/tui 测试 5-9 | 触下游，收尾全量 `mvn clean verify`（~34 min，11 模块 0 失败）＋checkstyle |

显式 `git add <path>`；无 `System.out`（诊断走 `System.err`）；无 @SuppressWarnings；
文件 ≤500。

---

## 4. 裁决点（请审核时拍板）

① **裸 id 跨 provider 歧义（16 个国内镜像 id）**：pi CLI 在认证面裁决（唯一已认证胜，否则
硬错误要求 `--provider`）。Java 无 auth 门。**建议**：不引硬错误——精确歧义落子串路，
按目录序取确定性首中（实测序＝国际家先于 `-cn` 家：minimax→minimax-cn、moonshotai→cn、
zai→zai-coding-cn），与现状首条行为一致、零回归；差异记入 docs/04 B137 闭环备注。
备选：照搬硬错误（裸 `--model kimi-k2.6` 将不可用，必须写家限定）。

② **localeCompare 替代**：JS `localeCompare` 随运行环境 locale 变化（本身不确定）。
**建议**：码点序（`String.compareTo` 语义）降序＋稳定序兜底；dated 后缀场景与 pi 等价
（`-20250929` 码点序＝时间序）。差异仅在「同前缀子串多命中且含大小写/标点差异」的挑选，登记。

③ **minimatch 子集边界**：做 `* ? ** [class] 转义 点门 nocase`；**不做** brace `{a,b}`
展开、extglob、posix class、`#`/`!` 模式——花括号在 id 字符集外，用户写 `{a,b}` 会得 no-match
诊断（明确报错，不静默），窄差登记，需要时另批。

④ **Ctrl+P 反向**：只做正向（键位/RPC/pi rpc-mode 都只有正向；反向 shift+ctrl+p 不登记动作）。

⑤ **auth 可用性过滤**：scope 与 cycling 都对**全目录**（pi 对已认证快照）。Java 全产品无
解析期 auth 门，属既有整体差异，不随 B137 打开。

⑥ **resume 跳过 scoped 初始模型**：优先靠 record-log fold 自然覆盖；若实测 fold 不还原模型，
在持久化恢复点补判断（§3.6），不因此扩大本批。

---

## 5. 闭环回填计划（实施完成后）

- docs/04：**B137 划销**（备注：glob/子串/alias 排序/两码/`--models`/Ctrl+P 全接；残留①-⑤窄差）；
- docs/06：批次记录＋存活表去行；
- docs/08：批 3b 行改 ✅ 闭环（commit/测试数/加权不变说明）；
- map/02 头部批 3 段、map/04 行 5 备注（enabled models 已被 cycling 消费，选择器 UI 仍存疑）；
- docs/05：总表百分比不变（B 类无加权单元），读数段说明；
- docs/12 横幅转「已闭环」＋实施差异（同 docs/11 体例）；
- 记忆新增批 3b 文件、更新 MEMORY.md 与 docs41-active-plan 指针。
