# 12 coding-agent 批 2 设计 —— B244 `/skill:` 命令族（命令注册面 ＋ `enableSkillCommands` 开关）

> **状态：✅ 已闭环（2026-10-19，审核通过当日实施）**。
> 基线：pi `ea448f454`（2026-10-11）；pi-java main（coding-agent 批 1 闭环后，`6b69fa0e`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树。
> 前批：`11-coding-agent-batch1-design.md`（已闭环）。
>
> **闭环 commits**：`7c39a0e6`（§3.1/§3.2＋测试 1/3）→ `0d12ef51`（§3.3 `SkillCommandItems`＋测试 2）→ `af58655f`（§3.3 TUI 半＋§3.4/§3.5＋测试 5）→ `69f76f92`（§3.6＋测试 4）→ docs 回填（本稿＋map/04＋docs/04＋docs/05）。
> **验证**：新测试 19 例全绿（测试 1–5 ＝ 4＋5＋4＋3＋3）；全量 `mvn clean verify` BUILD SUCCESS（14/14 模块，8:11 min）；checkstyle 0 错误 0 警告；spotbugs 全模块过；日志 [WARNING] 23 条全为 pi-java-dist shade 打包重叠（存量，与本批无关）。
> **实施偏差**（均不改变裁决）：① `SkillCommandItems.list` 入参由 `List` 放宽为 `Collection`——`SkillManager.all()` 返回 `Collection<Skill>`（设计稿按 List 写）；② RPC `get_commands` 的 `data` 直接是命令数组（不包 `commands` 键）——测试 4 按实线格式钉；③ map/04 C 节汇总行旧账更正（旧行「对齐 11/Σwc 25.0」与逐行判定不符，逐行实算对齐 10/Σwc 24.0，与域汇总表一致；本批后对齐 11/Σwc 26.0）；④ 测试 5 的 SettingsScreen 断言走 FIELDS 反射＋真 key 路径 cycle（设计稿预留的 overlay 重建钩子未驱动——重建行由评审把关，同设计稿 §4 注）。

## 0. 批次卡

| 项 | 值 |
|---|---|
| 范围（用户 2026-10-19 选定） | **B244** 全量：`skill:<name>` 命令注册面（TUI 补全目录＋提交透传＋RPC `get_commands` skill 臂）＋ settings `enableSkillCommands` 开关（accessor＋TUI 设置页字段＋来源标签） |
| 地图单元 | map/04 **#25**（`/skill:<name>` 命令族，w2 缺失→对齐）、**E2-31**（`enableSkillCommands`，w2 存疑→对齐）、**E4-2**（skill 注册为命令，w2 缺失→对齐） |
| 基线账面 | coding-agent 221.0/496 = **44.6%**（未加权 95/290 = 32.8%）；全仓 886.0/1381 ≈ 64.2% |
| 目标账面 | coding-agent **226.0/496 ≈ 45.6%**（+5.0：两条缺失各 +2.0、一条存疑 +1.0）；未加权 **98/290 ≈ 33.8%**；全仓 **891.0/1381 ≈ 64.5%** |
| 重件 | `CommandRegistry.dispatch` 的 `/skill:` 透传（§3.2，裁决①——现在手敲 `/skill:foo` 被「Unknown command」吞掉，根本到不了批 4 已落地的展开器）；TUI 补全目录 skill 臂（§3.3） |
| 中件 | `SettingsAccessors` get/set（§3.1）；`SkillCommandItems` 来源标签（§3.3，裁决⑥）；RPC `get_commands` skill 臂（§3.6，裁决④） |
| 小件 | TUI 设置页字段＋overlay 关闭重建目录（§3.4/§3.5，裁决⑦） |
| 裁决 | ① 注册面＝补全目录＋透传，**不注册真命令**（pi 同形）；② 展开不受开关门（pi 实证）；③ legacy 嵌套 `skills` 对象迁移不移植（窄差 D1）；④ RPC 形状差（builtins/source/sourceInfo/模板臂）登记 **B247**，本批只追加 skill 臂；⑤ pi 的 `skillCommands` Map 只写不读，不移植；⑥ 来源标签落 u/p/t 三值子集（窄差 D2）；⑦ overlay 关闭统一重建补全目录 |
| 新登记 | **B247**（prompt template 命令注册面＋RPC `get_commands` 形状对齐） |
| 不碰 | prompt template 命令族（B247）；扩展命令注册（B221/D 域）；`@文件` 补全（map/05 K25）；settings selector 其余字段；展开器本体（批 4 已闭环） |

**一句话**：pi 的 `/skill:name` 不是真注册命令——它只活在**补全名单**里（受 `enableSkillCommands` 门，默认开），提交时靠「非内置命令 ⇒ 落回普通 prompt ⇒ `_expandSkillCommand` 展开」生效。pi-java 批 4 已把展开器装进 `AgentSession`，但 `CommandRegistry` 会把 `/skill:foo` 当未知命令吞掉，展开永远到不了。本批把「注册面」三层（设置开关、补全目录、提交透传）连同 RPC 上报补齐。

---

## 1. pi 参照（逐字）

### 1.1 设置键 `enableSkillCommands`（schema/默认值/accessor/旧格式迁移）

schema（`core/settings-schema.ts:313-318`）：

```ts
		enableSkillCommands: Type.Optional(
			Type.Boolean({
				description: "Register skills as /skill:name commands.",
				default: SETTINGS_DEFAULTS.enableSkillCommands,
			}),
		),
```

默认值（`core/settings-defaults.ts:33`）：`enableSkillCommands: true,`

accessor（`core/settings-manager.ts:1156-1164`）——**读 = 有效值缺省回落 true；写 = 只写全局域**：

```ts
	getEnableSkillCommands(): boolean {
		return this.settings.enableSkillCommands ?? SETTINGS_DEFAULTS.enableSkillCommands;
	}

	setEnableSkillCommands(enabled: boolean): void {
		this.globalSettings.enableSkillCommands = enabled;
		this.markModified("enableSkillCommands");
		this.save();
	}
```

旧格式迁移（`core/settings-manager.ts:409-426`）——pi 历史上 `skills` 是对象形 `{enableSkillCommands, customDirectories}`，加载时迁移为顶置键＋数组（裁决③：不移植）：

```ts
		// Migrate old skills object format to new array format
		if (
			"skills" in settings &&
			typeof settings.skills === "object" &&
			settings.skills !== null &&
			!Array.isArray(settings.skills)
		) {
			const skillsSettings = settings.skills as {
				enableSkillCommands?: boolean;
				customDirectories?: unknown;
			};
			if (skillsSettings.enableSkillCommands !== undefined && settings.enableSkillCommands === undefined) {
				settings.enableSkillCommands = skillsSettings.enableSkillCommands;
			}
			if (Array.isArray(skillsSettings.customDirectories) && skillsSettings.customDirectories.length > 0) {
				settings.skills = skillsSettings.customDirectories;
			} else {
				delete settings.skills;
			}
		}
```

### 1.2 补全名单注册（`modes/interactive/interactive-mode.ts:803-815`）

```ts
		// Build skill commands from session.skills (if enabled)
		this.skillCommands.clear();
		const skillCommandList: SlashCommand[] = [];
		if (this.settingsManager.getEnableSkillCommands()) {
			for (const skill of this.session.resourceLoader.getSkills().skills) {
				const commandName = `skill:${skill.name}`;
				this.skillCommands.set(commandName, skill.filePath);
				skillCommandList.push({
					name: commandName,
					description: this.prefixAutocompleteDescription(skill.description, skill.sourceInfo),
				});
			}
		}
```

要点：① 唯一消费 `getEnableSkillCommands()` 的地方就是这份补全名单；② 条目只有 `name`＋`description`，**无 argumentHint、无 handler**；③ `this.skillCommands`（`:523` 声明的 `Map<string,string>`）**只写不读**——全仓 grep 实证无读取点，是死状态（裁决⑤）；④ `disableModelInvocation` 的技能照样注册（该标志只管系统提示，不妨碍用户显式调用）。

描述的来源标签（`:702-708`＋`:676-700`）：

```ts
	private prefixAutocompleteDescription(description: string | undefined, sourceInfo?: SourceInfo): string | undefined {
		const sourceTag = this.getAutocompleteSourceTag(sourceInfo);
		if (!sourceTag) {
			return description;
		}
		return description ? `[${sourceTag}] ${description}` : `[${sourceTag}]`;
	}
```

```ts
	private getAutocompleteSourceTag(sourceInfo?: SourceInfo): string | undefined {
		// Built-in extension commands are untagged, like built-in commands.
		if (!sourceInfo || sourceInfo.source === "builtin") {
			return undefined;
		}

		const scopePrefix = sourceInfo.scope === "user" ? "u" : sourceInfo.scope === "project" ? "p" : "t";
		const source = sourceInfo.source.trim();

		if (source === "auto" || source === "local" || source === "cli") {
			return scopePrefix;
		}

		if (source.startsWith("npm:")) {
			return `${scopePrefix}:${source}`;
		}
		// …git 源变体（parseGitUrl）…
	}
```

技能来源构造（`core/skills.ts:136-157`）：user 目录 ⇒ `source:"local", scope:"user"`；project 目录 ⇒ `scope:"project"`；显式路径（`"path"`）⇒ `source:"local"`、**scope 缺省**。代入标签逻辑 ⇒ **user→`u`、project→`p`、显式路径→`t`**（scope 缺省落 else 分支）。

### 1.3 提交路径：没有 skill 分支，展开恒开

提交处理器（`interactive-mode.ts:3200-3398`）是一长串内置命令精确匹配，**没有 `/skill:` 分支**；不中的文本最终走「普通消息提交」⇒ `session.prompt(text)`。

`prompt()` 内的展开（`core/agent-session.ts:2003-2011`）——gate 是 `expandPromptTemplates`（默认 `true`，`:1973`），**与 `enableSkillCommands` 设置无关**：

```ts
		const { text: currentText, images: currentImages } = processedInput;

		// Expand skill commands (/skill:name args) and prompt templates (/template args)
		let expandedText = currentText;
		if (expandPromptTemplates) {
			expandedText = this._expandSkillCommand(expandedText);
			expandedText = expandPromptTemplate(expandedText, [...this.promptTemplates]);
		}
```

推论（裁决②）：设置关掉后，手敲 `/skill:foo` 照样展开；未知名（`/skill:nope`）展开器原文透传、作为字面 prompt 发给模型。pi 对**任何**未识别 slash 都是这个落法（模板命令同形），不存在「Unknown command」提示。

### 1.4 设置选择器开关＋即时重建补全

选择器条目（`modes/interactive/components/settings-selector.ts:797-806`，插在 `block-images` 之后）：

```ts
		// Skill commands toggle (insert after block-images)
		const blockImagesIndex = items.findIndex((item) => item.id === "block-images");
		items.splice(blockImagesIndex + 1, 0, {
			id: "skill-commands",
			label: "Skill commands",
			description: "Register skills as /skill:name commands",
			currentValue: config.enableSkillCommands ? "true" : "false",
			values: ["true", "false"],
		});
```

改动回调（`interactive-mode.ts:4986-4989`）——写设置后**即时重建补全 provider**：

```ts
					onEnableSkillCommandsChange: (enabled) => {
						this.settingsManager.setEnableSkillCommands(enabled);
						this.setupAutocompleteProvider();
					},
```

### 1.5 RPC `get_commands` 的 skill 臂（`modes/rpc/rpc-mode.ts:679-710`）

```ts
			case "get_commands": {
				const commands: RpcSlashCommand[] = [];

				for (const command of session.extensionRunner.getRegisteredCommands()) { /* source:"extension" */ }
				for (const template of session.promptTemplates) { /* source:"prompt" */ }

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

线格式（`rpc-types.ts:81-89`）：`{ name; description?; source: "extension"|"prompt"|"skill"; sourceInfo: SourceInfo }`——**内置命令不在其中**（内置由 TUI 硬编码，get_commands 的语义是「可经 prompt 调用的命令」）。同一清单也绑进扩展核（`agent-session.ts:3376-3382`，`getCommands` 供扩展查询），java 无扩展运行时，此面随 B221。

### 1.6 死状态实证：`skillCommands` Map 只写不读

`interactive-mode.ts` 全文件仅 3 处命中：`:523` 声明、`:805` clear、`:810` set。无读取点 ⇒ 不移植（裁决⑤）。

---

## 2. pi-java 现状盘点

| 面 | 现状 | 差距 |
|---|---|---|
| 设置字段 | `Settings.java:50` `public Boolean enableSkillCommands;` 已有；唯一消费是 `ConfigCommand.java:71` 打印 | 无 accessor；无默认值回落；无设置页字段 |
| 提交路径 | `CommandRegistry.dispatch`（`core/slash/CommandRegistry.java:80-92`）对未知名返回 `"Unknown command: /xxx"` 结果 | `/skill:foo` 被吞，**到不了** `AgentSession.expandUserPrompt`（批 4，`AgentSession.java:1097-1102`）——展开器是死代码 |
| 补全目录 | `PiTuiApp.loadSlashItems`（`pi-java-tui PiTuiApp.java:111-121`）只从 `CommandRegistry` 取名，构造器里装一次（`:104`） | 无 skill 条目；无开关门；无来源标签；设置改动后不重建 |
| 设置页 | `SettingsScreen.FIELDS`（`pi-java-tui SettingsScreen.java:29-57`）10 个字段 | 无 "Skill commands" |
| RPC | `RpcDispatcher.buildCommands`（`RpcDispatcher.java:484-495`）返回内置 24 条，形状 `(name, description, argumentHint)` | 无 skill 臂；形状与 pi 不同（裁决④） |
| 技能容器 | `harness.skillManager().all()` 是全量技能（`SessionSetup.discoverSkills`＋钩子路径合并，`AgentSession.java:504/583-590`）；`Skill.sourceInfo()` 三值枚举 `USER/PROJECT/EXPLICIT`（agent-core `SkillSource.java`） | 与展开器同源，直接可用 |

**关键事实**：pi 侧「注册」从来不在 `AgentSession` 里发生（`:3377` 那行只是给扩展/RPC 的**清单**，不是可执行注册）。java 要还原的行为 = 补全目录条目＋提交透传，两处都以 `skillManager` 为源，与展开器同一容器。

---

## 3. 改动点

### 3.1 `SettingsAccessors`：`get/setEnableSkillCommands`

pi 对位：`settings-manager.ts:1156-1164`。读 = 缺省 true；写 = 全局域＋标脏。java setter 沿用既有约定（写域＋`markModified`，**不自带 flush**——pi 的 `save()` 对位在调用方，`SettingsScreen.cycle` 已统一 `flush()`，`SettingsScreen.java:128-129`）。

```java
    /**
     * B244（pi settings-manager.ts:1156-1158）：是否把技能注册为 {@code /skill:name}
     * 命令；缺省回落 pi 默认 {@code true}（settings-defaults.ts:33）。
     */
    public boolean getEnableSkillCommands() {
        var value = manager.effective().enableSkillCommands;
        return value == null || value;
    }

    /** B244（pi settings-manager.ts:1160-1164）：写全局域＋标脏（flush 在调用方）。 */
    public void setEnableSkillCommands(Boolean enabled) {
        manager.global().enableSkillCommands = enabled;
        manager.markModified("enableSkillCommands");
    }
```

### 3.2 `CommandRegistry.dispatch`：`/skill:` 前缀透传为 prompt

pi 对位：提交处理器无 skill 分支 ⇒ 落 prompt（§1.3）。java 的最小对齐：dispatch 把 `/skill:` 前缀输入当「非命令」返回 `null`，调用方（`InteractiveMode.dispatch` → `PiTuiApp.submit`:387-396）按 prompt 提交，`AgentSession.expandUserPrompt` 里批 4 的展开器接手。

```java
        var parts = input.substring(1).split("\\s+", 2);
        var command = commands.get(parts[0]);
        if (command == null) {
            // B244：/skill:<name> 不是注册命令——pi 里它只活在补全名单
            //（interactive-mode.ts:803-815），提交时随普通 prompt 走
            // _expandSkillCommand 展开（agent-session.ts:2008）。透传为 prompt
            //（null ≙ 非命令），未知名/读失败由展开器原文透传（批 4 语义）。
            if (parts[0].startsWith("skill:")) {
                return null;
            }
            return CompletableFuture.completedFuture(
                "Unknown command: /" + parts[0]
                    + " (try /hotkeys for the list of commands)");
        }
```

门**不设** `enableSkillCommands` 条件（裁决②）：pi 的展开与设置无关，关开关后手敲 `/skill:foo` 照样展开。

### 3.3 补全目录 skill 臂＋来源标签

新类 `core/slash/SkillCommandItems.java`（coding-agent，纯函数、可单测；TUI 不依赖反向）：

```java
package com.pijava.coding.agent.core.slash;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.skill.SkillSource;

/**
 * B244：{@code skill:<name>} 补全条目（pi interactive-mode.ts:803-815）。
 * 条目只有 name＋description（pi 无 argumentHint/handler——它不是真命令）。
 */
public final class SkillCommandItems {

    /** 一条补全目录条目（name 不带 {@code /} 前缀）。 */
    public record Entry(String name, String description) {}

    private SkillCommandItems() {}

    /**
     * 受 {@code enableSkillCommands} 门的 skill 条目清单（pi :807-814）；
     * {@code disableModelInvocation} 技能照样列出（pi 不过滤——该标志只管系统提示）。
     */
    public static List<Entry> list(List<? extends Skill> skills, boolean enabled) {
        if (!enabled) {
            return List.of();
        }
        var entries = new ArrayList<Entry>(skills.size());
        for (var skill : skills) {
            entries.add(new Entry("skill:" + skill.name(), taggedDescription(skill)));
        }
        return entries;
    }

    /** pi prefixAutocompleteDescription（:702-708）：{@code [tag] description}；描述空 ⇒ 仅 {@code [tag]}。 */
    static String taggedDescription(Skill skill) {
        var tag = sourceTag(skill.sourceInfo());
        var description = skill.description();
        return description == null || description.isEmpty()
            ? "[" + tag + "]"
            : "[" + tag + "] " + description;
    }

    /**
     * pi getAutocompleteSourceTag（:676-700）三值子集：user→u、project→p、
     * 显式路径→t（pi 显式路径 scope 缺省，skills.ts:150-154，落 else 分支）。
     * npm:/git: 复合标签无对位（java 无包源加载，窄差 D2）。
     */
    static String sourceTag(SkillSource source) {
        return switch (source) {
            case USER -> "u";
            case PROJECT -> "p";
            case EXPLICIT -> "t";
        };
    }
}
```

`PiTuiApp.loadSlashItems`（`pi-java-tui PiTuiApp.java:111-121`）追加 skill 臂：

```java
    /** Build the completion catalog from the slash-command registry. */
    private List<SlashCompleter.CommandItem> loadSlashItems() {
        var registry = session.services().slashCommands();
        var items = new ArrayList<>(registry.names().stream()
            .map(name -> {
                var command = registry.get(name);
                return new SlashCompleter.CommandItem(
                    name, command.argumentHint(), command.description());
            })
            .toList());
        // B244（pi interactive-mode.ts:803-815）：开关开 ⇒ 每个技能一个
        // skill:<name> 补全条目，排在内置之后（pi 序：内置→模板→扩展→skill）。
        var enabled = session.services().settings().accessors().getEnableSkillCommands();
        for (var entry : SkillCommandItems.list(session.harness().skillManager().all(), enabled)) {
            items.add(new SlashCompleter.CommandItem(entry.name(), null, entry.description()));
        }
        return List.copyOf(items);
    }
```

排序注：pi 的名单序是 内置→模板→扩展→skill，skill 恒在最后；java 无模板/扩展臂（B247/B221），追加即等价。

### 3.4 overlay 关闭重建补全目录

pi 对位：`:4986-4989` toggle 即时 `setupAutocompleteProvider()`。java 补全目录在构造器装一次（`PiTuiApp.java:104`）；设置屏 Enter 即写盘，但要有关屏后的重建点。落法：overlay 关闭处（`PiTuiApp.java:296-298`）统一重建——不只设置屏，所有 overlay 关屏都刷（重建是纯读装配，无副作用；裁决⑦）。

```java
            if (overlay.isDone()) {
                overlay.apply(session, this::switchSession);
                overlay = null;
                // B244（pi interactive-mode.ts:4986-4989 对位）：设置可能已改，
                // 关屏统一重建补全目录（pi 是 toggle 即时重建，窄差 D4 时机差）。
                chatScreen.setSlashCommands(loadSlashItems());
            }
```

### 3.5 `SettingsScreen` 加 "Skill commands" 字段

pi 对位：`settings-selector.ts:797-806`（label "Skill commands"，值 true/false）。java 设置屏是简化循环列表（无 block-images 行），字段追加到 `FIELDS` 末尾；布尔字段沿用既有写法（`List.of("false", "true")`，同 "Hide thinking block"）。

```java
        new SettingField("Skill commands", List.of("false", "true"),
            a -> String.valueOf(a.getEnableSkillCommands()),
            (a, v) -> a.setEnableSkillCommands(Boolean.parseBoolean(v))),
```

### 3.6 RPC `get_commands` 追加 skill 臂

pi 对位：`rpc-mode.ts:699-707`。java 保留现形状 `(name, description, argumentHint)` 与内置清单（java 扩面，见裁决④），skill 条目追加在末尾：

```java
    private List<RpcSlashCommand> buildCommands() {
        var registry = session.services().slashCommands();
        var commands = new ArrayList<RpcSlashCommand>();
        for (var name : registry.names()) {
            var cmd = registry.get(name);
            if (cmd != null) {
                commands.add(new RpcSlashCommand(
                    name, cmd.description(), cmd.argumentHint()));
            }
        }
        // B244（pi rpc-mode.ts:699-707）：skill 臂。pi 形状含 source/sourceInfo
        // 且不收内置——形状对齐随 B247；本批在现形状追加（argumentHint 恒 null）。
        for (var skill : session.harness().skillManager().all()) {
            commands.add(new RpcSlashCommand(
                "skill:" + skill.name(), skill.description(), null));
        }
        return commands;
    }
```

RPC 臂**不受** `enableSkillCommands` 门（pi `get_commands` 无门，§1.5——开关只管交互补全）。

---

## 4. 测试计划

pi 侧无命令注册面的直接测试（`agent-session-prompt.test.ts:240` 的展开用例批 4 已移植；`config-schemas.test.ts:124` 只覆盖 legacy 对象形 schema，随裁决③不做）。本批测试全为 java 新写：

| # | 测试（位置） | 覆盖 |
|---|---|---|
| 1 | `core/EnableSkillCommandsTest.java`（coding-agent，仿 `DefaultToolsSettingsTest`） | 缺省 true（字段 null）；set false→get false；写全局域（`manager.global()` 有值、project 域不染）；`InMemorySettingsStorage` 往返 |
| 2 | `core/slash/SkillCommandItemsTest.java`（coding-agent） | 门开⇒每技能一条 `skill:<name>`；门关⇒空表；标签三值 USER/PROJECT/EXPLICIT→`[u]/[p]/[t]`；描述空⇒仅 `[tag]`；`disableModelInvocation` 技能照列 |
| 3 | `core/slash/CommandRegistrySkillDispatchTest.java`（coding-agent） | `dispatch("/skill:foo bar")` ⇒ `null`；`dispatch("/skill:")` ⇒ `null`；`dispatch("/nope")` ⇒ 仍是 Unknown command 文案；内置 `/model` 不受影响 |
| 4 | `rpc/RpcGetCommandsSkillTest.java`（coding-agent，仿 `SessionRpcStateTest` 装配） | 会话挂一个技能 ⇒ `get_commands` 载荷含 `skill:<name>`（description 透传、hint null）；内置条目保留（java 扩面钉死）；无技能 ⇒ 无 skill 条目 |
| 5 | TUI（`pi-java-tui`，仿 `TuiAssemblyTest`/`PiTuiAppInputTest`） | 装配后补全目录含 `skill:` 条目（开关开）；`SettingsScreen` 字段表含 "Skill commands"；字段 cycle 写 accessors（false→true 往返） |

 overlay 关闭重建（§3.4）若能用现有 overlay 测试钩子（`currentOverlay()`）驱动则补一条；驱动不了则以 1–5 为验收面，重建行由代码评审把关。

---

## 5. 裁决与窄差

### 裁决（【场景/决定/风险】三段式）

**裁决①：注册面＝补全目录＋提交透传，不注册真 `SlashCommand`**
- 【场景】pi 的 `skill:<name>` 没有 handler，只活在补全名单里；提交靠「非内置 ⇒ 落 prompt ⇒ 展开」。java 若注册真命令，`execute` 无处可去（`SlashCommand` 的返回是「显示文本」，没有「转 prompt」语义），得新造 marker 协议。
- 【决定】补全目录加条目（§3.3）＋ dispatch 对 `/skill:` 前缀返回 `null` 透传（§3.2）。与 pi 同形，且零新协议。
- 【风险/代价】`null` 的语义从「非 slash 输入」扩为「非命令的 slash 输入」，两个调用点（`PiTuiApp.submit`、潜在 RPC/print 路径）都已是「null ⇒ 当 prompt」。[内部，模型不可见]

**裁决②：展开不受 `enableSkillCommands` 门**
- 【场景】直觉上「关了开关 `/skill:` 就不该生效」；pi 实证相反——`prompt()` 的 gate 是 `expandPromptTemplates` 参数（默认 true），设置只门补全名单（`interactive-mode.ts:807` 是唯一消费点）。
- 【决定】透传门（§3.2）不读设置；展开器（批 4）本就不读。
- 【风险/代价】关开关后手敲 `/skill:foo` 仍展开——与 pi 逐字一致，非 bug。[用户可见]

**裁决③：legacy 嵌套 `skills` 对象迁移不移植**
- 【场景】pi 兼容历史对象形 `skills: {enableSkillCommands, customDirectories}`（`settings-manager.ts:409-426`）。java 的 `.pi-java` 设置文件自始是数组形，无此历史。
- 【决定】不移植迁移臂；`Settings.skills` 保持 `List<String>`。
- 【风险/代价】用户手抄 pi 旧格式设置进 `.pi-java` ⇒ Jackson 类型不符，加载期报错（pi 是静默迁移）。登记窄差 D1。[边缘场景，用户可见报错]

**裁决④：RPC `get_commands` 形状差登记 B247，本批只追加 skill 臂**
- 【场景】pi 的 `get_commands` = 扩展＋模板＋skill（**无内置**），条目带 `source`/`sourceInfo`；java 现实现 = 内置 24 条、`(name, description, argumentHint)` 形状。全对齐要改线格式（删内置、加字段）＋补模板臂，超出 B244 登记文（「skill: slash 命令注册＋skillCommands 开关」）。
- 【决定】现形状追加 skill 条目（§3.6）；形状差（builtins 处置、`source`/`sourceInfo`、模板臂）连同 prompt template 命令注册面打包登记 **B247**。
- 【风险/代价】RPC 客户端看到的 skill 条目无 `source` 标记，与 pi 客户端不可互换；java 目前无内部消费者（client/server/web 零命中），外部消费者随 B247 重裁。[用户可见-线格式]

**裁决⑤：pi 的 `skillCommands` Map 不移植**
- 【场景】pi `interactive-mode.ts:523/805/810` 维护 `Map<命令名, filePath>`，全仓 grep 实证**只写不读**（疑似重构残余）。
- 【决定】不在 java 侧复刻。
- 【风险/代价】无影响；pi 后续若给它接上消费点，漂移检测时回收。[内部，模型不可见]

**裁决⑥：来源标签落 u/p/t 三值子集**
- 【场景】pi `getAutocompleteSourceTag` 还有 `npm:`/`git:` 复合标签（`u:npm:pkg`、`p:git:host/path@ref` 形）；java 的 `SkillSource` 是三值枚举，无包源加载面。
- 【决定】USER→`u`、PROJECT→`p`、EXPLICIT→`t`（pi 显式路径 scope 缺省落 else 分支，逐字等价）；npm/git 臂登记窄差 D2。
- 【风险/代价】java 包源加载落地时回收 D2。[用户可见-补全描述]

**裁决⑦：overlay 关闭统一重建补全目录**
- 【场景】pi 是 toggle 回调里即时 `setupAutocompleteProvider()`；java 设置屏字段是静态表，无 per-field 回调，补全目录在 app 构造器装一次。
- 【决定】overlay 关闭点（`PiTuiApp.java:296-298`）统一 `setSlashCommands(loadSlashItems())`，不只为设置屏加钩子。
- 【风险/代价】设置屏内 toggle 后、关屏前补全还是旧目录（pi 即时生效）。登记窄差 D4。[用户可见-时机]

### 窄差表

| # | 差 | pi | java | 可见性 |
|---|---|---|---|---|
| D1 | legacy 对象形 `skills` 设置 | 静默迁移为顶置键＋数组（settings-manager.ts:409-426） | Jackson 类型不符、加载期报错 | 用户可见（边缘） |
| D2 | 来源标签 | u/p/t＋`npm:`/`git:` 复合 | 仅 u/p/t（包域未做） | 用户可见 |
| D3 | 未识别 slash 的落法 | 任何 `/xxx` 都落 prompt 发给模型 | 仅放行 `/skill:` 前缀；其余仍报 `Unknown command`（java 既有 UX） | 用户可见 |
| D4 | 补全目录重建时机 | toggle 即时重建 | 设置屏关闭时重建 | 用户可见（时机） |

### 新登记

- **B247**（混合）：prompt template 命令注册面（补全条目——pi **无条件**注册，`interactive-mode.ts:787-792`；`/tpl` 透传——与裁决①同形）＋ RPC `get_commands` 形状对齐（收编模板臂、`source`/`sourceInfo` 字段、内置条目去留裁决）。触发条件：本批闭环后排期。

---

## 6. 闭环回填清单

1. map/04：#25（缺失→对齐，证据改填 §3.2/§3.3 类名）、E2-31（存疑→对齐，「字段有、1 处消费」改 accessor＋设置页＋补全门）、E4-2（缺失→对齐）；C 节 §122 行「单列」注记更新；账面 221.0→**226.0/496 ≈ 45.6%**、未加权 95→**98/290 ≈ 33.8%**。
2. docs/05：coding-agent 模块完成度重算；全仓 886.0→**891.0/1381 ≈ 64.5%**。
3. docs/04：B244 销号（迁移至已闭环区，注明本稿）；B247 新登记；窄差 D1–D4 入账。
4. 本稿状态行改「✅ 已闭环」＋commits 清单＋实施偏差注记。
5. 全量 `mvn clean verify`（显式 `JAVA_HOME=D:\soft\jdk\graalvm-jdk-25`）零错误零警告。

## 7. Commit 计划

1. `feat(coding-agent): enableSkillCommands accessors + /skill: dispatch passthrough (B244)`（§3.1/§3.2＋测试 1/3）
2. `feat(coding-agent): skill command items with u/p/t source tags (B244)`（§3.3 `SkillCommandItems`＋测试 2）
3. `feat(tui): skill commands in slash catalog + settings toggle + overlay-close refresh (B244)`（§3.3 TUI 半＋§3.4/§3.5＋测试 5）
4. `feat(coding-agent): RPC get_commands skill arm (B244)`（§3.6＋测试 4）
5. `docs(coding-agent): batch-2 closure — weighted 44.6% -> 45.6%`（§6 全量回填）
