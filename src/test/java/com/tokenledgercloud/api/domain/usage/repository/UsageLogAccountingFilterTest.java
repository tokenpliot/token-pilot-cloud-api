package com.tokenledgercloud.api.domain.usage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.usage.entity.UsageLog;

/**
 * [이슈 #11] 기존(v0) 집계 쿼리가 LEGACY_UNVERIFIED 행만 읽는지 확인한다.
 * H2 + Hibernate 스키마 생성을 쓰고 Flyway는 끈다. (다른 통합 테스트와 같은 방식)
 */
// 이 테스트 전용 인메모리 DB를 만들고, Hibernate가 엔티티대로 테이블을 생성하게 한다.
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:usage-accounting-filter;DB_CLOSE_DELAY=-1",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false"
})
// 각 테스트가 끝나면 롤백해서 서로 영향을 주지 않게 한다.
@Transactional
class UsageLogAccountingFilterTest {

	// 테스트에서 공통으로 쓰는 프로젝트와 환경 값.
	private static final String PROJECT = "project-1";
	private static final String ENV = "prod";
	// 모든 테스트 행이 이 시각에 발생한 것으로 만든다.
	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 10, 12, 0);
	// 조회 기간: 2026-10-01 이상, 2026-11-01 미만.
	private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
	private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 1, 0, 0);

	@Autowired
	private UsageLogRepository usageLogRepository;

	// 테스트용 사용 로그를 만드는 도우미. status가 null이면 엔티티 기본값(LEGACY_UNVERIFIED)이 쓰인다.
	private UsageLog log(String eventId, String status, String promptCost) {
		return UsageLog.builder()
			.organizationId("org-1")
			.projectId(PROJECT)
			.environment(ENV)
			.requestId("req-" + eventId)
			.eventId(eventId)
			.provider("openai")
			.model("gpt-test")
			.promptCostUsd(new BigDecimal(promptCost))
			.pricingVersion("legacy")
			.accountingStatus(status)
			.occurredAt(OCCURRED_AT)
			.build();
	}

	@Test
	void statusDefaultsToLegacyUnverifiedWhenNotSpecified() {
		// 상태를 지정하지 않고 저장한다. (기존 수집 경로와 같은 상황)
		UsageLog saved = usageLogRepository.saveAndFlush(log("event-default", null, "1.000000"));
		// 저장된 상태가 LEGACY_UNVERIFIED여야 한다.
		assertThat(saved.getAccountingStatus()).isEqualTo("LEGACY_UNVERIFIED");
	}

	@Test
	void sumTotalCostExcludesNonLegacyRows() {
		// 기존 행(1달러)과 새 방식 행(5달러)을 저장한다.
		usageLogRepository.saveAndFlush(log("event-legacy", "LEGACY_UNVERIFIED", "1.000000"));
		usageLogRepository.saveAndFlush(log("event-pending", "PENDING", "5.000000"));
		// 기존 집계는 기존 행의 1달러만 더해야 한다.
		BigDecimal total = usageLogRepository.sumTotalCostUsd(PROJECT, ENV, FROM, TO);
		assertThat(total).isEqualByComparingTo("1.000000");
	}

	@Test
	void kpiExcludesNonLegacyRows() {
		// 기존 행(2달러)과 격리된 행(7달러)을 저장한다.
		usageLogRepository.saveAndFlush(log("event-legacy", "LEGACY_UNVERIFIED", "2.000000"));
		usageLogRepository.saveAndFlush(log("event-quarantined", "QUARANTINED", "7.000000"));
		// KPI의 총 비용도 기존 행의 2달러만이어야 한다.
		var kpi = usageLogRepository.getKpi(PROJECT, FROM, TO);
		assertThat(kpi.getTotalCost()).isEqualByComparingTo("2.000000");
	}

	@Test
	void recentEventsExcludeNonLegacyRows() {
		// 기존 행과 검증 완료 행을 저장한다.
		usageLogRepository.saveAndFlush(log("event-legacy", "LEGACY_UNVERIFIED", "1.000000"));
		usageLogRepository.saveAndFlush(log("event-verified", "VERIFIED", "3.000000"));
		// 최근 이벤트 목록에는 기존 행만 나와야 한다.
		List<UsageLog> recent = usageLogRepository.findRecentUsageEvents(
			PROJECT, ENV, null, null, null, PageRequest.of(0, 10));
		assertThat(recent).extracting(UsageLog::getEventId).containsExactly("event-legacy");
	}

	@Test
	void idempotencyLookupStillFindsNonLegacyRows() {
		// 새 방식 행을 저장한다.
		usageLogRepository.saveAndFlush(log("event-pending", "PENDING", "5.000000"));
		// 중복 판정용 조회는 상태와 무관하게 이 행을 찾아야 한다. (필터를 걸면 중복 이벤트가 또 저장된다)
		assertThat(usageLogRepository.findByProjectIdAndEnvironmentAndEventId(PROJECT, ENV, "event-pending"))
			.isPresent();
	}
}
