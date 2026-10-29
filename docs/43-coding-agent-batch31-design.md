# docs/43 — 批 31 设计稿：C 域 slash 尾（完整选择器＋Radius 主路径）

## §0 背景与范围

【背景摘要】批 30 闭环后，按 `docs/06` §3 序 24 收尾 C 域 slash 命令。本批覆盖 `/settings`、`/scoped-models`、`/share`、`/login` 四条命令。

本稿中的术语：

- **C 域**：`docs/map/04-coding-agent.md` 的 slash 命令能力单元分组。
- **Radius**：pi 的 `pi-messages` 网关及其账号、模型目录、制品分享和 MCP 服务。
- **选择器**：终端内完成搜索、导航、修改和确认的交互组件。
- **模型可见面**：大模型能够读到的提示文本、工具 schema 等内容。本批主要是用户可见面与协议面，不改系统提示。
- **窄差**：实现与 pi 仍有差异，但已登记边界和触发条件的条目。

### 已获用户拍板的范围裁决

1. **重开 B226**：Radius 内建 provider 纳入本批。不能只做命令外壳。
2. **完整 `/settings`**：补全设置项、搜索和子菜单，不把现有平铺子集直接核销为完整选择器。
3. **方案 A——领域内核＋薄 TUI 适配**：状态机、持久化、HTTP 和流程编排可脱离终端测试；TamboUI 只承担按键和渲染。

### 范围内

| 单元 | 本批交付 |
|---|---|
| C-1 `/settings` | pi 全设置项、搜索、10 行窗口、警告/主题/每模型思考档子菜单、写回与已有即时生效回调 |
| C-5 `/scoped-models` | 会话态选择、搜索、单项/全量/provider 切换、排序、显式保存、后台目录刷新 |
| C-8 `/share` | Radius 制品上传主路径；无 Radius 可用凭证时走 `gh` 私有 gist 备用路径 |
| C-18 `/login` | 认证方式/provider 选择器、API key/OAuth 登录、登录后目录刷新与默认模型处理、Radius MCP 配置提议 |
| B226 / ai#21/#44 | Radius provider、动态目录、browser/device-code 两种 OAuth、凭证刷新 |
| D85 触发项 | `/bug` 上传在存在 Radius 凭证时带 Bearer token；无凭证仍匿名 |

### 范围外

- Mermaid、LaTeX、终端图片、选区复制、OSC 进度等独立渲染/终端子系统。本批只让设置选择器正确读写既有设置，不借机宣称这些子系统闭环。
- TUI 队尾的其它屏幕、examples、evals、session 余量。
- codemode、Radius 分类器和图像模型。
- 真实账号、真实 Radius、真实 GitHub 的自动化上传。验收使用本地假服务与假 `gh`。

---

## §1 现状与账面纠错

### 四条命令的真实差距

| 行 | 权重 | Java 现状 | pi 锚点证据 |
|---|---:|---|---|
| C-1 | 2 | `SettingsCommands` 发 marker；`SettingsScreen` 是平铺子集，无搜索，缺多项设置 | `settings-selector.ts:465-995`；`settings-list.ts:38-328` |
| C-5 | 1 | 空参只提示使用 `+/-`；写参数立即持久化，没有会话态选择器 | `scoped-models-selector.ts:18-401`；`interactive-mode.ts:5378-5497` |
| C-8 | 1 | 直接用 `GITHUB_TOKEN` 上传 JSONL gist；无 Radius、无 `gh`、无 HTML/gist viewer | `session-share.ts:57-217` |
| C-18 | 2 | 无参只报 Usage；有参经 `System.console().readPassword` 保存 API key | `interactive-mode.ts:5788-6415`；`login-dialog.ts:12-238`；`oauth-selector.ts:27-224` |

### C 域汇总行必须与逐行收平

逐行实际状态是 **21 对齐＋4 存疑**，不是汇总行的“20 对齐＋5 存疑”。

- 四个存疑行的权重合计为 6，半分得 3 分。
- 其余 21 行权重合计为 32。
- 因而设计前逐行实算应为 **35.0/38 ≈ 92.1%**，不是旧汇总 **34.5/38 ≈ 90.7%**。
- 本批四行翻正后为 **38.0/38＝100%**。
- 相对批 30 账面，闭环回填为 **+3.5**：其中 +0.5 是旧账纠错，+3.0 是本批功能落地。

---

## §2 总体结构与数据流

### 模块分工

| 模块 | 新增/调整 |
|---|---|
| `pi-java-ai` | `RadiusProvider`、网关配置解析、`RadiusOAuthFlow`、OAuth 注册与刷新、Radius 默认模型 id |
| `pi-java-coding-agent` | scoped-model 状态机与会话控制器、登录编排、分享编排、Radius MCP 写回、设置访问器补全 |
| `pi-java-tui` | 完整设置列表、scoped-model 选择器、登录选择器/对话框、分享 loader 与取消 |
| `pi-java-mcp` | 不改协议；复用 `McpConfig.load/add` 和既有 `McpServerConfig.Http` |

### 关键数据流

1. **scoped-model**：选择器修改 `EnabledModelSet` → 每次修改只更新会话 scope → Ctrl+S 才写全局 `enabledModels` → 后台刷新完成后按“用户是否已改动”决定是否重算初始选择。
2. **登录**：TUI 选择认证方式/provider → `ProviderLoginService` 在虚拟线程执行 → 写凭证 → 刷新该 provider 目录 → 必要时选择默认模型 → Radius 成功后询问是否写 MCP → 写回后执行既有 `/reload`。
3. **分享**：当前分支序列化（含 `pi.share`）→ Radius provider＋有效 token 存在则上传制品 → Radius 已尝试时无论成功或失败都不回退 gist → 否则检查 `gh` → 导出 HTML → 私有 gist → 返回 viewer 与 gist 两个 URL。

### 文件长度约束

- `AgentSession` 已在批 29 拆到 496 行，本批**不在其中新增方法**。会话操作放入同包控制器，经既有包私有协作者出口完成。
- 设置列表拆为 `SettingsScreen`、`SettingsItems`、`SettingsListModel/View` 和子菜单组件。
- Radius OAuth 的浏览器流、设备流和 HTTP 解析分文件，任何文件不得超过 500 行；FileLength error 闸门是验收条件。

---

## §3 `/settings`：完整选择器

### 3.1 pi 的列表行为

`settings-list.ts:222-250` 的按键分派：

```ts
if (kb.matches(data, "tui.select.up")) {
	if (displayItems.length === 0) return;
	this.selectedIndex = this.selectedIndex === 0 ? displayItems.length - 1 : this.selectedIndex - 1;
} else if (kb.matches(data, "tui.select.down")) {
	if (displayItems.length === 0) return;
	this.selectedIndex = this.selectedIndex === displayItems.length - 1 ? 0 : this.selectedIndex + 1;
} else if (
	kb.matches(data, "tui.select.confirm") ||
	(data === " " && (!this.searchEnabled || this.searchInput?.getValue().length === 0))
) {
	this.activateItem();
} else if (kb.matches(data, "tui.select.cancel")) {
	this.onCancel();
} else if (this.searchEnabled && this.searchInput) {
	this.searchInput.handleInput(data);
	this.applyFilter(this.searchInput.getValue());
}
```

`settings-list.ts:264-291` 的激活语义：

```ts
if (item.submenu) {
	this.submenuItemIndex = this.selectedIndex;
	this.submenuComponent = item.submenu(/* … */);
} else if (item.values && item.values.length > 0) {
	const currentIndex = item.values.indexOf(item.currentValue);
	const nextIndex = (currentIndex + 1) % item.values.length;
	const newValue = item.values[nextIndex];
	item.currentValue = newValue;
	this.onChange(item.id, newValue);
}
```

Java 对应：

- `SettingsListModel` 保存全量项、过滤项、选中下标、搜索串和子菜单返回位置。
- 上下键环绕；搜索后选中第 0 项；最多显示 10 项；显示选中项描述。
- Enter 和空搜索时的 Space 激活；值不存在于选项表时，与 pi 一样落到第 0 项。
- Esc 在子菜单中先返回上一级，主列表中才关闭。
- 搜索只匹配 label（`settings-list.ts:310-313`），不把 description 混入。

### 3.2 设置项清单与顺序

按 `settings-selector.ts:482-865` 建表，动态插入规则保持 pi 顺序：

1. Auto-compact。
2. 仅终端支持 inline images 时插入 Show images、Image width。Java 当前能力为 false，两行不显示。
3. Auto-resize images、Block images、Skill commands、Show hardware cursor、Editor padding、Output padding、Autocomplete max items、Clear on shrink、Terminal progress。
4. Steering mode、Follow-up mode、Transport、HTTP idle timeout、Cache warming、Hide thinking、Mermaid diagrams、Cache miss notices、Collapse changelog、Quiet startup、Install telemetry、Default project trust、Double-escape action、Tree filter mode。
5. Warnings、Default thinking level per model。
6. TUI mode、Fullscreen exit output、Fullscreen scrollbar、Fullscreen copy on select、Fullscreen wheel scrolling、Theme。

Java 独有的“Thinking level”平铺行删除；pi 在该页只提供每模型 override 子菜单。

值域逐字：

| 项 | 值 |
|---|---|
| Transport | `sse` / `websocket` / `websocket-cached` / `auto` |
| HTTP idle timeout | `30 sec` / `1 min` / `2 min` / `5 min` / `disabled` |
| Cache warming | `off` / `streaming` / `idle` |
| Mermaid diagrams | `off` / `final` / `streaming` |
| Quiet startup | `true` / `header` / `false` |
| Default project trust | `Ask` / `Always trust` / `Never trust`，写回 `ask/always/never` |
| Double-escape action | `tree` / `fork` / `none` |
| Fullscreen wheel scrolling | `auto`＋去重排序后的 `1/2/3/5/10/当前数字值` |

pi 的 HTTP 选项（`http-dispatcher.ts:8-14`）：

```ts
export const HTTP_IDLE_TIMEOUT_CHOICES = [
	{ label: "30 sec", timeoutMs: 30_000 },
	{ label: "1 min", timeoutMs: 60_000 },
	{ label: "2 min", timeoutMs: 120_000 },
	{ label: "5 min", timeoutMs: 300_000 },
	{ label: "disabled", timeoutMs: 0 },
] as const;
```

### 3.3 设置访问器补全

新增或调整以下访问器；均写全局域并 `markModified`，屏幕激活后统一 `flush`，与现有 SettingsScreen 一致：

- `SettingsNetworkAccessors`：`getTransport()` 缺省 `auto`；`setTransport(String)`。
- `SettingsCompactionAccessors`：`get/setImageAutoResize`（缺省 true）、`get/setBlockImages`、`get/setMarkdownMermaid`。
- `SettingsUiPrefsAccessors`：`get/setShowImages`、`get/setImageWidthCells`、`get/setClearOnShrink`、`get/setShowTerminalProgress`。nested `terminal` record 重建时保留其余组件。
- `Settings.quietStartup` 边界类型由 `Boolean` 扩为 `Object`，接受 boolean 或 `"header"`。新增字符串形访问器；保留 boolean setter 兼容既有调用。
- `SettingsAccessors.setEnabledModels` 允许 null；null 表示删除设置键，与 pi `undefined` 对应。另增可区分“未设置”和显式空表的只读口。

pi 的 quiet-startup 值来自 `settings-selector.ts:556-562`：

```ts
{
	id: "quiet-startup",
	label: "Quiet startup",
	description: "Disable verbose printing at startup (header: keep only the startup header)",
	currentValue: String(config.quietStartup),
	values: ["true", "header", "false"],
},
```

### 3.4 子菜单与宿主回调

- **Warnings**：独立 `WarningSettingsScreen`，含 Anthropic extra usage 一项；修改立即写回。依据 `settings-selector.ts:147-188`。
- **Theme**：复用批 21 `ThemeSubmenuScreen`，保留 single/automatic、预览和 Esc 还原，不另造主题引擎。
- **Per-model thinking**：复用 `ModelThinkingLevelScreen` 的模型/级别两步；改为循环返回模型步，直到 Esc。当前模型 override 变更仍立即调用 `setThinkingLevel`。
- **Steering/Follow-up**：除写设置外，调用 `harness.setQueueModes`，与 `SessionReload` 使用同一 `SessionSetup.queueMode` 映射。
- **Skill commands**：写回后重建 slash 补全目录。
- **Hide thinking / Cache miss notices**：走 ChatScreen 现读源或重建转录，避免只写文件不生效。
- **Cache warming**：继续走 `session.setCacheWarmingMode`，保留后台模式重调和。
- **TUI mode / hardware cursor / fullscreen 等**：复用已有宿主能力；没有即时应用能力的项只写持久化设置，差异登记 D177，不把独立终端子系统塞入本批。

---

## §4 `/scoped-models`：会话态选择器

### 4.1 用 ADT 保留 null 与空表的区别

新增 `EnabledModelSet`：

```java
sealed interface EnabledModelSet {
    record All() implements EnabledModelSet {}
    record Explicit(List<String> orderedIds) implements EnabledModelSet {}
}
```

- `All` 对应 pi `null`：全部可用，Ctrl+P 不做 scope。
- `Explicit([])` 对应空数组：用户显式清空，不等于全部启用。
- `ScopedModelSelection` 实现 `isEnabled/normalize/toggle/enableAll/clearAll/move/sortedIds`，不依赖 TUI。

pi 的核心规则（`scoped-models-selector.ts:21-70`）：

```ts
function isEnabled(enabledIds: EnabledIds, id: string): boolean {
	return enabledIds === null || enabledIds.includes(id);
}
function normalizeEnabled(result: string[], allIds: string[]): EnabledIds {
	return result.length === allIds.length && result.every((id) => allIds.includes(id)) ? null : result;
}
function toggle(enabledIds: EnabledIds, allIds: string[], id: string): EnabledIds {
	if (enabledIds === null) return allIds.filter((modelId) => modelId !== id);
	const index = enabledIds.indexOf(id);
	if (index >= 0) return [...enabledIds.slice(0, index), ...enabledIds.slice(index + 1)];
	return normalizeEnabled([...enabledIds, id], allIds);
}
function move(enabledIds: EnabledIds, id: string, delta: number): EnabledIds {
	if (enabledIds === null) return null;
	// …只交换显式列表中的相邻项，越界不动
}
function getSortedIds(enabledIds: EnabledIds, allIds: string[]): string[] {
	if (enabledIds === null) return allIds;
	const enabledSet = new Set(enabledIds);
	return [...enabledIds, ...allIds.filter((id) => !enabledSet.has(id))];
}
```

显式列表中已不可用 id 保留，并以 `[unavailable]` 和删除线显示；不得在刷新时静默丢弃。

### 4.2 会话与持久化分离

`ScopedModelController` 位于 coding-agent 同包，使用 `AgentSession.modelTools()`，不改 496 行的 `AgentSession`。

pi 的会话更新规则（`interactive-mode.ts:5399-5414`）：

```ts
const hasEnabledAvailableModel = enabledIds?.some((id) => availableModelIds.has(id)) ?? false;
const allAvailableModelsEnabled =
	enabledIds !== null && [...availableModelIds].every((id) => enabledIds.includes(id));
if (enabledIds && hasEnabledAvailableModel && !allAvailableModelsEnabled) {
	const newScopedModels = resolveModelScopeFromModels(enabledIds, availableModels).scopedModels;
	this.session.setScopedModels(/* resolved entries */);
} else {
	this.session.setScopedModels([]);
}
```

保存规则（`interactive-mode.ts:5438-5445`）：

```ts
const allEnabled =
	enabledIds !== null &&
	enabledIds.length === availableModels.length &&
	enabledIds.every((id) => availableModelIds.has(id));
const newPatterns = enabledIds === null || allEnabled ? undefined : enabledIds;
this.settingsManager.setEnabledModels(newPatterns ? [...newPatterns] : undefined);
this.showStatus("Model selection saved to settings");
```

因此：

- Enter、Ctrl+A、Ctrl+X、Ctrl+P、Alt+↑/↓ 只改会话并显示 `(unsaved)`。
- Ctrl+S 才写设置，并显示 `Model selection saved to settings`。
- 关闭选择器不回滚会话修改；未保存只表示不写全局设置。
- `/scoped-models +id/-id` 旧直写臂保留，但与空参选择器分开测试，避免把两种语义混合。

### 4.3 搜索、按键和刷新

- 搜索文本使用 `provider/id/name`，复用现有 fuzzy 匹配；无结果显示 `No matching models`。
- 选中项下方显示 `Model Name: <name>` 或 `Model unavailable`。
- 页脚显示切换/全选/清空/provider/排序/保存键位和计数。
- `KeybindingsManager` 的 overlay 表新增六个 pi 动作：`app.models.save=ctrl+s`、`enableAll=ctrl+a`、`clearAll=ctrl+x`、`toggleProvider=ctrl+p`、`reorderUp/Down=alt+up/down`。
- 选择器打开时立即显示缓存目录和 `Refreshing model catalogs…`，同时后台刷新；15 秒超时文案、部分失败文案和成功文案按 `interactive-mode.ts:5465-5484` 逐字。
- 用户尚未改动且初始 scope 来自设置时，刷新后重算选择；用户已改动或会话已有显式 scope 时，只更新模型资料，不覆盖选择。
- 关闭后置 disposed 代次；迟到结果不得再更新屏幕或会话。网络取消沿用 Java 刷新层的既有边界，登记 D181。

---

## §5 Radius provider 与 OAuth（B226 重开）

### 5.1 provider 与动态目录

新增 `RadiusProvider`，注册到 `ProviderCatalog`，默认 id/name 为 `radius` / `Radius`，默认网关 `https://radius.pi.dev`，provider 配置声明 `RADIUS_API_KEY`。请求车道复用 `PiMessagesApi`，不复制协议代码。

`DefaultProviders.REMOTE_CATALOG_EXCLUDED` 加入 `radius`：Radius 使用自己的 `/v1/config`，不能再套 pi.dev 通用远程目录。

pi 的目录替换原则（`radius.ts:26-32`）：

```ts
const baselineModels = /* shipped baseline */;
// Gateway catalog for this account. Radius org owners can disable models, so once known it replaces
// the shipped baseline instead of overlaying it. The baseline only covers the time before any catalog exists.
let dynamicModels: Model<"pi-messages">[] | undefined;
```

刷新顺序：

1. 离线相从 `ModelsStore` 恢复该 provider 的目录，`updateOnly` 发布。
2. 联网相经 `Credentials.resolveApiKey("radius")` 取当前 token，GET `/v1/config`。
3. 校验并过滤模型，转换成 `ModelInfo`，`api=pi-messages`、`provider=radius`、`baseUrl=config.baseUrl`。
4. 用 `persistAndUpdate` **整体替换**动态目录，不与 baseline 做 union。
5. 网络失败保留已恢复目录，由 `ModelCatalogRefresh.Result.errors` 记录。

pi 的网关校验和请求（`radius-config.ts:80-95`）：

```ts
export async function loadRadiusGatewayConfig(gateway: string, apiKey?: string): Promise<RadiusGatewayConfig> {
	const headers: Record<string, string> = { accept: "application/json" };
	if (apiKey) headers.authorization = `Bearer ${apiKey}`;
	const response = await fetch(new URL("/v1/config", gateway), { headers });
	if (!response.ok) {
		throw new Error(`Could not load Radius config from ${gateway}: ${response.status}: ${truncateHttpBody(await response.text())}`);
	}
	const config = sanitizeRadiusGatewayConfig(await response.json());
	if (!config) throw new Error(`Invalid Radius config from ${gateway}`);
	return config;
}
```

`DefaultModelIds` 增加 `radius → balanced`，依据 `model-resolver.ts:27`。登录后若 balanced 不存在，Radius 特判取目录首条，依据 `interactive-mode.ts:6096-6102`。既有 `DefaultModelIdsTest` 不再要求 Radius 具有静态分片，改用动态目录夹具验证 balanced 与首条回落两臂。

**生成 baseline 的边界**：pi 锚点 Git 树只有 `radius.models.ts` 生成入口，没有入库的 `providers/data/radius.json`。本批不伪造“锚点逐字”静态数据；Java 初始 baseline 为空，models-store 或首次 `/v1/config` 成为目录来源。该边缘差异登记 D176。

### 5.2 两种 OAuth 登录法

新增 `OAuthProvider.Radius` 变体与 `RadiusOAuthFlow`。`OAuthProviders.get("radius")` 使用默认网关并在每次调用时构造，避免类初始化冻结配置；`names()` 包含 radius。`DefaultOAuthTokenRefresher`、`AuthCommand` 同步处理新变体。`PI_RADIUS_GATEWAY` 继续只服务既有 `/bug` 上传配置，不擅自扩大成内建 provider 或分享的覆盖开关。

pi 的选择面（`radius.ts:281-301`）：

```ts
const loginMethod = await interaction.prompt({
	type: "select",
	message: `Sign in to ${options.name}:`,
	options: [
		{ id: "browser", label: "Sign in with browser (recommended)" },
		{ id: "device-code", label: "Sign in with device code (when signing in from another device)" },
	],
});
```

- **browser**：GET `/v1/oauth` 发现 `authorizationEndpoint`；PKCE S256；state；固定 `127.0.0.1:1456/oauth/callback`；`handoff=url`；共享 `LoopbackCallbackServer`；端口占用不偷偷换口。
- **device-code**：POST `/v1/oauth/device`，client_id=`pi-gateway`，scope=`gateway offline_access`；复用设备轮询内核，但 Radius 的过期/拒绝文案按 `radius.ts:253-264`。该臂只展示 verification URI/code，不自动打开浏览器。
- **token/refresh**：POST `/v1/oauth/token`；过期时间提前 60 秒；OAuth access token 按 API key 语义进入现有 `Credentials` 链。
- 取消统一为 `Login cancelled`；不在 UI 线程执行网络或轮询。

---

## §6 `/login`：provider 选择、完成处理与 MCP 提议

### 6.1 登录目录与选择器

新增 `ProviderLoginCatalog`，从 `ProviderRegistry`、`OAuthProviders` 和 `EnvApiKeyResolver` 汇总选项，不把 auth 元数据塞进所有 Provider 实现：

- 每个选项包含 provider id、显示名、`OAUTH/API_KEY`、当前凭证状态。
- 按显示名排序；搜索匹配名称、id、认证类型。
- 无参 `/login` 显示 `Sign in with an account`、`Sign in with an API key`，末尾单独显示 `Sign in with Radius`。
- `/login <provider>` 同时按 id 或显示名大小写不敏感精确查找。唯一命中直接启动；同 provider 多认证方式时打开认证方式选择；未命中则带原搜索词打开 provider 选择器。
- 无可用方法分别显示 `No account providers available.`、`No API key providers available.`、`No login methods available.`。

pi 顶层 Radius 入口（`interactive-mode.ts:5885-5907`）：

```ts
// The top-level selector offers Radius directly, as its last option.
const radiusOption = providerOptions
	? undefined
	: this.getLoginProviderOptions("oauth").find((provider) => provider.id === RADIUS_PROVIDER_ID);
const options: string[] = [];
if (availableAuthTypes.has("oauth")) options.push(subscriptionLabel);
if (availableAuthTypes.has("api_key")) options.push(apiKeyLabel);
if (radiusLabel) options.push(radiusLabel);
```

### 6.2 登录执行与写回

`ProviderLoginService` 在 coding-agent 提供阻塞式领域方法，TUI 用虚拟线程调用：

- API key：输入提示 `Enter <provider auth name>`（`auth/helpers.ts:12-16`）；写 `FileCredentialStore`。
- OAuth：经 `OAuthInteraction` 接入对话框；写 `OAuthCredentialStore`。
- 取消回到来源选择器；其它失败保留会话并显示 `Failed to login to <name>: <message>` 或 `Failed to save API key for <name>: <message>`。
- 凭证已经保存、后续目录/模型同步失败时，不得误报“登录未保存”；错误文案区分两个阶段。
- 成功后只刷新该 provider，15 秒超时；刷新失败不撤销凭证。

当前模型不在目录（Java 对 pi unknown-model 的等价判据）时：

1. 选该 provider 的默认模型并持久化 defaultProvider/defaultModel。
2. 动态目录暂时为空时先显示保存成功和刷新中，刷新后补选择。
3. Radius 优先 `balanced`，缺席取目录首条。
4. 刷新期间用户已换模型或换会话时，不覆盖用户选择。

`ModelToolControl` 增加带 persist 参数的模型设置方法；`AgentSession` 不新增行。

### 6.3 登录对话框

新增 `LoginFlow`、`LoginProviderScreen`、`LoginDialogScreen`：

- provider 搜索、上下导航、Enter 确认、Esc 返回。
- API key 输入掩码显示；OAuth 的 URL、device code、说明和进度逐行展示。
- 网络在虚拟线程；Esc 取消 future 并关闭对话框。
- 不在 `System.console()` 上读取输入，保证真正运行于 TUI。
- 富 OSC 链接、专门复制按钮和 IME 光标的像素级结构不移植，登记 D179；文本 URL/code 完整可见。

### 6.4 Radius MCP 配置提议

Radius OAuth 成功后调用 `RadiusMcpOffer`：

pi 的判据（`interactive-mode.ts:6377-6397`）：

```ts
const existing = servers.find(
	(server) => "url" in server.config && normalizeUrl(server.config.url) === normalizeUrl(RADIUS_MCP_URL),
);
if (existing && "url" in existing.config && existing.config.auth?.provider === providerId) return;
let name = existing?.name ?? "radius";
if (!existing && servers.some((server) => server.name === name)) name = "radius-mcp";
const config = existing
	? { ...existing.config, auth: { provider: providerId } }
	: { url: RADIUS_MCP_URL, auth: { provider: providerId } };
delete config.oauth;
```

Java 行为：

- 读取全局 agentDir 与未信任项目上下文下的合并配置，查找去尾斜杠后等于 `https://radius.pi.dev/mcp` 的 HTTP server。
- 已是 `auth.provider=radius` 时不询问。
- 否则询问 `Configure Radius MCP in <agentDir/mcp.json>?`，选项 Yes/No。
- Yes：复用 `McpConfig.add` 原位新增/替换，删除 `oauth` 并保留其它 HTTP 配置；随后调用既有 `session.reload()`。
- 写失败显示 `Could not update <path>: <message>`；No 不写文件。
- 内建登录、分享和 MCP 提议均使用默认 Radius 网关；`PI_RADIUS_GATEWAY` 对 `/bug` 的既有覆盖不改变这三个端点。自定义 models.json Radius 兼容 provider 的动态目录扩展不在本批。

---

## §7 `/share`：Radius 主路径与 gist 备用路径

### 7.1 领域服务

以新 `SessionShareService` 取代 `MiscCommands` 内的直接 gist 调用。旧 `SessionShare` 的 token/gist HTTP 实现退出该命令路径；`GhCli` 负责 `gh auth status` 和 `gh gist create`。

导出改用既有 `SessionBranchSerializer.serialize(session)`，因此 Radius 和 HTML 都使用当前分支，并携带 `pi.share` 的 systemPrompt/tools。pi 对应物是 `session-share.ts:25-54`。

### 7.2 Radius 上传

触发条件：provider 注册表存在 radius，且 `Credentials.resolveApiKey("radius")` 能取得 token。凭证解析已有“临期 5 分钟刷新”，对应 pi `minOAuthValidityMs`。

请求逐字契约（`session-share.ts:118-146`）：

```ts
const body = fs.readFileSync(tmpFile);
const url = new URL("/v1/artifacts", DEFAULT_RADIUS_GATEWAY);
url.searchParams.set("visibility", "organization");
url.searchParams.set("title", "Pi session");
const response = await fetch(url, {
	method: "POST",
	headers: {
		Authorization: `Bearer ${token}`,
		"Content-Type": "application/x-ndjson",
		"Content-Length": String(body.byteLength),
	},
	body,
});
const json = await response.json().catch(() => null);
if (!response.ok || !json?.artifact) {
	context.showError(`Failed to upload Radius artifact: ${json?.error || response.statusText || response.status}`);
	return true;
}
context.showStatus(`Share URL: ${json.artifact.canonical_url}`);
```

- body 是 UTF-8 NDJSON 原始字节，Content-Length 按字节数。
- 成功输出 `Share URL: <canonical_url>`。
- 已尝试 Radius 后，HTTP 错误、响应缺 artifact、网络异常或取消都终止分享，**不回退 gist**。
- Java `HttpResponse` 无 statusText，错误链末位用数字 status，登记 D180。
- `/share` 与 pi 一样使用 `DEFAULT_RADIUS_GATEWAY`，不读取 `PI_RADIUS_GATEWAY`；测试通过构造函数注入本地 URL。

### 7.3 gist fallback

只有“无 radius provider 或无 token”进入：

1. `gh auth status`；非零显示 `GitHub CLI is not logged in. Run 'gh auth login' first.`；启动失败显示 `GitHub CLI (gh) is not installed. Install it from https://cli.github.com/`。
2. 导出当前主题 HTML；失败显示 `Failed to export session: <message>`。
3. `gh gist create --public=false <html>`；非零显示 `Failed to create gist: <stderr||Unknown error>`。
4. 从 stdout URL 末段取 gist id；缺 id 显示 `Failed to parse gist ID from gh output`。
5. 输出两行：`Share URL: <PI_SHARE_VIEWER_URL||https://pi.dev/session/>#<id>` 与 `Gist: <gistUrl>`。

TUI 显示 `Uploading to Radius...` 或 `Creating gist...` loader；Esc 取消并显示 `Share cancelled`。取消不伪装成功，也不触发备用路径。临时目录递归清理失败忽略。

---

## §8 D85 随 Radius 接线

`BugReportFlow` 上传时改为：

```java
var radiusToken = Credentials.resolveApiKey("radius").orElse(null);
var result = uploader.get().upload(bundle, radiusToken);
```

- 有 token：使用既有 `Authorization: Bearer` 臂，D85 销号。
- 无 token：保持 pi 匿名上传。
- 凭证刷新失败沿上传失败路径显示，不静默降级为匿名，避免把归因错误藏掉。
- 本项不重开 `/bug` 的其它窄差。

---

## §9 实施任务拆分

| 任务 | 内容 | 主要验证 |
|---|---|---|
| T1 | 设置边界类型与访问器：quietStartup 三态、terminal/images/markdown/transport、enabledModels null | accessor 默认值、nested record 保留、JSON 往返 |
| T2 | `SettingsListModel/View`＋完整 `SettingsItems` | 顺序、条件图片行、搜索、环绕、10 行窗口、循环值、Space |
| T3 | 设置子菜单与宿主动作 | warnings/theme/thinking 返回、queue mode、cache warming、补全重建、flush |
| T4 | `EnabledModelSet`＋`ScopedModelSelection`＋六个键位 | pi 函数对拍：All/Explicit、归一、过滤集全选/清空、不可用 id、排序边界 |
| T5 | `ScopedModelController`＋选择器＋刷新 | 会话态不即时落盘、Ctrl+S、scope 清空规则、刷新不覆盖用户修改、disposed |
| T6 | Radius gateway/config＋provider＋注册 | `/v1/config` 校验、错误截断、动态目录整体替换、离线恢复、pi-messages 车道 |
| T7 | `RadiusOAuthFlow`＋注册/刷新 | browser 1456/PKCE/state/handoff、device 两法、60 秒 skew、取消、refresh |
| T8 | `ProviderLoginCatalog/Service`＋登录 TUI | 无参/直参/歧义/未命中、API key/OAuth、目录失败、默认模型、取消返回 |
| T9 | `RadiusMcpOffer` | 已配置不问、radius-mcp 撞名、保配置删 oauth、Yes 写回＋reload、No 不写 |
| T10 | `SessionShareService`＋TUI loader | Radius 成功/失败不 fallback、无 token fallback、gh 三错误、HTML/gist URL、取消清理 |
| T11 | `/bug` token 接线＋命令描述/markers | 有/无 token、刷新失败不匿名；`/share` 描述改 pi 逐字 |
| T12 | 全量回归、FileLength、台账 | 模块测试→全仓 `mvn clean verify`→地图与总账实算 |

实施顺序按依赖为 T1→T2/T4/T6→T3/T5/T7→T8/T9/T10→T11→T12。每个可独立编译阶段单独 commit，显式路径 stage。

---

## §10 测试设计

### 纯领域与协议测试

- `SettingsListModelTest`：至少覆盖搜索重置、上下环绕、值表外回落第 0 项、子菜单返回原位。
- `ScopedModelSelectionTest`：将 `scoped-models-selector.ts:21-70` 的 Each 函数做输入/输出对拍，特别区分 `All` 与 `Explicit([])`。
- `ScopedModelControllerTest`：验证只有 Ctrl+S 写设置；覆盖“全部可用已启用但含 unavailable id”时仍清 scope 的 pi 条件。
- `RadiusGatewayConfigTest`：坏 JSON、缺 baseUrl/models、过滤坏模型、512 字符错误截断、Bearer 有/无。
- `RadiusProviderTest`：缓存恢复→联网替换；第二次空目录必须替换旧目录而不是 union。
- `RadiusOAuthFlowTest`：本地设备端点、本地授权发现与 loopback；不连接真实 Radius。
- `ProviderLoginServiceTest`：凭证保存与模型同步分阶段错误；刷新期间模型已被用户替换时不覆盖。
- `SessionShareServiceTest`：本地假 HTTP 与假 `GhCli`，断言请求字节、query、header、fallback 条件和最终文案。

### TUI 测试

- 设置搜索、全项顺序、warnings/theme/thinking 子菜单 Esc 返回、修改后 flush。
- scoped-model 的六个动作、unsaved 标记、不可用项、刷新迟到丢弃。
- 登录 API key 掩码、OAuth device code 文本、Esc 取消、Radius 顶层入口位于最后。
- 分享 loader 两臂、取消、Radius 失败后不出现 `Creating gist...`。

### 验证纪律

1. 底层 ai 改动后，后续模块测试使用 `mvn test -pl <module> -am`，防止拾旧 jar。
2. 不用管道接 `head` 判断 Maven 成败；以落盘日志和退出码为准。
3. 不并发跑同一仓 Maven，避免互踩 target。
4. 最终 `mvn clean verify` 必须零错误零警告，测试全绿，checkstyle/FileLength 与 spotbugs 全过。
5. 测试 home、settings、auth、models-store、MCP 路径全部显式隔离，不继承真 home 配置。

---

## §11 裁决与窄差（设计审核点）

### 裁决④：初始 Radius baseline 为空

- 【场景】pi 的静态 Radius 数据是生成物，未纳入锚点 Git 树；无法按“只走 `git show <sha>:<path>`”取得逐字数据。
- 【决定】不手写或从未锚定工作树复制 baseline。Java 以空 baseline 启动，恢复 models-store 或读取 `/v1/config` 后获得目录。
- 【风险/代价】边缘场景。[用户可见] 首次离线且无任何缓存时，Radius 无模型；认证和联网刷新不受影响。登记 D176。

### 裁决⑤：完整设置选择器不连带移植独立终端子系统

- 【场景】设置项中有 Mermaid、copy-on-select、clear-on-shrink、terminal progress、TUI mode 等，其中部分消费面属于既有 TUI/终端欠账。
- 【决定】选择器字段、值域、读写和已有宿主回调全部对齐；不因此宣称 R7、S2、K39 等独立单元闭环。TUI mode/hardware cursor 当前只能保证持久化，不能宣称全部即时切换。
- 【风险/代价】存量约束，非本批范围。[用户可见] 个别项仍需重启或等待对应子系统；登记 D177，原地图行不变。

### 裁决⑥：不移植鼠标与富登录控件的像素级结构

- 【场景】pi `SettingsList` 支持鼠标，LoginDialog 有 OSC 链接、复制按钮和 IME 焦点细节；Java 当前选择器主要是键盘交互。
- 【决定】本批对齐键盘、文本、状态与持久化；鼠标设置列表登记 D178，富登录控件登记 D179。
- 【风险/代价】边缘场景。[用户可见] 核心流程完整，鼠标/富控件体验不同。

### 裁决⑦：分享和刷新保留 Java 平台边界

- 【场景】JDK `HttpResponse` 没有 reason phrase；现有目录刷新层也没有完整 AbortSignal。
- 【决定】分享错误末位用 HTTP 数字状态，登记 D180；scoped 刷新关闭后使用代次丢弃迟到结果，登记 D181。
- 【风险/代价】边缘场景。[用户可见/内部] 错误文案末位略异；关闭后底层请求可能稍后结束，但不再污染会话或 UI。

### 窄差编号

| 编号 | 内容 |
|---|---|
| D176 | Radius 初始生成 baseline 为空；动态 models-store/`/v1/config` 语义完整 |
| D177 | 部分 TUI/终端设置只保证持久化，独立即时消费面不在本批 |
| D178 | SettingsList 不移植鼠标输入 |
| D179 | 登录对话框为文本级，不含完整 OSC 链接/复制按钮/IME 焦点结构 |
| D180 | Radius 分享错误的 statusText 回落为数字 status |
| D181 | scoped 刷新取消以代次隔离为界，底层请求不保证立即中止 |

---

## §12 预计闭环账面

### map/04

- C 域：旧汇总 34.5/38；逐行纠错后 35.0/38；本批完成后 **38.0/38＝100%**。
- coding-agent：446.0 + 0.5（纠错）+ 3.0（四行）＝**449.5/485 ≈ 92.7%**。
- C 域成为新的满分域；D85 销号不改权重。

### map/05

- S7 缺失→对齐：+1.0。
- S15 缺失→对齐：+1.0。
- S17 缺失→对齐：+2.0。
- S14 已对齐，不重复加分。
- tui/web：133.5 + 4.0＝**137.5/244 ≈ 56.4%**。

### map/02 与全仓

- ai#21 Radius provider、#44 Radius OAuth 按用户新裁决各以权重 2 纳入分母；两行对齐带 D176。
- ai 分母 219→223，得分 208.0→**212.0/223 ≈ 95.1%**。
- 全仓分母 1379→1383。
- 全仓得分 1164.0 + 3.5（map/04）+ 4.0（map/05）+ 4.0（ai）＝**1175.5/1383 ≈ 85.0%**。

闭环时必须重新逐行实算；若实现范围与本稿不同，不按预估值翻行。

### 文档回填

1. `docs/04`：B226 从 C 类移除并记录重开结果；D85 销号；新增批 31 段与 D176–D181；下一包号 docs/44。
2. `docs/05` 与 `docs/map/02/04/05`：按实算翻行、分母和汇总，特别修正 C 域旧汇总错位。
3. `docs/06`：§1 批 31 闭环读数、§2 新行、§3 序 24 划销；下一主线为批 32 evals。
4. 本稿追加 §13 闭环记录：实际改动、行为变化、测试数、verify 退出码和评审修真。

---

## §13 闭环记录

待实施与验收后填写；本稿当前仅为设计待审版本，不代表功能已经落地。
