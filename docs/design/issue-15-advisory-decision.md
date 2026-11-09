# #15 Advisory 판정 API

계약: [`docs/api/control-plane-v1.yaml`](../api/control-plane-v1.yaml) `POST /api/control/v1/decisions`. 책임 경계: [ADR 0001](../adr/0001-decision-enforcement-boundary.md).

## 범위

- 판정은 권고다. 서버는 provider 호출을 막지 않고, 집행 여부는 SDK의 `EnforcementSettings`가 정한다.
- 판정은 `AdvisoryEvaluator`(순수 함수)가 한다. 같은 입력이면 같은 결과가 나온다.
- 가격과 정책은 서버가 고른 불변 snapshot에 묶는다(`PolicyContextService.capture`). SDK가 보낸 `estimatedCostUsd`·`pricingVersion`은 판정에 쓰지 않고, 가격이 없을 때 대신 쓰지도 않는다.
- 이번 PR에서 빼는 것: 실제 호출 여부 이벤트(수집 코드를 건드려 PR #23과 충돌할 수 있음), 예약(#17), 승인(`REQUIRE_APPROVAL`).

## 판정 규칙

- 최대 예상 비용: 입력 토큰은 prompt·cachedPrompt 중 비싼 요율로, 출력 토큰 상한(`maxOutputTokens`)은 completion·reasoning 중 비싼 요율로 계산한다. 별도 요율이 null이면 기본 요율을 쓰고, 합계는 소수 6자리에서 올림한다.
- 활성인 모든 범위(프로젝트, 환경)를 함께 만족해야 한다. 환경 정책이 프로젝트 정책을 완화하지 않는다.
- 한도 비교는 초과(`>`) 기준이며 hard와 soft에 같은 기준을 쓴다.
- 요청당 비용 한도 초과도 `DENY_BUDGET`이다. 그래서 고객이 `enforcedOutcomes`를 좁혀도 요청당 한도만 따로 집행할 수는 없다.
- 정책이 없으면(legacy 예산만 있는 범위 포함) `INDETERMINATE`이고, 정책이 모두 비활성이면 `ALLOW`(`POLICY_INACTIVE`)이다.
- reason 코드 목록은 yaml의 `DecisionReason` 설명에 있다.

## 운영 주의

- 판정 API가 꺼져 있거나 정책이 없으면 `INDETERMINATE`다. `on-indeterminate: fail-closed`를 고른 고객은 이때 호출이 전부 막힌다. `ENFORCE`를 안내하기 전에 판정 API를 켜고 정책을 만들거나 import한다.
- 앱이 `maxTokens`를 주지 않으면 SDK는 `maxOutputTokens`를 1024로 보낸다. 이 경우 서버가 계산한 상한은 실제 상한이 아니다.
- SDK의 Spring AI advisor는 아직 `reasons`를 앱에 넘기지 않는다. soft 경고는 당분간 서버 기록에서만 볼 수 있다.

## 미확정 가정

팀장님 확인 없이 제안대로 정한 부분이다. PR 설명에 그대로 옮긴다.

| # | 가정 | 들어간 곳 |
| --- | --- | --- |
| B | `policies` 배열(범위, policyId, policyVersion, active)을 optional로 추가하고 `policyVersion`은 유지한다. `policyVersion` 값은 `prj:<policyId>:<revision>`과 `env:<policyId>:<revision>`을 `.`로 이은 것이고, 정책이 없으면 `none`이다. | yaml `DecisionResponse`, C5 응답 매핑 |
| C | decisions 경로에 `404` 응답(`DecisionApiDisabled`)을 추가한다. 경로가 등록되지 않은 일반 404라 본문에 `data.outcome`이 없고 `code`(현재 `COMMON-404`)도 고정하지 않는다. | yaml |
| D | `/api/control/v1/**` 전용 오류 응답 변환을 둔다. 계약의 오류 코드 문자열과 `data.outcome`, `Retry-After`를 쓴다. 기존 API의 오류 응답은 바꾸지 않는다. | C5 |
| E | `policy_request_contexts.request_id`를 계약 `Identifier`와 같은 128자로 늘린다. | C3 (V8 마이그레이션) |
| G | `clientMode: ENFORCE` 요청도 받지만 Advisory로만 판정한다. `reservation`은 발급하지 않고(#17) `effectiveMode`는 `ADVISORY`다. SDK가 `effectiveMode`를 어떻게 쓰는지는 확인하지 않았다. | yaml 설명, C4/C5 |
| J | 같은 프로젝트·환경에서 같은 `requestId`가 다른 `Idempotency-Key`나 다른 본문으로 오면 `409` `IDEMPOTENCY_CONFLICT`다. | yaml 설명, C4 |
| N' | 응답 필드 이름은 `requiredCostUsd`, `remainingMonthlyBudgetUsd`, `retryAt`이다. 필드를 추가하자는 것은 팀장님 의견이고, 이름은 제안이다. SDK는 아직 200 응답에서 이 값들을 읽지 않는다. | yaml `DecisionResponse` |
| R | `remainingMonthlyBudgetUsd`는 이번 요청 전 기준, 평가한 활성 범위 중 가장 작은 월 hard 한도 잔액이다(음수면 0). | `AdvisoryEvaluator`, yaml |
| S | soft 요청당 한도 reason 이름을 `REQUEST_COST_SOFT_LIMIT_EXCEEDED`로 정했다. hard 쪽 이름 변경에 맞춘 것이다. | `AdvisoryEvaluator`, yaml |
