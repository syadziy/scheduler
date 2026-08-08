package com.mac.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "scheduler.engine.enabled=false",
    "scheduler.threshold-alert.enabled=false",
    "sdk.security.enabled=false",
    "sdk.security.method-security-enabled=false",
    "sdk.security.cors.enabled=false"
})
class SchedulerDatabaseIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayCreatesSchedulerTables() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'scheduler_task',
                      'scheduler_task_group',
                      'scheduler_group_task',
                      'scheduler_group_group',
                      'scheduler_schedule',
                      'scheduler_execution',
                      'scheduler_task_history'
                  )
                """, Integer.class);

        assertThat(tableCount).isEqualTo(7);
    }
}
