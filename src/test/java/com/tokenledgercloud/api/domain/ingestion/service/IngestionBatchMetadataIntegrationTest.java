package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchItemResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventItemRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionItemStatus;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

/** Per-item metadata judgement in a batch, using the application's real validator and H2. */
@SpringBootTest
class IngestionBatchMetadataIntegrationTest {

	@Autowired
	private IngestionService ingestionService;

	@Autowired
	private UsageLogRepository usageLogRepository;

	@MockitoBean
	private ProjectApiKeyAuthenticator projectApiKeyAuthenticator;

	@BeforeEach
	void setUp() {
		given(projectApiKeyAuthenticator.authenticate(eq("key"), eq("support-copilot"), eq("prod")))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		usageLogRepository.deleteAll();
	}

	@AfterEach
	void tearDown() {
		usageLogRepository.deleteAll();
	}

	private static IngestionEventItemRequest item(String requestId, Map<String, Object> metadata) {
		return new IngestionEventItemRequest(
			requestId, "openai", "gpt-4o-mini",
			100L, 40L, 0L, null, null,
			new BigDecimal("0.00018"), new BigDecimal("0.00024"), null, null, null,
			null, "2026-05-01", null, metadata,
			OffsetDateTime.parse("2026-05-06T10:00:00Z"), null
		);
	}

	@Test
	void forbiddenKeysRejectOnlyTheirItemAndLooseFormatStillPasses() {
		Map<String, Object> sloppy = new HashMap<>();
		for (int i = 0; i < 17; i++) {
			sloppy.put("k" + i, "v");
		}
		sloppy.put("count", 5);

		IngestionBatchResponse response = ingestionService.collectBatch("key", new IngestionBatchRequest(
			"support-copilot", "prod", List.of(
				item("req-ok", Map.of("feature", "summary")),
				item("req-prompt", Map.of("prompt", "SECRET-ITEM-TEXT")),
				item("req-sloppy", sloppy),
				item("req-nested", Map.of("ctx", Map.of("messages", List.of("SECRET-NESTED-TEXT")))))));

		assertThat(response.items()).extracting(IngestionBatchItemResponse::status).containsExactly(
			IngestionItemStatus.CREATED, IngestionItemStatus.REJECTED, IngestionItemStatus.CREATED,
			IngestionItemStatus.REJECTED);
		assertThat(response.items().get(1).code()).isEqualTo("COMMON-400");
		assertThat(response.items().get(1).retryable()).isFalse();
		assertThat(response.items().get(1).message()).contains("prompt");
		assertThat(response.items().get(3).message()).contains("messages");
		assertThat(response.items().get(1).message() + response.items().get(3).message())
			.doesNotContain("SECRET-ITEM-TEXT", "SECRET-NESTED-TEXT");
		assertThat(response.rejectedItems()).extracting(r -> r.requestId()).containsExactly("req-prompt", "req-nested");

		// rejected items are not stored; the loosely formatted one is stored untouched (non-strict default)
		assertThat(usageLogRepository.findAll()).extracting(UsageLog::getRequestId)
			.containsExactlyInAnyOrder("req-ok", "req-sloppy");
		UsageLog sloppyRow = usageLogRepository.findAll().stream()
			.filter(log -> "req-sloppy".equals(log.getRequestId())).findFirst().orElseThrow();
		// H2 keeps a json column as an escaped JSON string; compare content, not quoting
		assertThat(sloppyRow.getMetadataJson()).contains("count", "k16", "k0");
	}
}
