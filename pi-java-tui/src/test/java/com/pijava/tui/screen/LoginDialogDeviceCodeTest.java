package com.pijava.tui.screen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.auth.OAuthEvent;
import com.pijava.coding.agent.core.LoginAuthType;
import com.pijava.coding.agent.core.LoginProviderOption;
import com.pijava.tui.util.ClipboardSupport;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.ElementRegistry;
import dev.tamboui.toolkit.event.EventRouter;
import dev.tamboui.toolkit.focus.FocusManager;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/19 #45：TUI 登录对话框的 device-code 展示与「确认后打开浏览器／复制代码」。
 *
 * <p>两个动作都只消费一次（pi {@code login-dialog.ts:331-341}），失败不打断登录。</p>
 */
class LoginDialogDeviceCodeTest {

    private static final String URL = "https://example.com/device";
    private static final String CODE = "ABCD-EFGH";

    @AfterEach
    void resetOverrides() {
        ClipboardSupport.overrideForTest(null);
        LoginDialogScreen.overrideBrowserForTest(null);
    }

    @Test
    void rendersDeviceDetailsAndConfirmHint() throws Exception {
        var dialog = dialog();
        dialog.notify(new OAuthEvent.DeviceCode(CODE, URL, 1L, 60L));

        var text = render(dialog);
        assertThat(text).contains(URL)
            .contains("Enter code: " + CODE)
            .contains("to open browser and copy code");
    }

    @Test
    void enterCopiesCodeAndOpensBrowserExactlyOnce() throws Exception {
        var copied = new ArrayList<String>();
        var opened = new ArrayList<String>();
        ClipboardSupport.overrideForTest(copied::add);
        LoginDialogScreen.overrideBrowserForTest(opened::add);
        var dialog = dialog();
        dialog.notify(new OAuthEvent.DeviceCode(CODE, URL, 1L, 60L));

        dialog.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        dialog.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(copied).containsExactly(CODE);
        assertThat(opened).containsExactly(URL);
        assertThat(render(dialog)).contains("Code copied to clipboard")
            .doesNotContain("to open browser and copy code");
    }

    @Test
    void onlyConfirmKeyTriggersActions() throws Exception {
        var copied = new ArrayList<String>();
        var opened = new ArrayList<String>();
        ClipboardSupport.overrideForTest(copied::add);
        LoginDialogScreen.overrideBrowserForTest(opened::add);
        var dialog = dialog();
        dialog.notify(new OAuthEvent.DeviceCode(CODE, URL, 1L, 60L));

        dialog.onKeyEvent(KeyEvent.ofChar('x'));
        dialog.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));

        assertThat(copied).isEmpty();
        assertThat(opened).isEmpty();
        assertThat(render(dialog)).contains("to open browser and copy code");

        dialog.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(copied).containsExactly(CODE);
        assertThat(opened).containsExactly(URL);
    }

    @Test
    void clipboardFailureShowsManualInstructionWithoutBlockingBrowser() throws Exception {
        var opened = new ArrayList<String>();
        ClipboardSupport.overrideForTest(text -> {
            throw new IOException("clipboard unavailable");
        });
        LoginDialogScreen.overrideBrowserForTest(opened::add);
        var dialog = dialog();
        dialog.notify(new OAuthEvent.DeviceCode(CODE, URL, 1L, 60L));

        dialog.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(opened).containsExactly(URL);
        assertThat(render(dialog)).contains("Copy code manually");
    }

    @Test
    void browserFailureDoesNotInterruptLogin() throws Exception {
        var copied = new ArrayList<String>();
        ClipboardSupport.overrideForTest(copied::add);
        LoginDialogScreen.overrideBrowserForTest(url -> {
            throw new IOException("browser unavailable");
        });
        var dialog = dialog();
        dialog.notify(new OAuthEvent.DeviceCode(CODE, URL, 1L, 60L));

        dialog.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(copied).containsExactly(CODE);
        assertThat(dialog.isDone()).isFalse();
    }

    @Test
    void bothActionsDisabledShowsNoHintAndIgnoresEnter() throws Exception {
        var copied = new ArrayList<String>();
        var opened = new ArrayList<String>();
        ClipboardSupport.overrideForTest(copied::add);
        LoginDialogScreen.overrideBrowserForTest(opened::add);
        var dialog = dialog();
        dialog.notify(new OAuthEvent.DeviceCode(CODE, URL, false, false, 1L, 60L));

        assertThat(render(dialog)).contains("Enter code: " + CODE)
            .doesNotContain("to open browser");

        dialog.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(copied).isEmpty();
        assertThat(opened).isEmpty();
    }

    @Test
    void respectsIndividualActionSwitches() throws Exception {
        var copied = new ArrayList<String>();
        var opened = new ArrayList<String>();
        ClipboardSupport.overrideForTest(copied::add);
        LoginDialogScreen.overrideBrowserForTest(opened::add);

        var copyOnly = dialog();
        copyOnly.notify(new OAuthEvent.DeviceCode(CODE, URL, false, true, 1L, 60L));
        assertThat(render(copyOnly)).contains("to copy code").doesNotContain("to open browser");
        copyOnly.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(copied).containsExactly(CODE);
        assertThat(opened).isEmpty();

        copied.clear();
        var browserOnly = dialog();
        browserOnly.notify(new OAuthEvent.DeviceCode(CODE, URL, true, false, 1L, 60L));
        assertThat(render(browserOnly)).contains("to open browser").doesNotContain("to copy code");
        browserOnly.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(opened).containsExactly(URL);
        assertThat(copied).isEmpty();
    }

    private static LoginDialogScreen dialog() {
        var option = new LoginProviderOption(
            "github-copilot", "GitHub Copilot", LoginAuthType.OAUTH, null, false);
        return new LoginDialogScreen(option, Runnable::run, () -> {});
    }

    private static String render(LoginDialogScreen dialog) throws Exception {
        return String.join("\n", renderText(dialog.render(), 120, 20));
    }

    private static List<String> renderText(Element element, int width, int height)
            throws Exception {
        var buffer = Buffer.empty(new Rect(0, 0, width, height));
        var frame = Frame.forTesting(buffer);
        var focus = new FocusManager();
        markRenderThread(true);
        try {
            element.render(frame, new Rect(0, 0, width, height),
                new DefaultRenderContext(focus, new EventRouter(focus, new ElementRegistry())));
        } finally {
            markRenderThread(false);
        }
        var rows = new ArrayList<String>();
        for (int y = 0; y < height; y++) {
            var row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                var cell = buffer.get(x, y);
                if (!cell.isContinuation()) {
                    row.append(cell.symbol());
                }
            }
            rows.add(row.toString().stripTrailing());
        }
        return rows;
    }

    /** TamboUI asserts element registration happens on the render thread. */
    private static void markRenderThread(boolean on) throws Exception {
        var method = dev.tamboui.tui.RenderThread.class.getDeclaredMethod(
            on ? "markAsRenderThread" : "clearRenderThread");
        method.setAccessible(true);
        method.invoke(null);
    }
}
