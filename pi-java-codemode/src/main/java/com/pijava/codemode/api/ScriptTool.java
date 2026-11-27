package com.pijava.codemode.api;

/** A tool or global a script may call. Arguments and results are plain JSON values. */
@FunctionalInterface
public interface ScriptTool {
    /** Executes the call; thrown exceptions reject the script's promise. */
    Object execute(Object args) throws Exception;
}
