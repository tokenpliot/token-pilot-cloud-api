package com.tokenledgercloud.api.domain.shadow;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** Use only an empty disposable database: the inherited suite deletes its fixtures. */
@EnabledIfEnvironmentVariable(named = "SHADOW_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(properties = {
    "spring.datasource.url=${SHADOW_TEST_MYSQL_URL}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "spring.datasource.username=${SHADOW_TEST_MYSQL_USER:root}",
    "spring.datasource.password=${SHADOW_TEST_MYSQL_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
    "spring.flyway.enabled=true", "spring.flyway.baseline-on-migrate=false",
    "token-pilot.shadow.enabled=true", "token-pilot.decision.enabled=true",
    "token-pilot.observe.ingestion.rate-unit-tokens=1000000",
    "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
class ShadowMySqlIntegrationTest extends ShadowIntegrationTest {}
