package com.tokenledgercloud.api.domain.ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.tokenledgercloud.api.TokenLedgerCloudApplication;
import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.accounting.observe.LedgerPeriod;
import com.tokenledgercloud.api.domain.accounting.observe.ObserveLedgerQueryService;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.ingestion.web.IngestionRequestSizeFilter;
import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.domain.pricing.repository.PricingPlanRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.entity.ProjectApiKey;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

/**
 * POST /api/v2/observe/events through the real security chain, filters, BCrypt key check and H2; nothing mocked.
 * The last three tests check issue #11's completion criteria end to end: HTTP ingestion -> ledger -> query services.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=" + ObserveIngestionHttpIntegrationTest.DB_URL,
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false",
	"token-pilot.observe.ingestion.enabled=true",
	"token-pilot.observe.ingestion.rate-unit-tokens=1000000",
	"token-pilot.ingestion.max-event-bytes=2048"
})
class ObserveIngestionHttpIntegrationTest {

	static final String DB_URL = "jdbc:h2:mem:observe-http;DB_CLOSE_DELAY=-1";
	private static final String PROD_KEY = "tp_observe_prod_secret";
	// A model with a price plan (prompt 2 USD, completion 8 USD per 1M tokens) and one without.
	private static final String PRICED_MODEL = "priced-model";
	private static final String UNPRICED_MODEL = "m";
	private static final LedgerPeriod OCTOBER = new LedgerPeriod(
		LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0));
	private static final String ANY_ENV_KEY = "tp_observe_any_env_secret";

	@Autowired WebApplicationContext context;
	@Autowired FilterChainProxy securityFilterChain;
	@Autowired IngestionRequestSizeFilter sizeFilter;
	@Autowired ProjectRepository projects;
	@Autowired ProjectEnvironmentRepository environments;
	@Autowired ProjectApiKeyRepository keys;
	@Autowired UsageLogRepository usage;
	@Autowired UsageAccountingValueRepository values;
	@Autowired PasswordEncoder encoder;
	@Autowired PricingPlanRepository plans;
	@Autowired ObserveLedgerQueryService ledger;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		// The size filter is a servlet filter ordered before Spring Security, as in the running server.
		mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(sizeFilter, securityFilterChain).build();
		projects.saveAndFlush(Project.builder().id("observe-project").organizationId("org-1").projectKey("alpha")
			.name("alpha").status(ProjectStatus.ACTIVE).build());
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId("org-1").projectId("observe-project")
			.environment("prod").build());
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId("org-1").projectId("observe-project")
			.environment("staging").build());
		key("observe-key-prod", PROD_KEY, "prod");
		// No environment restriction: valid for every environment of the project.
		key("observe-key-any", ANY_ENV_KEY, null);
		if (!plans.existsById("plan-http-priced")) {
			plans.saveAndFlush(PricingPlan.builder().id("plan-http-priced").catalogId("catalog-1")
				.provider("openai").model(PRICED_MODEL).currency("USD")
				.promptRate(new BigDecimal("2.000000")).completionRate(new BigDecimal("8.000000"))
				.cachedPromptRate(new BigDecimal("0.500000")).reasoningRate(new BigDecimal("8.000000"))
				.version("http-prices-v1").effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
		}
	}

	/** The ledger writer commits in REQUIRES_NEW, so clean up explicitly. */
	@AfterEach
	void tearDown() {
		values.deleteAll();
		usage.deleteAll();
		keys.deleteAll();
		environments.deleteAll();
		projects.deleteAll();
	}

	@Test
	void projectKeyReachesTheServiceAndResendIsADuplicate() throws Exception {
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", PROD_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(event("evt-1", "prod")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.duplicate").value(false));
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", PROD_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(event("evt-1", "prod")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.duplicate").value(true));
		assertThat(usage.findAll()).hasSize(1);
		assertThat(values.findAll()).hasSize(1);
	}

	@Test
	void keyWithoutEnvironmentRestrictionStoresTheRequestedEnvironment() throws Exception {
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", ANY_ENV_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(event("evt-any", "staging")))
			.andExpect(status().isCreated());
		assertThat(usage.findAll()).singleElement()
			.satisfies(row -> assertThat(row.getEnvironment()).isEqualTo("staging"));
	}

	@Test
	void oversizedBodyIsRejectedBeforeAuthentication() throws Exception {
		String oversized = event("evt-big", "prod").replace("\"model\":\"m\"", "\"model\":\"" + "m".repeat(4096) + "\"");
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", "not-a-real-key")
				.contentType(MediaType.APPLICATION_JSON).content(oversized))
			.andExpect(status().isPayloadTooLarge());
		assertThat(usage.findAll()).isEmpty();
	}

	/** #11: sending the same actual-usage event several times is reflected in the ledger and the cost total once. */
	@Test
	void sameEventSentThreeTimesIsCountedOnceInLedgerAndSpend() throws Exception {
		// 1000 prompt x 2 USD + 500 completion x 8 USD per 1M tokens = 0.006 USD
		send(event("evt-paid", "prod", PRICED_MODEL), 201);
		send(event("evt-paid", "prod", PRICED_MODEL), 200);
		send(event("evt-paid", "prod", PRICED_MODEL), 200);

		assertThat(usage.findAll()).hasSize(1);
		assertThat(values.findAll()).singleElement().satisfies(value -> {
			assertThat(value.getPricingStatus()).isEqualTo(AccountingAmount.PricingStatus.PRICED);
			assertThat(value.getAmount()).isEqualByComparingTo("0.006");
		});
		var spend = ledger.getSpendSummary("org-1", "observe-project", "prod", "2026-10");
		assertThat(spend.ledgerConfirmedSpentUsd()).isEqualByComparingTo("0.006");
		assertThat(spend.totalSpentUsd()).isEqualByComparingTo("0.006");
		assertThat(spend.unreflectedEventCount()).isZero();
	}

	/** #11: a missing price is kept as UNPRICED and reported as such, never as a 0 USD actual cost. */
	@Test
	void missingPriceIsReportedAsUnpricedNotAsZeroDollars() throws Exception {
		send(event("evt-paid", "prod", PRICED_MODEL), 201);
		send(event("evt-no-plan", "prod", UNPRICED_MODEL), 201);

		UsageAccountingValue unpricedValue = values.findAll().stream()
			.filter(value -> value.getPricingStatus() == AccountingAmount.PricingStatus.UNPRICED)
			.findFirst().orElseThrow();
		assertThat(unpricedValue.getAmount()).isNull();
		// The total only contains the priced event; the unpriced one is counted separately instead of as 0 USD.
		var spend = ledger.getSpendSummary("org-1", "observe-project", "prod", "2026-10");
		assertThat(spend.totalSpentUsd()).isEqualByComparingTo("0.006");
		assertThat(spend.unreflectedEventCount()).isEqualTo(1);
		assertThat(ledger.getUnpriced("org-1", "observe-project", "prod", OCTOBER).unpricedEventCount()).isEqualTo(1);
		assertThat(ledger.getReconciliation("org-1", "observe-project", "prod", OCTOBER).unpricedEventCount())
			.isEqualTo(1);
	}

	/**
	 * #11: ingestion and settlement state can still be queried after a restart. A second application context starts on
	 * the same database (as a restarted server would, with nothing carried over in memory) and reads it back.
	 */
	@Test
	void ledgerStateCanBeQueriedAfterARestart() throws Exception {
		send(event("evt-paid", "prod", PRICED_MODEL), 201);
		send(event("evt-no-plan", "prod", UNPRICED_MODEL), 201);

		try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(TokenLedgerCloudApplication.class)
				.properties(
					"spring.datasource.url=" + DB_URL,
					// Keep the tables: the restarted server must find what the first one stored.
					"spring.jpa.hibernate.ddl-auto=none",
					"spring.jpa.show-sql=false",
					"spring.flyway.enabled=false",
					"server.port=0")
				.run()) {
			ObserveLedgerQueryService afterRestart = restarted.getBean(ObserveLedgerQueryService.class);

			var spend = afterRestart.getSpendSummary("org-1", "observe-project", "prod", "2026-10");
			assertThat(spend.totalSpentUsd()).isEqualByComparingTo("0.006");
			assertThat(spend.unreflectedEventCount()).isEqualTo(1);
			var lag = afterRestart.getIngestionLag("org-1", "observe-project", "prod", OCTOBER, LocalDateTime.now());
			assertThat(lag.eventCount()).isEqualTo(2);
			assertThat(lag.unsettledCount()).isEqualTo(1);
			assertThat(afterRestart.getReconciliation("org-1", "observe-project", "prod", OCTOBER).unpricedEventCount())
				.isEqualTo(1);
		}
	}

	private void send(String body, int expectedStatus) throws Exception {
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", PROD_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(expectedStatus));
	}

	private void key(String id, String raw, String environment) {
		keys.saveAndFlush(ProjectApiKey.builder().id(id).organizationId("org-1").projectId("observe-project")
			.environment(environment).name(id).keyPrefix(raw).keyHash(encoder.encode(raw)).status("ACTIVE").build());
	}

	private static String event(String eventId, String environment) {
		return """
			{"projectKey":"alpha","environment":"%s","eventId":"%s","schemaVersion":"observe-v1",
			 "tokenSemantics":"INCLUSIVE_V1","provider":"openai","model":"m","callStatus":"SUCCEEDED",
			 "promptTokens":10,"completionTokens":5,"occurredAt":"2026-10-01T00:00:00Z"}
			""".formatted(environment, eventId);
	}

	// Cached and reasoning tokens are sent as 0: when they are missing the server cannot split the rates and records
	// the event as UNPRICED (CACHE_DETAIL_UNKNOWN), which is the intended behaviour but not what these tests need.
	private static String event(String eventId, String environment, String model) {
		return """
			{"projectKey":"alpha","environment":"%s","eventId":"%s","schemaVersion":"observe-v1",
			 "tokenSemantics":"INCLUSIVE_V1","provider":"openai","model":"%s","callStatus":"SUCCEEDED",
			 "promptTokens":1000,"completionTokens":500,"cachedPromptTokens":0,"reasoningTokens":0,
			 "occurredAt":"2026-10-01T00:00:00Z"}
			""".formatted(environment, eventId, model);
	}
}
