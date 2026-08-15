package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/** Logging bootstrap tests (10-logging-design §8). */
class LoggingTest {

    @Test
    void configureSetsRootLevelFromDebugFlag() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        Logging.configure(true, false);
        assertThat(root.getLevel()).isEqualTo(Level.DEBUG);

        Logging.configure(false, false);
        assertThat(root.getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void configureDetachesConsoleInTuiModeAndKeepsFile() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        Logging.configure(false, true);

        assertThat(root.getAppender("CONSOLE")).isNull();
        assertThat(root.getAppender("FILE")).isNotNull();
    }
}
