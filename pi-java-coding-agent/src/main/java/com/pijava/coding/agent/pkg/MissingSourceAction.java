package com.pijava.coding.agent.pkg;

import java.util.Locale;

/** pi {@code MissingSourceAction}（{@code "skip" | "error" | "install"}）。 */
public enum MissingSourceAction {
    SKIP,
    ERROR,
    INSTALL;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
