package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/55 §2.1：notify 验收——agent_settled 通知、OSC 转义逐字、toast 脚本、路由。 */
class NotifyExtensionTest {

    @Test
    void agentSettledNotifies() {
        var w = new W3Context();
        new NotifyExtension().register(w.ctx);
        w.fire(ExtensionEventSpec.AGENT_SETTLED, null);
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Ready for input");
        assertThat(w.ui.notices().get(0).type()).isEqualTo(NotifyType.INFO);
    }

    @Test
    void osc777Verbatim() {
        assertThat(NotifyExtension.osc777("Pi", "Ready for input"))
            .isEqualTo("\u001b]777;notify;Pi;Ready for input\u0007");
    }

    @Test
    void osc99Verbatim() {
        assertThat(NotifyExtension.osc99("Pi", "Body"))
            .containsExactly(
                "\u001b]99;i=1:d=0;Pi\u001b\\",
                "\u001b]99;i=1:p=body;Body\u001b\\");
    }

    @Test
    void windowsToastScriptVerbatim() {
        var script = NotifyExtension.windowsToastScript("Pi", "Ready");
        assertThat(script).contains("[Windows.UI.Notifications.ToastNotificationManager, "
                + "Windows.UI.Notifications, ContentType = WindowsRuntime] > $null")
            .contains("GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText01)")
            .contains("AppendChild($xml.CreateTextNode('Ready')) > $null")
            .contains("CreateToastNotifier('Pi').Show([Windows.UI.Notifications.ToastNotification]::new($xml))");
        assertThat(script).contains("; ");
    }

    @Test
    void dispatchRoute() {
        assertThat(NotifyExtension.dispatchRoute("", "")).isZero();           // osc777
        assertThat(NotifyExtension.dispatchRoute("", "123")).isEqualTo(1);    // osc99
        assertThat(NotifyExtension.dispatchRoute("wt-session", "123")).isEqualTo(2); // windows 优先
    }
}
