package com.tokenledgercloud.api.domain.accounting.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.global.exception.ApiException;

/**
 * 가격 스냅샷 생성의 멱등성과 검증 규칙을 확인한다. H2 + Hibernate 스키마 생성을 쓰고 Flyway는 끈다.
 * 서비스가 REQUIRES_NEW로 커밋하므로 테스트에 @Transactional을 붙이지 않고, 테스트마다 서로 다른 버전을 쓴다.
 */
// 이 테스트 전용 인메모리 DB를 만들고, Hibernate가 엔티티대로 테이블을 생성하게 한다.
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:pricing-snapshot;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
})
class PricingSnapshotServiceTest {

	@Autowired PricingSnapshotService service;
	@Autowired PricingSnapshotRepository repository;

	// 테스트용 가격표를 만드는 도우미. 버전과 프롬프트 요율, 통화만 바꿔서 쓴다.
	private static PricingPlan plan(String version, String promptRate, String currency) {
		return PricingPlan.builder()
				.id("plan-" + version)
				.provider("openai")
				.model("gpt-test")
				.currency(currency)
				.promptRate(new BigDecimal(promptRate))
				.completionRate(new BigDecimal("2.000000"))
				.version(version)
				.effectiveFrom(LocalDateTime.of(2026, 10, 1, 0, 0))
				.build();
	}

	@Test
	void captureCopiesRatesAndComputesChecksum() {
		// 가격표를 스냅샷으로 남긴다.
		PricingSnapshot snapshot = service.capture(plan("v-copy", "1.500000", "USD"), 1_000_000L, "SYSTEM", "tester");
		// 출처와 단위, 체크섬(64자)이 저장된다.
		assertThat(snapshot.getPricingPlanId()).isEqualTo("plan-v-copy");
		assertThat(snapshot.getRateUnitTokens()).isEqualTo(1_000_000L);
		assertThat(snapshot.getChecksum()).hasSize(64);
		// JSON에 복사한 요율이 들어 있다. 없는 요율(reasoning)은 null이다.
		assertThat(snapshot.getSnapshotJson()).contains("\"prompt\":\"1.500000\"", "\"reasoning\":null");
	}

	@Test
	void sameVersionAndContentReturnsExistingSnapshot() {
		// 같은 가격표를 두 번 남긴다.
		PricingSnapshot first = service.capture(plan("v-same", "1.500000", "USD"), 1_000_000L, "SYSTEM", "tester");
		PricingSnapshot second = service.capture(plan("v-same", "1.500000", "USD"), 1_000_000L, "SYSTEM", "tester");
		// 같은 스냅샷을 돌려주고 행은 하나뿐이다.
		assertThat(second.getId()).isEqualTo(first.getId());
		assertThat(repository.findByProviderAndModelAndCurrencyAndVersion("openai", "gpt-test", "USD", "v-same"))
				.isPresent();
		assertThat(repository.findAll().stream().filter(row -> row.getVersion().equals("v-same")).count()).isEqualTo(1);
	}

	@Test
	void sameVersionWithDifferentRateIsRejected() {
		// 같은 버전을 먼저 남긴다.
		service.capture(plan("v-conflict", "1.500000", "USD"), 1_000_000L, "SYSTEM", "tester");
		// 같은 버전인데 요율이 다르면 거절되어야 한다. (과거 비용이 조용히 바뀌는 것을 막는다)
		assertThatThrownBy(() -> service.capture(plan("v-conflict", "9.999999", "USD"), 1_000_000L, "SYSTEM", "tester"))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void sameVersionWithDifferentRateUnitIsRejected() {
		// 요율 숫자가 같아도 토큰 단위가 다르면 다른 가격표다.
		service.capture(plan("v-unit", "1.500000", "USD"), 1_000_000L, "SYSTEM", "tester");
		assertThatThrownBy(() -> service.capture(plan("v-unit", "1.500000", "USD"), 1_000L, "SYSTEM", "tester"))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void invalidInputIsRejected() {
		// USD가 아닌 통화는 거부한다.
		assertThatThrownBy(() -> service.capture(plan("v-bad1", "1.5", "KRW"), 1_000_000L, "SYSTEM", "tester"))
				.isInstanceOf(IllegalArgumentException.class);
		// 토큰 단위가 0 이하면 거부한다.
		assertThatThrownBy(() -> service.capture(plan("v-bad2", "1.5", "USD"), 0L, "SYSTEM", "tester"))
				.isInstanceOf(IllegalArgumentException.class);
		// 기록 주체가 비어 있으면 거부한다.
		assertThatThrownBy(() -> service.capture(plan("v-bad3", "1.5", "USD"), 1_000_000L, " ", "tester"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
