package com.pijava.tui.screen;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.pijava.ai.auth.OAuthEvent;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.LoginInteraction;
import com.pijava.coding.agent.core.LoginProviderOption;
import com.pijava.tui.component.EditorComponent;
import com.pijava.tui.util.ClipboardSupport;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/** Text-level OAuth/API-key dialog with masked secrets and cancellable prompts. */
public final class LoginDialogScreen implements ScreenOverlay, LoginInteraction {

    /** docs/19 #45：浏览器打开注入点（测试不碰真实桌面）。 */
    @FunctionalInterface
    interface BrowserOpener {
        void open(String url) throws Exception;
    }

    private static volatile BrowserOpener browserOpener = LoginDialogScreen::openDesktop;

    /** 测试替换点：{@code null} 恢复 AWT 默认。 */
    static void overrideBrowserForTest(BrowserOpener opener) {
        browserOpener = opener == null ? LoginDialogScreen::openDesktop : opener;
    }

    private static void openDesktop(String url) throws Exception {
        if (Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url));
        }
    }

    private final LoginProviderOption option;
    private final Consumer<Runnable> dispatcher;
    private final Runnable onCancel;
    private final EditorComponent editor = new EditorComponent();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private CompletableFuture<String> pending;
    private List<LoginOption> selectOptions = List.of();
    private int selected;
    private boolean inputActive;
    private boolean inputMasked;
    private boolean selectionActive;
    private boolean done;
    private boolean cancelled;
    private OAuthEvent.DeviceCode deviceCode;
    private OAuthEvent.DeviceCode deviceCodeAction;
    private String deviceCodeStatus;

    /** Create a dialog; asynchronous state changes are marshalled through dispatcher. */
    public LoginDialogScreen(LoginProviderOption option,
                             Consumer<Runnable> dispatcher, Runnable onCancel) {
        this.option = option;
        this.dispatcher = dispatcher;
        this.onCancel = onCancel;
    }

    @Override
    public void notify(String message) {
        dispatcher.accept(() -> messages.add(message));
    }

    @Override
    public boolean handlesStructuredEvents() {
        return true;
    }

    @Override
    public void notify(OAuthEvent event) {
        if (event instanceof OAuthEvent.DeviceCode device) {
            dispatcher.accept(() -> {
                synchronized (this) {
                    deviceCode = device;
                    deviceCodeStatus = null;
                    deviceCodeAction = device.openBrowserOnConfirm() || device.copyCodeOnConfirm()
                        ? device : null;
                }
            });
            return;
        }
        notify(event.legacyText());
    }

    @Override
    public String prompt(String message) {
        return requestInput(message, false);
    }

    @Override
    public String secret(String message) {
        return requestInput(message, true);
    }

    @Override
    public String select(String message, List<LoginOption> options) {
        var answer = new CompletableFuture<String>();
        dispatcher.accept(() -> {
            synchronized (this) {
                if (cancelled) {
                    answer.completeExceptionally(new CancellationException("Login cancelled"));
                    return;
                }
                messages.add(message);
                pending = answer;
                selectOptions = List.copyOf(options);
                selected = 0;
                selectionActive = true;
                inputActive = false;
                // pi clearDeviceCodeAction（login-dialog.ts:130）：换到选择/输入时清空待确认事件。
                deviceCodeAction = null;
                deviceCodeStatus = null;
            }
        });
        return await(answer);
    }

    private String requestInput(String message, boolean masked) {
        var answer = new CompletableFuture<String>();
        dispatcher.accept(() -> {
            synchronized (this) {
                if (cancelled) {
                    answer.completeExceptionally(new CancellationException("Login cancelled"));
                    return;
                }
                messages.add(message);
                editor.setText("");
                pending = answer;
                inputMasked = masked;
                inputActive = true;
                selectionActive = false;
                deviceCodeAction = null;
                deviceCodeStatus = null;
            }
        });
        return await(answer);
    }

    private static String await(CompletableFuture<String> answer) {
        try {
            return answer.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof CancellationException cancelled) {
                throw cancelled;
            }
            throw e;
        }
    }

    @Override
    public synchronized boolean onKeyEvent(KeyEvent event) {
        if (done) {
            return true;
        }
        if (event.isCancel() || event.isCtrlC()) {
            cancelled = true;
            done = true;
            if (pending != null) {
                pending.completeExceptionally(new CancellationException("Login cancelled"));
            }
            return true;
        }
        if (deviceCodeAction != null && event.isConfirm()) {
            // pi login-dialog.ts:331-341：只认 confirm 键，且只消费一次。
            activateDeviceCode();
            return true;
        }
        if (selectionActive) {
            if (event.isUp()) {
                selected = Math.max(0, selected - 1);
            } else if (event.isDown()) {
                selected = Math.min(selectOptions.size() - 1, selected + 1);
            } else if (event.isConfirm() && !selectOptions.isEmpty()) {
                complete(selectOptions.get(selected).id());
            }
            return true;
        }
        if (inputActive) {
            if (event.isConfirm()) {
                complete(editor.getText());
            } else {
                editor.onKeyEvent(event);
            }
            return true;
        }
        return true;
    }

    /** 用户确认后的两个便利动作；任一失败都不打断登录。 */
    private void activateDeviceCode() {
        var action = deviceCodeAction;
        deviceCodeAction = null;
        if (action == null) {
            return;
        }
        var status = action.copyCodeOnConfirm() ? "Copying code..." : "Browser open requested";
        if (action.copyCodeOnConfirm()) {
            try {
                ClipboardSupport.copy(action.userCode());
                status = "Code copied to clipboard";
            } catch (IOException e) {
                status = "Copy code manually";
            }
        }
        deviceCodeStatus = status;
        if (action.openBrowserOnConfirm()) {
            try {
                browserOpener.open(action.verificationUri());
            } catch (Exception ignored) {
                // Launcher failures must not interrupt login (pi activateDeviceCode).
            }
        }
    }

    private static String deviceActionLabel(OAuthEvent.DeviceCode device) {
        if (!device.openBrowserOnConfirm() && !device.copyCodeOnConfirm()) {
            return null;
        }
        if (!device.openBrowserOnConfirm()) {
            return "to copy code";
        }
        if (!device.copyCodeOnConfirm()) {
            return "to open browser";
        }
        return "to open browser and copy code";
    }

    private void complete(String value) {
        var answer = pending;
        pending = null;
        inputActive = false;
        selectionActive = false;
        if (answer != null) {
            answer.complete(value);
        }
    }

    @Override
    public synchronized boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        if (cancelled) {
            onCancel.run();
        }
    }

    @Override
    public synchronized Element render() {
        var rows = new ArrayList<Element>();
        rows.add(TamboUIAdapter.text(option.name()));
        for (var message : messages) {
            for (var line : message.split("\n", -1)) {
                rows.add(TamboUIAdapter.text(line));
            }
        }
        if (deviceCode != null) {
            rows.add(TamboUIAdapter.text(deviceCode.verificationUri()));
            rows.add(TamboUIAdapter.text("Enter code: " + deviceCode.userCode()));
            if (deviceCodeAction != null) {
                var label = deviceActionLabel(deviceCodeAction);
                if (label != null) {
                    rows.add(TamboUIAdapter.text("(Enter " + label + ")"));
                }
            } else if (deviceCodeStatus != null) {
                rows.add(TamboUIAdapter.text(deviceCodeStatus));
            }
        }
        if (selectionActive) {
            for (int i = 0; i < selectOptions.size(); i++) {
                rows.add(TamboUIAdapter.text((i == selected ? "> " : "  ")
                    + selectOptions.get(i).label()));
            }
        } else if (inputActive) {
            rows.add(inputMasked ? TamboUIAdapter.text(inputDisplay()) : editor.render());
        } else {
            rows.add(TamboUIAdapter.text("Waiting for authentication..."));
        }
        return TamboUIAdapter.column(rows);
    }

    synchronized boolean awaitingInput() {
        return inputActive;
    }

    synchronized String inputDisplay() {
        return inputMasked ? "*".repeat(editor.getText().length()) : editor.getText();
    }
}
