package com.pijava.tui.app;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FirstRunSetupTest {

    @Test
    void runsOnlyWhenExperimentalOfficialAndNoSettings() {
        Map<String, String> env = Map.of("PI_EXPERIMENTAL", "1");
        assertThat(FirstRunSetup.shouldRun(env, false, true)).isTrue();
        assertThat(FirstRunSetup.shouldRun(env, true, true)).isFalse();
        assertThat(FirstRunSetup.shouldRun(env, false, false)).isFalse();
        assertThat(FirstRunSetup.shouldRun(Map.of(), false, true)).isFalse();
        assertThat(FirstRunSetup.shouldRun(Map.of("PI_EXPERIMENTAL", "0"), false, true)).isFalse();
    }

    @Test
    void agentDirOverrideSkipsSetup() {
        var env = new HashMap<String, String>();
        env.put("PI_EXPERIMENTAL", "1");
        env.put("PI_JAVA_CODING_AGENT_DIR", "/tmp/agent");
        assertThat(FirstRunSetup.shouldRun(env, false, true)).isFalse();
    }
}
