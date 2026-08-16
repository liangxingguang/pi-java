package com.pijava.agent.session;

class JsonlConformanceGroup3Test extends ConformanceGroup3Test {
    @Override
    protected ConformanceBackend backend() {
        return new JsonlConformanceBackend();
    }
}
