package com.pijava.session.sqlite;

import com.pijava.agent.session.ConformanceBackend;
import com.pijava.agent.session.ConformanceGroup3Test;

class SqliteConformanceGroup3Test extends ConformanceGroup3Test {
    @Override
    protected ConformanceBackend backend() {
        return new SqliteConformanceBackend();
    }
}
