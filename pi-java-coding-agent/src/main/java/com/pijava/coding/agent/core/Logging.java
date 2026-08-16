package com.pijava.coding.agent.core;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Logging bootstrap (10-logging-design §6).
 *
 * <p>Runs once at startup: resolves the bundled {@code logback.xml} (or a
 * {@code ~/.pi-java/logback.xml} override), sets the root level from
 * {@code --debug}, and detaches the console appender in interactive (TUI)
 * mode so raw-mode terminal output is never corrupted. The file appender
 * stays active in both modes.</p>
 *
 * <p>This class depends only on slf4j-api at compile time (Phase 5 native
 * support): when logback-classic is on the classpath (JVM builds) it is
 * configured reflectively; otherwise (native builds with slf4j-simple) the
 * configuration degrades to the simple logger's default-level property.</p>
 */
public final class Logging {

    /** Name of the console appender in the bundled logback.xml. */
    private static final String CONSOLE_APPENDER = "CONSOLE";

    private static final String LOGBACK_CONTEXT = "ch.qos.logback.classic.LoggerContext";

    private Logging() {}

    /** Configure logging. {@code tui} true = interactive alternate-screen mode. */
    public static void configure(boolean debug, boolean tui) {
        ensureLogDirectory();
        if (configureLogback(debug, tui)) {
            return;
        }
        // Native builds replace logback-classic with slf4j-simple; the only
        // level switch available through the slf4j-api is the system property.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
            debug ? "debug" : "info");
    }

    /** Reflective logback configuration; returns false when logback is absent. */
    private static boolean configureLogback(boolean debug, boolean tui) {
        try {
            Class.forName(LOGBACK_CONTEXT);
        } catch (ClassNotFoundException e) {
            return false;
        }
        try {
            Class<?> contextType = Class.forName(LOGBACK_CONTEXT);
            Object factory = org.slf4j.LoggerFactory.getILoggerFactory();
            if (!contextType.isInstance(factory)) {
                return false;
            }
            Class<?> loggerType = Class.forName("ch.qos.logback.classic.Logger");
            Object root = contextType.getMethod("getLogger", String.class)
                .invoke(factory, loggerType.getField("ROOT_LOGGER_NAME").get(null));
            Class<?> levelType = Class.forName("ch.qos.logback.classic.Level");
            Object level = levelType.getField(debug ? "DEBUG" : "INFO").get(null);
            loggerType.getMethod("setLevel", levelType).invoke(root, level);
            if (tui) {
                Method detach = loggerType.getMethod("detachAppender", String.class);
                detach.invoke(root, CONSOLE_APPENDER);
            }
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException
                 | IllegalAccessException | InvocationTargetException e) {
            // API drift or a non-logback binding: degrade to the simple fallback.
            return false;
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