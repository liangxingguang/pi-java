package com.pijava.agent.harness;

/**
 * Drive mode for the agent harness.
 *
 * <p>In automatic mode the harness processes actions as they become
 * available. In manual mode the caller drives execution step by step
 * via {@link AgentHarness#peekAction()} /
 * {@link AgentHarness#executeAction(Action)}.</p>
 */
public sealed interface DriveMode {

    /** The harness runs autonomously to completion. */
    record Automatic() implements DriveMode {}

    /** The caller drives execution one action at a time. */
    record Manual() implements DriveMode {}
}
