# #15 Advisory 판정 API

계약: [`docs/api/control-plane-v1.yaml`](../api/control-plane-v1.yaml) `POST /api/control/v1/decisions`. 책임 경계: [ADR 0001](../adr/0001-decision-enforcement-boundary.md). 운영: [판정 API 롤아웃 런북](../runbooks/decision-api-rollout.md).

## 요약

고객 정책을 평가해 `ALLOW`·`DENY_*`·`INDETERMINATE` 권고를 돌려주는 Advisory API를 추가한다. 판정은 권고일 뿐 서버는 provider 호출을 막지 않고, 집행 여부는 Client SDK의 `EnforcementSettings`가 정한다. 같은 `Idempotency-Key`와 같은 본문은 처음 응답을 그대로 돌려주고, 판정은 요청 시점에 묶은 불변 정책·가격 snapshot으로 재현된다. 응답과 저장 어디에도 원문 prompt는 없다.

기본값은 꺼짐(`token-pilot.decision.enabled=false`)이며 꺼져 있으면 `404`다.

## 범위

**포함**
- 최대 예상 비용(프로젝트·환경·모델·토큰 상한)과 정책 평가: `AdvisoryEvaluator`(순수 함수)
- 한 요청에 정책·가격 snapshot 묶기: `PolicyContextService.capture`(#14의 연동 지점)
- 멱등 처리와 판정 기록: `DecisionService`, `decision_records`(V8)
- 컨트롤러, 오류 변환, 기능 플래그, 본문 크기 제한, 계약 갱신

**제외**
- 실제 호출 여부 이벤트: 수집 코드를 건드려 PR #23과 충돌할 수 있어 뺀다.
- 예약(#17)과 승인(`REQUIRE_APPROVAL`): `ENFORCE` 요청도 받지만 Advisory로만 판정하고 `reservation`은 발급하지 않는다.
- Shadow 실행·예상 거부율(#16), 집행(#18/#19), 속도 제한(`429`).

## 변경 요약

커밋 순서대로다. 제목만 적는다.

| 커밋 | 내용 |
| --- | --- |
| `feat(decision): Advisory 판정 순수 로직 추가` + `fix(decision): 판정 reason 이름, retryAt, 상한 비용 올림 반영` | `AdvisoryEvaluator`. DB·시계를 읽지 않아 입력이 같으면 결과가 같다. |
| `docs(contract): 판정 응답 선택 필드·404·reason 목록 추가` | `DecisionResponse`에 선택 필드 추가, 404 응답, reason 코드 목록 |
| `feat(decision): 판정 기록 테이블(V8)과 requestId 128자 확장` | `decision_records`, `policy_request_contexts.request_id`를 128자로 |
| `feat(decision): Advisory 판정 서비스와 멱등 처리` | `DecisionService`, 키만으로 인증하는 오버로드, 요청 지문 |
| `feat(decision): 판정 API 컨트롤러와 오류 변환, 기능 플래그` | 컨트롤러, 전용 오류 변환, 필터 연결, 403·413 응답 |
| `build: 테스트 JVM 힙 1536m 설정` | 전체 테스트가 기본 힙에서 끝나지 않아 `build.gradle`에 힙 설정 |

**기존 코드에 닿는 곳** (동작은 바뀌지 않는다)
- `ProjectApiKeyAuthenticator`: `authenticate(rawApiKey, environment)` 오버로드 추가. 기존 검증은 private 메서드로 뽑아 함께 쓴다.
- `IngestionPaths`, `ApiKeyAuthenticationFilter`, `IngestionRequestSizeFilter`, `SecurityConfig`: 판정 경로가 수집 경로와 같은 정규화 경로로 필터를 받게 한다.
- `PolicyRequestContext`, `PolicyContextService`: requestId 길이 100에서 128로. context를 처음 묶은 요청의 본문 지문(`request_fingerprint`, V8)을 함께 저장한다.

## 판정 규칙

- **최대 예상 비용**: 입력 토큰은 prompt·cachedPrompt 중 비싼 요율로, 출력 토큰 상한(`maxOutputTokens`)은 completion·reasoning 중 비싼 요율로 계산한다. 별도 요율이 null이면 기본 요율을 쓰고, 합계는 소수 6자리에서 올림한다. SDK가 보낸 `estimatedCostUsd`·`pricingVersion`은 판정에 쓰지 않고, 가격이 없을 때 대신 쓰지도 않는다.
- **범위**: 활성인 모든 범위(프로젝트, 환경)를 함께 만족해야 한다. 환경 정책이 프로젝트 정책을 완화하지 않는다.
- **한도 비교**: 초과(`>`) 기준이며 hard와 soft에 같은 기준을 쓴다. 월 누적은 UTC 달력 월이다.
- **요청당 비용 한도** 초과도 `DENY_BUDGET`이다. 그래서 고객이 `enforcedOutcomes`를 좁혀도 요청당 한도만 따로 집행할 수는 없다.
- **정책 없음**(legacy 예산만 있는 범위 포함)은 `INDETERMINATE`(`POLICY_NOT_CONFIGURED`), **가격 없음**은 `INDETERMINATE`(`PRICING_NOT_FOUND`), **정책이 모두 비활성**이면 `ALLOW`(`POLICY_INACTIVE`)이다.
- **재시도 시각**(`retryAt`)은 거부 사유가 전부 시간이 지나면 풀리는 월 한도일 때만 다음 UTC 월 1일 00:00이다. 하나라도 기다려도 풀리지 않는 사유가 있으면 없다. SDK의 `retryAfter`(429/503 대기 시간)와 이름을 분리했다.
- reason 코드 목록은 yaml의 `DecisionReason` 설명에 있다.

**처리 순서**: 프로젝트 키 인증 → 멱등 키 조회(같은 지문이면 저장한 응답, 다르면 409) → 가격 snapshot 선택과 정책 `capture` → 같은 requestId의 다른 키 검사(409) → context를 묶은 본문 지문과 비교(다르면 409) → 월 지출 조회와 평가 → 저장. 지문 비교가 없으면 같은 requestId로 모델만 다른 요청이 동시에 올 때, 판정이 저장되기 전 틈에 다른 모델의 가격 snapshot으로 평가된다. 동시에 같은 키가 오면 유니크 위반을 잡아 새 트랜잭션에서 다시 조회한다. 서비스는 트랜잭션 밖에서 실행하며, 바깥 트랜잭션이 있으면 거부한다(`capture`의 프로젝트 행 잠금을 쥔 채 insert하지 않기 위해).

## 미확정 가정

팀장님 확인 없이 제안대로 정했거나, 구현하면서 정한 부분이다. **아래 표를 PR 설명에 그대로 옮긴다.**

`A`(선택 필드), `H`(soft 한도 표현), `I`(SDK 추정 비용), `K`~`Q`는 팀장님 답변으로 확정되었고, `F`(호출 여부 이벤트)는 범위에서 뺐다. 그래서 표에 없다.

| # | 가정 | 들어간 곳 |
| --- | --- | --- |
| B | `policies` 배열(범위, policyId, policyVersion, active)을 선택 필드로 추가하고 `policyVersion`은 유지한다. `policyVersion` 값은 `prj:<policyId>:<revision>`과 `env:<policyId>:<revision>`을 `.`로 이은 것이고, 정책이 없으면 `none`이다. | yaml `DecisionResponse`, `DecisionService` |
| C | decisions 경로에 `404` 응답(`DecisionApiDisabled`)을 추가한다. 경로가 등록되지 않은 일반 404라 본문에 `data.outcome`이 없고 `code`(현재 `COMMON-404`)도 고정하지 않는다. | yaml |
| D | `/api/control/v1/**` 전용 오류 응답 변환을 둔다. 계약의 오류 코드 문자열과 `data.outcome`, `Retry-After`를 쓴다. 기존 API의 오류 응답은 바꾸지 않는다. | `ControlPlaneExceptionHandler` |
| E | `policy_request_contexts.request_id`를 계약 `Identifier`와 같은 128자로 늘린다. 되돌려 줄이면 101자 이상 값이 있을 때 실패하므로 롤백은 기능 플래그를 끄는 것으로만 안내한다. | V8 |
| G | `clientMode: ENFORCE` 요청도 받지만 Advisory로만 판정한다. `reservation`은 발급하지 않고(#17) `effectiveMode`는 `ADVISORY`다. SDK가 `effectiveMode`를 어떻게 쓰는지는 확인하지 않았다. | yaml 설명, `DecisionService` |
| J | 같은 프로젝트·환경에서 같은 `requestId`가 다른 `Idempotency-Key`나 다른 본문으로 오면 `409` `IDEMPOTENCY_CONFLICT`다. | yaml 설명, `DecisionService` |
| N' | 응답 필드 이름은 `requiredCostUsd`, `remainingMonthlyBudgetUsd`, `retryAt`이다. 필드를 추가하자는 것은 팀장님 의견이고, 이름은 제안이다. SDK는 아직 200 응답에서 이 값들을 읽지 않는다. | yaml `DecisionResponse` |
| R | `remainingMonthlyBudgetUsd`는 이번 요청 전 기준, 평가한 활성 범위 중 가장 작은 월 hard 한도 잔액이다(음수면 0). | `AdvisoryEvaluator`, yaml |
| S | soft 요청당 한도 reason 이름을 `REQUEST_COST_SOFT_LIMIT_EXCEEDED`로 정했다. hard 쪽 이름 변경에 맞춘 것이다. | `AdvisoryEvaluator`, yaml |
| T | 월 지출은 `LedgerSpendRepository.sumConfirmedSpend`(최신 개정본이 ACTUAL·PRICED인 금액)만 쓴다. `LEGACY_UNVERIFIED`(기존 v1 ingestion) 행, 가격 미상·미정산(`UNSETTLED`) 행, 격리(`QUARANTINED`) 행은 빠진다. **v1 수집만 쓰는 고객은 월 지출이 실제보다 적게 잡혀 남은 예산이 크게 나오고, 월 한도 거부 권고가 늦게 나온다.** | `DecisionService` |
| U | `PolicyContextService.capture`가 `projects` 행을 `SELECT ... FOR UPDATE`로 잠그므로, **같은 프로젝트의 판정은 capture 구간에서 하나씩 직렬화된다.** 파일럿 규모를 가정했고 처리량은 측정하지 않았다(#14 설계가 #15/#17로 넘긴 항목). | `PolicyContextService`, `DecisionService` |
| V | 판정 키 인증에 `ProjectApiKeyAuthenticator.authenticate(rawApiKey, environment)` 오버로드를 추가했다. 본문에 `projectKey`가 없으므로 키의 프로젝트를 쓰고, 만료·환경·ACTIVE 검사는 기존과 같다. 수집 코드 파일을 건드린다. | `ProjectApiKeyAuthenticator` |
| W | 가격 단위는 Observe와 같은 `token-pilot.observe.ingestion.rate-unit-tokens`를 쓴다. 0이면 모든 판정이 `INDETERMINATE`(`PRICING_UNAVAILABLE`)다. | `DecisionService` |
| X | 동시 요청이 유니크 위반으로 부딪혔는데 이긴 행을 찾지 못하면 계약의 `503 CONTROL_PLANE_UNAVAILABLE`과 `Retry-After: 1`로 응답한다. 같은 요청을 다시 보내면 안전하다. | `DecisionService`, `ControlPlaneExceptionHandler` |
| Y | 같은 requestId의 context가 이미 있는데 가격이 묶이지 않았으면, 지금 가격이 있어도 묶인 snapshot을 바꾸지 않고 `PRICING_UNAVAILABLE`(`PRICE_NOT_BOUND`)로 판정한다. | `DecisionService` |
| Z | `decision_records`에 보존 기간이나 삭제 정책이 없다. 호출 한 건마다 JSON 두 개가 담긴 행이 쌓인다. | V8 |
| Z2 | 계약의 `environment`는 128자까지 허용하지만 DB와 정책 쪽은 20자다. 등록된 환경만 통과하므로 그대로 두었고, 21자 이상은 판정이 아니라 오류가 된다. | V7, 계약 |
| AA | 계약에 없던 `403` 응답(`Forbidden`)을 decisions에 추가했다. 키가 이 환경에서 쓸 수 없거나 프로젝트가 보관된 경우이며, 정책 거부가 아니라 `data.outcome: INDETERMINATE`다. 기존 인증기가 이미 403을 내던 경우를 계약에 옮긴 것이다. | yaml, `ControlPlaneExceptionHandler` |
| AB | 계약에 없던 `413` 응답(`PayloadTooLarge`)을 decisions에 추가했다. 본문이 단건 한도(`token-pilot.ingestion.max-event-bytes`, 기본 16384바이트)를 넘으면 인증과 JSON 파싱 전에 필터가 거부한다. 판정 요청에 별도 한도를 두지 않고 수집 단건 한도를 재사용했다. | yaml, `IngestionRequestSizeFilter` |
| AC | 기존 `ApiFieldError`(`field`, `rejectedValue`, `reason`)는 계약의 `{field, message}`와 맞지 않아 쓰지 않고 판정 전용 오류 필드 타입(`ControlPlaneErrorResponse.FieldError`)을 만들었다. 다른 API의 오류 응답은 바꾸지 않았다. 거부된 값은 응답에 싣지 않는다. | `ControlPlaneErrorResponse` |
| AD | 스키마 이름 `RouteNotFoundEnvelope`를 `PlainErrorEnvelope`로 바꿨다. `404`(경로 미등록)뿐 아니라 `413`처럼 필터·프레임워크가 만드는 `data`/`data.outcome` 없는 오류를 함께 설명하기 위해서다. 아직 배포된 계약이 아니라 호환 문제는 없다고 보았다. | yaml, 계약 테스트 |
| AE | `429 RATE_LIMITED`는 계약에 있지만 이번 PR에서 구현하지 않았다. 서버에 속도 제한 자체가 없다. 판정 호출이 몰릴 때의 보호는 별도 작업이 필요하다(`capture`의 프로젝트 행 잠금 직렬화는 U 참고). | 계약, 후속 작업 |
| AF | 판정 컨트롤러에서 예상하지 못한 예외(버그)는 계약의 `503 CONTROL_PLANE_UNAVAILABLE`이 아니라 전역 처리기의 `500`(`data` 없음)으로 나간다. 저장소 장애(`DataAccessException`, `TransactionException`)와 동시 요청 충돌 미해결만 `503`+`Retry-After`다. SDK는 5xx를 모두 같은 장애로 다룬다. | `ControlPlaneExceptionHandler` |
| AG | 크기 필터가 판정 경로에 항상 적용되므로, 기능 플래그가 꺼져 있어도 큰 본문은 `404`가 아니라 `413`을 받는다. SDK는 둘 다 4xx로 `INDETERMINATE`로 다룬다. | `IngestionRequestSizeFilter` |
| AH | `;`나 `//`가 든 경로는 Spring Security 방화벽이 필터 체인 앞에서 400으로 거부한다. 수집·정책 API도 같다. 끝 슬래시(`/decisions/`)는 컨트롤러에 연결되지 않아 404다. 모두 판정 API만의 동작이며 SDK가 보내는 경로에는 해당하지 않는다. | 테스트 `DecisionApiHttpTest` |

## 검증 결과와 한계

**결과**: 전체 테스트 502개, 실패 0, 오류 0, 건너뜀 16개(MySQL 전용). 판정 응답은 `control-plane-v1.yaml` 스키마로 검증한다. 경로 표기를 바꿔 보내는 HTTP 테스트도 있다. 크기 초과 본문은 모든 표기(`%64ecisions`, 끝 슬래시, `;`, `//`)에서 `413`이고, 프로젝트 키는 라우팅되는 표기(`%64ecisions`)에서 회원 키 필터에 가로채이지 않는다. `;`와 `//`는 필터가 아니라 Spring Security 방화벽이 400으로 거부한다(AH).

**한계** (리뷰어와 배포 담당자가 알아야 한다)

1. **MySQL 미검증.** 테스트는 H2에서 엔티티로 스키마를 만들고 Flyway를 끈다. 따라서 아래는 확인하지 않았다. C5 이후 일회용 MySQL에서 한 번에 확인한다.
   - `V8__decision_records.sql` 실행과 V1~V8 Flyway 적용
   - 외래 키 두 개(`projects`, `policy_request_contexts`)의 문자셋·collation 일치
   - `ddl-auto=validate`가 `DecisionRecord` 매핑(JSON 컬럼, enum 문자열)을 통과하는지
   - `request_id` 확장이 인플레이스로 되는지, `utf8mb4_bin` 키 비교 동작
   - check 제약(MySQL 8.0.16 미만에서는 무시된다)
   - `pricing_snapshots.snapshot_json`을 JSON 객체로 읽는지(H2는 문자열로 읽어 두 형태를 모두 처리하도록 했다)
   - FK 공유 잠금 교착. 구조로 막았지만(서비스를 바깥 트랜잭션에서 실행하면 거부) MySQL에서 실제로 보지 못했다.
2. **`429` 미구현.** 속도 제한이 없다(AE).
3. **테스트 힙 설정.** `build.gradle`에 `maxHeapSize = '1536m'`를 추가했다. 기본 힙(512MB)에서는 `@SpringBootTest` 컨텍스트가 쌓여 전체 실행이 끝나지 않았다. 1536m으로 502개가 약 30초에 끝났지만 실제 사용량은 측정하지 않았고, 컨텍스트가 더 늘면 다시 한계에 닿을 수 있다.
4. **SDK와 대조하지 않았다.** `token-pilot-client` 저장소의 `decisions.md` 마지막 절을 확인하지 못했다. SDK가 `effectiveMode`, `policies` 등 새 필드를 어떻게 다루는지는 팀장님 설명에 근거한다.
5. **#14 완료 기준의 end-to-end 검증이 없다.** 판정 API 호출 → 정책·가격 변경 → 같은 요청의 `GET .../request-context` 조회를 한 번에 확인하는 테스트가 없다. 연동은 되어 있다. 자세한 내용은 [#14 설계 문서](issue-14-policy-versions.md)의 "#15 연동 결과"에 있다.
6. **동시 요청 테스트의 경로.** 같은 키로 두 요청을 동시에 보내면 기록이 1건이고 응답이 같다는 것은 확인했지만, 두 스레드가 실제로 유니크 위반 재조회 경로까지 갔는지는 기록하지 않았다.
7. **요율 단위가 0인 경우**의 서비스 통합 테스트는 없다(판정 결과 자체는 평가기 단위 테스트가 확인한다).
8. **성능.** 같은 프로젝트의 판정이 직렬화되는 점(U)과 `decision_records` 증가(Z)는 측정하지 않았다.

## 운영

[판정 API 롤아웃 런북](../runbooks/decision-api-rollout.md)에 있다. 핵심만 적는다.

- 판정 API는 `token-pilot.decision.enabled=true`로 켠다. 기본은 꺼짐이고, 꺼지면 `404`다.
- **`ENFORCE`를 안내하기 전에 판정 API를 먼저 켠다.** SDK는 404를 `INDETERMINATE`로 다루므로 `on-indeterminate: fail-closed` 고객은 호출이 전부 막힌다.
- 정책이 없는 범위(legacy 예산만 있는 범위 포함)도 `INDETERMINATE`다. 정책을 만들거나 import한다.
- 앱이 `maxTokens`를 주지 않으면 SDK는 `maxOutputTokens`를 1024로 보낸다. 이때 서버가 계산한 상한은 실제 상한이 아니다.
- SDK의 Spring AI advisor는 아직 `reasons`를 앱에 넘기지 않는다. soft 경고는 당분간 서버 기록에서만 볼 수 있다.
