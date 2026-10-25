# coding-agent 批 23 设计稿：扩展 MCP API 面＋`project_trust`/`mcp_servers_change` 事件＋RPC `get_commands` 扩展臂

【背景摘要】本批是 coding-agent 对齐批次的第 23 批（docs/20 §3 计划表清空后
首个立项批，来源＝§3「触发条件型欠账」清单的 B221 余 ①⑥ 与 D 域余 2 缺失）。
范围四块：① **扩展 MCP API 三件**——`registerMcpServer`/`unregisterMcpServer`/
`getMcpServers`（E5-23/24/25，挂载点接通已落地的 `McpServerRegistry`，B218 销号）；
② **`mcp_servers_change` 事件**（D#11——注册表变更广播＋无处理器时的扩展错误臂）；
③ **`project_trust` 事件与信任解析链**（D#1——本批最大件，含 trust.json 存储、
敏感资源门、预信任扩展装配、启动信任弹窗缝）；④ **RPC `get_commands` 扩展臂**
（B221⑥——命令 sourceInfo 盖章＋撞名 `:N` 编号，销批 3 窄差 D5，顺带 E5-20/E5-34）。
**内置 MCP 扩展本体**（六事件订阅者、连接编排、`/mcp` 命令）不在本批——
它是 B221① 的剩余部分，随下一批（批 24 候选，见 §4）。

术语（首现释义）：

- **扩展事件面**：pi 扩展经 `pi.on("事件名", handler)` 订阅的会话事件全集
  （pi 41 个；java 已有 37 个变体的 `ExtensionEvent`＋`ExtensionEventBus`）。
- **runner 对位物**：pi 的 `ExtensionRunner` 在 java 侧拆成
  `ExtensionEventBus`（注册表）＋`SessionEventFirer`（发射法合订本）＋三个桥。
- **first-wins/undecided 穿透**：`project_trust` 的分发规则——按扩展序逐个问，
  第一个返回「信任/不信任」的处理器胜出；返回「undecided（不表态）」继续问下一个。
- **信任敏感资源**：项目目录里只有「被信任」才该生效的东西——pi 判定清单是
  `.pi/` 下 8 个条目（settings.json、mcp.json、extensions、skills、prompts、
  themes、SYSTEM.md、APPEND_SYSTEM.md）加上祖先链上的 `.agents/skills`。
- **预信任装配（pre-trust bootstrap）**：pi 在问「信不信这个项目」**之前**，
  先用「不信任」状态加载一遍用户/全局扩展（项目扩展此时不加载），
  好让扩展能在 `project_trust` 事件里投票；定夺后再走正常装配。
- **sourceInfo 溯源**：pi 给每个资源（命令/技能/模板/主题）盖的「出身章」——
  路径、来源串、域（user/project/temporary）、出处（package/top-level）。
- **invocationName `:N` 编号**：两个扩展注册同名命令时，pi 把它们改名
  `name:1`、`name:2` 暴露（裸名不再可调用）。

---

## §0 基线与预期翻动

| 指标 | 前（批 22 闭环） | 后（预期） |
|---|---|---|
| 模块账面（Σ 485，去重） | 406.5 ≈ 83.8% | **416.0 ≈ 85.8%**（+9.5） |
| D 域（Σ 83） | 79.0 ≈ 95.2% | **83.0 = 100%**（满分域之十三） |
| E5 域（Σ 58） | 23.5 ≈ 40.5% | **29.0 = 50.0%**（+5.5） |
| map/07 mcp（Σ 129） | 98.0 ≈ 76.0% | **102.0 ≈ 79.1%**（+4.0：#87 本批
 ＋#81/#84 批 16 回填——map/07 账面滞后收平） |
| 全仓（Σ 1379） | 1,090.0 ≈ 79.0% | **1,103.5 ≈ 80.0%**（+13.5） |

翻动行（10 行，coding-agent 7 行 +9.5、mcp 3 行 +4.0）：

| 行 | 权重 | 前 | 后 | 任务 |
|---|---:|---|---|---|
| D#1 `project_trust` | 2 | 缺失 | 对齐 | T3 |
| D#11 `mcp_servers_change` | 2 | 缺失 | 对齐 | T2 |
| E5-23 `registerMcpServer` | 2 | 缺失 | 对齐 | T1 |
| E5-24 `unregisterMcpServer` | 1 | 缺失 | 对齐 | T1 |
| E5-25 `getMcpServers` | 1 | 缺失 | 对齐 | T1 |
| E5-20 `getCommands` | 1 | 缺失 | 对齐 | T4 |
| E5-34 扩展来源追踪（SourceInfo 投影） | 1 | 存疑 | 对齐 | T4 |
| map/07 #87 扩展挂载点 | 1 | 缺失 | 对齐 | T1 |
| map/07 #81 激活门暴露 | 2 | 缺失 | 对齐 | 批 16 已落，本批回填（账面滞后收平） |
| map/07 #84 渲染器挂载面 | 1 | 缺失 | 对齐 | 批 16 已落，本批回填（同上） |

销号预期：**B218**（注册表无生产者）、**B221 收编 ⑥**（余 ①＝内置扩展本体，
挂批 24）、**批 3 窄差 D5 销号**（RPC 扩展臂落地）。无新 B 预期；
窄差新登 4 条（§5，D134–D137）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`）

### 1.1 扩展 MCP API 三件（types.ts:1866-1872 声明；loader.ts:471-498 实现逐字）

```ts
// loader.ts:471-498
registerMcpServer(name: string, config: McpServerConfig) {
    assertActive();
    const validated = validateMcpServerConfig(name, config);
    if (typeof validated === "string") {
        throw new Error(`Invalid MCP server registered by extension "${extension.path}": ${validated}`);
    }
    const owner = runtime.mcpServers.get(name)?.extensionPath;
    if (owner !== undefined && owner !== extension.path) {
        throw new Error(`MCP server "${name}" is already registered by extension "${owner}"`);
    }
    // Names that differ only in `-` and `_` would share a namespace.
    const clash = runtime.mcpServers
        .list()
        .find((server) => server.name !== name && mcpNamespace(server.name) === mcpNamespace(name));
    if (clash) throw new Error(`MCP server "${name}" conflicts with registered server "${clash.name}"`);
    const server = { name, config: structuredClone(validated), extensionPath: extension.path };
    applyRuntimeChange(() => runtime.mcpServers.register(server));
},
unregisterMcpServer(name: string) {
    assertActive();
    applyRuntimeChange(() => runtime.mcpServers.unregister(name, extension.path));
},
getMcpServers() {
    assertActive();
    return runtime.mcpServers.list();
},
```

三条承重守卫：① 配置校验失败抛（`Invalid MCP server registered by extension
"<path>": <原因>`）；② **别的扩展**注册的同名抛错；③ `-`/`_` 归一后撞命名
空间也抛（`mcpNamespace(name)`＝`mcp__`＋`-`→`_`，mcp-servers.ts:155-157）。
`register` 文档注记（types.ts:1857-1865）：不持久化、每次加载要重注册、
mcp.json 同名优先、无 MCP 处理器时报扩展错误。

**排队/立即语义**（loader.ts:251-267＋:531-548）：装载期（state==="loading"）
所有 runtime 变更进 `pendingRuntimeChanges` 队列，`commit()` 时才真正 register；
`discard()`（装载失败）整队丢弃。bind 后的注册立即生效并触发 changeListener。

**注册表本体已落**：java `McpServerRegistry`（pi-java-mcp/config，docs/39 §3.1
逐字移植：LinkedHashMap 保序、unregister 所有权门、list() 深拷贝、
setChangeListener）——缺的是**生产者与接线**（B218）。

### 1.2 `mcp_servers_change` 发射与无处理器臂（runner.ts:457-461＋:749-765 逐字）

```ts
// runner.ts:457-461（bindCore 内）
this.runtime.mcpServers.setChangeListener(() => {
    void this.emit({ type: "mcp_servers_change", servers: this.runtime.mcpServers.list() });
    this.reportUnhandledMcpServers();
});

// runner.ts:749-765
reportUnhandledMcpServers(): void {
    if (this.hasHandlers("mcp_servers_change")) return;
    for (const server of this.runtime.mcpServers.list()) {
        if (this.reportedMcpServers.has(server.name)) continue;
        this.reportedMcpServers.add(server.name);
        this.emitError({
            extensionPath: server.extensionPath,
            event: "register_mcp_server",
            error: `MCP server "${server.name}" is registered, but no loaded extension connects MCP servers; another extension may have replaced the built-in MCP support`,
        });
    }
}
```

事件载荷（types.ts:728-733）：`{ type: "mcp_servers_change", servers:
RegisteredMcpServer[] }`（变更后的全量列表）。`reportedMcpServers` 是按
服务器名的去重集（每名只报一次）。观察型事件，无结果。

### 1.3 `project_trust` 事件与信任解析链

**事件类型**（types.ts:685-707 逐字）：

```ts
export interface ProjectTrustEvent { type: "project_trust"; cwd: string; }
export type ProjectTrustEventDecision = "yes" | "no" | "undecided";
export interface ProjectTrustEventResult { trusted: ProjectTrustEventDecision; remember?: boolean; }
export interface ProjectTrustContext {
    cwd: string; mode: ExtensionMode; hasUI: boolean;
    ui: Pick<ExtensionUIContext, "select" | "confirm" | "input" | "notify">;
}
export type ProjectTrustHandler = (
    event: ProjectTrustEvent, ctx: ProjectTrustContext,
) => Promise<ProjectTrustEventResult> | ProjectTrustEventResult;
```

**分发**（runner.ts:292-320）：按扩展序×扩展内 handler 序；首个返回非
`"undecided"` 的处理器立即短路胜出；处理器抛错只进 errors 不中断；
全部 undecided ⇒ result 缺席。

**解析主链**（project-trust.ts:47-97，七级优先级逐字序）：
① `trustOverride` 显式覆盖 ⇒ 直接用；② `hasTrustRequiringProjectResources(cwd)`
为假 ⇒ **恒 true**（没有敏感资源的项目不问）；③ 扩展 `project_trust` 事件
（有 result 即定夺；`remember===true` 落 trustStore）；④ trustStore 已存决策
（**最近祖先继承**）；⑤ `defaultProjectTrust`（always⇒true/never⇒false/ask⇒
继续）；⑥ 无 UI ⇒ **false**；⑦ UI 弹窗（选中才写回 trustStore，取消返 false）。

**敏感资源判定**（trust-manager.ts:186-208）：cwd 一级 `.pi/`（java 侧目录名
是 `.pi-java`，B182 既有约定）下 8 条目存在性——settings.json/mcp.json/
extensions/skills/prompts/themes/SYSTEM.md/APPEND_SYSTEM.md；外加祖先链爬升
查 `<dir>/.agents/skills`（唯一豁免 `$HOME/.agents/skills`，canonicalize 后
字符串等值比较）。

**ProjectTrustStore**（trust-manager.ts:210-246）：文件 `<agentDir>/trust.json`，
JSON 对象 `Record<规范化绝对路径, true|false>`；读取＝从 cwd 起**逐级向父
目录找最近命中**；写＝`setMany`，`decision:null` 语义是**删除该键**；写入前
键排序＋2 空格缩进＋尾换行；读写全在 `.lock` 文件锁内（pi 用 proper-lockfile
同步忙等 10×20ms）。

**信任选项**（trust-manager.ts:67-97，`includeSessionOnly:true` 时 5 项）：
`Trust`（落 cwd=true）／`Trust parent folder (<父路径>)`（落父=true **且删
cwd 自身键**）／`Trust (this session only)`（不落盘）／`Do not trust`
（落 cwd=false）／`Do not trust (this session only)`（不落盘）。

**调用点三处**：主入口 main.ts:740-777（需解析时**先用 projectTrusted=false
建 settings**，经 resourceLoader 的 `resolveProjectTrust` 钩在 reload 内做
预信任装配→发事件→回填，按 cwd 缓存 `projectTrustByCwd`）；
resource-loader.ts:505-525（`loadProjectTrustExtensions`＝强制不信任 reload＋
只装 user/global＋临时 CLI 扩展）；package-manager-cli.ts:775-808（包管理 CLI
独立走一遍，`useSavedProjectTrustOnly` 臂只读已存决策）。弹窗 UI 工厂
cli/project-trust.ts:6-63（`showStartupSelector` 系；非 interactive 双闸返
undefined/false，notify 写 stderr）。

### 1.4 RPC `get_commands` 扩展臂与命令溯源

**RPC 三源序**（modes/rpc/rpc-mode.ts:680-711 逐字要点）：扩展命令（第一臂，
`name` 用 **invocationName**）→ prompt 模板 → skill（名前缀 `skill:`）；
无内置、无 argumentHint；每条携带 `sourceInfo`。

**注册盖章**（loader.ts:303-319）：`registerCommand` 把 `extension.sourceInfo`
盖进每条命令（扩展无法覆盖——类型层 Omit）。

**撞名 `:N` 编号**（runner.ts:805-840 逐字要点）：按扩展序×注册序扁平化；
同名计数 >1 时第 N 次出现得 `name:N`；若该名亦被占则自增后缀到空闲；
`getCommand(name)` 只按 invocationName 命中——**裸名在撞名时不可调用**。

**SourceInfo 计算**（resource-loader.ts:918-931＋:978-1010＋source-info.ts:3-24）：
扩展的 sourceInfo＝按路径查包元数据，查不到走默认规则——合成路径
（`builtin:<name>`⇒source "builtin"、`<inline:...>`⇒尖括号前缀）scope 恒
temporary；文件路径按是否落在 `<agentDir>/extensions`（user 域）或
`<cwd>/.pi/extensions`（project 域）定 scope，source 恒 "local"，origin 恒
top-level，带 baseDir；包源（npm:/git:）经 PathMetadata 携带 source/scope/
origin=package。

**扩展面向 `getCommands`**（agent-session.ts:3361-3381）：与 RPC 臂同一份
三源 listing（扩展 `pi.getCommands()` 用）。

---

## §2 java 侧现状（取证 2026-10-25，main `e52eeea6`）

| 面 | 现状 |
|---|---|
| `McpServerRegistry` | 已落（pi-java-mcp/config；LinkedHashMap/所有权门/深拷贝/changeListener），**生产者零**（B218）；coding-agent 主代码零引用 |
| `McpConfigValidation` | 已落——`Valid(config)`/`Invalid(message)` sealed（pi validateMcpServerConfig 对位） |
| `ExtensionContext` | 无 MCP 三方法；`forExtension(String)` 只带扩展名，**无路径/来源身份** |
| `DefaultExtensionContext` | 晚绑定齐备（uiSupplier/modeSupplier/toolControl）；`PerExtensionContext` 管 on()/flag 归属 |
| 事件面 | `ExtensionEvent` 37 变体；**无 Mcp/Trust 变体**；bus 有 `hasHandlers(spec)`/`snapshot(spec)`/`emitError` |
| 装配尾（AgentSession :2890-2965） | 加载（:2895-2911）→桥→firer 创建→`sessionStart` 发射（:2958）→resources_discover——**changeListener 接线位在 firer 创建后、sessionStart 前**（pi bindCore 同相对位） |
| 命令注册 | `CommandRegistry` 批 20 形：`CommandSource` 标签＋内置撞名旁表；**无 sourceInfo、无跨扩展编号**（扩展互撞＝后者压前者）；`extensionCommand()` 只认裸名 |
| RPC `buildCommands`（RpcDispatcher :638-659） | 两臂（模板/skill），**扩展臂缺席**（注释自承随 B221）；`RpcSlashCommand.Source.EXTENSION` 已声明无生产者 |
| `SourceInfo` record | 已落（core.source；path/source/scope/origin/baseDir，pi 形） |
| 信任 | `TrustManager`＝**标记文件**（`~/.pi-java/trust/--<cwd转义>--`）——pi 现锚点无此物（旧锚点遗留形态）；启动解析 `resolveTrust`＝override 否则 `!"never"`——**ask 被当信任**；启动链不读任何存储决策；消费者：PackageCommand:129、ConfigCommand:121/:166、`/trust`（SettingsCommands:40-45） |
| 信任门消费 | `setProjectTrusted(false)` 清 project 域内存（批 19 修真）；SYSTEM.md 发现门（SessionSetup:185/:200）；resolve() 自动发现信任门（批 18） |

---

## §3 任务分解

### T1：扩展 MCP API 三件（E5-23/24/25＋B218＋map/07 #87）

- **注册表安家**：`DefaultExtensionContext` 增字段 `McpServerRegistry mcpServers`
  （pi `runtime.mcpServers` 对位——每会话一份，随上下文生死）。
- **扩展身份**：`ExtensionContext.forExtension` 增二参重载
  `forExtension(String name, String path)`（旧单参 default 委托兼容）。
  `ExtensionManager.load` 记录 name→path：`loadJar(Path)`⇒JAR 路径串；
  ServiceLoader 发现⇒合成 `"builtin:" + name`（裁决⑥）。`PerExtensionContext`
  持 `extensionPath`。
- **三方法**（`PerExtensionContext` 覆盖实现，守卫逐字 §1.1）：
  `registerMcpServer(name, config)`——`McpConfigValidation` 校验（Invalid⇒
  `IllegalArgumentException`，错文逐字 `Invalid MCP server registered by
  extension "<path>": <msg>`）→ owner 门（别的扩展同名抛）→ namespace 撞名抛
  → `registry.register(new RegisteredMcpServer(name, config, extensionPath))`；
  `unregisterMcpServer(name)`⇒`registry.unregister(name, extensionPath)`；
  `getMcpServers()`⇒`registry.list()`。基础上下文（无扩展归属）三方法抛
  `IllegalStateException`（与 on() 同先例）。
- **排队语义对位**：java 装载同步，changeListener 在装配尾才装（T2）——
  装载期注册天然静默落表、装载后注册即时广播，**不需要** pi 的
  pendingRuntimeChanges 机制（裁决⑧）。装载失败路径：主装载道不隔离
  （异常上抛、会话建不起来），无残留；`--help` 轻量道（loadIsolated）
  扩展 register() 抛错时已注册条目残留——登记窄差（§5）。
- 测试：三守卫各一、替换同名（同扩展）、unregister 所有权门、list 深拷贝、
  装载期静默＋接线后广播（与 T2 合测）。

### T2：`mcp_servers_change` 事件（D#11）

- `ExtensionEvent` 新变体 `McpServersChangeEvent(List<RegisteredMcpServer> servers)`
  ＋ `ExtensionEventSpec.MCP_SERVERS_CHANGE`（线名 `"mcp_servers_change"`，
  结果型 Void）。
- `SessionEventFirer.mcpServersChange(List<RegisteredMcpServer>)`：观察型广播
  （hasHandlers 快走＋逐处理器 catch→emitError，既有先例同形）。
- **装配接线**（AgentSession.loadExtensions 尾，firer 创建后、sessionStart
  发射前——pi bindCore 相对序对位）：
  `context.mcpServers().setChangeListener(() -> { firer.mcpServersChange(
  registry.list()); unhandledReporter.run(); })`。
- **无处理器臂**：新内部件 `UnhandledMcpServerReporter`（或 firer 内私有态）：
  `hasHandlers(MCP_SERVERS_CHANGE)` 为假时逐服务器报 `ExtensionError`
  （event `"register_mcp_server"`，错文逐字 §1.2），按名去重
  （`reportedMcpServers` 集）。bus.emitError 已接会话事件
  （AgentSession:2948）。
- 测试：注册⇒事件载荷全量列表；unregister⇒再发；无处理器⇒错误逐字＋
  同名不重复报；有处理器⇒不报。

### T3：`project_trust` 事件与信任解析链（D#1，本批最大件）

- **新类型**：`ProjectTrustEvent(String cwd)` 变体＋spec `PROJECT_TRUST`；
  `TrustDecision` enum（YES/NO/UNDECIDED——纯常量闭集用 enum）；
  `ProjectTrustEventResult(TrustDecision trusted, Boolean remember)` record。
- **分发**：`SessionEventFirer.projectTrust(cwd)` →
  `Optional<ProjectTrustEventResult>`——first-wins/undecided 穿透/逐处理器
  catch 收集 errors（runner.ts:292-320 逐字序；errors 经调用方上报告警文
  `Extension "<path>" project_trust error: <msg>`）。
- **`ProjectTrustStore` 替换 `TrustManager` 标记文件**（裁决②）：
  新类 `core/ProjectTrustStore`——`<agentDir>/trust.json`、最近祖先继承读、
  setMany（null=删键）、排序写＋2 空格＋尾换行、文件锁 RMW（java 用
  `FileChannel.lock` 包整段读改写；**别在持锁期间做无关盘读**——ai 批 6
  Windows 锁教训）。路径规范化：绝对化＋分隔符/大小写归一（canonicalizePath
  对位，Windows 小写盘符）。
  - 消费者改线：`PackageCommand:129`/`ConfigCommand:121,:166` 的
    `trustManager.isTrusted(cwd)` ⇒ `trustStore.get(cwd) == true`（pi
    package-manager-cli `useSavedProjectTrustOnly` 臂对位）；`/trust` 命令
    持久化改写 trustStore。`TrustManager` 类删除。
- **敏感资源门**：`ProjectTrustResources.hasTrustRequiringProjectResources(Path cwd)`
  ——`.pi-java/` 下 8 条目（目录名 B182 约定）＋祖先链 `.agents/skills`
  （豁免 `$HOME/.agents/skills`，规范化后等值比较）。纯函数。
- **解析主链**：`core/ProjectTrustResolver.resolve(...)` 七级逐字（§1.3）——
  入参：override/trustStore/defaultProjectTrust/扩展发射器引用/有无 UI/
  prompter。无扩展发射器（--no-extensions）跳过③。
- **预信任装配**（裁决③含行为变化，红字）：`AgentSession.create*` 流程改——
  1. `SettingsManager.load` 后：`shouldResolve`＝override 缺席 ∧
     有敏感资源；shouldResolve ⇒ **先 `setProjectTrusted(false)`**，
     否则按 ④⑤级（store/default）定初值（pi main.ts:741-749 对位）。
  2. shouldResolve 时：建**一次性轻量扩展集**（ServiceLoader 发现＋
     未信任 resolve 输出＋CLI `-e`——信任门天然排掉项目扩展），挂到临时
     context/bus，发 `project_trust`；定夺（含 remember 落盘、无 result 时
     续走 store→default→prompter）后 `setProjectTrusted(结果)`。
  3. 正常装配照旧（扩展二次加载——pi 同为两遍；扩展须耐受重复注册，
     pi 语义同）。
- **启动弹窗缝**：`ProjectTrustPrompter` 函数接口（入 cwd＋5 选项形，出选中
  项或 null）落 core；`AgentSession.create*` 增可选注入（默认 null＝无 UI
  ⇒false 臂，print/rpc/json/测试道不变）。TUI 侧在启动壳（批 14 既有
  启动选择器同 niche）实现 prompter——`PiTuiApp` 启动序：信任弹窗先于
  会话选择器。选项文案/序逐字（§1.3 五项）。
- **行为变化（changelog 级，模型不可见但用户可见）**：
  - `defaultProjectTrust:"ask"`（默认）从「当信任」变「无敏感资源⇒信任；
    有⇒TUI 弹窗/无 UI 不信任」——**pi 逐字语义**；
  - 存量 `~/.pi-java/trust/` 标记文件失效（不迁移——pi 无此物，迁移即
    自造语义）；
  - 测试夹具对策：有种项目 `.pi-java/settings.json` 的夹具须显式
    `defaultProjectTrust:"always"` 或预置 trust.json（防继承坑同族——
    见 [[settings-json-test-inheritance]]）。
- 测试：七级链逐级的单元钉（override/无资源恒真/事件 yes/no/undecided
  穿透/remember 落盘/store 最近祖先/always/never/无 UI false/prompter 五选项
  含 parent 删自身键/session-only 不落盘）；trust.json 线格式钉（排序/缩进/
  尾换行/null 删键）；并发 RMW 两线程；create* 预信任装配集成（假扩展
  投票 no ⇒ 项目 settings 不生效）。

### T4：RPC `get_commands` 扩展臂＋命令溯源（B221⑥＋销 D5＋E5-20＋E5-34）

- **登记目**：`CommandRegistry` 增扩展命令目录——
  `record ExtensionCommandEntry(String extensionPath, SourceInfo sourceInfo,
  SlashCommand command)`，插入序列表（`List`，注册序＝pi 扁平化序）。
  `extensionView()` 改二参 `extensionView(String extensionPath, SourceInfo)`
  ——`PerExtensionContext.slashCommands()` 经此暴露（注册即盖章＋记目）。
  主表/旁表分发形状不变（批 20 执行面不动）。
- **sourceInfo 计算**：`ExtensionSourceInfos.forPath(...)`——合成路径
  （builtin:/`<inline:`）⇒temporary；落 `<agentDir>/extensions`⇒user/local/
  top-level＋baseDir；落 `<cwd>/.pi-java/extensions`⇒project 同形；
  其余显式路径⇒temporary（getDefaultSourceInfoForPath 规则逐字，目录名随
  B182）。resolve 装配的包源扩展经 `ScopedPackage` 元数据携带（origin=
  package）。ServiceLoader 发现⇒`builtin:<name>`（裁决⑥）。**E5-34 翻对齐**。
- **`:N` 编号**：`CommandRegistry.resolvedExtensionCommands()`——
  runner.ts:805-840 逐字（同名 >1 ⇒ `name:N`；仍撞则自增后缀）。
  `extensionCommand()` 改按 invocationName 命中（裸名撞名时不可调——
  **行为变化**，pi 语义；`/hotkeys`/补全列 invocationName）。
- **RPC 臂**：`RpcDispatcher.buildCommands` 第一臂——resolvedExtensionCommands
  逐条 `{name: invocationName, description, source: EXTENSION, sourceInfo}`
  （rpc-mode.ts:684-690 对位），模板/skill 两臂不动。**D5 销号**。
- **E5-20**：`ExtensionContext.getCommands()`⇒三源 listing（扩展编号名＋模板＋
  `skill:` 前缀，agent-session.ts:3361-3381 序）——`AgentSession` 侧已有
  模板/skills 数据源，拼 List 返回。
- 测试：编号规则（同名×2/×3/后缀再撞）、注册盖章序、`/foo:2` 可调＋裸名
  不可调、RPC 线形（source:"extension"＋sourceInfo 全字段）、E5-20 三源序、
  sourceInfo 四规则（builtin/user/project/temporary）。

---

## §4 不做项

| 项 | 去向 |
|---|---|
| 内置 MCP 扩展本体（六事件订阅者＋连接编排＋工具注册同步＋`/mcp` 命令＋管理器视图＋startupWaitMs） | **批 24 候选**（B221① 剩余；map/07 G 组 #73–#86/#89；B214 载荷；收编 B188 shellPath/B189 客户端身份/B195 销号/docs/11 D3-D4 `_pendingToolNames`） |
| E5-21 `setModel`／E5-22 thinking level 存取 | 下一扩展面批（裁决⑤） |
| E5-5/6/8/9/10/12(triggerTurn)/13-17/26/27/28/33 | 各挂既有台账，不在本批 |
| 交互模式信任警告横幅＋reload 后隐式落盘（interactive-mode.ts:4226/:5296） | 挂账——TUI 信任 UX 面，随 map/05 屏域拍板变量 |
| 包管理 CLI 的完整 `resolveProjectTrusted` 弹窗臂（package-manager-cli.ts:781-808 全链） | java 包 CLI 非交互——只接「已存决策」臂（T3 消费者改线已含） |
| 标记文件→trust.json 迁移 | 不做（pi 无此物） |

## §5 窄差登记

| # | 内容 | 类 |
|---|---|---|
| D134 | `project_trust` 处理器收全 `ExtensionContext`（pi 类型层窄化为 4 个 ui 方法＋cwd/mode/hasUI；java 无窄化类型，预信任上下文的面天然空） | [内部] |
| D135 | 扩展身份用 `builtin:<name>` 合成路径／JAR 路径串（pi 用加载文件路径；ServiceLoader 扩展无文件路径）——扩展错误消息内的标识与 pi 字面不同 | [模型可见文案，边缘] |
| D136 | trust.json 锁＝`FileChannel.lock`＋JVM 内监视器（pi proper-lockfile 同步忙等 10×20ms；语义同为进程间互斥） | [内部] |
| D137 | 隔离装载道（`--help` 轻量道／预信任道）扩展 register() 抛错时已注册 MCP 条目残留（pi discard 整队丢弃；隔离道上下文随即弃） | [内部·边缘] |

## §6 裁决

**裁决①（批次切分）**
【场景】B221 余 ①⑥ 与 D 域两行同族，但「内置 MCP 扩展本体」（pi 1,344 行
工厂）与「扩展 API/事件/溯源面」是可独立验收的两层——面不立则本体无的
放矢，本体不落则面无人消费（仅报错臂可被三方扩展触发）。
【决定】本批做面（T1–T4），本体下批（批 24）。中间态＝pi「无 MCP 扩展」
形态的逐字行为（注册服务器⇒扩展错误提示），语义自洽。
【风险/代价】批 23 闭环后默认会话仍不连接 mcp.json 服务器（现状不变，
无回退）；[内部，模型不可见]。

**裁决②（信任存储换代）**
【场景】java `TrustManager` 标记文件是旧锚点形态；pi 现锚点唯一形态是
`<agentDir>/trust.json`（最近祖先继承、null=删键、锁内 RMW）。两套并存
会让「信不信任」有两个答案。
【决定】移植 `ProjectTrustStore`（trust.json），删 `TrustManager`，三处
消费者改线；不迁移存量标记文件。
【风险/代价】用户既有的信任标记静默失效，首次进有敏感资源的项目会重新
被问（ask 臂）[用户可见，一次性]；Windows 文件锁下锁内不做无关盘读
（既有教训）。

**裁决③（ask 行为变化与预信任装配）**
【场景】java 现状把 `defaultProjectTrust:"ask"`（默认）当「信任」，启动链
不读存储、不问扩展、不弹窗——pi 是七级链（§1.3），ask 的终局是「无 UI
⇒不信任／有 UI⇒弹窗」。对齐必须动启动装配序（先不信任装扩展→问→回填）。
【决定】按 §3-T3 全链落地；弹窗经 `ProjectTrustPrompter` 缝注入，非 TUI
模式 null（＝pi 无 UI 恒 false 臂）。
【风险/代价】[用户可见] 有 `.pi-java/` 敏感资源的项目首次启动出弹窗、
print/rpc 下项目资源不生效——pi 逐字行为；存量测试夹具带项目 settings 的
须补 `defaultProjectTrust:"always"` 或预置 trust.json（预估扫一遍夹具，
settings 继承坑同族手法）；预信任装配让扩展装载跑两遍（pi 同）。

**裁决④（E5-20 随 T4 收）**
【场景】`pi.getCommands()` 与 RPC `get_commands` 扩展臂是同一份三源
listing 的两个出口。
【决定】T4 一并落 `ExtensionContext.getCommands()`，E5-20 翻行。
【风险/代价】无影响。

**裁决⑤（E5-21/E5-22 出本批）**
【场景】`setModel`/thinking 存取与 MCP/信任/命令溯源不同族，塞进本批只
会摊薄评审注意力。
【决定】留下一扩展面批。
【风险/代价】E5 域少翻 4.0 账面——账面诚实优先。

**裁决⑥（扩展身份合成路径）**
【场景】pi 的 `extension.path` 是加载文件路径；java ServiceLoader 发现的
扩展无文件路径，`RegisteredMcpServer.extensionPath` 与 sourceInfo 盖章都
需要稳定身份。
【决定】ServiceLoader 发现⇒`builtin:<name>`（pi 合成路径约定同形）；
`loadJar`/resolve⇒实际路径串。
【风险/代价】错文里的扩展标识与 pi 字面不同（路径 vs 名）[模型可见文案，
边缘——仅扩展错误消息]；登记 D-新2。

**裁决⑦（注册表落 `DefaultExtensionContext`）**
【场景】pi 注册表挂 extension runtime（每会话一份）；java 的 runtime 对位
物是 `DefaultExtensionContext`（晚绑定/事件总线都在这）。
【决定】注册表为其字段；装配尾由 AgentSession 接线 changeListener。
【风险/代价】`--no-extensions` 道上下文仍在但无消费者——与 pi runtime
恒在注册表恒在同形；无影响。

**裁决⑧（装载期排队＝listener 后装，不移植 pendingRuntimeChanges）**
【场景】pi 的装载期排队是因为装载异步、runtime 未绑；java 装载同步且
changeListener 在装配尾才装——装载期注册天然静默，语义等价。
【决定】不建 pendingRuntimeChanges 机制；`--help` 轻量道残留登记 D-新4。
【风险/代价】[内部] 轻量道残留（边缘；轻量道无会话随即弃）。

## §7 测试计划

| 测试 | 覆盖 |
|---|---|
| `ExtensionMcpServerApiTest` | T1 三守卫/替换/所有权门/深拷贝/基础上下文 ISE |
| `McpServersChangeEventTest` | T2 发射/载荷全量/无处理器臂逐字＋去重/有处理器不报 |
| `ProjectTrustResolverTest` | T3 七级链逐级＋undecided 穿透＋remember 落盘＋无 UI false |
| `ProjectTrustStoreTest` | trust.json 线格式（排序/缩进/尾换行）/最近祖先/null 删键/并发 RMW |
| `ProjectTrustResourcesTest` | 8 条目各自命中/祖先 .agents/skills/HOME 豁免 |
| `AgentSessionPreTrustTest` | 假扩展投 no ⇒ 项目 settings 不生效；投 yes+remember ⇒ 落盘 |
| `ExtensionCommandNumberingTest` | T4 `:N` 编号/再撞自增/裸名不可调/`:2` 可调 |
| `RpcGetCommandsExtensionArmTest` | 线形 source:"extension"＋sourceInfo＋三源序（D5 销号钉） |
| `ExtensionSourceInfoTest` | builtin/user/project/temporary 四规则 |
| 存量修形 | `/trust` 命令测试、PackageCommand/ConfigCommand 信任夹具改 trust.json、带项目 settings 的夹具补信任（裁决③） |
| 回归 | `mvn clean verify` 全绿（含 spotbugs——verify 阶段才跑，收口必全量） |

## §8 对账表

| 行 | 前 | 后 | 证据（实施后回填） |
|---|---|---|---|
| D#1 | 缺失 | 对齐 | T3（实施后填） |
| D#11 | 缺失 | 对齐 | T2 |
| E5-23/24/25 | 缺失 | 对齐 | T1 |
| E5-20 | 缺失 | 对齐 | T4 |
| E5-34 | 存疑 | 对齐 | T4 |
| map/07 #87 | 缺失 | 对齐 | T1 |
| B218 | 在册 | 销号 | T1 |
| B221 | 余①⑥ | 收编⑥（余①挂批 24） | T4 |
| D5（批 3 窄差） | 在册 | 销号 | T4 |
| map/07 #81/#84 | 注记过期 | 注记修正（账面不变） | 批 16 已落，本批回填 |

## §9 闭环回填

### 实施记录（2026-10-25 闭环）

- **任务全落**：T1（ExtensionContext MCP 三件＋forExtension 路径身份＋注册表
  安家 `DefaultExtensionContext`＋ExtensionManager 路径跟踪）＋T2
  （`McpServersChangeEvent` 变体/spec＋firer 广播＋`UnhandledMcpServerReporter`
  逐字错文＋装配尾 changeListener 接线）＋T4（登记目＋`:N` 编号＋RPC 扩展臂
  ＋E5-20 三源序＋`ExtensionSourceInfos` 四规则＋`SourceInfos.fromSkill` 共享件）
  ＋T3（`ProjectTrustEvent`/`TrustDecision`/first-wins firer＋`ProjectTrustStore`
  〔trust.json 逐字节线格式——手写输出器钉 `"key": value` 冒号间距〕＋
  `ProjectTrustResources`＋`ProjectTrustResolver` 七级链＋`ProjectTrustSetup`
  预信任装配＋`ProjectTrustPrompter` 缝＋TUI `TrustPromptRunner` 启动壳＋
  `TrustManager` 删除＋四消费点改线）。
- **撞坑与修真**：
  ① `suffixSkipsTakenNumberedNames` 首版预期写错（pi 自增从 occurrence 起——
  实为 `dup:2:2` 非 `dup:2:3`，按 runner.ts:828-836 重推修正）；
  ② 内置重注册压旁表时登记目未同步摘除 ⇒ `CommandRegistrySourceTest` 存量
  红（修真：BUILTIN 分支同步 removeIf）；
  ③ RPC 臂测试同扩展重注册语义误当撞名（同扩展 Map.set 覆盖——改双扩展）；
  ④ **T3 最大坑**：信任判定首版取 `ToolContext.cwd`，但项目 settings 的消费
  根是 settings 存储面的 projectDir（默认 `user.dir`）——判定与消费不同根
  ⇒ 改从 `FileSettingsStorage.projectPath` 反推（pi 单源 process.cwd() 对位），
  集成测试随之重定向 `user.dir`；
  ⑤ spotbugs 七钉全是 `Path.getParent()` 空路径（`ProjectTrustStore` 加
  `trustDir` 字段＋`forSettings` 工厂，三处调用点改走它）；
  ⑥ `McpListCommandTest` 夹具补种 trust.json（裁决③ 的存量对策唯一实击点）。
- **verify 史**：T1/T2 定向 13/13 → T4 定向（含修形）全绿 → T3 定向 31/31
  → coding-agent 全量 1,596 绿 → verify #1 spotbugs 7 钉红（NP_NULL_ON_SOME_PATH，
  `getParent()` 链）→ 修真后定向绿＋spotbugs:check 零钉 → 全量 verify #2 收口。
- **评审**：无独立评审轮（用户指令：设计稿审过直接实施）。
- **回填落位**：docs/04 §4 批 23 段＋B218 销号＋B221 收编⑥＋批 3 窄差 D5 销号
  ＋D134–D137 新登；docs/05 §1/§2.4/§2.5；map/04 D 域 #1/#11＋E5-20/23/24/25/34
  ＋E7-1 注记＋三处汇总＋合计；map/07 G 组 #81/#84（批 16 回填）/#87＋G 组注记
  ＋加权汇总；docs/20 §1/§2（批 23 行）/§3（触发条件型欠账 D 域两行移除）。
