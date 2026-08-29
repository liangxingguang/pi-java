package com.pijava.coding.agent.extension;

import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.memory.MemorySessionCreateOptions;
import com.pijava.agent.session.memory.MemorySessionRepository;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.SessionServices;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.TrustManager;
import com.pijava.coding.agent.core.slash.CommandRegistry;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extension sendMessage — custom_message 落盘路径（pi {@code pi.sendMessage} 无
 * triggerTurn 分支）：绑定 session 后写入可查；未绑定/triggerTurn 明确报错。
 */
class DefaultExtensionContextSendMessageTest {

    @Test
    void sendMessagePersistsCustomMessageEntryOnceBound() {
        var repository = new MemorySessionRepository();
        Session<?> session = repository.create(new MemorySessionCreateOptions(null, "cwd", null));
        var context = context();
        context.bindSession(() -> session);

        var id = context.sendMessage("my-ext", CustomMessageContent.of("injected context"),
            true, Map.of("note", "private"));

        assertThat(id).isNotBlank();
        var found = session.findEntry(new EntryQuery("custom_message", "my-ext", null, null, null));
        assertThat(found).isInstanceOf(Entry.CustomMessage.class);
        var cm = (Entry.CustomMessage) found;
        assertThat(cm.id()).isEqualTo(id);
        assertThat(((CustomMessageContent.Text) cm.content()).text()).isEqualTo("injected context");
        assertThat(cm.display()).isTrue();
        assertThat(cm.details()).containsEntry("note", "private");
    }

    @Test
    void sendMessageWithoutBoundSessionFails() {
        var context = context();

        assertThatThrownBy(() -> context.sendMessage("my-ext",
            CustomMessageContent.of("x"), false, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bound session");
    }

    @Test
    void boundButUnresolvedSessionFails() {
        var context = context();
        context.bindSession(() -> null);

        assertThatThrownBy(() -> context.sendMessage("my-ext",
            CustomMessageContent.of("x"), false, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void triggerTurnIsRejectedUntilImplemented() {
        var repository = new MemorySessionRepository();
        Session<?> session = repository.create(new MemorySessionCreateOptions(null, "cwd", null));
        var context = context();
        context.bindSession(() -> session);

        assertThatThrownBy(() -> context.sendMessage("my-ext", CustomMessageContent.of("x"),
            false, null, new ExtensionContext.SendMessageOptions(true)))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(session.findEntries(new EntryQuery("custom_message", null, null, null, null)))
            .isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static DefaultExtensionContext context() {
        var providers = ProviderRegistry.create();
        var tools = new ToolRegistry(null);
        var commands = CommandRegistry.withBuiltins();
        var services = new SessionServices(
            SettingsManager.load(null),
            new TrustManager("none"),
            providers,
            new DefaultModelResolver(BuiltinCatalog.all()),
            tools, commands, new MemorySessionRepository());
        return new DefaultExtensionContext(services, new SkillManager());
    }
}
