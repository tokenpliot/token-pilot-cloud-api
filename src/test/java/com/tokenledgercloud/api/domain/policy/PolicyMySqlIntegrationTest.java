package com.tokenledgercloud.api.domain.policy;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import com.tokenledgercloud.api.domain.budget.dto.BudgetUpsertRequest;
import com.tokenledgercloud.api.domain.budget.repository.MonthlyBudgetSettingRepository;

/** Runs the same behavioral suite after all real Flyway migrations against an EMPTY disposable MySQL DB. */
@EnabledIfEnvironmentVariable(named = "POLICY_TEST_MYSQL_URL", matches = ".+")
@Import(PolicyMySqlIntegrationTest.BarrierConfiguration.class)
@SpringBootTest(properties = {
	"spring.datasource.url=${POLICY_TEST_MYSQL_URL}",
	"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
	"spring.datasource.username=${POLICY_TEST_MYSQL_USER:root}",
	"spring.datasource.password=${POLICY_TEST_MYSQL_PASSWORD:}",
	// validate (the default) proves every entity mapping matches the real Flyway schema on MySQL.
	"spring.jpa.hibernate.ddl-auto=validate",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=true",
	"spring.flyway.baseline-on-migrate=false",
	"spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
class PolicyMySqlIntegrationTest extends PolicyIntegrationTest {
	@Autowired MissingRowBarrier reads;

	@Test
	void legacyCaseInsensitiveLookupCannotOverwriteManagedProjection() throws Exception {
		legacy.upsert(new BudgetUpsertRequest("project-a", "prod", "MONTHLY", new BigDecimal("100"), List.of(80)));
		var before = policies.importBudget(admin, "project-a", "prod", new PolicyRequests.ImportBudget("Migrate"));
		// Confirm the real MySQL lookup aliases PROD to prod; H2's case-sensitive string comparison cannot cover this.
		assertThat(budgets.findFirstByOrganizationIdAndProjectIdAndEnvironment("default-org", "project-a", "PROD")).isPresent();
		for (String environment : List.of("PROD", "PrOd")) {
			mvc.perform(put("/api/budgets").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"projectId":"project-a","environment":"%s","periodType":"MONTHLY","limitUsd":999,"thresholds":[50]}
					""".formatted(environment)))
				.andExpect(status().isConflict());
		}
		assertThat(policies.current(admin, "project-a", "prod").snapshotId()).isEqualTo(before.snapshotId());
		assertThat(budgets.findAll()).singleElement().satisfies(b -> {
			assertThat(b.getLimitUsd()).isEqualByComparingTo("100");
			assertThat(b.getThreshold50Enabled()).isFalse();
			assertThat(b.getThreshold80Enabled()).isTrue();
			assertThat(b.getPolicyVersion()).isEqualTo(before.version());
		});
	}

	@Test
	void differentProjectsCanCreateTheirFirstPoliciesAfterConcurrentMissingRowLookups() throws Exception {
		reads.heads = new CyclicBarrier(2);
		reads.budgets = new CyclicBarrier(2);
		var otherAdmin = jwt.getAuthentication(jwt.createToken("outsider@example.test", "USER"));
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> policies.replace(admin, "project-a", "prod", replace(0, "100", true)));
			var second = executor.submit(() -> policies.replace(otherAdmin, "project-b", "prod", replace(0, "200", true)));
			assertThat(first.get(20, TimeUnit.SECONDS).revision()).isEqualTo(1);
			assertThat(second.get(20, TimeUnit.SECONDS).revision()).isEqualTo(1);
		} finally {
			reads.heads = null;
			reads.budgets = null;
		}
		assertThat(jdbc.queryForObject("select count(*) from policy_snapshots", Integer.class)).isEqualTo(2);
		assertThat(budgets.findAll()).hasSize(2);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class BarrierConfiguration {
		@Bean static MissingRowBarrier missingRowBarrier() { return new MissingRowBarrier(); }
	}

	/** Pause AFTER real repository queries, so both transactions hold any gap locks before either inserts. */
	static class MissingRowBarrier implements BeanPostProcessor {
		volatile CyclicBarrier heads;
		volatile CyclicBarrier budgets;

		@Override
		public Object postProcessAfterInitialization(Object bean, String name) {
			if (!(bean instanceof CustomerPolicyRepository) && !(bean instanceof MonthlyBudgetSettingRepository)) return bean;
			ProxyFactory proxy = new ProxyFactory(bean);
			proxy.addAdvice((MethodInterceptor) invocation -> {
				Object result = invocation.proceed();
				CyclicBarrier barrier = switch (invocation.getMethod().getName()) {
					case "lockByScope" -> heads;
					case "findAllByOrganizationIdAndProjectIdAndEnvironment" -> budgets;
					default -> null;
				};
				if (barrier != null) barrier.await(10, TimeUnit.SECONDS);
				return result;
			});
			return proxy.getProxy();
		}
	}
}
