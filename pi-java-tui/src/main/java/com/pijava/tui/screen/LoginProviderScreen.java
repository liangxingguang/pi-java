package com.pijava.tui.screen;

import java.util.List;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.LoginAuthType;
import com.pijava.coding.agent.core.LoginProviderOption;
import com.pijava.tui.component.SelectList;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/** Searchable provider/authentication-method selector. */
public final class LoginProviderScreen implements ScreenOverlay {

    private final SelectList<LoginProviderOption> list;
    private final Consumer<LoginProviderOption> onChoice;

    /** Create a selector with an optional initial fuzzy query. */
    public LoginProviderScreen(List<LoginProviderOption> options, String initialSearch,
                               Consumer<LoginProviderOption> onChoice) {
        this.list = new SelectList<>(options, LoginProviderScreen::label);
        this.onChoice = onChoice;
        list.filter(initialSearch);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        return list.onKeyEvent(event);
    }

    @Override
    public boolean isDone() {
        return list.confirmed() || list.cancelled();
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        if (list.cancelled()) {
            onChoice.accept(null);
        } else {
            onChoice.accept(list.selected().orElse(null));
        }
    }

    @Override
    public Element render() {
        return TamboUIAdapter.column(List.of(
            TamboUIAdapter.text("Sign in"), list.render()));
    }

    private static String label(LoginProviderOption option) {
        var auth = option.authType() == LoginAuthType.OAUTH ? "account" : "API key";
        var status = option.status() == null ? "" : " — " + option.status();
        return option.name() + " (" + auth + ")" + status;
    }
}
