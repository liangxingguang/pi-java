package com.pijava.examples;

import java.util.List;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/notify.ts:1-58} 移植（docs/55 §2.1）——
 * agent_settled 后发桌面通知。OSC 777/99 与 Windows toast 拼装为纯函数逐字断言；
 * 终端 OSC 写出通道 TUI 缺席（D245），实际通知降级为 ui.notify 聊天屏文本。
 */
public final class NotifyExtension implements PiExtension {

    @Override
    public String name() {
        return "notify";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.AGENT_SETTLED, (event, c) -> {
            c.ui().notify("Ready for input", NotifyType.INFO);
            return null;
        });
    }

    /** pi L26-28：OSC 777（单条，BEL 终止）。 */
    static String osc777(String title, String body) {
        return "\u001b]777;notify;" + title + ";" + body + "\u0007";
    }

    /** pi L30-34：OSC 99（两条，ST 终止）。 */
    static List<String> osc99(String title, String body) {
        return List.of(
            "\u001b]99;i=1:d=0;" + title + "\u001b\\",
            "\u001b]99;i=1:p=body;" + body + "\u001b\\");
    }

    /** pi L13-24：Windows toast PowerShell 脚本（逐字，"; " 连接 4 行）。 */
    static String windowsToastScript(String title, String body) {
        var type = "Windows.UI.Notifications";
        var mgr = "[" + type + ".ToastNotificationManager, " + type + ", ContentType = WindowsRuntime]";
        var template = "[" + type + ".ToastTemplateType]::ToastText01";
        var toast = "[" + type + ".ToastNotification]::new($xml)";
        return String.join("; ",
            mgr + " > $null",
            "$xml = [" + type + ".ToastNotificationManager]::GetTemplateContent(" + template + ")",
            "$xml.GetElementsByTagName('text')[0].AppendChild($xml.CreateTextNode('" + body + "')) > $null",
            "[" + type + ".ToastNotificationManager]::CreateToastNotifier('" + title + "').Show(" + toast + ")");
    }

    /** pi L41-49：dispatch 路由（0=osc777 / 1=osc99 / 2=windows）。 */
    static int dispatchRoute(String wtSession, String kittyWindowId) {
        if (wtSession != null && !wtSession.isEmpty()) {
            return 2;
        }
        if (kittyWindowId != null && !kittyWindowId.isEmpty()) {
            return 1;
        }
        return 0;
    }
}
