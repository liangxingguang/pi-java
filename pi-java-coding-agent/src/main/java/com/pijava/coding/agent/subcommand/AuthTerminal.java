package com.pijava.coding.agent.subcommand;

import java.io.IOException;

/**
 * Line-based terminal I/O for {@code pi-java auth login} (pi {@code cli/auth-terminal.ts},
 * anchor {@code a00a5048d}).
 *
 * <p>Injected so tests can script prompts and output. Prompts reject with
 * {@link IOException} carrying {@code "Login cancelled"} when the user interrupts.</p>
 */
public interface AuthTerminal {

    /** Whether stdin and stdout are a terminal; login prompts need one. */
    boolean interactive();

    /** Print one line. */
    void write(String line);

    /** Print one error line. */
    void error(String line);

    /** Ask for a line and return it. */
    String question(String message) throws IOException;

    /** Read a line without echoing it. */
    String secret(String message) throws IOException;

    /** Open a web URL in the browser; failures do not interrupt the login. */
    void openUrl(String url);
}
