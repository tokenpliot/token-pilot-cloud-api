# Shadow 시뮬레이션·판정 감사 (#16)

선행: [#15 / PR #34](https://github.com/tokenpliot/token-pilot-cloud-api/pull/34)의 `advisory-v1`, `decision_records`, V8.
V9 적용 후 `token-pilot.shadow.enabled=true`로 활성화한다. 기본값은 false다.
신규 판정을 수집하려면 `token-pilot.decision.enabled=true`와 확인된 `token-pilot.observe.ingestion.rate-unit-tokens`도 필요하다.

## 1. 저장된 판정으로 후보 정책 비교

관리 API 기본 경로는 `/api/shadow/projects/{projectId}`이며 회원 Bearer JWT/OAuth 인증을 사용한다.
조직 OWNER/ADMIN만 생성 가능하며 OPERATOR/VIEWER도 조회할 수 있다. 프로젝트 API Key로 관리 API를 호출할 수 없다.

| 메서드·경로 | 입력 | 결과 (`ApiResponse.data`) |
| --- | --- | --- |
| POST `/runs` | 아래 RunRequest, `Idempotency-Key` | PilotReport |
| GET `/runs` | page=0, size=20 (최대 100) | 최신 보고서 목록 |
| GET `/runs/{runId}` | run ID | PilotReport |
| GET `/runs/{runId}/decisions` | offset=0, limit=50 (최대 200), changedOnly=false, falseDenialOnly=false | matching, nextOffset, items |
| GET `/execution-events` | environment, decisionId, category(선택), page, size | 실제 집행 보고 이력 |
| POST `/rollout-events` | 아래 RolloutRequest, `Idempotency-Key` | Rollout |
| GET `/rollout-events` | environment(생략 시 프로젝트 범위), page, size | revision 내림차순 이력 |

```json
{
  "baselineSnapshotId": "정책 조회 API의 snapshotId",
  "month": "2026-11",
  "candidate": {
    "currency": "USD",
    "monthlySoftLimitUsd": "80.000000",
    "monthlyHardLimitUsd": "100.000000",
    "requestSoftLimitUsd": "0.040000",
    "requestHardLimitUsd": "0.050000",
    "active": true,
    "notificationThresholds": [80]
  },
  "reason": "prod 요청 상한 변경 전 검토"
}
```

- 표본은 해당 UTC 월 `[월초, 다음 달 월초)`에 평가되었고 **선택한 baseline snapshot을 참조한 판정**이다. 전체 사용량이나 다른 정책 버전의 트래픽이 섞이지 않는다. 최대 2,000건을 넘으면 400으로 전체 실행을 거절한다. 조용히 잘라낸 보고서는 저장하지 않는다.
- `AdvisoryReplay`는 #15의 `AdvisoryEvaluator`를 그대로 호출한다. 최초 토큰 상한, 프로젝트·환경별 월 지출, 당시 가격 snapshot, 두 범위의 정책을 재사용하고 선택한 범위의 규칙만 후보로 바꾼다. 다른 범위의 현재 정책을 조회해 섞지 않는다.
- baseline 결과가 원본 응답과 일치해야 후보를 평가한다. 알고리즘 미지원·입력 누락·baseline 불일치는 후보 `INDETERMINATE`와 사유로 남긴다. #15는 비활성 정책의 월 지출을 조회하지 않으므로 비활성→활성 후보도 `INACTIVE_SCOPE_SPEND_NOT_CAPTURED`로 남긴다.
- 금액은 서버 가격표를 쓴 USD 소수 문자열이며 actual 청구액이 아닌 **판정 당시 예상 상한**이다. cache/reasoning 중복 합산 없이 #15의 상한 요율 계산을 재사용한다. SDK 제공 비용으로 재가격하지 않는다.
- 후보 버전은 `shadow:{runId}`이다. 실제 policy head, 예산 projection, 과거 판정·가격 snapshot을 변경하지 않는다. 각 sample에는 decisionId/requestId, 환경, 원본 알고리즘, 가격 snapshot ID/version/checksum, 정책 snapshot·규칙, evaluationInputJson을 보존한다.
- `changed`는 outcome 변경 여부다. 세부 reason·금액 변화는 before/after에서 비교한다. 거부율은 `denied / evaluated`(소수 6자리)이며 INDETERMINATE는 분모에서 제외한다. 분모가 0이면 null이다. `newlyDenied`는 ALLOW→정책 거부다.
- `falseDenialCandidate`는 baseline ALLOW→후보 거부이고 해당 decisionId에 고객의 PROCEEDED 보고가 존재할 때만 true다. 확인된 오차단이나 성공한 호출을 뜻하지 않으며 고객 검토가 필요하다. 집행 보고가 나중에 도착하면 이 표식과 보고서 집행 건수는 늘어날 수 있지만 저장된 시뮬레이션 입력·판정은 바뀌지 않는다.

## 2. 고객 실제 집행 결과 수집

`POST /api/control/v1/execution-events`는 `X-API-Key`(기존 프로젝트 키)와 `Idempotency-Key`로 인증·중복 방지한다.
기본 최대 body 16KiB(`token-pilot.ingestion.max-event-bytes`)이며 모르는 필드는 400으로 거절하고 값은 저장·응답하지 않는다.
원문 prompt/completion, 사용량, 금액은 받지 않는다. 기존 ingestion/Observe 이벤트 계약은 바뀌지 않는다.

```json
{
  "schemaVersion": "2026-10-01",
  "requestId": "customer-request-001",
  "environment": "prod",
  "decisionId": "dec-11111111-2222-3333-4444-555555555555",
  "reportedDecision": "DENY_BUDGET",
  "actualExecution": "PROCEEDED",
  "customerOverride": "ALLOW",
  "customerMode": "ADVISORY",
  "occurredAt": "2026-11-11T08:00:00Z",
  "reasonCode": "CUSTOMER_OVERRIDE"
}
```

- requestId: `[A-Za-z0-9._:-]{1,128}`, environment: 동일 문자 규칙 1~20자. reasonCode: `[A-Z0-9_]{1,64}`.
- actualExecution: PROCEEDED/BLOCKED/FAILED/UNKNOWN. customerOverride: NONE/ALLOW/BLOCK. ALLOW override는 PROCEEDED, BLOCK override는 BLOCKED와 일치해야 한다. customerMode: OBSERVE/ADVISORY/ENFORCE.
- decisionId가 있으면 키의 조직·프로젝트, 판정의 환경·requestId 및 reportedDecision을 서버 원본과 검증한다. 타 프로젝트·환경·요청 기록을 연결할 수 없다. 동일 프로젝트의 키 교체는 허용한다.
- 서버에 도달하지 못한 요청은 decisionId=null로 보내고 reportedDecision은 CONTROL_PLANE_UNAVAILABLE 또는 INDETERMINATE만 허용한다. 이때 serverOutcome=null이다. 서버 판정이 없는 정책 거부를 만들 수 없다.
- occurredAt은 ISO instant(2000년 이후, 수신 시각보다 최대 5분 미래)로 받고 마이크로초 UTC로 정규화한다. recordedAt과 actorId(API Key ID)는 서버가 기록한다.
- categories는 POLICY_DENIAL, SERVER_UNAVAILABLE, CUSTOMER_OVERRIDE, INDETERMINATE로 구분한다. 정책 거부와 override가 동시에 있으면 둘 다 남긴다. 조회 category 필터는 앞의 세 가지를 지원한다.
- 모든 집행 정보의 evidenceSource는 `CUSTOMER_REPORTED_PROJECT_KEY`다. 서버가 고객 코드 실행이나 장애를 관측했다는 보장이 아니다. 동시 재전송도 같은 프로젝트·키·본문이면 같은 event ID, 본문이 다르면 409다. 서로 다른 멱등 키는 별개의 감사 이벤트다.
- 보고서는 표본 decisionId에 연결된 이벤트와 해당 월·범위의 미연결 장애/불확정 이벤트를 센다. `customerFeedbackEvents`는 이벤트 건수이며 유일 요청 수가 아니다. 다른 baseline 보고서에도 같은 미연결 장애 이벤트가 포함될 수 있으므로 보고서 간 건수를 합산하지 않는다.
- 오류는 기존 Control Plane 오류 형식을 사용한다. 입력 오류는 400, 키 없음/무효는 401, 키 환경 제한은 403, 타 프로젝트 판정은 404, 멱등 충돌은 409, 저장소 장애는 503이다. 오류를 정책 거부로 취급하지 않는다.

## 3. Rollout/opt-in 선언과 되돌리기

```json
{
  "expectedVersion": 0,
  "environment": "prod",
  "settings": {
    "mode": "ENFORCE",
    "enforcedOutcomes": ["DENY_BUDGET"],
    "onIndeterminate": "FAIL_OPEN",
    "onUnavailable": "FAIL_OPEN"
  },
  "optIn": true,
  "evidenceRunId": "검토한 runId",
  "reason": "고객이 prod 파일럿 전환을 승인함"
}
```

ENFORCE 선언에는 optIn=true가 필수다. evidenceRunId가 있으면 같은 프로젝트·범위인지 검증한다.
이전 선언, 다음 선언, opt-in 전후 값, 작성자, 서버 시각, 사유를 append-only로 보존한다.
첫 선언의 before/beforeOptIn은 미상(null)이며 OBSERVE였다고 추정하지 않는다. expectedVersion 충돌은 409다.

이 API는 `CUSTOMER_DECLARATION_ONLY` 감사이며 SDK 설정을 배포하거나 강제 집행하지 않는다.
되돌릴 때 고객 SDK를 먼저 OBSERVE/ADVISORY로 전환하고, 최신 revision을 expectedVersion으로 하여 같은 범위에 새 선언을 기록한다.
`evidenceRunId`와 변경 사유를 포함해 원인·복구 이력을 남긴다. 프로젝트와 환경 선언은 각각 별도의 revision이고 상속·우선순위를 자동 계산하지 않는다.

## 4. 배포·검증·롤백

1. #15 V8을 먼저 적용한 뒤 V9의 `shadow_audits`, `decision_execution_events`를 생성한다. 기존 행 변경이나 추정 백필은 없다. 과거 usage만 있고 decision 입력이 없는 요청은 재현 표본으로 만들지 않는다.
2. #15의 가격 단위와 판정 API를 확인하고 Shadow 기능 플래그를 켠다. 기존 API 응답/집행 기본값은 그대로다.
3. 두 새 테이블은 INSERT/SELECT 전용으로 운영하고 삭제·UPDATE 권한은 별도 관리한다. 엔티티는 immutable이며 수정·삭제 API는 없다.
4. JVM과 MySQL Connector의 시간대는 일치시킨다(현재 prod URL은 Asia/Seoul). 기존 LocalDateTime/Timestamp 매핑에서 서로 다르면 DB raw DATETIME과 UTC 계약이 어긋날 수 있다. 이 PR에서 기존 데이터 시간대를 변환하지 않는다.
5. 애플리케이션 롤백은 `token-pilot.shadow.enabled=false`로 수집/관리를 끄고 새 테이블과 V9 이력을 보존한다. 부모 decision_records/프로젝트보다 감사 테이블을 먼저 삭제하는 데이터 손실 롤백은 운영 경로로 제공하지 않는다.

검증: `./gradlew test`. 실제 MySQL은 **비어 있는 일회용 DB**로만 다음을 실행한다(테스트가 fixture를 삭제함).

```sh
SHADOW_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:33416/shadow_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul' \
SHADOW_TEST_MYSQL_USER=root ./gradlew test --tests '*ShadowMySqlIntegrationTest'
```

실행 JVM도 Asia/Seoul인 예다. UTC 환경이면 JVM·Connector 양쪽 모두 UTC로 맞춘다.
이 테스트는 실제 Flyway V1~V9와 Hibernate validate 뒤 H2와 같은 HTTP/서비스 계약, tenant 격리, 동시성, 월 경계를 검증한다.

## 5. 파일럿 보고서 제출 형식

| 항목 | 기록할 내용 |
| --- | --- |
| 대상 | 프로젝트, 환경 또는 프로젝트 전체, UTC 월, runId, 작성자·시각 |
| 비교 근거 | baseline/candidate 버전, 원본 advisory 알고리즘, 가격 snapshot 버전별 표본 수 |
| 표본 품질 | sampled, baselineEvaluated, candidateEvaluated, indeterminate와 사유, baseline 선택 편향 |
| 예상 영향 | 전후 거부 수·거부율, newlyDenied, changed, 주요 reason 코드 |
| 검토 대상 | falseDenialCandidates와 decisionId별 업무상 필요성, 고객 PROCEEDED 보고의 신뢰성 |
| 실제 집행 | 정책 거부/장애/override 이벤트, 키 주체, 서버 판정과 고객 집행의 차이 |
| 결정 | 승인자, opt-in 증빙, 적용 범위·모드·enforcedOutcomes·오류 시 행동 |
| 복구 | 고객 SDK 되돌리기 담당자·절차, rollout revision, 복구 사유·시각 |

`rolloutReadiness`는 평가 가능한 후보가 없으면 INSUFFICIENT_EVIDENCE, 있으면 MANUAL_REVIEW_REQUIRED다.
자동으로 Enforce 전환 가능 판정을 내리지 않는다. 표본 대표성, 불확정 비율, 오차단 후보, 장애/override를 고객과 검토하고 명시적으로 승인한다.
