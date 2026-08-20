package com.pijava.coding.agent.subcommand;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-15: AuthCommand — bearer token 格式化。
 */
class AuthCommandTest {

    @Test
    void bearerFormatsAuthorizationHeaderValue() {
        assertThat(AuthCommand.bearer("sk-abc")).isEqualTo("Bearer sk-abc");
        assertThat(AuthCommand.bearer("")).isEqualTo("Bearer ");
    }
}
