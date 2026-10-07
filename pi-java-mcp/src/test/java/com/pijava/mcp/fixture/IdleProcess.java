package com.pijava.mcp.fixture;

/**
 * Child-process fixture: sleeps until killed.
 */
public final class IdleProcess {

    private IdleProcess() {
    }

    /** Entry point. */
    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(60_000);
    }
}
