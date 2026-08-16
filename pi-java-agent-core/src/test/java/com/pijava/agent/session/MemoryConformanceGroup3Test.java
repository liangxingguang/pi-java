package com.pijava.agent.session;

class MemoryConformanceGroup3Test extends ConformanceGroup3Test {
    @Override
    protected ConformanceBackend backend() {
        return new MemoryConformanceBackend();
    }
}