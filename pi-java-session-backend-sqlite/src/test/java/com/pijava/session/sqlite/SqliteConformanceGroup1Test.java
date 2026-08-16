package com.pijava.session.sqlite;

import com.pijava.agent.session.ConformanceBackend;
import com.pijava.agent.session.ConformanceGroup1Test;

class SqliteConformanceGroup1Test extends ConformanceGroup1Test {
    @Override
    protected ConformanceBackend backend() {
        return new SqliteConformanceBackend();
    }
}