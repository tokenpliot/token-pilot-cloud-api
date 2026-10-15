package com.tokenledgercloud.api.domain.accounting.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * pricing_snapshots 테이블의 한 행. 비용 계산에 쓴 가격표를 그 시점 그대로 복사해 둔 것이다.
 * 나중에 pricing_plans가 바뀌어도 과거 비용이 변하지 않도록 한 번 만들면 수정하지 않는다.
 */
// JPA 엔티티임을 선언한다.
@Entity
// 테이블 이름과 (공급자, 모델, 통화, 버전) UNIQUE 제약을 선언한다. (V6 SQL의 uk_price_snapshot_version과 같다)
@Table(name = "pricing_snapshots", uniqueConstraints = {
		@UniqueConstraint(name = "uk_price_snapshot_version",
				columnNames = {"provider", "model", "currency", "version"})
})
// Hibernate가 이 엔티티의 UPDATE를 보내지 않도록 한다.
@Immutable
// 모든 필드의 getter를 만든다.
@Getter
// JPA가 쓰는 기본 생성자. 외부에서는 쓰지 못하게 protected로 둔다.
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PricingSnapshot {

	// 스냅샷 내용을 만든 규칙의 이름. 규칙이 바뀌면 이 값을 올려서 옛 스냅샷과 구분한다.
	static final String ALGORITHM = "price-snapshot-v1";

	// 기본키. UUID 문자열(36자).
	@Id
	@Column(length = 36, updatable = false)
	private String id;

	// 가격표 버전 (pricing_plans.version과 같은 값).
	@Column(nullable = false, length = 128, updatable = false)
	private String version;

	// 어떤 pricing_plans 행에서 복사했는지 출처. 이후 계산에는 쓰지 않는다.
	@Column(name = "pricing_plan_id", length = 36, updatable = false)
	private String pricingPlanId;

	// 공급자.
	@Column(nullable = false, length = 128, updatable = false)
	private String provider;

	// 모델.
	@Column(nullable = false, length = 128, updatable = false)
	private String model;

	// 통화. 현재는 USD만 허용한다.
	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	// 요율이 몇 토큰당 가격인지. 예를 들어 100만 토큰당 가격이면 1000000.
	@Column(name = "rate_unit_tokens", nullable = false, updatable = false)
	private long rateUnitTokens;

	// 복사한 요율과 유효 기간을 담은 JSON 문자열.
	@Column(name = "snapshot_json", nullable = false, columnDefinition = "json", updatable = false)
	private String snapshotJson;

	// snapshotJson의 SHA-256(소문자 hex 64자). 같은 버전에 다른 내용이 들어오는지 판별한다.
	@Column(nullable = false, length = 64, updatable = false)
	private String checksum;

	// 스냅샷을 만든 주체의 종류와 식별자.
	@Column(name = "actor_type", nullable = false, length = 30, updatable = false)
	private String actorType;

	@Column(name = "actor_id", nullable = false, length = 128, updatable = false)
	private String actorId;

	// 생성 시각.
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/**
	 * 가격표 한 줄을 복사해 새 스냅샷을 만든다.
	 *
	 * @param rateUnitTokens 요율의 토큰 단위. 기존 pricing_plans에는 이 값이 없어서, 출처를 확인한 사람이 직접 넘겨야 한다.
	 */
	public static PricingSnapshot fromPlan(PricingPlan plan, long rateUnitTokens, String actorType, String actorId) {
		// 필수 입력 검사.
		if (plan == null) {
			throw new IllegalArgumentException("plan must not be null");
		}
		if (rateUnitTokens <= 0) {
			throw new IllegalArgumentException("rateUnitTokens must be positive");
		}
		// DB의 ck_price_snapshot_currency와 같은 규칙: USD만 허용한다.
		if (!"USD".equals(plan.getCurrency())) {
			throw new IllegalArgumentException("only USD is supported");
		}
		requireText(actorType, "actorType");
		requireText(actorId, "actorId");

		// 요율과 유효 기간을 JSON으로 복사한다. 키 순서가 고정이라 같은 입력은 항상 같은 JSON이 된다.
		String json = "{"
				+ "\"algorithm\":" + quote(ALGORITHM)
				+ ",\"provider\":" + quote(plan.getProvider())
				+ ",\"model\":" + quote(plan.getModel())
				+ ",\"currency\":" + quote(plan.getCurrency())
				+ ",\"version\":" + quote(plan.getVersion())
				+ ",\"rateUnitTokens\":" + rateUnitTokens
				+ ",\"rates\":{"
				+ "\"prompt\":" + rate(plan.getPromptRate())
				+ ",\"completion\":" + rate(plan.getCompletionRate())
				+ ",\"reasoning\":" + rate(plan.getReasoningRate())
				+ ",\"cachedPrompt\":" + rate(plan.getCachedPromptRate())
				+ "}"
				+ ",\"effectiveFrom\":" + quote(String.valueOf(plan.getEffectiveFrom()))
				+ ",\"effectiveTo\":" + (plan.getEffectiveTo() == null ? "null" : quote(plan.getEffectiveTo().toString()))
				+ "}";

		// 엔티티를 만들어 값을 채운다.
		PricingSnapshot snapshot = new PricingSnapshot();
		snapshot.id = UUID.randomUUID().toString();
		snapshot.version = plan.getVersion();
		snapshot.pricingPlanId = plan.getId();
		snapshot.provider = plan.getProvider();
		snapshot.model = plan.getModel();
		snapshot.currency = plan.getCurrency();
		snapshot.rateUnitTokens = rateUnitTokens;
		snapshot.snapshotJson = json;
		snapshot.checksum = sha256Hex(json);
		snapshot.actorType = actorType;
		snapshot.actorId = actorId;
		snapshot.createdAt = LocalDateTime.now();
		return snapshot;
	}

	// 요율을 JSON 값으로 바꾼다. 없는 요율은 null, 있는 요율은 문자열로 둔다. (소수점이 변하지 않게)
	private static String rate(java.math.BigDecimal value) {
		return value == null ? "null" : quote(value.toPlainString());
	}

	// 문자열을 JSON 문자열로 바꾼다. 따옴표, 역슬래시, 제어 문자를 이스케이프한다.
	private static String quote(String value) {
		StringBuilder builder = new StringBuilder("\"");
		for (char ch : value.toCharArray()) {
			switch (ch) {
				case '"' -> builder.append("\\\"");
				case '\\' -> builder.append("\\\\");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				default -> {
					if (ch < 0x20) {
						builder.append(String.format("\\u%04x", (int) ch));
					} else {
						builder.append(ch);
					}
				}
			}
		}
		return builder.append('"').toString();
	}

	// 문자열의 SHA-256을 소문자 hex로 계산한다.
	private static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			// SHA-256은 모든 자바 구현이 지원하므로 여기에 올 일이 없다.
			throw new IllegalStateException(exception);
		}
	}

	// 문자열이 null이거나 공백뿐이면 예외를 던지는 도우미 메서드.
	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
