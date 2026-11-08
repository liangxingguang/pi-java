package com.pijava.evals.harness;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §10）：{@link IsolatedEnvironment} 的属性覆盖/还原、目录清理与
 * 幂等 close。
 */
class IsolatedEnvironmentTest {

    @Test
    void overridesAndRestoresProcessProperties() throws Exception {
        var previousHome = System.getProperty("user.home");
        var previousDir = System.getProperty("user.dir");
        Path root;
        try (var environment = IsolatedEnvironment.open()) {
            root = environment.root();
            assertThat(System.getProperty("user.home"))
                .isEqualTo(environment.home().toString());
            assertThat(System.getProperty("user.dir"))
                .isEqualTo(environment.workspace().toString());
            assertThat(environment.home()).isDirectory();
            assertThat(environment.workspace()).isDirectory();
            assertThat(environment.sessionDir()).isDirectory();
            assertThat(environment.agentDir()).isDirectory()
                .isEqualTo(environment.home().resolve(".pi-java").resolve("agent"));
            // L6 W4：agent 目录经属性覆盖指向隔离根，本仓读取面与种入面同源
            assertThat(System.getProperty(IsolatedEnvironment.AGENT_DIR_PROPERTY))
                .isEqualTo(environment.agentDir().toString());
        }
        assertThat(System.getProperty("user.home")).isEqualTo(previousHome);
        assertThat(System.getProperty("user.dir")).isEqualTo(previousDir);
        assertThat(root).doesNotExist();
    }

    @Test
    void restoresClearedPropertiesAsAbsent() throws Exception {
        var previousHome = System.getProperty("user.home");
        var previousDir = System.getProperty("user.dir");
        var previousAgentDir = System.getProperty(IsolatedEnvironment.AGENT_DIR_PROPERTY);
        try {
            System.clearProperty("user.home");
            System.clearProperty("user.dir");
            System.clearProperty(IsolatedEnvironment.AGENT_DIR_PROPERTY);
            try (var environment = IsolatedEnvironment.open()) {
                assertThat(environment.home()).isDirectory();
                assertThat(System.getProperty("user.home"))
                    .isEqualTo(environment.home().toString());
            }
            assertThat(System.getProperty("user.home")).isNull();
            assertThat(System.getProperty("user.dir")).isNull();
            assertThat(System.getProperty(IsolatedEnvironment.AGENT_DIR_PROPERTY)).isNull();
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("user.dir", previousDir);
            restoreProperty(IsolatedEnvironment.AGENT_DIR_PROPERTY, previousAgentDir);
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        var environment = IsolatedEnvironment.open();
        var root = environment.root();
        environment.close();
        assertThat(root).doesNotExist();
        environment.close();
        assertThat(root).doesNotExist();
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
