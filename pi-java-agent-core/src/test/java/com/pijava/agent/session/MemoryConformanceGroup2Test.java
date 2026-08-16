package com.pijava.agent.session;

class MemoryConformanceGroup2Test extends ConformanceGroup2Test {
    @Override
    protected ConformanceBackend backend() {
        return new MemoryConformanceBackend();
    }
}
