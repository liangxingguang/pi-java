package com.pijava.tui.screen;

import java.util.List;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.LoginAuthType;
import com.pijava.coding.agent.core.LoginProviderOption;
import com.pijava.coding.agent.core.PreferredProviders;
import com.pijava.tui.component.SelectList;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Searchable provider/authentication-method selector.
 *
 * <p>docs/62（L7 W2，pi {@code components/oauth-selector.ts:56}）：PgUp/PgDn 整页翻，
 * 页大小 8。</p>
 */
public final class LoginProviderScreen implements ScreenOverlay {

    /** pi {@code oauth-selector.ts:56}。 */
    private static final int MAX_VISIBLE = 8;

    private final SelectList<LoginProviderOption> list;
    private final Consumer<LoginProviderOption> onChoice;

    /** Create a selector with an optional initial fuzzy query. */
    public LoginProviderScreen(List<LoginProviderOption> options, String initialSearch,
                               Consumer<LoginProviderOption> onChoice) {
        this.list = new SelectList<>(options, LoginProviderScreen::label, MAX_VISIBLE);
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

    static String label(LoginProviderOption option) {
        var auth = option.authType() == LoginAuthType.OAUTH ? "account" : "API key";
        var status = option.status() == null ? "" : " — " + option.status();
        // L7 W8（漏项1后半，pi oauth-selector.ts:190）：伙伴 provider 名后加
        // U+2726（单宽），与品牌动画同为「伙伴」标记；动画本身未移植（N-W8-1）。
        var partner = PreferredProviders.isPreferredProvider(option.id()) ? " ✦" : "";
        return option.name() + partner + " (" + auth + ")" + status;
    }
}
