package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tokenledgercloud.api.domain.accounting.CallStatus;
import com.tokenledgercloud.api.domain.accounting.TokenSemantics;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.ingestion.dto.ObserveEventRequest;

/** 요율 단위를 설정하지 않으면(기본값) 모든 이벤트가 0달러가 아니라 가격 미상으로 기록되는지 확인한다. */
// 요율 단위 설정 없이 이 테스트 전용 인메모리 DB를 만든다.
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:observe-ingestion-unconfirmed;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
})
class ObserveIngestionUnconfirmedUnitTest {

	@Autowired ObserveIngestionService service;
	@Autowired UsageAccountingValueRepository valueRepository;

	// API 키 검증은 가짜로 대체한다.
	@MockitoBean ProjectApiKeyAuthenticator authenticator;

	@Test
	void everythingIsUnpricedUntilRateUnitIsConfirmed() {
		when(authenticator.authenticate(any(), any(), any()))
				.thenReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		var response = service.collect("api-key", new ObserveEventRequest("project-key", "prod", "obs-unit-1",
				"req-unit-1", "observe-v1", TokenSemantics.INCLUSIVE_V1, "openai", "any-model",
				CallStatus.SUCCEEDED, 100L, 50L, 0L, 0L, OffsetDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneOffset.UTC)));
		// 금액은 0이 아니라 null이고, 사유는 요율 단위 미확인이다.
		var values = valueRepository.findAll().stream()
				.filter(value -> value.getUsageEventId().equals(response.usageEventId())).toList();
		assertThat(values).hasSize(1);
		assertThat(values.get(0).getAmount()).isNull();
		assertThat(values.get(0).getReasonCode()).isEqualTo("RATE_UNIT_UNCONFIRMED");
	}
}
