# 23 - L3 agent-core 新锚点重测与对齐设计（W1–W3）

> 【背景摘要】L3 的 90 个计分单元（9 内置工具 ＋ 81 能力单元）已按新锚点 `56cae7823` 全量重测。旧锚点的 180/180＝100% 不能直接沿用：新锚点出现 3 个候选差异——模板命令的引号空参数＋JS 空白、技能路径的 Windows 大小写比较键、grep 对孤立 CR 的行切分。其中技能路径经 RED 复核为 Java 平台语义等价（W2 无改动），实际实施 W1／W3。Identity、`reachesTools` 与 AI 新类型适配已由 docs/14／docs/21／B264／B267 落齐，重测为对齐。另按 docs/08 §5 的层归属，把 #77（恢复会话 active tools，w2）与 #24（branch summary 生成，w1）两行转出 L3 分母，交由 L4 重测。
>
> 文档状态：**已实施并闭环（2026-11-05）**。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。pi-java 代码提交：`14be51a9`（main，2026-11-05）；前序基线 `cc7e4aec`（2026-11-04）。旧基线 pi-java：`edc8749d`（map/03 旧读数）。

## 0. 范围、口径与术语

- **层范围**：`pi-java-agent-core` ＋ 其对应的 `packages/agent/src/*`（agent loop 本体）与 `packages/coding-agent/src/core/*` 主流副本中、map/03 已登记的 90 个单元。会话持久化/压缩语义按 docs/08 §5 归 L4；`_startRun` 等宿主启动面归 L5；TUI 渲染与 render-utils 归 L7。
- **计分口径**：权重＝用户可观察影响 × 频率（3/2/1/0）；完成系数 对齐 1.0／存疑 0.5／缺失 0。R1（pi 已删参照物）与 R5（`bin pi` 不可达）权重 0、不进分母；转出行保留行与证据、权重 0，也不进未加权分母（docs/22 先例）。
- **判定三档**：对齐／存疑／缺失。旧锚点已对齐的行，新锚点必须重新取证；未重测不得沿用旧判定。
- **数据通路**：L3 只消费 L1（ai）与 L2（mcp）已闭环的接口；L4/L5 的未闭环行为不得当作 L3 稳定输入。
- **术语**：`identity` ＝ 客户端身份（UA＋归因头）；`reachesTools` ＝ 工具调用可触达的其他工具声明；`quoted-empty args` ＝ 引号包住的空参数（`""`／`''`）；`comparison key` ＝ 仅用于比较的路径键（不做文件访问、不用于展示）。

## 1. 重测结论

### 1.1 方法

1. 以 `git diff --stat ea448f454..56cae7823` 枚举 L3 归属路径的全部变化（不读工作树）。
2. 每个变化按 docs/08 的层归属表分类：L3 实施／已由前包对齐／转 L4／转 L5／R5。
3. map/03 的 90 行逐行重判；变化只落在证据被新锚点改写的行。
4. AI 新类型、`reachesTools`、旧裁决行单独复核（§1.4–§1.6）。

### 1.2 变化清单与归属

`ea448f454..56cae7823` 中落在 L3 归属面的文件：

| pi 文件 | 变化 | 归属 | 结论 |
|---|---|---|---|
| `packages/agent/src/agent-loop.ts` | +10：`DEFAULT_AGENT_IDENTITY`＋`mergeClientIdentity` | 已对齐（docs/14 / B266） | 对齐 |
| `packages/agent/src/agent.ts` | +10：`AgentOptions.identity`／`Agent.identity` 透传 | 已对齐（docs/14 / B266） | 对齐 |
| `packages/agent/src/proxy.ts` | 22 行（增删合计）：`ToolCallArgumentsStream` 替换 `partialJson` | R5（#68 `streamProxy` 仍不可达） | 权重 0 |
| `packages/coding-agent/src/core/prompt-templates.ts` | +9：`parseCommandArgs` 的 `hasToken` | **L3 实施（W1）** | 缺失 |
| `packages/coding-agent/src/core/skills.ts` | 19 行（增删合计）：`getResourcePathKey`＋`isPathWithin` | **L3 复核（W2：Java 平台 Path 语义已等价，无改动）** | 对齐 |
| `packages/coding-agent/src/utils/paths.ts` | +46：路径比较键与 `isPathWithin` | L3 复核（W2）：Java 无需新增工具；L4/L5 复用时按同一判据评估 | —— |
| `packages/coding-agent/src/core/tools/grep.ts` | +6/-2：孤立 CR 的切行；`--no-config` | **L3 实施（W3）**；`--no-config` 已等价 | 缺失 |
| `packages/coding-agent/src/core/tools/render-utils.ts`、`renderers/bash.ts`、`renderers/read.ts` | 渲染标签/耗时 | L7 TUI | 不属 L3 |
| `packages/coding-agent/src/core/agent-session.ts` | 257 行：恢复工具、run 启动、省略修复、路径比较 | L4／L5（见 §1.3） | 转出/挂账 |
| `packages/coding-agent/src/core/compaction/*`、`session-manager.ts` | 压缩语义、持久化回滚、fork 单 fd | L4 | 挂账 |
| `packages/coding-agent/src/core/sdk.ts`、`settings-manager.ts`、`provider-composer.ts` 等 | 摘要鉴权、settings 新键、classifier compat 等 | L4／L5 | 挂账 |

`packages/agent/src/types.ts`、`stream-fn.ts`、`core/messages.ts` 在区间内无变化；`streamProxy` 仍只被 `agent/src/index.ts` 再导出、单测与 README 引用，coding-agent 全包零 import ⇒ R5 维持。

### 1.3 行判定变化（90 行中只有 3 行改变）

| 行 | 单元 | 权重 | 新锚点事实 | 旧判定 → 新判定 |
|---|---|---:|---|---|
| 工具 #5 | `grep` | 3 | `getFileLines` 不再把孤立 `\r` 换成 `\n`（`grep.ts:153`） | 对齐 → **缺失（W3）** |
| #63 | `loadSkills` / `loadSkillsFromDir` | 2 | 去重键改为 `getResourcePathKey`；来源推断改 `isPathWithin`（`skills.ts:430,465-468`） | 对齐 → **对齐（W2 复核，无改动）** |
| #64 | `loadPromptTemplates` 等四函数 | 2 | `parseCommandArgs` 保留引号空参数＋JS `\s` 空白（`prompt-templates.ts:25-58`） | 对齐 → **缺失（W1）** |

其余 87 行按新锚点证据重判为对齐，其中 #77／#24 按层归属转出（§1.7）。

> #63 的「存疑」在 RED 阶段被证伪：Java `WindowsPath` 的 `equals`／`startsWith` 本就大小写不敏感且按段比较，`toRealPath` 又返回磁盘真实大小写，与 pi `getPathComparisonKey`／`isPathWithin` 在 skills.ts 两处消费点上行为等价。详见 §3。

### 1.4 AI 新类型适配复核（docs/08 L3 第三条）

| 新类型/字段 | pi 证据 | pi-java 现状 | 结论 |
|---|---|---|---|
| `ClientIdentity`＋`DEFAULT_AGENT_IDENTITY` | `agent-loop.ts:37-41`；`agent-loop.ts:415` 合并；`agent.ts:139-147,485-486` | `AgentIdentity.DEFAULT`（`:14-20`）；`PiLoopRunner` 在请求前 `ClientIdentity.merge(DEFAULT, config.identity())`（`:288-296`）；`HarnessConfig.identity` 透传 | 对齐（docs/14） |
| `ToolCallArgumentsStream` | `proxy.ts:334-358` | `pi-java-ai` 已有 `ToolCallArgumentsStream`；agent-core 不直接解析流式参数 | 对齐（proxied 面 R5） |
| `argumentsError` / `responseId` | `ai/src/types.ts` 新增 | B264 已落两字段的生产与执行前拒绝；agent-core 只做机械传播 | 对齐（B264） |
| `AssistantMessage.deferred` | docs/20 #80 | 11 个重建点＋`Message.fromPartial` 全传播；agent-core 无遗漏消费点 | 对齐（docs/20） |
| `Usage.Cost.totalFromProvider` 等 | docs/15 | agent-core 不重算 usage，只透传 | 对齐（docs/15） |

### 1.5 `reachesTools` 复核（docs/08 L3 第四条）

- pi `ToolDefinition.reachesTools`（`core/extensions/types.ts:635-648`）；`agent-session.ts:1539` 在 `getAllTools()` 投影里透传。
- pi-java `AgentTool.reachesTools()` 默认方法（`AgentTool.java:86-92`）＋`ToolReach`；MCP（`McpExtension.java:304`、`McpLifecycle.java:281,316-319`）与 tool-search（`ToolSearchTool.java:177-180`）已消费。**L3 工具契约对齐**（docs/21）。
- 宿主投影 `ExtensionToolInfo` 仍未携带 `reachesTools`（`ModelToolControl.java:251-263`、`ExtensionToolInfo.java:19-24`）⇒ 属 L5 扩展 API 面，登记 B276，不在 L3 实施。

### 1.6 旧裁决行复核（docs/04 §4 在册项）

| 行/裁决 | 新锚点复核 | 结论 |
|---|---|---|
| #54 Entry union | `session-manager.ts` 区间变化只有 `pathsEqual`、append 回滚与 fork 单 fd；Entry union 与 v3 平铺未变 | 裁决继续有效 |
| #58 `convertToLlm` | `core/messages.ts` 零变化 | 继续有效 |
| #78 `latest()`/`find()` | `listSessionsFromDir`/`findMostRecentSession` 未变；只换了 `pathsEqual` | 继续有效 |
| #100 系统提示作为 system 消息 | `ai/src/types.ts` 无 `TranscriptContext` 形状变化 | 继续有效 |
| #14 `QueueMode.All` | `packages/agent/src/types.ts` 零变化 | 继续有效 |
| #80 `streamingBehavior` | 两门语义随 `_startRun` 重构搬家（L5），本层不改判 | 保持；L5 重测时复核门文本 |
| #82 `retry.provider.*` | `settings-manager.ts` 未改 retry 键 | 继续有效 |
| #57 四个消息类型 | `core/messages.ts` 零变化 | 继续有效 |
| #90/#91 Java 自有面 | 无新锚对位 | 继续有效 |

### 1.7 转出（L3 分母 → L4）

| 行 | 单元 | 权重 | 新锚点变化 | L4 归属 |
|---|---|---:|---|---|
| #77 | 会话恢复（resume seed＋record replay） | 2 | `usesDefaultTools` 门（`agent-session.ts:538`）；`_restoreToolsFromTranscript` 现取**分支上全部 system 消息**、合并 `toolsAdded`/`toolsRemoved`、补晚注册扩展工具、应用 `+name/-name`（`:1846-1882`）；晚注册工具名保留 pending（`bd3c9b823`） | docs/08 §5 L4 第 1 条；登记 **B271** |
| #24 | branch summary 生成 | 1 | `thinkingLevel` 传入摘要请求（`branch-summarization.ts:302-360`，`269afc66a`） | docs/08 §5 L4 第 3 条；登记 **B272** |

转出后 L3 计分 88 行／177 权重；行与旧证据保留，判定写「转出（L4）」。

### 1.8 账面

| 域 | Σ权重 | Σ(w×c) | 加权 | 未加权 |
|---|---:|---:|---:|---:|
| 内置工具（9 行，#5 缺失） | 21 | 18.0 | 85.71% | 8/9 |
| 能力单元（79 计分＋2 转出；#64 缺失） | 156 | 154.0 | 98.72% | 78/79 |
| **合计（88 计分＋2 转出）** | **177** | **172.0** | **97.18%** | **86/88** |

复算：177＝180−2（#77）−1（#24）；172.0＝177−3（grep）−2（#64）。未加权 86＝90−2 转出−2 缺失。W1／W3 实施后：**177/177＝100%，88/88**。

---

## 2. W1（B268）：模板命令保留引号空参数

### 2.1 pi 事实（`core/prompt-templates.ts:25-58`，逐字）

```ts
export function parseCommandArgs(argsString: string): string[] {
	const args: string[] = [];
	let current = "";
	// Tracks whether a token has started, so quoted empty strings ("" or '') are kept as arguments
	let hasToken = false;
	let inQuote: string | null = null;

	for (let i = 0; i < argsString.length; i++) {
		const char = argsString[i];

		if (inQuote) {
			if (char === inQuote) {
				inQuote = null;
			} else {
				current += char;
			}
		} else if (char === '"' || char === "'") {
			inQuote = char;
			hasToken = true;
		} else if (/\s/.test(char)) {
			if (hasToken) {
				args.push(current);
				current = "";
				hasToken = false;
			}
		} else {
			current += char;
			hasToken = true;
		}
	}

	if (hasToken) {
		args.push(current);
	}

	return args;
}
```

命令识别（`prompt-templates.ts:312`）：`text.match(/^\/([^\s]+)(?:\s+([\s\S]*))?$/)`；`argsString = match[2] ?? ""`，再 `parseCommandArgs(argsString)`。

### 2.2 pi-java 现状

`PromptTemplates.java:226-254` 用 `current.length() > 0` 判空 ⇒ `""`／`''` 被丢弃；空白只认 `' '` 与 `'\t'`。`PromptTemplateExpansion.java:13` 的正则 `^/([^\s]+)(?:\s+([\s\S]*))?$` 用 Java `\s`（不含 NBSP 等 Unicode 空白）。

可观察差异：`/review "" notes` ⇒ pi `argv[1]=""`（`${2:-d}` 落默认、`$2` 展开为空），java 只有 `["notes"]`，`${2:-d}` 落 `d`。

### 2.3 改动

1. `parseCommandArgs` 增加 `boolean hasToken`：引号分支置 true；空白分支仅在 `hasToken` 时产出 token 并复位；末尾按 `hasToken` 产出。
2. 空白判定改为 **JS `/\s/` 精确集合**：`\t \n \u000B \f \r space \u00A0 \u1680 \u2000-\u200A \u2028 \u2029 \u202F \u205F \u3000 \uFEFF`。放在 `PromptTemplates` 内（`isJsWhitespace(char)`＋一个共享字符类常量），`parseCommandArgs` 与 `PromptTemplateExpansion` 的正则共用。
3. `PromptTemplateExpansion` 正则改用同一字符类（名字段 `[^WS]+`、间隔 `WS+`）；`[\s\S]` 保留（等价于任意字符）。
4. 不引入新类、不改 `substituteArgs`（其五支与 JS 真值语义已对齐）。

### 2.4 测试（RED-first）

`PromptTemplatesTest`：

- `parseCommandArgs("\"\" x")` ⇒ `["", "x"]`；`parseCommandArgs("'' ")` ⇒ `[""]`；`parseCommandArgs("a \"\" b")` ⇒ `["a", "", "b"]`；`parseCommandArgs("  ''  ")` ⇒ `[""]`。
- 空白：`"a\u00A0b"` ⇒ `["a","b"]`；`"\u3000\u3000"` ⇒ `[]`；`"\uFEFFx"` ⇒ `["x"]`；`"\u2028a\u2029b"` ⇒ `["a","b"]`。
- `PromptTemplateExpansion`：`/greet\u00A0""` 走模板展开（模板名 `greet`、参数 `[""]`），而非整体透传。
- 既有参数断言（`a b 'c d' e`、`"double quoted" x`、`"  spaced  "`）保持不变。

变异探针：把 `hasToken = true` 从引号分支删掉 ⇒ 空引号测试红；把空白判定退回 `' '||'\t'` ⇒ NBSP 测试红。两者已还原后收口。

### 2.5 风险

- 该函数被 `/template` 展开与 slash 命令消费；空参数会进入 `${N:-default}` 的真值判断——与 pi 一致（空串在 JS 为 falsy），不会把默认值误吞成参数。
- `\uFEFF` 属 JS `\s` 但不属 Java `Character.isWhitespace`，必须用显式集合，不能用 `Character.isWhitespace`。

---

## 3. W2（B269）：技能路径比较键复核（无实现改动）

### 3.1 pi 事实

`utils/paths.ts:28-34`：

```ts
export function canonicalizePath(path: string): string {
	try {
		return realpathSync(path);
	} catch {
		return path;
	}
}
```

`utils/paths.ts:46-70`（逐字）：

```ts
export function getPathComparisonKey(path: string): string {
	const pathApi = getPlatformPathApi();
	const resolvedPath = pathApi.resolve(path);
	return pathApi === win32 ? resolvedPath.toLowerCase() : resolvedPath;
}

/** `node:path` rules for the current platform, read at call time so tests can simulate Windows. */
function getPlatformPathApi(): typeof win32 {
	return process.platform === "win32" ? win32 : posix;
}

/** True when both paths name the same location (see `getPathComparisonKey`). */
export function pathsEqual(left: string, right: string): boolean {
	return getPathComparisonKey(left) === getPathComparisonKey(right);
}

/** True when `child` is `parent` or inside it (see `getPathComparisonKey`). */
export function isPathWithin(parent: string, child: string): boolean {
	const parentKey = getPathComparisonKey(parent);
	const childKey = getPathComparisonKey(child);
	if (childKey === parentKey) return true;
	// Filesystem roots (`/`, `C:\`) already end with a separator.
	const pathSep = getPlatformPathApi().sep;
	return childKey.startsWith(parentKey.endsWith(pathSep) ? parentKey : `${parentKey}${pathSep}`);
}
```

`core/source-info.ts:44-49`：

```ts
export function isSyntheticPath(path: string): boolean {
	return path.startsWith(BUILTIN_PATH_PREFIX) || path.startsWith("<");
}

/** Key for deduplicating resource paths: synthetic paths as-is, file paths via realpath and platform casing. */
export function getResourcePathKey(path: string): string {
	return isSyntheticPath(path) ? path : getPathComparisonKey(canonicalizePath(path));
}
```

`core/skills.ts:425-434`（去重）与 `:462-475`（来源推断）：

```ts
			// Resolve symlinks to detect duplicate files
			const realPath = getResourcePathKey(skill.filePath);

			// Skip silently if we've already loaded this exact file (via symlink)
			if (realPathSet.has(realPath)) {
				continue;
			}
```

```ts
	const getSource = (resolvedPath: string): "user" | "project" | "path" => {
		if (!includeDefaults) {
			if (isPathWithin(userSkillsDir, resolvedPath)) return "user";
			if (isPathWithin(projectSkillsDir, resolvedPath)) return "project";
		}
		return "path";
	};
```

测试锚点 `test/paths.test.ts:44-56`：Windows 下 `c:\Users\me\.agents\skills` 与 `C:\Users\Me\.agents\skills` 比较键相等；`C:\Users\Me` 是 `c:\users\me\project` 的父；`C:\Users\Me2` 不是；根 `C:\` 与 UNC `\\server\share` 也正确。

### 3.2 pi-java 现状与 RED 证伪

- `LoadSkillsResult.dedupe`（`LoadSkillsResult.java:35-55`）用 `canonicalize(file)`（`toRealPath`，失败回落绝对 normalize）作为 `HashSet` 键。
- `SkillDiscovery.inferSource`（`SkillDiscovery.java:125-136`）用 `Path.startsWith`（绝对化＋normalize 后逐段比较）。
- 两者都不手写 `toLowerCase`，但 **Java `WindowsPath` 的 `equals`／`startsWith` 本身在 Windows 上大小写不敏感、且按路径段比较**（`Path` 契约的「系统相关」语义）；`toRealPath` 又返回磁盘真实大小写。因此 `getPathComparisonKey`（resolve＋Windows 小写）与 `isPathWithin`（段边界前缀）在这两处消费点上的可观察结果与 Java 等价。
- 实证（本机 Windows，jshell）：`Path.of("C:/Users/me/project").startsWith(Path.of("c:/users/ME"))` ⇒ `true`；`Path.of("C:/Users/Me2").startsWith(Path.of("c:/users/me"))` ⇒ `false`；`Path.of("C:/Users").startsWith(Path.of("c:/"))` ⇒ `true`；`Path.of("C:/Users/me/project").equals(Path.of("c:/users/ME/project"))` ⇒ `true`。
- 先行添加的两个 Windows 回归用例（`inferSourceUsesCaseInsensitiveKeysForWindows`、`dedupeTreatsCaseVariantsAsSameFileOnWindows`）**在改动前即通过**，证伪原「java 判 EXPLICIT」的差异假设。

结论：**#63 维持对齐，无实现改动**。不新增 `PathKeys` 工具（无消费缺口，避免 dead code）；L4 的 B274（session-manager `pathsEqual`）若存在字符串级比较，再按同一判据评估。

### 3.3 改动

无生产代码改动。仅保留 §3.4 的两个 Windows 回归测试，锁定「Java `Path` 平台语义即比较键」这一前提；若未来有人把 `Path.startsWith` 改成字符串 `startsWith`（大小写敏感），测试会立即变红（见变异探针）。

品牌分叉 `.pi-java` 目录名维持现状（docs/22 #71 已验证方言）。

### 3.4 测试（回归锁，先行通过）

`SkillDiscoveryTest`（新增，Windows-only，`Assumptions.assumeTrue(File.separatorChar=='\\')`）：

- `agentDir=tmp/Agent`，显式路径 `tmp/agent/skills/mine`（大小写不同）⇒ 来源 `USER`；`cwd=tmp`，显式 `tmp/.PI-JAVA/skills/ours`（目录名大小写变体）⇒ `PROJECT`。
- 去重回归：同文件经 `Caseskills`／`caseskills` 两种拼写经 `dedupe` 后只剩 1 条、无 collision 诊断。
- 既有 `explicitPathUnderDefaultDirsInfersSource` 逐字保留（大小写相同时仍通过）。

变异探针：把 `inferSource` 的两处 `Path.startsWith` 改成大小写敏感的字符串 `startsWith` ⇒ 来源用例红；把 `LoadSkillsResult.canonicalize` 去掉 `toRealPath` ⇒ 去重用例红（均已在实施期验证后还原）。

### 3.5 风险

- 该结论依赖 JDK `WindowsPath` 的「大小写不敏感＋按段比较」契约；回归用例锁定行为，JDK 升级若改变语义会立即暴露。
- Windows NTFS 可按目录启用大小写敏感（WSL 互操作），pi 明确忽略此例外；Java 同样忽略（触发条件：真实大小写敏感目录上的技能来源误判）。
- `toRealPath` 在文件不存在时抛异常，回落绝对 normalize；与 pi `canonicalizePath` 的「失败返回原字符串」对已存在技能文件等价，缺失文件不经 dedupe。

---

## 4. W3（B270）：grep 对孤立 CR 的行切分

### 4.1 pi 事实（`core/tools/grep.ts:148-166`，逐字）

```ts
						const getFileLines = async (filePath: string): Promise<string[]> => {
							let lines = fileCache.get(filePath);
							if (!lines) {
								try {
									const content = await ops.readFile(filePath);
									lines = content.replace(/\r\n/g, "\n").split("\n");
								} catch {
									lines = [];
								}
								fileCache.set(filePath, lines);
							}
							return lines;
						};

						// --no-config: the tool contract (literal, glob, limit, ...) must not depend on
						// ambient user config like RIPGREP_CONFIG_PATH. Users can customize via bash or extensions.
						const args: string[] = ["--no-config", "--json", "--line-number", "--color=never", "--hidden"];
```

### 4.2 pi-java 现状

`GrepTool.java:359-367`：

```java
    private static List<String> fileLines(Path file) {
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return List.of(content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));
        } catch (IOException e) {
            return List.of();   // pi: catch ⇒ lines = []
        }
    }
```

多出的 `.replace("\r", "\n")` 把孤立 CR 当作换行，导致上下文/行号与 ripgrep 不一致：ripgrep 只按 `\n` 切行，孤立 CR 留在行内。例：`"before\rinside needle\nafter"`，当前 java 报第 2 行 `inside needle`，pi 报第 1 行 `before\rinside needle`。

### 4.3 改动

1. `fileLines` 删除 `.replace("\r", "\n")`，只保留 CRLF→LF，随后 `split("\n", -1)`。
2. `--no-config` **不需要移植**：java grep 是纯 Java 引擎（裁决①，不移植 rg 外壳），不存在 `RIPGREP_CONFIG_PATH` 等外部配置；该契约天然成立，设计留痕不登缺口。

### 4.4 测试（RED-first）

`GrepToolTest`：

- `outsideContextLinesMatchRipgrepForLoneCr`：文件 `"before\rinside needle\nafter"`，`context=1` ⇒ 输出 `f.txt:1: before\rinside needle\nf.txt-2- after`（当前实现会给 `f.txt-2: inside needle`＋两条上下文，测试红）。
- 无 context 同文件 ⇒ `f.txt:1: before\rinside needle`。
- CRLF 回归：`"l1\r\nl2 needle\r\n"` ⇒ 行号 2、内容不含 `\r`（现有行为保持）。
- 尾空段保持：以 `\n` 结尾的文件 `split("\n", -1)` 不丢尾段（既有 `TruncationUtils` 测试不受影响）。

变异探针：恢复 `.replace("\r", "\n")` ⇒ 孤立 CR 测试红；改用 `split("\n")`（无 `-1`）⇒ 尾空段测试红。

### 4.5 风险

- 输出中保留 `\r` 会原样进入模型可见文本（pi 同）；JSON 序列化时 `\r` 转义，无额外处理。
- 只改上下文取行；匹配仍由 Java 正则引擎逐行跑，CR 在行内会被 `.`／`^$` 语义覆盖，与 pi `rg` 的逐行匹配面一致（窄差 D1 既有登记不变）。

---

## 5. 回归与验证

### 5.1 新增/修改测试

| 模块 | 测试 | 覆盖 |
|---|---|---|
| agent-core | `GrepToolTest` | 孤立 CR 行号/上下文、CRLF 回归、尾空段 |
| coding-agent | `PromptTemplatesTest` | 引号空参数、JS 空白集合、既有 23 行 |
| coding-agent | `SkillDiscoveryTest` | Windows 大小写来源推断、去重回归 |
| coding-agent | `PromptTemplateExpansionTest` | NBSP 分隔下的模板识别 |

### 5.2 回归面（docs/08 L3 末条：21 工具、嵌套调用、工具暴露面）

- 内置工具全量：`BashToolTest`／`ReadToolTest`／`WriteToolTest`（若按工具分文件）／`EditToolTest`／`GrepToolTest`／`FindToolTest`／`LsToolTest`／`PowerShellToolTest`。
- 批次与两相：`ToolBatchConcurrencyTest`、`ToolBatchParityTest`、`PiToolRunnerTest`、`ToolChangeDeclarationTest`。
- 嵌套调用：`NestedToolCallE2eTest`、`NestedToolCallRunnerTest`、`NestedCallsRoundTripTest`、`NestedCallsFileOpsTest`、`EngineHookBridgeNestedArgumentsTest`。
- 工具暴露/激活：`DefaultToolsSelectionTest`、`DefaultToolsSettingsTest`、`ExtensionToolActivationTest`、`AgentSessionPendingToolsTest`、`RestoreToolsOnNavigateTest`、`ToolNameMatcherTest`（MCP alias/reachesTools 面）、`McpToolDefinitionTest`、`McpToolNamesTest`。
- 命令：`mvn test -pl pi-java-agent-core,pi-java-coding-agent -am`，随后 `mvn clean verify`（含 checkstyle/spotbugs，零错误零警告）。

### 5.3 验收口径

- W1／W3 的 RED 测试在实现前真实失败（非编译失败）；W2 的 Windows 用例先行通过，作为证伪与回归锁。
- 变异探针逐一验证后还原（§2.4、§3.4、§4.4）。
- `mvn clean verify` 全绿、测试数不少于当前基线（6,199）。
- 无 `System.out.println`、无 `@SuppressWarnings`、文件 ≤500 行。

---

## 6. 跨层转出与挂账（docs/04 新登 B268–B277）

| 编号 | 所属层 | 内容 | 证据 | 本包动作 |
|---|---|---|---|---|
| **B268** | L3 | 模板命令引号空参数＋JS 空白集合 | `prompt-templates.ts:25-58,312` | **实施（W1）** |
| **B269** | L3 | 技能路径 Windows 大小写比较键 | `paths.ts:46-70`；`source-info.ts:46-49`；`skills.ts:430,465-468` | **复核（W2，无改动；回归锁已加）** |
| **B270** | L3 | grep 孤立 CR 行切分 | `grep.ts:148-169` | **实施（W3）** |
| B271 | L4 | 恢复会话 active tools（全部 system 消息并集、`toolsRemoved`、晚注册扩展工具、`+name/-name`、`usesDefaultTools` 门） | `agent-session.ts:538,1846-1882`；`6f2f164ab`／`bd3c9b823` | #77 转出；L4 实施 |
| B272 | L4 | compaction 摘要语义：去掉固定输出上限、thinking 不进 `serializeConversation`、split-turn 应用 `/compact` 指令、summary auth 由 stream fn 解析、branch summary `thinkingLevel` | `compaction.ts:644-740,1062-1105`；`compaction/utils.ts:108-150`；`branch-summarization.ts:302-360`；`6fcb48670`／`15e7bdc14`／`adfc14461`／`6fed3cfb9`／`269afc66a` | #24 转出；L4 实施 |
| B273 | L4 | 恢复省略成对：`_prepareRetry` 现按 toolCallId 在投影中找工具结果并一起省略；java 溢出压缩路（`RecoveryOmissions.persist`）已按 toolCallId 配对，auto-retry 路（`persistRetryOmission`）仍只省助手，L4 复核补齐 | `agent-session.ts:1252-1287,3868`；`02726086d` | L4 实施 |
| B274 | L4 | 会话持久化细节：`pathsEqual`（session-manager 四处）、append 失败回滚内存、fork 单 fd 写 | `session-manager.ts:745,1149,1191-1210,1815+`；`807a5c051`／`8ddc44ec8` | L4 实施（W2 的 `PathKeys` 可复用） |
| B275 | L5 | run 启动重构：`_startRun`/`_prepareRun`、启动即 busy、`before_agent_start` 期间 abort 在 `agent_start` 生效、flush 次序、settled 延迟动作修复；`before_agent_start` 的 user/custom 联合 | `agent-session.ts:1894-2010`；`extensions/types.ts` BeforeAgentStart 两事件 | L5 实施 |
| B276 | L5 | 扩展工具投影：`getAllTools()` 透传 `reachesTools`；active tools 重设同名集合时保持原顺序 | `agent-session.ts:1539,1614-1620`；`b9e3eef6c` | L5 实施 |
| B277 | L5 | 其余新锚点面（`sessionShareDestination`／`extensionChangelogVersions`、classifier compat、exec、package-manager、model-resolver、export-html、KeybindingHints/`blocked`） | docs/08 §L5 第 4–8 条 | L5 重测时细拆 |

> R5 不改判：#68 `streamProxy` 仍权重 0；其新增 `ToolCallArgumentsStream` 用法不影响 `bin pi` 主流。
> R1 不复活：`harness/**`、`session testing`、`pico3`、`durable` 均不因重测回加。

---

## 7. 风险与依赖

1. **平台性**：W2 经复核无可观察差异；Windows 回归用例用 `Assumptions` 守卫。CI 若只在 Linux 跑，该分支无集成覆盖——接受，并由本机 Windows 验收（与 pi 的 `simulatePlatform` 测试策略等价）。
2. **JS 语义精确性**：空白集合必须显式列出；`\uFEFF`／`\u0085` 的取舍以 JS `/\s/` 为准（不含 `\u0085`，含 `\uFEFF`）。变异探针覆盖。
3. **行为面**：W1 只影响 slash 模板展开的参数数组；W3 只影响 grep 上下文取行；W2 只影响技能来源标签与去重键。三者都不改工具 schema、提示文本或事件载荷。
4. **依赖**：本包只依赖已闭环的 L1（`ClientIdentity`、`ToolCallArgumentsStream`、`argumentsError`）与 L2（`reachesTools`／aliases）；不把 L4/L5 未闭环行为当输入。
5. **账面纪律**：#77／#24 转出后，L3 不得在后续设计中把它们重新计入；L4 若改判归属，需在 docs/04 回写并同步 map/03 与 map/06 的去重说明。

---

## 8. 编号与回填结果

1. 实施 W1／W3、复核 W2（RED → GREEN → 变异探针还原），已更新 `docs/map/03-agent-core.md`：头部 `PI_PIN=56cae7823`＋本包 SHA；工具 #5、单元 #63/#64 改判对齐并写新锚行号；#77/#24 权重 0、判定「转出（L4）」，保留旧证据；汇总改为 88 计分／177 权重＝100%。
2. `docs/04` §2 已登记 B268–B277（B268–B270 随本包闭环；B271–B277 挂所属层）。
3. `docs/06` §2 L3 行改「已闭环（docs/23 W1–W3）」，下一步 L4；`docs/08` §9 进度表已同步。
4. 提交：代码 `14be51a9` `feat(agent-core,coding-agent): align l3 prompt args and grep cr`＋本文档关闭提交 `docs(l3): close docs/23 rebaseline`（均带 `Co-Authored-By` 行）。W2 无代码改动，故代码提交标题不含 path keys。
