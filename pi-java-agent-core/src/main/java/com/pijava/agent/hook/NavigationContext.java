package com.pijava.agent.hook;

/** Context passed to {@code before_navigation} hook. */
public record NavigationContext(String lane, String targetLeafId) {}
