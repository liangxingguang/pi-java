package com.pijava.agent.hook;

/** Hook triggered before tree navigation. Can reject the navigation. */
@FunctionalInterface
public interface BeforeNavigationHook {
    void beforeNavigation(NavigationContext ctx);
}
