package com.pijava.coding.agent.core;

/**
 * Interactive selector for a missing session cwd (pi {@code showStartupSelector},
 * L5 W3).
 *
 * <p>Null means no UI: startup reports the missing cwd as an error and exits 1,
 * as pi's non-interactive arm does.</p>
 */
@FunctionalInterface
public interface SessionCwdPrompter {

    /** Ask the user; a null result is {@link SessionCwd.Choice#CANCEL}. */
    SessionCwd.Choice prompt(SessionCwd.Issue issue);
}
