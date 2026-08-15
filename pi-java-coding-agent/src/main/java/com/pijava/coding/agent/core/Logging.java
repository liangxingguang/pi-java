package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/**
 * Logging bootstrap (10-logging-design §6).
 *
 * <p>Runs once at startup: resolves the bundled {@code logback.xml} (or a
 * {@code ~/.pi-java/logback.xml} override), sets the root level from {@code --debug},
 * and detaches the console appender in interactive (TUI) mode so raw-mode terminal
 * output is never corrupted. The file appender stays active in both modes.</p>
 */
public final class Logging {

    /** Name of the console appender in the bundled logback.xml. */
    private static final String CONSOLE_APPENDER = "CONSOLE";

    private Logging() {}

    /** Configure logging. {@code tui} true = interactive alternate-screen mode. */
    public static void configure(boolean debug, boolean tui) {
        ensureLogDirectory();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(debug ? Level.DEBUG : Level.INFO);

        if (tui) {
            root.detachAppender(CONSOLE_APPENDER);
        }
    }

    private static void ensureLogDirectory() {
        Path dir = Path.of(System.getProperty("user.home"), ".pi-java", "logs");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // logback reports its own error if the file appender cannot open.
        }
    }
}
