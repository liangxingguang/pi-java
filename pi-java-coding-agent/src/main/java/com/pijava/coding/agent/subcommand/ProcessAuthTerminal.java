package com.pijava.coding.agent.subcommand;

import java.io.Console;
import java.io.IOException;

/**
 * Terminal I/O on stdin and stdout for {@code pi-java auth login}.
 *
 * <p>{@link System#console()} is null when stdin or stdout is redirected, which is
 * also how Java exposes "not interactive". Reading a line without echo uses
 * {@link Console#readPassword}; without a console the terminal is non-interactive
 * and prompts are never reached in production.</p>
 */
public final class ProcessAuthTerminal implements AuthTerminal {

    private final Console console;

    /** Create the process terminal bound to the current console. */
    public ProcessAuthTerminal() {
        this(System.console());
    }

    /** Test/injection form: a console-less terminal is non-interactive. */
    ProcessAuthTerminal(Console console) {
        this.console = console;
    }

    @Override
    public boolean interactive() {
        return console != null;
    }

    @Override
    public void write(String line) {
        System.out.println(line);
    }

    @Override
    public void error(String line) {
        System.err.println(line);
    }

    @Override
    public String question(String message) throws IOException {
        var answer = console.readLine(message);
        if (answer == null) {
            throw new IOException("Login cancelled");
        }
        return answer;
    }

    @Override
    public String secret(String message) throws IOException {
        var answer = console.readPassword(message);
        if (answer == null) {
            throw new IOException("Login cancelled");
        }
        return String.valueOf(answer);
    }

    @Override
    public void openUrl(String url) {
        BrowserLauncher.open(url, ignored -> {
            // The URL is printed anyway; a launcher failure must not interrupt sign-in.
        });
    }
}
