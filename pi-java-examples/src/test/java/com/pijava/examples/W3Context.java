package com.pijava.examples;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionExec;
import com.pijava.coding.agent.extension.ExtensionFlagOptions;
import com.pijava.coding.agent.extension.ExtensionSessionView;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionEventHandler;

/**
 * docs/52 W3 测试夹具：{@link ExtensionContext} 的记录型代理。
 *
 * <p>避开 {@code ExampleFixtures.newSession} 的 {@code --no-session} 限制（setLabel/
 * setSessionName 需要持久会话面）：这些写操作的宿主实现已在 coding-agent 测过，
 * examples 层只验「示例调用形状＋通知文本＋分支逻辑」。命令注册用真实
 * {@link CommandRegistry}（handler 捕获本代理），事件用 spec-keyed 表登记并可手动 fire。</p>
 */
final class W3Context {

    final CommandRegistry commands = new CommandRegistry();
    final com.pijava.agent.tool.ToolRegistry tools = new com.pijava.agent.tool.ToolRegistry(null);
    final ExampleFixtures.ScriptedUi ui;
    final ExtensionContext ctx;
    final ExtensionSessionView view;

    final Map<String, String> labels = new LinkedHashMap<>();
    final List<String> appended = new ArrayList<>();
    final List<String> sentMessages = new ArrayList<>();
    final List<String> execLog = new ArrayList<>();
    String sessionName;
    String mode = "tui";
    List<Entry> entries = List.of();
    List<Entry> branch = List.of();
    Entry leafEntry;
    Object flagValue;
    List<String> activeTools = new ArrayList<>();
    List<com.pijava.coding.agent.extension.ExtensionToolInfo> allTools = List.of();
    com.pijava.coding.agent.extension.ExtensionModelRegistry modelRegistry;
    java.util.Optional<com.pijava.coding.agent.extension.ExtensionModelInfo> model =
        java.util.Optional.empty();
    boolean setModelResult = true;
    /** 思考级别（宿主恒有值；pi {@code ModelThinkingLevel = "off" | ThinkingLevel}）。 */
    Object thinkingLevel = com.pijava.ai.thinking.ModelThinkingLevel.off();
    /** cwd 可配（ssh/tool-override 路径解析用）。 */
    String cwd = ".";
    /** W4：已注册的 renderer resolver 数（registerToolRenderer）。 */
    int rendererCount;
    /** W4：已注册补全 provider 数（ui.addAutocompleteProvider）。 */
    int autocompleteCount;
    /** W4：user_bash 事件返回的结果（fire 后由测试读取）。 */
    java.util.List<Object> userBashResults = new ArrayList<>();
    /** exec 应答脚本：按调用序返回；默认空结果。 */
    final List<ExtensionExec.ExecResult> execResults = new ArrayList<>();
    java.util.function.Function<ExtensionExec.ExecResult, ExtensionExec.ExecResult> execResultFn;
    /** W7：exec 的 cwd 参数录表（ExecOptions.cwd，null 记 "<null>"）。 */
    final List<String> execCwdLog = new ArrayList<>();
    /** W7：ctx.shutdown() 调用次数。 */
    int shutdownCount;
    /** W5：getContextUsage 的应答（empty ≙ pi 的 undefined）。 */
    java.util.Optional<com.pijava.coding.agent.extension.ExtensionContextUsage> contextUsage =
        java.util.Optional.empty();
    /** W6：getCommands 的应答（pi {@code ctx.getCommands()}，三源序）。 */
    List<com.pijava.coding.agent.core.slash.SlashCommandInfo> commandInfos = List.of();
    /** W5：registerMessageRenderer 录表（customType ⇒ renderer）。 */
    final Map<String, com.pijava.coding.agent.extension.ExtensionMessageRenderer>
        messageRenderers = new LinkedHashMap<>();
    /** W5：registerEntryRenderer 录表。 */
    final Map<String, com.pijava.coding.agent.extension.ExtensionEntryRenderer>
        entryRenderers = new LinkedHashMap<>();
    /** W5：appendEntry 的 data 录表（customType ⇒ 最后一次的 data）。 */
    final Map<String, Map<String, Object>> appendedData = new LinkedHashMap<>();
    /** W5：sendMessage 的完整载荷（type, content, details）。 */
    final List<SentCustomMessage> sentCustom = new ArrayList<>();

    /** W5：一条 {@code sendMessage} 的自定义消息载荷。 */
    record SentCustomMessage(String type, String content, Map<String, Object> details) {}

    private final Map<ExtensionEventSpec<?, ?>, List<ExtensionEventHandler<?, ?>>> handlers =
        new LinkedHashMap<>();

    W3Context() {
        this(new ExampleFixtures.ScriptedUi());
    }

    W3Context(ExampleFixtures.ScriptedUi ui) {
        this.ui = ui;
        this.ctx = (ExtensionContext) Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(), new Class<?>[] {ExtensionContext.class},
            this::invoke);
        this.view = (ExtensionSessionView) Proxy.newProxyInstance(
            ExtensionSessionView.class.getClassLoader(), new Class<?>[] {ExtensionSessionView.class},
            this::invokeView);
    }

    /** 触发一个已注册命令（handler 捕获本代理 ctx）。 */
    void command(String name, String args) {
        var command = commands.get(name);
        if (command == null) {
            throw new AssertionError("command not registered: /" + name);
        }
        command.execute(args, SlashContext.of(null)).toCompletableFuture().join();
    }

    /** 按 spec 触发所有登记的事件处理器。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <E extends ExtensionEvent> Object[] fire(ExtensionEventSpec<E, ?> spec, E event) {
        var list = handlers.get(spec);
        if (list == null) {
            return new Object[0];
        }
        var results = new ArrayList<>();
        for (var h : list) {
            try {
                results.add(((ExtensionEventHandler) h).handle(event, ctx));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return results.toArray();
    }

    /** 登记一个事件处理器（模拟 ctx.on）。 */
    @SuppressWarnings("unchecked")
    <E extends ExtensionEvent, R> void on(ExtensionEventSpec<E, R> spec,
                                           ExtensionEventHandler<E, R> handler) {
        handlers.computeIfAbsent(spec, k -> new ArrayList<>()).add(handler);
    }

    private Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
            throws Exception {
        var name = method.getName();
        switch (name) {
            case "slashCommands": return commands;
            case "tools": return tools;
            case "ui": return ui;
            case "mode": return mode;
            case "hasUi": return !"print".equals(mode);
            case "sessionManager": return view;
            case "services": return null;
            case "setLabel":
                if (args[1] == null) {
                    labels.remove((String) args[0]);
                } else {
                    labels.put((String) args[0], (String) args[1]);
                }
                return null;
            case "setSessionName": sessionName = normalizeName((String) args[0]); return null;
            case "getSessionName": return sessionName;
            case "appendEntry":
                appended.add((String) args[0]);
                if (args.length > 1 && args[1] instanceof Map<?, ?> data) {
                    @SuppressWarnings("unchecked")
                    var typed = (Map<String, Object>) data;
                    appendedData.put((String) args[0], typed);
                }
                return null;
            case "registerMessageRenderer":
                messageRenderers.put((String) args[0],
                    (com.pijava.coding.agent.extension.ExtensionMessageRenderer) args[1]);
                return null;
            case "registerEntryRenderer":
                entryRenderers.put((String) args[0],
                    (com.pijava.coding.agent.extension.ExtensionEntryRenderer) args[1]);
                return null;
            case "getContextUsage": return contextUsage;
            case "sendMessage":
                sentMessages.add((String) args[0]);
                if (args[1] instanceof CustomMessageContent content) {
                    @SuppressWarnings("unchecked")
                    var details = args[3] instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : Map.<String, Object>of();
                    sentCustom.add(new SentCustomMessage((String) args[0],
                        content.plainText(), details));
                }
                return "msg-" + sentMessages.size();
            case "sendUserMessage":
                if (args[0] instanceof String s) {
                    sentMessages.add(s);
                } else if (args[0] instanceof CustomMessageContent cmc) {
                    sentMessages.add(String.valueOf(cmc));
                }
                return null;
            case "exec":
                var cmd = (String) args[0];
                @SuppressWarnings("unchecked") var argv = (List<String>) args[1];
                execLog.add(cmd + " " + String.join(" ", argv));
                var opts = args.length > 2 && args[2] instanceof ExtensionExec.ExecOptions eo ? eo : null;
                execCwdLog.add(opts == null || opts.cwd() == null ? "<null>" : opts.cwd());
                var result = defaultExecResult();
                if (execResultFn != null) {
                    result = execResultFn.apply(result);
                }
                return result;
            case "getCommands": return commandInfos;
            case "getActiveTools": return activeTools;
            case "setActiveTools": activeTools = new ArrayList<>((List<String>) args[0]); return null;
            case "getAllTools": return allTools;
            case "getFlag": return flagValue;
            case "registerFlag": return null;
            case "modelRegistry": return modelRegistry;
            case "getModel": return model;
            case "setModel": return setModelResult;
            case "getThinkingLevel": return thinkingLevel;
            case "setThinkingLevel": thinkingLevel = args[0]; return null;
            case "cwd": return cwd;
            case "registerToolRenderer": rendererCount++; return null;
            case "executeTool": return null;
            case "on":
                if (args != null && args.length == 2) {
                    on((ExtensionEventSpec<ExtensionEvent, Object>) args[0],
                       (ExtensionEventHandler<ExtensionEvent, Object>) args[1]);
                }
                return (AutoCloseable) () -> { };
            case "forExtension": return proxy;
            case "registerShortcut": return null;
            case "registerFlag3": return null;
            case "shutdown": shutdownCount++; return null;
            case "reload": shutdownCount++; return null;
            case "toString": return "W3Context";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == args[0];
            default: return defaultValue(method.getReturnType());
        }
    }

    private Object invokeView(Object proxy, java.lang.reflect.Method method, Object[] args) {
        var name = method.getName();
        switch (name) {
            case "getEntries": return entries;
            case "getBranch": return branch;
            case "getLeafEntry": return leafEntry;
            case "getLeafId": return leafEntry == null ? null : leafEntry.id();
            case "getLabel": return labels.get(args[0]);
            case "getEntry":
                var id = (String) args[0];
                return entries.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
            default: return defaultValue(method.getReturnType());
        }
    }

    private ExtensionExec.ExecResult defaultExecResult() {
        if (!execResults.isEmpty()) {
            return execResults.remove(0);
        }
        return new ExtensionExec.ExecResult("", "", 0, false, false);
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        var trimmed = name.replace("\n", "").replace("\r", "").trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
