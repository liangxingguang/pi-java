package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.AFTER_PROVIDER_RESPONSE;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.BEFORE_PROVIDER_HEADERS;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.BEFORE_PROVIDER_REQUEST;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.PROVIDER_STREAM_EVENT;

/**
 * docs/17 §6（T3）：会话级端到端 —— 扩展订阅四事件、prompt 驱动真 StreamFn
 * 链（DefaultProviders 注入 ⇒ 车道消费钩 ⇒ 事件回总线）。扩展经 TCCL 作用域
 * loader 注入（批 4/5/6 先例）。车道是消费钩的桩 provider（生产车道的钩读取
 * 面已在 ai 模块 wire 夹具逐车道对账）。
 */
class ProviderHooksSessionWiringTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    /** 桩车道实际发出的载荷（before_provider_request 替换臂的观测面）。 */
    static final AtomicReference<Object> WIRE_PAYLOAD = new AtomicReference<>();

    /** 探针扩展：录四个 provider 请求面事件；payload 钩做替换。 */
    public static final class Probe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Probe() {}

        @Override public String name() { return "provider-hooks-probe"; }

        @Override @SuppressWarnings("unchecked")
        public void register(ExtensionContext ctx) {
            ctx.on(BEFORE_PROVIDER_REQUEST, (e, c) -> {
                EVENTS.add("before_provider_request:" + e.payload());
                var copy = new LinkedHashMap<>((Map<String, Object>) e.payload());
                copy.put("injected", "by-extension");
                return copy;
            });
            ctx.on(BEFORE_PROVIDER_HEADERS, (e, c) -> {
                EVENTS.add("before_provider_headers:" + e.headers());
                e.headers().put("x-hook", "yes");
                return null;
            });
            ctx.on(AFTER_PROVIDER_RESPONSE, (e, c) -> {
                EVENTS.add("after_provider_response:" + e.status());
                return null;
            });
            ctx.on(PROVIDER_STREAM_EVENT, (e, c) -> {
                EVENTS.add("provider_stream_event:" + e.provider() + "/" + e.api()
                    + "/" + e.model() + ":" + e.data());
                return null;
            });
        }
    }

    /** 消费钩的桩车道（生产车道读取面同形：createApi 期读 extra 三钩）。 */
    private static final class HookLaneProvider implements Provider {
        @Override public String name() { return "hooklane"; }

        @Override public String displayName() { return "hooklane"; }

        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }

        @Override public <T extends ProviderApi> T createApi(Class<T> apiType,
                                                             ApiOptions options) {
            var payloadHook = ProviderHooks.payloadHookOf(options);
            var responseHook = ProviderHooks.responseHookOf(options);
            var streamHook = ProviderHooks.streamEventHookOf(options);
            // 头变换缝的落点观测面：extra["headers"] 应已含扩展注入头。
            EVENTS.add("lane-headers:" + options.extra().get("headers"));
            return apiType.cast(new ChatApi() {
                @Override public Flow.Publisher<StreamEvent> stream(StreamRequest request,
                                                                    ApiOptions o) {
                    throw new UnsupportedOperationException("测试只走 streamBlocking");
                }

                @Override public StreamIterator streamBlocking(StreamRequest request,
                                                               ApiOptions o) {
                    Object payload = new LinkedHashMap<>(
                        Map.of("model", request.modelId().modelName()));
                    if (payloadHook != null) {
                        var next = payloadHook.onPayload(payload, request.model());
                        if (next != null) {
                            payload = next;
                        }
                    }
                    WIRE_PAYLOAD.set(payload);
                    if (responseHook != null) {
                        responseHook.onResponse(200, Map.of("x-resp", "r"), request.model());
                    }
                    if (streamHook != null) {
                        streamHook.onEvent(Map.of("raw-chunk", 1), request.model());
                    }
                    var done = AssistantMessage.empty().withContent(List.of(
                        new ContentBlock.TextContent("ok"))).withStopReason(StopReason.STOP);
                    return StreamIterator.from(List.of(
                        new StreamEvent.Start(AssistantMessage.empty()),
                        new StreamEvent.StreamDone(StopReason.STOP, null, done)));
                }

                @Override public Message send(StreamRequest request, ApiOptions o) {
                    throw new UnsupportedOperationException("测试只走 streamBlocking");
                }
            });
        }

        @Override public ModelCatalog builtinModels() {
            var model = new ModelInfo(com.pijava.ai.model.ModelId.of("hooklane", "hook-model"),
                "hook-model", Set.of(), 200_000, 8192, false,
                PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
                Map.of("x-model", "m"), Map.of(), ModelCompat.NONE, "hook-api");
            return new ModelCatalog() {
                @Override public List<ModelInfo> listModels() { return List.of(model); }

                @Override public java.util.Optional<ModelInfo> find(
                        com.pijava.ai.model.ModelId<?> id) {
                    return model.id().equals(id)
                        ? java.util.Optional.of(model) : java.util.Optional.empty();
                }

                @Override public List<ModelInfo> search(String query) {
                    return query != null && query.contains("hook") ? List.of(model) : List.of();
                }
            };
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
        WIRE_PAYLOAD.set(null);
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Probe.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
    }

    @AfterEach
    void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousTccl);
    }

    private AgentSession session(String... argv) {
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        providers.register(new HookLaneProvider());
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    /** ① 端到端：prompt 后扩展看到四事件；payload 替换落进桩车道；头事件见到
     *  models.json 合并值且注入头落进 extra。 */
    @Test
    void promptFiresAllFourProviderEvents() {
        try (var s = session("--provider", "hooklane", "--model", "hook-model",
                "--session-id", "ph1")) {
            EVENTS.clear();
            s.processPrompt("hi", PromptConfig.defaults()).status(); // 阻塞到 run 终局

            assertThat(EVENTS).anyMatch(e -> e.startsWith("before_provider_request:"));
            assertThat(WIRE_PAYLOAD.get()).as("替换臂落线")
                .isEqualTo(Map.of("model", "hook-model", "injected", "by-extension"));
            assertThat(EVENTS).anyMatch(e ->
                e.startsWith("before_provider_headers:") && e.contains("x-model=m"));
            assertThat(EVENTS).anyMatch(e ->
                e.startsWith("lane-headers:") && e.contains("x-hook=yes"));
            assertThat(EVENTS).anyMatch(e -> e.equals("after_provider_response:200"));
            assertThat(EVENTS).anyMatch(e -> e.equals(
                "provider_stream_event:hooklane/hook-api/hook-model:{raw-chunk=1}"));
        }
    }

    /** ② --no-extensions ⇒ 桥永不安装 ⇒ 四事件零 firing（pi 空表同效）。 */
    @Test
    void noExtensionsMeansZeroFiring() {
        try (var s = session("--provider", "hooklane", "--model", "hook-model",
                "--session-id", "ph2", "--no-extensions")) {
            EVENTS.clear();
            s.processPrompt("hi", PromptConfig.defaults()).status(); // 阻塞到 run 终局

            assertThat(EVENTS).noneMatch(e -> e.startsWith("before_provider_request:"));
            assertThat(EVENTS).noneMatch(e -> e.startsWith("after_provider_response:"));
            assertThat(EVENTS).noneMatch(e -> e.startsWith("provider_stream_event:"));
            // 桩车道仍被调（请求照常），但载荷无注入。
            assertThat(WIRE_PAYLOAD.get()).isEqualTo(Map.of("model", "hook-model"));
        }
    }

    /** ③ fork 后新会话的桥独立生效（批 6 装配缝：fork 新桥新装）。 */
    @Test
    void forkedSessionHasIndependentBridge() {
        try (var s = session("--provider", "hooklane", "--model", "hook-model",
                "--session-id", "ph3")) {
            var copy = s.forkCopy("branch").orElseThrow();
            EVENTS.clear();
            copy.processPrompt("hi", PromptConfig.defaults()).status(); // 阻塞到 run 终局

            assertThat(EVENTS).anyMatch(e -> e.startsWith("before_provider_request:"));
            assertThat(WIRE_PAYLOAD.get()).as("fork 后替换臂仍落线")
                .isEqualTo(Map.of("model", "hook-model", "injected", "by-extension"));
        }
    }
}
