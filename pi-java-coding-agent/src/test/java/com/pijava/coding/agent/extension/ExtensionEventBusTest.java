package com.pijava.coding.agent.extension;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.session.memory.MemorySessionRepository;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.TrustManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.extension.event.ExtensionEvent.AgentStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventHandler;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/14 §7.1：{@link ExtensionEventBus} —— pi loader.ts:272-288（订阅/退订）＋
 * runner.ts:269-271（序）＋:742-746（emitError）＋:765-773（hasHandlers）的逐字情形。
 */
class ExtensionEventBusTest {

    private static final ExtensionEventSpec<AgentStartEvent, Void> SPEC =
        ExtensionEventSpec.AGENT_START;
    private static final AgentStartEvent EVENT = new AgentStartEvent();

    private static ExtensionEventHandler<AgentStartEvent, Void> recording(
            List<String> log, String tag) {
        return (event, ctx) -> {
            log.add(tag);
            return null;
        };
    }

    /** 注册序即触发序（pi：扩展加载序 × 扩展内注册序 ⇒ java 全局追加序同构）。 */
    @Test
    void handlersFireInRegistrationOrder() {
        var bus = new ExtensionEventBus();
        var log = new ArrayList<String>();
        bus.on("ext-a", SPEC, recording(log, "a1"));
        bus.on("ext-a", SPEC, recording(log, "a2"));
        bus.on("ext-b", SPEC, recording(log, "b1"));

        for (var h : bus.snapshot(SPEC)) {
            try {
                h.handle(EVENT, null);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        assertThat(log).containsExactly("a1", "a2", "b1");
    }

    /** 退订按实例移除（pi :278-286）；全退空表删键 ⇒ hasHandlers 翻假（pi :286）。 */
    @Test
    void unsubscribeRemovesByInstanceAndDeletesEmptyKey() throws Exception {
        var bus = new ExtensionEventBus();
        var log = new ArrayList<String>();
        var first = bus.on("ext-a", SPEC, recording(log, "a1"));
        var second = bus.on("ext-a", SPEC, recording(log, "a2"));

        assertThat(bus.hasHandlers(SPEC)).isTrue();
        first.close();

        bus.snapshot(SPEC).forEach(h -> {
            try {
                h.handle(EVENT, null);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertThat(log).containsExactly("a2");

        // 重复退订是 noop（pi 的 indexOf === -1 分支）
        first.close();
        assertThat(bus.hasHandlers(SPEC)).isTrue();

        // 最后一个退掉 ⇒ 空表删键
        second.close();
        assertThat(bus.hasHandlers(SPEC)).isFalse();
        assertThat(bus.snapshot(SPEC)).isEmpty();
    }

    /** 同一 handler 实例注册两次 ⇒ 各火一次（pi 每次 on() 包一层新函数）。 */
    @Test
    void theSameHandlerRegisteredTwiceFiresTwice() throws Exception {
        var bus = new ExtensionEventBus();
        var log = new ArrayList<String>();
        ExtensionEventHandler<AgentStartEvent, Void> shared = recording(log, "s");
        var one = bus.on("ext-a", SPEC, shared);
        bus.on("ext-a", SPEC, shared);

        bus.snapshot(SPEC).forEach(h -> {
            try {
                h.handle(EVENT, null);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertThat(log).containsExactly("s", "s");

        // 退掉一次 ⇒ 还剩一次（pi 的 indexOf 只删首个）
        one.close();
        assertThat(bus.snapshot(SPEC)).hasSize(1);
    }

    /** 空表 ⇒ hasHandlers 假、snapshot 空。 */
    @Test
    void emptyBusHasNoHandlers() {
        var bus = new ExtensionEventBus();
        assertThat(bus.hasHandlers(SPEC)).isFalse();
        assertThat(bus.snapshot(SPEC)).isEmpty();
    }

    /** 错误按扩展名归属：两扩展各订同名事件，一个抛错 ⇒ 错误对象归属正确、另一个照跑。 */
    @Test
    void errorsAreAttributedToTheThrowingExtension() {
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(errors::add);
        var log = new ArrayList<String>();
        bus.on("ext-bad", SPEC, (event, ctx) -> {
            throw new IllegalStateException("boom");
        });
        bus.on("ext-good", SPEC, recording(log, "good"));

        // 触发纪律是桥的事（逐处理器 catch → emitError → 继续），这里按桥的跑法驱动：
        for (var h : bus.snapshot(SPEC)) {
            try {
                h.handle(EVENT, null);
            } catch (Exception e) {
                bus.emitError(h.error(e));
            }
        }

        assertThat(log).containsExactly("good");
        assertThat(errors).hasSize(1);
        var error = errors.get(0);
        assertThat(error.extensionName()).isEqualTo("ext-bad");
        assertThat(error.event()).isEqualTo("agent_start");
        assertThat(error.message()).isEqualTo("boom");
        assertThat(error.stack()).contains("IllegalStateException");
    }

    /** emitError 不进事件流、走独立 listener；listener 抛错被隔离。 */
    @Test
    void emitErrorFansOutToListenersAndIsolatesThrowers() {
        var bus = new ExtensionEventBus();
        var errors = new ArrayList<ExtensionError>();
        bus.onError(err -> {
            throw new RuntimeException("listener boom");
        });
        bus.onError(errors::add);

        var error = new ExtensionError("ext", "agent_start", "m", null);
        bus.emitError(error);

        assertThat(errors).containsExactly(error);
    }

    // ═══ per-extension 上下文（DefaultExtensionContext.forExtension）═══

    /** 扩展经 forExtension 订阅 ⇒ 归属扩展名；共享基础上下文的 on() 抛（pi assertActive）。 */
    @Test
    void perExtensionContextTagsSubscriptionsWithTheExtensionName() {
        var bus = new ExtensionEventBus();
        var base = context(bus);
        var extCtx = base.forExtension("my-ext");

        assertThatThrownBy(() -> base.on(SPEC, recording(new ArrayList<>(), "x")))
            .isInstanceOf(IllegalStateException.class);

        extCtx.on(SPEC, recording(new ArrayList<>(), "x"));

        var snapshot = bus.snapshot(SPEC);
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).extensionName()).isEqualTo("my-ext");
    }

    private static DefaultExtensionContext context(ExtensionEventBus bus) {
        var providers = ProviderRegistry.create();
        var services = new SessionServices(
            SettingsManager.load(null),
            new TrustManager("none"),
            providers,
            new DefaultModelResolver(ProviderCatalog.allModels()),
            new ToolRegistry(null), CommandRegistry.withBuiltins(),
            new MemorySessionRepository(),
            new PromptTemplateRegistry());
        return new DefaultExtensionContext(services, new SkillManager(), null, bus);
    }
}
