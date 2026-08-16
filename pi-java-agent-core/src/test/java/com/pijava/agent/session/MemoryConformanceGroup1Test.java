package com.pijava.agent.session;

class MemoryConformanceGroup1Test extends ConformanceGroup1Test {
    @Override
    protected ConformanceBackend backend() {
        return new MemoryConformanceBackend();
    }
}
