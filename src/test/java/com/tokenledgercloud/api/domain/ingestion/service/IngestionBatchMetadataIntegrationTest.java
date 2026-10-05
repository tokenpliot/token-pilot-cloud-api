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
	void forbiddenKeysAreDroppedAndTheItemIsStoredWhileLooseFormatStillPasses() {
		Map<String, Object> sloppy = new HashMap<>();
		for (int i = 0; i < 17; i++) {
			sloppy.put("k" + i, "v");
		}
		sloppy.put("count", 5);

		IngestionBatchResponse response = ingestionService.collectBatch("key", new IngestionBatchRequest(
			"support-copilot", "prod", List.of(
				item("req-ok", Map.of("feature", "summary")),
				item("req-prompt", Map.of("prompt", "SECRET-ITEM-TEXT", "feature", "kept")),
				item("req-sloppy", sloppy),
				item("req-nested", Map.of("ctx", Map.of("messages", List.of("SECRET-NESTED-TEXT"), "a", "b"))))));

		assertThat(response.items()).extracting(IngestionBatchItemResponse::status)
			.containsOnly(IngestionItemStatus.CREATED);
		assertThat(response.rejectedItems()).isEmpty();
		assertThat(response.toString()).doesNotContain("SECRET-ITEM-TEXT", "SECRET-NESTED-TEXT");

		assertThat(usageLogRepository.findAll()).extracting(UsageLog::getRequestId)
			.containsExactlyInAnyOrder("req-ok", "req-prompt", "req-sloppy", "req-nested");
		assertThat(metadataOf("req-prompt")).contains("feature", "kept")
			.doesNotContain("SECRET-ITEM-TEXT", "prompt");
		assertThat(metadataOf("req-nested")).contains("ctx", "a", "b")
			.doesNotContain("SECRET-NESTED-TEXT", "messages");
		// H2 keeps a json column as an escaped JSON string; compare content, not quoting
		assertThat(metadataOf("req-sloppy")).contains("count", "k16", "k0");
	}

	private String metadataOf(String requestId) {
		return usageLogRepository.findAll().stream()
			.filter(log -> requestId.equals(log.getRequestId())).findFirst().orElseThrow().getMetadataJson();
	}
}
