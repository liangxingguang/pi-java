# coding-agent 批 9 设计稿：树选择器本体（D37 收编＝map/05 S12＋S13）

【背景摘要】本稿是 coding-agent 对齐批 9 的设计。收编批 8（docs/18）留下的窄差
**D37**——`/tree` 的选择器本体：java 现列 **lane 名＋leafId**（112 行平表），pi 是
**entry 树选择器**（`tree-selector.ts` 1,435 行：树形缩进/连接符/gutter、折叠、五种过滤
模式、搜索、标签编辑、复制、水平视口）。pi 侧组件内聚（TreeList＋容器层一次到位），
故 map/05 的 **S13**（折叠/标签/过滤/搜索/复制，权重 1，缺失）随本批一并落地；
map/04 #3 `/tree`（权重 1，存疑）的证据列同步翻转。

术语（首现释义）：

- **D37**：批 8 登记的窄差编号，见 `docs/04-open-items-register.md` §4 批 8 条目。
- **S12/S13**：`docs/map/05-tui-web.md` 的屏幕对齐行号。
- **entry 树**：会话的全部条目按 `parentId` 链成的树（含所有分支）；pi 的「分支」是
  树上的叶指针移动，java 的 lane（车道）概念在 pi 不存在。
- **SessionTreeNode**：pi `session-manager.ts:200` 的树节点——`{entry, children,
  label?, labelTimestamp?}`，label 是解析后的当前值（label 条目是树内真实条目，
  最新一条赢）。
- **活动路径（active path）**：根到当前叶的 entry id 集；渲染时在行首打 `•`，
  含活动叶的根/子分支排序优先。
- **gutter / connector**：树形线字符。connector＝节点自身的 `├─/└─`；gutter＝
  祖先分支点传下来的竖线 `│`（指示「该层祖先还有后续兄弟」）。
- **fold（折叠）**：在选择器内临时隐藏某节点的全部可见后代；纯视图态，过滤/搜索
  变化即清空。
- **FilterMode**：`default | no-tools | user-only | labeled-only | all` 五种显示过滤。
- **FactLabel / FactName**：java 的会话事实变体（label/会话名），wire 上是 pi 原生
  `label`/`session_info` 行，但 java 侧解析为**事实**而非 Entry 树内条目。
- **窄差**：java 与 pi 无法完全对齐、登记在册的差异。

---

## §0 基线与预期翻动

| 口径 | 批 8 后基线 | 本批预期 | 依据 |
|---|---|---|---|
| D 域（map/04 §D，Σ权重 83） | 73.0（88.0%） | 73.0（88.0%） | 本批不动 D 域行 |
| 模块（Σ 487，去重） | 283.5（58.2%） | 286.0（58.7%） | map/05 S12 存疑→对齐 +1.0（权重 2）、S13 缺失→对齐 +1.0（权重 1）、map/04 #3 `/tree` 存疑→对齐 +0.5 |
| 全仓（Σ 1381） | 955.5（69.2%） | 958.0（69.4%） | 同上 +2.5 |

新登工单：**B254**（`/fork` 选择器本体＝pi user-message 选择器，见 §1.7 新发现）。
新登窄差：D43–D46（§5）。既有窄差 **D37、D40 销号**（D40「重开恢复选中＝lane 行
定位」随 entry 树本体落地自动消解——恢复粒度变为 pi 的 entry 节点）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 `SessionTreeNode` 与 `getTree()`（`core/session-manager.ts` 逐字）

`:199-207`：

```ts
/** Tree node for getTree() - defensive copy of session structure */
export interface SessionTreeNode {
	entry: SessionEntry;
	children: SessionTreeNode[];
	/** Resolved label for this entry, if any */
	label?: string;
	/** Timestamp of the latest label change for this entry, if any */
	labelTimestamp?: string;
}
```

`:134-139` LabelEntry（**label 是树内真实条目**，parentId＝当时叶）：

```ts
export interface LabelEntry extends SessionEntryBase {
	type: "label";
	targetId: string;
	label: string | undefined;   // undefined = 清除
}
```

`:142-146` SessionInfoEntry（`type:"session_info"; name?: string`）同理是树内条目。

label 解析态（`:996-997`＋`_buildIndex` `:1103-1122`）：按文件序扫 label 条目，
`label` 真值 ⇒ `labelsById.set(targetId,label)`＋`labelTimestampsById.set(targetId,
entry.timestamp)`，否则两表同删（**最新一条赢**）。

`getTree()` `:1529-1567` 逐字：

```ts
getTree(): SessionTreeNode[] {
	const entries = this.getEntries();
	const nodeMap = new Map<string, SessionTreeNode>();
	const roots: SessionTreeNode[] = [];

	// Create nodes with resolved labels
	for (const entry of entries) {
		const label = this.labelsById.get(entry.id);
		const labelTimestamp = this.labelTimestampsById.get(entry.id);
		nodeMap.set(entry.id, { entry, children: [], label, labelTimestamp });
	}

	// Build tree
	for (const entry of entries) {
		const node = nodeMap.get(entry.id)!;
		if (entry.parentId === null || entry.parentId === entry.id) {
			roots.push(node);
		} else {
			const parent = nodeMap.get(entry.parentId);
			if (parent) {
				parent.children.push(node);
			} else {
				roots.push(node);   // Orphan - treat as root
			}
		}
	}

	// Sort children by timestamp (oldest first, newest at bottom)
	// Use iterative approach to avoid stack overflow on deep trees
	const stack: SessionTreeNode[] = [...roots];
	while (stack.length > 0) {
		const node = stack.pop()!;
		node.children.sort((a, b) => new Date(a.entry.timestamp).getTime() - new Date(b.entry.timestamp).getTime());
		stack.push(...node.children);
	}

	return roots;
}
```

`appendLabelChange` `:1441-1462`（选择器标签编辑的持久化点）：label 条目作为
**当前叶的子条目**追加并立即 `_persist`；内存两表同步更新/删除。

### 1.2 TreeList：平铺与缩进规则（`tree-selector.ts:195-316`）

状态字段 `:111-126`：`flatNodes / filteredNodes / selectedIndex / currentLeafId /
maxVisibleLines / filterMode / searchQuery / toolCallMap / multipleRoots /
showLabelTimestamps / activePathIds / visibleParentMap / visibleChildrenMap /
lastSelectedId / foldedNodes`。

`FlatNode` `:29-44`＝`{node, indent, showConnector, isLast, gutters, isVirtualRootChild}`；
`GutterInfo` `:23-26`＝`{position, show}`。

`flattenTree` `:195-316` 要点（迭代栈，避免深树爆栈）：

1. **containsActive 后序计算** `:214-233`：先 pre-order 收集再逆序处理，标记每棵
   子树是否含当前叶。
2. **根排序** `:238-241`：含活动叶的根排最前；多根 ⇒ 全部当「虚拟根」的子节点
   （indent 从 1 起、`isVirtualRootChild=true`）。
3. **缩进规则** `:286-296`（StackItem＝`[node, indent, justBranched, showConnector,
   isLast, gutters, isVirtualRootChild]`）：
   - 父节点多子 ⇒ `childIndent = indent + 1`；
   - 否则若 `justBranched && indent > 0`（分支点后的第一代）⇒ `+1`（视觉分组）；
   - 否则单链 ⇒ 持平。
4. **子节点排序** `:270-282`：含活动叶的子分支排前，其余保持原序。
5. **gutter 传递** `:298-308`：仅当 connector 实际显示（`showConnector &&
   !isVirtualRootChild`）才追加 `{position: max(0, displayIndent-1), show: !isLast}`；
   多根时 `displayIndent = max(0, indent-1)`。
6. **toolCallMap** `:248-259`：平铺时顺手收集 assistant 消息里的 toolCall 块
   （id→{name,arguments}），供 toolResult 行反查。

构造尾 `:140-146`：`applyFilter()` 后 `selectedIndex =
findNearestVisibleIndex(initialSelectedId ?? currentLeafId)`。

`findNearestVisibleIndex` `:152-172`：从目标 id 沿 parentId 上爬，找第一个在
filteredNodes 里的祖先；全不可见 ⇒ 回退最后一行。

`buildActivePath` `:177-193`：从 currentLeafId 沿 parentId 到根，收集 id 集。

### 1.3 过滤、搜索与折叠（`:320-560`）

`applyFilter` `:320-429` 谓词（逐字要点）：

```ts
const searchTokens = this.searchQuery.toLowerCase().split(/\s+/).filter(Boolean);
this.filteredNodes = this.flatNodes.filter((flatNode) => {
	const entry = flatNode.node.entry;
	if (entry.type === "usage") return false;                       // usage 恒隐
	const isCurrentLeaf = entry.id === this.currentLeafId;
	// 纯工具调用 assistant（无文本、非 error/aborted）默认隐；当前叶恒显
	if (entry.type === "message" && entry.message.role === "assistant" && !isCurrentLeaf) {
		const hasText = this.hasTextContent(msg.content);
		const isErrorOrAborted = msg.stopReason && msg.stopReason !== "stop" && msg.stopReason !== "toolUse";
		if (!hasText && !isErrorOrAborted) return false;
	}
	const isSettingsEntry =
		entry.type === "label" || entry.type === "context_edit" || entry.type === "custom" ||
		entry.type === "model_change" || entry.type === "thinking_level_change" ||
		entry.type === "session_info";
	switch (this.filterMode) {
		case "user-only":    passesFilter = entry.type === "message" && entry.message.role === "user"; break;
		case "no-tools":     passesFilter = !isSettingsEntry && !(entry.type === "message" && entry.message.role === "toolResult"); break;
		case "labeled-only": passesFilter = flatNode.node.label !== undefined; break;
		case "all":          passesFilter = true; break;
		default:             passesFilter = !isSettingsEntry; break;
	}
	if (!passesFilter) return false;
	if (searchTokens.length > 0) {
		const nodeText = this.getSearchableText(flatNode.node).toLowerCase();
		return searchTokens.every((token) => nodeText.includes(token));  // 空白分词 AND
	}
	return true;
});
// 折叠：foldedNodes 的全体后代（沿 parentId 传染 skipSet）滤除 `:407-415`
// 可见结构重算 recalculateVisualStructure() `:417`
// 选中保持：lastSelectedId 优先找自身，否则最近可见祖先 `:420-428`
```

`recalculateVisualStructure` `:433-560`：过滤会藏掉中间条目，**后代挂到最近可见
祖先**下重算缩进/连接符/gutter（与 flattenTree 同套缩进规则，但 children/parent
用可见映射 `visibleParentMap`/`visibleChildrenMap`；`multipleRoots` 按可见根数重判）。
这两张映射同时服务 `isFoldable`/`findBranchSegmentStart`。

`getSearchableText` `:566-612`：label＋按 entry 类型拼搜索语料（message→role＋
文本＋bash 命令；custom_message→customType＋内容；branch_summary→"branch
summary"＋摘要；model_change→"model"＋id；label→"label"＋值；等），空格 join。

### 1.4 渲染（`:617-1002`）

行构成（`render` `:660-776`，每行）：`cursor("› "/"") + prefix(树形线) +
foldMarker + pathMarker("• ") + label("[x] ") + labelTimestamp + content`；
选中行 gutter/body 套 `selectedBg` 底色；`anchorCol`＝prefixPart 可视宽（水平视口用）。
尾部状态行 `  (i+1/N)[no-tools][user][labeled][all][+label time]`。

prefix 逐字符摆放 `:687-735`：每级 3 列；gutter 层第 0 列 `│/ `；connector 层
`├/└`＋第 1 列放**折叠指示**（已折 `⊞`、可折 `⊟`、否则 `─`）；无 connector 的根行
折叠标记独立为 `⊞ `（accent 色）。

`renderHorizontalViewport` `:62-103`（gutter 恒可见，body 仅在选中行锚点不可见时左移）：

```ts
const viewportWidth = Math.max(0, width - TREE_GUTTER_WIDTH);      // TREE_GUTTER_WIDTH = 2
const maxHorizontalScroll = Math.max(0, maxBodyWidth - viewportWidth);
let horizontalScroll = 0;
if (selectedRow && maxHorizontalScroll > 0) {
	const minVisibleAnchorContentWidth = Math.min(20, Math.max(4, Math.floor(viewportWidth / 3)));
	if (selectedRow.anchorCol > viewportWidth - minVisibleAnchorContentWidth) {
		const anchorContextWidth = Math.min(12, Math.max(2, Math.floor(viewportWidth / 4)));
		horizontalScroll = Math.min(maxHorizontalScroll, selectedRow.anchorCol - anchorContextWidth);
	}
}
// 滚动时 body = sliceByColumn(body, scroll, viewportWidth, /*strict*/ true) + "\x1b[0m"
// 每行最终 truncateToWidth(line, width, "")（无省略号）
```

`getEntryDisplayText` `:779-872`（显示文案规格，全类型）：

| entry | 文案 |
|---|---|
| message user | `user: `（accent）＋归一化文本（`\n\t`→空格、trim、200 截） |
| message assistant | 有文本 `assistant: `（success）＋文本；无文本且 `stopReason==="aborted"` ⇒ `(aborted)`（muted）；有 `errorMessage` ⇒ 80 截（error）；否则 `(no content)`（muted） |
| message toolResult | toolCallMap 命中 ⇒ `formatToolCall(name,args)`（muted）；否则 `[toolName ?? "tool"]` |
| message bashExecution | `[bash]: command`（dim，归一化） |
| 其他 role | `[role]`（dim） |
| custom_message | `[customType]: 文本`（customMessageLabel＋归一化） |
| compaction | `[compaction: {round(tokensBefore/1000)}k tokens]`（borderAccent） |
| branch_summary | `[branch summary]: 摘要`（warning） |
| model_change / thinking_level_change / custom | `[model: id]` / `[thinking: level]` / `[custom: type]`（dim） |
| context_edit | `[context omit\|replace: targetId]`（dim） |
| label | `[label: 值 ?? "(cleared)"]`（dim） |
| session_info | `[title: name]` 或 `[title: empty]`（empty 斜体，dim） |

选中行整体 bold。`formatToolCall` `:960-1002`：read/write/edit ⇒ `[read:
path[:start[-end]]]`（home 缩 `~`）；bash ⇒ `[bash: 命令50截...]`；grep/find/ls ⇒
`[grep: /pat/ in path]` 等；自定义工具 ⇒ `[name: JSON40截...]`。

`formatLabelTimestamp` `:874-894`：当天 `HH:MM`；今年 `M/D HH:MM`；跨年
`YY/M/D HH:MM`。

`getEntryCopyText` `:921-948`：message→全文（assistant 无文本回退 errorMessage；
bashExecution→command）；custom_message→全文；compaction/branch_summary→summary；
trim 后空 ⇒ undefined。

### 1.5 输入处理（`handleInput :1004-1130`＋键位表）

分派序（逐字要点）：上/下（**环绕**）；`app.tree.foldOrUp`（可折且未折 ⇒ 折叠＋
applyFilter，否则 `findBranchSegmentStart("up")`）；`app.tree.unfoldOrDown`（已折 ⇒
展开，否则段首下跳）；左/pageUp、右/pageDown（±maxVisibleLines）；confirm ⇒
onSelect(entryId)；`app.message.copy` ⇒ copySelected；cancel（**Esc 与 ctrl+c**）⇒
有搜索串先清搜索＋清折叠，否则 onCancel；五个过滤键（各自与 default 互切）＋两个
cycle 键（五模式环）；backspace 删搜索尾字符；`app.tree.editLabel` ⇒
onLabelEdit(entryId, 当前 label)；`app.tree.toggleLabelTimestamp` 开关；**其余可打印
字符**（无控制字符）⇒ 追加搜索串＋清折叠＋applyFilter。**任何过滤/搜索变化都清
foldedNodes**。

`isFoldable` `:1137-1145`：有可见子节点，且（无可见父＝根）或（可见父多子＝段首）。
`findBranchSegmentStart` `:1153-1185`：down＝沿首子直下，遇分支点停在其首子；
up＝沿可见父链上爬，停在「自己是多子之一且下标小于当前」的段首。

键位默认（`core/keybindings.ts:83-167`、`tui/src/keybindings.ts:72-225`）：

| action | 默认键 |
|---|---|
| app.tree.foldOrUp | ctrl+left／alt+left |
| app.tree.unfoldOrDown | ctrl+right／alt+right |
| app.tree.editLabel | shift+l |
| app.tree.toggleLabelTimestamp | shift+t |
| app.tree.filter.default / noTools / userOnly / labeledOnly / all | ctrl+d／ctrl+t／ctrl+u／ctrl+l／ctrl+a |
| app.tree.filter.cycleForward / cycleBackward | ctrl+o／shift+ctrl+o |
| app.message.copy | ctrl+x |
| tui.select.up/down/confirm | up／down／enter |
| tui.select.cancel | escape／ctrl+c |
| tui.select.pageUp/pageDown | pageUp／pageDown |
| tui.editor.cursorLeft/Right（此处＝翻页） | left／right |
| tui.editor.deleteCharBackward | backspace |

### 1.6 容器层（`:1188-1435`）

`SearchLine`（`Type to search:`＋accent 查询串）；`TreeHelp`（语义帮助行，
`· ` 分隔、按宽换行：move／page／branch／copy／label／label time／filters／cycle，
键名经 `formatKeyText` 美化——`↑↓←→/pgup/pgdn`，同前缀合并 `ctrl+d/t/u/…`）；
`LabelInput`（替换树区显示：`Label (empty to remove):`＋单行输入＋
`enter save  esc cancel` 提示；submit⇒`updateNodeLabel` 本地改＋onLabelChange 回调）。

`TreeSelectorComponent extends Container` `:1336-1435`：构造签名
`(tree, currentLeafId, terminalHeight, onSelect, onCancel, onLabelChange?,
initialSelectedId?, initialFilterMode?)`；`maxVisibleLines = Math.max(5,
Math.floor(terminalHeight/2))`；布局＝Spacer/DynamicBorder/粗体标题 `  Session
Tree`/TreeHelp/SearchLine/DynamicBorder/Spacer/treeContainer/labelInputContainer/
Spacer/DynamicBorder；label 编辑时 treeContainer 与 labelInputContainer 互换内容；
**空树 ⇒ `setTimeout(onCancel, 100)`**；focused 传播给 LabelInput（IME 光标）。

### 1.7 宿主流（`interactive-mode.ts:5559-5707`）＋`/fork` 新发现

`showTreeSelector(initialSelectedId?)`：**唯一**构造点；`/tree`、`app.session.tree`、
double-escape（setting＝tree）三路汇入。参数来源：`tree =
sessionManager.getTree()`、`realLeafId = getLeafId()`、`terminalHeight =
ui.terminal.rows`、**`initialFilterMode = settingsManager.getTreeFilterMode()`**
（全局 settings.json；选择器内的切换**不回写**，仅 /settings 页写）；空树 ⇒
`showStatus("No entries in session")` 不打开。`onLabelChange` ⇒
`sessionManager.appendLabelChange(entryId, label)`（立即持久化）。`onCopy`：
text 空 ⇒ `showError("Selected entry has no text to copy")`；否则 `copyToClipboard`
＋`showStatus("Copied selected message to clipboard")`，异常 ⇒ showError。
剪贴板机制（`utils/clipboard.ts`）：native binding → pbcopy/clip/wl-copy/xclip/xsel
平台命令 → WSL/OSC 52 降级链。

onSelect 导航流＝批 8（docs/18 §1.4）已对齐部分，本稿不重复。

**新发现（本批取证撞出）**：pi 的 **`/fork` 不用树选择器**——走
`UserMessageSelectorComponent`（`user-message-selector.ts`，与 S20 排队消息选择器
同文件），列**用户消息**（初始选中最后一条），选中 ⇒ `runtimeHost.fork(entryId)`
＋编辑器回填选中文本＋"Forked to new session"（`interactive-mode.ts:5500-5538`；
`/clone` 无选择器 :5540-5557）。java 的 `/fork` 现为 lane 平表——**本体差异另立
B254**，本批不动（见裁决⑥）。

### 1.8 pi 测试锚点（`test/tree-selector.test.ts` 739 行，行为规格）

纯组件测试（无 SessionManager mock；`selector.handleInput(原始键串)`＋
`getTreeList().getSelectedNode()`／render 输出断言）：

1. 当前叶是被默认过滤的 model_change（有兄弟分支）⇒ 选中落在**最近可见祖先**
   user-2 而非兄弟（`:130`）。
2. context_edit 默认隐、`initialFilterMode:"all"` 时渲染含 `[context omit: asst-1]`
   （`:159`）。
3. thinking_level_change 叶 ⇒ 同样的祖先回退（`:196`）。
4. ctrl+u 切 user-only：从 asst-2 选中父 user-2（`:227`）；再 ctrl+d 回 default
   保持 user-2（`:256`）。
5. 帮助行宽 30 不截断、含 branch/copy/filters/cycle/label time（`:290`）。
6. ctrl+x 复制选中消息**全文**（`:314`）。
7. shift+t 切 label 时间戳：渲染出 `3/28 14:32` 与状态标 `[+label time]`（`:336`）。
8. 空 labeled 过滤往返保持选中（`:366`）；多次空过滤切换保持（`:400`）。
9. ctrl+left 折段首 → ctrl+right 展开 → 再 ctrl+right 段跳到叶（`:476`）；
   alt+left/right 同效别名（`:512`）。
10. 折根隐整棵子树、嵌套折叠展开后保留（`:536`）；非活动分支折叠导航（`:572`）；
    多根折叠导航（`:607`）；中间条目被过滤时折叠仍隐后代（`:648`）。
11. 搜索清折叠（`:676`）；过滤模式切换清折叠（`:709`）。

---

## §2 java 侧现状与缺口（实读）

### 2.1 数据面：Entry ADT 无 Label/SessionInfo；label 是事实而非条目

`Entry`（`pi-java-agent-core …/entry/Entry.java:46-341`）sealed 九变体：Message／
BashExecution／ModelChange／ThinkingLevelChange／Compaction／BranchSummary／Usage／
Custom／CustomMessage／ContextEdit——**无 Label、无 SessionInfo**。

label 在 java 走**事实**通道，且 wire 与 pi 原生互通（既有资产，不动）：

- `SessionMutation.FactLabel(seq, parentId, timestamp, targetId, label)`
  （`SessionMutation.java:54`）；`SessionState` 解析为 `Map<String,String> labels`
  （`SessionState.java:38`，`getLabel` `:226`，`applyFactLabel` `:374-384`，
  分支复制时按路径重发 `:286-288`）。
- wire 编解码 pi 原生：`PiV3Wire.java:216-223` 写 `type:"label"` 行
  （syntheticId），`:239-241` 读回 FactLabel；`JsonlCodec.java:158/:340-341` 同。
- 会话名同理：`FactName` ↔ `type:"session_info"`。
- **缺口①**：`SessionState.labels` **无时间戳**——pi 的 labelTimestamp（`[+label
  time]` 开关的数据源）无处可查；且分支复制重发 FactLabel 用 `tipTime`（当前叶
  时间）而非 pi 的**原 label 时间戳**（pi `createBranchedSession:1694-1712` 保留
  原值）。
- 语义差：pi 的 label/session_info 是**树内条目**（出现在 `all` 过滤模式的行里、
  参与父子链），java 是旁表——见裁决①。

### 2.2 entry 树：无公共构建器，两个私有复制品都有偏差

- `RpcDispatcher.buildTree()`（`pi-java-coding-agent …/rpc/RpcDispatcher.java:643-669`）：
  数据源自 `harnessTranscript()`（`:459-461`，**harness 单车道转录**，非持久会话
  全分支）；`treeNode` 里 **label 硬编码 null**（`:663-669`）；无根时回退首条目
  （pi 无此回退）。
- `WebDispatcher.buildTree()`（`pi-java-web …/WebDispatcher.java:401-426`）：同形复制。
- `AgentSession.accumulatedEntries()`（`AgentSession.java:874-887`）已是**持久层全
  分支 OLDEST_FIRST**（回退 harness 转录）——pi `getEntries()` 的正确对位，但
  无树组装、无 label 解析。
- pi 侧 RPC `get_tree` 的数据源就是 `sessionManager.getTree()`——java 这两处复制
  是**真实偏差**（单车道＋label 丢失），本批顺带修真（裁决③）。

### 2.3 `AgentSession` 无 label 公开面

`session` 字段私有（`AgentSession.java:97`）；label 读写仅在 navigateTree 内部
（`:1206/:1214`）。`Session.setLabel/getLabel`（`Session.java:170-176`）、
`SessionTree.getLabel/setLabel`（`SessionTree.java:32-35`）在 agent-core 已公开，
两个 storage（Jsonl `:258-266`／Memory `:161-169`／Sqlite `:366`）都已实现——
**只欠 `AgentSession` 的转发与时间戳维度**。

### 2.4 TUI 组件层现状

- `SelectList`（162 行）：平表＋fuzzy 过滤，无树缩进/折叠/多键绑定——**不可改造
  复用**（pi 的搜索是空白分词 AND 子串，非 fuzzy；键分派是独立查表）。
- `ScreenOverlay` 合同（`ScreenOverlay.java:14-27`）：`onKeyEvent/isDone/apply/
  render`，overlay 在 `PiTuiApp:310-321` **先拿键**（故 tree 键与全局 ctrl+t/o/l/d
  无冲突），`isDone` 后先清位再 apply（批 8 已修）。
- overlay 渲染＝聊天下方**列堆叠**（`PiTuiApp:210-218`），无 DynamicBorder/模态；
  边框靠组件级 `.rounded()`。
- `EditorComponent`：可复用的输入组件（`CustomInstructionsScreen` 是既有包装
  样板）——LabelInput 的落点。
- **无语义色令牌**：`pi-dark.tcss`（38 行）只有类样式；组件用 ad-hoc
  `Color.CYAN`/`.dim()`/`.red()`/`.gray()`。pi 的 accent/muted/warning/success/
  error/borderAccent/customMessageLabel/dim/selectedBg 需要落一组 java 常量。
- **无剪贴板**：仅 `MiscCommands.java:107-114` 用 AWT（headless 回退打印）。
- 终端行高：TamboUI 有 `ResizeEvent(width,height)`，pi-java TUI **未消费**；
  `PiTuiApp` 无 rows 来源。

### 2.5 settings 已通（本批零改造）

`Settings.java:61 treeFilterMode`＋`SettingsAccessors.getTreeFilterMode():196`＋
`SettingsScreen.java:53`（/settings 页已可循环取值）——pi 的「初值读全局 settings、
选择器内切换不回写」语义**直接可用**，只欠选择器侧消费。非法值回退 default 在
选择器解析时做（pi settings-manager 读时校验的对位）。

### 2.6 `KeybindingsManager` 单键映射

`bindings: Map<String,KeyStroke>`（一动作一键；`KeybindingsManager.java:54`），
无用户覆写（`keybindings.json` 未接线，既有约束）。pi 的 foldOrUp/unfoldOrDown
各有两键（ctrl／alt），需扩为多键；`resolve(stroke)` 签名不变。

---

## §3 设计

总形：T1 数据面（agent-core＋coding-agent）→ T2 纯状态机 `TreeListModel`（tui，
零 TamboUI 依赖，pi 算法逐字对位）→ T3 渲染层 `TreeListRenderer`（span 行＋水平
视口）→ T4 overlay 容器 `TreeSelectorOverlay` → T5 接线（flow/PiTuiApp/键位/剪贴板）。
新文件全部落 `pi-java-tui/src/main/java/com/pijava/tui/component/tree/`（T2–T4）；
行数预算均 ≤500。

### T1：数据面——`SessionTreeNode`＋label 时间戳＋公开面

#### 3.1 `SessionTreeNode`（coding-agent，新文件 ~45 行）

`com.pijava.coding.agent.core.SessionTreeNode`：

```java
/** pi {@code SessionTreeNode}（session-manager.ts:200-207）：entry 树节点＋解析后标签。 */
public record SessionTreeNode(
    Entry entry,
    List<SessionTreeNode> children,   // 可变构建后冻结为 List.copyOf
    String label,                     // null = 无标签
    Instant labelTimestamp            // null = 无标签；pi 是 ISO 串，java 原生 Instant
) {}
```

#### 3.2 `SessionState` label 时间戳（agent-core，~20 行改动）

```java
private final Map<String, Instant> labelTimestamps = new LinkedHashMap<>();   // :38 旁

private void applyFactLabel(SessionMutation.FactLabel m, long seq) {          // :374 改
    if (m.label() == null) {
        labels.remove(m.targetId());
        labelTimestamps.remove(m.targetId());
    } else {
        labels.put(m.targetId(), m.label());
        labelTimestamps.put(m.targetId(), m.timestamp());   // pi _buildIndex 同语义
    }
    log.add(...);   // 不变
}

/** pi {@code labelTimestampsById} 对位：最近一次 label 变更的时间戳。 */
public Instant getLabelTimestamp(String id) { return labelTimestamps.get(id); }
```

**顺带修真**：分支复制重发（`:286-288`）的 FactLabel 时间戳由 `tipTime` 改为
`labelTimestamps.get(entry.id())`（原值兜底 tipTime）——pi
`createBranchedSession:1694-1712` 保留原 label 时间戳的对位。

#### 3.3 `SessionTree`／`Session`／`LaneView`／storage 转发（agent-core）

`SessionTree` 加 `default Instant getLabelTimestamp(String id) { return null; }`？
——不。三 storage 都已实现 get/setLabel，加抽象方法 `Instant
getLabelTimestamp(String id)` 并逐一实现（Jsonl/Memory 走 SessionState；Sqlite
查其 facts 表同名列，无列则按既有 label 通道补——**实施时先实读 Sqlite schema，
若 facts 无 timestamp 列则 Sqlite 侧登记窄差 D47**）。`Session`／`LaneView` 同名转发。

#### 3.4 `AgentSession` 三面（coding-agent，`AgentSession.java`，~70 行）

```java
/** pi {@code sessionManager.getTree()}（session-manager.ts:1529-1567）对位。 */
public List<SessionTreeNode> getTree() {
    var entries = accumulatedEntries();                       // 持久层全分支（:874）
    var byId = new LinkedHashMap<String, SessionTreeNode[]>();
    // 节点先建（label 解析），再按 parentId 链接；parentId==null 或自指或孤儿 ⇒ 根
    // children 按 entry.timestamp() 升序（迭代栈，不用递归——pi 防爆栈同纪律）
    ...
}

/** pi {@code sessionManager.getLabel()} 对位。 */
public String getLabel(String entryId) { return session.getLabel(entryId); }

/**
 * pi {@code appendLabelChange}（:1441-1462）的行为对位：label 变更持久化。
 * java 落 FactLabel（wire 即 pi 原生 label 行），label==null ⇒ 清除。
 */
public void setLabel(String entryId, String label) {
    session.view(laneName()).setLabel(entryId, label);   // SessionTree.setLabel 既有
}
```

构建注意（pi §1.1 逐字）：① 两轮——先全量建节点再链接；② 根判定
`parentId==null || parentId.equals(id) || 父缺失`；③ 子表按 timestamp 升序；
④ children 用 `List.copyOf` 冻结。

#### 3.5 `RpcDispatcher`／`WebDispatcher` `get_tree` 修真（裁决③）

`RpcDispatcher.buildTree()`（`:643-669`）删除，`case GetTree` 改：

```java
case RpcCommand.GetTree t -> out.write(RpcResponse.ok(t.id(), "get_tree",
    new RpcPayloads.RpcTreeData(toRpcTree(session.getTree()), session.getTreeLeafId())));
```

`toRpcTree`＝`SessionTreeNode → RpcSessionTreeNode` 浅映射（label 从此有值）。
`WebDispatcher.buildTree`（`:401-426`）同样改走 `session.getTree()`（ObjectNode
映射保留本地）。leafId 取 harness 车道叶（现状语义不变，pi RPC 的 leafId 即
sessionManager 叶——持久层与车道同叶，无需新增面）。

### T2：`TreeListModel`（tui，新文件 ~430 行，纯 java 无 UI 依赖）

pi `TreeList` 的状态机部分逐字对位（§1.2/§1.3/§1.5 的纯逻辑）。

```java
package com.pijava.tui.component.tree;

/** pi tree-selector.ts FilterMode（:95）。纯常量闭集 ⇒ enum。 */
public enum FilterMode { DEFAULT, NO_TOOLS, USER_ONLY, LABELED_ONLY, ALL }

/** pi FlatNode（:29-44）＋ GutterInfo（:23-26）。 */
record GutterInfo(int position, boolean show) {}
final class FlatNode {
    final SessionTreeNode node;
    int indent; boolean showConnector; boolean isLast;
    List<GutterInfo> gutters; boolean isVirtualRootChild;
}

public final class TreeListModel {
    // pi :111-126 同名状态（flatNodes/filteredNodes/selectedIndex/currentLeafId/
    // maxVisibleLines/filterMode/searchQuery/toolCallMap/multipleRoots/
    // showLabelTimestamps/activePathIds/visibleParentMap/visibleChildrenMap/
    // lastSelectedId/foldedNodes）
    public TreeListModel(List<SessionTreeNode> tree, String currentLeafId,
                         String initialSelectedId, FilterMode initialFilterMode) { ... }
    // flattenTree / buildActivePath / applyFilter / recalculateVisualStructure
    // findNearestVisibleIndex / isFoldable / findBranchSegmentStart
    // ── 键语义方法（overlay 分派调用，pi handleInput 各臂一一对应）：
    public void moveUp();                 // 环绕
    public void moveDown();
    public void pageUp();                 // ±maxVisibleLines（maxVisibleLines 由渲染层告知）
    public void pageDown();
    public void foldOrUp();               // §1.5 臂语义
    public void unfoldOrDown();
    public void cycleFilter(boolean forward);
    public void setFilter(FilterMode m);  // 与 DEFAULT 互切语义在 overlay（pi 逐臂）
    public void backspaceSearch();
    public void appendSearch(String s);
    /** cancel 第一级：清搜索＋清折叠；返回 false ⇒ 调用方走 onCancel。 */
    public boolean clearSearchIfAny();
    public String selectedEntryId();
    public SessionTreeNode selectedNode();
    public void updateNodeLabel(String entryId, String label, Instant timestamp);
    // 只读探针（渲染/测试）：filteredNodes()、selectedIndex()、searchQuery()、
    // filterMode()、showLabelTimestamps()、toggleLabelTimestamps()、folded()…
}
```

要点对位（实施红线）：

- **环绕导航**（pi `:1006-1010`）：index 0 上移 ⇒ 末行；末行下移 ⇒ 0。
- 过滤谓词按 §1.3 逐字（usage 恒隐；纯工具 assistant 隐但当前叶恒显；settings
  六型；五模式；搜索空白分词 AND 小写）。java 无 label/session_info **条目**，
  `isSettingsEntry` 剩四型（context_edit/custom/model_change/thinking_level_change）
  ——裁决①的登记点。
- `Entry` 分派用 switch 模式匹配（ADT）；「assistant 纯工具调用」判定读
  `Entry.Message` 的 role/content/stopReason（`hasTextContent`＋`isErrorOrAborted`
  两助手脚本逐字）；bashExecution 在 java 是独立变体不是 role——文案/搜索语料按
  pi 的 role 表对位到变体。
- toolCallMap：平铺时从 `Entry.Message`（assistant）content 块收集 toolCall。
- 折叠传染 skipSet、可见结构重算、gutter 传递、多根虚拟根——§1.2/§1.3 逐字；
  排序稳定性：java `List.sort` 稳定，pi `Array.sort` 自 ES2019 稳定——timestamp
  相等时两側同序（seq 序），一致。
- `applyFilter` 首尾两处 lastSelectedId 维护（`:321-324`／`:425-428`）易漏，照抄。

### T3：`TreeListRenderer`（tui，新文件 ~340 行）

pi §1.4 的纯文本层：**输入 FlatNode 行＋宽度，输出 span 行**。span＝
`(String text, Style style)`（TamboUI `Style`），不接 ANSI 串（裁决⑦）。

```java
public final class TreeListRenderer {
    public record Span(String text, Style style) {}
    record Row(List<Span> gutter, List<Span> body, int anchorCol, int bodyWidth, boolean selected) {}

    /** pi render() :660-776 行构建（prefix 逐字符/gutter/connector/折叠符/•/label/时间戳/文案）。 */
    public List<Row> buildRows(TreeListModel m, int startIndex, int endIndex) { ... }
    /** pi renderHorizontalViewport :62-103 逐字（常量 2/4/20/2/12；span 级列切片）。 */
    public List<List<Span>> viewport(List<Row> rows, int width) { ... }
    /** pi getEntryDisplayText :779-872 全类型文案表（§1.4）。 */
    static List<Span> displayText(SessionTreeNode node, Map<String,ToolCall> toolCalls, boolean selected) { ... }
    /** pi formatToolCall :960-1002（home→~ 缩写、50/40 截断）。 */
    /** pi formatLabelTimestamp :874-894（当天 HH:MM／今年 M/D HH:MM／跨年 YY/M/D HH:MM）。 */
    /** pi extractContent/extractFullContent（200 截）/getEntryCopyText :921-948/
        hasTextContent/getSearchableText :566-612。 */
    /** pi getStatusLabels :649-666。 */
}
```

- **语义色**：新小常量类 `com.pijava.tui.theme.SemanticColors`（~40 行）：
  `ACCENT=CYAN、MUTED=GRAY、WARNING=YELLOW、SUCCESS=GREEN、ERROR=RED、
  BORDER_ACCENT=MAGENTA、CUSTOM_MESSAGE_LABEL=CYAN、DIM=Style.EMPTY.dim()、
  SELECTED_BG`（selectedBg 底色用 TamboUI 现有高亮底色，与 SelectList 的
  CYAN 高亮同族）。pi dark 主题的具体 RGB 逐字对齐属主题域事项，不在本批；
  语义色名→近似色的落差是 UI 形态内部差，不登记（裁决⑦）。
- **宽度工具**：复用 tui 模块既有可视宽计算（ChatViewportElement  flatten 同款；
  若无公共出口则抽 `TextWidths` 小工具——实施时先实读再定）。
- 水平视口的 span 切片按**列**切：累计列宽跨 scroll 起点切分 text（样式随段走），
  宽字符跨界丢弃＝pi `sliceByColumn(strict=true)` 语义。
- 行尾无省略号截断（pi `truncateToWidth(line, width, "")`）由 span 切片天然保证。

### T4：`TreeSelectorOverlay`（tui，新文件 ~290 行）

pi `TreeSelectorComponent`（§1.6）的 java 落形，实现 `ScreenOverlay`。**只承载
navigate 模式**；`/fork` 的 lane 平表留在旧 `TreeSelectorScreen`（裁决⑥）。

```java
public final class TreeSelectorOverlay implements ScreenOverlay {
    public TreeSelectorOverlay(
        List<SessionTreeNode> tree, String currentLeafId, int terminalRows,
        String initialSelectedId, FilterMode initialFilterMode,
        Consumer<String> onSelect, Runnable onCancel,
        BiConsumer<String, String> onLabelChange,      // (entryId, label|null)
        Consumer<String> onCopy,                       // 宿主给状态反馈
        KeybindingsManager keybindings) { ... }
    // maxVisibleLines = max(5, terminalRows/2)（pi :1371）
    // 组合：标题 "  Session Tree"(bold) / TreeHelpLine / SearchLine / 树区 / (LabelInput) 
}
```

- **键分派**（`onKeyEvent`）：先问 label 编辑态（编辑中 ⇒ EditorComponent 臂：
  Enter submit／Esc cancel，pi LabelInput.handleInput :1298-1311）；否则：
  ① KeyEvent→KeyStroke 经 `TamboUIAdapter.toStroke` 查 `KeybindingsManager`
  （app.tree.*／app.message.copy）；② tui.select.* 层用 KeyEvent helper 直配
  （up/down/enter/esc、ctrl+c、pageUp/Down、left/right、backspace——与 SelectList
  同款纪律）；③ 其余 `isChar && !hasCtrl && !hasAlt` ⇒ appendSearch。
  分派序严格按 §1.5。
- **结局**：`onSelect`⇒ 置 confirmed＋记 entryId，`apply()` 不动会话、把 entryId
  交回调（批 8 §3.10 的 navigate 合同原样）；`onCancel`⇒ cancelled，`apply()` 跑
  onCancel 回调。`isDone = confirmed || cancelled`。
- **label 编辑臂**：收到 `app.tree.editLabel` ⇒ 换出树区、换入 `EditorComponent`
  单行包装（初值＝当前 label；标题行 `Label (empty to remove):`＋
  `enter save  esc cancel` 提示，pi :1276-1295 逐字）；submit ⇒
  `model.updateNodeLabel(id, label, Instant.now())`（本地即时反映，pi
  :1402-1406）＋`onLabelChange.accept(id, label)`（宿主持久化）；cancel ⇒ 换回。
- **TreeHelpLine**（~70 行独立文件）：pi TreeHelp :1217-1258——`move／page／
  branch／copy／label／label time／filters／cycle` 八项，` · ` 分隔按宽换行，
  键名静态取默认（`↑/↓`、`←/→`、`ctrl+left/right`、`ctrl+x`、`shift+l`、`shift+t`、
  `ctrl+d/t/u/l/a`、`ctrl+o`），narrow 端不截断（wrap 优先）。
- **SearchLine**：`  Type to search: `＋查询串（accent），pi :1197-1214 逐字。
- **render()**：`TamboUIAdapter.column(...)` 组合上述块；树区行＝
  `TreeListRenderer.viewport(...)` 输出的 span 行转 TamboUI Text/Row Element；
  窗口起始索引 pi `:666-673`（`selectedIndex - maxVisibleLines/2` 钳制）。

### T5：接线

#### 3.9 `TreeNavigationFlow.openTree` 改道（`TreeNavigationFlow.java:72-81`）

```java
private void openTree(String initialSelectedId) {
    var tree = session.getTree();                                   // T1 §3.4
    if (tree.isEmpty()) {                                           // pi :5563-5566
        chatScreen.appendSystemText("No entries in session");
        return;
    }
    var mode = parseFilterMode(session.services().settings().accessors().getTreeFilterMode());
    openOverlay.accept(new TreeSelectorOverlay(
        tree, currentLeafId(), terminalRows.getAsInt(),
        initialSelectedId, mode,
        this::onTreeSelected, () -> { },
        session::setLabel, this::onCopySelected, keybindings));
}
```

- `parseFilterMode`：五值外回退 DEFAULT（pi settings-manager 读时校验对位）。
- `terminalRows`：构造新参 `IntSupplier`（PiTuiApp 注入）。
- `onCopySelected(String text)`：text==null ⇒ `chatScreen.showError("Selected entry
  has no text to copy")`；否则 `ClipboardSupport.copy(text)` 成功 ⇒
  `appendSystemText("Copied selected message to clipboard")`，异常 ⇒
  `showError(e.getMessage())`（pi :5595-5607 逐字）。
- 构造签名变更 ⇒ `PiTuiApp:431-434` 同步（传 `this::terminalRows` 与
  `keybindingsManager`）。

#### 3.10 `PiTuiApp` terminal rows（~15 行）

事件循环消费 `ResizeEvent`（TamboUI 既有事件类型，pi-java 未消费）：
`lastTerminalRows = event.height()`，初值 24；`int terminalRows()` 读取。
inline 模式同值即可（overlay 在 inline 也渲染于底部区）。

#### 3.11 `KeybindingsManager` 多键＋tree 动作（coding-agent，~40 行改动）

```java
// bindings 改 Map<String, List<KeyStroke>>；resolve 不变（任一命中即返）
public static final String TREE_FOLD_OR_UP   = "app.tree.foldOrUp";      // ctrl+left, alt+left
public static final String TREE_UNFOLD_OR_DOWN = "app.tree.unfoldOrDown";// ctrl+right, alt+right
public static final String TREE_EDIT_LABEL   = "app.tree.editLabel";     // shift+l
public static final String TREE_TOGGLE_LABEL_TIMESTAMP = "app.tree.toggleLabelTimestamp"; // shift+t
public static final String TREE_FILTER_DEFAULT = "app.tree.filter.default";     // ctrl+d
public static final String TREE_FILTER_NO_TOOLS  = "app.tree.filter.noTools";   // ctrl+t
public static final String TREE_FILTER_USER_ONLY = "app.tree.filter.userOnly";  // ctrl+u
public static final String TREE_FILTER_LABELED   = "app.tree.filter.labeledOnly"; // ctrl+l
public static final String TREE_FILTER_ALL       = "app.tree.filter.all";       // ctrl+a
public static final String TREE_FILTER_CYCLE_FORWARD  = "app.tree.filter.cycleForward";  // ctrl+o
public static final String TREE_FILTER_CYCLE_BACKWARD = "app.tree.filter.cycleBackward"; // shift+ctrl+o
public static final String MESSAGE_COPY = "app.message.copy";            // ctrl+x
```

既有 11 个全局动作注册改单元素 list，行为零变化。用户覆写仍不支持（既有约束，
窄差 D45）。

#### 3.12 `ClipboardSupport`（tui，新文件 ~60 行）

```java
public final class ClipboardSupport {
    /** AWT 系统剪贴板；headless/不可用 ⇒ 抛 IOException（调用方转 showError）。 */
    public static void copy(String text) throws IOException { ... }
}
```

不移植 pi 的 native/pbcopy/clip/OSC 52 降级链（裁决⑤，窄差 D44）。

---

## §4 裁决条目

### 裁决① label／session_info 不进 `Entry` ADT——「all」模式少两种行，登记窄差

【场景】pi 的 label/session_info 是**树内条目**（`all` 过滤模式下显示
`[label: x]`／`[title: y]` 行，参与父子链）；java 的既有架构是 FactLabel／FactName
**事实旁表**（wire 与 pi 原生双向互通，批 agent-core 各批已沉淀）。
【决定】本批不给 Entry 加变体。选择器数据面走 `getTree()`＋事实 label 解析；
`all` 模式下 java 树**没有**这两种行（`isSettingsEntry` 谓词相应只有四型）。
【风险/代价】窄差 D43（「all」模式少行；labeled-only 过滤、标签显示/编辑/时间戳
均不受影响——label 值经事实表解析，与 pi 同效）。反向若加变体：wire 双表示
（既有 FactLabel 解码＋新条目型）冲突、压缩/投影/分支复制全链要重裁，代价远超
「all」模式两行。

### 裁决② `SessionState` 加 labelTimestamps，不把 `[+label time]` 登记成窄差

【场景】pi 的 shift+t 开关显示 label 变更时刻（数据源
`labelTimestampsById`）；java 的 labels 表无时间戳。
【决定】`SessionState` 加 `labelTimestamps` 随 FactLabel 应用/清除（§3.2），全链
转发到 `AgentSession.getTree()`；**顺带修真**分支复制重发用原时间戳（pi
:1694-1712）。代价 ~30 行，换来 S13 整行无残留。
【风险】Sqlite storage 的 facts 表若无时间戳列 ⇒ 该后端时间戳为 null，登记
D47（实施时实读 schema 定夺；Jsonl/Memory 两主路径全对齐）。

### 裁决③ `get_tree` RPC/Web 顺带修真（单车道转录＋label=null ⇒ 全分支＋label）

【场景】java 两处私有树构建器（§2.2）建在 harness 单车道转录上且 label 恒 null，
pi RPC `get_tree` 数据源即 `sessionManager.getTree()`。
【决定】T1 的 `AgentSession.getTree()` 落成后两处改走它（§3.5），私有构建器删除。
【风险/代价】RPC `get_tree` 响应的 tree 内容变化（全分支＋label）——这是**向 pi
看齐的修真**不是回归；web 前端消费方若按单车道假设渲染需在闭环时实跑一次
（web 验证手法见记忆：无头 Chrome＋假 WS）。

### 裁决④ tree 键注册进 `KeybindingsManager`（多键扩展），overlay 内解析

【场景】pi 的 tree 键全部走可配置 keybindings（foldOrUp 双键 ctrl／alt）；java
注册表一动作一键且无覆写；现有 SelectList 直配 KeyEvent helper 不经注册表。
【决定】`bindings` 扩为 `Map<String, List<KeyStroke>>`，注册 12 个 app.tree.*／
app.message.copy 动作（§3.11），overlay 经 `TamboUIAdapter.toStroke` 查询；
tui.select.* 层（上下/确认/取消/翻页/退格）沿 SelectList 纪律直配 helper。
【风险/代价】与全局动作同键（ctrl+t/o/l/d）不冲突——overlay 先拿键
（`PiTuiApp:310-321`）；用户覆写不支持是既有约束（D45）；注册表 Map 值类型变更是
内部 API，全仓消费者仅 TUI 一处实查后改。

### 裁决⑤ 剪贴板只走 AWT，不移植平台命令/OSC 52 降级链

【场景】pi `copyToClipboard` 有 native binding→平台命令→OSC 52 的降级链；
java 全仓仅 `MiscCommands` 用 AWT。
【决定】`ClipboardSupport`＝AWT 系统剪贴板，headless/异常 ⇒ IOException ⇒
`showError`（§3.12）。成功/无文本/失败三结局文案与 pi 逐字。
【风险】SSH/无显示环境复制失败只报错不降级（D44）——主路径（桌面终端）可用。

### 裁决⑥ `/fork` 本体不动——pi 的 user-message fork 选择器另立 B254

【场景】取证撞出 pi `/fork` 用的是**用户消息选择器**（§1.7），java `/fork` 是
lane 平表——这是本体差异但**不是 D37 的登记内容**（D37 仅指 `/tree` 选择器）。
【决定】本批只换 `/tree` 侧；`TreeSelectorScreen`（fork 模式 lane 平表）原样保留
至 B254（与 S20 排队消息选择器同组件，宜合包立项）。
【风险】无——两 overlay 独立文件，互不影响。

### 裁决⑦ 渲染走 span 自绘，不引 ANSI 串；宽度/切片语义对齐 pi

【场景】pi 渲染层是 ANSI 字符串＋`sliceByColumn/truncateToWidth/visibleWidth`
（pi-tui utils）；java TUI 是 TamboUI Element/Style 世界。
【决定】行＝`List<Span(text, Style)>`（§T3），水平视口在 span 层按列切片
（gutter 恒可见、strict 宽字符边界、无省略号截断——pi `:62-103` 逐字语义）；
语义色映射到 TamboUI 近似色常量（accent→CYAN 等，§T3）。
【风险】色值是近似非逐字（pi dark 主题 RGB 对齐属主题域，不登记）；宽度计算复用
tui 既有工具，宽字符/grapheme 边缘与 pi 的 grapheme 分割有内部差（不登记，两端
主路径 ASCII/CJK 一致）。

### 裁决⑧ terminal rows 经 `ResizeEvent` 跟踪，初值 24

【场景】pi 用 `ui.terminal.rows` 实时值算 `maxVisibleLines=max(5, rows/2)`；
java TUI 未消费 ResizeEvent。
【决定】`PiTuiApp` 事件循环记 `lastTerminalRows`（§3.10），开 overlay 时读取。
【风险】打开后终端再缩放不重算（pi 同样只在构造时取一次——无差）。

---

## §5 窄差表（本批新增，接续批 8 的 D36–D42）

| # | 窄差 | 影响面 |
|---|---|---|
| D43 | 「all」过滤模式无 label／session_info **条目行**（pi 这两种是树内条目；java 是事实旁表，裁决①）。labeled-only 过滤/标签显示/编辑不受影响 | 边缘（all 模式少两行元数据） |
| D44 | 剪贴板仅 AWT：无 pbcopy/clip/wl-copy/OSC 52 降级链，headless 报错（裁决⑤） | 边缘（SSH/无显示环境） |
| D45 | tree 键用户覆写不支持（keybindings.json 未接线——既有全局约束，tree 键同源） | 内部 |
| D46 | 帮助行键名静态取默认值（pi 随覆写渲染；与 D45 同源，覆写落地即消） | 内部 |
| D47 | （条件登记）Sqlite 后端 label 时间戳不可得 ⇒ 该后端 `[+label time]` 无数据显示（裁决②；实读 schema 后定，若 facts 有 timestamp 列则本条作废） | 边缘 |

既有窄差随本批**销号**：D37（本体落地）、D40（恢复选中粒度＝entry 节点，
`findNearestVisibleIndex(initialSelectedId ?? currentLeafId)` 逐字对位）。

---

## §6 测试计划

### tui：`TreeListModelTest`（pi 13 用例逐一对位移植，零 UI 驱动）

夹具对位 pi `tree-selector.test.ts`：`userMessage/assistantMessage/
toolCallOnlyAssistant/modelChange` 工厂＋`buildTree`（parentId 链）＋
`buildBranchingTree`（user-1→asst-1→user-2→asst-2，A/B 两分支，活动叶 asst-4a）。

1. 当前叶＝被默认过滤的 model_change（有兄弟）⇒ 选中最近可见祖先 user-2（pi :130）。
2. context_edit 默认隐；`initialFilterMode=ALL` 时渲染文案含 `[context omit: …]`（pi :159）。
3. thinking_level_change 叶祖先回退（pi :196）。
4. user-only 从 asst-2 ⇒ 父 user-2；切回 default 保持（pi :227/:256）。
5. ctrl+x 复制全文（`selectedCopyText()` 探针，pi :314）。
6. shift+t ⇒ label 行出 `M/D HH:MM`＋状态标 `[+label time]`（pi :336）。
7. 空 labeled 往返保持选中；多次空切换保持（pi :366/:400）。
8. foldOrUp 折段首 → unfoldOrDown 展开 → 再 unfoldOrDown 段跳到叶（pi :476）。
9. 折根隐整棵子树、嵌套折叠展开后保留（pi :536）；非活动分支（pi :572）；
   多根（pi :607）；中间条目被过滤时折叠仍隐后代（pi :648）。
10. 搜索清折叠（pi :676）；过滤切换清折叠（pi :709）。
11. 环绕导航：首行上移⇒末行、末行下移⇒首行（pi :1006-1010）。
12. 搜索分词 AND：多 token 全中才显；backspace 逐字删；Esc 第一级清搜索不清选中。

### tui：`TreeListRendererTest`

13. prefix/gutter 形：分支点 `├─/└─`、gutter `│` 延续、折叠符 `⊞/⊟/─`、活动路径 `•`。
14. 文案表全类型（§1.4 表逐行断言，含 `(aborted)`/`(no content)`/errorMessage 80 截）。
15. `formatToolCall` 七型＋home→`~` 缩写＋50/40 截断。
16. labelTimestamp 三档（当天/今年/跨年，注入时钟）。
17. 水平视口：选中行锚点超宽 ⇒ body 左移、gutter 不动、无省略号（pi :62-103 数值例）。
18. 帮助行宽 30 换行不截断、含八项关键词（pi :290）。

### tui：`TreeSelectorOverlayTest`（overlay 直驱，不跑 runner——批 8 手法）

19. 键分派全表（12 注册键＋tui.select.*＋可打印搜索）；与全局同键无串扰。
20. label 编辑臂：shift+l 换入编辑器、Enter submit ⇒ `updateNodeLabel`＋回调、
    Esc 换回；空串 submit ⇒ label=null（清除）。
21. Enter ⇒ confirmed＋entryId；Esc（无搜索）⇒ cancelled；apply 不动会话。
22. `initialSelectedId` 恢复选中＝entry 粒度（D40 销号实证）；`initialFilterMode`
    非法值回退 DEFAULT。

### coding-agent／agent-core

23. `AgentSession.getTree()`：多分支/孤儿/自指根、children 时间戳升序、label＋
    labelTimestamp 解析（最新 FactLabel 赢、清除删两表）。
24. 分支复制重发保留原 label 时间戳（裁决②顺带修真回归）。
25. `TreeNavigationFlowTest` 现有 12 用例改新 overlay 构造全绿（接线回归）；
    新增：空树 ⇒ "No entries in session" 不开 overlay；copy 三结局文案；
    `getTreeFilterMode` 初值消费。
26. RPC `get_tree`：tree 含全分支＋label 非空（裁决③修真回归）。

### 验证口径（§7 同批 8 纪律）

`mvn test -pl pi-java-agent-core,pi-java-coding-agent,pi-java-tui -am`（**必须 -am**）；
`mvn checkstyle:check`；收口前 `mvn clean verify` 全量（spotbugs 在 verify 段）。
web 前端按裁决③实跑一次（无头 Chrome＋假 WS）。

---

## §7 风险与开放点

- **E1 连带**：`AgentSession` +70 行 ⇒ 2,010→~2,080，E 类台账同步；getTree 构建
  段如超 60 行宜置 `core/` 下独立 `SessionTreeBuilder`（实施时按 500 行纪律定）。
- **TamboUI span→Element**：树区每帧重建 Element 列表（ overlay 渲染本来就整棵
  重建，与 SelectList 同款）；性能无虞（行数 ≤ maxVisibleLines+3）。
- **`/fork` 车道平表保留**造成的「同名两选择器」认知差在 B254 闭环前存在，
  证据列注明。
- **inline 模式**：overlay 走 `renderOverlayInline` 底部区，树高受 maxVisibleLines
  自限，无需特判。

## §8 对账表（预期）

| 行 | 现判定 | 预期 | 证据更新 |
|---|---|---|---|
| map/05 S12（权重 2） | 存疑 | **对齐** | `TreeSelectorOverlay`＋`TreeListModel`＋`TreeListRenderer`（entry 树本体）；D43–D47 登记 |
| map/05 S13（权重 1） | 缺失 | **对齐** | 折叠/标签编辑/五过滤/搜索/复制全落（§6 测试 1–22） |
| map/04 #3 `/tree`（权重 1） | 存疑 | **对齐** | 选择器本体＝entry 树；导航流批 8 已落 |
| docs/04 D37/D40 | 在册 | **销号** | §5 |
| docs/04 B254 | — | **新登** | `/fork`＝user-message 选择器本体（§1.7/裁决⑥），触发条件：随 S20 合包 |
| docs/04 E1 | 2,010 | ~2,080 | §7 |

## §9 闭环回填

**闭环日 2026-10-22**（commits `e8b8505d`…`aeb28a23`，12 个；T1–T5＋评审三修）。

- **落地对账**：§8 预期全部兑现——map/05 S12 存疑→**对齐**（+1.0）、S13 缺失→**对齐**
  （+1.0）、map/04 #3 `/tree` 存疑→**对齐**（+0.5）；模块 283.5→**286.0/487 ≈ 58.7%**、
  全仓 955.5→**958.0/1381 ≈ 69.4%**、D 域不动（88.0%）。**D37/D40 销号**（本体落地；
  恢复选中粒度＝entry 节点）；**B254 新登**（docs/04 §3.4）；E1 2,010→**2,060**、
  E2 693→**674**、E6 537→**559**。
- **窄差**：D43–D46 新登＋评审 M1 登 **D48**（RPC `get_tree` 节点缺 labelTimestamp）；
  **D47 作废**（sqlite facts 表自带 timestamp 列，三后端一致性测试通过）。
- **超设计落地**（评审/实施撞出，均 RED→GREEN）：① C1——tree 键与全局同表污染
  `resolve()`（裁决④「resolve 任一命中即返」被证伪）⇒ 全局/tree 两表分离；
  ② I1——TamboUI 不报字母 SHIFT ⇒ `toStroke` 落 pi legacy 规则（大写即
  shift+letter，否则 label 编辑臂死代码）；③ I2——`--no-session` label 提交异常
  ⇒ flow 转 showError；④ sqlite fork 是原生表拷贝（不走 SessionState）⇒ 分支
  label 时间戳修真双点；⑤ treeLeafId 折算（usage 记账行叶 ⇒ 语义叶取最近非
  usage 条目，否则 /tree 初始 Enter 弹三选与 pi 主流分叉）。
- **验证口径**：`mvn test -pl pi-java-agent-core,pi-java-coding-agent,pi-java-tui -am`
  全绿（agent-core 860＋sqlite 56、coding-agent 全套件、tui 278）；`mvn checkstyle:check`
  绿；`mvn clean verify` 全量绿（首跑撞 `LaneRecordsConcurrencyTest` 既有并发 flake，
  单跑 5/5 过、二跑全绿，与本批改动零交集）；web 前端实跑（无头 Chrome＋假 WS 驱动
  真 bundle）：多分支＋label 树正常渲染、零 console 错误（裁决③闭环）。
- **台账回填**：docs/04（批 9 条目＋B254＋E1/E2/E6 读数＋基准批次行）、
  docs/05（§1 总表＋§2.4 批 9 行＋§2.5 45.1%）、docs/map/05-tui-web.md
  （S12/S13 行＋计分表＋缺失清单）、docs/map/04-coding-agent.md（#3 行）、
  docs/20（§1/§2/§3——§2.5 进行中清空）、记忆（下一包号 docs/21）。


