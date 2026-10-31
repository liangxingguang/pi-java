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
                .isEqualTo(environment.home().resolve(".pi").resolve("agent"));
        }
        assertThat(System.getProperty("user.home")).isEqualTo(previousHome);
        assertThat(System.getProperty("user.dir")).isEqualTo(previousDir);
        assertThat(root).doesNotExist();
    }

    @Test
    void restoresClearedPropertiesAsAbsent() throws Exception {
        var previousHome = System.getProperty("user.home");
        var previousDir = System.getProperty("user.dir");
        try {
            System.clearProperty("user.home");
            System.clearProperty("user.dir");
            try (var environment = IsolatedEnvironment.open()) {
                assertThat(environment.home()).isDirectory();
                assertThat(System.getProperty("user.home"))
                    .isEqualTo(environment.home().toString());
            }
            assertThat(System.getProperty("user.home")).isNull();
            assertThat(System.getProperty("user.dir")).isNull();
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("user.dir", previousDir);
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
