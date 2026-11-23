package com.tokenledgercloud.api.domain.rollout.metrics;

import java.util.Locale;

import io.micrometer.core.instrument.Metrics;

/**
 * 정책 거부·중앙 장애·provider 실패를 서로 다른 운영 지표로 센다. (이슈 #19)
 *
 * <p>지표 이름과 태그를 한 곳에서 정한다. 다른 도메인 코드는 생성자를 바꾸지 않고 호출 한 줄만 넣도록 정적 메서드로 둔다.
 * Spring Boot는 기본 설정(management.metrics.use-global-registry=true)에서 자동 구성한 레지스트리(Prometheus 등)를
 * Micrometer 전역 레지스트리에 연결하므로, 여기서 센 값이 /actuator/prometheus에 그대로 나온다.
 *
 * <p>구분 기준(Prometheus 이름):
 * <ul>
 *   <li>정책 거부: {@code tokenpilot_decision_outcome_total{outcome="DENY_POLICY"|"DENY_BUDGET"|"REQUIRE_APPROVAL"}}</li>
 *   <li>중앙 장애: {@code tokenpilot_control_plane_unavailable_total{reason=...}} — 판정이 아니라 Token Pilot 쪽 문제다</li>
 *   <li>provider 실패: {@code tokenpilot_ingestion_call_status_total{status="PROVIDER_FAILED"}}</li>
 *   <li>break-glass: {@code tokenpilot_break_glass_total{action="ISSUED"|"REVOKED"}}</li>
 * </ul>
 * 태그에는 프로젝트·조직 id를 넣지 않는다. 값이 늘어날 때마다 시계열이 늘고, 지표 공개 범위에서 고객이 드러나기 때문이다.
 */
public final class RolloutMetrics {

	public static final String DECISION_OUTCOME = "tokenpilot.decision.outcome";
	public static final String CONTROL_PLANE_UNAVAILABLE = "tokenpilot.control_plane.unavailable";
	public static final String INGESTION_CALL_STATUS = "tokenpilot.ingestion.call_status";
	public static final String BREAK_GLASS = "tokenpilot.break_glass";

	/** 중앙 장애 사유: 저장소(DB) 장애. */
	public static final String REASON_STORE_DOWN = "STORE_DOWN";
	/** 중앙 장애 사유: 동시 요청 충돌을 풀지 못해 재시도를 요청함. */
	public static final String REASON_RETRY_LATER = "RETRY_LATER";

	private RolloutMetrics() {
	}

	/** 새로 저장된 판정 하나. 멱등 재전송으로 처음 응답을 돌려준 경우는 세지 않는다. */
	public static void decision(Enum<?> outcome) {
		Metrics.counter(DECISION_OUTCOME, "outcome", name(outcome)).increment();
	}

	/** 판정·집행 API가 Token Pilot 쪽 문제로 503을 돌려준 경우. */
	public static void controlPlaneUnavailable(String reason) {
		Metrics.counter(CONTROL_PLANE_UNAVAILABLE, "reason", reason == null ? "UNKNOWN" : reason).increment();
	}

	/** 새로 기록된 사용량 이벤트 하나의 호출 결과. 중복 재전송은 세지 않는다. */
	public static void ingestionCallStatus(Enum<?> callStatus) {
		Metrics.counter(INGESTION_CALL_STATUS, "status", name(callStatus)).increment();
	}

	/** break-glass 발급(ISSUED)·취소(REVOKED). */
	public static void breakGlass(String action) {
		Metrics.counter(BREAK_GLASS, "action", action.toUpperCase(Locale.ROOT)).increment();
	}

	private static String name(Enum<?> value) {
		return value == null ? "UNKNOWN" : value.name();
	}
}
