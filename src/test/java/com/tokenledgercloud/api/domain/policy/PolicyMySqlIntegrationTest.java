package com.tokenledgercloud.api.domain.policy;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** Runs the same behavioral suite after all real Flyway migrations against an EMPTY disposable MySQL DB. */
@EnabledIfEnvironmentVariable(named = "POLICY_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(properties = {
	"spring.datasource.url=${POLICY_TEST_MYSQL_URL}",
	"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
	"spring.datasource.username=${POLICY_TEST_MYSQL_USER:root}",
	"spring.datasource.password=${POLICY_TEST_MYSQL_PASSWORD:}",
	// Match production: unrelated legacy pricing_catalogs LOB mappings do not pass Hibernate validate.
	"spring.jpa.hibernate.ddl-auto=none",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=true",
	"spring.flyway.baseline-on-migrate=false",
	"spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
class PolicyMySqlIntegrationTest extends PolicyIntegrationTest {}
