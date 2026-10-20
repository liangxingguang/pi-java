package com.pijava.coding.agent.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.tool.ShellResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.CustomMessageDraft;
import com.pijava.coding.agent.extension.event.ExtensionResults.InputEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeForkResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeSwitchResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.BEFORE_AGENT_START;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.INPUT;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_FORK;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_SWITCH;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_SHUTDOWN;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.USER_BASH;

/**
 * docs/15 §3.3 宿主发射器单元测试——pi runner 发射语义逐字对拍：
 * 生命周期观察（:258-267）、before 族 cancel 短路（:1089-1118）、input 变换链
 * （:1520-1558）、user_bash 首发言者赢＋异常重抛（:1262-1296）、before_agent_start
 * 累积与链式覆盖（:1420-1472）。
 */
class SessionEventFirerTest {

    private ExtensionEventBus bus;
    private SessionEventFirer firer;
    private List<ExtensionError> errors;

    @BeforeEach
    void setUp() {
        bus = new ExtensionEventBus();
        errors = new ArrayList<>();
        bus.onError(errors::add);
        // firer 只把 ctx 透传给处理器——本组用例不读 ctx，传 null 即可。
        firer = new SessionEventFirer(bus, null);
    }

    // ── 生命周期 ──

    @Test
    void lifecycleEventsCarryPayloadsAndFastPathWithoutHandlers() {
        firer.sessionStart("startup", null); // 无订阅者：无效果、不抛
        firer.sessionShutdown("quit", null);
        assertThat(errors).isEmpty();

        var seen = new ArrayList<String>();
        bus.on("ext", SESSION_START, (e, c) -> {
            seen.add("start:" + e.reason() + ":" + e.previousSessionFile());
            return null;
        });
        bus.on("ext", SESSION_SHUTDOWN, (e, c) -> {
            seen.add("shutdown:" + e.reason() + ":" + e.targetSessionFile());
            return null;
        });
        firer.sessionStart("resume", "prev-id");
        firer.sessionShutdown("resume", "target-id");
        assertThat(seen).containsExactly("start:resume:prev-id", "shutdown:resume:target-id");
    }

    // ── before 族 cancel 短路 ──

    @Test
    void beforeSwitchCancelShortCircuitsAndErrorsAreReported() {
        var ran = new ArrayList<String>();
        bus.on("bad", SESSION_BEFORE_SWITCH, (e, c) -> {
            ran.add("bad");
            throw new IllegalStateException("boom");
        });
        bus.on("canceler", SESSION_BEFORE_SWITCH,
            (e, c) -> new SessionBeforeSwitchResult(true));
        bus.on("late", SESSION_BEFORE_SWITCH, (e, c) -> {
            ran.add("late");
            return null;
        });

        assertThat(firer.sessionBeforeSwitch("resume", "t")).isTrue();
        // 抛错的被上报、cancel 短路后续处理器（pi :1100-1104）。
        assertThat(ran).containsExactly("bad");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).extensionName()).isEqualTo("bad");
    }

    @Test
    void beforeForkNoCancelReturnsFalse() {
        bus.on("ext", SESSION_BEFORE_FORK,
            (e, c) -> new SessionBeforeForkResult(null, null));
        assertThat(firer.sessionBeforeFork("e1", "before")).isFalse();
        bus.on("ext2", SESSION_BEFORE_FORK,
            (e, c) -> new SessionBeforeForkResult(false, null));
        assertThat(firer.sessionBeforeFork("e1", "at")).isFalse();
        assertThat(firer.sessionBeforeSwitch("new", null)).isFalse(); // 无订阅者
    }

    // ── input 变换链 ──

    @Test
    void inputTransformChainsAndHandledShortCircuits() {
        var seenBySecond = new ArrayList<String>();
        bus.on("h1", INPUT, (e, c) -> new InputEventResult.Transform(e.text() + "-one", null));
        bus.on("h2", INPUT, (e, c) -> {
            seenBySecond.add(e.text());
            return new InputEventResult.Transform(e.text() + "-two", null);
        });
        var outcome = firer.input("base", "rpc", "steer");
        assertThat(outcome.handled()).isFalse();
        assertThat(outcome.text()).isEqualTo("base-one-two");
        assertThat(seenBySecond).containsExactly("base-one"); // pi 的链式 currentText

        bus.on("h3", INPUT, (e, c) -> new InputEventResult.Handled());
        var handled = firer.input("base", "interactive", null);
        assertThat(handled.handled()).isTrue();

        var plain = new SessionEventFirer(new ExtensionEventBus(), null);
        assertThat(plain.input("x", "interactive", null).text()).isEqualTo("x");
    }

    // ── user_bash ──

    @Test
    void userBashFirstSpeakerWinsAndErrorRethrows() {
        var bash = new ShellResult("ext-output", 0, false, false, 1, 10);
        bus.on("first", USER_BASH, (e, c) -> new UserBashEventResult(bash));
        bus.on("second", USER_BASH, (e, c) -> {
            throw new AssertionError("must not run"); // pi :1273-1278 首发言者赢
        });
        Optional<ShellResult> result = firer.userBash("ls", false, "/tmp");
        assertThat(result).containsSame(bash);

        var bus2 = new ExtensionEventBus();
        var errors2 = new ArrayList<ExtensionError>();
        bus2.onError(errors2::add);
        bus2.on("bad", USER_BASH, (e, c) -> {
            throw new IllegalStateException("handler blew up");
        });
        // pi :1283-1290：emitError 后重抛——调用方不得回落本地执行。
        assertThatThrownBy(() -> new SessionEventFirer(bus2, null).userBash("ls", true, "/tmp"))
            .isInstanceOf(RuntimeException.class).hasMessage("handler blew up");
        assertThat(errors2).hasSize(1);
    }

    // ── before_agent_start ──

    @Test
    void beforeAgentStartAccumulatesMessagesAndChainsSystemPrompt() {
        var seenPrompts = new ArrayList<String>();
        bus.on("h1", BEFORE_AGENT_START, (e, c) -> {
            seenPrompts.add(e.systemPrompt());
            return new BeforeAgentStartEventResult(
                new CustomMessageDraft("note", null, true, Map.of()), "override-one");
        });
        bus.on("h2", BEFORE_AGENT_START, (e, c) -> {
            seenPrompts.add(e.systemPrompt());
            return new BeforeAgentStartEventResult(null, "override-two");
        });
        var outcome = firer.beforeAgentStart("prompt text", () -> "base-prompt");

        assertThat(outcome.messages()).hasSize(1);
        assertThat(outcome.messages().get(0).customType()).isEqualTo("note");
        assertThat(outcome.systemPrompt()).isEqualTo("override-two");
        // pi：systemPrompt 链式——后处理器看到前处理器的改写值。
        assertThat(seenPrompts).containsExactly("base-prompt", "override-one");
    }
}
