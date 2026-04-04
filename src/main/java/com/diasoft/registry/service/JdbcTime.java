package com.diasoft.registry.service;

import java.sql.Timestamp;
import java.time.Instant;

final class JdbcTime {
    private JdbcTime() {
    }

    static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
