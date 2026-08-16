package com.pijava.session.sqlite;

import com.pijava.agent.session.ConformanceBackend;
import com.pijava.agent.session.ConformanceGroup2Test;

class SqliteConformanceGroup2Test extends ConformanceGroup2Test {
    @Override
    protected ConformanceBackend backend() {
        return new SqliteConformanceBackend();
    }
}