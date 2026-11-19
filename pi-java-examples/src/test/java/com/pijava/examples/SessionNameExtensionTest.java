package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;

/** docs/52 §2.5：session-name 验收——设名/显示两臂逐字。 */
class SessionNameExtensionTest {

    @Test
    void setNameNotifiesVerbatimAndStores() {
        var w = new W3Context();
        new SessionNameExtension().register(w.ctx);

        w.command("session-name", "  my session  ");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Session named: my session");
        assertThat(w.sessionName).isEqualTo("my session");
        assertThat(w.ui.notices()).allSatisfy(n ->
            assertThat(n.type()).isEqualTo(NotifyType.INFO));
    }

    @Test
    void showNameAndUnsetArms() {
        var w = new W3Context();
        new SessionNameExtension().register(w.ctx);

        w.command("session-name", "   ");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("No session name set");

        w.command("session-name", "project-x");
        w.command("session-name", "");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("No session name set", "Session named: project-x",
                "Session: project-x");
    }
}
