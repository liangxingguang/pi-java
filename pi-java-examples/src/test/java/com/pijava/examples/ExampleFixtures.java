package com.pijava.examples;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.extension.ExtensionUI;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * 批 28（docs/40）examples 验收测试共享夹具。
 *
 * <p>TCCL 单件装载（批 27 两钉：classpath 根＝META-INF 祖父；扩展类顶层 public）＋
 * <b>父链过滤</b>：examples 主资源的 services 全量名单在父 classpath 上，不过滤则
 * 每个测试都装上全部移植件（逐件隔离即失效）——滤掉的只有 examples 模块自己
 * （target/classes 根前缀匹配），coding-agent 内置件（tool-search 等）照常发现。</p>
 */
final class ExampleFixtures {

    private static final String SERVICES_NAME =
        "META-INF/services/com.pijava.coding.agent.extension.PiExtension";

    private ExampleFixtures() {}

    /**
     * 把 TCCL 换成「只发现指定扩展」的作用域 loader；返回恢复闭包（try-with-resources 用）。
     */
    static AutoCloseable scopeExtensions(Path tmp, Class<? extends PiExtension>... extensions)
            throws Exception {
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        var names = new StringBuilder();
        for (var ext : extensions) {
            names.append(ext.getName()).append('\n');
        }
        Files.writeString(servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            names.toString());
        Path testClasses = Path.of(ExampleFixtures.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        var examplesRoot = Path.of(HelloExtension.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toUri().toURL().toString();
        var previous = Thread.currentThread().getContextClassLoader();
        var loader = new URLClassLoader(
            new URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()}, previous) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (!SERVICES_NAME.equals(name)) {
                    return super.getResources(name);
                }
                var urls = new ArrayList<URL>();
                Collections.list(findResources(name)).forEach(urls::add);
                for (var url : Collections.list(super.getResources(name))) {
                    if (!url.toString().startsWith(examplesRoot)) {
                        urls.add(url);
                    }
                }
                return Collections.enumeration(urls);
            }
        };
        Thread.currentThread().setContextClassLoader(loader);
        return () -> Thread.currentThread().setContextClassLoader(previous);
    }

    /** 标准会话：faux 单 provider＋{@code --no-session --no-mcp}＋cwd=tmp（防真 home 继承坑）。 */
    static AgentSession newSession(Path tmp, Provider provider) {
        return newSession(tmp, provider, "hello");
    }

    /** {@link #newSession(Path, Provider)} 的指定模型形。 */
    static AgentSession newSession(Path tmp, Provider provider, String model) {
        var providers = ProviderRegistry.create();
        providers.register(provider);
        var args = ArgsParser.parse(new String[] {
            "--provider", provider.name(), "--model", model,
            "--no-session", "--no-mcp"});
        return AgentSession.create(args, providers, new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    /** 直接线夹具（ProjectTrustResolverTest 形）：bus＋context（脚本 UI/模式）＋firer。 */
    record Wired(com.pijava.coding.agent.extension.ExtensionEventBus bus,
                 com.pijava.coding.agent.extension.DefaultExtensionContext context,
                 com.pijava.coding.agent.extension.SessionEventFirer firer) {}

    /**
     * 把扩展注册进直接线夹具（per-extension 上下文对位生产装配）。{@code ui} 为
     * {@code null} ⇒ NOOP（hasUi=false 臂）。
     */
    static Wired wireWith(ExtensionUI ui, String mode, PiExtension... extensions) {
        var bus = new com.pijava.coding.agent.extension.ExtensionEventBus();
        var context = new com.pijava.coding.agent.extension.DefaultExtensionContext(
            null, new com.pijava.agent.skill.SkillManager(),
            () -> ui, () -> mode, bus);
        for (var ext : extensions) {
            ext.register(context.forExtension(ext.name()));
        }
        return new Wired(bus, context,
            new com.pijava.coding.agent.extension.SessionEventFirer(bus, context));
    }

    /** 提取 content 块列表的全文本（TextContent 拼接）。 */
    static String textOf(List<ContentBlock> content) {
        var text = new StringBuilder();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }

    /** 文本应答 faux（{@code "done"} 单段）。 */
    static FauxProvider textProvider(String name, String text) {
        var msg = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        return FauxProvider.sequence(name, List.of(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, msg),
            new StreamEvent.TextEnd(0, text, msg),
            new StreamEvent.StreamDone(StopReason.STOP, null, msg))));
    }

    /** 「工具调用 ⇒ 文本收尾」两响应序列 faux。 */
    static FauxProvider toolThenDone(String name, String callId, String toolName,
                                     Map<String, Object> args) {
        var toolTurn = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(callId, toolName, args)))
            .withStopReason(StopReason.TOOL_USE);
        return FauxProvider.sequence(name, List.of(
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.ToolCallStart(0, AssistantMessage.empty()),
                new StreamEvent.ToolCallEnd(0, callId, toolName, args, toolTurn),
                new StreamEvent.StreamDone(StopReason.TOOL_USE, null, toolTurn)),
            textEvents("done")));
    }

    /** 单段文本事件列（toolThenDone 的第二响应；终止断言用单列）。 */
    static List<StreamEvent> textEvents(String text) {
        var msg = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, msg),
            new StreamEvent.TextEnd(0, text, msg),
            new StreamEvent.StreamDone(StopReason.STOP, null, msg));
    }

    /**
     * 假 ExtensionUI（docs/40 裁决⑦）：select/confirm/input 脚本化应答（按调用序弹出），
     * notify 录表（message＋type）。注入 {@code session.extensionUI(ui, "tui")} 后
     * {@code hasUi()} 为 true。
     */
    static final class ScriptedUi implements ExtensionUI {

        /** 通知录表（message, type）。 */
        record Notice(String message, NotifyType type) {}

        /** 取消哨兵（ArrayDeque 不收 null）。 */
        private static final Object CANCEL = new Object();

        private final Deque<Object> answers = new ArrayDeque<>();
        private final List<Notice> notices = new ArrayList<>();

        /** 脚本化一次 select/input 应答（null ⇒ 取消）。 */
        ScriptedUi answer(Object value) {
            answers.add(value == null ? CANCEL : value);
            return this;
        }

        /** 脚本化一次 confirm 应答。 */
        ScriptedUi confirmAnswer(boolean value) {
            answers.add(value);
            return this;
        }

        List<Notice> notices() {
            return notices;
        }

        private Object poll() {
            var value = answers.poll();
            return value == CANCEL ? null : value;
        }

        @Override
        public String select(String title, List<String> options,
                             com.pijava.coding.agent.extension.ExtensionUiDialogOptions opts) {
            return (String) poll();
        }

        @Override
        public boolean confirm(String title, String message,
                               com.pijava.coding.agent.extension.ExtensionUiDialogOptions opts) {
            return Boolean.TRUE.equals(poll());
        }

        @Override
        public String input(String title, String placeholder,
                            com.pijava.coding.agent.extension.ExtensionUiDialogOptions opts) {
            return (String) poll();
        }

        @Override
        public void notify(String message, NotifyType type) {
            notices.add(new Notice(message, type));
        }
    }
}
