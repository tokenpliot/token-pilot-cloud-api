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
| T | 월 지출은 `LedgerSpendRepository.sumConfirmedSpend`(최신 개정본이 ACTUAL·PRICED인 금액)만 쓴다. `LEGACY_UNVERIFIED`(기존 v1 ingestion) 행, 가격 미상·미정산(`UNSETTLED`) 행, 격리(`QUARANTINED`) 행은 빠진다. **v1 수집만 쓰는 고객은 월 지출이 실제보다 적게 잡혀 남은 예산이 크게 나오고, 월 한도 거부 권고가 늦게 나온다.** | `DecisionService` |
| U | `PolicyContextService.capture`가 `projects` 행을 `SELECT ... FOR UPDATE`로 잠그므로, **같은 프로젝트의 판정은 capture 구간에서 하나씩 직렬화된다.** 파일럿 규모를 가정했고 처리량은 측정하지 않았다(#14 설계가 #15/#17로 넘긴 항목). | `PolicyContextService`, `DecisionService` |
| V | 판정 키 인증에 `ProjectApiKeyAuthenticator.authenticate(rawApiKey, environment)` 오버로드를 추가했다. 본문에 `projectKey`가 없으므로 키의 프로젝트를 쓰고, 만료·환경·ACTIVE 검사는 기존과 같다. 수집 코드 파일을 건드린다. | `ProjectApiKeyAuthenticator` |
| W | 가격 단위는 Observe와 같은 `token-pilot.observe.ingestion.rate-unit-tokens`를 쓴다. 0이면 모든 판정이 `INDETERMINATE`(`PRICING_UNAVAILABLE`)다. | `DecisionService` |
| X | 동시 요청이 유니크 위반으로 부딪혔는데 이긴 행을 찾지 못하면 `503`(현재 `INGESTION_RETRY_LATER`)이다. C5에서 계약의 `CONTROL_PLANE_UNAVAILABLE`과 `Retry-After`로 바꾼다. | `DecisionService`, C5 |
| Y | 같은 requestId의 context가 이미 있는데 가격이 묶이지 않았으면, 지금 가격이 있어도 묶인 snapshot을 바꾸지 않고 `PRICING_UNAVAILABLE`(`PRICE_NOT_BOUND`)로 판정한다. | `DecisionService` |
| Z | `decision_records`에 보존 기간이나 삭제 정책이 없다. 호출 한 건마다 JSON 두 개가 담긴 행이 쌓인다. | V8 |
| Z2 | 계약의 `environment`는 128자까지 허용하지만 DB와 정책 쪽은 20자다. 등록된 환경만 통과하므로 그대로 두었고, 21자 이상은 판정이 아니라 오류가 된다. | V7, 계약 |
| AA | 계약에 없던 `403` 응답(`Forbidden`)을 decisions에 추가했다. 키가 이 환경에서 쓸 수 없거나 프로젝트가 보관된 경우이며, 정책 거부가 아니라 `data.outcome: INDETERMINATE`다. 기존 인증기가 이미 403을 내던 경우를 계약에 옮긴 것이다. | yaml, `ControlPlaneExceptionHandler` |
| AB | 계약에 없던 `413` 응답(`PayloadTooLarge`)을 decisions에 추가했다. 본문이 단건 한도(`token-pilot.ingestion.max-event-bytes`, 기본 16384바이트)를 넘으면 인증과 JSON 파싱 전에 필터가 거부한다. 판정 요청에 별도 한도를 두지 않고 수집 단건 한도를 재사용했다. | yaml, `IngestionRequestSizeFilter` |
| AC | 기존 `ApiFieldError`(`field`, `rejectedValue`, `reason`)는 계약의 `{field, message}`와 맞지 않아 쓰지 않고 판정 전용 오류 필드 타입(`ControlPlaneErrorResponse.FieldError`)을 만들었다. 다른 API의 오류 응답은 바꾸지 않았다. 거부된 값은 응답에 싣지 않는다. | `ControlPlaneErrorResponse` |
| AD | C1에서 만든 스키마 이름 `RouteNotFoundEnvelope`를 `PlainErrorEnvelope`로 바꿨다. `404`(경로 미등록)뿐 아니라 `413`처럼 필터·프레임워크가 만드는 `data`/`data.outcome` 없는 오류를 함께 설명하기 위해서다. 아직 배포된 계약이 아니라 호환 문제는 없다고 보았다. | yaml, 계약 테스트 |
| AE | `429 RATE_LIMITED`는 계약에 있지만 이번 PR에서 구현하지 않았다. 서버에 속도 제한 자체가 없다. 판정 호출이 몰릴 때의 보호는 별도 작업이 필요하다(`capture`의 프로젝트 행 잠금 직렬화는 U 참고). | 계약, 후속 작업 |
| AF | 판정 컨트롤러에서 예상하지 못한 예외(버그)는 계약의 `503 CONTROL_PLANE_UNAVAILABLE`이 아니라 전역 처리기의 `500`(`data` 없음)으로 나간다. 저장소 장애(`DataAccessException`, `TransactionException`)와 동시 요청 충돌 미해결만 `503`+`Retry-After`다. SDK는 5xx를 모두 같은 장애로 다룬다. | `ControlPlaneExceptionHandler` |
| AG | 크기 필터가 판정 경로에 항상 적용되므로, 기능 플래그가 꺼져 있어도 큰 본문은 `404`가 아니라 `413`을 받는다. SDK는 둘 다 4xx로 `INDETERMINATE`로 다룬다. | `IngestionRequestSizeFilter` |
| AH | `;`나 `//`가 든 경로는 Spring Security 방화벽이 필터 체인 앞에서 400으로 거부한다. 수집·정책 API도 같다. 끝 슬래시(`/decisions/`)는 컨트롤러에 연결되지 않아 404다. 모두 판정 API만의 동작이 아니며 SDK가 보내는 경로에는 해당하지 않는다. | 테스트 `DecisionApiHttpTest` |
