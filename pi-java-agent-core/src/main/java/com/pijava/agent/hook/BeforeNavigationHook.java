package com.pijava.agent.hook;

/** Hook triggered before tree navigation. Can reject the navigation. */
@FunctionalInterface
public interface BeforeNavigationHook {
    /** Invoked before tree navigation; may reject the navigation. */
    void beforeNavigation(NavigationContext ctx);
}
