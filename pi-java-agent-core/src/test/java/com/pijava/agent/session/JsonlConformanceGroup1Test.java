package com.pijava.agent.session;

class JsonlConformanceGroup1Test extends ConformanceGroup1Test {
    @Override
    protected ConformanceBackend backend() {
        return new JsonlConformanceBackend();
    }
}
