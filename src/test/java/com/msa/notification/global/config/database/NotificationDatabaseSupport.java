package com.msa.notification.global.config.database;

import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 기존 로컬 DB 대신 수명이 테스트로 한정된 PostgreSQL을 사용합니다. */
public abstract class NotificationDatabaseSupport {
    static final PostgreSQLContainer DB =
            new PostgreSQLContainer("postgres:17-alpine").withInitScript("db/schema.sql");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }
}
