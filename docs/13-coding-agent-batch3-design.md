# 13 coding-agent 批 3 设计 —— B247 prompt template 命令注册面 ＋ RPC `get_commands` 形状对齐

> **状态：✅ 已闭环（2026-10-19，用户指示设计稿落地后直接实施、当日闭环）**。
> 基线：pi `ea448f454`（2026-10-11）；pi-java main（coding-agent 批 2 闭环后，`820d770f`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树。
> 前批：`12-coding-agent-batch2-design.md`（已闭环）。
>
> **闭环 commits**：`17771fd7`（§3.1/§3.2＋测试 1）→ `d967948b`（§3.3/§3.5＋测试 2/3）→ `dd76ee3e`（§3.4＋测试 5）→ `bd53a448`（§3.6＋测试 4＋`RpcDispatcherTest` 随行）→ docs 回填（本稿＋map/04＋docs/04＋docs/05）。
> **验证**：新测试 21 例全绿（测试 1–5 ＝ 5＋4＋4＋5＋3），存量邻面随行绿（`PromptTemplatesTest` 11、`SkillCommandItemsTest` 5、`CommandRegistrySkillDispatchTest` 4、`RpcDispatcherTest` 15）；全量 `mvn clean verify` BUILD SUCCESS（8:16 min）；checkstyle/spotbugs 随 verify 全模块过；日志 [WARNING] 23 条全为 pi-java-dist shade 打包重叠（存量，与本批无关）。
> **实施偏差**（均不改变裁决）：① 测试 1 夹具用 `DefaultFileSystem`＋`@TempDir`（设计稿写「仿 InMemoryFileSystem」——现存 `PromptTemplatesTest` 实为真 FS 夹具，随现存）；② 测试 3 用真 `AgentSession.create` 夹具（设计稿写「仿 CommandRegistrySkillDispatchTest」——该路 session=null，模板判定要 `session.services()`，改仿 RPC 测试夹具）；③ TUI `SlashCompleter.CommandItem` 组件名是 `hint` 非 `argumentHint`（测试 5 断言改读 `hint`）；④ 测试 5 反射调私有 `loadSlashItems` 取全清单做序断言（设计稿「同名前缀 indexOf 断言」的具体化）；⑤ 新登记 E6（`PiTuiApp.java` 519 行——批 2 闭环时已 512 未入册，本批 +7，顺手补登）。

## 0. 批次卡

| 项 | 值 |
|---|---|
| 范围 | **B247** 全量：① prompt template 命令注册面——TUI 补全目录模板臂（pi **无条件**注册）＋`/<template>` 提交透传；② RPC `get_commands` 形状对齐——`(name, description?, source, sourceInfo)`、**去内置**、`data` 包 `{commands}` 键、收编模板臂 |
| 地图单元 | 无计分母行翻动：模板注册面折叠进 map/04 **E4-3** 台账注（该单元判「发现＋参数替换」已对齐，注册面是未计分母的新面，先例＝docs/05「新增面折叠进既有单元，不另计」）；RPC 形状注记挂 **E7-1** 台账 |
| 基线账面 | coding-agent 226.0/496 = **45.6%**（未加权 98/290 = 33.8%）；全仓 891.0/1381 ≈ 64.5% |
| 目标账面 | **账面不变**（226.0/496；891.0/1381）——本批价值＝B247 销号＋`get_commands` 线格式与 pi 可对拍＋模板注册面三层补齐 |
| 重件 | RPC 形状对齐（§3.6：去内置＋source/sourceInfo＋`{commands}` 包键，三处线格式差一次收平）；模板 sourceInfo 加载期打标（§3.2——现 `loadDefaults` 两目录合一、scope 信息已丢） |
| 中件 | `PromptTemplateItems` 补全条目（§3.3）；dispatch 模板名透传（§3.5——现在手敲 `/tpl` 被「Unknown command」吞掉，批 4 已落地的 `PromptTemplateExpansion` 又是死代码，与 B244 前 `/skill:` 同款） |
| 小件 | TUI 目录模板臂（§3.4，一行循环）；`SkillCommandItems.sourceTag` 抽 Scope 重载（§3.3） |
| 裁决 | ① `get_commands` 去内置（pi 语义＝可经 prompt 调用；内部消费者零命中已复核）；② `data` 包 `{commands}` 键（批 2 偏差②回本）；③ 扩展臂随 B221（java 扩展命令与内置同池、无 sourceInfo 溯源，窄差 D5）；④ 模板注册面＝补全＋透传不注册真命令，透传按「已注册模板名」判定（D3 保持）；⑤ RPC 形状删 `argumentHint`（pi 无此字段）；⑥ sourceInfo 落 user/project/temporary＋`"local"`/`"top-level"` 子集；⑦ `SourceInfo` 放新叶子包 `core.source`（避免 core↔prompt 包环） |
| 新登记 | 无（D5 入窄差表；扩展臂由 B221 承载，不新立 B 号） |
| 不碰 | 扩展命令注册面/溯源（B221/D 域）；包源加载（npm:/git: 标签，窄差 D2 已载）；展开器本体（批 4 已闭环）；`enableSkillCommands` 开关（B244 已闭环——模板臂 pi 无门） |

**一句话**：B244 把 `/skill:` 的注册面三层补齐时，模板族还是老三样缺——补全目录无模板臂、手敲 `/tpl` 被 `CommandRegistry` 当未知命令吞掉（展开器到不了）、RPC `get_commands` 还是 java 自造形状（收内置、`(name, description, argumentHint)`、`data` 裸数组）。本批把模板族注册面按 B244 同款两层（补全＋透传）补齐，并把 `get_commands` 线格式一次对到 pi 的 `(name, description?, source, sourceInfo)`＋`{commands}` 包键＋去内置。

---

## 1. pi 参照（逐字）

### 1.1 补全名单模板臂：无条件注册（`modes/interactive/interactive-mode.ts:787-792`）

```ts
		// Convert prompt templates to SlashCommand format for autocomplete
		const templateCommands: SlashCommand[] = this.session.promptTemplates.map((cmd) => ({
			name: cmd.name,
			description: this.prefixAutocompleteDescription(cmd.description, cmd.sourceInfo),
			...(cmd.argumentHint && { argumentHint: cmd.argumentHint }),
		}));
```

要点：① **无设置门**（对照 skill 臂 :807 的 `getEnableSkillCommands()` 门——模板臂没有）；② 条目带 `argumentHint`（skill 条目没有）；③ 不去重——模板名撞内置时补全名单两条同现，提交时内置先匹配胜出；④ 名单序 `:820-823` `[...slashCommands, ...templateCommands, ...extensionCommands, ...skillCommandList]`（内置→模板→扩展→skill）。

描述的 `[tag]` 前缀（`prefixAutocompleteDescription` :702-708／`getAutocompleteSourceTag` :676-700）逐字见 docs/12 §1.2；对模板而言 `source` 恒 `"local"`（见 §1.4），标签只剩 scope 三值：user→`u`、project→`p`、缺省（temporary）→`t`。

### 1.2 提交路径：无模板分支，展开恒开

提交处理器（`interactive-mode.ts:3200-3398`）无模板分支——任何未识别 slash 落普通 prompt（窄差 D3：java 仅放行 `/skill:` 前缀，本批扩到「已注册模板名」，其余仍 `Unknown command`）。

`prompt()` 内展开（`core/agent-session.ts:2003-2011`，docs/12 §1.3 已逐字）：`_expandSkillCommand` 后 `expandPromptTemplate(expandedText, [...this.promptTemplates])`（`:2009`；队列路径同序 `:2192`）。展开器本体（`prompt-templates.ts:304-320`）批 4 已移植为 `PromptTemplateExpansion.java`，**已挂在 `AgentSession.expandUserPrompt` 链上**（`AgentSession.java:1097-1102`）——缺的只是让它能被触达。

### 1.3 RPC `get_commands` 全形

处理器（`modes/rpc/rpc-mode.ts:679-710`）：

```ts
			case "get_commands": {
				const commands: RpcSlashCommand[] = [];

				for (const command of session.extensionRunner.getRegisteredCommands()) {
					commands.push({
						name: command.invocationName,
						description: command.description,
						source: "extension",
						sourceInfo: command.sourceInfo,
					});
				}

				for (const template of session.promptTemplates) {
					commands.push({
						name: template.name,
						description: template.description,
						source: "prompt",
						sourceInfo: template.sourceInfo,
					});
				}

				for (const skill of session.resourceLoader.getSkills().skills) {
					commands.push({
						name: `skill:${skill.name}`,
						description: skill.description,
						source: "skill",
						sourceInfo: skill.sourceInfo,
					});
				}

				return success(id, "get_commands", { commands });
			}
```

线格式（`modes/rpc/rpc-types.ts:77-89`）：

```ts
/** A command available for invocation via prompt */
export interface RpcSlashCommand {
	/** Command name (without leading slash) */
	name: string;
	/** Human-readable description */
	description?: string;
	/** What kind of command this is */
	source: "extension" | "prompt" | "skill";
	/** Source metadata for the owning resource */
	sourceInfo: SourceInfo;
}
```

`SourceInfo`（`core/source-info.ts:6-12`）：

```ts
export interface SourceInfo {
	path: string;
	source: string;
	scope: SourceScope;   // "user" | "project" | "temporary"（:2）
	origin: SourceOrigin; // "package" | "top-level"（:3）
	baseDir?: string;
}
```

`data` 包键两证：① 响应类型 `rpc-types.ts:241` `data: { commands: RpcSlashCommand[] }`；② 客户端消费 `rpc-client.ts:457-460`：

```ts
	async getCommands(): Promise<RpcSlashCommand[]> {
		const response = await this.send({ type: "get_commands" });
		return this.getData<{ commands: RpcSlashCommand[] }>(response).commands;
	}
```

要点：① **内置命令不在其中**（get_commands 语义＝「可经 prompt 调用的命令」，rpc-types.ts:73-74 注释；内置由 TUI 硬编码且不可经 prompt 调用）；② 形状**无 `argumentHint`**；③ 序＝扩展→模板→skill；④ skill 臂无门（B244 已对齐此点）。

### 1.4 模板 sourceInfo 构造（`core/prompt-templates.ts:235-261`）

```ts
	const globalPromptsDir = join(resolvedAgentDir, "prompts");
	const projectPromptsDir = resolve(resolvedCwd, CONFIG_DIR_NAME, "prompts");

	const isUnderPath = (target: string, root: string): boolean => { /* resolve 后前缀判定 */ };

	const getSourceInfo = (resolvedPath: string): SourceInfo => {
		if (isUnderPath(resolvedPath, globalPromptsDir)) {
			return createSyntheticSourceInfo(resolvedPath, {
				source: "local",
				scope: "user",
				baseDir: globalPromptsDir,
			});
		}
		if (isUnderPath(resolvedPath, projectPromptsDir)) {
			return createSyntheticSourceInfo(resolvedPath, {
				source: "local",
				scope: "project",
				baseDir: projectPromptsDir,
			});
		}
		return createSyntheticSourceInfo(resolvedPath, {
			source: "local",
			baseDir: statSync(resolvedPath).isDirectory() ? resolvedPath : dirname(resolvedPath),
		});
	};
```

`createSyntheticSourceInfo`（`source-info.ts:33-46`）：`scope` 缺省 `"temporary"`、`origin` 缺省 `"top-level"`。代入：默认全局目录⇒`{source:"local", scope:"user", origin:"top-level", baseDir:全局prompts目录}`；默认项目目录⇒`scope:"project"`；显式路径（`--prompt-template`、扩展 promptPaths）⇒`scope:"temporary"`、baseDir＝目录自身（目录入参）或父目录（文件入参）。`path` 恒为模板文件全路径（`loadTemplateFromFile` 的 `sourceInfo` 入参按文件逐个构造，`:118-157`）。

### 1.5 技能 sourceInfo 构造（`core/skills.ts:136-157`）

```ts
	function createSkillSourceInfo(filePath: string, baseDir: string, source: string): SourceInfo {
		switch (source) {
			case "user":
				return createSyntheticSourceInfo(filePath, { source: "local", scope: "user", baseDir });
			case "project":
				return createSyntheticSourceInfo(filePath, { source: "local", scope: "project", baseDir });
			case "path":
				return createSyntheticSourceInfo(filePath, { source: "local", baseDir });
			default:
				return createSyntheticSourceInfo(filePath, { source, baseDir });
		}
	}
```

java 三源映射：`USER→scope:"user"`、`PROJECT→scope:"project"`、`EXPLICIT→scope:"temporary"`（pi `"path"` 源 scope 缺省）；`source` 恒 `"local"`、`origin` 恒 `"top-level"`、`path`＝`filePath`、`baseDir`＝`baseDir`。

---

## 2. pi-java 现状盘点

| 面 | 现状 | 差距 |
|---|---|---|
| 补全目录 | `PiTuiApp.loadSlashItems`（`pi-java-tui PiTuiApp.java:113-130`）＝内置＋skill 臂（B244） | 无模板臂（pi :787-792 无条件注册、带 `[tag]` 前缀与 argumentHint） |
| 提交路径 | `CommandRegistry.dispatch`（`core/slash/CommandRegistry.java:86-97`）对未知名仅放行 `/skill:` 前缀（B244），其余返回 `"Unknown command"` | 已注册模板名被吞——`AgentSession.expandUserPrompt`（`AgentSession.java:1097-1102`）里的 `PromptTemplateExpansion`（批 4）**触达不到**，与 B244 前 `/skill:` 同款死代码 |
| 模板容器 | `PromptTemplateRegistry`（`prompt/PromptTemplateRegistry.java`）＋`PromptTemplates.PromptTemplate(name, description, content, argumentHint, filePath)`（`prompt/PromptTemplates.java:34-41`） | 记录无 `sourceInfo`——scope 信息在加载期已丢（见下行） |
| 加载 | `PromptTemplates.loadDefaults`（`PromptTemplates.java:85-89`）把 agentDir/prompts 与 cwd/.pi-java/prompts **合一**调 `load`；显式路径走 `load`（`:61-78`） | pi 按目录打 user/project/temporary 标（§1.4）；java 无打标点，补全 `[tag]` 与 RPC sourceInfo 都无米 |
| RPC | `RpcDispatcher.buildCommands`（`RpcDispatcher.java:483-502`）＝内置 24 条＋skill 臂；`RpcSlashCommand(name, description, argumentHint)`；`data` 直接是命令数组（批 2 偏差②钉死） | 三点线格式差：①收内置（pi 不收）；②无 `source`/`sourceInfo`、多 `argumentHint`；③`data` 未包 `{commands}` 键（pi rpc-types.ts:241／rpc-client.ts:459 两证） |
| 技能容器 | `Skill.sourceInfo()` 三值枚举＋`filePath()`/`baseDir()`（agent-core `Skill.java:33-59`） | 缺到 pi `SourceInfo` 五字段的构造器 |
| 内部消费者 | 全仓 grep `get_commands\|getCommands\|RpcSlashCommand`：仅 coding-agent 主/测 6 文件命中，client/server/web **零命中**（2026-10-19 复核，docs/12 裁决④结论仍成立） | 改线格式无内部破坏面 |

---

## 3. 改动点

### 3.1 新叶子包 `core.source`：`SourceInfo`

pi 对位：`core/source-info.ts:6-12`。放新包 `com.pijava.coding.agent.core.source`（裁决⑦：`prompt` 包要被 `core.AgentSession` 引用，若 `SourceInfo` 放 `core` 则 prompt→core 成包环；叶子包无出边）。scope/origin 是纯常量闭集，按编码规范用枚举＋`@JsonValue`（不手写 switch 序列化）；`baseDir` 可空、缺席即省略。

```java
package com.pijava.coding.agent.core.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * B247：资源来源元数据（pi {@code core/source-info.ts:6-12} {@code SourceInfo}）。
 * RPC {@code get_commands} 条目与资源诊断携带；{@code baseDir} 缺席即省略
 * （pi {@code baseDir?}）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceInfo(
    String path,
    String source,
    Scope scope,
    Origin origin,
    String baseDir
) {
    /** pi {@code SourceScope}（source-info.ts:2）。 */
    public enum Scope {
        USER("user"), PROJECT("project"), TEMPORARY("temporary");

        private final String wire;
        Scope(String wire) { this.wire = wire; }

        @JsonValue
        public String wire() { return wire; }
    }

    /** pi {@code SourceOrigin}（source-info.ts:3）；java 无包源加载，恒 {@link #TOP_LEVEL}。 */
    public enum Origin {
        PACKAGE("package"), TOP_LEVEL("top-level");

        private final String wire;
        Origin(String wire) { this.wire = wire; }

        @JsonValue
        public String wire() { return wire; }
    }
}
```

注：`Origin.PACKAGE` 列全闭集但 java 现不产出（包域未做，窄差 D2 同族）；序列化靠 `@JsonValue`，无消费端 switch。

### 3.2 `PromptTemplates`：记录增 `sourceInfo`＋加载期打标

pi 对位：§1.4（`prompt-templates.ts:235-261`）。`PromptTemplate` 记录尾部加 `SourceInfo sourceInfo` 组件（兼容构造补 `null` ≙ pi 缺席——仅测试/手造实例用）；`loadDefaults` 拆两路分别打 user/project 标，`load`（显式路径）打 temporary 标。baseDir 规则：目录入参⇒目录自身；文件入参⇒父目录（pi `:259` `statSync(resolvedPath).isDirectory() ? resolvedPath : dirname(resolvedPath)`）。

```java
    public record PromptTemplate(String name, String description, String content,
                                 String argumentHint, String filePath,
                                 com.pijava.coding.agent.core.source.SourceInfo sourceInfo) {

        /** 兼容构造：无 {@code argumentHint}/{@code filePath}/{@code sourceInfo}。 */
        public PromptTemplate(String name, String description, String content) {
            this(name, description, content, null, null, null);
        }
    }
```

加载面改造（`load`/`loadDefaults`/`loadFromDir`/`loadFromFile` 全链带 `SourceInfo.Scope`＋baseDir 下行）：

```java
    /** 显式路径加载：scope=temporary（pi :257-261，scope 缺省落 temporary）。 */
    public static LoadResult load(FileSystem fs, List<String> paths) {
        var templates = new ArrayList<PromptTemplate>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (var path : paths) {
            FileInfo info;
            try {
                info = fs.fileInfo(path);
            } catch (IOException e) {
                continue; // pi :271-273：显式路径不存在 ⇒ 静默跳过（无诊断）
            }
            if ("dir".equals(info.kind())) {
                // baseDir=目录自身（pi :259 目录分支）
                loadFromDir(fs, info.path(), templates, diagnostics,
                    SourceInfo.Scope.TEMPORARY, info.path());
            } else if ("file".equals(info.kind()) && info.path().endsWith(".md")) {
                // baseDir=父目录（pi :259 文件分支 dirname）
                loadFromFile(fs, info.path(), templates, diagnostics,
                    SourceInfo.Scope.TEMPORARY, parentDir(info.path()));
            }
        }
        return new LoadResult(templates, diagnostics);
    }

    /**
     * pi {@code includeDefaults} 分支（:262-265）＋ :247-256 打标：全局目录
     * scope=user、项目目录 scope=project，baseDir 各为所属 prompts 目录。
     * 品牌分叉：pi 是 {@code .pi}，窄差已登记 docs/09 §14。
     */
    public static LoadResult loadDefaults(FileSystem fs, String agentDir, String cwd) {
        var sep = java.io.File.separator;
        var globalDir = agentDir + sep + "prompts";
        var projectDir = cwd + sep + ".pi-java" + sep + "prompts";
        var templates = new ArrayList<PromptTemplate>();
        var diagnostics = new ArrayList<Diagnostic>();
        loadFromDir(fs, globalDir, templates, diagnostics, SourceInfo.Scope.USER, globalDir);
        loadFromDir(fs, projectDir, templates, diagnostics, SourceInfo.Scope.PROJECT, projectDir);
        return new LoadResult(templates, diagnostics);
    }
```

`loadFromDir`/`loadFromFile` 签名加 `(Scope scope, String baseDir)`，`loadFromFile` 构造：

```java
        templates.add(new PromptTemplate(fileName, description, parsed.body(),
            argumentHint, filePath,
            new SourceInfo(filePath, "local", scope,
                SourceInfo.Origin.TOP_LEVEL, baseDir)));
```

行为注：① `loadDefaults` 旧实现复用 `load`（缺目录静默跳过）；改后直调 `loadFromDir`——`loadFromDir` 对缺目录本就静默（`fs.listDir` IOException ⇒ 诊断 `"list_failed"`）。**差异**：旧路缺目录零诊断，新路会留 `list_failed` 诊断——对齐 pi（pi `loadTemplatesFromDir` 缺目录 `existsSync` 短路零诊断，`:164-166`），故 `loadFromDir` 入口先 `fs.fileInfo` 探活、缺目录直返（保持零诊断），探活失败才进 listDir。② `load` 的显式单文件路旧不调 `loadFromDir`，打标后两路合一进 `loadFromFile` 不变。

### 3.3 新 `core/slash/PromptTemplateItems.java`＋`sourceTag` 抽 Scope 重载

pi 对位：§1.1（:787-792）。无条件（无门）、`[tag]` 前缀、`argumentHint` 透传；模板名撞内置不去重（pi 同形，§1.1 要点③）。

```java
package com.pijava.coding.agent.core.slash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.pijava.coding.agent.core.source.SourceInfo;
import com.pijava.coding.agent.prompt.PromptTemplates.PromptTemplate;

/**
 * B247：prompt template 补全条目（pi interactive-mode.ts:787-792）。
 * **无条件**注册（对照 skill 臂的 enableSkillCommands 门——pi 模板臂无门）；
 * 条目带 argumentHint（pi :790），名撞内置不去重（提交时内置先匹配胜出）。
 */
public final class PromptTemplateItems {

    /** 一条补全目录条目（name 不带 {@code /} 前缀）。 */
    public record Entry(String name, String description, String argumentHint) {}

    private PromptTemplateItems() {}

    /** 全部已注册模板的补全条目（pi :787-792，无门）。 */
    public static List<Entry> list(Collection<? extends PromptTemplate> templates) {
        var entries = new ArrayList<Entry>(templates.size());
        for (var template : templates) {
            entries.add(new Entry(template.name(),
                taggedDescription(template), template.argumentHint()));
        }
        return entries;
    }

    /**
     * pi prefixAutocompleteDescription（:702-708）经模板 sourceInfo：
     * {@code [tag] description}；描述空 ⇒ 仅 {@code [tag]}；无 sourceInfo
     * （手造实例）⇒ 原文（pi :703-705 sourceInfo 缺席短路）。
     */
    static String taggedDescription(PromptTemplate template) {
        var info = template.sourceInfo();
        if (info == null) {
            return template.description();
        }
        var tag = SkillCommandItems.sourceTag(info.scope());
        var description = template.description();
        return description == null || description.isEmpty()
            ? "[" + tag + "]"
            : "[" + tag + "] " + description;
    }
}
```

`SkillCommandItems` 抽 Scope 重载（原三值枚举重载委托；USER/PROJECT 同名映射、EXPLICIT→TEMPORARY——pi 显式路径 scope 缺省，skills.ts:150-154）：

```java
    /** B247：scope 直取版（模板臂用）；pi getAutocompleteSourceTag :683 三值。 */
    static String sourceTag(com.pijava.coding.agent.core.source.SourceInfo.Scope scope) {
        return switch (scope) {
            case USER -> "u";
            case PROJECT -> "p";
            case TEMPORARY -> "t";
        };
    }

    static String sourceTag(SkillSource source) {
        return switch (source) {
            case USER -> "u";
            case PROJECT -> "p";
            case EXPLICIT -> "t";
        };
    }
```

（两重载签名不同参型，不合并；SkillSource→Scope 真合并留给包域落地时随 D2 一起收。）

### 3.4 `PiTuiApp.loadSlashItems`：模板臂插在内置与 skill 之间

pi 对位：名单序 :820-823（内置→模板→扩展→skill）；java 无扩展臂（B221），模板插在 skill 前即等价。

```java
        var registry = session.services().slashCommands();
        var items = new ArrayList<>(registry.names().stream() /* …内置，不变… */ .toList());
        // B247（pi interactive-mode.ts:787-792）：模板臂——无条件（pi 无门），
        // 序在内置之后、skill 之前（pi :820-823 内置→模板→扩展→skill；
        // java 无扩展臂，B221）。
        for (var entry : PromptTemplateItems.list(session.services().promptTemplates().all())) {
            items.add(new SlashCompleter.CommandItem(
                entry.name(), entry.argumentHint(), entry.description()));
        }
        // B244（pi interactive-mode.ts:803-815）：skill 臂，不变。
        var enabled = session.services().settings().accessors().getEnableSkillCommands();
        for (var entry : SkillCommandItems.list(session.harness().skillManager().all(), enabled)) {
            items.add(new SlashCompleter.CommandItem(entry.name(), null, entry.description()));
        }
```

overlay 关闭重建（B244 §3.4）已统一刷目录——模板臂随同一重建点生效，无需新钩子。

### 3.5 `CommandRegistry.dispatch`：已注册模板名透传为 prompt

pi 对位：§1.2（无模板分支 ⇒ 落 prompt ⇒ 展开器）。与 B244 裁决①同形：不注册真命令，dispatch 对「已注册模板名」返回 `null` 透传；窄差 D3 保持——未识别 slash 仍 `Unknown command`（pi 是任何 `/xxx` 都落 prompt）。

```java
        if (command == null) {
            // B244：/skill:<name> 透传（略，不变）。
            if (parts[0].startsWith("skill:")) {
                return null;
            }
            // B247：已注册模板名同样不是注册命令——pi 提交处理器无模板分支，
            // 落 prompt 后由 expandPromptTemplate 展开（agent-session.ts:2009，
            // java 批 4 已挂 AgentSession.expandUserPrompt）。按注册表判定
            // （窄差 D3：pi 任何未识别 slash 都落 prompt，java 只放行
            // /skill: 前缀与已注册模板名）。
            var session = context.session();
            if (session != null
                    && session.services().promptTemplates().find(parts[0]) != null) {
                return null;
            }
            return CompletableFuture.completedFuture(
                "Unknown command: /" + parts[0]
                    + " (try /hotkeys for the list of commands)");
        }
```

`context.session()` 可为 null（`SlashContext.of(null)` 测试路）——守卫后落 Unknown command，不 NPE。

### 3.6 RPC `get_commands` 形状对齐

pi 对位：§1.3。三处线格式差一次收平：① 去内置（裁决①）；② 条目改形 `(name, description?, source, sourceInfo)`、删 `argumentHint`（裁决⑤）；③ `data` 包 `{commands}` 键（裁决②）。扩展臂不产（裁决③，窄差 D5）。

`RpcSlashCommand` 改形：

```java
package com.pijava.coding.agent.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.pijava.coding.agent.core.source.SourceInfo;

/**
 * get_commands 载荷 —— 单个可经 prompt 调用命令的元数据
 * （B247 对齐 pi {@code rpc-types.ts:77-89}：内置不在其中；无 argumentHint）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RpcSlashCommand(
    String name,
    String description,
    Source source,
    SourceInfo sourceInfo
) {
    /** pi {@code source: "extension" | "prompt" | "skill"}（rpc-types.ts:87）。 */
    public enum Source {
        EXTENSION("extension"), PROMPT("prompt"), SKILL("skill");

        private final String wire;
        Source(String wire) { this.wire = wire; }

        @JsonValue
        public String wire() { return wire; }
    }
}
```

`buildCommands` 重写（序＝模板→skill；pi 序是扩展→模板→skill，扩展臂缺席，D5）：

```java
    private List<RpcSlashCommand> buildCommands() {
        var commands = new ArrayList<RpcSlashCommand>();
        // B247（pi rpc-mode.ts:691-697）：模板臂。sourceInfo 由加载期打标
        // （§3.2）；手造实例无 sourceInfo ⇒ 组件 null、线格式省略（pi 恒有，
        // 此臂仅测试/扩展手工注册路）。
        for (var template : session.services().promptTemplates().all()) {
            commands.add(new RpcSlashCommand(
                template.name(), template.description(),
                RpcSlashCommand.Source.PROMPT, template.sourceInfo()));
        }
        // B244/B247（pi rpc-mode.ts:699-707）：skill 臂，不受
        // enableSkillCommands 门；sourceInfo 由 Skill 三面构造（§1.5 映射）。
        for (var skill : session.harness().skillManager().all()) {
            commands.add(new RpcSlashCommand(
                "skill:" + skill.name(), skill.description(),
                RpcSlashCommand.Source.SKILL, skillSourceInfo(skill)));
        }
        return commands;
    }

    /**
     * pi createSkillSourceInfo（skills.ts:136-157）的 java 三面映射：
     * USER/PROJECT→同值 scope，EXPLICIT→temporary（pi "path" 源 scope 缺省）；
     * source 恒 "local"、origin 恒 "top-level"。
     */
    private static SourceInfo skillSourceInfo(com.pijava.agent.skill.Skill skill) {
        var scope = switch (skill.sourceInfo()) {
            case USER -> SourceInfo.Scope.USER;
            case PROJECT -> SourceInfo.Scope.PROJECT;
            case EXPLICIT -> SourceInfo.Scope.TEMPORARY;
        };
        var filePath = skill.filePath();
        var baseDir = skill.baseDir();
        return new SourceInfo(
            filePath == null ? "" : filePath.toString(), // pi path 必填；非文件技能 java 独有边
            "local", scope, SourceInfo.Origin.TOP_LEVEL,
            baseDir == null ? null : baseDir.toString());
    }
```

`data` 包键（分发点改一行）：

```java
                case RpcCommand.GetCommands g ->
                    // B247（pi rpc-types.ts:241 / rpc-client.ts:459）：data 包
                    // {commands} 键（批 2 偏差②回本——彼时按裸数组钉死）。
                    out.write(RpcResponse.ok(g.id(), "get_commands",
                        java.util.Map.of("commands", buildCommands())));
```

---

## 4. 测试计划

pi 侧参照：`prompt-templates.test.ts` 覆盖加载/替换（批 4 已移植对应面）；get_commands 形状无直接 pi 测试可移植，形状用例全为 java 新写。

| # | 测试（位置） | 覆盖 |
|---|---|---|
| 1 | `prompt/PromptTemplatesSourceInfoTest.java`（coding-agent，仿 `PromptTemplatesTest` 的 `InMemoryFileSystem` 夹具） | `loadDefaults`：全局目录模板 scope=USER、baseDir=全局 prompts 目录；项目目录 scope=PROJECT；缺目录零诊断（§3.2 行为注①）；`load` 显式：scope=TEMPORARY，目录入参 baseDir=自身、`.md` 文件入参 baseDir=父目录；source/origin 恒 `"local"`/`TOP_LEVEL`；path=模板文件路径 |
| 2 | `core/slash/PromptTemplateItemsTest.java`（coding-agent，仿 `SkillCommandItemsTest`） | 无条件列出（无门——签名无 enabled 参）；USER/PROJECT/TEMPORARY→`[u]/[p]/[t]`；描述空⇒仅 `[tag]`；`argumentHint` 透传进 Entry；sourceInfo=null（兼容构造）⇒描述原文无前缀 |
| 3 | `core/slash/CommandRegistryTemplateDispatchTest.java`（coding-agent，仿 `CommandRegistrySkillDispatchTest`） | 注册表有 `review` 模板＋`SlashContext.of(session)` ⇒ `dispatch("/review src")` 返回 `null`；未知名 `/nope` ⇒ Unknown command 文案；`SlashContext.of(null)` ⇒ 模板名也落 Unknown command（不 NPE）；内置 `/model` 不受影响；模板名撞内置名 ⇒ 内置胜出（dispatch 不返回 null） |
| 4 | `rpc/RpcGetCommandsShapeTest.java`（coding-agent，**改写** `RpcGetCommandsSkillTest`，夹具复用） | ① `data` 是对象且 `data.commands` 是数组（包键钉死）；② 无内置条目（`help`/`model` 不出现）；③ 注册模板 ⇒ 条目 `source:"prompt"`、`sourceInfo.scope` 线格式 `"user"/"project"/"temporary"`、`path`/`baseDir` 在场；④ 技能 ⇒ `source:"skill"`、sourceInfo 五字段（EXPLICIT→`"temporary"`）；⑤ 任意条目**无** `argumentHint` 键；⑥ 空会话 ⇒ `commands` 空数组仍包键 |
| 5 | TUI（`pi-java-tui`，仿 `SkillCommandsTuiTest`） | 装配后补全目录含模板条目；序＝内置→模板→skill（同名前缀下 indexOf 断言）；模板条目 argumentHint 进 `CommandItem` |

存量测试影响面（实施时同步更新）：`RpcDispatcherTest` 若钉旧形状（内置/裸数组/argumentHint）随测试 4 一并改；`PromptTemplatesTest`/`PromptTemplateRegistryTest`/`PromptTemplateExpansionTest` 用兼容构造的实例不受影响（sourceInfo=null 路）。

---

## 5. 裁决与窄差

### 裁决（【场景/决定/风险】三段式）

**裁决①：`get_commands` 去内置**
- 【场景】pi 的 get_commands 语义是「可经 prompt 调用的命令」（rpc-types.ts:73-74 注释），内置由 TUI 硬编码、不可经 prompt 调用，故 pi 不收；java 批 1 起把内置 24 条塞进响应（彼时形状自造）。
- 【决定】`buildCommands` 只产模板臂＋skill 臂（§3.6）；内置条目整臂删除。
- 【风险/代价】消费 java RPC 的外部脚本会看到清单剧变（内置消失）。复核：全仓 grep 内部消费者零命中（client/server/web 无 get_commands 引用）；pi 客户端按 `{commands}`＋source 形状写，对齐后反而可互换。[用户可见-线格式]

**裁决②：`data` 包 `{commands}` 键**
- 【场景】批 2 实施时发现 java `data` 直接是命令数组，按实线格式钉了测试（docs/12 偏差②）；本批取证到 pi 两证——响应类型 `rpc-types.ts:241 data: { commands }`、官方客户端 `rpc-client.ts:459 .commands`。
- 【决定】分发点改 `Map.of("commands", buildCommands())`（§3.6）。
- 【风险/代价】同裁决①，外部裸读 `data` 数组的脚本断；内部零消费者。[用户可见-线格式]

**裁决③：扩展臂随 B221，不新立 B 号**
- 【场景】pi 第一臂是 `extensionRunner.getRegisteredCommands()`（source:"extension"＋每命令 sourceInfo）。java 扩展经 `ExtensionContext.slashCommands()` 把命令注册进**与内置同一个** `CommandRegistry`，无「哪条是哪个扩展注册的」溯源，也没有 per-command sourceInfo；造臂要先造溯源面，那是 B221（扩展运行时）的地基活。docs/12 §1.5 已注「java 无扩展运行时，此面随 B221」。
- 【决定】本批 get_commands 不产扩展臂；差入窄差 D5，回收点挂在 B221。
- 【风险/代价】java 扩展真注册了 slash 命令时，该命令不进 get_commands（pi 会进）。java 扩展命令注册面目前无实际生产者（D 域 12.7%），影响为空转。[用户可见-线格式，当前无触发者]

**裁决④：模板注册面＝补全＋透传，透传按「已注册模板名」判定**
- 【场景】与 B244 裁决①同形：pi 的模板命令没有 handler，靠「未识别 ⇒ 落 prompt ⇒ expandPromptTemplate」生效；java `SlashCommand` 返回语义无「转 prompt」通道。但 pi 的落法是「任何 `/xxx` 都落 prompt」，java 既有 UX 是「未识别 ⇒ Unknown command」（窄差 D3）。
- 【决定】补全目录加模板臂（§3.3/§3.4）＋dispatch 对**已注册模板名**返回 `null` 透传（§3.5）；D3 保持，不扩成「任意 slash 透传」。
- 【风险/代价】未注册的 `/xxx` 仍报 Unknown command（pi 会当字面 prompt 发给模型）——D3 继续承载此差；模板名撞内置时内置胜出、补全双条目（pi 同形，非差）。[用户可见]

**裁决⑤：RPC 形状删 `argumentHint`**
- 【场景】java 自造形状带 `argumentHint`（来自内置命令表）；pi `RpcSlashCommand`（rpc-types.ts:81-89）无此字段——模板有 argumentHint 但 pi RPC 面不暴露。
- 【决定】`RpcSlashCommand` 四组件 `(name, description, source, sourceInfo)`，`argumentHint` 随改形删除。
- 【风险/代价】无内部消费者；外部脚本若读 hint 断（同裁决①破坏面）。[用户可见-线格式]

**裁决⑥：sourceInfo 落 user/project/temporary＋`"local"`/`"top-level"` 子集**
- 【场景】pi 的 `source` 还有 `"builtin"`、`"auto"`、`"cli"`、`npm:`/`git:` 复合、`origin:"package"`——全在包源/内置扩展域。
- 【决定】java 模板/技能 sourceInfo 恒 `source:"local"`、`origin:"top-level"`，scope 三值全落；复合源与 package origin 随包域落地时回收（窄差 D2 同族，不新登记）。
- 【风险/代价】线格式字段值域是 pi 子集——对拍器按全集写会差，按子集断言。[内部-线格式值域]

**裁决⑦：`SourceInfo` 放新叶子包 `core.source`**
- 【场景】`PromptTemplate`（`prompt` 包）要持 `SourceInfo`；`AgentSession`（`core` 包）已引用 `prompt` 包。`SourceInfo` 若放 `core`，`prompt→core` 成包环。
- 【决定】新包 `com.pijava.coding.agent.core.source`，无出边（只依赖 Jackson 注解）；rpc/prompt/slash 三方消费。
- 【风险/代价】包数 +1；无行为面。[内部]

### 窄差表（本批新增/更新）

| # | 差 | pi | java | 可见性 |
|---|---|---|---|---|
| D3（更新） | 未识别 slash 的落法 | 任何 `/xxx` 都落 prompt 发给模型 | 仅放行 `/skill:` 前缀与**已注册模板名**；其余仍报 `Unknown command` | 用户可见 |
| D5（新） | `get_commands` 扩展臂 | 收扩展注册命令（`source:"extension"`＋per-command sourceInfo，rpc-mode.ts:684-690） | 不产（扩展命令与内置同池、无溯源，随 B221） | 用户可见（线格式，当前无触发者） |

### 新登记

无。D5 入 docs/04 窄差表；扩展臂回收点挂 B221 既有条目（闭环时在其文内补一句）。

---

## 6. 闭环回填清单

1. map/04：E4-3 台账注更新（「发现＋参数替换」补「注册面＝补全＋透传，批 3/docs/13」）；E7-1 台账注补「`get_commands` 形状对齐（source/sourceInfo/`{commands}` 包键/去内置），批 3」；账面不变 226.0/496。
2. docs/05：coding-agent 行补批 3 注记（B247 销号、账面不变）；全仓 891.0/1381 不变。
3. docs/04：B247 销号（迁移至已闭环区，注明本稿）；窄差 D3 描述更新＋D5 入账；B221 条目补「扩展臂含 get_commands source:"extension"」。
4. 本稿状态行改「✅ 已闭环」＋commits 清单＋实施偏差注记。
5. 全量 `mvn clean verify`（显式 `JAVA_HOME=D:\soft\jdk\graalvm-jdk-25`）零错误零警告。

## 7. Commit 计划

1. `feat(coding-agent): SourceInfo + prompt template load-time scope tagging (B247)`（§3.1/§3.2＋测试 1）
2. `feat(coding-agent): prompt template completion items + dispatch passthrough (B247)`（§3.3/§3.5＋测试 2/3）
3. `feat(tui): template arm in slash completion catalog (B247)`（§3.4＋测试 5）
4. `feat(coding-agent): RPC get_commands shape alignment (B247)`（§3.6＋测试 4＋`RpcDispatcherTest` 受影响行）
5. `docs(coding-agent): batch-3 closure — B247 closed, weighted unchanged 45.6%`（§6 全量回填）
