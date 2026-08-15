package com.pijava.agent.session;

/** Session / entry / record id generator (aligned with pi {@code IdGenerator}). */
@FunctionalInterface
public interface IdGenerator {

    /** Generate the next id. */
    String next();
}