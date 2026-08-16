package com.pijava.agent.session;

class JsonlConformanceGroup2Test extends ConformanceGroup2Test {
    @Override
    protected ConformanceBackend backend() {
        return new JsonlConformanceBackend();
    }
}