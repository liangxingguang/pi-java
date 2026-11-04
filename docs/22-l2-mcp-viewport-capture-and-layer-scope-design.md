# 22 - L2 MCP 视口捕获收尾与跨层口径裁决（#85／D217）

> 【背景摘要】docs/21 收口时 MCP 账面 126.0/131＝96.18%，余 10 条存疑（均 w1）。用户裁决：只有 #85（`/mcp` 管理器视口捕获与翻页）属于 MCP 层，本包实施；其余 9 条是跨层、共享平台或品牌改名方言，转出 MCP 分母。MCP 账面改为 **122.0/122＝100%**（对齐 82／存疑 0／缺失 0；未加权 82/82）。
>
> 文档状态：**已实施并闭环（2026-11-04）**。
>
> 当前 pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`；取证只走 `git show 56cae7823:<path>`。pi-java 实施终态：`30abb76c`（main，2026-11-04）。

## 1. 用户裁决（2026-11-04）

1. **#85 属 MCP 层**，按 pi 完整对齐（`capturesViewportInput` 属性＋PageUp/PageDown 分页），不再转 L7。
2. **其余 9 条不属 MCP 层**：从 MCP 分母转出（map/07 权重记 0，行与证据保留），归属由各自层重测时确认：

   | 行 | 台账 | 归属 |
   |---|---|---|
   | #5 | B182 | L5 配置子系统（`piConfig.configDir`） |
   | #6 | B183 | 共享 JSON 解析方言（V8 与 Jackson 错误正文） |
   | #27 | B190 | 平台异常分类方言（`TypeError` 与 `IOException`） |
   | #30 | B191/B197 | 平台 URL 归一（WHATWG 与 `java.net.URI`） |
   | #38 | B193 | 共享 JSON 序列化方言（键序／显式 `null`） |
   | #40 | B198 | L1 ai 共享 `OAuthPage` 模板 |
   | #41 | B212 | JVM 平台方言（`isatty` 无对位） |
   | #43 | B192 | OAuth 身份托管基础设施（pi.dev 域名） |
   | #71 | B213 | 全仓品牌改名方言（`pi-java`／`.pi-java`） |

## 2. pi 事实

`packages/coding-agent/src/extensions/mcp/ui.ts:73-101`：

```ts
type InputHandler = Pick<Component, "handleInput" | "capturesViewportInput">;

export class McpManagerView implements McpUi, Component, Focusable {
	...
	get capturesViewportInput(): boolean {
		return this.inputHandler?.capturesViewportInput === true;
	}
```

`packages/tui/src/components/select-list.ts:41,148-164`：

```ts
	readonly capturesViewportInput: boolean = true;
	...
	const pageIndex = getSelectPageIndex(kb, keyData, this.selectedIndex, this.filteredItems.length, this.maxVisible);
	...
	else if (pageIndex !== undefined) {
		if (this.filteredItems.length === 0 || pageIndex === this.selectedIndex) return;
		this.selectedIndex = pageIndex;
		this.notifySelectionChange();
	}
```

`packages/tui/src/keybindings.ts:327-339`：

```ts
export function getSelectPageIndex(keybindings, data, selectedIndex, itemCount, pageSize) {
	let direction: number;
	if (keybindings.matches(data, "tui.select.pageUp")) direction = -1;
	else if (keybindings.matches(data, "tui.select.pageDown")) direction = 1;
	else return undefined;
	return Math.max(0, Math.min(itemCount - 1, selectedIndex + direction * pageSize));
}
```

测试锚点 `packages/coding-agent/test/mcp-manager-view.test.ts:44-64`：20 项菜单上按 PageDown 选中 `server-12`；`capturesViewportInput` 在菜单列表展示时为 `true`，切到状态屏后为 `false`。

## 3. 改动

1. `ExtensionComponent`（coding-agent）新增 `default boolean capturesViewportInput()`，默认 `false`（pi `Component.capturesViewportInput` 对位）。
2. `McpManagerView`：
   - 覆写 `capturesViewportInput()`：仅当前屏是非空 `MenuScreen` 时为 `true`（空菜单/状态/redirect/加载屏为 `false`，与 pi 的 inputHandler 选择一致）。
   - `MenuScreen.handleInput` 增加 `tui.select.pageUp/pageDown` 两臂；页大小＝`min(条目数, MAX_VISIBLE_ITEMS=12)`，索引按 `getSelectPageIndex` 钳制到 `0..n-1`。
3. `KeybindingsManager` 新增 `SELECT_PAGE_UP`／`SELECT_PAGE_DOWN`（`tui.select.pageUp/pageDown`），**只进 overlay 表**（裸 `pageup`/`pagedown`）；全局 `resolve` 不命中，主屏翻页仍由 `GlobalKeyRouter` 直管。
4. `ExtensionComponentOverlay`（tui）新增 `capturesViewportInput()` 透传访问器。**路由不改**：`GlobalKeyRouter` 对活动 overlay 已转发全部按键并返回 `HANDLED`，视口滚动分支不可达（模态语义即捕获）；访问器供未来路由在 overlay 之前分流时使用。
5. 文档回填：map/07 九行权重 0＋判定「转出（非本层）」；#85 对齐；docs/04／06／08 同步。

## 4. 测试（RED-first）

- RED：先加测试，编译失败于缺失的 `capturesViewportInput`／两个动作常量。
- 行为断言（`McpManagerViewTest`）：
  - 20 项菜单 PageDown ⇒ `server-12`；再 PageDown ⇒ `server-19`（钳制）；PageUp ⇒ `server-7`；Enter 返回 `s7`。
  - 5 项短列表 PageDown ⇒ `server-4`、PageUp ⇒ `server-0`（页大小取可见项数）。
  - `capturesViewportInput`：非空菜单 `true`；空菜单/状态屏/redirect 屏 `false`。
- `KeybindingsManagerTest`：翻页键只在 overlay 表（全局 `resolve` 为 `null`），`actionIds` 45→47。
- `ExtensionComponentTest`：普通组件默认 `false`。
- `ExtensionCustomOverlayTest`：overlay 现读透传组件的属性（false→true）。
- 变异探针：把页大小改为 0 ⇒ 分页测试红（已还原）。

## 5. 账面

| 域 | Σ权重 | Σ(w×c) | 加权 | 未加权 |
|---|---:|---:|---:|---:|
| A 配置（12 计分＋2 转出） | 18 | 18.0 | 100% | 12/12 |
| B 协议/客户端（7） | 13 | 13.0 | 100% | 7/7 |
| C 传输（9 计分＋2 转出） | 13 | 13.0 | 100% | 9/9 |
| D OAuth（11 计分＋4 转出） | 17 | 17.0 | 100% | 11/11 |
| E 工具/资源/结果（19） | 27 | 27.0 | 100% | 19/19 |
| F CLI（7 计分＋1 转出） | 11 | 11.0 | 100% | 7/7 |
| G 接线（17） | 23 | 23.0 | 100% | 17/17 |
| **合计（82 计分＋9 转出）** | **122** | **122.0** | **100%** | **82/82** |

复算：map/07 只计权重 > 0 的行（转出行权重 0）。历史读数：旧基线 93.8%（121.0/129）、docs/21 收口 96.18%（126.0/131）。

## 6. 编号与后续

- docs/21 的 W11／D217 由本包接替（#85 实施，D217 销号）。
- 下一新文档号为 **docs/23**（L3 agent-core 首个设计）。
