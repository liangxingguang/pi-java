# docs/64 — L7 W4：对话框栈化＋扩展编辑器 Ctrl+G 失败提示＋外部编辑器文案

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游：`docs/59` §3 W4（覆盖 map/05 **S22、K10**）、docs/28（扩展 UI 批 17）、docs/59 §2.1 S22/K10 判定
- 状态：**2026-11-20 用户授权「写文档后直接实施」**（§6 实施记录）

## 0. 范围

| 行 | pi 面 | Java 现状 | 本包 |
|---|---|---|---|
| S22 | `interactive-mode.ts` 扩展对话框**栈**（`:573`）：只挂栈顶、关闭回上一层、`blocked` 取栈顶 | `OverlayManager.overlay` 是**单槽**（`:21`），嵌套开对话框会顶掉父层且不恢复 | `OverlayManager` 改栈 |
| S22 | 同上：`blocked` 随栈顶（`:2812`） | Java 无 `programStatus`/`blocked` 概念（全仓 0 命中） | 登记 D272（无消费面） |
| K10 | `external-editor.ts` 两条失败文案逐字 | 只有 `ExternalEditor failed: <msg>`，`exit≠0` **完全无提示** | `Result.Failed(String)`＋逐字文案 |
| K10 | `extension-editor.ts:34/:90/:136`：Ctrl+G 失败在**框内** warning 行（`theme.fg("warning", message)`），下次成功才清 | `ExtensionEditorOverlay` 无 status 行，失败只在宿主 `showError`（alt 屏期间可能不可见） | 框内 warning 行 |

## 1. pi 事实（逐条带行号）

1. 栈结构：`extensionDialogs: Array<{component, blocked}>`（`:573`），末尾＝可见；**无深度上限**。
2. 压栈 `pushExtensionDialog`（`:2792-2796`）：先 `disposeActiveSelector()` 再 push 再 `showTopExtensionDialog()`。
3. 只挂栈顶 `showTopExtensionDialog`（`:2806-2814`）：`editorContainer.clear()` → 只 add `at(-1)`，空栈回落编辑器；`programStatus.setBlocked("extension-dialog", top?.blocked)` ⇒ **blocked 只看栈顶**。
4. 出栈 `removeExtensionDialog`（`:2799-2804`）：按引用 splice；**只有关的是栈顶**才重挂 ⇒ 关闭最上层回退到上一层。
5. Esc/取消由组件自己吃（`extension-editor.ts:113-116`），无全局弹栈。
6. 扩展编辑器 warning：字段 `externalEditorStatus = new Text("")`（`:34`），挂在 editor 之后（`:90`）；Ctrl+G 结果 `failed` ⇒ `setText(theme.fg("warning", result.message))`（`:136`）；仅下一次 `complete` 时清空，**无定时清除**。
7. 失败文案（`external-editor.ts`）：
   - 启动失败：`Failed to launch external editor: <command>`（`:41`）
   - 退出码非零：`External editor exited with code N; prompt unchanged`（`:43-47`；信号臂为 `... exited with SIGINT; prompt unchanged`，Java 无信号面）

## 2. 设计

### 2.1 `OverlayManager`：单槽 → 栈

```java
private final Deque<ScreenOverlay> stack = new ArrayDeque<>();
```
- `openOverlay(o)`：`stack.push(o)`（栈顶＝head）。
- `currentOverlay()`：`stack.peek()`。
- `dismissOverlay(target)`：按**引用**移除（`removeIf(o -> o == target)`），若是栈顶则自然露出下一层；随后照旧 `target.apply(...)`（结局 future 必须完成）。
- `closeOverlayIfDone()`：`done = peek()`；`isDone()` ⇒ 先 `pop()` 再 `done.apply(...)`（保持"先清位再 apply"的既有语义：apply 可链式开下一个 overlay，如 Settings→thinking/theme）。
- **不做** `blocked`（D272）：Java 没有 program-status 行，也没有按 blocked 拦键的消费面；栈化本身已解决"父对话被顶掉"。
- 线程：`ArrayDeque` 非线程安全 ⇒ 四个入口 `synchronized`（写：dispatcher 线程；读：全局键处理/测试钩子）。`PiTuiApp`/`GlobalKeyRouter` 只经 `currentOverlay()` 读，无需改动。

### 2.2 `ExternalEditorSupport`：失败带文案

```java
sealed interface Result {
    record Complete(String content) implements Result {}
    record Failed(String message) implements Result {}
}
```
- `pb.start()` 抛 `IOException` ⇒ `Failed("Failed to launch external editor: " + command)`（不再外抛；临时文件写失败仍外抛——那是宿主环境问题，不是"编辑器启动失败"）。
- `code != 0` ⇒ `Failed("External editor exited with code " + code + "; prompt unchanged")`。
- 兼容：`Failed(int)` 删除；唯一测试断言改为消息面（`ExternalEditorSupportTest`）。

### 2.3 `ExtensionEditorOverlay`：框内 warning 行

- 往返函数类型从 `BiFunction<String,String,Optional<String>>` 改为 `BiFunction<String,String,ExternalEditorSupport.Result>`（tui 已依赖 coding-agent）。
- 字段 `volatile String warning`（null ⇒ 不渲染）；`Ctrl+G`：
  - `Complete` ⇒ `editor.setText(content)`，`warning = null`；
  - `Failed` ⇒ `warning = result.message()`（内容不动）。
- 渲染：`editor.renderBare()` 之后插入一行 warning（`SemanticColors.warning()`；空则**不占行**——pi 的 `Text("")` 渲染零行），其余行序不变。

### 2.4 主聊天 Ctrl+G

`GlobalKeyRouter.openExternalEditor`：`Failed` ⇒ `chatScreen.showError(result.message())`（文案已是逐字模板，不再拼 `"External editor failed: "`）；`ExtensionHostWiring` 同步。

## 3. 窄差

| 编号 | 内容 |
|---|---|
| **D272** | 扩展对话框 `blocked` 状态无对位：pi 有 program-status 区（`programStatus.setBlocked("extension-dialog", top?.blocked)`，`interactive-mode.ts:2812`），Java 没有状态行、也没有按 blocked 拦截输入的消费面；栈化后"只挂栈顶/回退上一层"已对齐，`blocked` 不移植。 |

## 4. 测试计划

1. `OverlayManagerStackTest`（新）：开 A → 开 B ⇒ `currentOverlay()==B`；A 完成后 `closeOverlayIfDone` 不弹 B；B 完成 ⇒ 回 A；`dismissOverlay(A)`（非栈顶）不影响 B；`dismissOverlay(B)` ⇒ 回 A；空栈 ⇒ null。
2. `ExtensionEditorOverlayTest`：Ctrl+G 失败 ⇒ `warningText()` 为逐字文案且内容不动；再次成功 ⇒ warning 清空、内容更新；render 行数在无 warning 时与旧一致（零行）。
3. `ExternalEditorSupportTest`：启动失败（不存在的命令）⇒ `Failed` 且 message 逐字；退出码非零 ⇒ `Failed` 且 message 含 `code N; prompt unchanged`。
4. 既有回归：`ExtensionCustomOverlayTest`、`ExtensionSelectorOverlayTest`、`ExtensionInputOverlayTest`、`PiTuiAppInputTest`（overlay 单槽假设若存在需改）。

## 5. 风险

- `closeOverlayIfDone` 的链式开屏（Settings→子菜单）依赖"先清位再 apply"，栈化必须保持该顺序，否则新屏被立即弹掉。
- `TuiExtensionUI.OverlayHandleNarrowed.hide()` 走 `dismissOverlay` ⇒ 必须按引用移除，不能"弹一个"（隐藏的不是栈顶时误伤）。
- 外部编辑器 `Result` 换签名会波及 3 处调用与 1 处测试，全部是编译期暴露。

## 6. 实施记录（2026-11-20）

| 文件 | 改动 |
|---|---|
| `tui/.../app/OverlayManager.java` | 单槽 `overlay` → `ArrayDeque<ScreenOverlay>`（`synchronized` 四入口）；`openOverlay` 压栈、`dismissOverlay` 按引用移除、`closeOverlayIfDone` 先 pop 再 apply；新增 `depth()` |
| `tui/.../app/PiTuiApp.java` | `overlayDepth()`／`closeCompletedOverlay()` 测试钩子（并压缩既有注释以守住 500 行上限） |
| `coding-agent/.../core/ExternalEditorSupport.java` | `Result.Failed(int)` → `Failed(String message)`；启动失败与退出码非零两条逐字文案，`pb.start()` 的 `IOException` 不再外抛 |
| `tui/.../screen/ExtensionEditorOverlay.java` | 往返类型改 `BiFunction<…, ExternalEditorSupport.Result>`；新增 `warning` 字段＋框内 warning 行（`SemanticColors.warning()`，下次成功才清）；`warningText()` 探针 |
| `tui/.../app/TuiExtensionUiHost.java`、`ExtensionHostWiring.java`、`GlobalKeyRouter.java` | 往返返回结果对象；失败文案原样上屏（不再拼 `External editor failed:`） |

**测试**：`pi-java-tui` **682 测 0 失败 1 skip**（新增 8 项：`OverlayStackTest` 5、`ExtensionEditorOverlayTest` +2、`ExternalEditorSupportTest` 改写 1 项为文案断言）；`ExtensionCustomOverlayTest`／`ExtensionUiResetTest`／`TuiExtensionUITest` 的 `bindApp` lambda 同步改签名。

**偏离**：`blocked` 不移植（D272）；`OverlayManager` 的测试钩子给了 `PiTuiApp` 两个包私有方法，因 `OverlayManager` 构造依赖完整 app，无法独立单测。
