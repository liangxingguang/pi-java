# 29 - L7 W8 设计：新增小单元与复核收尾

> 【背景摘要】L7 分段实施 W1–W7 已收口（`map/05` 230.5/252＝91.5%）。W8 是 L7 最后一段：把 `docs/21` §4 预审计漏项、`docs/21` §5 旧基线扫尾与 §7 列的收尾单元逐项对账，能闭环的落地，不能闭环的登记窄差并写明理由。
>
> 【锚点】pi `a00a5048d`；Java 当前工作区（W7 未提交）。上游取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`。
> 【原则】设计稿落盘后直接实施，不再等审核；改动留工作区不 commit。

## 1. 逐项判定（取证结论）

| 项 | 上游证据 | Java 现状 | 结论 |
|---|---|---|---|
| 漏项1 provider 品牌动画 | `components/provider-brand.ts`（73 行）：Radius 四色 sRGB shimmer（4 字符/色带、10 字符/秒、smoothstep）、OpenRouter 主题分叉 oklch 呼吸（2400 ms）、`startBrandAnimation` 50 ms；`oauth-selector.ts` 选中行用品牌样式＋伙伴 `✦` 标记 | `LoginProviderScreen` 用 `SelectList`（行=纯字符串）；无品牌样式 | **部分**：`✦` 伙伴标记可落地；逐字符动画不可达（见 §2.4 窄差 N-W8-1） |
| 漏项2 missing-cwd 三选 | `core/session-cwd.ts`：`getMissingSessionCwdOptions` Continue/Fork/Cancel；`interactive-mode.ts` `switchToForkedSession` | `SessionCwd` 已含三选与 `formatPrompt`；`SessionCwdPromptRunner`＋`SessionPersistence` 已消费（L5 W3） | **已闭环**（零代码） |
| 漏项3 reasoning drop 泛化 | `interactive-mode.ts` `REASONING_DROP_DIAGNOSTICS`（Anthropic thinking_dropped／OpenAI reasoning）＋原因串 | `ReasoningDropNotice` 已泛化并接 `CacheNoticeRenderer`（L7 W2 R25） | **已闭环**（零代码） |
| 漏项8 "to resume" 独立成行（#12237） | `interactive-mode.ts`：`"To resume this session:"` 与命令分两行 | `PiTuiLauncher.printResumeHint` 单行 `To resume this session: pi-java -r` | **缺失**，落地 |
| 漏项9 changelog 不可读写回 | `utils/changelog.ts` `getNewEntries` 返回 undefined；调用方写回当前版本 | `ChangelogSupport.forDisplay` 已写回（`:139-142`） | **已闭环** |
| 漏项9 session-share spawn error | `session-share.ts` 加 `proc.on("error")` 防崩 | Java 走 `HttpClient`（`SessionShare.java`），无子进程 | **不适用** |
| 漏项9 tool-execution spread 循环 | `tool-execution.ts` `lines.push(...contentLines)` 改 for 循环（V8 参数上限） | Java `List.addAll` 无该上限 | **不适用** |
| 漏项9 退出前 flushStdout | `utils/flush-stdout.ts`＋三处 await（session-picker／fatal／正常退出） | `PiTuiLauncher` 已有 `System.out.flush()` 于 alt 屏恢复；resume hint 未 flush | **补 flush**（低成本对位） |
| 漏项10 session-selector 渐进保选中 | `session-selector.ts`：`pendingSelectedPath`＋`setSessions(..., partial)` | `SessionListModel.setSessions` 无 partial；渐进加载中选中项丢失即被 clamp 到别的会话 | **缺失**，落地 |
| 漏项10 session-selector 搜索置顶 | `handleSearchInput`：query 变化且非空 ⇒ `selectedIndex=0`、`selectionTouched=false` | 转发输入后直接 `filterSessions`，选中可停在别处 | **缺失**，落地 |
| 漏项10 model-selector 刷新保偏好 | `model-selector.ts` `filterModels(query, preferredModel)`＋query 门控 | Java 选择器静态构建、无异步刷新；`SelectList` 光标键不重过滤、实际输入才 `refresh`（选中归零） | **天然对齐**（零代码） |
| K17 keybindings.json | `core/keybindings.ts`：`create()` 读 `<agentDir>/keybindings.json`、跳过 `$schema`、typebox 校验值、legacy 名迁移、按定义序排序、`reload()` | `KeybindingsManager.java:14` 注释「Phase 6」；无文件面 | **缺失**，落地 |
| S27 `/share` | `session-share.ts`＋`sessionShareDestination` | `SessionShareService` 已有 `destination` 现读与 `Stage`；`ShareFlow` 已有 `BorderedLoaderFrame` loader（W1 C7） | **复核翻对齐**（文档改判） |
| S28 `/export` | vendored marked/highlight 模板 | `HtmlExporter` 自写 `MarkdownToHtml` | 维持**存疑**（输出形状未逐项核，窄差登记） |
| R28 排队消息区 | streaming 时 bash/工具组件挂 pending 区 | `PendingQueueView` 只有消息行 | 维持**存疑**（流式组件结构面另批） |
| W16 `find()` 范围 | 项目内精确臂 | 保留跨项目阶梯 ③（D271） | 维持**存疑**（既有裁决） |
| R22/R27 彩蛋 | `earendil-announcement.ts`／`armin.ts`（384 行）／`easter-egg-3d.ts`（1292 行） | 无 | 维持**缺失**，登记 D-W8-2（不移植） |
| 10 行旧基线 | `docs/21` §5 已判定 | S6/S19/S25/T3/T4/T5/T7/T10/W14 翻正、S16 已由 W4 修复 | **已完成**（零代码，文档已在位） |

## 2. 代码设计

### 2.1 K17：`keybindings.json` 用户覆盖

文件：`pi-java-coding-agent/src/main/java/com/pijava/coding/agent/core/KeybindingsManager.java`

- 新增 `private static final Map<String,String> LEGACY_NAMES`：pi `KEYBINDING_NAME_MIGRATIONS` 全表（cursorUp→tui.editor.cursorUp … deleteSessionNoninvasive→app.session.deleteNoninvasive）。
- 新增构造 `private KeybindingsManager(boolean windowsKeybindings, @Nullable Path configPath)`：先建默认两表，再 `applyUserBindings(loadUserBindings(configPath))`；保留现有包内构造与 `new KeybindingsManager()`。
- `public static KeybindingsManager create()`：`create(FileSettingsStorage.defaultAgentDir())`。
- `static KeybindingsManager create(Path agentDir)`（测试缝）：`configPath = agentDir.resolve("keybindings.json")`。
- `public void reload()`：重建默认表后重新应用文件（pi `reload`）。
- `public Map<String, List<KeyStroke>> effectiveBindings()`：全局＋overlay 合并只读视图（pi `getEffectiveConfig`；`/hotkeys` 与测试读点）。
- 读取/校验：
  - 文件不存在／解析失败 ⇒ 空覆盖（静默，pi `loadRawConfig` catch）。
  - 跳过 `$schema`；值必须为字符串或字符串数组，其余丢弃（pi `KeybindingValueSchema`）。
  - 空数组＝解绑（保留动作、清空键表）。
- 键解析 `parseStroke("ctrl+left")`：按 `+` 分段，末段为键名；修饰 `ctrl/alt/shift`；`escape`→`esc`；键名小写；未知键名 ⇒ 丢弃该项。
- 覆盖落位：动作在全局表 ⇒ 替换全局表；在 overlay 表 ⇒ 替换 overlay 表；两表同有（`app.message.copy`）⇒ 两表都替换；未知动作 ⇒ 忽略。
- 迁移：legacy 名映射到新名；新旧同存 ⇒ 以新名为准（pi `migrateKeybindingsConfig` 的 `continue` 臂）。
- 不写回文件（pi 迁移仅内存生效）。
- 接线：TUI 侧构造处改 `KeybindingsManager.create()`（实施时按 grep 结果逐个替换；测试构造保持默认零覆盖）。
- 窄差 N-W8-2：`tui.editor.*`／`tui.input.*` 等由编辑器内部直读键的位点不消费用户覆盖（Java 编辑器不经过动作表）；只覆盖两表内的动作。

### 2.2 会话选择器：渐进加载保选中＋搜索置顶

文件：`pi-java-tui/src/main/java/com/pijava/tui/component/session/SessionListModel.java`

- 字段 `private String pendingSelectedPath;`
- `touchSelection()`：置 `selectionTouched=true` 同时 `pendingSelectedPath=null`（pi :614）。
- `setSessions(sessions, showCwd, query)` 保留（委托 4 参 `partial=false`）。
- 新增 `setSessions(sessions, showCwd, query, boolean partial)`（pi :354-378 逐字）：
  - `pendingPath = selectionTouched ? pendingSelectedPath : null`
  - `selectedPath = selectionTouched ? (pendingPath != null ? pendingPath : selectedSessionPath()) : null`
  - 未摸过 ⇒ `selectedIndex=0`；摸过且找到 ⇒ 置下标并清 pending；找不到且 partial ⇒ 保留 pending；否则清 pending。
- 新增 `selectTopForSearch()`：`selectedIndex=0; selectionTouched=false; pendingSelectedPath=null;`（pi :660-663）。

文件：`pi-java-tui/src/main/java/com/pijava/tui/component/session/SessionSelectorComponent.java`

- 新增 `private void handleSearchInput(KeyEvent event)`：记录前后 query；只在 query 变化时 `model.filterSessions(query)`；变化后 query 非空白 ⇒ `model.selectTopForSearch()`。
- 替换两处转发：`SESSION_DELETE_NONINVASIVE` 非空臂、分派末尾 else 臂。
- `onProgress`：`model.setSessions(..., true)`；`onLoadComplete`：`model.setSessions(..., false)`。

### 2.3 "to resume" 独立成行＋flush

文件：`pi-java-tui/src/main/java/com/pijava/tui/app/PiTuiLauncher.java`

- `printResumeHint`：
  ```java
  out.println("To resume this session:");
  out.println("pi-java -r");
  out.flush();
  ```
- 保持 D64（命令形按 Java CLI 能力，不打印 `--session <id>`）。

### 2.4 伙伴 provider `✦` 标记

文件：`pi-java-tui/src/main/java/com/pijava/tui/screen/LoginProviderScreen.java`

- `label(...)` 末尾：`PreferredProviders.isPreferredProvider(option.id())` 为真 ⇒ 追加 `" ✦"`（U+2726，单宽；pi `oauth-selector.ts:190` 逐字）。
- 品牌动画（shimmer/glow）：不落地——`SelectList` 行是纯字符串，TamboUI `ListElement` 无逐字符样式通道；另建自绘列表需替换整个选择器渲染与动画 tick，超出 W8 收尾成本。登记窄差 **N-W8-1**（伙伴标记已对位，动画未移植）。`LoginProviderScreen` 由 `ProviderLoginCatalog` 保证伙伴置顶（L5 W9 已对位）。

### 2.5 model-selector 刷新保偏好

Java `ModelSelectorScreen` 无异步刷新（模型表静态），`SelectList` 光标键不重过滤、输入变化才重排且选中归零——与 pi 的 query 门控＋置顶一致。**零代码**，仅在 map 证据列补记。

## 3. 复核结论（文档改判）

- S27 `/share` ⇒ **对齐**：`SessionShareService` 的 `destination`（auto|gist|radius）＋`Stage` 流；`ShareFlow` loader 走 W1 `BorderedLoaderFrame`；解锁原「加载指示器／destination 待复核」。
- S28／R28／W16 维持原判，补登窄差：
  - D-W8-3：`/export` HTML 输出形状未逐项核（vendored marked vs 自写转换）。
  - D-W8-4：R28 流式 bash/工具组件不进 pending 区。
- R22/R27 维持缺失；D-W8-2：彩蛋组件不移植（无 Java 消费面）。
- 旧基线 10 行：`docs/21` §5 已翻正，map 行在位，无动作。

## 4. 测试计划

| 面 | 测试 |
|---|---|
| K17 | `KeybindingsManagerTest` 增：单键覆盖、数组多键、空数组解绑、`$schema` 跳过、非法值丢弃、legacy 名迁移、新旧同存新名优先、`reload` 重读、未知动作忽略 |
| 会话选择器 | `SessionListModelTest` 增：partial 保留 pending、到货清 pending、完成缺失清 pending、搜索置顶清 touched；`SessionSelectorComponentTest` 增：输入变化置顶、光标键（←/→）不置顶 |
| resume hint | `PiTuiLauncherExitTest` 断言两行输出＋flush 后可读 |
| 伙伴标记 | `LoginFlowTest` 或新增断言：radius/openrouter 行含 `✦`，其他不含 |

命令：`./mvnw test -pl pi-java-coding-agent,pi-java-tui`（coding-agent 先 `-pl pi-java-coding-agent -am -DskipTests install` 供 tui 编译）。

## 5. 读数与回填

- 预期：K17 缺失→对齐（+1）、S27 存疑→对齐（+1）⇒ `map/05` **232.5/252＝92.3%**（未加权 133/153；投影 236.0/260≈90.8%）。
- 品牌动画不作 +（N-W8-1）；R22/R27 维持缺失（D-W8-2）；S28/R28/W16 维持存疑（D-W8-3/4）。
- 回填：`docs/map/05-tui-web.md` 对应行＋汇总；`docs/05` §1.2；`docs/09` L7 行与「下一分段」（L7 收口）；`docs/04` §0 追加 W8 收口条；本稿 §6 补实施结果。

## 6. 实施结果

**改动文件**

| 文件 | 改动 |
|---|---|
| `pi-java-coding-agent/.../core/KeybindingsManager.java` | `create()`／`create(Path)`／`reload()`／`effectiveBindings()`／`loadUserBindings`／`parseValue`／`parseStroke`／`applyUserBindings` |
| `pi-java-coding-agent/.../core/KeybindingsNames.java`（新） | pi legacy 迁移全表＋命名键集合（拆出保 500 行硬限） |
| `pi-java-tui/.../component/session/SessionListModel.java` | `pendingSelectedPath`、4 参 `setSessions`、`touchSelection` 清 pending、`selectTopForSearch`、读点 |
| `pi-java-tui/.../component/session/SessionSelectorComponent.java` | `handleSearchInput`（查询变化门控＋非空置顶）、两处转发替换、progress `partial=true` |
| `pi-java-tui/.../app/PiTuiLauncher.java` | resume hint 独立两行＋`flush`；`KeybindingsManager.create()` 接线 |
| `pi-java-tui/.../app/SessionSelectorRunner.java` | `KeybindingsManager.create()` 接线 |
| `pi-java-tui/.../screen/LoginProviderScreen.java` | 伙伴 provider `✦` 标记（`label` 包内可见供测试） |
| 测试 | `KeybindingsManagerTest`（九类用例，含 W7 遗留断言同步）、`SessionListModelTest`（3 例）、`SessionSelectorComponentTest`（1 例）、`PiTuiLauncherExitTest`（两行断言）、`LoginProviderScreenTest`（新，2 例） |

**测试读数**：`coding-agent` **2,056** 通过／0 失败；`pi-java-tui` **904** 通过／0 失败（1 跳过）；Checkstyle 0 error。W7 基线 898 → 904（新增 6 例）。

**读数**：K17＋1.0、S27＋1.0 ⇒ `map/05` **232.5/252＝92.3%**（未加权 133/153；投影回算 236.0/260＝90.8%）。

**遗留**：N-W8-1／N-W8-2／D-W8-2／D-W8-3／D-W8-4 编号在 P12 统一落；#20（L6 context footer）仍开放。

