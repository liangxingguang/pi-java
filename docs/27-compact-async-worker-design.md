# 包 27：手动 `/compact` 异步化——压缩挪 worker 线程（B176）

> **状态：⏳ 设计待审核，未写生产代码。**
> 承接台账 **B176**（包 26 实施中登记）。同一根因在 **TUI slash 命令**与 **RPC compact
> 命令**两个面，本包一并修：命令处理线程在压缩期间必须继续处理输入。

## 1. 问题

- pi 的命令处理是 **async 函数**：`await session.compact(...)` 只挂起当前处理函数，
  JS 事件循环（渲染、读行、后续命令）照跑。
- pi-java 的对应路径把**整次压缩同步跑在命令处理线程上**：
  - TUI：`/compact` 在**渲染线程**跑完整压缩 ⇒ 画面冻结、无法输入，包 26 的压缩窗口
    缓冲在这条手动路径**不可达**；
  - RPC：`compact` 命令在 **stdin 读线程**跑完整压缩 ⇒ 压缩期间后续命令全部堵住，
    pi 客户端却可并发投递（pi `void handleInputLine(line)`，fire-and-forget）。

## 2. pi 锚点事实

### 2.1 TUI：onSubmit 是 async 回调，编辑器不 await

```ts
// interactive-mode.ts:3153
this.defaultEditor.onSubmit = async (text: string) => {
    ...
    // :3263-3268
    if (text === "/compact" || text.startsWith("/compact ")) {
        const customInstructions = text.startsWith("/compact ") ? text.slice(9).trim() : undefined;
        this.editor.setText("");
        await this.handleCompactCommand(customInstructions);
        return;
    }
```

```ts
// :7008-7017
private async handleCompactCommand(customInstructions?: string): Promise<void> {
    this.clearStatusIndicator();
    try {
        await this.session.compact(customInstructions);
    } catch {
        // Ignore, will be emitted as an event
    }
}
```

`await` 期间事件循环不空转：渲染继续、编辑器继续收键（压缩窗口输入经包 26 缓冲）。

### 2.2 RPC：每行一个 fire-and-forget async 调用

```ts
// rpc-mode.ts:750
const handleInputLine = async (line: string) => {
    ...
    try {
        const response = await handleCommand(command);   // compact 在此 await
        if (response) { output(response); ... }
    } catch (commandError: unknown) {
        output(error(command.id, command.type,
            commandError instanceof Error ? commandError.message : String(commandError)));
    }
};
// :809（读行循环内）
void handleInputLine(line);
```

compact 分支：`:533-536` `const result = await session.compact(command.customInstructions);
return success(id, "compact", result);`。读行循环不 await 处理函数 ⇒ 压缩中后续命令
**并发处理**；compact 抛错 ⇒ 外层 catch 写一条 `fail(id,"compact",message)`。

## 3. 方案

### 3.1 `CommandUtil.async`（NEW helper）

同步 `simple` 之外加一个 worker-thread 变体——命令 body 改在虚拟线程跑，`execute`
立即返回未完成的 `CompletableFuture`：

```java
/** Command body executed on a worker virtual thread; dispatch returns at once. */
static SlashCommand async(String name, String description,
                          String hint, SimpleBody body) {
    return new SlashCommand() {
        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public String argumentHint() { return hint; }
        @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
            var result = new CompletableFuture<String>();
            // B176（docs/27）：pi 命令处理是 async 函数 ⇒ 压缩不阻塞命令/渲染线程。
            Thread.startVirtualThread(() -> {
                try {
                    result.complete(body.run(args, ctx));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
            return result;
        }
    };
}
```

### 3.2 `MiscCommands`：compact 改用 `async`

```java
registry.register(async("compact", "Compact context manually", "<text>",
    (args, ctx) -> {
        try {
            String custom = args.isBlank() ? null : args.trim();
            ctx.session().compact(CompactionSettings.defaults(), custom);
            return "Compacted.";
        } catch (Exception e) {
            return "Compaction failed: " + e.getMessage();
        }
    }));
```

body 内 try/catch 保留（失败结果文本，同现状）；未捕获的 `Throwable` 经 helper 以
异常完成。PiTuiApp `submit()` 的 `command.thenAccept(... dispatcher.dispatch(...))`
已天然支持：dispatch 立即返回、结果文本在压缩完成时经渲染线程追加（与
CompactionEnd 同 worker 线程顺序发 ⇒ 追加序在事件之后）。

### 3.3 RPC：compact case 改 worker 虚拟线程

```java
case RpcCommand.Compact c -> {
    // B176（docs/27）：pi void handleInputLine ⇒ 读线程不阻塞，响应压缩完成时写。
    Thread.startVirtualThread(() -> {
        try {
            var result = session.compact(
                CompactionSettings.defaults(), c.customInstructions());
            out.write(RpcResponse.ok(c.id(), "compact", CompactResultWire.of(result)));
        } catch (RuntimeException e) {
            try {
                // pi 外层 catch：fail(id,"compact",message)（rpc-mode.ts:789-797）。
                out.write(RpcResponse.fail(c.id(), "compact", e.getMessage()));
            } catch (IOException io) {
                throw new UncheckedIOException(io);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    });
}
```

`JsonlWriter.write` 已 `synchronized`（事件本来就由 run 虚拟线程并发写）⇒ 响应字节
不交错。其余命令的处理不受影响、读线程立即回到读下一行。

### 3.4 关闭 / 中止语义

- TUI 退出（`close()`）：现有逻辑 abort harness 信号；worker 虚拟线程跑到压缩收口
  后结束（无人再 await 它的 future），与 pi dispose 形状一致。
- 压缩中用户 Esc：abort 信号经 `close()`/`abort()` 既有路径生效，CompactionEnd 事件
  照常驱动指示器清除与包 26 缓冲 flush。
- 不改动：RPC `prompt` 压缩门（仍报错，pi 同）、自动压缩路径（本就跑在 run 线程）。

## 4. 测试计划（RED-first）

1. **`CompactSlashAsyncTest`**（coding-agent，NEW）：
   - 专用 `BlockingSummaryProvider`：摘要请求（user 文本含 `Conversation`）在
     CountDownLatch 上阻塞；其余请求直接返 `ok`。会话经 `seedTranscript` 直接播种
     4 条消息（同包 26 夹具，免跑主回合）。
   - watchdog 虚拟线程：`isCompacting=true` 后 2s 自动 release（兜底，使同步变异体
     产出普通红而非永久挂死）。
   - `registry.dispatch("/compact")` 返回 stage；轮询 `isCompacting=true` ⇒
     断言 **`!stage.isDone()`**；
   - `mode.submit("later")` ⇒ `"queued"`（包 26 缓冲，手动路径自此可达；校验
     queue_update 聚合含 `"later"`）；
   - 主动 release latch；`stage.get(10s)` ⇒ `"Compacted."`；缓冲的 "later" 起新 run
     并收口（`session.isRunning` 最终 false、transcript 含 compaction 标记）。
   - 失败用例：摘要流发 `StreamError` ⇒ stage 结果 `"Compaction failed: ..."`。
2. **`RpcCompactAsyncTest`**（coding-agent，NEW，handleLine 直调形状）：
   - compact 命令 dispatch 后轮询 `isCompacting`；压缩中发 `get_state` ⇒
     **其响应在 compact 响应之前写出**（按响应出现序断言；同步变异体顺序反转）。
   - release latch ⇒ compact 响应 success、data 为 5 键 CompactResultWire 形状。
3. **既有 `CompactSlashCommandTest` ×3 不改**（已 `join()` 等待，天然兼容异步）。

## 5. 变异探针

| # | 变异 | 预期红 |
|---|---|---|
| M1 | compact slash 改回 `simple`（同步） | `!stage.isDone()` 断言：watchdog 放行后才返回、stage 已完成 ⇒ 红 |
| M2 | RPC compact 改回同步 | get_state 响应被堵到压缩后 ⇒ 响应序断言红 |
| M3 | worker 不 complete future（漏成功/失败任一路） | stage.get 超时 ⇒ 红 |
| M4 | 失败 catch 删除（异常完成而非结果文本） | 失败用例红 |

每次变异 grep 复核落地（CRLF 教训）。

## 6. 台账影响

- **B176 销号**（B 70 → 69）。
- docs/04 同步；闭环后 banner 与实施记录回填。
