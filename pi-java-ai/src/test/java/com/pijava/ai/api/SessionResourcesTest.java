package com.pijava.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * pi {@code packages/ai/src/session-resources.ts}:1-25：注册、注销、按会话清理、
 * 无参清空，以及「单个钩子抛错不影响其余」的聚合语义。
 */
class SessionResourcesTest {

    @Test
    void registeredCleanupReceivesSessionIdAndUnregisterStopsIt() {
        var seen = new CopyOnWriteArrayList<String>();
        var handle = SessionResources.register(seen::add);

        SessionResources.cleanup("s1");
        assertThat(seen).contains("s1");

        handle.run();
        SessionResources.cleanup("s2");
        assertThat(seen).containsExactly("s1");
    }

    @Test
    void cleanupWithoutSessionIdPassesNullForClearAll() {
        var seen = new CopyOnWriteArrayList<String>();
        seen.add("before");
        var handle = SessionResources.register(seen::add);

        try {
            SessionResources.cleanup();
            assertThat(seen).containsExactly("before", null);
        } finally {
            handle.run();
        }
    }

    @Test
    void oneThrowingCleanupDoesNotBlockTheOthers() {
        var ran = new AtomicInteger();
        var first = new IllegalStateException("boom-1");
        var second = new RuntimeException("boom-2");
        var h1 = SessionResources.register(id -> {
            throw first;
        });
        var h2 = SessionResources.register(id -> {
            throw second;
        });
        var h3 = SessionResources.register(id -> ran.incrementAndGet());

        try {
            var error = catchThrowable(() -> SessionResources.cleanup("s"));
            assertThat(error)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to cleanup session resources");
            assertThat(ran).hasValue(1);
            assertThat(error.getCause()).isIn(first, second);
            assertThat(error.getCause().getSuppressed()).hasSize(1);
        } finally {
            h1.run();
            h2.run();
            h3.run();
        }
    }
}
