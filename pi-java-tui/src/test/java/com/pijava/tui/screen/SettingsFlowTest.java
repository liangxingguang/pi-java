package com.pijava.tui.screen;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.agent.harness.QueueMode;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.screen.settings.SettingsActionHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsFlowTest {

    @Test
    void steeringChangeUpdatesSettingsAndLiveQueueMode() throws Exception {
        try (var rig = new Rig()) {
            rig.screen.activate("steering-mode");

            assertThat(rig.session.services().settings().accessors().getSteeringMode()).isEqualTo("all");
            assertThat(rig.session.harness().steeringMode()).isEqualTo(new QueueMode.All());
            assertThat(rig.session.harness().followUpMode()).isEqualTo(new QueueMode.OneAtATime());
        }
    }

    @Test
    void thinkingSubmenuEscapeReturnsToItsOriginatingRow() throws Exception {
        try (var rig = new Rig()) {
            rig.screen.activate("model-thinking");
            rig.screen.escape();

            assertThat(rig.screen.isDone()).isFalse();
            assertThat(rig.screen.selectedId()).isEqualTo("model-thinking");
        }
    }

    @Test
    void skillToggleRebuildsAutocompleteAndWarningsReturnToMainList() throws Exception {
        try (var rig = new Rig()) {
            rig.screen.activate("skill-commands");
            assertThat(rig.host.autocompleteRebuilds).isEqualTo(1);

            rig.screen.activate("warnings");
            rig.screen.escape();
            assertThat(rig.screen.selectedId()).isEqualTo("warnings");
            assertThat(rig.screen.isDone()).isFalse();
        }
    }

    private static final class RecordingHost implements SettingsActionHandler.Host {
        private int autocompleteRebuilds;

        @Override
        public void rebuildAutocomplete() {
            autocompleteRebuilds++;
        }
    }

    private static final class Rig implements AutoCloseable {
        private final String oldHome = System.getProperty("user.home");
        private final AgentSession session;
        private final RecordingHost host = new RecordingHost();
        private final SettingsScreen screen;

        private Rig() throws Exception {
            Path home = Files.createTempDirectory("pi-settings-flow-home");
            System.setProperty("user.home", home.toString());
            session = AgentSession.create(ArgsParser.parse(new String[] {
                "--session-dir", Files.createTempDirectory("pi-settings-flow-session").toString() }));
            screen = new SettingsScreen(session, null, host);
        }

        @Override
        public void close() {
            session.close();
            System.setProperty("user.home", oldHome);
        }
    }
}
