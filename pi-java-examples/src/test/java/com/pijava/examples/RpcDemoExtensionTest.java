package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.examples.rpc.RpcDemoExtension;

import org.junit.jupiter.api.Test;

/** docs/56 §2.3：rpc-demo 扩展验收——危险命令正则/拦截决策、3 command 注册、session_start UI。 */
class RpcDemoExtensionTest {

    @Test
    void dangerousCommandRegex() {
        assertThat(RpcDemoExtension.isDangerous("rm -rf /tmp")).isTrue();
        assertThat(RpcDemoExtension.isDangerous("rm -r folder")).isTrue();
        assertThat(RpcDemoExtension.isDangerous("sudo apt-get update")).isTrue();
        assertThat(RpcDemoExtension.isDangerous("echo hello")).isFalse();
        assertThat(RpcDemoExtension.isDangerous(null)).isFalse();
    }

    @Test
    void toolCallDecision() {
        assertThat(RpcDemoExtension.decide("echo hi", true, null).block()).isFalse();
        var noUi = RpcDemoExtension.decide("rm -rf x", false, null);
        assertThat(noUi.block()).isTrue();
        assertThat(noUi.reason()).isEqualTo("Dangerous command blocked (no UI)");
        var blocked = RpcDemoExtension.decide("sudo x", true, "Block");
        assertThat(blocked.reason()).isEqualTo("Blocked by user");
        assertThat(RpcDemoExtension.decide("sudo x", true, "Allow").block()).isFalse();
    }

    @Test
    void registersCommands() {
        var w = new W3Context();
        new RpcDemoExtension().register(w.ctx);
        assertThat(w.commands.names()).contains("rpc-input", "rpc-editor", "rpc-prefill");
    }

    @Test
    void sessionStartSetsTitle() {
        var w = new W3Context();
        new RpcDemoExtension().register(w.ctx);
        w.fire(ExtensionEventSpec.SESSION_START,
            new ExtensionEvent.SessionStartEvent("new", null));
        assertThat(w.ui.titles()).contains("pi RPC Demo (new session)");
    }
}
