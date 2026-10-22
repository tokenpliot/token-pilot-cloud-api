package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tokenledgercloud.api.domain.accounting.CallStatus;
import com.tokenledgercloud.api.domain.accounting.TokenSemantics;
import com.tokenledgercloud.api.domain.ingestion.config.IngestionProperties;
import com.tokenledgercloud.api.domain.ingestion.dto.ObserveEventRequest;
import com.tokenledgercloud.api.domain.ingestion.validation.IngestionMetadataPolicy;

/** Observe 지문에 metadata가 들어가는 규칙. 저장용 JSON은 v1과 같은 serializer로 만든다. */
class ObserveEventFingerprintTest {

	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 1, 0, 0);
	private final IngestionMetadataSerializer serializer =
			new IngestionMetadataSerializer(new IngestionMetadataPolicy(IngestionProperties.defaults()));

	private String fingerprint(Map<String, Object> metadata) {
		ObserveEventRequest request = new ObserveEventRequest("alpha", "prod", "evt-1", null, "observe-v1",
				TokenSemantics.INCLUSIVE_V1, "openai", "m", CallStatus.SUCCEEDED, 10L, 5L, null, null,
				OffsetDateTime.of(OCCURRED_AT, ZoneOffset.UTC), metadata);
		return ObserveEventFingerprint.of(request, OCCURRED_AT, serializer.toJson(metadata));
	}

	@Test
	void absentAndEmptyMetadataFingerprintTheSame() {
		assertThat(fingerprint(Map.of())).isEqualTo(fingerprint(null));
		// 금지 키만 있던 metadata도 버리고 나면 비어 있으므로 같다.
		assertThat(fingerprint(Map.of("prompt", "SECRET"))).isEqualTo(fingerprint(null));
	}

	@Test
	void keyOrderDoesNotMatter() {
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("trace_id", "t-1");
		first.put("app_request_id", "a-1");
		Map<String, Object> second = new LinkedHashMap<>();
		second.put("app_request_id", "a-1");
		second.put("trace_id", "t-1");
		assertThat(fingerprint(first)).isEqualTo(fingerprint(second));
	}

	@Test
	void metadataValuesChangeTheFingerprint() {
		assertThat(fingerprint(Map.of("trace_id", "t-1")))
				.isNotEqualTo(fingerprint(Map.of("trace_id", "t-2")))
				.isNotEqualTo(fingerprint(null));
	}
}
