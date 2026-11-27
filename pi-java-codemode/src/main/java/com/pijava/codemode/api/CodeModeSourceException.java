package com.pijava.codemode.api;

import java.io.Serial;

/** Reports invalid codemode source text. */
public class CodeModeSourceException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given detail message.
     *
     * @param message detail message
     */
    public CodeModeSourceException(String message) {
        super(message);
    }
}
