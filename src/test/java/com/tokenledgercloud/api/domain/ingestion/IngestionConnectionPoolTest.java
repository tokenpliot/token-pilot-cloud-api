package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.domain.usage.service.UsageLogWriter;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Regression test for connection-pool exhaustion: with a tiny pool, concurrent ingestion requests must all finish
 * without waiting for a connection until the pool timeout. A request that holds one connection (open-in-view)
 * while {@code UsageLogWriter}'s REQUIRES_NEW asks for a second one deadlocks the pool.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.hikari.maximum-pool-size=2",
		"spring.datasource.hikari.minimum-idle=2",
		"spring.datasource.hikari.connection-timeout=2000"
	})
class IngestionConnectionPoolTest {

	private static final int CONCURRENT_REQUESTS = 8;
	private static final long PRE_CHECK_TO_INSERT_DELAY_MS = 100;

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private UsageLogRepository usageLogRepository;

	@Autowired
	private DataSource dataSource;

	@MockitoSpyBean
	private UsageLogWriter usageLogWriter;

	@MockitoBean
	private ProjectApiKeyAuthenticator projectApiKeyAuthenticator;

	/** Largest number of connections a request already held, as seen on entering {@code insertNew}. */
	private final AtomicInteger maxHeldByCaller = new AtomicInteger();

	private final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	@BeforeEach
	void setUp() {
		given(projectApiKeyAuthenticator.authenticate(any(), eq("support-copilot"), eq("prod")))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		usageLogRepository.deleteAll();
		maxHeldByCaller.set(0);
		// On entering insertNew, record how many pool connections are in use, then wait a moment so concurrent
		// requests overlap for certain. If the spy sits inside the REQUIRES_NEW transaction, that transaction's
		// own connection is part of the count and is subtracted.
		doAnswer(invocation -> {
			int active = ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
			int own = TransactionSynchronizationManager.isActualTransactionActive() ? 1 : 0;
			maxHeldByCaller.accumulateAndGet(active - own, Math::max);
			Thread.sleep(PRE_CHECK_TO_INSERT_DELAY_MS);
			return invocation.callRealMethod();
		}).when(usageLogWriter).insertNew(any(), any(), any());
	}

	@AfterEach
	void tearDown() {
		Mockito.reset(projectApiKeyAuthenticator);
		Mockito.reset(usageLogWriter);
		usageLogRepository.deleteAll();
	}

	@Test
	void singleRequestHoldsNoConnectionWhenEnteringInsert() throws Exception {
		assertThat(post("pool-single").statusCode()).isEqualTo(201);

		assertThat(maxHeldByCaller.get()).isZero();
	}

	@Test
	void concurrentRequestsFinishWithoutPoolTimeoutWhenPoolIsTiny() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
		CyclicBarrier start = new CyclicBarrier(CONCURRENT_REQUESTS);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
				String requestId = "pool-req-" + i;
				results.add(executor.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return post(requestId).statusCode();
				}));
			}

			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get(30, TimeUnit.SECONDS));
			}

			assertThat(statuses).containsOnly(201);
			assertThat(usageLogRepository.count()).isEqualTo(CONCURRENT_REQUESTS);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private HttpResponse<String> post(String requestId) throws Exception {
		String body = """
			{ "projectKey": "support-copilot", "environment": "prod", "requestId": "%s",
			  "provider": "openai", "model": "gpt-4o-mini",
			  "promptTokens": 1200, "completionTokens": 400, "totalTokens": 1600,
			  "promptCostUsd": 0.00018, "completionCostUsd": 0.00024, "totalCostUsd": 0.00042,
			  "pricingVersion": "2026-05-01", "occurredAt": "2026-05-06T10:00:00Z" }
			""".formatted(requestId);
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/ingestion/events"))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
